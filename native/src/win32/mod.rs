#![allow(non_snake_case)]

// The Windows backend: desktop windows are captured and shown in-game, and input is
// forwarded to them. It implements the same natives as the Linux compositor, so the
// Java side treats each captured window as a toplevel with a single surface.

mod apps;
mod capture;
mod input;
mod tracker;

#[path = "../java_types.rs"]
mod java_types;

use java_types::*;
use jni::objects::{JIntArray, JLongArray, JObjectArray};
use jni::{
    Env, bind_java_type,
    objects::{JClass, JString},
    sys::{jboolean, jdouble, jint, jlong},
};
use thiserror::Error;
use tracker::{Instance, Kind};

bind_java_type! {
    rust_type = WaylandCraftBridge,
    java_type = dev.evvie.waylandcraft.bridge.WaylandCraftBridge,

    type_map {
        WLCSurface => dev.evvie.waylandcraft.bridge.WLCSurface,
        JRawDesktopEntry => dev.evvie.waylandcraft.desktop.RawDesktopEntry,
        JDmabufFormat => dev.evvie.waylandcraft.bridge.dmabuf.DmabufFormat,
        JDmabufPlane => dev.evvie.waylandcraft.bridge.dmabuf.DmabufPlane,
        JDmabuf => dev.evvie.waylandcraft.bridge.dmabuf.Dmabuf,
        JDmabufFeedbackData =>
            dev.evvie.waylandcraft.bridge.dmabuf.DmabufFeedbackData,
    },

    methods {
        fn get_or_create_surface(jlong) -> WLCSurface,
        fn import_dmabuf(JDmabuf) -> jboolean,
    },

    native_methods {
        static extern fn init {
            sig = (
                dmabuf_feedback: JDmabufFeedbackData,
            ) -> jlong,
            fn = init,
        },
        static extern fn shutdown {
            sig = (instance: jlong),
            fn = shutdown,
        },
        static extern fn dispatch_clients {
            sig = (instance: jlong),
            fn = dispatch_clients,
        },
        static extern fn flush_display {
            sig = (instance: jlong),
            fn = flush_display
        },
        static extern fn socket {
            sig = (instance: jlong) -> JString,
            fn = socket,
        },
        static extern fn x11_display {
            sig = (instance: jlong) -> JString,
            fn = x11_display,
        },
        static extern fn send_frame {
            sig = (surface_handle: jlong),
            fn = send_frame,
        },
        static extern fn update_surface_data {
            sig = (instance: jlong, surface: WLCSurface),
            fn = update_surface_data,
        },
        static extern fn toplevels {
            sig = (instance: jlong) -> jlong[],
            fn = toplevels,
        },
        static extern fn toplevel_surface {
            sig = (instance: jlong, toplevel_handle: jlong) -> jlong,
            fn = toplevel_surface,
        },
        static extern fn toplevel_title {
            sig = (toplevel_handle: jlong) -> JString,
            fn = toplevel_title,
        },
        static extern fn toplevel_app_id {
            sig = (toplevel_handle: jlong) -> JString,
            name = "toplevelAppID",
            fn = toplevel_app_id,
        },
        static extern fn toplevel_pid {
            sig = (instance: jlong, toplevel_handle: jlong) -> jint,
            name = "toplevelPID",
            fn = toplevel_pid,
        },
        static extern fn toplevel_resize {
            sig = (
                toplevel_handle: jlong,
                width: jint,
                height: jint,
                interactive: jboolean
            ),
            fn = toplevel_resize,
        },
        static extern fn toplevel_resize_ovr {
            sig = (toplevel_handle: jlong, width: jint, height: jint),
            fn = toplevel_resize_ovr,
        },
        static extern fn minimize_req {
            sig = (instance: jlong) -> jlong[],
            fn = minimize_req,
        },
        static extern fn maximize_req {
            sig = (instance: jlong) -> jlong[],
            fn = maximize_req,
        },
        static extern fn unmaximize_req {
            sig = (instance: jlong) -> jlong[],
            fn = unmaximize_req,
        },
        static extern fn fullscreen_req {
            sig = (instance: jlong) -> jlong[],
            fn = fullscreen_req,
        },
        static extern fn unfullscreen_req {
            sig = (instance: jlong) -> jlong[],
            fn = unfullscreen_req,
        },
        static extern fn move_request {
            sig = (instance: jlong) -> jint[],
            fn = move_request,
        },
        static extern fn resize_request {
            sig = (instance: jlong) -> jint[],
            fn = resize_request,
        },
        static extern fn fullscreened {
            sig = (instance: jlong) -> jlong[],
            fn = fullscreened,
        },
        static extern fn toplevel_maximize {
            sig = (instance: jlong, toplevel_handle: jlong),
            fn = toplevel_maximize,
        },
        static extern fn toplevel_fullscreen {
            sig = (instance: jlong, toplevel_handle: jlong),
            fn = toplevel_fullscreen,
        },
        static extern fn popups {
            sig = (instance: jlong) -> jlong[],
            fn = popups,
        },
        static extern fn popup_surface {
            sig = (instance: jlong, popup_handle: jlong) -> jlong,
            fn = popup_surface,
        },
        static extern fn popup_parent {
            sig = (instance: jlong, popup_handle: jlong) -> jlong,
            fn = popup_parent,
        },
        static extern fn popup_offset {
            sig = (popup_handle: jlong) -> jint[],
            fn = popup_offset,
        },
        static extern fn surface_xdg_geometry {
            sig = (surface_handle: jlong) -> jint[],
            name = "surfaceXDGGeometry",
            fn = surface_xdg_geometry,
        },
        static extern fn dmabufs {
            sig = (instance: jlong) -> jlong[],
            fn = dmabufs
        },
        extern fn check_import_dmabuf {
            sig = (instance: jlong),
            fn = check_import_dmabuf,
        },
        static extern fn release_buffer {
            sig = (instance: jlong, release_handle: jlong),
            fn = release_buffer,
        },
        static extern fn sync_dmabuf_planes {
            sig = (instance: jlong, handle: jlong, end: jboolean),
            fn = sync_dmabuf_planes,
        },
        extern fn update_surface_tree {
            sig = (instance: jlong, surface: WLCSurface) -> WLCSurface,
            fn = update_surface_tree,
        },
        static extern fn check_input_region {
            sig = (surface_handle: jlong, x: jdouble, y: jdouble) -> jboolean,
            fn = check_input_region,
        },
        static extern fn pointer_motion {
            sig = (instance: jlong, x: jdouble, y: jdouble),
            fn = pointer_motion,
        },
        static extern fn pointer_motion_focus {
            sig = (
                instance: jlong,
                surface_handle: jlong,
                x: jdouble,
                y: jdouble
            ),
            fn = pointer_motion_focus,
        },
        static extern fn pointer_rel_motion {
            sig = (instance: jlong, dx: jdouble, dy: jdouble),
            fn = pointer_rel_motion,
        },
        static extern fn maybe_pointer_lock {
            sig = (instance: jlong, surface_handle: jlong) -> jboolean,
            fn = maybe_pointer_lock,
        },
        static extern fn pointer_unlock {
            sig = (instance: jlong),
            fn = pointer_unlock,
        },
        static extern fn pointer_leave {
            sig = (instance: jlong),
            fn = pointer_leave,
        },
        static extern fn pointer_button {
            sig = (instance: jlong, button: jint, state: jint) -> jint,
            fn = pointer_button,
        },
        static extern fn pointer_axis {
            sig = (instance: jlong, axis: jint, value: jdouble),
            fn = pointer_axis,
        },
        static extern fn cursor_shape {
            sig = (instance: jlong) -> jint,
            fn = cursor_shape,
        },
        static extern fn keyboard_focus {
            sig = (instance: jlong, surface_handle: jlong),
            fn = keyboard_focus,
        },
        static extern fn keyboard_activate {
            sig = (instance: jlong),
            fn = keyboard_activate,
        },
        static extern fn keyboard_deactivate {
            sig = (instance: jlong),
            fn = keyboard_deactivate,
        },
        static extern fn keyboard_input {
            sig = (instance: jlong, scancode: jint, action: jint),
            fn = keyboard_input,
        },
        static extern fn keyboard_update {
            sig = (instance: jlong, scancode: jint, pressed: jboolean),
            fn = keyboard_update,
        },
        static extern fn output_size {
            sig = (instance: jlong) -> jint[],
            fn = output_size,
        },
        static extern fn output_bounds {
            sig = (instance: jlong) -> jint[],
            fn = output_bounds,
        },
        static extern fn output_resize {
            sig = (instance: jlong, width: jint, height: jint),
            fn = output_resize,
        },
        static extern fn output_set_bounds {
            sig = (instance: jlong, width: jint, height: jint),
            fn = output_set_bounds,
        },
        static extern fn free_surface {
            sig = (instance: jlong, surface_handle: jlong),
            fn = free_surface,
        },
        static extern fn free_toplevel {
            sig = (instance: jlong, toplevel_handle: jlong),
            fn = free_toplevel,
        },
        static extern fn free_popup {
            sig = (instance: jlong, popup_handle: jlong),
            fn = free_popup,
        },
        static extern fn load_desktop_entry {
            sig = (instance: jlong, path: JString) -> JRawDesktopEntry,
            fn = load_desktop_entry,
        },
        static extern fn load_desktop_entries {
            sig = (instance: jlong) -> JRawDesktopEntry[],
            fn = load_desktop_entries,
        },
        static extern fn render_svg {
            sig = (
                path: JString,
                width: jint,
                height: jint,
                buffer_ptr: jlong
            ) -> jboolean,
            name = "renderSVG",
            fn = render_svg,
        },
        static extern fn exec_app {
            sig = (instance: jlong, app_id: JString) -> jboolean,
            fn = exec_app,
        },
        static extern fn set_preferred_terminal {
            sig = (instance: jlong, cmd: JString),
            fn = set_preferred_terminal,
        },
        static extern fn set_env_overrides {
            sig = (instance: jlong, overrides: JString),
            fn = set_env_overrides,
        },
        static extern fn set_keymap_default {
            sig = (instance: jlong),
            fn = set_keymap_default,
        },
        static extern fn export_keymap {
            sig = (instance: jlong) -> JString,
            fn = export_keymap,
        },
        static extern fn set_keymap_from_str {
            sig = (instance: jlong, keymap: JString) -> jboolean,
            fn = set_keymap_from_str,
        },
        static extern fn check_dnd_request {
            sig = (instance: jlong) -> jint[],
            fn = check_dnd_request,
        },
        static extern fn check_dnd_active {
            sig = (instance: jlong) -> jboolean,
            fn = check_dnd_active,
        },
        static extern fn dnd_cancel {
            sig = (instance: jlong),
            fn = dnd_cancel,
        },
        static extern fn dnd_drop {
            sig = (instance: jlong),
            fn = dnd_drop,
        },
        static extern fn dnd_motion {
            sig = (
                instance: jlong,
                surface_handle: jlong,
                x: jdouble,
                y: jdouble
            ),
            fn = dnd_motion,
        },
        static extern fn dnd_icon {
            sig = (instance: jlong) -> jlong,
            fn = dnd_icon,
        },
        static extern fn drm_device_by_path {
            sig = (path: JString) -> jlong,
            fn = drm_device_by_path,
        },
        static extern fn drm_device_by_major_minor {
            sig = (major: jint, minor: jint) -> jlong,
            fn = drm_device_by_major_minor,
        },
    },
}

#[derive(Debug, Error)]
enum BridgeError {
    #[error(transparent)]
    JniError(#[from] jni::errors::Error),
    #[error("Null WLC instance handle given. Function: {0}")]
    NullInstancePtr(&'static str),
    #[error("Not supported on Windows: {0}")]
    Unsupported(&'static str),
}

type R<T> = Result<T, BridgeError>;

fn instance<'a>(ptr: jlong, location: &'static str) -> R<&'a mut Instance> {
    let ptr = ptr as usize as *mut Instance;
    if ptr.is_null() {
        Err(BridgeError::NullInstancePtr(location))
    } else {
        Ok(unsafe { &mut *ptr })
    }
}

// Natives without an instance argument get handles of live windows from the Java
// side, which never passes a handle after freeing it
fn window_ref<'a>(handle: jlong) -> Option<&'a tracker::TrackedWindow> {
    let ptr = (handle & !1) as usize as *const tracker::TrackedWindow;
    if ptr.is_null() { None } else { Some(unsafe { &*ptr }) }
}

fn long_array<'local>(env: &mut Env<'local>, values: &[jlong]) -> R<JLongArray<'local>> {
    let array = JLongArray::new(env, values.len())?;
    array.set_region(env, 0, values)?;
    Ok(array)
}

fn int_array<'local>(env: &mut Env<'local>, values: &[jint]) -> R<JIntArray<'local>> {
    let array = JIntArray::new(env, values.len())?;
    array.set_region(env, 0, values)?;
    Ok(array)
}

fn empty_longs<'local>(env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<JLongArray<'local>> {
    long_array(env, &[])
}

fn null_ints<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<JIntArray<'local>> {
    Ok(JIntArray::null())
}

fn noop<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<()> {
    Ok(())
}

fn init<'local>(
    _env: &mut Env<'local>,
    _class: JClass<'local>,
    _dmabuf_feedback: JDmabufFeedbackData<'local>,
) -> R<jlong> {
    let instance = Box::new(Instance::new());
    Ok(Box::into_raw(instance) as usize as jlong)
}

fn shutdown<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<()> {
    let ptr = instance as usize as *mut Instance;
    if !ptr.is_null() {
        let _ = unsafe { Box::from_raw(ptr) };
    }
    Ok(())
}

fn dispatch_clients<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<()> {
    self::instance(instance, "dispatchClients")?.update();
    Ok(())
}

use noop as flush_display;

fn socket<'local>(env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<JString<'local>> {
    let method = self::instance(instance, "socket")?.method();
    let name = match method {
        capture::CaptureMethod::Wgc => "Windows window capture (WGC)",
        capture::CaptureMethod::PrintWindow => "Windows window capture (PrintWindow)",
    };
    Ok(JString::new(env, name)?)
}

fn x11_display<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<JString<'local>> {
    Ok(JString::null())
}

fn send_frame<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _surface: jlong) -> R<()> {
    Ok(())
}

fn update_surface_data<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    instance: jlong,
    jsurface: WLCSurface<'local>,
) -> R<()> {
    let instance = self::instance(instance, "updateSurfaceData")?;
    let handle = jsurface.handle(env)?;
    let Some(window) = instance.find_mut(handle) else { return Ok(()) };

    if !window.alive {
        jsurface.remove_buffer(env)?;
        return Ok(());
    }

    if window.frame_new
        && let Some(frame) = &window.frame
    {
        // The Java side uploads the pixels right away, so the frame only has to live
        // through this call. Format 1 is XRGB8888: captured alpha is ignored.
        jsurface.attach_shm_buffer(
            env,
            frame.data.as_ptr() as usize as jlong,
            frame.width,
            frame.height,
            1,
            frame.width * 4,
        )?;
        jsurface.clear_damage(env)?;
        jsurface.add_buffer_damage(env, 0, 0, frame.width, frame.height)?;
        window.frame_new = false;
    }

    Ok(())
}

fn toplevels<'local>(env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<JLongArray<'local>> {
    let instance = self::instance(instance, "toplevels")?;
    let handles: Vec<jlong> = instance
        .windows
        .iter()
        .filter(|w| w.alive && w.kind == Kind::Toplevel)
        .map(|w| w.handle())
        .collect();
    long_array(env, &handles)
}

fn toplevel_surface<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, handle: jlong) -> R<jlong> {
    Ok(window_ref(handle).map(|w| w.surface_handle()).unwrap_or(0))
}

fn toplevel_title<'local>(env: &mut Env<'local>, _class: JClass<'local>, handle: jlong) -> R<JString<'local>> {
    match window_ref(handle) {
        Some(w) => Ok(JString::new(env, &w.title)?),
        None => Ok(JString::null()),
    }
}

fn toplevel_app_id<'local>(env: &mut Env<'local>, _class: JClass<'local>, handle: jlong) -> R<JString<'local>> {
    match window_ref(handle) {
        Some(w) if !w.app_id.is_empty() => Ok(JString::new(env, &w.app_id)?),
        _ => Ok(JString::null()),
    }
}

fn toplevel_pid<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, handle: jlong) -> R<jint> {
    let instance = self::instance(instance, "toplevelPID")?;
    Ok(instance.find(handle).map(|w| w.pid as jint).unwrap_or(-1))
}

fn toplevel_resize<'local>(
    _env: &mut Env<'local>,
    _class: JClass<'local>,
    handle: jlong,
    width: jint,
    height: jint,
    _interactive: jboolean,
) -> R<()> {
    if width > 0 && height > 0 && let Some(w) = window_ref(handle) {
        w.resize(width, height);
    }
    Ok(())
}

fn toplevel_resize_ovr<'local>(env: &mut Env<'local>, class: JClass<'local>, handle: jlong, width: jint, height: jint) -> R<()> {
    toplevel_resize(env, class, handle, width, height, false)
}

use empty_longs as minimize_req;
use empty_longs as maximize_req;
use empty_longs as unmaximize_req;
use empty_longs as fullscreen_req;
use empty_longs as unfullscreen_req;
use empty_longs as fullscreened;
use empty_longs as dmabufs;
use null_ints as move_request;
use null_ints as resize_request;
use null_ints as check_dnd_request;

// Maximizing and fullscreen fill the space the game offers to windows
fn toplevel_maximize<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, handle: jlong) -> R<()> {
    let instance = self::instance(instance, "toplevelMaximize")?;
    let (width, height) = instance.output_bounds;
    if let Some(w) = instance.find(handle) {
        w.resize(width, height);
    }
    Ok(())
}

fn toplevel_fullscreen<'local>(env: &mut Env<'local>, class: JClass<'local>, instance: jlong, handle: jlong) -> R<()> {
    toplevel_maximize(env, class, instance, handle)
}

fn popups<'local>(env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<JLongArray<'local>> {
    let instance = self::instance(instance, "popups")?;
    let handles: Vec<jlong> = instance
        .windows
        .iter()
        .filter(|w| w.alive && matches!(w.kind, Kind::Popup { .. }))
        .map(|w| w.handle())
        .collect();
    long_array(env, &handles)
}

use toplevel_surface as popup_surface;

fn popup_parent<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, handle: jlong) -> R<jlong> {
    let instance = self::instance(instance, "popupParent")?;
    let Some(popup) = instance.find(handle) else { return Ok(0) };
    let Kind::Popup { parent } = popup.kind else { return Ok(0) };
    Ok(instance.find_hwnd(parent).map(|p| p.handle()).unwrap_or(0))
}

fn popup_offset<'local>(env: &mut Env<'local>, _class: JClass<'local>, handle: jlong) -> R<JIntArray<'local>> {
    // The offset is computed during the update, when the parent can be looked up
    let offset = window_ref(handle).map(|w| w.popup_offset).unwrap_or([0, 0]);
    int_array(env, &offset)
}

fn surface_xdg_geometry<'local>(env: &mut Env<'local>, _class: JClass<'local>, handle: jlong) -> R<JIntArray<'local>> {
    match window_ref(handle) {
        Some(w) if w.geometry[2] > 0 && w.geometry[3] > 0 => int_array(env, &w.geometry),
        _ => Ok(JIntArray::null()),
    }
}

fn check_import_dmabuf<'local>(_env: &mut Env<'local>, _this: WaylandCraftBridge<'local>, _instance: jlong) -> R<()> {
    Ok(())
}

fn release_buffer<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _handle: jlong) -> R<()> {
    Ok(())
}

fn sync_dmabuf_planes<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _handle: jlong, _end: jboolean) -> R<()> {
    Ok(())
}

// Every window is a single surface without subsurfaces
fn update_surface_tree<'local>(
    env: &mut Env<'local>,
    _this: WaylandCraftBridge<'local>,
    _instance: jlong,
    surface: WLCSurface<'local>,
) -> R<WLCSurface<'local>> {
    surface.set_parent_handle(env, 0)?;
    surface.set_next_child(env, WLCSurface::null())?;
    surface.set_prev_child(env, WLCSurface::null())?;
    surface.set_visited(env, true)?;
    surface.set_xoff(env, 0)?;
    surface.set_yoff(env, 0)?;
    Ok(surface)
}

fn check_input_region<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _surface: jlong, _x: jdouble, _y: jdouble) -> R<jboolean> {
    Ok(true)
}

fn pointer_motion<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, x: jdouble, y: jdouble) -> R<()> {
    let instance = self::instance(instance, "pointerMotion")?;
    let Some(hwnd) = instance.pointer_focus else { return Ok(()) };
    let Some(window) = instance.find_hwnd(hwnd) else { return Ok(()) };
    let point = window.surface_to_client(x, y);
    instance.input.motion(hwnd, point);
    Ok(())
}

fn pointer_motion_focus<'local>(
    env: &mut Env<'local>,
    class: JClass<'local>,
    instance: jlong,
    surface: jlong,
    x: jdouble,
    y: jdouble,
) -> R<()> {
    let inst = self::instance(instance, "pointerMotionFocus")?;
    let hwnd = inst.find(surface).filter(|w| w.alive).map(|w| w.hwnd);
    if hwnd != inst.pointer_focus {
        inst.input.leave();
    }
    inst.pointer_focus = hwnd;
    pointer_motion(env, class, instance, x, y)
}

fn pointer_rel_motion<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _dx: jdouble, _dy: jdouble) -> R<()> {
    Ok(())
}

fn maybe_pointer_lock<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _surface: jlong) -> R<jboolean> {
    Ok(false)
}

use noop as pointer_unlock;

fn pointer_leave<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<()> {
    let instance = self::instance(instance, "pointerLeave")?;
    instance.pointer_focus = None;
    instance.input.leave();
    Ok(())
}

fn pointer_button<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, button: jint, state: jint) -> R<jint> {
    let instance = self::instance(instance, "pointerButton")?;
    instance.input.button(button, state != 0);
    Ok(instance.next_serial())
}

fn pointer_axis<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, axis: jint, value: jdouble) -> R<()> {
    let instance = self::instance(instance, "pointerAxis")?;
    instance.input.scroll(axis == 1, value);
    Ok(())
}

fn cursor_shape<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<jint> {
    // CursorShape.DEFAULT
    Ok(1)
}

fn keyboard_focus<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, handle: jlong) -> R<()> {
    let instance = self::instance(instance, "keyboardFocus")?;
    let focus = instance.find(handle).filter(|w| w.alive).map(|w| w.hwnd);
    if focus != instance.keyboard_focus {
        instance.keyboard_focus = focus;
    }
    Ok(())
}

fn keyboard_activate<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<()> {
    self::instance(instance, "keyboardActivate")?.keyboard_active = true;
    Ok(())
}

fn keyboard_deactivate<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<()> {
    let instance = self::instance(instance, "keyboardDeactivate")?;
    instance.keyboard_active = false;
    instance.input.release_all();
    Ok(())
}

fn keyboard_input<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, scancode: jint, action: jint) -> R<()> {
    let instance = self::instance(instance, "keyboardInput")?;
    let Some(hwnd) = instance.keyboard_focus else { return Ok(()) };
    instance.input.key(hwnd, scancode as u32, action != 0);
    Ok(())
}

fn keyboard_update<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, scancode: jint, pressed: jboolean) -> R<()> {
    self::instance(instance, "keyboardUpdate")?.input.update_key(scancode as u32, pressed);
    Ok(())
}

fn output_size<'local>(env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<JIntArray<'local>> {
    let (w, h) = self::instance(instance, "outputSize")?.output_size;
    int_array(env, &[w, h])
}

fn output_bounds<'local>(env: &mut Env<'local>, _class: JClass<'local>, instance: jlong) -> R<JIntArray<'local>> {
    let (w, h) = self::instance(instance, "outputBounds")?.output_bounds;
    int_array(env, &[w, h])
}

fn output_resize<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, width: jint, height: jint) -> R<()> {
    self::instance(instance, "outputResize")?.output_size = (width, height);
    Ok(())
}

fn output_set_bounds<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, width: jint, height: jint) -> R<()> {
    self::instance(instance, "outputSetBounds")?.output_bounds = (width, height);
    Ok(())
}

// Surfaces belong to their window and are freed with it
fn free_surface<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _handle: jlong) -> R<()> {
    Ok(())
}

fn free_toplevel<'local>(_env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, handle: jlong) -> R<()> {
    self::instance(instance, "freeToplevel")?.free(handle);
    Ok(())
}

use free_toplevel as free_popup;

fn load_desktop_entry<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _path: JString<'local>) -> R<JRawDesktopEntry<'local>> {
    Ok(JRawDesktopEntry::null())
}

fn load_desktop_entries<'local>(env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<JObjectArray<'local, JRawDesktopEntry<'local>>> {
    let apps = apps::load_apps();
    let array = JObjectArray::<JRawDesktopEntry>::new(env, apps.len(), &JRawDesktopEntry::null())?;
    for (index, app) in apps.iter().enumerate() {
        let app_id = JString::new(env, &app.app_id)?;
        let name = JString::new(env, &app.name)?;
        let icon_path = match &app.icon_path {
            Some(path) => JString::new(env, path)?,
            None => JString::null(),
        };
        let no_strings = JObjectArray::<JString>::new(env, 0, &JString::null())?;
        let no_strings2 = JObjectArray::<JString>::new(env, 0, &JString::null())?;
        let entry = JRawDesktopEntry::new(
            env,
            app_id,
            name,
            JString::null(),
            JString::null(),
            false,
            JString::null(),
            no_strings,
            no_strings2,
            true,
            icon_path,
        )?;
        array.set_element(env, index, &entry)?;
    }
    Ok(array)
}

// App icons are converted to PNG, so no SVGs need rendering
fn render_svg<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _path: JString<'local>, _width: jint, _height: jint, _ptr: jlong) -> R<jboolean> {
    Ok(false)
}

fn exec_app<'local>(env: &mut Env<'local>, _class: JClass<'local>, instance: jlong, app_id: JString<'local>) -> R<jboolean> {
    let instance = self::instance(instance, "execApp")?;
    let app_id = app_id.try_to_string(env)?;
    match apps::launch(&app_id) {
        Some(apps::Launched::Process(pid)) => {
            instance.track_process(pid);
            Ok(true)
        }
        Some(apps::Launched::Unknown) => {
            instance.expect_unknown_launch();
            Ok(true)
        }
        None => Ok(false),
    }
}

fn set_preferred_terminal<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _cmd: JString<'local>) -> R<()> {
    Ok(())
}

use set_preferred_terminal as set_env_overrides;
use noop as set_keymap_default;

// Windows translates keys with the user's own layout; there is no keymap to manage
fn export_keymap<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<JString<'local>> {
    Ok(JString::null())
}

fn set_keymap_from_str<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _keymap: JString<'local>) -> R<jboolean> {
    Ok(true)
}

fn check_dnd_active<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<jboolean> {
    Ok(false)
}

use noop as dnd_cancel;
use noop as dnd_drop;

fn dnd_motion<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong, _surface: jlong, _x: jdouble, _y: jdouble) -> R<()> {
    Ok(())
}

fn dnd_icon<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _instance: jlong) -> R<jlong> {
    Ok(0)
}

fn drm_device_by_path<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _path: JString<'local>) -> R<jlong> {
    Err(BridgeError::Unsupported("drmDeviceByPath"))
}

fn drm_device_by_major_minor<'local>(_env: &mut Env<'local>, _class: JClass<'local>, _major: jint, _minor: jint) -> R<jlong> {
    Err(BridgeError::Unsupported("drmDeviceByMajorMinor"))
}
