"""Offline image/video vehicle + plate recognition for demo and testing."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

import cv2
import torch
from ultralytics import YOLO
from PIL import Image, ImageDraw, ImageFont
from functools import lru_cache
import imageio_ffmpeg

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from plate_ocr import create_ocr, recognize_plates_from_vehicles  # noqa: E402


def args_parser():
    p = argparse.ArgumentParser()
    p.add_argument("--input", required=True)
    p.add_argument("--output-dir", required=True)
    p.add_argument("--weights", required=True)
    p.add_argument("--frame-step", type=int, default=3)
    p.add_argument("--max-frames", type=int, default=900)
    return p.parse_args()


def detect(model, frame):
    result = model.predict(
        frame, conf=0.25, imgsz=640,
        device="0" if torch.cuda.is_available() else "cpu", verbose=False
    )[0]
    dets = []
    for box in result.boxes:
        cls_id = int(box.cls[0])
        name = str(result.names.get(cls_id, "car"))
        if name.lower() not in {"car", "bus", "truck", "motorcycle", "bicycle", "vehicle", "obj"}:
            continue
        x1, y1, x2, y2 = [int(v) for v in box.xyxy[0].tolist()]
        dets.append({
            "className": "car" if name in {"vehicle", "obj"} else name,
            "confidence": round(float(box.conf[0]), 3),
            "bbox": [x1, y1, x2, y2],
        })
    return dets


@lru_cache(maxsize=32)
def plate_label(text):
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 25) if font_path.exists() else ImageFont.load_default()
    box = font.getbbox(text)
    image = Image.new("RGB", (max(90, box[2] - box[0] + 14), 36), (0, 120, 255))
    ImageDraw.Draw(image).text((7, 2), text, font=font, fill=(255, 255, 255))
    return cv2.cvtColor(__import__('numpy').array(image), cv2.COLOR_RGB2BGR)


def paste_label(frame, label, x, y):
    h, w = label.shape[:2]
    x, y = max(0, min(frame.shape[1] - w, x)), max(0, min(frame.shape[0] - h, y))
    frame[y:y+h, x:x+w] = label


def annotate(frame, dets, plates):
    out = frame.copy()
    for d in dets:
        x1, y1, x2, y2 = d["bbox"]
        cv2.rectangle(out, (x1, y1), (x2, y2), (32, 220, 90), 3)
        cv2.putText(out, f'{d["className"]} {d["confidence"]:.2f}',
                    (x1, max(22, y1 - 7)), cv2.FONT_HERSHEY_SIMPLEX, .68, (32, 220, 90), 2)
    for p in plates:
        x1, y1, x2, y2 = p.get("bbox", [0, 0, 0, 0])
        cv2.rectangle(out, (x1, y1), (x2, y2), (0, 120, 255), 3)
        paste_label(out, plate_label(str(p.get("plateNumber", "车牌"))), x1, max(0, y1 - 38))
    return out


def merge_plates(store, plates):
    for p in plates:
        key = p.get("plateNumber")
        if not key:
            continue
        # OCR can flicker by one character across adjacent video frames. Treat
        # same-length, one-character variants as the same physical plate and
        # retain the highest-confidence reading.
        similar = next((old_key for old_key in store
                        if len(old_key) == len(key)
                        and sum(a != b for a, b in zip(old_key, key)) <= 1), None)
        if similar is not None:
            if p.get("confidence", 0) > store[similar].get("confidence", 0):
                del store[similar]
                store[key] = p
            continue
        old = store.get(key)
        if old is None or p.get("confidence", 0) > old.get("confidence", 0):
            store[key] = p


def process_image(path, out_dir, model, ocr):
    frame = cv2.imread(str(path))
    if frame is None:
        raise ValueError("无法读取图片")
    dets = detect(model, frame)
    plates = recognize_plates_from_vehicles(ocr, frame, dets, min_score=.35, max_vehicles=10)
    cv2.imwrite(str(out_dir / "annotated.jpg"), annotate(frame, dets, plates))
    return {
        "mediaType": "image", "annotatedFile": "annotated.jpg",
        "width": frame.shape[1], "height": frame.shape[0],
        "vehicleCount": len(dets), "maxVehicleCount": len(dets),
        "detections": dets, "plates": plates,
        "analyzedFrames": 1, "frameCount": 1,
    }


def write_progress(out_dir, percent, message):
    (out_dir / "progress.json").write_text(
        json.dumps({"progress": int(percent), "message": message}, ensure_ascii=False), encoding="utf-8")


def process_video(path, out_dir, model, ocr, frame_step, max_frames):
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise ValueError("无法读取视频")
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    # OpenCV writes frames quickly to an intermediate file. It is transcoded
    # to H.264 at the end because browsers cannot reliably decode mp4v.
    output_name = "annotated.mp4"
    temp_video = out_dir / "annotated-temp.mp4"
    writer = cv2.VideoWriter(str(temp_video), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
    if not writer.isOpened():
        raise RuntimeError("无法创建浏览器兼容的 WebM 结果视频")
    # Analyze about two frames per second. This is sufficient for demo footage
    # and is dramatically faster than running YOLO+OCR on every third frame.
    frame_step = max(frame_step, int(round(fps / 2)))
    process_total = min(total, max_frames) if total > 0 else max_frames
    i = analyzed = max_vehicles = 0
    plate_store = {}
    last_dets, last_plates = [], []
    while i < max_frames:
        ok, frame = cap.read()
        if not ok:
            break
        if i % max(1, frame_step) == 0:
            last_dets = detect(model, frame)
            # OCR is considerably slower than vehicle detection on CPU. Run it
            # once per second while keeping the last labels on intervening frames.
            if analyzed % 2 == 0:
                last_plates = recognize_plates_from_vehicles(ocr, frame, last_dets, min_score=.35, max_vehicles=8)
                merge_plates(plate_store, last_plates)
            max_vehicles = max(max_vehicles, len(last_dets))
            analyzed += 1
            write_progress(out_dir, 5 + 90 * i / max(process_total, 1), f"已分析 {i}/{process_total} 帧")
        writer.write(annotate(frame, last_dets, last_plates))
        i += 1
    writer.release()
    cap.release()
    write_progress(out_dir, 96, "正在生成浏览器兼容视频")
    output_video = out_dir / output_name
    command = [
        imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error",
        "-i", str(temp_video), "-an", "-c:v", "libx264", "-preset", "veryfast",
        "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output_video),
    ]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0 or not output_video.exists():
        raise RuntimeError("生成浏览器兼容视频失败: " + completed.stderr[-300:])
    temp_video.unlink(missing_ok=True)
    return {
        "mediaType": "video", "annotatedFile": output_name,
        "width": width, "height": height, "fps": round(fps, 2),
        "durationSeconds": round(i / max(fps, 1), 2),
        "frameCount": min(total, i) if total > 0 else i,
        "analyzedFrames": analyzed, "maxVehicleCount": max_vehicles,
        "plates": list(plate_store.values()),
    }


def main():
    args = args_parser()
    src = Path(args.input).resolve()
    out = Path(args.output_dir).resolve()
    out.mkdir(parents=True, exist_ok=True)
    model = YOLO(args.weights)
    ocr = create_ocr()
    write_progress(out, 3, "模型已加载")
    if src.suffix.lower() in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}:
        result = process_image(src, out, model, ocr)
    else:
        result = process_video(src, out, model, ocr, args.frame_step, args.max_frames)
    (out / "result.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    write_progress(out, 100, "识别完成")


if __name__ == "__main__":
    main()
