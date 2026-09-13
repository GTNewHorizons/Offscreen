package com.sbancuz.offscreen.integration.nei;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import com.sbancuz.offscreen.mixins.early.GuiContainerAccessor;
import com.sbancuz.offscreen.mixins.late.BookmarkPanelAccessor;
import com.sbancuz.offscreen.mixins.late.ItemPanelsAccessor;
import com.sbancuz.offscreen.mixins.late.LayoutManagerAccessor;
import com.sbancuz.offscreen.scope.Scope;

import codechicken.nei.BookmarkPanel;
import codechicken.nei.Button;
import codechicken.nei.ButtonCycled;
import codechicken.nei.ItemList;
import codechicken.nei.ItemList.ItemsLoadedCallback;
import codechicken.nei.ItemPanel;
import codechicken.nei.ItemQuantityField;
import codechicken.nei.ItemZoom;
import codechicken.nei.LayoutManager;
import codechicken.nei.NEIController;
import codechicken.nei.SearchField;
import codechicken.nei.SubsetWidget;
import codechicken.nei.Widget;
import codechicken.nei.api.ItemFilter;
import codechicken.nei.bookmark.BookmarkStorage;
import codechicken.nei.guihook.GuiContainerManager;
import lombok.NoArgsConstructor;

public final class NeiScope implements Scope {

    private static final Field managerField;
    private static final Map<SearchField, NeiScope> searchFieldScopes = Collections
        .synchronizedMap(new WeakHashMap<>());

    static {
        try {
            managerField = GuiContainer.class.getDeclaredField("manager");
            managerField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("NEI manager field not found", e);
        }
    }

    public static @Nullable GuiContainerManager getManager(final GuiContainer container) {
        try {
            return (GuiContainerManager) managerField.get(container);
        } catch (final IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Supplier<GuiContainer> containerSupplier;
    private final Ctx own = new Ctx();
    private final Ctx saved = new Ctx();
    private int enteredCount = 0;

    public NeiScope(final Supplier<GuiContainer> containerSupplier) {
        this.containerSupplier = containerSupplier;

        saved.capture();
        saved.manager = NEIController.manager;

        BookmarkPanel originalBookmarkPanel = saved.bookmarkPanel;
        final BookmarkStorage originalStorage = ((BookmarkPanelAccessor) originalBookmarkPanel).getStorage();

        final BookmarkPanel offscreenPanel = new BookmarkPanel();
        ((BookmarkPanelAccessor) offscreenPanel).setStorage(originalStorage);

        ItemPanelsAccessor.setItemPanel(new ItemPanel());
        ItemPanelsAccessor.setBookmarkPanel(offscreenPanel);
        LayoutManagerAccessor.invokeInit();
        LayoutManager.getLayoutStyle()
            .init();
        LayoutManager.itemZoom = new InertItemZoom();

        own.capture();

        GuiContainerManager.inputHandlers.remove(own.itemZoom);
        synchronized (ItemList.itemFilterers) {
            ItemList.itemFilterers.remove(own.searchField);
        }

        saved.apply();
        NEIController.manager = saved.manager;

        searchFieldScopes.put(own.searchField, this);

        ItemsLoadedCallback loadCallback = () -> {
            if (enteredCount > 0) {
                filterOffscreenItems(own.searchField.getFilter());
            }
        };
        ItemList.loadCallbacks.add(loadCallback);
    }

    @Override
    public void enter() {
        if (enteredCount++ > 0) return;

        saved.capture();
        saved.manager = NEIController.manager;

        final GuiContainer container = containerSupplier.get();
        if (container != null) {
            own.manager = getManager(container);
        }

        NEIController.manager = own.manager;
        own.apply();
        LayoutManager.bookmarkPanel.update();

        if (container != null) {
            final GuiContainerAccessor accessor = (GuiContainerAccessor) container;
            final int savedLeft = accessor.getGuiLeft();
            if (savedLeft < 82) {
                accessor.setGuiLeft(82);
            }
            LayoutManager.layout(container);
            accessor.setGuiLeft(savedLeft);
        }

        if (!LayoutManager.itemsLoaded) {
            ItemList.loadItems.restart();
        }

        if (ItemList.loadFinished) {
            filterOffscreenItems(own.searchField.getFilter());
        }
    }

    @Override
    public void restore() {
        if (--enteredCount > 0) return;
        if (enteredCount < 0) {
            enteredCount = 0;
            return;
        }

        saved.apply();
        NEIController.manager = saved.manager;
        saved.manager = null;
        saved.clear();
    }

    public static boolean isOffscreenSearchField(SearchField field) {
        return searchFieldScopes.containsKey(field);
    }

    public static void onOffscreenSearchChanged(SearchField field) {
        final NeiScope scope = searchFieldScopes.get(field);
        if (scope != null) {
            scope.filterOffscreenItems(field.getFilter());
        }
    }

    private void filterOffscreenItems(final @Nullable ItemFilter filter) {
        final ArrayList<ItemStack> result = new ArrayList<>();
        for (final ItemStack item : ItemList.items) {
            if (filter == null || filter.matches(item)) {
                result.add(item);
            }
        }
        own.itemPanel.getGrid()
            .setItems(result);
        own.itemPanel.realItems = result;
        final GuiContainer container = containerSupplier.get();
        if (container != null) {
            own.itemPanel.getGrid()
                .refresh(container);
        }
    }

    private static final class InertItemZoom extends ItemZoom {

        @Override
        public void draw(final int mousex, final int mousey) {}

        @Override
        public void resize(final GuiContainer gui) {}

        @Override
        public boolean mouseScrolled(final GuiContainer gui, final int mousex, final int mousey, final int scrolled) {
            return false;
        }

        @Override
        public boolean lastKeyTyped(final GuiContainer gui, final char keyChar, final int keyID) {
            return false;
        }
    }

    @NoArgsConstructor
    private static final class Ctx {

        private static final Ctx NULL_CTX = new Ctx();

        private ItemPanel itemPanel;
        private BookmarkPanel bookmarkPanel;
        private SubsetWidget dropDown;
        private SearchField searchField;
        private ItemZoom itemZoom;
        private ButtonCycled options;
        private ButtonCycled bookmarksButton;
        private Button more;
        private Button less;
        private ItemQuantityField quantity;
        private Button delete;
        private ButtonCycled gamemode;
        private Button rain;
        private ButtonCycled magnet;
        private Button heal;
        private Button[] timeButtons;
        private GuiContainerManager manager;
        private Widget inputFocused;
        private Set<Widget> drawWidgets;
        private Set<Widget> controlWidgets;

        public void capture() {
            this.itemPanel = ItemPanelsAccessor.getItemPanel();
            this.bookmarkPanel = LayoutManager.bookmarkPanel;
            this.dropDown = LayoutManager.dropDown;
            this.searchField = LayoutManager.searchField;
            this.itemZoom = LayoutManager.itemZoom;
            this.options = LayoutManager.options;
            this.bookmarksButton = LayoutManager.bookmarksButton;
            this.more = LayoutManager.more;
            this.less = LayoutManager.less;
            this.quantity = LayoutManager.quantity;
            this.delete = LayoutManager.delete;
            this.gamemode = LayoutManager.gamemode;
            this.rain = LayoutManager.rain;
            this.magnet = LayoutManager.magnet;
            this.heal = LayoutManager.heal;
            this.timeButtons = LayoutManager.timeButtons;
            this.inputFocused = LayoutManager.getInputFocused();
            this.drawWidgets = LayoutManagerAccessor.getDrawWidgets();
            this.controlWidgets = LayoutManagerAccessor.getControlWidgets();
        }

        public void apply() {
            ItemPanelsAccessor.setItemPanel(this.itemPanel);
            ItemPanelsAccessor.setBookmarkPanel(this.bookmarkPanel);
            LayoutManager.itemPanel = this.itemPanel;
            LayoutManager.bookmarkPanel = this.bookmarkPanel;
            LayoutManager.dropDown = this.dropDown;
            LayoutManager.searchField = this.searchField;
            LayoutManager.itemZoom = this.itemZoom;
            LayoutManager.options = this.options;
            LayoutManager.bookmarksButton = this.bookmarksButton;
            LayoutManager.more = this.more;
            LayoutManager.less = this.less;
            LayoutManager.quantity = this.quantity;
            LayoutManager.delete = this.delete;
            LayoutManager.gamemode = this.gamemode;
            LayoutManager.rain = this.rain;
            LayoutManager.magnet = this.magnet;
            LayoutManager.heal = this.heal;
            LayoutManager.timeButtons = this.timeButtons;
            LayoutManager.setInputFocused(this.inputFocused);
            LayoutManagerAccessor.setDrawWidgets(this.drawWidgets);
            LayoutManagerAccessor.setControlWidgets(this.controlWidgets);
        }

        public void clear() {
            swap(NULL_CTX);
        }

        public void swap(Ctx other) {
            this.itemPanel = other.itemPanel;
            this.bookmarkPanel = other.bookmarkPanel;
            this.dropDown = other.dropDown;
            this.searchField = other.searchField;
            this.itemZoom = other.itemZoom;
            this.options = other.options;
            this.bookmarksButton = other.bookmarksButton;
            this.more = other.more;
            this.less = other.less;
            this.quantity = other.quantity;
            this.delete = other.delete;
            this.gamemode = other.gamemode;
            this.rain = other.rain;
            this.magnet = other.magnet;
            this.heal = other.heal;
            this.timeButtons = other.timeButtons;
            this.manager = other.manager;
            this.inputFocused = other.inputFocused;
            this.drawWidgets = other.drawWidgets;
            this.controlWidgets = other.controlWidgets;
        }
    }
}
