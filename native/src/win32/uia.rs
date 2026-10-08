// Clicks for UI drawn with XAML (WinUI), like the tabs and menus of Windows 11's
// Notepad. XAML reads mouse input from the system's pointer input, not from posted
// mouse messages, so a click there is carried out with UI Automation instead: the
// control under the point is invoked, selected, toggled or expanded.

use ::windows::Win32::Foundation::{HWND, POINT, RECT};
use ::windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx,
};
use ::windows::Win32::UI::Accessibility::{
    CUIAutomation, ExpandCollapseState_Collapsed, IUIAutomation, IUIAutomationElement,
    IUIAutomationExpandCollapsePattern, IUIAutomationInvokePattern,
    IUIAutomationSelectionItemPattern, IUIAutomationTogglePattern, TreeScope_Descendants,
    UIA_BoundingRectanglePropertyId, UIA_ExpandCollapsePatternId, UIA_InvokePatternId,
    UIA_SelectionItemPatternId, UIA_TogglePatternId,
};
use ::windows::core::Result;

// Window classes that host XAML content
const XAML_CLASSES: &[&str] = &[
    "Windows.UI.Input.InputSite.WindowClass",
    "Microsoft.UI.Content.DesktopChildSiteBridge",
    "Windows.UI.Composition.DesktopWindowContentBridge",
    "Windows.UI.Core.CoreWindow",
];

pub fn is_xaml_host(class: &str) -> bool {
    XAML_CLASSES.contains(&class)
}

/// Clicks the control at a screen point of a window, on a thread of its own because
/// UI Automation calls into the app and can take a while.
pub fn click(top: HWND, screen: POINT) {
    let top = top.0 as isize;
    std::thread::spawn(move || {
        let _ = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
        if let Err(e) = click_blocking(HWND(top as *mut _), screen) {
            eprintln!("[waylandcraft] UI Automation click failed: {e}");
        }
    });
}

fn click_blocking(top: HWND, screen: POINT) -> Result<()> {
    let automation: IUIAutomation =
        unsafe { CoCreateInstance(&CUIAutomation, None, CLSCTX_INPROC_SERVER)? };
    let root = unsafe { automation.ElementFromHandle(top)? };

    // Fetch every element with its bounds in one call, then take the smallest one
    // containing the point
    let cache = unsafe { automation.CreateCacheRequest()? };
    unsafe { cache.AddProperty(UIA_BoundingRectanglePropertyId)? };
    let condition = unsafe { automation.CreateTrueCondition()? };
    let all = unsafe { root.FindAllBuildCache(TreeScope_Descendants, &condition, &cache)? };

    let mut best: Option<(i64, IUIAutomationElement)> = None;
    for index in 0..unsafe { all.Length()? } {
        let element = unsafe { all.GetElement(index)? };
        let Ok(rect) = (unsafe { element.CachedBoundingRectangle() }) else { continue };
        if !contains(&rect, screen) {
            continue;
        }
        let area = (rect.right - rect.left) as i64 * (rect.bottom - rect.top) as i64;
        if best.as_ref().is_none_or(|(a, _)| area < *a) {
            best = Some((area, element));
        }
    }
    let Some((_, mut element)) = best else { return Ok(()) };

    // The smallest element is often a label inside the button; act on the nearest
    // ancestor that can be clicked
    let walker = unsafe { automation.ControlViewWalker()? };
    for _ in 0..8 {
        if act(&element)? {
            return Ok(());
        }
        match unsafe { walker.GetParentElement(&element) } {
            Ok(parent) if unsafe { automation.CompareElements(&parent, &root)? }.as_bool() => {
                break;
            }
            Ok(parent) => element = parent,
            Err(_) => break,
        }
    }
    let _ = unsafe { element.SetFocus() };
    Ok(())
}

// Returns whether the element supported a click-like action
fn act(element: &IUIAutomationElement) -> Result<bool> {
    unsafe {
        if let Ok(p) = element.GetCurrentPatternAs::<IUIAutomationInvokePattern>(UIA_InvokePatternId) {
            p.Invoke()?;
            return Ok(true);
        }
        if let Ok(p) = element
            .GetCurrentPatternAs::<IUIAutomationSelectionItemPattern>(UIA_SelectionItemPatternId)
        {
            p.Select()?;
            return Ok(true);
        }
        if let Ok(p) = element.GetCurrentPatternAs::<IUIAutomationTogglePattern>(UIA_TogglePatternId) {
            p.Toggle()?;
            return Ok(true);
        }
        if let Ok(p) = element
            .GetCurrentPatternAs::<IUIAutomationExpandCollapsePattern>(UIA_ExpandCollapsePatternId)
        {
            if p.CurrentExpandCollapseState()? == ExpandCollapseState_Collapsed {
                p.Expand()?;
            } else {
                p.Collapse()?;
            }
            return Ok(true);
        }
    }
    Ok(false)
}

fn contains(rect: &RECT, point: POINT) -> bool {
    point.x >= rect.left && point.x < rect.right && point.y >= rect.top && point.y < rect.bottom
}
