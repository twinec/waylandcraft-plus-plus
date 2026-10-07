// Window capture on Windows.
//
// Windows.Graphics.Capture (WGC) is preferred: it captures GPU-rendered apps and keeps
// working while the window is covered. Windows only lets an app turn off the yellow
// capture border from Windows 11 (and Windows 10 build 20348) on, so on older systems
// PrintWindow is used instead, which never draws a border.

use std::time::{Duration, Instant};

use ::windows::Foundation::Metadata::ApiInformation;
use ::windows::Graphics::Capture::{
    Direct3D11CaptureFramePool, GraphicsCaptureAccess, GraphicsCaptureAccessKind,
    GraphicsCaptureItem, GraphicsCaptureSession,
};
use ::windows::Graphics::DirectX::Direct3D11::IDirect3DDevice;
use ::windows::Graphics::DirectX::DirectXPixelFormat;
use ::windows::Graphics::SizeInt32;
use ::windows::Win32::Foundation::{HMODULE, HWND, POINT, RECT};
use ::windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_HARDWARE;
use ::windows::Win32::Graphics::Direct3D11::{
    D3D11_BOX, D3D11_CPU_ACCESS_READ, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
    D3D11_MAP_READ, D3D11_MAPPED_SUBRESOURCE, D3D11_SDK_VERSION,
    D3D11_TEXTURE2D_DESC, D3D11_USAGE_STAGING, D3D11CreateDevice, ID3D11Device,
    ID3D11DeviceContext, ID3D11Texture2D,
};
use ::windows::Win32::Graphics::Dwm::{
    DWMWA_EXTENDED_FRAME_BOUNDS, DwmGetWindowAttribute,
};
use ::windows::Win32::Graphics::Dxgi::Common::{
    DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC,
};
use ::windows::Win32::Graphics::Dxgi::IDXGIDevice;
use ::windows::Win32::Graphics::Gdi::{
    BI_RGB, BITMAPINFO, BITMAPINFOHEADER, CreateCompatibleDC, CreateDIBSection,
    DIB_RGB_COLORS, DeleteDC, DeleteObject, GdiFlush, GetDC, HBITMAP, HDC,
    HGDIOBJ, ReleaseDC, SelectObject,
};
use ::windows::Win32::Storage::Xps::{PRINT_WINDOW_FLAGS, PrintWindow};
use ::windows::Win32::System::WinRT::Direct3D11::{
    CreateDirect3D11DeviceFromDXGIDevice, IDirect3DDxgiInterfaceAccess,
};
use ::windows::Win32::System::WinRT::Graphics::Capture::IGraphicsCaptureItemInterop;
use ::windows::Win32::UI::WindowsAndMessaging::GetWindowRect;
use ::windows::core::{Interface, Result, factory};

// Tells PrintWindow to ask DWM for the composed contents, which works for most
// GPU-rendered windows too. Not exported by the windows crate.
const PW_RENDERFULLCONTENT: PRINT_WINDOW_FLAGS = PRINT_WINDOW_FLAGS(2);

// PrintWindow is slow for large windows, so it runs at most this often per window
const PRINT_WINDOW_INTERVAL: Duration = Duration::from_millis(33);

/// A captured frame: tightly packed BGRA rows, top row first.
pub struct Frame {
    pub data: Vec<u8>,
    pub width: i32,
    pub height: i32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum CaptureMethod {
    /// Windows.Graphics.Capture with the yellow border turned off
    Wgc,
    /// PrintWindow with PW_RENDERFULLCONTENT
    PrintWindow,
}

pub enum WindowCapture {
    Wgc(WgcCapture),
    PrintWindow(PrintWindowCapture),
}

impl WindowCapture {
    pub fn new(
        hwnd: HWND, method: CaptureMethod, d3d: Option<&D3D>,
    ) -> WindowCapture {
        if method == CaptureMethod::Wgc
            && let Some(d3d) = d3d
        {
            match WgcCapture::new(hwnd, d3d) {
                Ok(capture) => return WindowCapture::Wgc(capture),
                Err(e) => eprintln!(
                    "[waylandcraft] WGC capture failed for window {:?}, using PrintWindow: {}",
                    hwnd, e
                ),
            }
        }
        WindowCapture::PrintWindow(PrintWindowCapture::new())
    }

    /// Returns a new frame when the window contents changed since the last call.
    pub fn poll(&mut self, hwnd: HWND, d3d: Option<&D3D>) -> Option<Frame> {
        match self {
            WindowCapture::Wgc(c) => match d3d {
                Some(d3d) => c.poll(d3d).unwrap_or_else(|e| {
                    eprintln!("[waylandcraft] WGC frame failed: {e}");
                    None
                }),
                None => None,
            },
            WindowCapture::PrintWindow(c) => c.poll(hwnd),
        }
    }

    /// Screen position of the captured frame's top-left pixel.
    pub fn origin(&self, hwnd: HWND) -> POINT {
        match self {
            // WGC captures the visible frame, without the invisible resize borders
            WindowCapture::Wgc(_) => extended_frame_bounds(hwnd)
                .map(|r| POINT { x: r.left, y: r.top })
                .unwrap_or_else(|| window_rect_origin(hwnd)),
            WindowCapture::PrintWindow(_) => window_rect_origin(hwnd),
        }
    }
}

fn window_rect_origin(hwnd: HWND) -> POINT {
    let mut rect = RECT::default();
    let _ = unsafe { GetWindowRect(hwnd, &mut rect) };
    POINT { x: rect.left, y: rect.top }
}

fn extended_frame_bounds(hwnd: HWND) -> Option<RECT> {
    let mut rect = RECT::default();
    unsafe {
        DwmGetWindowAttribute(
            hwnd,
            DWMWA_EXTENDED_FRAME_BOUNDS,
            &mut rect as *mut RECT as *mut _,
            size_of::<RECT>() as u32,
        )
    }
    .ok()
    .map(|_| rect)
}

/// Picks WGC when this Windows version can capture without the yellow border,
/// and PrintWindow otherwise. WAYLANDCRAFT_CAPTURE=wgc|printwindow overrides it.
pub fn choose_method() -> CaptureMethod {
    match std::env::var("WAYLANDCRAFT_CAPTURE").as_deref() {
        Ok("wgc") => return CaptureMethod::Wgc,
        Ok("printwindow") => return CaptureMethod::PrintWindow,
        _ => {}
    }

    let wgc_supported = GraphicsCaptureSession::IsSupported().unwrap_or(false);
    let border_optional = ApiInformation::IsPropertyPresent(
        &"Windows.Graphics.Capture.GraphicsCaptureSession".into(),
        &"IsBorderRequired".into(),
    )
    .unwrap_or(false);

    if !(wgc_supported && border_optional) {
        return CaptureMethod::PrintWindow;
    }

    // Unpackaged apps are normally allowed borderless capture, but ask like OBS does
    let allowed =
        GraphicsCaptureAccess::RequestAccessAsync(GraphicsCaptureAccessKind::Borderless)
            .and_then(|op| op.join())
            .map(|status| {
                status
                    == ::windows::Security::Authorization::AppCapabilityAccess::AppCapabilityAccessStatus::Allowed
            })
            .unwrap_or(false);

    if allowed {
        CaptureMethod::Wgc
    } else {
        CaptureMethod::PrintWindow
    }
}

pub struct D3D {
    device: ID3D11Device,
    context: ID3D11DeviceContext,
    winrt_device: IDirect3DDevice,
}

impl D3D {
    pub fn new() -> Result<D3D> {
        let mut device = None;
        let mut context = None;
        unsafe {
            D3D11CreateDevice(
                None,
                D3D_DRIVER_TYPE_HARDWARE,
                HMODULE::default(),
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                None,
                D3D11_SDK_VERSION,
                Some(&mut device),
                None,
                Some(&mut context),
            )?;
        }
        let device: ID3D11Device = device.unwrap();
        let context = context.unwrap();

        let dxgi: IDXGIDevice = device.cast()?;
        let winrt_device: IDirect3DDevice =
            unsafe { CreateDirect3D11DeviceFromDXGIDevice(&dxgi)? }.cast()?;

        Ok(D3D { device, context, winrt_device })
    }
}

pub struct WgcCapture {
    _item: GraphicsCaptureItem,
    pool: Direct3D11CaptureFramePool,
    session: GraphicsCaptureSession,
    pool_size: SizeInt32,
    staging: Option<(ID3D11Texture2D, u32, u32)>,
}

impl WgcCapture {
    fn new(hwnd: HWND, d3d: &D3D) -> Result<WgcCapture> {
        let interop =
            factory::<GraphicsCaptureItem, IGraphicsCaptureItemInterop>()?;
        let item: GraphicsCaptureItem = unsafe { interop.CreateForWindow(hwnd)? };
        let size = item.Size()?;

        // Free threaded, so frames can be polled from the render thread without a
        // dispatcher queue
        let pool = Direct3D11CaptureFramePool::CreateFreeThreaded(
            &d3d.winrt_device,
            DirectXPixelFormat::B8G8R8A8UIntNormalized,
            2,
            size,
        )?;
        let session = pool.CreateCaptureSession(&item)?;
        let _ = session.SetIsBorderRequired(false);
        let _ = session.SetIsCursorCaptureEnabled(false);
        session.StartCapture()?;

        Ok(WgcCapture {
            _item: item,
            pool,
            session,
            pool_size: size,
            staging: None,
        })
    }

    fn poll(&mut self, d3d: &D3D) -> Result<Option<Frame>> {
        // Only the newest frame matters; drop any older ones still queued
        let mut latest = None;
        while let Ok(frame) = self.pool.TryGetNextFrame() {
            latest = Some(frame);
        }
        let Some(frame) = latest else { return Ok(None) };

        let size = frame.ContentSize()?;
        if size.Width <= 0 || size.Height <= 0 {
            return Ok(None);
        }

        let surface = frame.Surface()?;
        let access: IDirect3DDxgiInterfaceAccess = surface.cast()?;
        let texture: ID3D11Texture2D = unsafe { access.GetInterface()? };

        // The pool buffers keep their size until recreated; the content may be smaller
        let mut desc = D3D11_TEXTURE2D_DESC::default();
        unsafe { texture.GetDesc(&mut desc) };
        let width = (size.Width as u32).min(desc.Width);
        let height = (size.Height as u32).min(desc.Height);

        let staging = self.staging_texture(d3d, width, height)?;
        let region = D3D11_BOX {
            left: 0,
            top: 0,
            front: 0,
            right: width,
            bottom: height,
            back: 1,
        };
        unsafe {
            d3d.context.CopySubresourceRegion(
                &staging,
                0,
                0,
                0,
                0,
                &texture,
                0,
                Some(&region),
            );
        }

        let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
        unsafe {
            d3d.context.Map(&staging, 0, D3D11_MAP_READ, 0, Some(&mut mapped))?
        };
        let row = width as usize * 4;
        let mut data = vec![0u8; row * height as usize];
        for y in 0..height as usize {
            let src = unsafe {
                std::slice::from_raw_parts(
                    (mapped.pData as *const u8).add(y * mapped.RowPitch as usize),
                    row,
                )
            };
            data[y * row..(y + 1) * row].copy_from_slice(src);
        }
        unsafe { d3d.context.Unmap(&staging, 0) };

        drop(frame);

        // Resize the pool when the window size changed, so the next frames fit
        if size.Width != self.pool_size.Width || size.Height != self.pool_size.Height {
            self.pool.Recreate(
                &d3d.winrt_device,
                DirectXPixelFormat::B8G8R8A8UIntNormalized,
                2,
                size,
            )?;
            self.pool_size = size;
        }

        Ok(Some(Frame { data, width: width as i32, height: height as i32 }))
    }

    fn staging_texture(
        &mut self, d3d: &D3D, width: u32, height: u32,
    ) -> Result<ID3D11Texture2D> {
        if let Some((texture, w, h)) = &self.staging
            && *w == width
            && *h == height
        {
            return Ok(texture.clone());
        }

        let desc = D3D11_TEXTURE2D_DESC {
            Width: width,
            Height: height,
            MipLevels: 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_STAGING,
            BindFlags: 0,
            CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32,
            MiscFlags: 0,
        };
        let mut texture = None;
        unsafe { d3d.device.CreateTexture2D(&desc, None, Some(&mut texture))? };
        let texture = texture.unwrap();
        self.staging = Some((texture.clone(), width, height));
        Ok(texture)
    }
}

impl Drop for WgcCapture {
    fn drop(&mut self) {
        let _ = self.session.Close();
        let _ = self.pool.Close();
    }
}

pub struct PrintWindowCapture {
    last: Option<Instant>,
    target: Option<DibTarget>,
}

struct DibTarget {
    dc: HDC,
    bitmap: HBITMAP,
    old: HGDIOBJ,
    bits: *mut u8,
    width: i32,
    height: i32,
}

impl Drop for DibTarget {
    fn drop(&mut self) {
        unsafe {
            SelectObject(self.dc, self.old);
            let _ = DeleteObject(self.bitmap.into());
            let _ = DeleteDC(self.dc);
        }
    }
}

impl PrintWindowCapture {
    fn new() -> PrintWindowCapture {
        PrintWindowCapture { last: None, target: None }
    }

    fn poll(&mut self, hwnd: HWND) -> Option<Frame> {
        if let Some(last) = self.last
            && last.elapsed() < PRINT_WINDOW_INTERVAL
        {
            return None;
        }
        self.last = Some(Instant::now());

        let mut rect = RECT::default();
        unsafe { GetWindowRect(hwnd, &mut rect) }.ok()?;
        let width = rect.right - rect.left;
        let height = rect.bottom - rect.top;
        if width <= 0 || height <= 0 {
            return None;
        }

        let target = self.target(width, height)?;
        if !unsafe { PrintWindow(hwnd, target.dc, PW_RENDERFULLCONTENT) }
            .as_bool()
        {
            return None;
        }
        unsafe {
            let _ = GdiFlush();
        }

        let len = width as usize * height as usize * 4;
        let data =
            unsafe { std::slice::from_raw_parts(target.bits, len) }.to_vec();
        Some(Frame { data, width, height })
    }

    fn target(&mut self, width: i32, height: i32) -> Option<&DibTarget> {
        let reuse = matches!(&self.target, Some(t) if t.width == width && t.height == height);
        if !reuse {
            self.target = None;
            unsafe {
                let screen = GetDC(None);
                let dc = CreateCompatibleDC(Some(screen));
                ReleaseDC(None, screen);

                let info = BITMAPINFO {
                    bmiHeader: BITMAPINFOHEADER {
                        biSize: size_of::<BITMAPINFOHEADER>() as u32,
                        biWidth: width,
                        // Negative height: top row first
                        biHeight: -height,
                        biPlanes: 1,
                        biBitCount: 32,
                        biCompression: BI_RGB.0,
                        ..Default::default()
                    },
                    ..Default::default()
                };
                let mut bits = std::ptr::null_mut();
                let bitmap = match CreateDIBSection(
                    Some(dc),
                    &info,
                    DIB_RGB_COLORS,
                    &mut bits,
                    None,
                    0,
                ) {
                    Ok(b) => b,
                    Err(_) => {
                        let _ = DeleteDC(dc);
                        return None;
                    }
                };
                let old = SelectObject(dc, bitmap.into());
                self.target = Some(DibTarget {
                    dc,
                    bitmap,
                    old,
                    bits: bits as *mut u8,
                    width,
                    height,
                });
            }
        }
        self.target.as_ref()
    }
}
