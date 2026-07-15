"""Chinese plate OCR helpers (PP-OCR ONNX via RapidOCR).

Pipeline stage matching design doc:
  plate_det.onnx  -> text/plate localization inside vehicle crop
  ppocr.onnx      -> character recognition on cropped plate
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT.parent.parent
MODELS_DIR = PROJECT / "models"

# Mainland China plate patterns (normal + new-energy)
PLATE_RE = re.compile(
    r"^["
    r"京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼"
    r"]"
    r"[A-Z]"
    r"[A-Z0-9]{5,6}$"
)

# Common OCR confusions on plates
_FIX = str.maketrans(
    {
        "O": "0",
        "I": "1",
        "Z": "2",
        "S": "5",
        "B": "8",
    }
)


def _normalize_plate(text: str) -> str:
    t = re.sub(r"[\s·•\-\._]", "", text or "").upper()
    # Keep Chinese province char + alnum only
    t = re.sub(r"[^京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼A-Z0-9]", "", t)
    # Drop duplicated leading province chars: 皖京H7912N -> 京H7912N
    provinces = "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼"
    while len(t) >= 2 and t[0] in provinces and t[1] in provinces:
        t = t[1:]
    return t


def looks_like_plate(text: str) -> bool:
    t = _normalize_plate(text)
    if PLATE_RE.match(t):
        return True
    # Soft check: province + letter + >=4 alnum (sandbox / partial OCR)
    if len(t) >= 6 and t[0] in "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼" and t[1].isalpha():
        return True
    return False


def create_ocr(models_dir: Path | None = None):
    """Create RapidOCR using project ONNX models when present."""
    from rapidocr_onnxruntime import RapidOCR

    md = Path(models_dir) if models_dir else MODELS_DIR
    det = md / "plate_det.onnx"
    rec = md / "ppocr.onnx"
    cls = md / "ppocr_cls.onnx"
    kwargs: dict[str, Any] = {}
    if det.exists() and rec.exists():
        kwargs["det_model_path"] = str(det)
        kwargs["rec_model_path"] = str(rec)
        if cls.exists():
            kwargs["cls_model_path"] = str(cls)
    return RapidOCR(**kwargs)


def crop_vehicle_plate_roi(frame: np.ndarray, bbox: list[int], lower_ratio: float = 0.35) -> tuple[np.ndarray, list[int]]:
    """Crop lower portion of vehicle box where plates usually sit."""
    h, w = frame.shape[:2]
    x1, y1, x2, y2 = [int(v) for v in bbox]
    x1 = max(0, min(w - 1, x1))
    x2 = max(0, min(w, x2))
    y1 = max(0, min(h - 1, y1))
    y2 = max(0, min(h, y2))
    if x2 <= x1 or y2 <= y1:
        return frame[0:0, 0:0], [0, 0, 0, 0]
    cy1 = y1 + int((y2 - y1) * lower_ratio)
    pad_x = max(2, int((x2 - x1) * 0.02))
    rx1 = max(0, x1 - pad_x)
    rx2 = min(w, x2 + pad_x)
    crop = frame[cy1:y2, rx1:rx2]
    return crop, [rx1, cy1, rx2, y2]


def recognize_plate_in_crop(ocr, crop: np.ndarray, min_score: float = 0.45) -> tuple[str | None, float, list[int] | None]:
    """Run plate_det+ppocr on a vehicle crop. Returns (plate, score, local_bbox)."""
    if crop is None or crop.size == 0 or crop.shape[0] < 8 or crop.shape[1] < 16:
        return None, 0.0, None

    # Upscale tiny crops for better OCR
    ch, cw = crop.shape[:2]
    img = crop
    if ch < 48 or cw < 120:
        scale = max(48 / max(ch, 1), 120 / max(cw, 1), 2.0)
        img = cv2.resize(crop, (int(cw * scale), int(ch * scale)), interpolation=cv2.INTER_CUBIC)

    result, _elapse = ocr(img)
    if not result:
        return None, 0.0, None

    best_text = None
    best_score = 0.0
    best_box = None
    scale_x = crop.shape[1] / img.shape[1]
    scale_y = crop.shape[0] / img.shape[0]

    for item in result:
        # item: [box_points, text, score]
        if not item or len(item) < 3:
            continue
        box, text, score = item[0], str(item[1]), float(item[2])
        text_n = _normalize_plate(text)
        if not text_n:
            continue
        # Prefer plate-like strings; still keep high-score short alnum for sandbox
        plate_like = looks_like_plate(text_n)
        soft_ok = len(text_n) >= 5 and any(ch.isalpha() for ch in text_n) and any(ch.isdigit() for ch in text_n)
        if not plate_like and not soft_ok:
            continue
        if score < min_score and not plate_like:
            continue
        rank = score + (0.2 if plate_like else 0.0)
        if rank > best_score:
            best_score = score
            best_text = text_n
            xs = [p[0] * scale_x for p in box]
            ys = [p[1] * scale_y for p in box]
            best_box = [int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys))]

    return best_text, float(best_score), best_box


def recognize_plates_from_vehicles(
    ocr,
    frame: np.ndarray,
    vehicle_dets: list[dict],
    min_score: float = 0.45,
    max_vehicles: int = 5,
) -> list[dict]:
    """For each vehicle detection, OCR plate inside its box."""
    plates: list[dict] = []
    for det in vehicle_dets[:max_vehicles]:
        bbox = det.get("bbox")
        if not isinstance(bbox, (list, tuple)) or len(bbox) < 4:
            continue
        crop, roi = crop_vehicle_plate_roi(frame, list(bbox))
        text, score, local = recognize_plate_in_crop(ocr, crop, min_score=min_score)
        if not text:
            continue
        if local is not None:
            abs_box = [
                roi[0] + local[0],
                roi[1] + local[1],
                roi[0] + local[2],
                roi[1] + local[3],
            ]
        else:
            abs_box = roi
        plates.append(
            {
                "plateNumber": text,
                "confidence": round(score, 3),
                "bbox": abs_box,
                "vehicleBbox": [int(v) for v in bbox[:4]],
                "vehicleClass": det.get("className", "car"),
            }
        )
    return plates
