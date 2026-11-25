"""Turn the planner's touch events into Android gesture windows.

Android's AccessibilityService.dispatchGesture() has two hard limits that the
original ADB/scrcpy approach did not have:

  * a gesture holds at most 10 strokes (one stroke = one finger path), and
  * a new gesture cancels the one in flight.

So the whole play-through is cut into consecutive "windows" of <= 10 strokes.
A finger that is still down at a window boundary (hold notes, long drags) is
split into two strokes and chained with continueStroke() on the Kotlin side.
Boundaries are placed in the quietest gap that still fits 10 strokes, so that
the small delay between two gestures does not land on a note.

Everything here is plain Python so it can be unit-tested off-device.
"""

import json
from typing import Dict, List, Tuple

MAX_STROKES = 10          # GestureDescription.getMaxStrokeCount()
MIN_TOUCH_MS = 90         # taps/drags shorter than this are held for this long
HOLD_TAIL_MS = 60         # longer touches (holds, flicks) are released this much later
PREFERRED_GAP_MS = 60     # try to cut windows where nothing happens for this long
PATH_STEP_MS = 5          # path points closer than this are dropped

Point = Tuple[int, int, int]  # (t_ms, x, y)


# ---------------------------------------------------------------- touches ---

def map_events(ans, dev_w: int, dev_h: int, mirror: bool):
    """Scale the planner's 1280x720 coordinates onto the real screen.

    Same mapping as the original main.py: a centred 16:9 area, scaled by
    height / 720, optionally mirrored horizontally.
    """
    height = dev_h
    width = height * 16 // 9
    xoff = (dev_w - width) // 2
    scale = height / 720

    out = []
    for ms in sorted(ans):
        for ev in ans[ms]:
            x, y = ev.pos
            x = xoff + round(x * scale)
            if mirror:
                x = dev_w - x
            y = round(y * scale)
            x = min(max(x, 0), dev_w - 1)
            y = min(max(y, 0), dev_h - 1)
            out.append((int(ms), ev.action.name, int(ev.pointer), x, y))
    return out


def build_touches(events) -> List[List[Point]]:
    """events: (ms, 'DOWN'|'MOVE'|'UP', pointer_id, x, y) in time order.

    Returns one point list per finger-down..finger-up, sorted by start time.
    Timestamps inside a touch are strictly increasing.
    """
    active: Dict[int, List[Point]] = {}
    done: List[List[Point]] = []

    def push(pts: List[Point], t: int, x: int, y: int):
        t = max(t, pts[-1][0] + 1)
        pts.append((t, x, y))

    for ms, action, pid, x, y in events:
        pts = active.get(pid)
        if action == 'DOWN':
            if pts:
                done.append(pts)
            active[pid] = [(ms, x, y)]
        elif action == 'MOVE':
            if pts is None:
                active[pid] = [(ms, x, y)]
            else:
                push(pts, ms, x, y)
        elif action == 'UP':
            if pts is not None:
                push(pts, ms, x, y)
                done.append(pts)
                del active[pid]
    done.extend(active.values())

    for pts in done:
        t, x, y = pts[-1]
        if t - pts[0][0] < MIN_TOUCH_MS:
            pts.append((pts[0][0] + MIN_TOUCH_MS, x, y))   # short touch: hold it longer
        else:
            pts.append((t + HOLD_TAIL_MS, x, y))           # long touch: release a bit later
    done.sort(key=lambda p: (p[0][0], p[0][1], p[0][2]))
    return done


# ---------------------------------------------------------------- windows ---

def _pos_at(pts: List[Point], t: float) -> Tuple[int, int]:
    if t <= pts[0][0]:
        return pts[0][1], pts[0][2]
    if t >= pts[-1][0]:
        return pts[-1][1], pts[-1][2]
    lo, hi = 0, len(pts) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if pts[mid][0] <= t:
            lo = mid
        else:
            hi = mid
    t0, x0, y0 = pts[lo]
    t1, x1, y1 = pts[hi]
    f = (t - t0) / (t1 - t0)
    return round(x0 + (x1 - x0) * f), round(y0 + (y1 - y0) * f)


def _slice(pts: List[Point], t0: int, t1: int) -> List[List[int]]:
    """Points of a touch clipped to [t0, t1], thinned out."""
    x, y = _pos_at(pts, t0)
    out = [[t0, x, y]]
    last_t = t0
    for t, px, py in pts:
        if t <= t0 or t >= t1:
            continue
        if t - last_t >= PATH_STEP_MS:
            out.append([t, px, py])
            last_t = t
    x, y = _pos_at(pts, t1)
    out.append([t1, x, y])   # t1 > t0 and > every kept point
    return out


def build_windows(touches: List[List[Point]]):
    """Cut touches into gesture windows of at most MAX_STROKES strokes."""
    n = len(touches)
    starts = [p[0][0] for p in touches]
    ends = [p[-1][0] for p in touches]

    windows = []
    carry: List[int] = []   # touches already down at the start of this window
    i = 0                   # next touch that has not started yet
    w0 = starts[0] if n else 0

    while i < n or carry:
        if len(carry) >= MAX_STROKES:      # cannot happen with sane charts
            carry = carry[:MAX_STROKES - 1]
        room = MAX_STROKES - len(carry)
        max_j = min(n, i + room)

        boundary = None
        end_j = i
        if i >= n:
            end_j = n                      # only carried touches are left
        elif max_j >= n:
            end_j = n                      # everything remaining fits
        else:
            best = None  # (gap >= preferred, gap if not preferred, j)
            for j in range(i + 1, max_j + 1):
                s_j = starts[j]
                in_play = carry + list(range(i, j))
                latest = w0
                for k in in_play:
                    if starts[k] <= s_j:
                        latest = max(latest, starts[k])
                    if ends[k] <= s_j:
                        latest = max(latest, ends[k])
                gap = s_j - latest
                b = latest + gap // 2
                b = max(b, w0 + 1)
                if b >= s_j:
                    continue
                key = (1, j) if gap >= PREFERRED_GAP_MS else (0, gap, j)
                if best is None or key > best[0]:
                    best = (key, j, b)
            if best is None:               # all candidates are simultaneous
                end_j = max_j
                boundary = starts[max_j] - 1
                boundary = max(boundary, w0 + 1)
            else:
                _, end_j, boundary = best

        in_window = carry + list(range(i, end_j))
        strokes = []
        next_carry = []
        for k in in_window:
            pts = touches[k]
            t0 = max(starts[k], w0)
            if boundary is None or ends[k] <= boundary:
                t1 = ends[k]
                will = False
            else:
                t1 = boundary
                will = True
                next_carry.append(k)
            if t1 <= t0:
                t1 = t0 + 1
            strokes.append({
                'id': k,
                't0': t0,
                't1': t1,
                'cont': starts[k] < w0,
                'will': will,
                'p': _slice(pts, t0, t1),
            })
        windows.append({'w0': w0, 'strokes': strokes})

        i = end_j
        carry = next_carry
        if boundary is None:
            break
        w0 = boundary

    return windows


def make_plan(ans, dev_w: int, dev_h: int, mirror: bool) -> dict:
    events = map_events(ans, dev_w, dev_h, mirror)
    touches = build_touches(events)
    windows = build_windows(touches)
    first = touches[0][0][0] if touches else 0
    last = max((p[-1][0] for p in touches), default=0)
    return {
        'first': first,
        'last': last,
        'screen': [dev_w, dev_h],
        'touches': len(touches),
        'windows': windows,
    }


def plan_to_json(plan: dict) -> str:
    return json.dumps(plan, separators=(',', ':'))
