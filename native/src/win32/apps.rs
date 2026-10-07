// The app picker on Windows.
//
// Like Open-Shell, apps are read from the shell's AppsFolder (the list behind Start's
// "All apps"), which covers both desktop apps and Store apps. Each app's parsing name is
// its launch ID: an AppUserModelID for Store apps, a path or ID for desktop apps.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use ::windows::Win32::Foundation::{CloseHandle, HANDLE, SIZE};
use ::windows::Win32::Graphics::Gdi::{
    BI_RGB, BITMAP, BITMAPINFO, BITMAPINFOHEADER, DIB_RGB_COLORS, DeleteObject,
    GetDC, GetDIBits, GetObjectW, HBITMAP, ReleaseDC,
};
use ::windows::Win32::System::Com::{
    CLSCTX_LOCAL_SERVER, COINIT_APARTMENTTHREADED, COINIT_MULTITHREADED,
    CoCreateInstance,
    CoInitializeEx, CoTaskMemFree,
};
use ::windows::Win32::System::Threading::GetProcessId;
use ::windows::Win32::UI::Shell::{
    AO_NONE, ApplicationActivationManager, BHID_EnumItems, FOLDERID_AppsFolder,
    IApplicationActivationManager, IEnumShellItems, IShellItem,
    IShellItemImageFactory, KF_FLAG_DEFAULT, SEE_MASK_FLAG_NO_UI,
    SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, SHGetKnownFolderItem,
    SIGDN, SIGDN_NORMALDISPLAY, SIGDN_PARENTRELATIVEPARSING, SIIGBF_ICONONLY,
    ShellExecuteExW,
};
use ::windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
use ::windows::core::{HSTRING, Interface, PCWSTR, Result};

const ICON_SIZE: i32 = 64;

pub struct AppEntry {
    pub app_id: String,
    pub name: String,
    pub icon_path: Option<String>,
}

/// What a launch returned: the started process, if Windows told us.
pub enum Launched {
    Process(u32),
    Unknown,
}

fn init_com() {
    // Fails harmlessly when this thread already joined another apartment type
    let _ = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
}

pub fn load_apps() -> Vec<AppEntry> {
    // Called on a background thread of its own, which has no message loop, so
    // single-threaded COM could deadlock there
    let _ = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
    match enumerate_apps_folder() {
        Ok(apps) => apps,
        Err(e) => {
            eprintln!("[waylandcraft] Failed to read the AppsFolder: {e}");
            vec![]
        }
    }
}

fn enumerate_apps_folder() -> Result<Vec<AppEntry>> {
    let folder: IShellItem = unsafe {
        SHGetKnownFolderItem(&FOLDERID_AppsFolder, KF_FLAG_DEFAULT, None)?
    };
    let items: IEnumShellItems =
        unsafe { folder.BindToHandler(None, &BHID_EnumItems)? };

    let icon_dir = icon_dir();
    let mut apps = vec![];
    let mut seen = HashMap::new();

    loop {
        let mut item = [None];
        let mut fetched = 0;
        let hr = unsafe { items.Next(&mut item, Some(&mut fetched)) };
        if hr.is_err() || fetched == 0 {
            break;
        }
        let Some(item) = item[0].take() else { break };

        let Some(app_id) = display_name(&item, SIGDN_PARENTRELATIVEPARSING)
        else {
            continue;
        };
        let Some(name) = display_name(&item, SIGDN_NORMALDISPLAY) else {
            continue;
        };
        if seen.insert(app_id.clone(), ()).is_some() {
            continue;
        }

        let icon_path = icon_dir
            .as_ref()
            .and_then(|dir| save_icon(&item, dir, &app_id));

        apps.push(AppEntry { app_id, name, icon_path });
    }

    Ok(apps)
}

fn display_name(item: &IShellItem, sigdn: SIGDN) -> Option<String> {
    unsafe {
        let name = item.GetDisplayName(sigdn).ok()?;
        let string = name.to_string().ok();
        CoTaskMemFree(Some(name.0 as *const _));
        string
    }
}

fn icon_dir() -> Option<PathBuf> {
    let dir = std::env::temp_dir().join("waylandcraft-icons");
    std::fs::create_dir_all(&dir).ok()?;
    Some(dir)
}

fn save_icon(item: &IShellItem, dir: &Path, app_id: &str) -> Option<String> {
    let path = dir.join(format!("{:016x}.png", fnv1a(app_id)));
    if path.exists() {
        return path.to_str().map(String::from);
    }

    let factory: IShellItemImageFactory = item.cast().ok()?;
    let bitmap = unsafe {
        factory
            .GetImage(SIZE { cx: ICON_SIZE, cy: ICON_SIZE }, SIIGBF_ICONONLY)
            .ok()?
    };
    let pixels = bitmap_rgba(bitmap);
    unsafe {
        let _ = DeleteObject(bitmap.into());
    }
    let (rgba, width, height) = pixels?;

    std::fs::write(&path, encode_png(&rgba, width, height)).ok()?;
    path.to_str().map(String::from)
}

// Reads a 32-bit bitmap as straight (not premultiplied) RGBA, top row first
fn bitmap_rgba(bitmap: HBITMAP) -> Option<(Vec<u8>, u32, u32)> {
    let mut info = BITMAP::default();
    let read = unsafe {
        GetObjectW(
            bitmap.into(),
            size_of::<BITMAP>() as i32,
            Some(&mut info as *mut BITMAP as *mut _),
        )
    };
    if read == 0 || info.bmWidth <= 0 || info.bmHeight <= 0 {
        return None;
    }
    let (width, height) = (info.bmWidth, info.bmHeight);

    let mut header = BITMAPINFO {
        bmiHeader: BITMAPINFOHEADER {
            biSize: size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: width,
            biHeight: -height,
            biPlanes: 1,
            biBitCount: 32,
            biCompression: BI_RGB.0,
            ..Default::default()
        },
        ..Default::default()
    };
    let mut bgra = vec![0u8; (width * height * 4) as usize];
    let lines = unsafe {
        let dc = GetDC(None);
        let lines = GetDIBits(
            dc,
            bitmap,
            0,
            height as u32,
            Some(bgra.as_mut_ptr() as *mut _),
            &mut header,
            DIB_RGB_COLORS,
        );
        ReleaseDC(None, dc);
        lines
    };
    if lines == 0 {
        return None;
    }

    let mut rgba = Vec::with_capacity(bgra.len());
    for px in bgra.chunks_exact(4) {
        let (b, g, r, a) = (px[0], px[1], px[2], px[3]);
        // Shell icons come premultiplied
        let unpremultiply = |c: u8| -> u8 {
            if a == 0 { 0 } else { ((c as u32 * 255) / a as u32).min(255) as u8 }
        };
        rgba.extend_from_slice(&[
            unpremultiply(r),
            unpremultiply(g),
            unpremultiply(b),
            a,
        ]);
    }

    Some((rgba, width as u32, height as u32))
}

fn fnv1a(s: &str) -> u64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in s.bytes() {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

// A minimal PNG writer (uncompressed deflate blocks); icons are tiny, so size doesn't matter
fn encode_png(rgba: &[u8], width: u32, height: u32) -> Vec<u8> {
    fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let start = out.len();
        out.extend_from_slice(kind);
        out.extend_from_slice(data);
        let crc = crc32(&out[start..]);
        out.extend_from_slice(&crc.to_be_bytes());
    }

    let mut raw = Vec::with_capacity(rgba.len() + height as usize);
    for row in rgba.chunks_exact(width as usize * 4) {
        raw.push(0); // filter: none
        raw.extend_from_slice(row);
    }

    let mut zlib = vec![0x78, 0x01];
    let mut blocks = raw.chunks(65535).peekable();
    while let Some(block) = blocks.next() {
        zlib.push(if blocks.peek().is_none() { 1 } else { 0 });
        let len = block.len() as u16;
        zlib.extend_from_slice(&len.to_le_bytes());
        zlib.extend_from_slice(&(!len).to_le_bytes());
        zlib.extend_from_slice(block);
    }
    zlib.extend_from_slice(&adler32(&raw).to_be_bytes());

    let mut ihdr = vec![];
    ihdr.extend_from_slice(&width.to_be_bytes());
    ihdr.extend_from_slice(&height.to_be_bytes());
    ihdr.extend_from_slice(&[8, 6, 0, 0, 0]); // 8-bit RGBA

    let mut out = vec![0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];
    chunk(&mut out, b"IHDR", &ihdr);
    chunk(&mut out, b"IDAT", &zlib);
    chunk(&mut out, b"IEND", &[]);
    out
}

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xffffffffu32;
    for &byte in data {
        crc ^= byte as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ 0xedb88320 } else { crc >> 1 };
        }
    }
    !crc
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &byte in data {
        a = (a + byte as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

/// Starts an app by its AppsFolder ID.
pub fn launch(app_id: &str) -> Option<Launched> {
    init_com();

    // Store apps have an AppUserModelID ("Family!App"); activating them returns the process
    if app_id.contains('!')
        && let Ok(pid) = activate_store_app(app_id)
    {
        return Some(Launched::Process(pid));
    }

    let target = HSTRING::from(format!("shell:AppsFolder\\{app_id}"));
    let verb = HSTRING::from("open");
    let mut info = SHELLEXECUTEINFOW {
        cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI,
        lpVerb: PCWSTR(verb.as_ptr()),
        lpFile: PCWSTR(target.as_ptr()),
        nShow: SW_SHOWNORMAL.0,
        ..Default::default()
    };
    if unsafe { ShellExecuteExW(&mut info) }.is_err() {
        return None;
    }

    if info.hProcess.is_invalid() || info.hProcess == HANDLE::default() {
        return Some(Launched::Unknown);
    }
    let pid = unsafe { GetProcessId(info.hProcess) };
    unsafe {
        let _ = CloseHandle(info.hProcess);
    }
    Some(if pid == 0 { Launched::Unknown } else { Launched::Process(pid) })
}

fn activate_store_app(aumid: &str) -> Result<u32> {
    let manager: IApplicationActivationManager = unsafe {
        CoCreateInstance(&ApplicationActivationManager, None, CLSCTX_LOCAL_SERVER)?
    };
    let aumid = HSTRING::from(aumid);
    unsafe { manager.ActivateApplication(&aumid, None, AO_NONE) }
}
