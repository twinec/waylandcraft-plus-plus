use std::sync::{Arc, Mutex};
use std::ops::DerefMut;
use crate::WLCState;
use smithay::{
    reexports::{
        wayland_server::{
            protocol::{
                wl_data_device_manager::self as wl_ddm,
                wl_data_device_manager::WlDataDeviceManager as WlDDM,
                wl_data_source::{self, WlDataSource},
                wl_data_device::{self, WlDataDevice},
                wl_data_offer::{self, WlDataOffer},
            },
            backend::ClientId,
            DisplayHandle, DataInit, New, GlobalDispatch, Dispatch, Client,
            Resource,
        },
    },
};
use rustix::{
    fd::{AsFd, OwnedFd},
    io::{read, write},
    pipe::{pipe_with, PipeFlags},
};

pub struct WLCDataState {
    pub devices: Vec<WlDataDevice>,
    pub sources: Vec<WlDataSource>,
    pub clipboard: Option<String>,
    display_handle: DisplayHandle,
}

type WLCDataSource = Arc<Mutex<WLCDataSourceData>>;
struct WLCDataSourceData {
    mime: Vec<String>,
}

type WLCDataDevice = Arc<Mutex<WLCDataDeviceData>>;
struct WLCDataDeviceData {
    offer: Option<WlDataOffer>,
}

fn with_source_data<F, R>(source: &WlDataSource, f: F) -> R
    where F: FnOnce(&mut WLCDataSourceData) -> R
{
    let mut guard = source
        .data::<WLCDataSource>()
        .unwrap()
        .lock()
        .unwrap();
    let data = guard.deref_mut();
    f(data)
}

fn with_device_data<F, R>(device: &WlDataDevice, f: F) -> R
    where F: FnOnce(&mut WLCDataDeviceData) -> R
{
    let mut guard = device
        .data::<WLCDataDevice>()
        .unwrap()
        .lock()
        .unwrap();
    let data = guard.deref_mut();
    f(data)
}

const CLIPBOARD_MIME: &'static str = "text/plain;charset=utf-8";

impl WLCDataState {
    pub fn new(display_handle: &DisplayHandle) -> Self {
        WLCDataState {
            devices: vec![],
            sources: vec![],
            clipboard: None,
            display_handle: display_handle.clone(),
        }
    }

    pub fn create_global(&self) {
        self.display_handle.create_global::<WLCState, WlDDM, ()>(3, ());
    }

    // Send clipboard data to client
    // `client` has to be the client currently holding keyboard focus!
    pub fn send_clipboard(&self, client: Client) {
        self.for_all_devices(|device, data| {
            if device.client().is_some_and(|c| c == client) {
                let offer = client.create_resource::<
                    WlDataOffer, (), WLCState
                >(&self.display_handle, device.version(), ()).unwrap();

                println!("Sending selection");
                device.data_offer(&offer);
                offer.offer(CLIPBOARD_MIME.into());
                device.selection(Some(&offer));
                data.offer = Some(offer);
            } else {
                data.offer = None;
            }
        });
    }

    fn for_all_devices<F>(&self, mut f: F)
        where F: FnMut(&WlDataDevice, &mut WLCDataDeviceData)
    {
        for device in &self.devices {
            with_device_data(device, |data| f(device, data));
        }
    }
}

impl GlobalDispatch<WlDDM, ()> for WLCState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlDDM>,
        _data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let _ddm: WlDDM = data_init.init(resource, ());
    }
}

impl Dispatch<WlDDM, ()> for WLCState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _ddm: &WlDDM,
        request: wl_ddm::Request,
        _data: &(),
        _disp: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_ddm::Request::CreateDataSource { id } => {
                let source_data = WLCDataSourceData {
                    mime: vec![],
                };
                let source_data = Arc::new(Mutex::new(source_data));
                let source = data_init.init(id, source_data.clone());

                state.data.sources.push(source);
            },
            wl_ddm::Request::GetDataDevice { id, .. } => {
                let device_data = WLCDataDeviceData {
                    offer: None,
                };
                let device_data = Arc::new(Mutex::new(device_data));
                let device = data_init.init(id, device_data.clone());

                state.data.devices.push(device);
            },
            _ => unreachable!(),
        }
    }
}

impl Dispatch<WlDataSource, WLCDataSource> for WLCState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        resource: &WlDataSource,
        request: wl_data_source::Request,
        _source: &WLCDataSource,
        _disp: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_source::Request::Offer { mime_type } => {
                with_source_data(resource, |data| {
                    data.mime.push(mime_type);
                });
            },
            wl_data_source::Request::Destroy => {},
            wl_data_source::Request::SetActions { .. } => {},
            _ => unreachable!(),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &WlDataSource,
        _data: &WLCDataSource,
    ) {
        state.data.sources.retain(|s| s != resource);
    }
}

fn read_file_descriptor(fd: OwnedFd) -> Vec<u8> {
    let mut data: Vec<u8> = vec![];
    let mut buf: [u8; 2048] = [0; 2048];
    loop {
        let len = read(&fd, &mut buf).expect("pipe read");
        if len == 0 {
            break;
        }

        data.extend(&buf[..len]);
    }
    data
}

impl Dispatch<WlDataDevice, WLCDataDevice> for WLCState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _device: &WlDataDevice,
        request: wl_data_device::Request,
        _data: &WLCDataDevice,
        _disp: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_device::Request::StartDrag { .. } => {},
            wl_data_device::Request::SetSelection { source, serial: _ } => {
                if let Some(source) = source {
                    with_source_data(&source, |data| {
                        if !data.mime.iter().any(|s| s == CLIPBOARD_MIME) {
                            return;
                        }
                        let (read_fd, write_fd) =
                            pipe_with(PipeFlags::CLOEXEC)
                            .expect("pipe open");
                        source.send(CLIPBOARD_MIME.into(), write_fd.as_fd());
                        state.display_handle.flush_clients().unwrap();
                        drop(write_fd);

                        let data = read_file_descriptor(read_fd);
                        if let Ok(data) = String::from_utf8(data) {
                            println!("SELECTION '{}'", data);
                            state.data.clipboard = Some(data);
                        }
                    });
                }
            },
            wl_data_device::Request::Release => {},
            _ => unreachable!(),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        device: &WlDataDevice,
        _data: &WLCDataDevice,
    ) {
        state.data.devices.retain(|d| d != device);
    }
}

impl Dispatch<WlDataOffer, ()> for WLCState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &WlDataOffer,
        request: wl_data_offer::Request,
        _data: &(),
        _disp: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_offer::Request::Receive { mime_type, fd } => {
                println!("CLIPBOARD RECEIVE REQUEST '{}', have '{:#?}'",
                    mime_type, state.data.clipboard);
                if mime_type != CLIPBOARD_MIME {
                    return;
                }
                if let Some(content) = &state.data.clipboard {
                    write(fd, content.as_bytes())
                        .expect("wl_data_offer write");
                }
            },
            wl_data_offer::Request::Accept { .. } => {},
            wl_data_offer::Request::Destroy { .. } => {},
            wl_data_offer::Request::Finish { .. } => {},
            wl_data_offer::Request::SetActions { .. } => {},
            _ => unreachable!(),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &WlDataOffer,
        _data: &(),
    ) {
        state.data.for_all_devices(|_device, data| {
            if data.offer.as_ref().is_some_and(|o| o == resource) {
                data.offer = None;
            }
        });
    }
}
