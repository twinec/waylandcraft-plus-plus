use std::ops::DerefMut;
use crate::{WaylandCraft, wlc_init};
use smithay::{
    wayland::{
        shell::xdg::ToplevelSurface,
        compositor::{
            SurfaceAttributes, BufferAssignment, with_states,
            with_surface_tree_upward, TraversalAction, SubsurfaceCachedState,
        },
        shm::with_buffer_contents,
    },
    reexports::{
        wayland_server::{
            protocol::{
                wl_surface::WlSurface,
            },
        },
    },
};
use jni::{
    objects::{JClass, JObject, JValue},
    sys::{jlong, jstring, jarray, jsize, jint, jvalue},
    signature::{ReturnType, Primitive},
    JNIEnv,
};

pub(crate) struct BridgeState {
    toplevels: Vec<Box<ToplevelSurface>>,
    surfaces: Vec<Box<WlSurface>>,
}

impl BridgeState {
    pub fn new() -> Self {
        BridgeState {
            toplevels: vec![],
            surfaces: vec![],
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
    _class: JClass<'l>
) -> jlong {
    let instance = match wlc_init() {
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

#[allow(non_upper_case_globals)]
const WLCSurface_class: &str = "dev/evvie/waylandcraft/bridge/WLCSurface";

#[allow(non_upper_case_globals)]
const WaylandCraftBridge_class: &str =
    "dev/evvie/waylandcraft/bridge/WaylandCraftBridge";

fn jptr_to_wlsurface(ptr: jlong) -> &'static mut WlSurface {
    let ptr: *mut WlSurface = (ptr as usize) as *mut WlSurface;
    unsafe { &mut *ptr }
}

#[unsafe(no_mangle)]
pub extern "system"
fn Java_dev_evvie_waylandcraft_bridge_WaylandCraftBridge_updateSurfaceData<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    obj: JObject<'l>
) {
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
            let _ = with_buffer_contents(buf, |ptr, _len, metadata| {
                let width = metadata.width as jint;
                let height = metadata.height as jint;
                unsafe {
                    let ptr = ptr.offset(metadata.offset as isize);
                    let jptr = (ptr as usize) as jlong;
                    let sig = "(JII)V";
                    env.call_method_unchecked(
                        &obj,
                        (WLCSurface_class, "attachShmBuffer", sig),
                        ReturnType::Primitive(Primitive::Void),
                        &[
                            jvalue { j: jptr },
                            jvalue { i: width },
                            jvalue { i: height },
                        ]
                    ).unwrap();
                }
            });
            buf.release();
            attr.buffer = None;
        }
    });
}

fn jptr_to_toplevel(ptr: jlong) -> &'static mut ToplevelSurface {
    let ptr: *mut ToplevelSurface = (ptr as usize) as *mut ToplevelSurface;
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
) {
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
