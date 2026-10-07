// Which desktop windows appear in the game, and their captured contents.
//
// Windows of apps started from the in-game launcher are shown (their process and its
// child processes), plus the first new window that shows up after launching an app
// whose process Windows doesn't report. WAYLANDCRAFT_WINDOWS=all shows every window.
// Owned popups without a title bar (menus, dropdowns, tooltips) become popups of
// their owner, the rest become toplevels.
//
// Windows has no way to make an app draw only into the game, so the real windows of
// launched apps are moved past the edge of the desktop, where they keep rendering and
// being captured, and the game window gets the focus back. They return to where they
// were when the game exits. WAYLANDCRAFT_HIDE_WINDOWS=0 leaves them on the desktop.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use ::windows::Win32::Foundation::{CloseHandle, HWND, LPARAM, POINT, RECT};
use ::windows::Win32::Graphics::Dwm::{DWMWA_CLOAKED, DwmGetWindowAttribute};
use ::windows::Win32::Graphics::Gdi::ClientToScreen;
use ::windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx};
use ::windows::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, PROCESSENTRY32W, Process32FirstW, Process32NextW,
    TH32CS_SNAPPROCESS,
};
use ::windows::Win32::System::Threading::{
    GetCurrentProcessId, OpenProcess, PROCESS_NAME_WIN32,
    PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW,
};
use ::windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GW_OWNER, GWL_EXSTYLE, GWL_STYLE, GetClassNameW, GetClientRect,
    GetForegroundWindow, GetSystemMetrics, GetWindow, GetWindowLongW, GetWindowRect,
    GetWindowTextW, GetWindowThreadProcessId, IsIconic, IsWindow, IsWindowVisible,
    IsZoomed, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN,
    SM_YVIRTUALSCREEN, SW_SHOWNOACTIVATE, SWP_NOACTIVATE, SWP_NOMOVE,
    SWP_NOOWNERZORDER, SWP_NOSIZE, SWP_NOZORDER, SetForegroundWindow, SetWindowPos,
    ShowWindow, WS_CAPTION, WS_EX_TOOLWINDOW, WS_POPUP,
};
use ::windows::core::{BOOL, PWSTR};

use super::capture::{CaptureMethod, D3D, Frame, WindowCapture};
use super::input::InputState;

const PROCESS_SCAN_INTERVAL: Duration = Duration::from_secs(1);
// How long after a launch a new window is assumed to belong to the launched app
const LAUNCH_ADOPT_WINDOW: Duration = Duration::from_secs(15);

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Toplevel,
    Popup { parent: HWND },
}

pub struct TrackedWindow {
    pub hwnd: HWND,
    pub kind: Kind,
    pub pid: u32,
    pub alive: bool,
    pub title: String,
    pub app_id: String,
    capture: WindowCapture,
    pub frame: Option<Frame>,
    pub frame_new: bool,
    /// Screen position of the captured frame's top-left pixel
    pub origin: POINT,
    /// The client area within the frame: x, y, width, height
    pub geometry: [i32; 4],
    /// For popups: position relative to the parent's window geometry
    pub popup_offset: [i32; 2],
}

impl TrackedWindow {
    pub fn handle(&self) -> i64 {
        self as *const TrackedWindow as i64
    }

    /// Each window has one surface. Its handle is the window handle with the low
    /// bit set, which never collides because the windows are 8-byte aligned.
    pub fn surface_handle(&self) -> i64 {
        self.handle() | 1
    }

    /// Screen position of the window geometry's top-left corner
    fn geometry_origin(&self) -> POINT {
        POINT {
            x: self.origin.x + self.geometry[0],
            y: self.origin.y + self.geometry[1],
        }
    }

    /// Converts a point in surface (captured frame) coordinates to client coordinates.
    pub fn surface_to_client(&self, x: f64, y: f64) -> POINT {
        let client = client_origin(self.hwnd);
        POINT {
            x: (self.origin.x as f64 + x).floor() as i32 - client.x,
            y: (self.origin.y as f64 + y).floor() as i32 - client.y,
        }
    }

    /// Resizes the window so its client area gets the given size.
    pub fn resize(&self, width: i32, height: i32) {
        let mut window = RECT::default();
        let mut client = RECT::default();
        unsafe {
            if GetWindowRect(self.hwnd, &mut window).is_err()
                || GetClientRect(self.hwnd, &mut client).is_err()
            {
                return;
            }
        }
        let extra_w = (window.right - window.left) - (client.right - client.left);
        let extra_h = (window.bottom - window.top) - (client.bottom - client.top);
        let _ = unsafe {
            SetWindowPos(
                self.hwnd,
                None,
                0,
                0,
                width + extra_w,
                height + extra_h,
                SWP_NOMOVE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_NOACTIVATE,
            )
        };
    }
}

pub struct Instance {
    method: CaptureMethod,
    d3d: Option<D3D>,
    #[allow(clippy::vec_box)]
    pub windows: Vec<Box<TrackedWindow>>,
    own_pid: u32,
    capture_all: bool,
    /// Processes of launched apps and their children
    tracked_pids: HashSet<u32>,
    /// Windows adopted after a launch without a known process
    adopted: HashSet<isize>,
    /// Windows that existed when an app without a known process was launched
    pending_launch: Option<(Instant, HashSet<isize>)>,
    last_process_scan: Option<Instant>,
    /// Whether the real windows of launched apps are moved off the desktop
    hide_windows: bool,
    /// Where each hidden window was before it was moved, to put it back on exit
    hidden: HashMap<isize, POINT>,
    /// The game's own window, which gets the focus back from launched apps
    game_window: Option<HWND>,
    pub input: InputState,
    pub pointer_focus: Option<HWND>,
    pub keyboard_focus: Option<HWND>,
    pub keyboard_active: bool,
    pub output_size: (i32, i32),
    pub output_bounds: (i32, i32),
    serial: i32,
}

impl Instance {
    pub fn new() -> Instance {
        // WGC and the shell need COM on this (the render) thread. Single-threaded, so
        // anything else in the game that initializes COM here keeps working.
        let _ = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };

        let method = super::capture::choose_method();
        let d3d = match method {
            CaptureMethod::Wgc => match D3D::new() {
                Ok(d3d) => Some(d3d),
                Err(e) => {
                    eprintln!("[waylandcraft] Direct3D 11 unavailable, using PrintWindow: {e}");
                    None
                }
            },
            CaptureMethod::PrintWindow => None,
        };
        let method = if d3d.is_some() { method } else { CaptureMethod::PrintWindow };
        eprintln!("[waylandcraft] Capturing windows with {method:?}");

        let capture_all = std::env::var("WAYLANDCRAFT_WINDOWS").as_deref() == Ok("all");
        let hide_windows =
            !capture_all && std::env::var("WAYLANDCRAFT_HIDE_WINDOWS").as_deref() != Ok("0");

        Instance {
            method,
            d3d,
            windows: vec![],
            own_pid: unsafe { GetCurrentProcessId() },
            capture_all,
            tracked_pids: HashSet::new(),
            adopted: HashSet::new(),
            pending_launch: None,
            last_process_scan: None,
            hide_windows,
            hidden: HashMap::new(),
            game_window: None,
            input: InputState::default(),
            pointer_focus: None,
            keyboard_focus: None,
            keyboard_active: false,
            output_size: (1280, 720),
            output_bounds: (1280, 720),
            serial: 0,
        }
    }

    pub fn method(&self) -> CaptureMethod {
        self.method
    }

    pub fn next_serial(&mut self) -> i32 {
        self.serial = self.serial.wrapping_add(1);
        self.serial
    }

    pub fn track_process(&mut self, pid: u32) {
        self.tracked_pids.insert(pid);
        self.last_process_scan = None;
    }

    pub fn expect_unknown_launch(&mut self) {
        let existing = enum_windows().into_iter().map(|h| h.0 as isize).collect();
        self.pending_launch = Some((Instant::now(), existing));
    }

    pub fn find(&self, handle: i64) -> Option<&TrackedWindow> {
        self.windows
            .iter()
            .find(|w| w.handle() == handle || w.surface_handle() == handle)
            .map(|w| &**w)
    }

    pub fn find_hwnd(&self, hwnd: HWND) -> Option<&TrackedWindow> {
        self.windows.iter().find(|w| w.alive && w.hwnd == hwnd).map(|w| &**w)
    }

    pub fn find_mut(&mut self, handle: i64) -> Option<&mut TrackedWindow> {
        self.windows
            .iter_mut()
            .find(|w| w.handle() == handle || w.surface_handle() == handle)
            .map(|w| &mut **w)
    }

    pub fn free(&mut self, handle: i64) {
        self.windows.retain(|w| w.handle() != handle);
    }

    pub fn update(&mut self) {
        if !self.tracked_pids.is_empty()
            && self
                .last_process_scan
                .is_none_or(|t| t.elapsed() >= PROCESS_SCAN_INTERVAL)
        {
            self.tracked_pids = with_child_processes(&self.tracked_pids);
            self.last_process_scan = Some(Instant::now());
        }

        if let Some((since, _)) = &self.pending_launch
            && since.elapsed() > LAUNCH_ADOPT_WINDOW
        {
            self.pending_launch = None;
        }

        let all = enum_windows();
        let mut wanted: Vec<(HWND, Kind, u32)> = vec![];

        // Toplevels first, so popups can find their parents
        for &hwnd in &all {
            let Some(info) = WindowInfo::read(hwnd) else { continue };
            if info.pid == self.own_pid && self.game_window.is_none() && !info.is_popup() {
                self.game_window = Some(hwnd);
            }
            if info.pid == self.own_pid || info.is_popup() || info.tool {
                continue;
            }

            let key = hwnd.0 as isize;
            let mut show = self.capture_all
                || self.tracked_pids.contains(&info.pid)
                || self.adopted.contains(&key);

            if !show
                && let Some((_, existing)) = &self.pending_launch
                && !existing.contains(&key)
                && !info.title.is_empty()
            {
                self.adopted.insert(key);
                self.pending_launch = None;
                show = true;
            }

            if show {
                wanted.push((hwnd, Kind::Toplevel, info.pid));
            }
        }

        for &hwnd in &all {
            let Some(info) = WindowInfo::read(hwnd) else { continue };
            if info.pid == self.own_pid || !info.is_popup() {
                continue;
            }

            let wanted_has = |h: HWND, wanted: &Vec<(HWND, Kind, u32)>| {
                wanted.iter().any(|(w, _, _)| *w == h)
            };
            let parent = if !info.owner.is_invalid() && wanted_has(info.owner, &wanted) {
                Some(info.owner)
            } else {
                // Menus have no owner; attach them to a shown window of the same thread
                wanted
                    .iter()
                    .filter(|(w, kind, _)| {
                        *kind == Kind::Toplevel && thread_of(*w) == info.thread
                    })
                    .map(|(w, _, _)| *w)
                    .find(|w| Some(*w) == self.keyboard_focus)
                    .or_else(|| {
                        wanted
                            .iter()
                            .find(|(w, kind, _)| {
                                *kind == Kind::Toplevel && thread_of(*w) == info.thread
                            })
                            .map(|(w, _, _)| *w)
                    })
            };

            if let Some(parent) = parent {
                wanted.push((hwnd, Kind::Popup { parent }, info.pid));
            }
        }

        // Windows that went away stay allocated until the Java side frees them
        for window in &mut self.windows {
            if window.alive
                && !wanted.iter().any(|(h, k, _)| *h == window.hwnd && *k == window.kind)
            {
                window.alive = false;
                window.frame = None;
                self.hidden.remove(&(window.hwnd.0 as isize));
            }
        }

        for (hwnd, kind, pid) in wanted {
            if self.windows.iter().any(|w| w.alive && w.hwnd == hwnd && w.kind == kind) {
                continue;
            }
            let capture = WindowCapture::new(hwnd, self.method, self.d3d.as_ref());
            self.windows.push(Box::new(TrackedWindow {
                hwnd,
                kind,
                pid,
                alive: true,
                title: String::new(),
                app_id: process_name(pid).unwrap_or_default(),
                capture,
                frame: None,
                frame_new: false,
                origin: POINT::default(),
                geometry: [0, 0, 0, 0],
                popup_offset: [0, 0],
            }));
        }

        if self.hide_windows {
            self.hide_toplevels();
        }

        for window in &mut self.windows {
            if !window.alive {
                continue;
            }

            window.title = window_title(window.hwnd);

            // Minimized windows produce no frames; keep showing the last one
            if !unsafe { IsIconic(window.hwnd) }.as_bool()
                && let Some(frame) = window.capture.poll(window.hwnd, self.d3d.as_ref())
            {
                window.frame = Some(frame);
                window.frame_new = true;
            }

            window.origin = window.capture.origin(window.hwnd);
            window.geometry = match (&window.frame, window.kind) {
                (Some(frame), Kind::Toplevel) => {
                    client_geometry(window.hwnd, window.origin, frame.width, frame.height)
                }
                (Some(frame), Kind::Popup { .. }) => [0, 0, frame.width, frame.height],
                (None, _) => [0, 0, 0, 0],
            };
        }

        self.update_popup_offsets();
    }

    // Moves launched apps' windows past the edge of the desktop. Apps that move their
    // window back (restoring a saved position, say) are moved away again.
    fn hide_toplevels(&mut self) {
        let screen = virtual_screen();
        let mut stole_focus = false;
        for window in &self.windows {
            if !window.alive || window.kind != Kind::Toplevel {
                continue;
            }
            let hwnd = window.hwnd;
            if unsafe { IsIconic(hwnd) }.as_bool() {
                continue;
            }
            let mut rect = RECT::default();
            if unsafe { GetWindowRect(hwnd, &mut rect) }.is_err()
                || !intersects(&rect, &screen)
            {
                continue;
            }

            self.hidden
                .entry(hwnd.0 as isize)
                .or_insert(POINT { x: rect.left, y: rect.top });
            // A maximized window can't be moved; restore it to its normal size first
            if unsafe { IsZoomed(hwnd) }.as_bool() {
                let _ = unsafe { ShowWindow(hwnd, SW_SHOWNOACTIVATE) };
            }
            let _ = unsafe {
                SetWindowPos(
                    hwnd,
                    None,
                    screen.right + 64,
                    screen.top,
                    0,
                    0,
                    SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_NOACTIVATE,
                )
            };
            if unsafe { GetForegroundWindow() } == hwnd {
                stole_focus = true;
            }
        }

        // The game process had the last input, so Windows lets it take the focus back
        if stole_focus && let Some(game) = self.game_window {
            let _ = unsafe { SetForegroundWindow(game) };
        }
    }

    // Popup positions relative to their parent's window geometry
    fn update_popup_offsets(&mut self) {
        let origins: Vec<(HWND, POINT)> = self
            .windows
            .iter()
            .filter(|w| w.alive)
            .map(|w| (w.hwnd, w.geometry_origin()))
            .collect();
        for window in &mut self.windows {
            let Kind::Popup { parent } = window.kind else { continue };
            if let Some((_, q)) = origins.iter().find(|(h, _)| *h == parent) {
                let p = window.geometry_origin();
                window.popup_offset = [p.x - q.x, p.y - q.y];
            }
        }
    }
}

impl Drop for Instance {
    fn drop(&mut self) {
        for (&hwnd, position) in &self.hidden {
            let hwnd = HWND(hwnd as *mut _);
            if !unsafe { IsWindow(Some(hwnd)) }.as_bool() {
                continue;
            }
            let _ = unsafe {
                SetWindowPos(
                    hwnd,
                    None,
                    position.x,
                    position.y,
                    0,
                    0,
                    SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_NOACTIVATE,
                )
            };
        }
    }
}

fn virtual_screen() -> RECT {
    unsafe {
        let left = GetSystemMetrics(SM_XVIRTUALSCREEN);
        let top = GetSystemMetrics(SM_YVIRTUALSCREEN);
        RECT {
            left,
            top,
            right: left + GetSystemMetrics(SM_CXVIRTUALSCREEN),
            bottom: top + GetSystemMetrics(SM_CYVIRTUALSCREEN),
        }
    }
}

fn intersects(a: &RECT, b: &RECT) -> bool {
    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}

struct WindowInfo {
    pid: u32,
    thread: u32,
    owner: HWND,
    title: String,
    tool: bool,
    popup_style: bool,
    has_caption: bool,
    menu_class: bool,
}

impl WindowInfo {
    fn read(hwnd: HWND) -> Option<WindowInfo> {
        unsafe {
            if !IsWindow(Some(hwnd)).as_bool() || !IsWindowVisible(hwnd).as_bool() {
                return None;
            }
        }
        if is_cloaked(hwnd) {
            return None;
        }

        let mut pid = 0;
        let thread = unsafe { GetWindowThreadProcessId(hwnd, Some(&mut pid)) };
        let style = unsafe { GetWindowLongW(hwnd, GWL_STYLE) } as u32;
        let ex_style = unsafe { GetWindowLongW(hwnd, GWL_EXSTYLE) } as u32;
        let owner = unsafe { GetWindow(hwnd, GW_OWNER) }.unwrap_or_default();

        Some(WindowInfo {
            pid,
            thread,
            owner,
            title: window_title(hwnd),
            tool: ex_style & WS_EX_TOOLWINDOW.0 != 0,
            popup_style: style & WS_POPUP.0 != 0,
            has_caption: style & WS_CAPTION.0 == WS_CAPTION.0,
            menu_class: class_name(hwnd) == "#32768",
        })
    }

    fn is_popup(&self) -> bool {
        self.menu_class
            || (self.popup_style && !self.has_caption && !self.owner.is_invalid())
    }
}

fn enum_windows() -> Vec<HWND> {
    unsafe extern "system" fn callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
        let list = unsafe { &mut *(lparam.0 as *mut Vec<HWND>) };
        list.push(hwnd);
        true.into()
    }

    let mut list: Vec<HWND> = vec![];
    let _ = unsafe {
        EnumWindows(Some(callback), LPARAM(&mut list as *mut Vec<HWND> as isize))
    };
    list
}

fn is_cloaked(hwnd: HWND) -> bool {
    let mut cloaked: u32 = 0;
    unsafe {
        DwmGetWindowAttribute(
            hwnd,
            DWMWA_CLOAKED,
            &mut cloaked as *mut u32 as *mut _,
            size_of::<u32>() as u32,
        )
    }
    .is_ok()
        && cloaked != 0
}

fn thread_of(hwnd: HWND) -> u32 {
    unsafe { GetWindowThreadProcessId(hwnd, None) }
}

fn window_title(hwnd: HWND) -> String {
    let mut buffer = [0u16; 512];
    let n = unsafe { GetWindowTextW(hwnd, &mut buffer) };
    String::from_utf16_lossy(&buffer[..n.max(0) as usize])
}

fn class_name(hwnd: HWND) -> String {
    let mut buffer = [0u16; 64];
    let n = unsafe { GetClassNameW(hwnd, &mut buffer) };
    String::from_utf16_lossy(&buffer[..n.max(0) as usize])
}

fn client_origin(hwnd: HWND) -> POINT {
    let mut point = POINT::default();
    unsafe {
        let _ = ClientToScreen(hwnd, &mut point);
    }
    point
}

// The client area within the captured frame, so title bars and borders are cropped
fn client_geometry(hwnd: HWND, origin: POINT, width: i32, height: i32) -> [i32; 4] {
    let mut client = RECT::default();
    if unsafe { GetClientRect(hwnd, &mut client) }.is_err() {
        return [0, 0, width, height];
    }
    let top_left = client_origin(hwnd);
    let x = (top_left.x - origin.x).clamp(0, width);
    let y = (top_left.y - origin.y).clamp(0, height);
    let w = (client.right - client.left).min(width - x);
    let h = (client.bottom - client.top).min(height - y);
    if w <= 0 || h <= 0 {
        return [0, 0, width, height];
    }
    [x, y, w, h]
}

// The executable's file name without extension, used as the window's app ID
fn process_name(pid: u32) -> Option<String> {
    unsafe {
        let process =
            OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let mut buffer = [0u16; 1024];
        let mut len = buffer.len() as u32;
        let result = QueryFullProcessImageNameW(
            process,
            PROCESS_NAME_WIN32,
            PWSTR(buffer.as_mut_ptr()),
            &mut len,
        );
        let _ = CloseHandle(process);
        result.ok()?;
        let path = String::from_utf16_lossy(&buffer[..len as usize]);
        let file = path.rsplit('\\').next()?;
        Some(file.trim_end_matches(".exe").trim_end_matches(".EXE").to_string())
    }
}

// Adds every descendant process of the given ones
fn with_child_processes(roots: &HashSet<u32>) -> HashSet<u32> {
    let mut children: HashMap<u32, Vec<u32>> = HashMap::new();
    unsafe {
        let Ok(snapshot) = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) else {
            return roots.clone();
        };
        let mut entry = PROCESSENTRY32W {
            dwSize: size_of::<PROCESSENTRY32W>() as u32,
            ..Default::default()
        };
        if Process32FirstW(snapshot, &mut entry).is_ok() {
            loop {
                children
                    .entry(entry.th32ParentProcessID)
                    .or_default()
                    .push(entry.th32ProcessID);
                if Process32NextW(snapshot, &mut entry).is_err() {
                    break;
                }
            }
        }
        let _ = CloseHandle(snapshot);
    }

    let mut result = roots.clone();
    let mut queue: Vec<u32> = roots.iter().copied().collect();
    while let Some(pid) = queue.pop() {
        if let Some(kids) = children.get(&pid) {
            for &kid in kids {
                // PID 0 parents everything orphaned; never walk into it
                if kid != 0 && result.insert(kid) {
                    queue.push(kid);
                }
            }
        }
    }
    result
}
