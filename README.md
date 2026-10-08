![waylandcraft banner](/assets/title_scaled.png)

Wayland Compositor in Minecraft

[Demo video](https://youtu.be/cTkEM7b0IQw)

Coming soon to a [Modrinth](https://modrinth.com/mod/waylandcraft_plus_plus) near you!

> **This branch adds read-only window sharing:** other players can watch and hear a window you share, e.g. to
> watch YouTube videos together or show a terminal. See [Window sharing](#can-other-players-see-my-windows-window-sharing)
> below and [TODO-SHARING.md](TODO-SHARING.md). Ported from
> [meltingscales/waylandcraft-neoforge-1.21.1](https://github.com/meltingscales/waylandcraft-neoforge-1.21.1),
> written with major help from an LLM (Claude Code).

## System dependencies
- OS: Linux, or Windows 10 1903+ / Windows 11 (x86_64, see below)
- Minecraft 26.2
- Fabric mod loader
- xkbcommon library 1.11.0 (Linux only)
- xkbcommon tools (xkbcli) (Linux only)
- xwayland-satellite (for Xwayland support)
- For sharing window audio: PipeWire (`pw-record`, `pw-dump`), and `xprop` for X11 apps

Additionally recommended:
- Prism Launcher
- Sodium
- Polymer

## Important notes for installing / using!!!
1. Do not use a Minecraft launcher packaged as a flatpak! You won't be able to use your apps.
2. For nvidia: Set the `__GL_THREADED_OPTIMIZATIONS` environment variable to `0` in your launcher.
3. The Zink OpenGL driver has been known to cause issues. Use native OpenGL instead.

## Frequently Asked Questions
### How do I use this thing?
Download the mod from the releases section, install Minecraft Fabric for 26.2 and drag the jar file in your mods folder.
Look at your keybind settings. By default `V` opens the app launcher, `G` enables keyboard capture allowing you to type in
the windows, `B` opens the window manager screen.

### How can I press Escape in the windows?
Instead of using `G` to capture the keyboard, use `ALT+Q` instead. The only way to turn it off is to press `ALT-Q` again,
so the `ESC` key is forwarded to the application.

### Does it work on Windows?
Yes, on Windows 10 1903 or newer (x86_64). Instead of running a Wayland compositor, the mod captures real desktop
windows and forwards keyboard and mouse input to them, while Minecraft keeps focus.
- The app launcher (`V`) lists the same apps as Start's "All apps", including Store apps. Windows of apps you start
  from it appear in the game; other desktop windows don't. Set the environment variable `WAYLANDCRAFT_WINDOWS=all`
  to show every window instead.
- Windows 11 captures with Windows.Graphics.Capture without the yellow capture border. Older Windows 10 versions
  can't hide that border, so they use PrintWindow instead, which is slower but borderless.
  `WAYLANDCRAFT_CAPTURE=wgc` or `WAYLANDCRAFT_CAPTURE=printwindow` forces one method.
- Apps open without taking focus from the game, and their real windows are kept behind the game window, where they
  keep drawing, and made almost fully transparent. Minimized apps keep showing their last frame. Set
  `WAYLANDCRAFT_HIDE_WINDOWS=behind` to skip the transparency, `=offscreen` to move them past the edge of the desktop
  instead (some apps, like Notepad, stop drawing there), or `=0` to leave them alone.
- Quitting the game closes the apps you started from it (they can still ask to save). Set `WAYLANDCRAFT_KEEP_APPS=1`
  to keep them open.
- The app list and icons are cached in `%LOCALAPPDATA%\WaylandCraft`, so the launcher fills instantly after the first
  start. Apps installed since the last start show up the start after.
- Window sharing works like on Linux. Sharing a window's audio needs Windows 10 2004 or newer; it carries everything
  the app (and its child processes) plays.
- Windows 11's newer app UI (like Notepad's tabs and menus) ignores the clicks the mod sends, so clicks there press
  the control under the pointer through UI Automation instead. Dragging doesn't work in those parts.
- Known limits: keyboard shortcuts that rely on held modifiers (like Ctrl+S) may not reach every app, and there is no
  relative mouse mode for 3D games yet.

### How do I run X11 apps?
Since v2.0.0 waylandcraft has integrated support for [xwayland-satellite](https://github.com/Supreeeme/xwayland-satellite).
If you have the binary installed on your system, it should automatically be started.

### How to do the relative mouse movement thing for 3D games?
Move your mouse over the window, then activate the hard keyboard capture mode. (`ALT-Q`)
Exiting the hard keyboard capture mode releases the mouse.

### Can other players see my windows? (window sharing)
Yes, if you choose to share them. Sharing is read-only: others can watch and hear, but can't interact.
1. Open the window manager (`B`), select the window's tab and click the **share** (broadcast) button. Click it again to stop.
   `N` toggles sharing of the most recently focused window as a shortcut.
2. Put the window's item in an item frame, or place the window in the world as usual. Other players see and hear it there.

Shared windows get a red ● in the window manager's tabs, and the HUD lists everything you share.
Video is only sent while someone can actually see the window. Audio (the shared app's own PipeWire stream, never
your microphone) plays in stereo from the window to players within 24 blocks. The server needs this mod for sharing;
players on any OS can watch.

To try it alone, run `/waylandcraft testpattern` (needs cheats): it spawns a test window with color bars and a beep
every second (the border flashes with each beep, and beeps alternate left/right). `/waylandcraft testpattern stop`
removes it. Design notes and known limitations are in [TODO-SHARING.md](TODO-SHARING.md).

### But can I use it on a server though?
You can, but because it's a client-side mod, other players won't see your windows or be able to interact with them.
Servers can opt to install the mod, which will allow players to use the window items for themselves.
If the server doesn't support it, you can spawn a window in the world by going into the wm screen (default bind `B`)
and then pressing and holding the "Grab" button.

### Does this work in VR?
Depending on your VR mod, you can probably get the windows to display fine but you probably won't be able to interact with
the windows using your controller. Soooo, kinda.

### Does this work with shaders?
Since v2.0.2 Iris shaders are supported.

There are a couple of downsides though: The windows might have large borders and text in windows will be harder to read from a distance (because window anti-aliasing doesn't work)

This is because for the shader support windows are rendered with the same pipeline as entities because otherwise the shaders would ignore them.

For some shaders you might need to disable features like Temporal Anti Aliasing (TAA).

## Building and Running
You need a Rust development environment and a Java 25 SDK, plus `x264` (library and headers), `clang`/`libclang`
and `pkg-config` for the native library's window sharing encoder (release builds link x264 statically; see
[TODO-SHARING.md](TODO-SHARING.md#building)).
```sh
./build.sh #all arguments are passed to cargo build
```

The final jar file will be in `build/libs`, or run `./gradlew runClient`
for a development environment


## Images
![screenshot](/assets/screenshot.png)

## Disclaimer
This compositor still has lots of issues and bugs. Use it at your own risk or whatever.

## Contribution Policy
All contributions have to be made an accordance with the GPLv3 license (see `LICENSE`).
~~Waylandcraft has some important policy around LLMs and Degenerative AI, mostly because of code and contribution quality as well as some ethical and copyright concerns.
Mergeable contributions made to the repository in the form of pull requests need to be made **without major usage** of LLMs.~~

~~If you feel as though you have something worthwhile to contribute which was made using LLMs **please disclose it** and file it as a **draft** pull request instead.
It will probably have to be more closely examined or even entirely rewritten by a human programmer, which can then be (re-)submitted as a normal pull request.~~
