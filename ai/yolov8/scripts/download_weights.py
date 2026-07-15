"""Download YOLOv8n pretrained weights into ./weights/yolov8n.pt"""
from pathlib import Path
import shutil

from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS = ROOT / "weights"
WEIGHTS.mkdir(parents=True, exist_ok=True)
local = WEIGHTS / "yolov8n.pt"

print("Loading yolov8n.pt (will download if missing)...")
model = YOLO("yolov8n.pt")

# Ultralytics may leave the file in CWD after first download
candidates = [
    Path.cwd() / "yolov8n.pt",
    ROOT / "yolov8n.pt",
    Path(model.ckpt_path) if getattr(model, "ckpt_path", None) else None,
]
for c in candidates:
    if c and Path(c).exists():
        if Path(c).resolve() != local.resolve():
            shutil.copy2(c, local)
        break

if not local.exists():
    # Last resort: save via model path attribute used by recent ultralytics
    src = getattr(getattr(model, "model", None), "pt_path", None) or getattr(model, "pt_path", None)
    if src and Path(src).exists():
        shutil.copy2(src, local)

print(f"Model ready. Local copy: {local if local.exists() else '(using ultralytics cache)'}")
print("Next: python scripts/capture_frames.py   or   python scripts/predict.py --source <image>")
