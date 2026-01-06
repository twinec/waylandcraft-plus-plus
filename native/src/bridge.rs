use std::ops::DerefMut;
use crate::{WaylandCraft, wlc_init};
use crate::egl::{EGLHelper, EGLDisplay};
use smithay::{
    wayland::{
        shell::xdg::{
            ToplevelSurface, PopupSurface, XDG_POPUP_ROLE,
            XdgPopupSurfaceData, SurfaceCachedState, XdgToplevelSurfaceData
        },
        compositor::{
            SurfaceAttributes, BufferAssignment, with_states, SurfaceData,
            with_surface_tree_upward, TraversalAction, SubsurfaceCachedState,
            get_role
        },
        shm::{self, with_buffer_contents},
        viewporter::{ViewportCachedState, ensure_viewport_valid},
        single_pixel_buffer::get_single_pixel_buffer,
        dmabuf::get_dmabuf,
    },
    utils::{Point, Logical, Size},
    backend::{
        allocator::{
            dmabuf::WeakDmabuf,
            Buffer,
        },
    },
    reexports::{
        wayland_server::{
            protocol::{
                wl_surface::WlSurface,
                wl_buffer::WlBuffer,
                wl_pointer::{ButtonState, Axis},
                wl_keyboard::KeyState,
            },
        },
        wayland_protocols::xdg::shell::server::xdg_toplevel,
    },
};
use jni::{
    objects::{JClass, JObject, JValue},
    sys::{
        jlong, jstring, jarray, jsize, jint, jvalue, jdouble, jboolean, jobject,
        jbyte, JNI_TRUE
    },
    signature::{ReturnType, Primitive},
    JNIEnv,
};

pub(crate) struct BridgeState {
    /* Handle collections */
    toplevels: Vec<Box<ToplevelSurface>>,
    popups: Vec<Box<PopupSurface>>,
    surfaces: Vec<Box<WlSurface>>,
    dmabufs: Vec<Box<WeakDmabuf>>,
}

impl BridgeState {
    pub fn new() -> Self {
        BridgeState {
            toplevels: vec![],
            popups: vec![],
            surfaces: vec![],
            dmabufs: vec![],
        }
    }
}

fn jptr_to_instance(ptr: jlong) -> &'static mut WaylandCraft<'static> {
    let ptr: *mut WaylandCraft = (ptr as usize) as *mut WaylandCraft;
    unsafe { &mut *ptr }
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_init<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    proc_addr: jlong,
    dpy_ptr: jlong
) -> jlong {
    let dpy: EGLDisplay = (dpy_ptr as usize) as EGLDisplay;
    let egl = EGLHelper::new(dpy, proc_addr as usize);

    let instance = match wlc_init(egl) {
        Ok(i) => i,
        Err(err) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                err.to_string()
            );
            return 0;
        }
    };

    let instance_box: Box<WaylandCraft> = Box::new(instance);
    let ptr: *mut WaylandCraft = Box::into_raw(instance_box);
    let addr: u64 = ptr.addr() as u64;
    addr as i64
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_update<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) {
    let instance = jptr_to_instance(ptr);
    instance.update();
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_socket<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) -> jstring {
    let instance = jptr_to_instance(ptr);
    let socket = instance.state.socket.to_str().unwrap();
    env.new_string(socket).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_sendFrame<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) {
    let instance = jptr_to_instance(ptr);
    instance.send_frame();
}

// Get or insert an element and return its handle
fn insert_get_handle<T>(vec: &mut Vec<Box<T>>, elem: &T) -> jlong
    where T: Clone + PartialEq
{
    if !vec.iter().any(|b| **b == *elem) {
        vec.push(Box::new(elem.clone()));
    }

    let ptr: &mut T = vec
        .iter_mut()
        .find(|r| ***r == *elem)
        .unwrap();
    ((ptr as *mut T) as usize) as jlong
}

// Get an element and return its handle
// Element has to be in the list, otherwise this functions panics
fn get_handle<T>(vec: &Vec<Box<T>>, elem: &T) -> jlong
    where T: Clone + PartialEq
{
    let ptr: &T = vec
        .iter()
        .find(|r| ***r == *elem)
        .unwrap();
    ((ptr as *const T) as usize) as jlong
}

// Insert all elements that aren't in the list already
fn insert_all<T>(vec: &mut Vec<Box<T>>, elems: &[T])
    where T: Clone + PartialEq
{
    for elem in elems {
        if !vec.iter().any(|b| **b == *elem) {
            vec.push(Box::new(elem.clone()));
        }
    }
}

// Get handles of all elements in the list
fn get_all_handles<T>(vec: &mut Vec<Box<T>>) -> Vec<jlong>
    where T: Clone + PartialEq
{
    vec
        .iter_mut()
        .map(|r| ((&mut **r) as *mut T) as usize as jlong)
        .collect()
}

// Remove element from list and free it
fn remove_element<T>(vec: &mut Vec<Box<T>>, handle: jlong)
    where T: Clone + PartialEq
{
    let ptr: *mut T = (handle as usize) as *mut T;
    let elem: &mut T = unsafe { &mut *ptr };
    vec.retain(|e| **e != *elem);
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_toplevels<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) -> jarray {
    let instance = jptr_to_instance(ptr);

    insert_all(
        &mut instance.bridge.toplevels,
        instance.state.xdg_state.toplevel_surfaces()
    );

    instance.bridge.toplevels.retain(|t| t.alive());

    let toplevels = get_all_handles(&mut instance.bridge.toplevels);
    let array = env.new_long_array(toplevels.len() as jsize).unwrap();
    env.set_long_array_region(&array, 0, &toplevels).unwrap();
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_popups<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) -> jarray {
    let instance = jptr_to_instance(ptr);

    insert_all(
        &mut instance.bridge.popups,
        instance.state.xdg_state.popup_surfaces()
    );

    instance.bridge.popups.retain(|t| t.alive());

    let popups = get_all_handles(&mut instance.bridge.popups);
    let array = env.new_long_array(popups.len() as jsize).unwrap();
    env.set_long_array_region(&array, 0, &popups).unwrap();
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_minimized<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) -> jarray {
    let instance = jptr_to_instance(ptr);

    let handles: Vec<jlong> = instance
        .state
        .minimized_toplevels
        .iter()
        .filter(|t| t.alive())
        .map(|t| insert_get_handle(&mut instance.bridge.toplevels, t))
        .collect();

    instance.state.minimized_toplevels.clear();

    let array = env.new_long_array(handles.len() as jsize).unwrap();
    env.set_long_array_region(&array, 0, &handles).unwrap();
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_maximized<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) -> jarray {
    let instance = jptr_to_instance(ptr);

    let handles: Vec<jlong> = instance
        .state
        .maximized_toplevels
        .iter()
        .filter(|t| t.alive())
        .map(|t| insert_get_handle(&mut instance.bridge.toplevels, t))
        .collect();

    instance.state.maximized_toplevels.clear();

    let array = env.new_long_array(handles.len() as jsize).unwrap();
    env.set_long_array_region(&array, 0, &handles).unwrap();
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_unmaximized<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) -> jarray {
    let instance = jptr_to_instance(ptr);

    let handles: Vec<jlong> = instance
        .state
        .unmaximized_toplevels
        .iter()
        .filter(|t| t.alive())
        .map(|t| insert_get_handle(&mut instance.bridge.toplevels, t))
        .collect();

    instance.state.unmaximized_toplevels.clear();

    let array = env.new_long_array(handles.len() as jsize).unwrap();
    env.set_long_array_region(&array, 0, &handles).unwrap();
    array.into_raw()
}

#[allow(non_upper_case_globals)]
const WLCSurface_class: &str = "dev/evvie/waylandcraft/bridge/WLCSurface";

#[allow(non_upper_case_globals)]
const WaylandCraftBridge_class: &str =
    "dev/evvie/waylandcraft/bridge/WaylandCraftBridge";

fn jptr_to_wlsurface(ptr: jlong) -> &'static mut WlSurface {
    let ptr: *mut WlSurface = (ptr as usize) as *mut WlSurface;
    unsafe { &mut *ptr }
}

enum BufferAttachResult {
    Success,
    Error,
    NotManaged,
}

impl BufferAttachResult {
    fn not_managed(&self) -> bool {
        match self {
            Self::NotManaged => true,
            _ => false,
        }
    }
}

fn try_attach_shm(
    env: &mut JNIEnv,
    obj: &JObject,
    buf: &WlBuffer,
    surf_data: &SurfaceData
) -> BufferAttachResult {
    let r = with_buffer_contents(buf, |ptr, _len, metadata| {
        let width = metadata.width as jint;
        let height = metadata.height as jint;
        let format = (metadata.format as u32) as jint;
        ensure_viewport_valid(surf_data, Size::new(width, height));

        unsafe {
            let ptr = ptr.offset(metadata.offset as isize);
            let jptr = (ptr as usize) as jlong;
            env.call_method_unchecked(
                obj,
                (WLCSurface_class, "attachShmBuffer", "(JIII)V"),
                ReturnType::Primitive(Primitive::Void),
                &[
                    jvalue { j: jptr },
                    jvalue { i: width },
                    jvalue { i: height },
                    jvalue { i: format }
                ]
            ).unwrap();
        }
    });

    match r {
        Ok(_) => BufferAttachResult::Success,
        Err(shm::BufferAccessError::NotManaged) =>
            BufferAttachResult::NotManaged,
        Err(_) => BufferAttachResult::Error,
    }
}

fn try_attach_single_pixel(
    env: &mut JNIEnv,
    obj: &JObject,
    buf: &WlBuffer,
    surf_data: &SurfaceData
) -> BufferAttachResult {
    let pix = match get_single_pixel_buffer(buf) {
        Ok(p) => p,
        Err(_) => {return BufferAttachResult::NotManaged;},
    };

    ensure_viewport_valid(surf_data, Size::new(1, 1));

    let [r, g, b, a] = pix.rgba8888();

    unsafe {
        env.call_method_unchecked(
            obj,
            (WLCSurface_class, "attachSinglePixelBuffer", "(BBBB)V"),
            ReturnType::Primitive(Primitive::Void),
            &[
                jvalue { b: r as jbyte },
                jvalue { b: g as jbyte },
                jvalue { b: b as jbyte },
                jvalue { b: a as jbyte }
            ]
        ).unwrap();
    }

    BufferAttachResult::Success
}

fn try_attach_dmabuf(
    instance: &mut WaylandCraft,
    env: &mut JNIEnv,
    obj: &JObject,
    buf: &WlBuffer,
    surf_data: &SurfaceData
) -> BufferAttachResult {
    let dmabuf = match get_dmabuf(buf) {
        Ok(d) => d,
        Err(_) => return BufferAttachResult::NotManaged,
    };

    let width = dmabuf.width() as jint;
    let height = dmabuf.height() as jint;
    ensure_viewport_valid(surf_data, Size::new(width, height));

    let weak = dmabuf.weak();
    let handle = insert_get_handle(&mut instance.bridge.dmabufs, &weak);

    let already_attached = unsafe {
        env.call_method_unchecked(
            obj,
            (WLCSurface_class, "attachDmabuf", "(J)Z"),
            ReturnType::Primitive(Primitive::Boolean),
            &[
                jvalue { j: handle }
            ]
        ).unwrap().z().unwrap()
    };

    if already_attached {
        return BufferAttachResult::Success;
    }

    let image = instance.egl.dmabuf_to_image(dmabuf);
    println!("Got EGLImage: {:?}", image);

    unsafe {
        env.call_method_unchecked(
            obj,
            (WLCSurface_class, "attachNewDmabuf", "(JJII)V"),
            ReturnType::Primitive(Primitive::Void),
            &[
                jvalue { j: handle },
                jvalue { j: (image as usize) as jlong },
                jvalue { i: width },
                jvalue { i: height }
            ]
        ).unwrap();
    }

    BufferAttachResult::Success
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_dmabufs<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) -> jarray {
    let instance = jptr_to_instance(ptr);
    instance.bridge.dmabufs.retain(|d| !d.is_gone());

    let dmabufs = get_all_handles(&mut instance.bridge.dmabufs);
    let array = env.new_long_array(dmabufs.len() as jsize).unwrap();
    env.set_long_array_region(&array, 0, &dmabufs).unwrap();
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_updateSurfaceData<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    obj: JObject<'l>
) {
    let instance = jptr_to_instance(ptr);
    let handle: jlong = env.get_field_unchecked(
        &obj,
        (WLCSurface_class, "handle", "J"),
        ReturnType::Primitive(Primitive::Long)
    ).unwrap().j().unwrap();

    let surface = jptr_to_wlsurface(handle);
    with_states(surface, |data| {
        let mut attr_guard = data
            .cached_state
            .get::<SurfaceAttributes>();
        let attr = attr_guard
            .deref_mut()
            .current();

        let maybe_buf = if let Some(assign) = &attr.buffer {
            match assign {
                BufferAssignment::NewBuffer(b) => Some(b),
                BufferAssignment::Removed => None,
            }
        } else {
            None
        };
        if let Some(buf) = maybe_buf {
            // First try shm
            let mut r = try_attach_shm(&mut env, &obj, &buf, &data);

            // If not managed by shm, try single pixel
            if r.not_managed() {
                r = try_attach_single_pixel(&mut env, &obj, &buf, &data);
            }

            // If not managed by single pixel, try dmabuf
            if r.not_managed() {
                r = try_attach_dmabuf(instance, &mut env, &obj, &buf, &data);
            }

            let _ = r;

            // Done with buffer attachment
            buf.release();
            attr.buffer = None;
        }

        let mut vp_data_guard = data
            .cached_state
            .get::<ViewportCachedState>();
        let vp_data = vp_data_guard
            .deref_mut()
            .current();

        if let Some(src) = vp_data.src {
            unsafe {
                env.call_method_unchecked(
                    &obj,
                    (WLCSurface_class, "setViewportSrc", "(DDDD)V"),
                    ReturnType::Primitive(Primitive::Void),
                    &[
                        jvalue { d: src.loc.x },
                        jvalue { d: src.loc.y },
                        jvalue { d: src.size.w },
                        jvalue { d: src.size.h },
                    ]
                ).unwrap();
            }
        }

        if let Some(dst) = vp_data.dst {
            unsafe {
                env.call_method_unchecked(
                    &obj,
                    (WLCSurface_class, "setViewportDst", "(II)V"),
                    ReturnType::Primitive(Primitive::Void),
                    &[
                        jvalue { i: dst.w },
                        jvalue { i: dst.h },
                    ]
                ).unwrap();
            }
        }
    });
}

fn jptr_to_toplevel(ptr: jlong) -> &'static mut ToplevelSurface {
    let ptr: *mut ToplevelSurface = (ptr as usize) as *mut ToplevelSurface;
    unsafe { &mut *ptr }
}

fn jptr_to_popup(ptr: jlong) -> &'static mut PopupSurface {
    let ptr: *mut PopupSurface = (ptr as usize) as *mut PopupSurface;
    unsafe { &mut *ptr }
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_toplevelSurface<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong
) -> jlong {
    let instance = jptr_to_instance(ptr);
    let toplevel: &mut ToplevelSurface = jptr_to_toplevel(handle);
    let surface: &WlSurface = toplevel.wl_surface();

    insert_get_handle(&mut instance.bridge.surfaces, surface)
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_popupSurface<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong
) -> jlong {
    let instance = jptr_to_instance(ptr);
    let popup: &mut PopupSurface = jptr_to_popup(handle);
    let surface: &WlSurface = popup.wl_surface();

    insert_get_handle(&mut instance.bridge.surfaces, surface)
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_popupParent<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong
) -> jlong {
    let instance = jptr_to_instance(ptr);
    let popup: &mut PopupSurface = jptr_to_popup(handle);
    let parent_surface: Option<WlSurface> = popup.get_parent_surface();
    if parent_surface.is_none() {
        return 0;
    }
    let parent_surface: WlSurface = parent_surface.unwrap();

    for toplevel in &instance.bridge.toplevels {
        if *toplevel.wl_surface() == parent_surface {
            return get_handle(&instance.bridge.toplevels, toplevel);
        }
    }

    for popup in &instance.bridge.popups {
        if *popup.wl_surface() == parent_surface {
            return get_handle(&instance.bridge.popups, popup);
        }
    }

    return 0;
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_popupOffset<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong
) -> jarray {
    let popup: &mut PopupSurface = jptr_to_popup(handle);
    let surface = popup.wl_surface();

    let mut offset: [jint; 2] = [0, 0];

    if get_role(surface) == Some(XDG_POPUP_ROLE) {
        with_states(surface, |states| {
            let attr_guard = states
                .data_map
                .get::<XdgPopupSurfaceData>()
                .unwrap()
                .lock()
                .unwrap();
            let position = attr_guard
                .current
                .geometry
                .loc;
            offset[0] = position.x;
            offset[1] = position.y;
        });
    }

    let array = env.new_int_array(2).unwrap();
    env.set_int_array_region(&array, 0, &offset).unwrap();
    array.into_raw()
}

fn get_or_create_surface<'l>(
    env: &mut JNIEnv<'l>,
    state: &mut BridgeState,
    bridge_obj: &JObject<'l>,
    surface: &WlSurface
) -> JObject<'l> {
    let handle = insert_get_handle(&mut state.surfaces, surface);
    let sig = "(J)Ldev/evvie/waylandcraft/bridge/WLCSurface;";
    unsafe {
        env.call_method_unchecked(
            bridge_obj,
            (WaylandCraftBridge_class, "getOrCreateSurface", sig),
            ReturnType::Object,
            &[
                jvalue { j: handle },
            ]
        ).unwrap().l().unwrap()
    }
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_updateSurfaceTree<'l>(
    mut env: JNIEnv<'l>,
    bridge_obj: JObject<'l>,
    root: JObject<'l>
) -> jobject {
    let instance_ptr: jlong = env.get_field_unchecked(
        &bridge_obj,
        (WaylandCraftBridge_class, "instance", "J"),
        ReturnType::Primitive(Primitive::Long)
    ).unwrap().j().unwrap();

    let instance = jptr_to_instance(instance_ptr);

    let handle: jlong = env.get_field_unchecked(
        &root,
        (WLCSurface_class, "handle", "J"),
        ReturnType::Primitive(Primitive::Long)
    ).unwrap().j().unwrap();

    let surface = jptr_to_wlsurface(handle);

    let mut last_child: JObject = JObject::null();

    with_surface_tree_upward(
        surface,
        None,
        |surface, _data, _parent| {
            TraversalAction::DoChildren(Some(surface.clone()))
        },
        |surface, data, parent| {
            let obj = get_or_create_surface(
                &mut env,
                &mut instance.bridge,
                &bridge_obj,
                surface
            );

            // Set the WLCSurface parentHandle
            let parent_handle = if let Some(p) = parent {
                insert_get_handle(
                    &mut instance.bridge.surfaces,
                    &p
                )
            } else { 0 };
            env.set_field_unchecked(
                &obj,
                (WLCSurface_class, "parentHandle", "J"),
                JValue::Long(parent_handle),
            ).unwrap();

            // Set last child to point to this current surface
            if !last_child.as_raw().is_null() {
                env.set_field_unchecked(
                    &last_child,
                    (
                        WLCSurface_class,
                        "nextChild",
                        "Ldev/evvie/waylandcraft/bridge/WLCSurface;"
                    ),
                    JValue::Object(&obj)
                ).unwrap();
            }

            // Set this surfaces nextChild to null
            env.set_field_unchecked(
                &obj,
                (
                    WLCSurface_class,
                    "nextChild",
                    "Ldev/evvie/waylandcraft/bridge/WLCSurface;"
                ),
                JValue::Object(&JObject::null())
            ).unwrap();

            // Set this surfaces prevChild to the last child
            env.set_field_unchecked(
                &obj,
                (
                    WLCSurface_class,
                    "prevChild",
                    "Ldev/evvie/waylandcraft/bridge/WLCSurface;"
                ),
                JValue::Object(&last_child)
            ).unwrap();

            // Mark this surface as visited
            env.set_field_unchecked(
                &obj,
                (WLCSurface_class, "visited", "Z"),
                JValue::Bool(1)
            ).unwrap();

            // Set subsurface location
            let (sx, sy) = if data.cached_state.has::<SubsurfaceCachedState>() {
                let mut subattr_guard = data
                    .cached_state
                    .get::<SubsurfaceCachedState>();
                let subattr = subattr_guard
                    .deref_mut()
                    .current();
                (subattr.location.x, subattr.location.y)

            } else { (0, 0) };

            env.set_field_unchecked(
                &obj,
                (WLCSurface_class, "xoff", "I"),
                JValue::Int(sx)
            ).unwrap();

            env.set_field_unchecked(
                &obj,
                (WLCSurface_class, "yoff", "I"),
                JValue::Int(sy)
            ).unwrap();

            last_child = obj;
        },
        |_surface, _data, _parent| true
    );

    last_child.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_pointerMotion<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong,
    x: jdouble,
    y: jdouble
) {
    let instance = jptr_to_instance(ptr);
    let surface = jptr_to_wlsurface(handle);

    instance.state.seat.pointer_motion(surface.clone(), x, y);
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_pointerLeave<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong
) {
    let instance = jptr_to_instance(ptr);
    instance.state.seat.pointer_unfocus();
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_pointerButton<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    button: jint,
    state: jint
) {
    let instance = jptr_to_instance(ptr);

    let state = match state {
        0 => ButtonState::Released,
        1 => ButtonState::Pressed,
        _ => {return;}
    };

    instance.state.seat.pointer_button(button as u32, state);
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_pointerAxis<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    axis: jint,
    value: jdouble
) {
    let instance = jptr_to_instance(ptr);

    let axis = match axis {
        0 => Axis::VerticalScroll,
        1 => Axis::HorizontalScroll,
        _ => {return;}
    };

    instance.state.seat.pointer_axis(axis, value);
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_keyboardFocus<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong
) {
    let instance = jptr_to_instance(ptr);
    let toplevel: Option<ToplevelSurface> = if handle != 0 {
        Some(jptr_to_toplevel(handle).clone())
    } else { None };

    let surface = toplevel.as_ref().map(|t| t.wl_surface().clone());

    match surface {
        Some(s) => instance.state.seat.keyboard_focus(s),
        None => instance.state.seat.keyboard_unfocus(),
    };

    instance.state.xdg_state.toplevel_surfaces().iter().for_each(|t| {
        t.with_pending_state(|state| {
            state.states.unset(xdg_toplevel::State::Activated);
        });
    });

    toplevel.map(|t| t.with_pending_state(|state| {
        state.states.set(xdg_toplevel::State::Activated);
    }));

    instance.state.xdg_state.toplevel_surfaces().iter().for_each(|t| {
        t.send_pending_configure();
    });
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_keyboardInput<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    scancode: jint,
    action: jint
) {
    let instance = jptr_to_instance(ptr);

    let scancode = scancode as u32;
    let action = match action {
        0 => KeyState::Released,
        1 => KeyState::Pressed,
        _ => {return;}
    };

    instance.state.seat.keyboard_key(scancode, action);
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_keyboardUpdate<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    scancode: jint,
    press: jboolean
) {
    let instance = jptr_to_instance(ptr);
    instance.state.seat.keyboard_update_xkb(
        scancode as u32,
        press == JNI_TRUE,
    );
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_checkInputRegion<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x: jdouble,
    y: jdouble
) -> jboolean {
    let surface = jptr_to_wlsurface(handle);
    let point: Point<f64, Logical> = Point::new(x, y);

    with_states(surface, |data| {
        let mut attr_guard = data
            .cached_state
            .get::<SurfaceAttributes>();
        let attr = attr_guard
            .deref_mut()
            .current();
        if let Some(r) = &attr.input_region {
            r.contains(point.to_i32_floor())
        } else {
            true
        }
    }) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_surfaceXDGGeometry<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong
) -> jarray {
    let surface = jptr_to_wlsurface(handle);

    let geometry: Option<[jint; 4]> = with_states(&surface, |states| {
        let mut guard = states.cached_state.get::<SurfaceCachedState>();
        guard
            .current()
            .geometry
            .map(|r| [r.loc.x, r.loc.y, r.size.w, r.size.h])
    });

    if let Some(geometry) = geometry {
        let array = env.new_int_array(4).unwrap();
        env.set_int_array_region(&array, 0, &geometry).unwrap();
        array.into_raw()
    } else { std::ptr::null_mut() }
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_toplevelTitle<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong
) -> jstring {
    let toplevel = jptr_to_toplevel(handle);
    let surface = toplevel.wl_surface();

    let title = with_states(&surface, |states| {
        let attr_guard = states
            .data_map
            .get::<XdgToplevelSurfaceData>()
            .unwrap()
            .lock()
            .unwrap();
        attr_guard.title.clone()
    });

    if let Some(title) = title {
        env.new_string(&title).unwrap().into_raw()
    } else { std::ptr::null_mut() }
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_toplevelAppID<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong
) -> jstring {
    let toplevel = jptr_to_toplevel(handle);
    let surface = toplevel.wl_surface();

    let app_id = with_states(&surface, |states| {
        let attr_guard = states
            .data_map
            .get::<XdgToplevelSurfaceData>()
            .unwrap()
            .lock()
            .unwrap();
        attr_guard.app_id.clone()
    });

    if let Some(app_id) = app_id {
        env.new_string(&app_id).unwrap().into_raw()
    } else { std::ptr::null_mut() }
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_toplevelResize<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    width: jint,
    height: jint,
    interactive: jboolean
) {
    let toplevel = jptr_to_toplevel(handle);

    toplevel.with_pending_state(|state| {
        state.size = Some(Size::new(width, height));
        state.states.unset(xdg_toplevel::State::Maximized);
        if interactive == JNI_TRUE {
            state.states.set(xdg_toplevel::State::Resizing);
        } else {
            state.states.unset(xdg_toplevel::State::Resizing);
        }
    });
    toplevel.send_pending_configure();
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_toplevelMaximize<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong
) {
    let toplevel = jptr_to_toplevel(handle);

    toplevel.with_pending_state(|state| {
        state.size = Some(Size::new(1920, 1080));
        state.states.set(xdg_toplevel::State::Maximized);
    });
    toplevel.send_pending_configure();
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_freeSurface<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong
) {
    let instance = jptr_to_instance(ptr);
    remove_element(&mut instance.bridge.surfaces, handle);
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_freeToplevel<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong
) {
    let instance = jptr_to_instance(ptr);
    remove_element(&mut instance.bridge.toplevels, handle);
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_freePopup<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    ptr: jlong,
    handle: jlong
) {
    let instance = jptr_to_instance(ptr);
    remove_element(&mut instance.bridge.popups, handle);
}
