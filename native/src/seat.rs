use crate::{WLCState, get_time};
use std::ffi::CString;
use std::sync::{Arc, Mutex};
use std::ops::DerefMut;
use std::os::fd::AsFd;
use smithay::{
    utils::{SERIAL_COUNTER, SealedFile},
    reexports::{
        wayland_server::{
            backend::{ClientId},
            protocol::{
                wl_surface::WlSurface,
                wl_seat::{self, WlSeat},
                wl_pointer::{self, WlPointer, ButtonState, Axis},
                wl_keyboard::{self, WlKeyboard, KeymapFormat, KeyState},
            },
            DisplayHandle, Client, GlobalDispatch, Dispatch, New, DataInit,
            Resource,
        },
    },
};
use xkbcommon::xkb::{self, Keymap};

pub struct WLCSeatState {
    pub pointers: Vec<WlPointer>,
    pub keyboards: Vec<WlKeyboard>,
    pub keymap_file: SealedFile,
    pub xkb_context: xkb::Context,
    pub xkb_state: xkb::State,
}

pub struct WLCPointerData {
    // WlSurface holding pointer focus
    // This surface has to be of the same client as the WlPointer
    focus: Option<WlSurface>,
}

type WLCPointer = Arc<Mutex<WLCPointerData>>;

pub struct WLCKeyboardData {
    // WlSurface holding keyboard focus
    // This surface has to be of the same client as the WlKeyboard
    focus: Option<WlSurface>,
}

type WLCKeyboard = Arc<Mutex<WLCKeyboardData>>;

fn with_pointer_data<F>(pointer: &WlPointer, f: F)
    where F: FnOnce(&mut WLCPointerData)
{
    let mut guard = pointer
        .data::<WLCPointer>()
        .unwrap()
        .lock()
        .unwrap();
    let data = guard.deref_mut();
    f(data);
}

fn with_keyboard_data<F>(keyboard: &WlKeyboard, f: F)
    where F: FnOnce(&mut WLCKeyboardData)
{
    let mut guard = keyboard
        .data::<WLCKeyboard>()
        .unwrap()
        .lock()
        .unwrap();
    let data = guard.deref_mut();
    f(data);
}

fn new_serial() -> u32 {
    SERIAL_COUNTER.next_serial().into()
}

impl WLCSeatState {
    pub fn new() -> Self {
        let xkb_context = xkb::Context::new(xkb::CONTEXT_NO_FLAGS);
        let keymap = Keymap::new_from_names(
            &xkb_context,
            "", // rules
            "", // model
            "", // layout
            "", // variant
            None, // options
            xkb::KEYMAP_COMPILE_NO_FLAGS, // flags
        ).expect("keymap create");

        let keymap_str = keymap.get_as_string(xkb::KEYMAP_FORMAT_TEXT_V1);
        let keymap_file = SealedFile::with_content(
            c"waylandcraft-keymap",
            &CString::new(keymap_str.as_str()).unwrap()
        ).expect("SealedFile create");

        let xkb_state = xkb::State::new(&keymap);

        WLCSeatState {
            pointers: vec![],
            keyboards: vec![],
            keymap_file,
            xkb_context,
            xkb_state,
        }
    }

    pub fn create_global(&self, disp: &DisplayHandle) {
        disp.create_global::<WLCState, WlSeat, ()>(10, ());
    }

    // Set pointer focus on a surface and register movement
    pub fn pointer_motion(&self, surface: WlSurface, x: f64, y: f64) {
        if !surface.is_alive() { return };
        let client = surface.client().unwrap();

        self.for_all_pointers(|pointer, data| {
            let pointer_client = pointer.client().unwrap();

            // If WlPointer belongs to different client, make it lose focus
            if pointer_client != client {
                if let Some(focus) = &data.focus {
                    pointer.leave(new_serial(), focus);
                    pointer.frame();
                    data.focus = None;
                }
                return;
            }

            // This pointer is now guaranteed to be of the same client as the
            // surface

            if let Some(focus) = &data.focus {
                if *focus != surface {
                    // Previously focusing different surface
                    pointer.leave(new_serial(), focus);
                    pointer.enter(new_serial(), &surface, x, y);
                    data.focus = Some(surface.clone());
                } else {
                    // Focus already on this surface
                    pointer.motion(get_time(), x, y);
                }
                pointer.frame();
            } else {
                pointer.enter(new_serial(), &surface, x, y);
                pointer.frame();
                data.focus = Some(surface.clone());
            }
        });
    }

    pub fn pointer_button(&self, button: u32, state: ButtonState) {
        self.for_all_pointers(|pointer, data| {
            if data.focus.is_some() {
                pointer.button(new_serial(), get_time(), button, state);
                pointer.frame();
            }
        });
    }

    pub fn pointer_axis(&self, axis: Axis, value: f64) {
        self.for_all_pointers(|pointer, data| {
            if data.focus.is_some() {
                pointer.axis(get_time(), axis, value);
                pointer.frame();
            }
        });
    }

    // Remove pointer from any surfaces
    pub fn pointer_unfocus(&self) {
        self.for_all_pointers(|pointer, data| {
            if let Some(focus) = &data.focus {
                pointer.leave(new_serial(), focus);
                pointer.frame();
                data.focus = None;
            }
        });
    }

    pub fn keyboard_update_xkb(&mut self, key: u32, pressed: bool) {
        let dir = match pressed {
            true => xkb::KeyDirection::Down,
            false => xkb::KeyDirection::Up,
        };
        let code = xkb::Keycode::new(key);
        self.xkb_state.update_key(code, dir);
    }

    pub fn keyboard_focus(&self, surface: WlSurface) {
        if !surface.is_alive() { return };
        let client = surface.client().unwrap();

        self.for_all_keyboards(|keyboard, data| {
            let keyboard_client = keyboard.client().unwrap();

            // If WlKeyboard belongs to different client, make it lose focus
            if keyboard_client != client {
                if let Some(focus) = &data.focus {
                    keyboard.leave(new_serial(), focus);
                    data.focus = None;
                }
                return;
            }

            // This keyboard is now guaranteed to be of the same client as the
            // surface

            if let Some(focus) = &data.focus {
                if *focus == surface {
                    // Surface already focused
                    return;
                }
                keyboard.leave(new_serial(), focus);
                data.focus = None;
            }

            // Keyboard should enter surface

            let pressed: Vec<u32> = vec![]; // TODO: Implement.
            let pressed: Vec<u8> = pressed
                .iter()
                .flat_map(|&k| k.to_ne_bytes())
                .collect();

            keyboard.enter(new_serial(), &surface, pressed);
            data.focus = Some(surface.clone());

            self.send_modifiers(&keyboard);
        });
    }

    fn send_modifiers(&self, keyboard: &WlKeyboard) {
        keyboard.modifiers(
            new_serial(),
            self.xkb_state.serialize_mods(xkb::STATE_MODS_DEPRESSED),
            self.xkb_state.serialize_mods(xkb::STATE_MODS_LATCHED),
            self.xkb_state.serialize_mods(xkb::STATE_MODS_LOCKED),
            self.xkb_state.serialize_layout(xkb::STATE_LAYOUT_EFFECTIVE)
        );
    }

    pub fn keyboard_unfocus(&self) {
        self.for_all_keyboards(|keyboard, data| {
            if let Some(focus) = &data.focus {
                keyboard.leave(new_serial(), focus);
                data.focus = None;
            }
        });
    }

    pub fn keyboard_key(&self, key: u32, state: KeyState) {
        self.for_all_keyboards(|keyboard, data| {
            if data.focus.is_some() {
                keyboard.key(new_serial(), get_time(), key - 8, state);
                self.send_modifiers(&keyboard);
            }
        });
    }

    fn for_all_pointers<F>(&self, mut f: F)
        where F: FnMut(&WlPointer, &mut WLCPointerData)
    {
        for pointer in &self.pointers {
            with_pointer_data(pointer, |data| f(pointer, data));
        }
    }

    fn for_all_keyboards<F>(&self, mut f: F)
        where F: FnMut(&WlKeyboard, &mut WLCKeyboardData)
    {
        for keyboard in &self.keyboards {
            with_keyboard_data(keyboard, |data| f(keyboard, data));
        }
    }
}

impl GlobalDispatch<WlSeat, ()> for WLCState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlSeat>,
        _data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let seat: WlSeat = data_init.init(resource, ());
        seat.name("waylandcraft-seat".into());

        let mut caps: wl_seat::Capability = wl_seat::Capability::empty();
        caps.insert(wl_seat::Capability::Pointer);
        caps.insert(wl_seat::Capability::Keyboard);
        seat.capabilities(caps);
    }
}

impl Dispatch<WlSeat, ()> for WLCState {
    fn request(
        state: &mut Self,
        _client: &Client,
        seat_resource: &WlSeat,
        request: wl_seat::Request,
        _data: &(),
        _disp: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_seat::Request::GetPointer { id } => {
                let pointer_data = WLCPointerData {
                    focus: None,
                };
                let pointer_data = Arc::new(Mutex::new(pointer_data));

                let pointer: WlPointer =
                    data_init.init(id, pointer_data.clone());

                state.seat.pointers.push(pointer);
            },
            wl_seat::Request::GetKeyboard { id } => {
                let keyboard_data = WLCKeyboardData {
                    focus: None,
                };
                let keyboard_data = Arc::new(Mutex::new(keyboard_data));

                let keyboard: WlKeyboard =
                    data_init.init(id, keyboard_data.clone());

                state.seat.keyboards.push(keyboard.clone());

                let keymap = &state.seat.keymap_file;
                keyboard.keymap(
                    KeymapFormat::XkbV1,
                    keymap.as_fd(),
                    keymap.size() as u32
                );

                keyboard.repeat_info(25, 200);
            },
            _ => {
                seat_resource.post_error(
                    wl_seat::Error::MissingCapability,
                    "accessed missing seat capability",
                );
            },
        }
    }
}

impl Dispatch<WlPointer, WLCPointer> for WLCState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _pointer_resource: &WlPointer,
        request: wl_pointer::Request,
        _data: &WLCPointer,
        _disp: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_pointer::Request::SetCursor { .. } => {},
            wl_pointer::Request::Release => {},
            _ => unreachable!(),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        pointer_resource: &WlPointer,
        _data: &WLCPointer,
    ) {
        state.seat.pointers.retain(|p| p != pointer_resource);
    }
}

impl Dispatch<WlKeyboard, WLCKeyboard> for WLCState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _keyboard_resource: &WlKeyboard,
        request: wl_keyboard::Request,
        _data: &WLCKeyboard,
        _disp: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_keyboard::Request::Release => {},
            _ => unreachable!(),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        keyboard_resource: &WlKeyboard,
        _data: &WLCKeyboard,
    ) {
        state.seat.keyboards.retain(|kb| kb != keyboard_resource);
    }
}
