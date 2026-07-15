"""Export trained / pretrained YOLOv8n to ONNX for later Java backend integration."""
from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WEIGHTS = ROOT / "runs" / "detect" / "traffic-yolov8n" / "weights" / "best.pt"
FALLBACK = ROOT / "weights" / "yolov8n.pt"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Export YOLOv8 to ONNX")
    p.add_argument("--weights", type=str, default="")
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--opset", type=int, default=12)
    p.add_argument("--dynamic", action="store_true", default=False)
    p.add_argument("--simplify", action="store_true", default=True)
    return p.parse_args()


def main() -> None:
    args = parse_args()
    if args.weights:
        weights = Path(args.weights)
    elif DEFAULT_WEIGHTS.exists():
        weights = DEFAULT_WEIGHTS
    else:
        weights = FALLBACK

    if not weights.exists():
        raise SystemExit(f"Weights not found: {weights}")

    model = YOLO(str(weights))
    out = model.export(
        format="onnx",
        imgsz=args.imgsz,
        opset=args.opset,
        dynamic=args.dynamic,
        simplify=args.simplify,
    )
    print(f"ONNX exported: {out}")


if __name__ == "__main__":
    main()
