"""
Train YOLOv8n on the local traffic dataset.

Usage:
  .\\.venv\\Scripts\\python.exe scripts\\train.py
  .\\.venv\\Scripts\\python.exe scripts\\train.py --epochs 50 --batch 8 --imgsz 640
"""
from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = ROOT / "datasets" / "traffic" / "data.yaml"
DEFAULT_WEIGHTS = ROOT / "weights" / "yolov8n.pt"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Train YOLOv8n for smart-traffic-vision")
    p.add_argument("--data", type=str, default=str(DEFAULT_DATA), help="dataset yaml")
    p.add_argument("--weights", type=str, default=str(DEFAULT_WEIGHTS), help="pretrained .pt")
    p.add_argument("--epochs", type=int, default=50)
    p.add_argument("--batch", type=int, default=8)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--device", type=str, default="0", help="0 for GPU, cpu for CPU")
    p.add_argument("--name", type=str, default="traffic-yolov8n")
    p.add_argument("--workers", type=int, default=2)
    p.add_argument("--patience", type=int, default=50, help="early-stop patience (epochs)")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    data = Path(args.data)
    weights = Path(args.weights)

    if not data.exists():
        raise SystemExit(f"Dataset yaml not found: {data}")

    if not weights.exists():
        print(f"Local weights missing ({weights}), falling back to yolov8n.pt download")
        model = YOLO("yolov8n.pt")
    else:
        model = YOLO(str(weights))

    # Quick sanity: empty train folder will fail clearly
    train_img_dir = data.parent / "images" / "train"
    n_imgs = len(list(train_img_dir.glob("*.*"))) if train_img_dir.exists() else 0
    if n_imgs == 0:
        raise SystemExit(
            f"No training images in {train_img_dir}\n"
            "Put labeled images there first, then re-run."
        )

    results = model.train(
        data=str(data),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=args.device,
        project=str(ROOT / "runs" / "detect"),
        name=args.name,
        workers=args.workers,
        exist_ok=True,
        pretrained=True,
        patience=args.patience,
        save=True,
        plots=True,
    )
    best = ROOT / "runs" / "detect" / args.name / "weights" / "best.pt"
    print("\nTraining finished.")
    print(f"Best weights: {best}")
    print("Next: python scripts/export_onnx.py")


if __name__ == "__main__":
    main()
