"""
Capture frames from local MediaMTX for labeling / training.

Prefer HLS (more reliable on Windows OpenCV). RTSP is fallback.

Requires: MediaMTX running and cam1 online.
  .\\.venv\\Scripts\\python.exe scripts\\capture_frames.py --count 100 --interval 1.0
"""
from __future__ import annotations

import argparse
import os
import time
from pathlib import Path

import cv2
import numpy as np


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Capture stream frames for dataset")
    p.add_argument(
        "--source",
        type=str,
        default="http://127.0.0.1:8888/cam1/index.m3u8",
        help="HLS/RTSP/URL. Default uses local MediaMTX HLS.",
    )
    p.add_argument("--rtsp", type=str, default="", help="Deprecated alias of --source")
    p.add_argument("--out", type=str, default="")
    p.add_argument("--count", type=int, default=50)
    p.add_argument("--interval", type=float, default=1.0, help="seconds between saves")
    p.add_argument("--prefix", type=str, default="cam1")
    p.add_argument("--open-timeout-ms", type=int, default=10000)
    return p.parse_args()


def open_capture(source: str, open_timeout_ms: int) -> cv2.VideoCapture:
    # Help FFmpeg fail fast on bad RTSP instead of hanging forever
    os.environ.setdefault(
        "OPENCV_FFMPEG_CAPTURE_OPTIONS",
        "rtsp_transport;tcp|stimeout;5000000|rw_timeout;5000000|max_delay;500000",
    )
    cap = cv2.VideoCapture(source, cv2.CAP_FFMPEG)
    try:
        cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, open_timeout_ms)
        cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, open_timeout_ms)
    except Exception:
        pass
    return cap


def save_image(path: Path, frame: np.ndarray) -> None:
    """cv2.imwrite fails on non-ASCII Windows paths; encode then write bytes."""
    ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 92])
    if not ok:
        raise RuntimeError(f"JPEG encode failed: {path.name}")
    path.write_bytes(buf.tobytes())


def main() -> None:
    args = parse_args()
    source = args.rtsp or args.source
    root = Path(__file__).resolve().parents[1]
    out = Path(args.out) if args.out else root / "datasets" / "traffic" / "images" / "train"
    out.mkdir(parents=True, exist_ok=True)

    print(f"Opening: {source}")
    cap = open_capture(source, args.open_timeout_ms)
    if not cap.isOpened():
        fallback = (
            "rtsp://127.0.0.1:8554/cam1"
            if source.startswith("http")
            else "http://127.0.0.1:8888/cam1/index.m3u8"
        )
        print(f"Open failed, trying fallback: {fallback}")
        cap.release()
        cap = open_capture(fallback, args.open_timeout_ms)
        source = fallback

    if not cap.isOpened():
        raise SystemExit(
            "Cannot open stream.\n"
            "1) Make sure MediaMTX is running\n"
            "2) Check HLS in browser: http://127.0.0.1:8888/cam1/index.m3u8\n"
            "3) Re-run with venv Python:\n"
            "   .venv\\Scripts\\python.exe scripts\\capture_frames.py --count 100 --interval 1"
        )

    existing = len(list(out.glob("*.jpg"))) + len(list(out.glob("*.png")))
    target = args.count
    # If folder already has images and user asks for N, treat N as total target.
    if existing > 0 and existing < target:
        print(f"Found {existing} existing images, will capture until total={target}")
    saved_now = 0
    index = existing
    fail_streak = 0
    reconnect_attempts = 0
    print(f"Capturing from {source} -> {out}")
    while existing + saved_now < target:
        ok, frame = cap.read()
        if not ok or frame is None:
            fail_streak += 1
            print(f"read failed ({fail_streak}), retry...")
            if fail_streak >= 5:
                reconnect_attempts += 1
                if reconnect_attempts > 30:
                    raise SystemExit(
                        f"Stream keep failing after reconnects. Saved {existing + saved_now}/{target}."
                    )
                print(f"Reconnecting stream... (#{reconnect_attempts})")
                cap.release()
                time.sleep(1.0)
                cap = open_capture(source, args.open_timeout_ms)
                if not cap.isOpened():
                    print("Reconnect failed, waiting 2s...")
                    time.sleep(2.0)
                    continue
                fail_streak = 0
                continue
            time.sleep(0.5)
            continue

        fail_streak = 0
        reconnect_attempts = 0
        name = f"{args.prefix}_{int(time.time())}_{index:04d}.jpg"
        path = out / name
        try:
            save_image(path, frame)
        except Exception as ex:
            print(f"write failed: {path.name} ({ex})")
            time.sleep(0.2)
            continue

        saved_now += 1
        index += 1
        total = existing + saved_now
        print(f"[{total}/{target}] {path.name}  shape={frame.shape}")
        time.sleep(args.interval)

    cap.release()
    print(f"Done. Saved {saved_now} new frames (total {existing + saved_now}) to {out}")
    print("Next: label with LabelImg / Roboflow / CVAT, put .txt into labels/train")


if __name__ == "__main__":
    main()
