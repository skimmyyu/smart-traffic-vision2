"""
Download a small ready-made detection dataset and convert it to local traffic classes.

Source: Ultralytics COCO128 (~128 images, auto-download)
Keep only: car / bus / truck / motorcycle / bicycle / person
Remap to datasets/traffic/data.yaml class ids.

Usage:
  .\\.venv\\Scripts\\python.exe scripts\\download_ready_dataset.py
"""
from __future__ import annotations

import random
import shutil
from pathlib import Path

from ultralytics.utils.downloads import download


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "datasets" / "traffic_ready"
# COCO class id -> our data.yaml id
# our names: 0 car, 1 bus, 2 truck, 3 motorcycle, 4 bicycle, 5 person
COCO_TO_OURS = {
    2: 0,  # car
    5: 1,  # bus
    7: 2,  # truck
    3: 3,  # motorcycle
    1: 4,  # bicycle
    0: 5,  # person
}


def convert_label(src: Path, dst: Path) -> int:
    kept = []
    if src.exists():
        for line in src.read_text(encoding="utf-8").splitlines():
            parts = line.strip().split()
            if len(parts) < 5:
                continue
            cid = int(float(parts[0]))
            if cid not in COCO_TO_OURS:
                continue
            parts[0] = str(COCO_TO_OURS[cid])
            kept.append(" ".join(parts))
    dst.write_text("\n".join(kept) + ("\n" if kept else ""), encoding="utf-8")
    return len(kept)


def main() -> None:
    zip_url = "https://github.com/ultralytics/assets/releases/download/v0.0.0/coco128.zip"
    raw_dir = ROOT / "datasets" / "_coco128_raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    print("Downloading COCO128 (small, ~7MB)...")
    download([zip_url], dir=raw_dir, unzip=True, delete=True)

    # Ultralytics zip usually extracts to coco128/
    candidates = [
        raw_dir / "coco128",
        raw_dir / "coco128-seg",
        raw_dir,
    ]
    coco_root = None
    for c in candidates:
        if (c / "images" / "train2017").exists() or (c / "images" / "train").exists():
            coco_root = c
            break
    if coco_root is None:
        # search one level
        for p in raw_dir.rglob("images"):
            parent = p.parent
            if any((p / d).exists() for d in ("train2017", "train", "val2017", "val")):
                coco_root = parent
                break
    if coco_root is None:
        raise SystemExit(f"Cannot find coco128 images under {raw_dir}")

    img_train = next(
        (coco_root / "images" / d for d in ("train2017", "train") if (coco_root / "images" / d).exists()),
        None,
    )
    lbl_train = next(
        (coco_root / "labels" / d for d in ("train2017", "train") if (coco_root / "labels" / d).exists()),
        None,
    )
    if img_train is None or lbl_train is None:
        raise SystemExit(f"Unexpected coco128 layout at {coco_root}")

    if OUT.exists():
        shutil.rmtree(OUT)
    for split in ("train", "val"):
        (OUT / "images" / split).mkdir(parents=True, exist_ok=True)
        (OUT / "labels" / split).mkdir(parents=True, exist_ok=True)

    images = sorted([p for p in img_train.glob("*.*") if p.suffix.lower() in {".jpg", ".jpeg", ".png"}])
    random.seed(42)
    random.shuffle(images)

    # Keep only images that still have at least one traffic-related box after filtering
    usable = []
    tmp_counts = []
    for img in images:
        lbl = lbl_train / f"{img.stem}.txt"
        # dry-run count
        n = 0
        if lbl.exists():
            for line in lbl.read_text(encoding="utf-8").splitlines():
                parts = line.strip().split()
                if parts and int(float(parts[0])) in COCO_TO_OURS:
                    n += 1
        if n > 0:
            usable.append(img)
            tmp_counts.append(n)

    if not usable:
        raise SystemExit("No traffic-related labels found in COCO128")

    # ~80% train / 20% val
    cut = max(1, int(len(usable) * 0.8))
    splits = {
        "train": usable[:cut],
        "val": usable[cut:] or usable[-1:],
    }

    stats = {"train": 0, "val": 0, "boxes": 0}
    for split, imgs in splits.items():
        for img in imgs:
            dst_img = OUT / "images" / split / img.name
            shutil.copy2(img, dst_img)
            n = convert_label(lbl_train / f"{img.stem}.txt", OUT / "labels" / split / f"{img.stem}.txt")
            stats[split] += 1
            stats["boxes"] += n

    yaml_text = f"""# Ready-made small traffic subset (from COCO128)
# classes aligned with this project

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
    (OUT / "data.yaml").write_text(yaml_text, encoding="utf-8")

    print("=" * 56)
    print(f"Ready dataset: {OUT}")
    print(f"train images: {stats['train']}")
    print(f"val images:   {stats['val']}")
    print(f"total boxes:  {stats['boxes']}")
    print("=" * 56)
    print("Train with:")
    print(r"  .\.venv\Scripts\python.exe scripts\train.py --data datasets\traffic_ready\data.yaml --epochs 30 --batch 8")


if __name__ == "__main__":
    main()
