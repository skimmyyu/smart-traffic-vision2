"""
Run YOLOv8n inference on an image / folder / RTSP URL.

Examples:
  .\\.venv\\Scripts\\python.exe scripts\\predict.py --source samples\\demo.jpg
  .\\.venv\\Scripts\\python.exe scripts\\predict.py --source rtsp://127.0.0.1:8554/cam1
"""
from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WEIGHTS = ROOT / "weights" / "yolov8n.pt"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="YOLOv8n predict / smoke test")
    p.add_argument("--weights", type=str, default=str(DEFAULT_WEIGHTS))
    p.add_argument("--source", type=str, required=True, help="image/video/folder/rtsp")
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--conf", type=float, default=0.25)
    p.add_argument("--device", type=str, default="0")
    p.add_argument("--save", action="store_true", default=True)
    return p.parse_args()


def main() -> None:
    args = parse_args()
    weights = Path(args.weights)
    model = YOLO(str(weights) if weights.exists() else "yolov8n.pt")

    results = model.predict(
        source=args.source,
        imgsz=args.imgsz,
        conf=args.conf,
        device=args.device,
        project=str(ROOT / "runs" / "predict"),
        name="smoke",
        exist_ok=True,
        save=args.save,
        stream=False,
    )
    for i, r in enumerate(results):
        n = 0 if r.boxes is None else len(r.boxes)
        print(f"[{i}] detections={n}  path={getattr(r, 'path', '')}")
    print(f"Saved under: {ROOT / 'runs' / 'predict' / 'smoke'}")


if __name__ == "__main__":
    main()
