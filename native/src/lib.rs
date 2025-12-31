use crate::bridge::BridgeState;
use crate::egl::EGLHelper;
use std::sync::Arc;
use std::ops::DerefMut;
use std::time::{SystemTime, UNIX_EPOCH, Duration};
use std::ffi::OsString;
use smithay::{
    reexports::{
        calloop::{
            generic::Generic as GenericEvent,
            self, EventLoop,
        },
        wayland_server::{
            self,
            backend::{ClientData, ClientId, DisconnectReason},
            protocol::{
                wl_surface::WlSurface,
                wl_buffer::WlBuffer,
                wl_seat::WlSeat,
            },
            Display, DisplayHandle,
        },
    },
    wayland::{
        socket::ListeningSocketSource,
        compositor::{
            CompositorState, CompositorClientState, CompositorHandler,
            SurfaceAttributes,
            with_surface_tree_downward, TraversalAction
        },
        buffer::BufferHandler,
        shm::{ShmState, ShmHandler},
        output::OutputHandler,
        shell::xdg::{
            XdgShellState, XdgShellHandler, ToplevelSurface, PopupSurface,
            PositionerState
        },
        selection::{
            data_device::{
                DataDeviceState, DataDeviceHandler, ClientDndGrabHandler,
                ServerDndGrabHandler,
            },
            SelectionHandler,
        },
        viewporter::ViewporterState,
        dmabuf::{
            DmabufState, DmabufGlobal, DmabufFeedbackBuilder, DmabufHandler,
            ImportNotifier
        },
        single_pixel_buffer::SinglePixelBufferState,
    },
    backend::{
        allocator::{
            dmabuf::Dmabuf,
        },
        drm::DrmNode,
    },
    output::{self, Output, PhysicalProperties, Subpixel},
    input::{
        keyboard::XkbConfig,
        SeatState, SeatHandler, Seat
    },
    utils::Serial,
    delegate_compositor, delegate_shm, delegate_output, delegate_seat,
    delegate_xdg_shell, delegate_data_device, delegate_viewporter,
    delegate_single_pixel_buffer, delegate_dmabuf
};

mod bridge;
mod egl;

pub(crate) struct WaylandCraft<'a> {
    pub state: WLCState,
    pub event_loop: EventLoop<'a, WLCState>,
    pub bridge: BridgeState,
    pub egl: EGLHelper,
}

pub struct WLCState {
    pub display_handle: DisplayHandle,
    pub socket: OsString,
    pub compositor_state: CompositorState,
    pub shm_state: ShmState,
    pub seat_state: SeatState<Self>,
    pub xdg_state: XdgShellState,
    pub data_device_state: DataDeviceState,
    pub viewporter_state: ViewporterState,
    pub single_pixel_buffer_state: SinglePixelBufferState,
    pub dmabuf_state: DmabufState,
    pub dmabuf_global: DmabufGlobal,
    pub seat: Seat<Self>,
    pub minimized_toplevels: Vec<ToplevelSurface>,
}

impl WLCState {
    fn new(disp: DisplayHandle, egl: &EGLHelper) -> Self {
        let compositor_state = CompositorState::new::<WLCState>(&disp);
        let shm_state = ShmState::new::<WLCState>(&disp, vec![]);

        let mut seat_state = SeatState::<WLCState>::new();
        let mut seat = seat_state.new_wl_seat(&disp, "seat-0");
        seat.add_pointer();
        seat.add_keyboard(XkbConfig::default(), 200, 25)
            .expect("Keyboard create");

        let xdg_state = XdgShellState::new::<WLCState>(&disp);
        let data_device_state = DataDeviceState::new::<WLCState>(&disp);
        let viewporter_state = ViewporterState::new::<WLCState>(&disp);
        let single_pixel_buffer_state =
            SinglePixelBufferState::new::<WLCState>(&disp);

        let mut dmabuf_state = DmabufState::new();
        let dmabuf_global = init_dmabuf(&disp, &mut dmabuf_state, egl);

        Self {
            display_handle: disp.clone(),
            socket: OsString::new(),
            compositor_state,
            shm_state,
            seat_state,
            xdg_state,
            data_device_state,
            viewporter_state,
            single_pixel_buffer_state,
            dmabuf_state,
            dmabuf_global,
            seat,
            minimized_toplevels: vec![],
        }
    }
}

fn init_dmabuf(
    disp: &DisplayHandle,
    state: &mut DmabufState,
    egl: &EGLHelper
) -> DmabufGlobal {
    let render_node_path = egl.get_render_node();
    let render_node = DrmNode::from_path(render_node_path).unwrap().dev_id();

    let formats = egl.query_dmabuf_formats();

    let feedback = DmabufFeedbackBuilder::new(render_node, formats)
        .build()
        .unwrap();

    state.create_global_with_default_feedback::<WLCState>(disp, &feedback)
}

impl CompositorHandler for WLCState {
    fn compositor_state(&mut self) -> &mut CompositorState {
        &mut self.compositor_state
    }

    fn client_compositor_state<'a>(
        &self,
        client: &'a wayland_server::Client,
    ) -> &'a CompositorClientState {
        &client.get_data::<WLCClient>().unwrap().compositor_state
    }

    fn commit(&mut self, _surface: &WlSurface) {
    }
}

impl BufferHandler for WLCState {
    fn buffer_destroyed(&mut self, _buffer: &WlBuffer) {
    }
}

impl ShmHandler for WLCState {
    fn shm_state(&self) -> &ShmState {
        &self.shm_state
    }
}

impl DmabufHandler for WLCState {
    fn dmabuf_state(&mut self) -> &mut DmabufState {
        &mut self.dmabuf_state
    }

    fn dmabuf_imported(
        &mut self,
        _global: &DmabufGlobal,
        dmabuf: Dmabuf,
        notifier: ImportNotifier
    ) {
        println!("DMABUF ATTACH: {:?}", dmabuf);
        let _ = notifier.successful::<WLCState>();
    }
}

impl OutputHandler for WLCState {}

impl SeatHandler for WLCState {
    type KeyboardFocus = WlSurface;
    type PointerFocus = WlSurface;
    type TouchFocus = WlSurface;

    fn seat_state(&mut self) -> &mut SeatState<Self> {
        &mut self.seat_state
    }
}

impl XdgShellHandler for WLCState {
    fn xdg_shell_state(&mut self) -> &mut XdgShellState {
        &mut self.xdg_state
    }

    fn new_toplevel(&mut self, surface: ToplevelSurface) {
        surface.send_configure();
    }

    fn new_popup(
        &mut self,
        surface: PopupSurface,
        positioner: PositionerState
    ) {
        surface.with_pending_state(|state| {
            state.geometry = positioner.get_geometry();
            state.positioner = positioner;
        });
        surface.send_configure().expect("popup initial configure");
    }

    fn grab(&mut self, _surface: PopupSurface, _seat: WlSeat, _serial: Serial) {
    }

    fn reposition_request(
        &mut self,
        surface: PopupSurface,
        positioner: PositionerState,
        token: u32
    ) {
        surface.with_pending_state(|state| {
            state.geometry = positioner.get_geometry();
            state.positioner = positioner;
        });
        surface.send_repositioned(token);
    }

    fn minimize_request(&mut self, surface: ToplevelSurface) {
        self.minimized_toplevels.push(surface);
    }
}

impl DataDeviceHandler for WLCState {
    fn data_device_state(&self) -> &DataDeviceState {
        &self.data_device_state
    }
}

impl SelectionHandler for WLCState {
    type SelectionUserData = ();
}

impl ClientDndGrabHandler for WLCState {}
impl ServerDndGrabHandler for WLCState {}

pub(crate) struct WLCClient {
    compositor_state: CompositorClientState,
}

impl WLCClient {
    fn new() -> Self {
        Self {
            compositor_state: CompositorClientState::default(),
        }
    }
}

impl ClientData for WLCClient {
    fn initialized(&self, _id: ClientId) {
    }

    fn disconnected(&self, _id: ClientId, _reason: DisconnectReason) {
    }
}

pub fn get_time() -> u32 {
    let time: u128 = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis();
    time as u32
}

fn register_virtual_output(state: &mut WLCState) {
    let output = Output::new(
        "output-0".into(),
        PhysicalProperties {
            size: (0, 0).into(),
            subpixel: Subpixel::Unknown,
            make: "Virtual".into(),
            model: "Monitor".into(),
        },
    );
    output.change_current_state(
        Some(output::Mode { size: (1920, 1080).into(), refresh: 60000 }),
        None,
        None,
        Some((0, 0).into())
    );
    output.create_global::<WLCState>(&state.display_handle);
}

pub(crate) fn wlc_init(
    egl: EGLHelper
) -> Result<WaylandCraft<'static>, Box<dyn std::error::Error>> {
    let event_loop: EventLoop<WLCState> = EventLoop::try_new()?;
    let display: Display<WLCState> = Display::new()?;
    let socket = ListeningSocketSource::new_auto()?;

    let mut state = WLCState::new(display.handle(), &egl);
    state.socket = socket.socket_name().to_os_string();

    register_virtual_output(&mut state);

    let ev_handle = event_loop.handle();

    ev_handle.insert_source(socket, |stream, _, state| {
        let client = WLCClient::new();
        state.display_handle.insert_client(stream, Arc::new(client)).unwrap();
    }).unwrap();

    let display_source = GenericEvent::new(
        display, calloop::Interest::READ, calloop::Mode::Level
    );
    ev_handle.insert_source(display_source, |_, display_io, state| {
        unsafe {
            display_io.get_mut().dispatch_clients(state).unwrap();
        }
        Ok(calloop::PostAction::Continue)
    }).unwrap();

    let instance = WaylandCraft {
        state,
        event_loop,
        bridge: BridgeState::new(),
        egl,
    };
    Ok(instance)
}

impl<'a> WaylandCraft<'a> {
    pub fn update(&mut self) {
        let state = &mut self.state;
        let event_loop = &mut self.event_loop;
        event_loop.dispatch(Some(Duration::ZERO), state).unwrap();
        state.display_handle.flush_clients().unwrap();
    }

    pub fn send_frame(&mut self) {
        let toplevels = self.state.xdg_state.toplevel_surfaces();
        for toplevel in toplevels {
            let toplevel_surface = toplevel.wl_surface();

            with_surface_tree_downward(
                toplevel_surface,
                (),
                |_, _, _| TraversalAction::DoChildren(()),
                |_, data, _| {
                    let mut attr_guard = data
                        .cached_state
                        .get::<SurfaceAttributes>();
                    let attr = attr_guard
                        .deref_mut()
                        .current();
                    for c in attr.frame_callbacks.drain(..) {
                        c.done(get_time());
                    }
                },
                |_, _, _| true,
            );
        }
        let popups = self.state.xdg_state.popup_surfaces();
        for popup in popups {
            let popup_surface = popup.wl_surface();

            with_surface_tree_downward(
                popup_surface,
                (),
                |_, _, _| TraversalAction::DoChildren(()),
                |_, data, _| {
                    let mut attr_guard = data
                        .cached_state
                        .get::<SurfaceAttributes>();
                    let attr = attr_guard
                        .deref_mut()
                        .current();
                    for c in attr.frame_callbacks.drain(..) {
                        c.done(get_time());
                    }
                },
                |_, _, _| true,
            );
        }
    }
}

delegate_compositor!(WLCState);
delegate_shm!(WLCState);
delegate_output!(WLCState);
delegate_seat!(WLCState);
delegate_xdg_shell!(WLCState);
delegate_data_device!(WLCState);
delegate_viewporter!(WLCState);
delegate_single_pixel_buffer!(WLCState);
delegate_dmabuf!(WLCState);
