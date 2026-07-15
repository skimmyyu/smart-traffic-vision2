"""
Build a small ready-to-train dataset by auto-labeling local sandbox frames
with pretrained YOLOv8n (no external dataset download needed).

Input:  datasets/traffic/images/train/*.jpg  (your multi-view captures)
Output: datasets/traffic_ready/  (YOLO format, 6 classes)

Usage:
  .\\.venv\\Scripts\\python.exe scripts\\build_ready_dataset.py
  .\\.venv\\Scripts\\python.exe scripts\\build_ready_dataset.py --conf 0.35 --val-ratio 0.2
"""
from __future__ import annotations

import argparse
import random
import shutil
from pathlib import Path

from ultralytics import YOLO


# COCO id -> our project classes in data.yaml
# 0 car, 1 bus, 2 truck, 3 motorcycle, 4 bicycle, 5 person
COCO_TO_OURS = {
    2: 0,  # car
    5: 1,  # bus
    7: 2,  # truck
    3: 3,  # motorcycle
    1: 4,  # bicycle
    0: 5,  # person
}
OURS_NAMES = ["car", "bus", "truck", "motorcycle", "bicycle", "person"]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Auto-label sandbox images into ready dataset")
    p.add_argument("--source", type=str, default="")
    p.add_argument("--out", type=str, default="")
    p.add_argument("--weights", type=str, default="")
    p.add_argument("--conf", type=float, default=0.35)
    p.add_argument("--iou", type=float, default=0.5)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--val-ratio", type=float, default=0.2)
    p.add_argument("--device", type=str, default="0")
    p.add_argument("--keep-empty", action="store_true", help="keep images with no detections")
    return p.parse_args()


def xyxy_to_yolo(x1, y1, x2, y2, w, h):
    bw = max(0.0, x2 - x1)
    bh = max(0.0, y2 - y1)
    cx = x1 + bw / 2.0
    cy = y1 + bh / 2.0
    return cx / w, cy / h, bw / w, bh / h


def main() -> None:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    source = Path(args.source) if args.source else root / "datasets" / "traffic" / "images" / "train"
    out = Path(args.out) if args.out else root / "datasets" / "traffic_ready"
    weights = Path(args.weights) if args.weights else root / "weights" / "yolov8n.pt"

    images = sorted(
        [p for p in source.glob("*.*") if p.suffix.lower() in {".jpg", ".jpeg", ".png"}]
    )
    if not images:
        raise SystemExit(f"No images found in {source}\nRun capture-all-views.bat first.")

    if not weights.exists():
        print(f"Local weights missing ({weights}), using yolov8n.pt download name")
        model = YOLO("yolov8n.pt")
    else:
        model = YOLO(str(weights))

    if out.exists():
        shutil.rmtree(out)
    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True, exist_ok=True)
        (out / "labels" / split).mkdir(parents=True, exist_ok=True)

    print("=" * 60)
    print(f"Auto-label {len(images)} images with YOLOv8n")
    print(f"conf={args.conf}  device={args.device}")
    print(f"out={out}")
    print("=" * 60)

    labeled = []  # (img_path, label_lines)
    class_counts = {i: 0 for i in range(len(OURS_NAMES))}

    for i, img in enumerate(images, start=1):
        results = model.predict(
            source=str(img),
            conf=args.conf,
            iou=args.iou,
            imgsz=args.imgsz,
            device=args.device,
            verbose=False,
        )
        r = results[0]
        h, w = r.orig_shape
        lines = []
        if r.boxes is not None and len(r.boxes) > 0:
            for box in r.boxes:
                coco_id = int(box.cls.item())
                if coco_id not in COCO_TO_OURS:
                    continue
                our_id = COCO_TO_OURS[coco_id]
                x1, y1, x2, y2 = box.xyxy[0].tolist()
                cx, cy, bw, bh = xyxy_to_yolo(x1, y1, x2, y2, w, h)
                # clamp
                cx = min(max(cx, 0.0), 1.0)
                cy = min(max(cy, 0.0), 1.0)
                bw = min(max(bw, 1e-6), 1.0)
                bh = min(max(bh, 1e-6), 1.0)
                lines.append(f"{our_id} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
                class_counts[our_id] += 1

        if lines or args.keep_empty:
            labeled.append((img, lines))
            print(f"[{i}/{len(images)}] {img.name}: {len(lines)} boxes")
        else:
            print(f"[{i}/{len(images)}] {img.name}: skip (no target class)")

    if not labeled:
        raise SystemExit("No usable labels produced. Try lowering --conf (e.g. 0.25).")

    random.seed(42)
    random.shuffle(labeled)
    n_val = max(1, int(len(labeled) * args.val_ratio))
    val_set = labeled[:n_val]
    train_set = labeled[n_val:] or labeled[1:]

    def dump(split_name: str, items):
        for img, lines in items:
            shutil.copy2(img, out / "images" / split_name / img.name)
            (out / "labels" / split_name / f"{img.stem}.txt").write_text(
                ("\n".join(lines) + "\n") if lines else "",
                encoding="utf-8",
            )

    dump("train", train_set)
    dump("val", val_set)

    yaml_text = f"""# Auto-labeled ready dataset from sandbox captures + YOLOv8n
# Review labels if you need higher quality; good enough to start fine-tuning.

path: datasets/traffic_ready
train: images/train
val: images/val

names:
  0: car
  1: bus
  2: truck
  3: motorcycle
  4: bicycle
  5: person
"""
    (out / "data.yaml").write_text(yaml_text, encoding="utf-8")

    print("\n" + "=" * 60)
    print(f"Ready dataset: {out}")
    print(f"train: {len(train_set)}  val: {len(val_set)}")
    print("class counts:")
    for i, name in enumerate(OURS_NAMES):
        print(f"  {i} {name}: {class_counts[i]}")
    print("=" * 60)
    print("Start training:")
    print(
        r"  .\.venv\Scripts\python.exe scripts\train.py "
        r"--data datasets\traffic_ready\data.yaml --epochs 30 --batch 8 --name traffic-ready"
    )


if __name__ == "__main__":
    main()
