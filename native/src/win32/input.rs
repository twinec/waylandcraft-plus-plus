// Keyboard and mouse input for captured windows.
//
// Input is posted to the window's message queue, so the app receives it while
// Minecraft keeps focus. Text is sent as WM_CHAR (translated with the keyboard layout
// and the modifiers held in-game), other keys as WM_KEYDOWN/WM_KEYUP.

use ::windows::Win32::Foundation::{HWND, LPARAM, POINT, WPARAM};
use ::windows::Win32::Graphics::Gdi::{ClientToScreen, MapWindowPoints};
use ::windows::Win32::System::SystemServices::{
    MK_LBUTTON, MK_MBUTTON, MK_RBUTTON, MK_XBUTTON1, MK_XBUTTON2,
};
use ::windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyboardLayout, MAPVK_VSC_TO_VK_EX, MapVirtualKeyExW, ToUnicodeEx,
    VIRTUAL_KEY, VK_CONTROL, VK_LCONTROL, VK_LMENU, VK_LSHIFT, VK_MENU,
    VK_RCONTROL, VK_RMENU, VK_RSHIFT, VK_SHIFT,
};
use ::windows::Win32::UI::WindowsAndMessaging::{
    CWP_SKIPDISABLED, CWP_SKIPINVISIBLE, CWP_SKIPTRANSPARENT,
    ChildWindowFromPointEx, GUITHREADINFO, GetGUIThreadInfo,
    GetWindowThreadProcessId, PostMessageW, WA_ACTIVE, WHEEL_DELTA, WM_ACTIVATE, WM_CHAR, WM_KEYDOWN, WM_KEYUP,
    WM_LBUTTONDOWN, WM_LBUTTONUP, WM_MBUTTONDOWN, WM_MBUTTONUP, WM_MOUSEHWHEEL,
    WM_MOUSEMOVE, WM_MOUSEWHEEL, WM_RBUTTONDOWN, WM_RBUTTONUP, WM_SYSKEYDOWN,
    WM_SYSKEYUP, WM_XBUTTONDOWN, WM_XBUTTONUP,
};

// Linux input event codes, which the Java side uses for buttons
const BTN_LEFT: i32 = 0x110;
const BTN_RIGHT: i32 = 0x111;
const BTN_MIDDLE: i32 = 0x112;
const BTN_SIDE: i32 = 0x113;
const BTN_EXTRA: i32 = 0x114;

// GLFW marks extended scancodes (right Ctrl, arrows, ...) with this bit on Windows
const SCANCODE_EXTENDED: u32 = 0x100;

pub struct InputState {
    /// Keys held, as a GetKeyboardState style table
    keys: [u8; 256],
    /// Mouse buttons held, as MK_* flags
    buttons: u32,
    /// The child window that received the button press, kept until all are released
    grab: Option<(HWND, HWND)>,
    /// Last pointer position in the toplevel's client coordinates
    pointer: Option<(HWND, POINT)>,
}

impl Default for InputState {
    fn default() -> Self {
        InputState { keys: [0; 256], buttons: 0, grab: None, pointer: None }
    }
}

fn lparam_point(p: POINT) -> LPARAM {
    LPARAM(((p.y as u16 as u32) << 16 | (p.x as u16 as u32)) as isize)
}

fn post(hwnd: HWND, msg: u32, wparam: usize, lparam: LPARAM) {
    let _ = unsafe { PostMessageW(Some(hwnd), msg, WPARAM(wparam), lparam) };
}

// Finds the deepest child window under a client point, returning it and the
// point in its client coordinates
fn child_at(top: HWND, point: POINT) -> (HWND, POINT) {
    let mut hwnd = top;
    let mut point = point;
    loop {
        let child = unsafe {
            ChildWindowFromPointEx(
                hwnd,
                point,
                CWP_SKIPINVISIBLE | CWP_SKIPDISABLED | CWP_SKIPTRANSPARENT,
            )
        };
        if child.is_invalid() || child == hwnd {
            return (hwnd, point);
        }
        let mut points = [point];
        unsafe { MapWindowPoints(Some(hwnd), Some(child), &mut points) };
        hwnd = child;
        point = points[0];
    }
}

fn to_child(top: HWND, child: HWND, point: POINT) -> POINT {
    let mut points = [point];
    unsafe { MapWindowPoints(Some(top), Some(child), &mut points) };
    points[0]
}

impl InputState {
    /// Mouse motion at a point in the toplevel's client coordinates.
    pub fn motion(&mut self, top: HWND, point: POINT) {
        self.pointer = Some((top, point));
        let (target, local) = match self.grab {
            Some((grab_top, child)) if grab_top == top => {
                (child, to_child(top, child, point))
            }
            _ => child_at(top, point),
        };
        post(target, WM_MOUSEMOVE, self.buttons as usize, lparam_point(local));
    }

    pub fn leave(&mut self) {
        self.pointer = None;
    }

    pub fn button(&mut self, button: i32, pressed: bool) {
        let Some((top, point)) = self.pointer else { return };

        let (down, up, flag, xbutton) = match button {
            BTN_LEFT => (WM_LBUTTONDOWN, WM_LBUTTONUP, MK_LBUTTON.0, 0),
            BTN_RIGHT => (WM_RBUTTONDOWN, WM_RBUTTONUP, MK_RBUTTON.0, 0),
            BTN_MIDDLE => (WM_MBUTTONDOWN, WM_MBUTTONUP, MK_MBUTTON.0, 0),
            BTN_SIDE => (WM_XBUTTONDOWN, WM_XBUTTONUP, MK_XBUTTON1.0, 1),
            BTN_EXTRA => (WM_XBUTTONDOWN, WM_XBUTTONUP, MK_XBUTTON2.0, 2),
            _ => return,
        };

        if pressed {
            self.buttons |= flag;
        } else {
            self.buttons &= !flag;
        }

        // Like mouse capture: the press target keeps receiving input until release
        let (target, local) = match self.grab {
            Some((grab_top, child)) if grab_top == top => {
                (child, to_child(top, child, point))
            }
            _ => child_at(top, point),
        };
        if pressed && self.grab.is_none() {
            self.grab = Some((top, target));
        }
        if self.buttons == 0 {
            self.grab = None;
        }

        let msg = if pressed { down } else { up };
        let wparam = (self.buttons as usize) | ((xbutton as usize) << 16);
        post(target, msg, wparam, lparam_point(local));
    }

    /// Scrolling in wheel notches, positive meaning down/right like on Wayland.
    pub fn scroll(&mut self, horizontal: bool, notches: f64) {
        let Some((top, point)) = self.pointer else { return };
        let (target, _) = child_at(top, point);

        let delta = (notches * WHEEL_DELTA as f64).round() as i32;
        // Wheel up is positive on Windows, horizontal right is positive
        let delta = if horizontal { delta } else { -delta };
        if delta == 0 {
            return;
        }

        // Wheel messages carry screen coordinates
        let mut screen = point;
        unsafe {
            let _ = ClientToScreen(top, &mut screen);
        }
        let msg = if horizontal { WM_MOUSEHWHEEL } else { WM_MOUSEWHEEL };
        let wparam = ((delta as i16 as u16 as usize) << 16) | self.buttons as usize;
        post(target, msg, wparam, lparam_point(screen));
    }

    /// Tracks keys pressed while Minecraft has the keyboard, for modifier state.
    pub fn update_key(&mut self, scancode: u32, pressed: bool) {
        let vk = scancode_to_vk(scancode);
        if vk.0 == 0 {
            return;
        }
        self.set_key(vk, pressed);
    }

    fn set_key(&mut self, vk: VIRTUAL_KEY, pressed: bool) {
        self.keys[vk.0 as usize & 0xff] = if pressed { 0x80 } else { 0 };

        // Keep the generic modifier entries in sync with the left/right ones
        let either = |a: VIRTUAL_KEY, b: VIRTUAL_KEY, keys: &[u8; 256]| {
            (keys[a.0 as usize] | keys[b.0 as usize]) & 0x80
        };
        self.keys[VK_SHIFT.0 as usize] = either(VK_LSHIFT, VK_RSHIFT, &self.keys);
        self.keys[VK_CONTROL.0 as usize] =
            either(VK_LCONTROL, VK_RCONTROL, &self.keys);
        self.keys[VK_MENU.0 as usize] = either(VK_LMENU, VK_RMENU, &self.keys);
    }

    pub fn release_all(&mut self) {
        self.keys = [0; 256];
    }

    /// A key press or release sent to the focused control of the window.
    pub fn key(&mut self, top: HWND, scancode: u32, pressed: bool) {
        let vk = scancode_to_vk(scancode);
        if vk.0 == 0 {
            return;
        }
        self.set_key(vk, pressed);

        let target = focused_control(top);
        let alt = self.keys[VK_MENU.0 as usize] & 0x80 != 0;

        // Text: translate with the current layout and modifiers, send as characters
        if !alt {
            let text = self.translate(top, vk, scancode);
            if let Some(text) = text {
                if pressed {
                    for unit in text.encode_utf16() {
                        post(target, WM_CHAR, unit as usize, key_lparam(scancode, true));
                    }
                }
                return;
            }
        }

        let msg = match (pressed, alt) {
            (true, false) => WM_KEYDOWN,
            (false, false) => WM_KEYUP,
            (true, true) => WM_SYSKEYDOWN,
            (false, true) => WM_SYSKEYUP,
        };
        // Generic modifier codes, as apps receive them from real keyboards
        let wparam_vk = match vk {
            VK_LSHIFT | VK_RSHIFT => VK_SHIFT,
            VK_LCONTROL | VK_RCONTROL => VK_CONTROL,
            VK_LMENU | VK_RMENU => VK_MENU,
            other => other,
        };
        let mut lparam = key_lparam(scancode, pressed);
        if alt {
            lparam.0 |= 1 << 29; // context code: Alt held
        }
        post(target, msg, wparam_vk.0 as usize, lparam);
    }

    fn translate(
        &self, top: HWND, vk: VIRTUAL_KEY, scancode: u32,
    ) -> Option<String> {
        let layout = unsafe {
            GetKeyboardLayout(GetWindowThreadProcessId(top, None))
        };
        let mut buffer = [0u16; 8];
        // Flag 4: don't change the keyboard state (dead keys) of this thread
        let n = unsafe {
            ToUnicodeEx(
                vk.0 as u32,
                scancode & 0xff,
                &self.keys,
                &mut buffer,
                4,
                Some(layout),
            )
        };
        if n <= 0 {
            return None;
        }
        let text = String::from_utf16_lossy(&buffer[..n as usize]);
        // Tab, Enter, Backspace and Escape also map to characters, but apps expect keys
        if text.chars().any(|c| matches!(c, '\t' | '\r' | '\n' | '\u{8}' | '\u{1b}')) {
            return None;
        }
        Some(text)
    }
}

fn key_lparam(scancode: u32, pressed: bool) -> LPARAM {
    let mut value: isize = 1 | (((scancode & 0xff) as isize) << 16);
    if scancode & SCANCODE_EXTENDED != 0 {
        value |= 1 << 24;
    }
    if !pressed {
        value |= (1 << 30) | (1 << 31);
    }
    LPARAM(value)
}

fn scancode_to_vk(scancode: u32) -> VIRTUAL_KEY {
    let code = if scancode & SCANCODE_EXTENDED != 0 {
        0xe000 | (scancode & 0xff)
    } else {
        scancode & 0xff
    };
    let vk = unsafe { MapVirtualKeyExW(code, MAPVK_VSC_TO_VK_EX, None) };
    VIRTUAL_KEY(vk as u16)
}

/// Tells a window it was activated, without making it the foreground window. Apps
/// started in the background never got activated, so they have no focused control
/// for keys to go to; activating makes them focus one (their text box, say).
pub fn activate(top: HWND) {
    post(top, WM_ACTIVATE, WA_ACTIVE as usize, LPARAM(0));
}

// The control with keyboard focus inside the window's thread, e.g. a text box
fn focused_control(top: HWND) -> HWND {
    let thread = unsafe { GetWindowThreadProcessId(top, None) };
    let mut info = GUITHREADINFO {
        cbSize: size_of::<GUITHREADINFO>() as u32,
        ..Default::default()
    };
    if unsafe { GetGUIThreadInfo(thread, &mut info) }.is_ok() {
        if !info.hwndFocus.is_invalid() {
            return info.hwndFocus;
        }
        if !info.hwndActive.is_invalid() {
            return info.hwndActive;
        }
    }
    top
}
