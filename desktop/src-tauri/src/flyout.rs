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
    fn a_work_area_smaller_than_the_flyout_keeps_its_origin() {
        let tiny = Rect { x: 100, y: 100, w: 300, h: 400 };
        let r = at(Edge::Bottom, tiny, (200, 500));
        assert_eq!((r.x, r.y), (100, 100));
    }
}
