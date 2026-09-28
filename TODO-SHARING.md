# Read-only window sharing

Other players can **watch and hear** a window someone chooses to share. The target use
cases are **watching videos together** (a browser playing YouTube) and **terminals**. A
shared window shows up wherever its owner shows it: in an item frame, or as a
free-floating in-world window. It's read-only: input only ever goes to the owner's local
compositor, and this adds no input path.

**Origin:** this is a port of the sharing feature from the NeoForge 1.21.1 fork
[meltingscales/waylandcraft-neoforge-1.21.1](https://github.com/meltingscales/waylandcraft-neoforge-1.21.1)
(branch `window-sharing`) to WaylandCraft++ on Fabric / Minecraft 26.2. Like that
fork, it was written with major help from an LLM (Claude Code).

## Using it

1. **Server:** needs the mod for sharing. Singleplayer opened to LAN works too. Players
   without the mod, or on a version without sharing, are simply never sent sharing data.
2. **Owner:**
   - Open the Window Manager (**B**), select the window's tab, and click the **share**
     button (the broadcast icon under "Give Window Item"). Click it again to stop.
   - Shared windows get a red **●** in front of their tab title, and the HUD lists them
     ("● Sharing: title").
   - **N** (Toggle Window Sharing) toggles the most recently focused window as a
     shortcut. A chat message confirms every start and stop.
3. **Show it:** put the window's item in an item frame, or place the window in the world
   as usual. Other players see and hear it there.
4. **To stop:** press the button or **N** again, close the window, or log out.

### Test pattern: checking it alone

`/waylandcraft testpattern` (operators, or cheats on in singleplayer) starts a
server-generated fake shared window. It doesn't need a second player or a real window.
- **Where it shows:** floating in front of you. You also get a "Test Pattern N" window
  item that shows the same stream in an item frame.
- **How it behaves:** like a real owner. It only generates video while you can see it,
  and audio while you're within 24 blocks, and it answers key frame requests.
- **What it plays:** color bars with a sweeping black box, plus an 880 Hz beep every
  second that alternates left and right. The border flashes white on every beep.
- **What to check:**
  - **Sync:** the flash coincides with the beep.
  - **Stereo:** facing the window, beeps alternate between its left and right edge.
  - **Smart streaming:** looking away or putting a block in between freezes the picture,
    but the beeps continue.
  - **Late join:** looking back resumes the picture from a requested key frame.
- **To stop:** `/waylandcraft testpattern stop` removes all test patterns.

## How it works

```
owner client                         server                           viewer clients
------------                         ------                           --------------
toggle sharing  --ShareState-->   tracks shared windows and where
                --DisplayPose-->  they are shown (floating displays   --StreamPose-->  render floating
                                  reported by the owner; item                          windows in the world
                                  frames scanned every 10 ticks)
                                                         <--Watch---  shared windows in view and in
                                                                      line of sight (every 5 ticks)
                                  re-checks each claim itself
                                  (distance + block raycast)
                <--Demand-------  video: any validated watcher?
                                  audio: anyone within 24 blocks?
                <--KeyFrameReq--  a new watcher joined
video, only while watched AND the window changed:
  x264 H.264 <=854 px, <=15 fps; one lossless PNG <=1600 px after 600 ms unchanged
  --VideoChunk-->                 relays to validated watchers,     --StreamVideo-->  reassemble, JCodec decode,
                                  within each viewer's byte budget                    show when the audio
                                                                                      clock reaches the frame
audio, only while someone is near:
  pw-record of the app's own PipeWire stream -> stereo Opus
  --AudioChunk-->                 relays to players in range        --StreamAudio-->  two OpenAL sources at the
                                                                                      window's left/right edges
```

- **Video codec:** the owner encodes H.264 with **x264**, linked into the native library
  (`native/src/h264.rs`, `NativeH264Encoder`). Settings: baseline profile, zero latency,
  constant QP 30, key frames only on request.
  - **Viewers** decode with **JCodec** (pure Java, bundled), so viewers on any OS can watch.
  - **Fallback:** if the native encoder is unavailable, the owner uses JCodec's encoder.
  - **Size:** measured at 848×480, x264 frames after the first are about 1.5 KB for
    panning video, 8.7 KB for a scrolling terminal and 1.8 KB for typing. Motion JPEG
    was 40–120 KB per frame.
- **Change-driven:** `WindowFramebuffer` counts content changes (surface damage or a
  resize). Nothing is captured while the window is idle.
- **Capture on Minecraft 26.2:** the window framebuffer is downscaled with a render pass
  (`pipeline/share_capture`: screenquad plus blit, no blending). It's then read back with
  `CommandEncoder.copyTextureToBuffer` and a mapped `GpuBuffer`, the same way vanilla
  takes screenshots. That works on both the OpenGL and Vulkan backends. Readback rows
  come back in texture order, and texture row 0 is the window's top row, so no flip is
  needed.
- **Key frame recovery:** the server drops whole frames over a viewer's budget (2 MB/s,
  4 MB burst). After a dropped key or delta frame it holds that viewer's deltas until the
  next key frame. New watchers start in that state and trigger a key frame request.
  Viewers also skip deltas after any incomplete frame.
- **Audio:** Wayland doesn't carry audio, so audio is matched per window.
  1. The owner gets the client's pid from a new `toplevelPID` JNI call. For X11 apps it
     uses the matching window's `_NET_WM_PID`, through `xprop`.
  2. `pw-dump` finds that process's (or a child's) `Stream/Output/Audio` node.
  3. `pw-record --target` records only that stream, with `node.dont-fallback` and
     `node.dont-reconnect`, so it never falls back to the microphone.
  4. It's encoded as stereo Opus at 96 kb/s with Concentus (pure Java, bundled).
- **A/V sync:** frames and audio packets carry the owner's capture time. Viewers show
  each frame when the audio playback clock (queued packet timestamps plus the OpenAL
  sample offset) reaches it.
- **Viewer visibility:** a view-cone test (camera direction, FOV and aspect ratio, plus
  the window's angular size) combined with a block raycast.
- **Viewer rendering:**
  - **Item frames** use the existing render-state hook: `IMyItemFrameRenderState` also
    carries the shared window's key, and `ItemFrameRendererMixin` draws it like a local
    window.
  - **Floating windows** are submitted in `LevelRenderEvents.COLLECT_SUBMITS`.
  - Both use `RenderUtils.FramebufferRenderInstanceEntity` with
    `RenderTypes.entityCutoutCull`, the same path local windows take under shader packs.
- **Non-Linux clients:** the viewer hooks are registered before the platform check, so
  Windows and macOS players with the mod can watch shared windows.

## Verified

- **Build:** the mod builds (JCodec and Concentus nested under `META-INF/jars/`), and the
  dev client (`runClient`) starts cleanly with sharing registered. Mixins apply, the
  capture pipeline registers, and the compositor starts.
- **Native encoder:** x264 round-tripped through the native library, `VideoDecoder` and
  a 26.2 `NativeImage`. Mean color error was 0.7–0.9/255, deltas were about 100–200
  bytes, and on-demand key frames worked.
- **Test pattern:** `TestPatternSource` driven for 2 s against a stub server, decoded
  with the real 26.2 classes. All frames decoded, the border flash matched the beep
  timing in 17/17 frames, all seven bar colors were right, and the beeps landed on the
  expected channels.
- **Release build:** the release-profile native library, built in Ubuntu 22.04 with
  static x264 (as the workflows now do), has no `libx264` runtime dependency, needs
  glibc 2.34, and exports the new functions.

**Not yet tested in game on 26.2:** the test pattern in a world, the GPU readback
capture of a real window (owner side), two players, the Vulkan backend, X11 app audio,
and Iris.

## Building

- **Native library requirements:** `x264` (library and headers), `clang`/`libclang` (for
  bindgen) and `pkg-config`.
- **Dev builds** link the system `libx264` dynamically.
- **Release builds** link a static, position-independent x264. See
  `.github/workflows/release.yml`: build x264 with `--enable-static --enable-pic`, then
  `cargo build` with `SYSTEM_DEPS_X264_LINK=static` and `PKG_CONFIG_PATH` pointing at it.
- **Licenses:** x264 is GPLv2+, compatible with the mod's GPLv3. JCodec is BSD, and
  Concentus is BSD-style (an Opus port).

## Known limitations

- Audio and video are shared together.
- Frames are flattened to opaque, so translucent backgrounds show over black.
- Only one audio stream per app is captured.
- A/V sync follows the audio clock only, with no long-term drift correction.
- Floating placements are trusted within 64 blocks of their owner.
- Everything is baseline profile, because the JCodec decoder needs it.
