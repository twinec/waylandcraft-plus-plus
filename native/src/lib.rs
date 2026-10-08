// Each platform implements the natives of WaylandCraftBridge with its own backend:
// a Wayland compositor on Linux, and window capture plus input injection on Windows.
cfg_if::cfg_if! {
    if #[cfg(target_os = "linux")] {
        include!("linux.rs");
    } else if #[cfg(target_os = "windows")] {
        mod win32;
    }
}
