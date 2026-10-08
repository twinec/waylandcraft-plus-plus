// Captures the audio an app plays, for window sharing. The Linux side records the app's
// PipeWire stream; here WASAPI process loopback (Windows 10 2004 and newer) records
// everything the app's process and its children play. The app keeps playing locally.

#![allow(non_snake_case)]

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jlong};
use jni::{Env, bind_java_type};
use ::windows::Win32::Foundation::{CloseHandle, HANDLE, WAIT_OBJECT_0};
use ::windows::Win32::Media::Audio::{
    AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
    AUDCLNT_STREAMFLAGS_EVENTCALLBACK, AUDCLNT_STREAMFLAGS_LOOPBACK,
    AUDIOCLIENT_ACTIVATION_PARAMS, AUDIOCLIENT_ACTIVATION_PARAMS_0,
    AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK, AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS,
    ActivateAudioInterfaceAsync, IActivateAudioInterfaceAsyncOperation,
    IActivateAudioInterfaceCompletionHandler, IActivateAudioInterfaceCompletionHandler_Impl,
    IAudioCaptureClient, IAudioClient, PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE,
    VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, WAVE_FORMAT_PCM, WAVEFORMATEX,
};
use ::windows::Win32::System::Com::StructuredStorage::{
    PROPVARIANT, PROPVARIANT_0, PROPVARIANT_0_0, PROPVARIANT_0_0_0,
};
use ::windows::Win32::System::Com::{BLOB, COINIT_MULTITHREADED, CoInitializeEx};
use ::windows::Win32::System::Threading::{CreateEventW, SetEvent, WaitForSingleObject};
use ::windows::Win32::System::Variant::VT_BLOB;
use ::windows::core::{IUnknown, Interface, Ref, Result, implement};
use thiserror::Error;

bind_java_type! {
    rust_type = WindowsAudioCapture,
    java_type = dev.evvie.waylandcraft.sharing.WindowsAudioCapture,

    native_methods {
        static extern fn start {
            sig = (pid: jint, sample_rate: jint, channels: jint) -> jlong,
            fn = start,
        },
        static extern fn read {
            sig = (handle: jlong, size: jint) -> jbyte[],
            fn = read,
        },
        static extern fn stop {
            sig = (handle: jlong),
            fn = stop,
        },
        static extern fn free {
            sig = (handle: jlong),
            fn = free,
        },
    },
}

#[derive(Debug, Error)]
enum AudioError {
    #[error(transparent)]
    JniError(#[from] jni::errors::Error),
    #[error(transparent)]
    Windows(#[from] ::windows::core::Error),
    #[error("Null audio capture handle")]
    NullHandle,
    #[error("Audio capture activation timed out")]
    Timeout,
}

const BUFFER_DURATION: i64 = 2_000_000; // 200 ms, in 100 ns units
const ACTIVATION_TIMEOUT: Duration = Duration::from_secs(5);

struct Capture {
    _client: IAudioClient,
    capture: IAudioCaptureClient,
    event: HANDLE,
    stopped: AtomicBool,
    block_align: usize,
    /// Captured bytes not handed to Java yet
    pending: Vec<u8>,
}

impl Drop for Capture {
    fn drop(&mut self) {
        unsafe {
            let _ = self._client.Stop();
            let _ = CloseHandle(self.event);
        }
    }
}

// Signals an event when activation finishes; the result is read by the waiting thread
#[implement(IActivateAudioInterfaceCompletionHandler)]
struct Completion {
    done: HANDLE,
}

impl IActivateAudioInterfaceCompletionHandler_Impl for Completion_Impl {
    fn ActivateCompleted(&self, _op: Ref<IActivateAudioInterfaceAsyncOperation>) -> Result<()> {
        unsafe { SetEvent(self.done) }
    }
}

fn start<'local>(
    _env: &mut Env<'local>,
    _class: JClass<'local>,
    pid: jint,
    sample_rate: jint,
    channels: jint,
) -> std::result::Result<jlong, AudioError> {
    let _ = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };

    let mut params = AUDIOCLIENT_ACTIVATION_PARAMS {
        ActivationType: AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK,
        Anonymous: AUDIOCLIENT_ACTIVATION_PARAMS_0 {
            ProcessLoopbackParams: AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS {
                TargetProcessId: pid as u32,
                ProcessLoopbackMode: PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE,
            },
        },
    };
    let variant = PROPVARIANT {
        Anonymous: PROPVARIANT_0 {
            Anonymous: std::mem::ManuallyDrop::new(PROPVARIANT_0_0 {
                vt: VT_BLOB,
                wReserved1: 0,
                wReserved2: 0,
                wReserved3: 0,
                Anonymous: PROPVARIANT_0_0_0 {
                    blob: BLOB {
                        cbSize: size_of::<AUDIOCLIENT_ACTIVATION_PARAMS>() as u32,
                        pBlobData: &mut params as *mut _ as *mut u8,
                    },
                },
            }),
        },
    };

    let done = unsafe { CreateEventW(None, false, false, None)? };
    let handler: IActivateAudioInterfaceCompletionHandler = Completion { done }.into();
    let operation = unsafe {
        ActivateAudioInterfaceAsync(
            VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK,
            &IAudioClient::IID,
            Some(&variant),
            &handler,
        )
    };
    let waited = operation.as_ref().ok().map(|_| unsafe {
        WaitForSingleObject(done, ACTIVATION_TIMEOUT.as_millis() as u32)
    });
    unsafe {
        let _ = CloseHandle(done);
    }
    let operation = operation?;
    if waited != Some(WAIT_OBJECT_0) {
        return Err(AudioError::Timeout);
    }

    let mut result = ::windows::core::HRESULT(0);
    let mut interface: Option<IUnknown> = None;
    unsafe { operation.GetActivateResult(&mut result, &mut interface)? };
    result.ok()?;
    let client: IAudioClient = interface.ok_or(AudioError::NullHandle)?.cast()?;

    let block_align = channels as u16 * 2;
    let format = WAVEFORMATEX {
        wFormatTag: WAVE_FORMAT_PCM as u16,
        nChannels: channels as u16,
        nSamplesPerSec: sample_rate as u32,
        nAvgBytesPerSec: sample_rate as u32 * block_align as u32,
        nBlockAlign: block_align,
        wBitsPerSample: 16,
        cbSize: 0,
    };
    let event = unsafe { CreateEventW(None, false, false, None)? };
    let setup = (|| unsafe {
        client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK
                | AUDCLNT_STREAMFLAGS_EVENTCALLBACK
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
            BUFFER_DURATION,
            0,
            &format,
            None,
        )?;
        client.SetEventHandle(event)?;
        let capture: IAudioCaptureClient = client.GetService()?;
        client.Start()?;
        Ok::<_, ::windows::core::Error>(capture)
    })();
    let capture = match setup {
        Ok(capture) => capture,
        Err(e) => {
            unsafe {
                let _ = CloseHandle(event);
            }
            return Err(e.into());
        }
    };

    let capture = Box::new(Capture {
        _client: client,
        capture,
        event,
        stopped: AtomicBool::new(false),
        block_align: block_align as usize,
        pending: vec![],
    });
    Ok(Box::into_raw(capture) as jlong)
}

fn capture_ref<'a>(handle: jlong) -> std::result::Result<&'a mut Capture, AudioError> {
    unsafe { (handle as *mut Capture).as_mut() }.ok_or(AudioError::NullHandle)
}

/// Blocks until `size` bytes of audio are captured. Returns an empty array once stopped.
fn read<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    handle: jlong,
    size: jint,
) -> std::result::Result<JByteArray<'local>, AudioError> {
    let capture = capture_ref(handle)?;
    let size = size.max(0) as usize;

    while capture.pending.len() < size {
        if capture.stopped.load(Ordering::Relaxed) {
            return Ok(JByteArray::new(env, 0)?);
        }
        if unsafe { WaitForSingleObject(capture.event, 100) } != WAIT_OBJECT_0 {
            continue;
        }
        loop {
            let frames = unsafe { capture.capture.GetNextPacketSize()? };
            if frames == 0 {
                break;
            }
            let mut data = std::ptr::null_mut();
            let mut frames = 0u32;
            let mut flags = 0u32;
            unsafe { capture.capture.GetBuffer(&mut data, &mut frames, &mut flags, None, None)? };
            let bytes = frames as usize * capture.block_align;
            if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 || data.is_null() {
                capture.pending.resize(capture.pending.len() + bytes, 0);
            } else {
                let samples = unsafe { std::slice::from_raw_parts(data, bytes) };
                capture.pending.extend_from_slice(samples);
            }
            unsafe { capture.capture.ReleaseBuffer(frames)? };
        }
    }

    let chunk: Vec<i8> = capture.pending.drain(..size).map(|b| b as i8).collect();
    let array = JByteArray::new(env, size)?;
    array.set_region(env, 0, &chunk)?;
    Ok(array)
}

/// Makes a blocked read return; the reading thread frees the capture afterwards.
fn stop<'local>(_env: &mut Env<'local>, _class: JClass<'local>, handle: jlong) -> std::result::Result<(), AudioError> {
    // Shared reference: a read may be running on another thread
    let capture = unsafe { (handle as *const Capture).as_ref() }.ok_or(AudioError::NullHandle)?;
    capture.stopped.store(true, Ordering::Relaxed);
    Ok(())
}

fn free<'local>(_env: &mut Env<'local>, _class: JClass<'local>, handle: jlong) -> std::result::Result<(), AudioError> {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Capture) });
    }
    Ok(())
}
