package com.sbancuz.offscreen.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.network.play.server.S3APacketTabComplete;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.sbancuz.offscreen.config.ContainerProtocolConfig;
import com.sbancuz.offscreen.mixins.early.GuiContainerAccessor;
import com.sbancuz.offscreen.window.MainWindow;
import com.sbancuz.offscreen.window.input.InputRouter;

/** Exercises cursor safety and switch completion through client packet handling. */
public class ServerContainerMultiplexerTest {

    private static final Minecraft MC = mock(Minecraft.class);
    private static final List<Packet> SENT = new ArrayList<>();
    private static MockedStatic<Minecraft> minecraft;
    private static MockedConstruction<InputRouter> inputRouters;
    private static MockedConstruction<MainWindow> mainWindows;
    private static ServerContainerMultiplexer multiplexer;

    @BeforeClass
    public static void setUpClient() throws ReflectiveOperationException {
        minecraft = mockStatic(Minecraft.class);
        minecraft.when(Minecraft::getMinecraft)
            .thenReturn(MC);
        inputRouters = mockConstruction(InputRouter.class);
        mainWindows = mockConstruction(MainWindow.class);

        final EntityClientPlayerMP player = mock(EntityClientPlayerMP.class);
        final NetHandlerPlayClient network = mock(NetHandlerPlayClient.class);
        final Field sendQueue = EntityClientPlayerMP.class.getField("sendQueue");
        sendQueue.setAccessible(true);
        sendQueue.set(player, network);
        MC.thePlayer = player;
        MC.theWorld = mock(WorldClient.class);
        player.inventory = new InventoryPlayer(player);
        player.inventoryContainer = new InventoryContainer(player.inventory);
        player.openContainer = player.inventoryContainer;
        multiplexer = ServerContainerMultiplexer.INSTANCE;
        doAnswer(call -> {
            final Packet packet = call.getArgument(0);
            multiplexer.onOutboundPacket(packet, () -> SENT.add(packet));
            return null;
        }).when(network)
            .addToSendQueue(any(Packet.class));
    }

    @AfterClass
    public static void closeClient() {
        if (mainWindows != null) mainWindows.close();
        if (inputRouters != null) inputRouters.close();
        if (minecraft != null) minecraft.close();
    }

    @Before
    public void join() {
        ContainerProtocolConfig.barrier = true;
        ContainerProtocolConfig.refill = true;
        MC.thePlayer.inventory.clearInventory(null, -1);
        MC.thePlayer.inventory.setItemStack(null);
        MC.currentScreen = null;
        MC.thePlayer.openContainer = MC.thePlayer.inventoryContainer;
        reset(multiplexer.mainWindow);
        multiplexer.onInboundPacket(new S01PacketJoinGame(), () -> {});
        final C0EPacketClickWindow probe = lastClick();
        multiplexer.onInboundPacket(new S3APacketTabComplete(new String[0]), () -> {});
        confirm(probe, true);
        assertTrue(multiplexer.isIdle());
        SENT.clear();
    }

    @Test
    public void apiOpenWaitsUntilCursorIsParkedAndAccepted() {
        MC.thePlayer.inventory.setItemStack(new ItemStack(new Item()));
        final List<ItemStack> cursorAtOpen = new ArrayList<>();

        multiplexer.openInWindow(
            () -> null,
            () -> cursorAtOpen.add(ItemStack.copyItemStack(MC.thePlayer.inventory.getItemStack())));

        assertTrue("the opener must wait for the parking confirmation", cursorAtOpen.isEmpty());
        final C0EPacketClickWindow park = lastClick();
        assertFalse(multiplexer.isIdle());
        confirm(park, true);
        assertEquals("the opener must run after an accepted park", 1, cursorAtOpen.size());
        assertNull("the opener must see an empty cursor", cursorAtOpen.get(0));
    }

    @Test
    public void apiOpenRefusesWhenNoInventorySlotCanHoldTheCursor() {
        final Item item = new Item();
        for (int i = 0; i < 36; i++) MC.thePlayer.inventory.setInventorySlotContents(i, new ItemStack(item));
        MC.thePlayer.inventory.setItemStack(new ItemStack(item));
        final List<ItemStack> cursorAtOpen = new ArrayList<>();

        multiplexer.openInWindow(() -> null, () -> cursorAtOpen.add(MC.thePlayer.inventory.getItemStack()));

        assertTrue(cursorAtOpen.isEmpty());
        assertTrue("a refused park must send no packets", SENT.isEmpty());
        assertTrue(multiplexer.isIdle());
    }

    @Test
    public void apiOpenStopsAfterTheServerRejectsParking() {
        final ItemStack stack = new ItemStack(new Item());
        MC.thePlayer.inventory.setItemStack(stack.copy());
        final List<ItemStack> cursorAtOpen = new ArrayList<>();
        multiplexer.openInWindow(() -> null, () -> cursorAtOpen.add(MC.thePlayer.inventory.getItemStack()));
        final C0EPacketClickWindow park = lastClick();

        confirm(park, false);
        MC.thePlayer.inventory.setInventorySlotContents(park.func_149544_d(), null);
        applyCursorResync(stack);

        assertTrue(cursorAtOpen.isEmpty());
        assertTrue(multiplexer.isIdle());
        assertTrue(ItemStack.areItemStacksEqual(stack, MC.thePlayer.inventory.getItemStack()));
    }

    @Test
    public void apiOpenKeepsTheCursorParkedWhenLeavingAnotherContainer() {
        openContainer(7);
        final ItemStack stack = new ItemStack(new Item());
        MC.thePlayer.inventory.setItemStack(stack.copy());
        final List<ItemStack> cursorAtOpen = new ArrayList<>();
        multiplexer.openInWindow(
            () -> null,
            () -> cursorAtOpen.add(ItemStack.copyItemStack(MC.thePlayer.inventory.getItemStack())));
        final C0EPacketClickWindow park = lastClick();

        confirm(park, true);
        confirm(lastClick(), true);
        confirm(lastClick(), false);
        applyCursorResync(null);

        assertEquals(1, cursorAtOpen.size());
        assertNull(cursorAtOpen.get(0));
        assertTrue(ItemStack.areItemStacksEqual(stack, MC.thePlayer.inventory.getStackInSlot(park.func_149544_d())));
    }

    @Test
    public void serverCloseDuringParkingCancelsTheSwitchAndRestoresInput() {
        openContainer(7);
        MC.thePlayer.inventory.setItemStack(new ItemStack(new Item()));
        assertEquals(OverlayHint.NONE, multiplexer.switchTo(multiplexer.playerInventory(), false, ServerProbes.IGNORE));

        multiplexer.onInboundPacket(new S2EPacketCloseWindow(7), () -> {});

        assertFalse(
            "a closed source must not leave a switch waiting for its unanswered click",
            multiplexer.isSwitching());
        assertEquals(0, lastClick().func_149548_c());
        confirm(lastClick(), false);
        applyCursorResync(null);
        assertTrue("the inventory must receive input after resynchronization", multiplexer.isIdle());
        assertTrue(multiplexer.idleAtPlayerInventory());
    }

    @Test
    public void serverCloseAfterReleaseLetsTheSwitchFinish() {
        openContainer(7);
        final List<Boolean> completed = new ArrayList<>();
        assertEquals(OverlayHint.NONE, multiplexer.switchTo(multiplexer.playerInventory(), false, completed::add));
        final C0EPacketClickWindow inventoryProbe = lastClick();

        multiplexer.onInboundPacket(new S2EPacketCloseWindow(7), () -> {});

        assertTrue(multiplexer.isSwitching());
        confirm(inventoryProbe, true);
        confirm(lastClick(), false);
        applyCursorResync(null);
        assertTrue(multiplexer.idleAtPlayerInventory());
        assertFalse(multiplexer.isSwitching());
        assertEquals(List.of(true), completed);
    }

    @Test
    public void heldClickRejectsChangedSlotTagsButAcceptsAnUnchangedCopy() {
        try (MockedStatic<GuiScreen> screens = mockStatic(GuiScreen.class)) {
            final ItemStack original = new ItemStack(new Item()).setStackDisplayName("first book");
            MC.thePlayer.inventory.setInventorySlotContents(0, original);
            final Slot slot = new Slot(MC.thePlayer.inventory, 0, 0, 0);
            final GuiScreen screen = mock(GuiScreen.class, withSettings().extraInterfaces(GuiContainerAccessor.class));
            when(((GuiContainerAccessor) screen).offscreen$getSlotAtPosition(10, 10)).thenReturn(slot);
            final ScreenView view = mock(ScreenView.class);
            when(view.screen()).thenReturn(screen);
            final HeldClick click = new HeldClick(view, 10, 10, 0);

            MC.thePlayer.inventory.setInventorySlotContents(0, original.copy());
            assertTrue(click.meansTheSameOn(screen));
            MC.thePlayer.inventory.setInventorySlotContents(
                0,
                original.copy()
                    .setStackDisplayName("second book"));
            assertFalse("a remembered click must not act on an item with different NBT", click.meansTheSameOn(screen));
        }
    }

    private static void openContainer(final int windowId) {
        final Container container = new InventoryContainer(MC.thePlayer.inventory);
        container.windowId = windowId;
        final GuiContainer screen = mock(GuiContainer.class);
        screen.inventorySlots = container;
        when(multiplexer.mainWindow.hasViewOf(any(ServerContainer.class))).thenReturn(true);
        multiplexer.onServerOpened(container, screen);
        confirm(lastClick(), true);
        assertTrue(multiplexer.isIdle());
        SENT.clear();
    }

    private static void applyCursorResync(final ItemStack stack) {
        multiplexer
            .onInboundPacket(new S2FPacketSetSlot(-1, -1, stack), () -> MC.thePlayer.inventory.setItemStack(stack));
    }

    private static C0EPacketClickWindow lastClick() {
        return SENT.stream()
            .filter(C0EPacketClickWindow.class::isInstance)
            .map(C0EPacketClickWindow.class::cast)
            .reduce((first, last) -> last)
            .orElseThrow();
    }

    private static void confirm(final C0EPacketClickWindow click, final boolean accepted) {
        multiplexer.onInboundPacket(
            new S32PacketConfirmTransaction(click.func_149548_c(), click.func_149547_f(), accepted),
            () -> {});
    }

    private static final class InventoryContainer extends Container {

        InventoryContainer(final InventoryPlayer inventory) {
            for (int i = 0; i < 36; i++) addSlotToContainer(new Slot(inventory, i, 0, 0));
        }

        @Override
        public boolean canInteractWith(final EntityPlayer player) {
            return true;
        }
    }
}
