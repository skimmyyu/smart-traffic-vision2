"""
Live YOLOv8 + optional plate OCR bridge.

Modes follow frontend /api/models/active:
  yolov8n   -> vehicle detect only
  plate_det -> vehicle + plate boxes (OCR text may be shown but not saved)
  plate_ocr -> vehicle + plate boxes + OCR + save plate records
  anomaly   -> Java ONNX Runtime (anomaly.onnx); this bridge skips push
  parking   -> vehicle detect only; Java ByteTrack + no-parking dwell alert
  congestion -> vehicle detect only; Java ROI → road-segment counts for heatmap

Usage:
  .\\.venv\\Scripts\\python.exe scripts\\live_bridge.py
"""
from __future__ import annotations

import argparse
import atexit
import json
import os
import sys
import threading
import time
import urllib.request
from collections import Counter
from pathlib import Path
from queue import Empty, Full, Queue

import cv2
import torch
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from plate_ocr import create_ocr, recognize_plates_from_vehicles  # noqa: E402

OLD_WEIGHTS = ROOT / "runs" / "detect" / "sandbox-car-v3" / "weights" / "best.pt"
NEW_WEIGHTS = ROOT / "runs" / "detect" / "sandbox-car-v4" / "weights" / "best.pt"
COCO_WEIGHTS = ROOT / "weights" / "yolov8n.pt"
LOCK_PATH = ROOT / "runs" / "live_bridge.lock"


def _pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        # Windows: OpenProcess succeeds only if process exists
        import ctypes

        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        handle = ctypes.windll.kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid)
        if handle:
            ctypes.windll.kernel32.CloseHandle(handle)
            return True
        return False
    except Exception:
        return False


def acquire_single_instance_lock() -> None:
    """Ensure only one live_bridge consumes the RTSP stream (multi-client causes lag/misses)."""
    LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    if LOCK_PATH.exists():
        try:
            old = int(LOCK_PATH.read_text(encoding="utf-8").strip().splitlines()[0])
        except Exception:
            old = 0
        if old and old != os.getpid() and _pid_alive(old):
            print(f"Another live_bridge is already running (pid={old}). Exit.")
            raise SystemExit(99)
    LOCK_PATH.write_text(str(os.getpid()), encoding="utf-8")

    def _release() -> None:
        try:
            if LOCK_PATH.exists() and LOCK_PATH.read_text(encoding="utf-8").strip() == str(os.getpid()):
                LOCK_PATH.unlink(missing_ok=True)
        except Exception:
            pass

    atexit.register(_release)

KEEP = {"car", "bus", "truck", "motorcycle", "bicycle"}
# Map rare classes to car for sandbox display stability
CLASS_ALIAS = {"bus": "car", "truck": "car"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Live YOLO + mode-aware plate OCR bridge")
    p.add_argument("--weights", type=str, default="", help="single weights path; empty = prefer v4")
    p.add_argument(
        "--ensemble",
        action="store_true",
        default=False,
        help="run v3+v4 and merge boxes (off by default; ensemble increases false positives)",
    )
    p.add_argument("--no-ensemble", action="store_true", help="force single model (default behavior)")
    p.add_argument("--iou-nms", type=float, default=0.45, help="IoU threshold when merging ensemble boxes")
    # Prefer local RTSP (low latency). HLS lags several seconds and hurts moving-car detection.
    p.add_argument("--source", type=str, default="rtsp://127.0.0.1:8554/cam1")
    p.add_argument("--api", type=str, default="http://127.0.0.1:8080/api/inference/push")
    p.add_argument(
        "--conf",
        type=float,
        default=0.35,
        help="min confidence; raise to cut lights/landmarks FPs (was 0.15)",
    )
    p.add_argument("--plate-conf", type=float, default=0.45, help="min OCR score")
    p.add_argument("--imgsz", type=int, default=640, help="YOLO letterbox size (lower = faster)")
    p.add_argument(
        "--infer-width",
        type=int,
        default=960,
        help="max frame width before YOLO/OCR; 0=native RTSP resolution",
    )
    p.add_argument("--interval", type=float, default=0.08, help="target seconds between inferences (adaptive)")
    p.add_argument(
        "--device",
        type=str,
        default="auto",
        help="inference device: auto, cpu, 0, 0,1... (auto falls back to CPU)",
    )
    p.add_argument(
        "--lead-ms",
        type=float,
        default=0.0,
        help="optional forward extrapolation ms (0=off; non-zero may run ahead of video)",
    )
    p.add_argument("--save-congestion", action="store_true", default=False)
    p.add_argument("--plate-every", type=int, default=3, help="run plate OCR every N frames")
    p.add_argument("--flush-reads", type=int, default=8, help="discard buffered frames before infer")
    p.add_argument(
        "--min-area-ratio",
        type=float,
        default=0.0012,
        help="drop boxes smaller than this fraction of frame (filters tiny landmarks)",
    )
    p.add_argument(
        "--max-aspect",
        type=float,
        default=2.8,
        help="drop boxes taller/thinner than this h/w (filters traffic lights)",
    )
    p.add_argument(
        "--min-box-px",
        type=int,
        default=18,
        help="drop boxes whose shorter side is below this many pixels",
    )
    p.add_argument(
        "--camera-api",
        type=str,
        default="http://127.0.0.1:8080/api/camera/sources",
        help="poll active video source (sandbox vs ip-camera)",
    )
    p.add_argument(
        "--camera-poll",
        type=float,
        default=0.5,
        help="seconds between camera-source polls",
    )
    p.add_argument(
        "--channel-api",
        type=str,
        default="http://127.0.0.1:8080/api/stream/current",
        help="poll current channel; clear plate cache on switch",
    )
    p.add_argument(
        "--channel-poll",
        type=float,
        default=0.4,
        help="seconds between channel-switch polls",
    )
    p.add_argument(
        "--model-api",
        type=str,
        default="http://127.0.0.1:8080/api/models/active",
        help="poll active AI mode from backend",
    )
    p.add_argument(
        "--mode",
        type=str,
        default="",
        help="force mode (yolov8n|plate_det|plate_ocr|anomaly|parking|congestion); empty = follow backend",
    )
    return p.parse_args()


def resolve_weight_paths(args: argparse.Namespace) -> list[Path]:
    if args.weights:
        p = Path(args.weights)
        if not p.exists():
            raise SystemExit(f"Weights not found: {p}")
        return [p]

    use_ensemble = bool(args.ensemble) and not bool(args.no_ensemble)
    if use_ensemble and OLD_WEIGHTS.exists() and NEW_WEIGHTS.exists():
        return [OLD_WEIGHTS, NEW_WEIGHTS]

    # Prefer v4; fall back to v3 / COCO
    for candidate in (NEW_WEIGHTS, OLD_WEIGHTS, COCO_WEIGHTS):
        if candidate.exists():
            return [candidate]
    raise SystemExit("No YOLO weights found (sandbox-car-v4 / sandbox-car-v3 / yolov8n.pt)")


def box_iou(a: list[int], b: list[int]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0, ix2 - ix1), max(0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    area_a = max(0, ax2 - ax1) * max(0, ay2 - ay1)
    area_b = max(0, bx2 - bx1) * max(0, by2 - by1)
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def nms_merge(dets: list[dict], iou_thr: float = 0.5) -> list[dict]:
    """Keep highest-confidence box when overlaps are high."""
    if not dets:
        return []
    ordered = sorted(dets, key=lambda d: float(d.get("confidence") or 0.0), reverse=True)
    kept: list[dict] = []
    for det in ordered:
        bbox = det.get("bbox")
        if not isinstance(bbox, list) or len(bbox) < 4:
            continue
        if any(box_iou(bbox, k["bbox"]) >= iou_thr for k in kept):
            continue
        kept.append(det)
    return kept


def is_plausible_vehicle_box(
    bbox: list[int],
    frame_w: int,
    frame_h: int,
    *,
    min_area_ratio: float,
    max_aspect: float,
    min_box_px: int,
) -> bool:
    """Reject traffic-light / landmark shaped false positives by geometry."""
    x1, y1, x2, y2 = bbox
    bw = max(0, x2 - x1)
    bh = max(0, y2 - y1)
    if bw < min_box_px or bh < min_box_px:
        return False
    frame_area = max(1, frame_w * frame_h)
    area_ratio = (bw * bh) / frame_area
    if area_ratio < min_area_ratio:
        return False
    # Tall thin poles / lights (h >> w)
    if bh / max(1, bw) > max_aspect:
        return False
    # Extremely wide thin strips (road lines / barriers mistaken as cars)
    if bw / max(1, bh) > max(max_aspect * 1.6, 4.5):
        return False
    return True


def extract_detections(
    result,
    conf_min: float,
    frame_wh: tuple[int, int] | None = None,
    *,
    min_area_ratio: float = 0.0012,
    max_aspect: float = 2.8,
    min_box_px: int = 18,
) -> list[dict]:
    out: list[dict] = []
    if result is None or result.boxes is None:
        return out
    names = result.names or {}
    fw = fh = 0
    if frame_wh is not None:
        fw, fh = int(frame_wh[0]), int(frame_wh[1])
    for box in result.boxes:
        cls_id = int(box.cls.item())
        name = str(names.get(cls_id, cls_id))
        # single-class old model may use index 0 only
        if name.isdigit():
            name = "car"
        name = CLASS_ALIAS.get(name, name)
        if name not in KEEP:
            continue
        conf = float(box.conf.item())
        if conf < conf_min:
            continue
        x1, y1, x2, y2 = [int(v) for v in box.xyxy[0].tolist()]
        bbox = [x1, y1, x2, y2]
        if fw > 0 and fh > 0 and not is_plausible_vehicle_box(
            bbox,
            fw,
            fh,
            min_area_ratio=min_area_ratio,
            max_aspect=max_aspect,
            min_box_px=min_box_px,
        ):
            continue
        out.append(
            {
                "className": name,
                "confidence": round(conf, 3),
                "bbox": bbox,
            }
        )
    return out


def prepare_infer_frame(frame, max_width: int):
    """Downscale wide frames before YOLO to cut decode/infer latency."""
    if max_width <= 0:
        return frame, 1.0
    h, w = frame.shape[:2]
    if w <= max_width:
        return frame, 1.0
    nh = max(1, int(round(h * max_width / w)))
    resized = cv2.resize(frame, (max_width, nh), interpolation=cv2.INTER_AREA)
    return resized, max_width / float(w)


def scale_detections(dets: list[dict], factor: float) -> list[dict]:
    if abs(factor - 1.0) < 1e-6:
        return dets
    scaled: list[dict] = []
    for d in dets:
        bb = d.get("bbox")
        if not isinstance(bb, list) or len(bb) < 4:
            scaled.append(d)
            continue
        item = dict(d)
        item["bbox"] = [
            int(round(bb[0] * factor)),
            int(round(bb[1] * factor)),
            int(round(bb[2] * factor)),
            int(round(bb[3] * factor)),
        ]
        scaled.append(item)
    return scaled


def predict_ensemble(models: list, frame, args: argparse.Namespace) -> list[dict]:
    h, w = frame.shape[:2]
    merged: list[dict] = []
    for model in models:
        results = model.predict(
            source=frame,
            conf=args.conf,
            imgsz=args.imgsz,
            device=args.device,
            verbose=False,
            workers=0,
        )
        merged.extend(
            extract_detections(
                results[0],
                args.conf,
                (w, h),
                min_area_ratio=float(args.min_area_ratio),
                max_aspect=float(args.max_aspect),
                min_box_px=int(args.min_box_px),
            )
        )
    return nms_merge(merged, iou_thr=float(args.iou_nms))


class _TrackState:
    __slots__ = ("bbox", "vx", "vy", "cls", "conf", "misses")

    def __init__(self, bbox: list[int], cls: str, conf: float):
        self.bbox = list(bbox)
        self.vx = 0.0
        self.vy = 0.0
        self.cls = cls
        self.conf = conf
        self.misses = 0


class BoxSmoother:
    """IoU track + EMA smooth only (no forward extrapolation by default)."""

    def __init__(self, iou_match: float = 0.25, lead_ms: float = 0.0, ema: float = 0.85):
        self.iou_match = iou_match
        self.lead_ms = max(0.0, lead_ms)
        self.ema = ema
        self.tracks: list[_TrackState] = []

    @staticmethod
    def _center(b: list[int]) -> tuple[float, float]:
        return (0.5 * (b[0] + b[2]), 0.5 * (b[1] + b[3]))

    def update(self, detections: list[dict], dt_sec: float) -> list[dict]:
        if not detections:
            for t in self.tracks:
                t.misses += 1
            self.tracks = [t for t in self.tracks if t.misses <= 2]
            return self._emit()

        used = [False] * len(detections)
        for t in self.tracks:
            best_i = -1
            best_iou = 0.0
            for i, det in enumerate(detections):
                if used[i]:
                    continue
                bb = det.get("bbox")
                if not isinstance(bb, list) or len(bb) < 4:
                    continue
                iou = box_iou(t.bbox, bb)
                if iou > best_iou:
                    best_iou = iou
                    best_i = i
            if best_i >= 0 and best_iou >= self.iou_match:
                used[best_i] = True
                det = detections[best_i]
                bb = [int(v) for v in det["bbox"]]
                if dt_sec > 1e-4:
                    cx0, cy0 = self._center(t.bbox)
                    cx1, cy1 = self._center(bb)
                    vx = (cx1 - cx0) / dt_sec
                    vy = (cy1 - cy0) / dt_sec
                    t.vx = self.ema * vx + (1.0 - self.ema) * t.vx
                    t.vy = self.ema * vy + (1.0 - self.ema) * t.vy
                a = self.ema
                t.bbox = [
                    int(a * bb[0] + (1.0 - a) * t.bbox[0]),
                    int(a * bb[1] + (1.0 - a) * t.bbox[1]),
                    int(a * bb[2] + (1.0 - a) * t.bbox[2]),
                    int(a * bb[3] + (1.0 - a) * t.bbox[3]),
                ]
                t.cls = str(det.get("className") or t.cls)
                t.conf = float(det.get("confidence") or t.conf)
                t.misses = 0
            else:
                t.misses += 1

        for i, det in enumerate(detections):
            if used[i]:
                continue
            bb = det.get("bbox")
            if not isinstance(bb, list) or len(bb) < 4:
                continue
            t = _TrackState([int(v) for v in bb], str(det.get("className") or "car"), float(det.get("confidence") or 0.0))
            self.tracks.append(t)

        self.tracks = [t for t in self.tracks if t.misses <= 2]
        return self._emit()

    def _emit(self) -> list[dict]:
        lead = self.lead_ms / 1000.0
        out: list[dict] = []
        for t in self.tracks:
            if t.misses > 0 and lead > 0:
                continue
            x1, y1, x2, y2 = t.bbox
            if lead > 0 and (abs(t.vx) > 0.5 or abs(t.vy) > 0.5):
                dx = int(round(t.vx * lead))
                dy = int(round(t.vy * lead))
                x1 += dx
                y1 += dy
                x2 += dx
                y2 += dy
            out.append(
                {
                    "className": t.cls,
                    "confidence": round(float(t.conf), 3),
                    "bbox": [x1, y1, x2, y2],
                }
            )
        return out


def _attach_plate_geometry(pinfo: dict) -> None:
    """Store plate position relative to its vehicle box for per-frame resync."""
    vb = pinfo.get("vehicleBbox")
    pb = pinfo.get("bbox")
    if not isinstance(vb, list) or len(vb) < 4 or not isinstance(pb, list) or len(pb) < 4:
        return
    vx1, vy1, vx2, vy2 = [float(v) for v in vb[:4]]
    px1, py1, px2, py2 = [float(v) for v in pb[:4]]
    vw = max(1.0, vx2 - vx1)
    vh = max(1.0, vy2 - vy1)
    pcx = 0.5 * (px1 + px2)
    pcy = 0.5 * (py1 + py2)
    pw = max(1.0, px2 - px1)
    ph = max(1.0, py2 - py1)
    pinfo["_rel"] = [
        (pcx - vx1) / vw,
        (pcy - vy1) / vh,
        pw / vw,
        ph / vh,
    ]


def _plate_bbox_on_vehicle(pinfo: dict, vehicle_bbox: list[int]) -> list[int]:
    rel = pinfo.get("_rel")
    pb = pinfo.get("bbox")
    if not isinstance(rel, list) or len(rel) < 4:
        return [int(v) for v in pb[:4]] if isinstance(pb, list) else [0, 0, 0, 0]
    vx1, vy1, vx2, vy2 = [float(v) for v in vehicle_bbox[:4]]
    vw = max(1.0, vx2 - vx1)
    vh = max(1.0, vy2 - vy1)
    pcx = vx1 + float(rel[0]) * vw
    pcy = vy1 + float(rel[1]) * vh
    pw = float(rel[2]) * vw
    ph = float(rel[3]) * vh
    return [
        int(round(pcx - pw * 0.5)),
        int(round(pcy - ph * 0.5)),
        int(round(pcx + pw * 0.5)),
        int(round(pcy + ph * 0.5)),
    ]


def sync_plates_with_vehicles(plates: list[dict], vehicles: list[dict]) -> list[dict]:
    """Move cached plate boxes with their parent vehicle each frame."""
    if not plates or not vehicles:
        return plates
    synced: list[dict] = []
    used_v: set[int] = set()
    for pinfo in plates:
        old_vb = pinfo.get("vehicleBbox")
        if not isinstance(old_vb, list) or len(old_vb) < 4:
            synced.append(dict(pinfo))
            continue
        best_i = -1
        best_score = 0.0
        for i, v in enumerate(vehicles):
            if i in used_v:
                continue
            bb = v.get("bbox")
            if not isinstance(bb, list) or len(bb) < 4:
                continue
            iou = box_iou(old_vb, bb)
            score = iou
            if score > best_score:
                best_score = score
                best_i = i
        if best_i < 0 or best_score < 0.08:
            synced.append(dict(pinfo))
            continue
        used_v.add(best_i)
        vb = [int(v) for v in vehicles[best_i]["bbox"][:4]]
        out = dict(pinfo)
        out["vehicleBbox"] = vb
        out["bbox"] = _plate_bbox_on_vehicle(pinfo, vb)
        synced.append(out)
    return synced


def build_plate_overlays(plates: list[dict], active_mode: str) -> list[dict]:
    """Plate boxes for display only — not merged into vehicle detections."""
    overlays: list[dict] = []
    for pinfo in plates:
        item = {
            "className": "plate",
            "confidence": pinfo.get("confidence"),
            "bbox": pinfo.get("bbox"),
            "vehicleBbox": pinfo.get("vehicleBbox"),
        }
        if active_mode == "plate_ocr":
            item["plateNumber"] = pinfo.get("plateNumber")
        elif active_mode == "plate_det":
            item["plateNumber"] = "车牌"
        overlays.append(item)
    return overlays


class PlateOcrWorker:
    """Run plate OCR off the main inference path; push plate_result when done."""

    def __init__(self) -> None:
        self._queue: Queue = Queue(maxsize=1)
        self._lock = threading.Lock()
        self._last_plates: list[dict] = []
        self._running = True
        self._ctx: dict = {}
        self._thread = threading.Thread(target=self._run, daemon=True, name="plate-ocr")

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        try:
            self._queue.put_nowait(None)
        except Full:
            pass

    def set_context(self, ctx: dict) -> None:
        self._ctx = ctx

    def snapshot(self) -> list[dict]:
        with self._lock:
            return [dict(p) for p in self._last_plates]

    def clear(self) -> None:
        with self._lock:
            self._last_plates = []

    def submit(self, frame, vehicles: list[dict]) -> None:
        if frame is None or not vehicles:
            return
        try:
            while True:
                try:
                    self._queue.get_nowait()
                except Empty:
                    break
            self._queue.put_nowait((frame, vehicles))
        except Full:
            pass

    def _run(self) -> None:
        while self._running:
            item = self._queue.get()
            if item is None:
                break
            frame, vehicles = item
            ctx = dict(self._ctx)
            ocr = ctx.get("ocr")
            if ocr is None:
                continue
            try:
                t0 = time.time()
                plates = recognize_plates_from_vehicles(
                    ocr,
                    frame,
                    vehicles,
                    min_score=float(ctx.get("plate_conf", 0.5)),
                    max_vehicles=5,
                )
                for pinfo in plates:
                    _attach_plate_geometry(pinfo)
                with self._lock:
                    self._last_plates = plates
                dt = (time.time() - t0) * 1000
                if plates:
                    nums = "、".join(str(p.get("plateNumber") or "?") for p in plates[:3])
                    print(f"[plate-ocr] {len(plates)} plate(s) in {dt:.0f}ms: {nums}")
                if ctx.get("save_plates") and plates:
                    payload = {
                        "pushKind": "plate_result",
                        "source": f"{ctx.get('source_tag', 'yolo')}:{ctx.get('active_mode', 'plate_ocr')}",
                        "cameraId": ctx.get("camera_id") or "cam1",
                        "cameraName": ctx.get("camera_name") or "沙盘当前摄像头",
                        "capturedAt": int(time.time() * 1000),
                        "vehicleCount": 0,
                        "summary": "车牌入库",
                        "detections": [],
                        "plateOverlays": [],
                        "plates": plates,
                        "savePlates": True,
                    }
                    post_json(str(ctx.get("api")), payload)
            except Exception as ex:
                print(f"[plate-ocr] error: {ex}")


def open_capture(source: str) -> cv2.VideoCapture:
    # Low-latency FFmpeg options (critical for moving objects)
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (
        "rtsp_transport;tcp|"
        "stimeout;3000000|"
        "rw_timeout;3000000|"
        "max_delay;0|"
        "fflags;nobuffer+discardcorrupt|"
        "flags;low_delay|"
        "probesize;32768|"
        "analyzeduration;0"
    )
    url = source
    if "index.m3u8" in source:
        sep = "&" if "?" in source else "?"
        url = f"{source}{sep}_t={int(time.time() * 1000)}"
    cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    try:
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 8000)
        cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000)
    except Exception:
        pass
    return cap


def read_latest_frame(cap: cv2.VideoCapture, flush_reads: int = 4):
    """Drop queued frames and return the newest one (reduces lag on moving cars)."""
    ok, frame = False, None
    # grab() is cheaper than read(); keep decoding only the last one
    for _ in range(max(1, flush_reads)):
        grabbed = cap.grab()
        if not grabbed:
            break
    ok, frame = cap.retrieve()
    if not ok or frame is None:
        ok, frame = cap.read()
    return ok, frame


def reconnect_capture(cap: cv2.VideoCapture | None, source: str, reason: str = "") -> cv2.VideoCapture:
    if cap is not None:
        try:
            cap.release()
        except Exception:
            pass
    if reason:
        print(f"reconnect capture: {reason}")
    time.sleep(0.15)
    return open_capture(source)


def post_json(url: str, payload: dict, timeout: float = 5.0) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def get_json(url: str, timeout: float = 2.0) -> dict:
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def summarize(names: list[str]) -> str:
    if not names:
        return "无目标"
    c = Counter(names)
    return " · ".join(f"{k}×{v}" for k, v in c.most_common())


def mode_flags(mode: str) -> tuple[bool, bool]:
    """Return (run_plate_pipeline, save_plates)."""
    m = (mode or "yolov8n").strip().lower()
    if m in {"plate_det", "plate_ocr"}:
        return True, m == "plate_ocr"
    # yolov8n / anomaly / unknown -> no plate OCR
    return False, False


def fetch_active_mode(model_api: str, fallback: str = "yolov8n") -> str:
    try:
        cur = get_json(model_api)
        data = cur.get("data") if isinstance(cur, dict) else None
        if isinstance(data, dict):
            mid = str(data.get("id") or data.get("modelId") or "").strip()
            if mid:
                return mid
        if isinstance(data, str) and data.strip():
            return data.strip()
    except Exception:
        pass
    return fallback


def resolve_device(requested: str) -> str:
    """Choose a usable inference device without crashing on non-CUDA PCs."""
    requested = (requested or "auto").strip().lower()
    if requested in {"", "auto"}:
        return "0" if torch.cuda.is_available() else "cpu"
    if requested != "cpu" and not torch.cuda.is_available():
        print(f"[WARN] CUDA device '{requested}' is unavailable; falling back to CPU.")
        return "cpu"
    return requested


def fetch_active_camera_source(camera_api: str, fallback: str = "sandbox") -> str:
    try:
        cur = get_json(camera_api)
        data = cur.get("data") if isinstance(cur, dict) else None
        if isinstance(data, dict):
            src = str(data.get("activeSource") or "").strip()
            if src:
                return src
    except Exception:
        pass
    return fallback


def mediamtx_rtsp_for_source(source_id: str) -> str:
    if (source_id or "").strip().lower() == "ip-camera":
        return "rtsp://127.0.0.1:8554/cam-phone"
    return "rtsp://127.0.0.1:8554/cam1"


def main() -> None:
    acquire_single_instance_lock()
    args = parse_args()
    args.device = resolve_device(args.device)
    weight_paths = resolve_weight_paths(args)

    active_mode = args.mode.strip() if args.mode else fetch_active_mode(args.model_api)
    enable_plate, save_plates = mode_flags(active_mode)

    print("=" * 60)
    if len(weight_paths) > 1:
        print("Live bridge ensemble (old + new):")
        for wp in weight_paths:
            print(f"  - {wp}")
    else:
        print(f"Live bridge weights: {weight_paths[0]}")
    print(f"Source: {args.source}")
    print(f"Push API: {args.api}")
    print(f"Device: {args.device}")
    print(f"Mode: {active_mode}  plate={enable_plate}  savePlates={save_plates}")
    print(f"Interval: {args.interval}s  conf={args.conf}  imgsz={args.imgsz}  infer_w={args.infer_width}  nms_iou={args.iou_nms}")
    print(
        f"FP filter: min_area_ratio={args.min_area_ratio}  "
        f"max_aspect={args.max_aspect}  min_box_px={args.min_box_px}"
    )
    print(f"Flush reads: {args.flush_reads} (drop stale buffered frames)")
    print(f"Lead extrapolation: {args.lead_ms}ms")
    print("Open frontend: http://localhost:5173/monitor")
    print("=" * 60)

    models = [YOLO(str(wp)) for wp in weight_paths]
    ocr = None
    ocr_worker: PlateOcrWorker | None = None
    if enable_plate:
        ocr = create_ocr()
        ocr_worker = PlateOcrWorker()
        ocr_worker.start()
        atexit.register(ocr_worker.stop)
        print("Plate OCR ready: RapidOCR (background thread)")

    last_camera_source = fetch_active_camera_source(args.camera_api) if args.camera_api else "sandbox"
    args.source = mediamtx_rtsp_for_source(last_camera_source)
    print(f"Initial camera source: {last_camera_source} -> {args.source}")

    cap = open_capture(args.source)
    if not cap.isOpened():
        # Fallback to HLS if local RTSP is unavailable
        fallback = "http://127.0.0.1:8888/cam1/index.m3u8"
        print(f"Cannot open {args.source}, fallback to {fallback}")
        args.source = fallback
        cap = open_capture(args.source)
    if not cap.isOpened():
        raise SystemExit(f"Cannot open source: {args.source}")

    frame_i = 0
    fail = 0
    last_plates: list[dict] = []
    last_channel_id = ""
    last_channel_name = ""
    last_switched_at = 0
    last_channel_poll = 0.0
    last_camera_poll = 0.0
    smoother = BoxSmoother(lead_ms=float(args.lead_ms))
    last_infer_t = time.time()
    while True:
        now = time.time()
        # Follow frontend model selection
        if not args.mode and args.model_api and frame_i % 2 == 0:
            new_mode = fetch_active_mode(args.model_api, active_mode)
            if new_mode != active_mode:
                active_mode = new_mode
                enable_plate, save_plates = mode_flags(active_mode)
                last_plates = []
                if ocr_worker:
                    ocr_worker.clear()
                if enable_plate and ocr is None:
                    ocr = create_ocr()
                    if ocr_worker is None:
                        ocr_worker = PlateOcrWorker()
                        ocr_worker.start()
                    print("Plate OCR loaded")
                print(f"mode switched -> {active_mode} (plate={enable_plate}, save={save_plates})")

        # Follow frontend video source (sandbox cam1 vs phone cam-phone)
        if args.camera_api and (now - last_camera_poll) >= max(0.2, float(args.camera_poll)):
            last_camera_poll = now
            try:
                new_src = fetch_active_camera_source(args.camera_api, last_camera_source)
                if new_src != last_camera_source:
                    new_rtsp = mediamtx_rtsp_for_source(new_src)
                    last_plates = []
                    if ocr_worker:
                        ocr_worker.clear()
                    smoother = BoxSmoother(lead_ms=float(args.lead_ms))
                    if active_mode != "anomaly" and new_rtsp != args.source:
                        print(f"video source {last_camera_source} -> {new_src}, reopen {new_rtsp}")
                        args.source = new_rtsp
                        cap = reconnect_capture(cap, args.source, reason=f"source -> {new_src}")
                        fail = 0
                    last_camera_source = new_src
            except Exception:
                pass

        # Detect channel switch quickly and reopen capture so boxes resume on new camera
        if (
            args.channel_api
            and last_camera_source == "sandbox"
            and (now - last_channel_poll) >= max(0.2, float(args.channel_poll))
        ):
            last_channel_poll = now
            try:
                cur = get_json(args.channel_api)
                data = cur.get("data") if isinstance(cur, dict) else None
                ch = ""
                channel_name = ""
                switched_at = 0
                if isinstance(data, dict):
                    ch = str(data.get("channelId") or "")
                    channel_name = str(data.get("channelName") or "")
                    try:
                        switched_at = int(data.get("switchedAt") or 0)
                    except (TypeError, ValueError):
                        switched_at = 0
                channel_changed = bool(ch and last_channel_id and ch != last_channel_id)
                switch_ts_changed = bool(switched_at and switched_at != last_switched_at)
                if channel_changed or switch_ts_changed:
                    last_plates = []
                    if ocr_worker:
                        ocr_worker.clear()
                    smoother = BoxSmoother(lead_ms=float(args.lead_ms))
                    reason = f"{last_channel_id or '?'} -> {ch or last_channel_id}"
                    # anomaly mode does not use this capture — skip reopen spam
                    if active_mode != "anomaly":
                        print(f"channel switched {reason}, reconnecting capture")
                        cap = reconnect_capture(cap, args.source, reason=reason)
                        fail = 0
                    else:
                        print(f"channel switched {reason} (anomaly idle, no capture)")
                if ch:
                    last_channel_id = ch
                if channel_name:
                    last_channel_name = channel_name
                if switched_at:
                    last_switched_at = switched_at
            except Exception:
                pass

        # anomaly: Java owns detection — do NOT read RTSP (avoids FFmpeg empty-frame / timeout spam)
        if active_mode == "anomaly":
            if cap is not None:
                try:
                    cap.release()
                except Exception:
                    pass
                cap = None
            if frame_i % 15 == 0:
                print("[anomaly] idle — Java ONNX is detecting; close this window if only testing anomaly")
            frame_i += 1
            time.sleep(1.0)
            continue

        if cap is None or not cap.isOpened():
            print("reopening capture after leaving anomaly / disconnect...")
            cap = open_capture(args.source)
            if not cap.isOpened():
                fail += 1
                time.sleep(0.5)
                if fail >= 20:
                    raise SystemExit("Too many open failures")
                continue
            fail = 0

        ok, frame = read_latest_frame(cap, flush_reads=int(args.flush_reads))
        if not ok or frame is None:
            fail += 1
            print(f"read fail #{fail}, reconnect...")
            cap = reconnect_capture(cap, args.source, reason=f"read fail #{fail}")
            if fail >= 20:
                raise SystemExit("Too many read failures")
            continue
        fail = 0
        frame_i += 1
        captured_at = int(time.time() * 1000)

        h, w = frame.shape[:2]
        t0 = time.time()
        dt_sec = max(1e-3, t0 - last_infer_t)
        last_infer_t = t0
        infer_frame, infer_scale = prepare_infer_frame(frame, int(args.infer_width))
        raw_detections = predict_ensemble(models, infer_frame, args)
        if infer_scale != 1.0:
            raw_detections = scale_detections(raw_detections, 1.0 / infer_scale)
        detections = smoother.update(raw_detections, dt_sec)
        names = [d["className"] for d in detections]

        plates: list[dict] = []
        if enable_plate and ocr_worker is not None:
            plates = ocr_worker.snapshot()
            if frame_i % max(1, args.plate_every) == 0:
                ocr_worker.set_context({
                    "ocr": ocr,
                    "api": args.api,
                    "plate_conf": args.plate_conf,
                    "save_plates": save_plates,
                    "active_mode": active_mode,
                    "source_tag": "ensemble" if len(models) > 1 else weight_paths[0].stem,
                    "camera_id": last_channel_id or "cam1",
                    "camera_name": last_channel_name or last_channel_id or "沙盘当前摄像头",
                })
                ocr_worker.submit(frame, raw_detections)
        elif enable_plate:
            plates = []
        else:
            plates = []

        plates = sync_plates_with_vehicles(plates, detections)
        plate_overlays = build_plate_overlays(plates, active_mode) if enable_plate else []

        summary = summarize(names)
        if active_mode == "plate_ocr" and plates:
            summary = summary + " | 车牌 " + "、".join(
                str(p.get("plateNumber") or "?") for p in plates[:3]
            )
        elif active_mode == "plate_det" and plates:
            summary = summary + f" | 车牌框×{len(plates)}"

        source_tag = "ensemble" if len(models) > 1 else weight_paths[0].stem
        payload = {
            "pushKind": "vehicle",
            "source": f"{source_tag}:{active_mode}",
            "cameraId": last_channel_id or "cam1",
            "cameraName": last_channel_name or last_channel_id or "沙盘当前摄像头",
            "capturedAt": captured_at,
            "vehicleCount": len(detections),
            "summary": summary,
            "detections": detections,
            "plateOverlays": plate_overlays,
            "plates": [],
            "imageWidth": int(w),
            "imageHeight": int(h),
            "inferenceTs": captured_at,
            "saveCongestion": bool(args.save_congestion and detections),
            "savePlates": False,
            "mode": active_mode,
        }

        try:
            resp = post_json(args.api, payload)
            dt = (time.time() - t0) * 1000
            print(
                f"[{frame_i}][{active_mode}] {summary}  "
                f"infer={dt:.0f}ms  pushed={resp.get('data', {}).get('pushed', resp)}"
            )
        except Exception as ex:
            print(f"[{frame_i}] push failed: {ex}")

        elapsed = time.time() - t0
        rest = max(0.0, float(args.interval) - elapsed)
        if rest > 0:
            time.sleep(rest)


if __name__ == "__main__":
    main()
