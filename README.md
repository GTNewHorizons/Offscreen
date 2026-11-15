# Offscreen

Minecraft 1.7.10 client-only mod. Open any GUI screens in their own OS windows.

## Requirements

- lwjgl3ify
- GTNHLib
- Angelica

## Supports

- Native Minecraft UIs (duh)
- ModularUI2
- NEI
- Any modded screen that doesn't heavily rely on static variables

## Usage

Press F7 with a screen open to move it to a new window. Press F7 in that window to move it back.

Container GUIs (chests, machines, the inventory) work in any window, several at once. The server keeps only one
container open per player, so one of them is live and the others are dimmed snapshots: click a snapshot to make
it the live one, or press F6 over it to refresh its contents.

## API

```java
Window window = OffscreenAPI.open(() -> new MyScreen());
OffscreenAPI.push(window, () -> new OtherScreen());
OffscreenAPI.pop(window);
OffscreenAPI.close(window);
```

Open a GUI the server opens (a mod packet, a GUI factory's client open) in its own window:

```java
OffscreenAPI.open("My machine", () -> MyNetwork.requestGui(x, y, z));
```

Run code inside a window, with `mc.currentScreen` and the display size set to that window:

```java
window.runInScope(() -> ...);
```

Screens that need custom hosting can register a `HostUI`:

```java
UIRegistry.register(MyScreen.class, screen -> new MyHostUI((MyScreen) screen));
```

## Building

```
./gradlew build
```

## License

MIT
