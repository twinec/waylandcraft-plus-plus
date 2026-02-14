use std::sync::{Arc, Mutex};
use std::ops::DerefMut;
use std::os::fd::AsFd;
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

pub struct WLCDataState {
    pub devices: Vec<WlDataDevice>,
    pub clipboard: Option<WlDataSource>,
    pub clipboard_focus: Option<Client>,
    display_handle: DisplayHandle,
}

type WLCDataSource = Arc<Mutex<WLCDataSourceData>>;
struct WLCDataSourceData {
    mime: Vec<String>,
}

type WLCDataOffer = Arc<Mutex<WLCDataOfferData>>;
struct WLCDataOfferData {
    // NOTE: The source is in the id space of the source client!!
    source: WlDataSource,
}

type WLCDataDevice = Arc<Mutex<WLCDataDeviceData>>;
struct WLCDataDeviceData {
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

fn with_offer_data<F, R>(offer: &WlDataOffer, f: F) -> R
    where F: FnOnce(&mut WLCDataOfferData) -> R
{
    let mut guard = offer
        .data::<WLCDataOffer>()
        .unwrap()
        .lock()
        .unwrap();
    let data = guard.deref_mut();
    f(data)
}

#[allow(dead_code)]
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

impl WLCDataState {
    pub fn new(display_handle: &DisplayHandle) -> Self {
        WLCDataState {
            devices: vec![],
            clipboard: None,
            clipboard_focus: None,
            display_handle: display_handle.clone(),
        }
    }

    pub fn create_global(&self) {
        self.display_handle.create_global::<WLCState, WlDDM, ()>(3, ());
    }

    pub fn update_clipboard_client(&mut self, client: Option<Client>) {
        if self.clipboard_focus != client {
            self.clipboard_focus = client;
            self.send_clipboard();
        }
    }

    // Send clipboard data to client with clipboard focus
    fn send_clipboard(&self) {
        let client = match &self.clipboard_focus {
            Some(c) => c,
            None => {return;},
        };
        for device in &self.devices {
            if !device.client().is_some_and(|c| c == *client) {
                continue;
            }

            println!("Sending clipboard!");
            if let Some(clipboard) = &self.clipboard {
                let offer_data = WLCDataOfferData {
                    source: clipboard.clone(),
                };
                let offer_data = Arc::new(Mutex::new(offer_data));
                let offer = client.create_resource::<
                    WlDataOffer, WLCDataOffer, WLCState
                >(&self.display_handle, device.version(), offer_data).unwrap();

                device.data_offer(&offer);
                with_source_data(&clipboard, |data| {
                    for m in data.mime.iter().cloned() {
                        offer.offer(m);
                    }
                });
                device.selection(Some(&offer));
            } else {
                device.selection(None);
            }
        }
    }

    #[allow(dead_code)]
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
                let _source = data_init.init(id, source_data.clone());
            },
            wl_ddm::Request::GetDataDevice { id, .. } => {
                let device_data = WLCDataDeviceData {
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
        if state.data.clipboard.as_ref().is_some_and(|c| c == resource) {
            state.data.clipboard = None;
        }
    }
}

impl Dispatch<WlDataDevice, WLCDataDevice> for WLCState {
    fn request(
        state: &mut Self,
        client: &Client,
        _device: &WlDataDevice,
        request: wl_data_device::Request,
        _data: &WLCDataDevice,
        _disp: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_device::Request::StartDrag { .. } => {},
            wl_data_device::Request::SetSelection { source, serial: _ } => {
                let focus = state.data.clipboard_focus.as_ref();
                if !focus.is_some_and(|c| c == client) {
                    return;
                }

                if let Some(source) = &source {
                    let mime = with_source_data(source, |data| {
                        data.mime.clone()
                    });

                    println!("New clipboard data: {:?}", mime);

                    // STOP SENDING ME EMPTY CLIPBOARD SELECTIONS WITH THE
                    // SAVE_TARGETS MIME. I HAVE NO CLUE WHAT THAT IS.
                    // WHYYYYYYYY. I blame X11.
                    if mime.iter().any(|m| m == "SAVE_TARGETS") {
                        return;
                    }
                }

                if let Some(old_clipboard) = &state.data.clipboard {
                    old_clipboard.cancelled();
                }
                state.data.clipboard = source;
                state.data.send_clipboard();
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

impl Dispatch<WlDataOffer, WLCDataOffer> for WLCState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        offer: &WlDataOffer,
        request: wl_data_offer::Request,
        _data: &WLCDataOffer,
        _disp: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_offer::Request::Receive { mime_type, fd } => {
                with_offer_data(offer, |data| {
                    if !data.source.is_alive() {
                        return;
                    }

                    let mime = with_source_data(&data.source, |source_data| {
                        source_data.mime.clone()
                    });

                    if !mime.iter().any(|m| *m == mime_type) {
                        return;
                    }

                    data.source.send(mime_type, fd.as_fd());
                });
            },
            wl_data_offer::Request::Accept { .. } => {},
            wl_data_offer::Request::Destroy { .. } => {},
            wl_data_offer::Request::Finish { .. } => {},
            wl_data_offer::Request::SetActions { .. } => {},
            _ => unreachable!(),
        }
    }

    fn destroyed(
        _state: &mut Self,
        _client: ClientId,
        _offer: &WlDataOffer,
        _data: &WLCDataOffer,
    ) {
    }
}
