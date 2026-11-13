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

## API

```java
Window window = OffscreenAPI.open(() -> new MyScreen());
OffscreenAPI.push(window, () -> new OtherScreen());
OffscreenAPI.pop(window);
OffscreenAPI.close(window);
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
