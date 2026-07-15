"""
Capture frames from all 12 sandbox camera views.

For each channel:
  1) POST backend /api/stream/switch/{id}  (updates mediamtx.yml)
  2) PATCH MediaMTX API to hot-reload cam1 source
  3) wait until path ready
  4) save N frames with longer interval

Usage:
  .\\.venv\\Scripts\\python.exe scripts\\capture_all_views.py
  .\\.venv\\Scripts\\python.exe scripts\\capture_all_views.py --per-view 15 --interval 2.5
"""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

import cv2
import numpy as np


CHANNELS = [
    ("live1", "bridge"),
    ("live2", "parking_out"),
    ("live3", "pedestrian"),
    ("live4", "firetruck"),
    ("live5", "bridge_out"),
    ("live6", "bridge_in"),
    ("live7", "road2"),
    ("live8", "tunnel_accident"),
    ("live9", "tunnel_flow"),
    ("live10", "road3"),
    ("live11", "parking_in"),
    ("live12", "road1"),
]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Capture frames from all sandbox views")
    p.add_argument("--api", type=str, default="http://127.0.0.1:8080")
    p.add_argument("--mtx-api", type=str, default="http://127.0.0.1:9997")
    p.add_argument("--sandbox", type=str, default="10.126.59.120:8554")
    p.add_argument("--hls", type=str, default="http://127.0.0.1:8888/cam1/index.m3u8")
    p.add_argument("--per-view", type=int, default=15)
    p.add_argument("--interval", type=float, default=2.5, help="seconds between frames")
    p.add_argument("--settle", type=float, default=3.0, help="extra wait after path ready")
    p.add_argument("--out", type=str, default="")
    p.add_argument(
        "--channels",
        type=str,
        default="",
        help="optional subset, e.g. live1,live7,live12",
    )
    return p.parse_args()


def http_json(method: str, url: str, body: dict | None = None, timeout: float = 30.0):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"} if body is not None else {},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return raw


def switch_channel(api: str, channel_id: str) -> dict:
    url = f"{api.rstrip('/')}/api/stream/switch/{channel_id}"
    print(f"  backend switch -> {channel_id}")
    return http_json("POST", url, timeout=30.0) or {}


def patch_mediamtx_source(mtx_api: str, sandbox: str, channel_id: str) -> None:
    rtsp = f"rtsp://{sandbox}/live/{channel_id}"
    url = f"{mtx_api.rstrip('/')}/v3/config/paths/patch/cam1"
    print(f"  mediamtx patch -> {rtsp}")
    # MediaMTX may block briefly while reconnecting upstream; allow longer timeout
    try:
        http_json("PATCH", url, {"source": rtsp}, timeout=45.0)
    except Exception as ex:
        print(f"  [WARN] mediamtx patch timeout/err: {ex}; rely on yml + settle")


def wait_path_ready(mtx_api: str, timeout_sec: float = 25.0) -> bool:
    url = f"{mtx_api.rstrip('/')}/v3/paths/get/cam1"
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            info = http_json("GET", url) or {}
            if info.get("ready"):
                print("  path ready")
                return True
            print(f"  waiting path ready... ready={info.get('ready')}")
        except Exception as ex:
            print(f"  wait path err: {ex}")
        time.sleep(1.0)
    print("  [WARN] path not ready in time, continue anyway")
    return False


def open_capture(source: str) -> cv2.VideoCapture:
    os.environ.setdefault(
        "OPENCV_FFMPEG_CAPTURE_OPTIONS",
        "rtsp_transport;tcp|stimeout;5000000|rw_timeout;5000000|max_delay;500000",
    )
    cap = cv2.VideoCapture(source, cv2.CAP_FFMPEG)
    try:
        cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 10000)
        cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 10000)
    except Exception:
        pass
    return cap


def save_image(path: Path, frame: np.ndarray) -> None:
    ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 92])
    if not ok:
        raise RuntimeError(f"JPEG encode failed: {path.name}")
    path.write_bytes(buf.tobytes())


def grab_one(cap: cv2.VideoCapture, retries: int = 8):
    for _ in range(retries):
        ok, frame = cap.read()
        if ok and frame is not None:
            return frame
        time.sleep(0.4)
    return None


def capture_view(
    hls: str,
    out: Path,
    channel_id: str,
    alias: str,
    per_view: int,
    interval: float,
) -> int:
    cap = open_capture(hls)
    if not cap.isOpened():
        print(f"  [FAIL] cannot open {hls}")
        return 0

    # discard stale buffered frames from previous view
    for _ in range(12):
        cap.read()
        time.sleep(0.12)

    saved = 0
    fail = 0
    while saved < per_view:
        frame = grab_one(cap)
        if frame is None:
            fail += 1
            print(f"  read fail #{fail}, reconnect...")
            cap.release()
            time.sleep(1.0)
            cap = open_capture(hls)
            if not cap.isOpened() or fail >= 12:
                print(f"  [FAIL] stop {channel_id} after repeated read errors")
                break
            continue

        name = f"{channel_id}_{alias}_{int(time.time())}_{saved:02d}.jpg"
        path = out / name
        try:
            save_image(path, frame)
        except Exception as ex:
            print(f"  write fail: {ex}")
            time.sleep(0.3)
            continue

        saved += 1
        print(f"  [{saved}/{per_view}] {name}  {frame.shape[1]}x{frame.shape[0]}")
        if saved < per_view:
            time.sleep(interval)

    cap.release()
    return saved


def main() -> None:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    out = Path(args.out) if args.out else root / "datasets" / "traffic" / "images" / "train"
    out.mkdir(parents=True, exist_ok=True)

    selected = {c.strip() for c in args.channels.split(",") if c.strip()}
    channels = [c for c in CHANNELS if not selected or c[0] in selected]

    total_target = len(channels) * args.per_view
    print("=" * 60)
    print(f"Multi-view capture: {len(channels)} views x {args.per_view} = {total_target}")
    print(f"Interval={args.interval}s  settle={args.settle}s")
    print(f"Output: {out}")
    print("=" * 60)

    grand = 0
    for idx, (channel_id, alias) in enumerate(channels, start=1):
        print(f"\n[{idx}/{len(channels)}] {channel_id} ({alias})")
        try:
            switch_channel(args.api, channel_id)
            patch_mediamtx_source(args.mtx_api, args.sandbox, channel_id)
            wait_path_ready(args.mtx_api, timeout_sec=25.0)
            print(f"  settle {args.settle}s ...")
            time.sleep(args.settle)
        except Exception as ex:
            print(f"  [SKIP] switch failed: {ex}")
            continue

        n = capture_view(args.hls, out, channel_id, alias, args.per_view, args.interval)
        grand += n
        print(f"  view done: {n}/{args.per_view}")

    print("\n" + "=" * 60)
    print(f"All done. Saved {grand}/{total_target} frames -> {out}")
    print("Next: label images, put YOLO txt into labels/train/")


if __name__ == "__main__":
    main()
