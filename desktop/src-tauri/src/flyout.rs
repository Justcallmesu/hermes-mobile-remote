//! Windows tray flyout: where the borderless window goes. `place` is pure; `Taskbar` is the seam over
//! `SHAppBarMessage(ABM_GETTASKBARPOS)` so tests need no Win32.

pub const FLYOUT_WIDTH: f64 = 360.0;
pub const FLYOUT_HEIGHT: f64 = 520.0;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Edge {
    Bottom,
    Top,
    Left,
    Right,
}

/// A rectangle in physical pixels; origins may be negative on multi-monitor setups.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

/// Which screen edge the taskbar sits on.
pub trait Taskbar {
    fn edge(&self) -> Edge;
}

fn clamp(v: i32, lo: i32, hi: i32) -> i32 {
    // When the flyout is bigger than the area, keep its start visible.
    v.min(hi).max(lo)
}

/// The flyout rect: flush against the taskbar side of `work` (the work area excludes the taskbar),
/// centred on the click along the taskbar, kept inside `work`.
pub fn place(edge: Edge, work: Rect, click: (i32, i32), size: (i32, i32)) -> Rect {
    let (w, h) = size;
    let along_x = clamp(click.0 - w / 2, work.x, work.x + work.w - w);
    let along_y = clamp(click.1 - h / 2, work.y, work.y + work.h - h);
    let (x, y) = match edge {
        Edge::Bottom => (along_x, clamp(work.y + work.h - h, work.y, work.y + work.h - h)),
        Edge::Top => (along_x, work.y),
        Edge::Left => (work.x, along_y),
        Edge::Right => (clamp(work.x + work.w - w, work.x, work.x + work.w - w), along_y),
    };
    Rect { x, y, w, h }
}

/// Used when neither the click's monitor nor the primary monitor is known: a common screen minus a taskbar.
pub const DEFAULT_WORK: Rect = Rect { x: 0, y: 0, w: 1920, h: 1040 };

/// The work area to place the flyout in: the click's monitor, else the primary monitor, else a sane default.
/// Never a huge sentinel, so the flyout stays on screen.
pub fn work_area(at_click: Option<Rect>, primary: Option<Rect>) -> Rect {
    at_click.or(primary).unwrap_or(DEFAULT_WORK)
}

/// Where the "Trust this network?" notification sends the user.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TrustTarget {
    Flyout,
    MainWindow,
}

/// On Windows the app lives in the tray, so the notification opens the flyout; elsewhere the normal window.
pub fn trust_target(is_windows: bool) -> TrustTarget {
    if is_windows {
        TrustTarget::Flyout
    } else {
        TrustTarget::MainWindow
    }
}

/// A tray click this soon after a focus-loss hide is the same click that closed the flyout.
pub const TRAY_CLICK_GRACE_MS: u64 = 300;

/// Should a tray click be ignored, given the time since the last focus-loss hide (None: never hidden)?
pub fn ignore_tray_click(since_focus_hide: Option<std::time::Duration>) -> bool {
    matches!(since_focus_hide, Some(d) if d.as_millis() < TRAY_CLICK_GRACE_MS as u128)
}

#[cfg(windows)]
pub struct WinTaskbar;

#[cfg(windows)]
impl Taskbar for WinTaskbar {
    fn edge(&self) -> Edge {
        use windows_sys::Win32::UI::Shell::{
            SHAppBarMessage, ABE_LEFT, ABE_RIGHT, ABE_TOP, ABM_GETTASKBARPOS, APPBARDATA,
        };
        // SAFETY: APPBARDATA is plain data; zeroed is valid and cbSize is set before the call.
        let mut data: APPBARDATA = unsafe { std::mem::zeroed() };
        data.cbSize = std::mem::size_of::<APPBARDATA>() as u32;
        let ok = unsafe { SHAppBarMessage(ABM_GETTASKBARPOS, &mut data) };
        if ok == 0 {
            return Edge::Bottom;
        }
        match data.uEdge {
            ABE_LEFT => Edge::Left,
            ABE_RIGHT => Edge::Right,
            ABE_TOP => Edge::Top,
            _ => Edge::Bottom,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const WORK: Rect = Rect { x: 0, y: 0, w: 1920, h: 1040 };
    const SIZE: (i32, i32) = (360, 520);

    struct Fixed(Edge);
    impl Taskbar for Fixed {
        fn edge(&self) -> Edge {
            self.0
        }
    }

    fn at(edge: Edge, work: Rect, click: (i32, i32)) -> Rect {
        place(Fixed(edge).edge(), work, click, SIZE)
    }

    #[test]
    fn bottom_taskbar_puts_the_flyout_above_the_click_inside_the_work_area() {
        assert_eq!(at(Edge::Bottom, WORK, (1800, 1060)), Rect { x: 1560, y: 520, w: 360, h: 520 });
        assert_eq!(at(Edge::Bottom, WORK, (1000, 1060)), Rect { x: 820, y: 520, w: 360, h: 520 });
        assert_eq!(at(Edge::Bottom, WORK, (10, 1060)).x, 0);
    }

    #[test]
    fn top_taskbar_puts_it_below() {
        let work = Rect { x: 0, y: 40, w: 1920, h: 1040 };
        assert_eq!(at(Edge::Top, work, (1800, 10)), Rect { x: 1560, y: 40, w: 360, h: 520 });
    }

    #[test]
    fn side_taskbars_put_it_beside() {
        let left = Rect { x: 60, y: 0, w: 1860, h: 1080 };
        assert_eq!(at(Edge::Left, left, (20, 1000)), Rect { x: 60, y: 560, w: 360, h: 520 });
        let right = Rect { x: 0, y: 0, w: 1860, h: 1080 };
        assert_eq!(at(Edge::Right, right, (1890, 500)), Rect { x: 1500, y: 240, w: 360, h: 520 });
        assert_eq!(at(Edge::Right, right, (1890, 5)).y, 0);
    }

    #[test]
    fn a_monitor_left_of_the_primary_has_a_negative_origin() {
        let work = Rect { x: -1920, y: 0, w: 1920, h: 1040 };
        assert_eq!(at(Edge::Bottom, work, (-10, 1060)), Rect { x: -360, y: 520, w: 360, h: 520 });
        assert_eq!(at(Edge::Bottom, work, (-1915, 1060)).x, -1920);
    }

    #[test]
    fn windows_trust_notification_routes_to_the_flyout() {
        assert_eq!(trust_target(true), TrustTarget::Flyout);
        assert_eq!(trust_target(false), TrustTarget::MainWindow);
    }

    #[test]
    fn a_missing_monitor_falls_back_to_the_primary_work_area() {
        let click = Rect { x: 1920, y: 0, w: 1280, h: 984 };
        let primary = Rect { x: 0, y: 0, w: 1920, h: 1040 };
        assert_eq!(work_area(Some(click), Some(primary)), click);
        assert_eq!(work_area(None, Some(primary)), primary);
        let w = work_area(None, None);
        assert!(w.w <= 10_000 && w.h <= 10_000);
        let r = place(Edge::Bottom, w, (5000, 5000), SIZE);
        assert!(r.x >= w.x && r.y >= w.y && r.x + r.w <= w.x + w.w && r.y + r.h <= w.y + w.h);
    }

    #[test]
    fn a_tray_click_right_after_a_focus_loss_hide_is_ignored() {
        use std::time::Duration;
        assert!(ignore_tray_click(Some(Duration::from_millis(50))));
        assert!(!ignore_tray_click(Some(Duration::from_millis(TRAY_CLICK_GRACE_MS))));
        assert!(!ignore_tray_click(Some(Duration::from_secs(5))));
        assert!(!ignore_tray_click(None));
    }

    #[test]
    fn a_work_area_smaller_than_the_flyout_keeps_its_origin() {
        let tiny = Rect { x: 100, y: 100, w: 300, h: 400 };
        let r = at(Edge::Bottom, tiny, (200, 500));
        assert_eq!((r.x, r.y), (100, 100));
    }
}
