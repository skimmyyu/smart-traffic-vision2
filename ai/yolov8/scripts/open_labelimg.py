"""Launch LabelImg with Qt plugin path fixed (Windows).

LabelImg CLI: labelImg [image_dir] [class_file] [save_dir]

Always prefer C:\\yolo_data\\... ASCII paths — LabelImg/Qt often crashes
when Open/Change Save Dir points into paths with parentheses or non-ASCII
(e.g. smart-traffic-vision(2)).
"""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path


def _fix_qt_plugins(root: Path) -> None:
    plugins = root / ".venv" / "Lib" / "site-packages" / "PyQt5" / "Qt5" / "plugins"
    platforms = plugins / "platforms"
    qt_bin = root / ".venv" / "Lib" / "site-packages" / "PyQt5" / "Qt5" / "bin"
    if platforms.is_dir():
        os.environ["QT_PLUGIN_PATH"] = str(plugins)
        os.environ["QT_QPA_PLATFORM_PLUGIN_PATH"] = str(platforms)
    if qt_bin.is_dir():
        os.environ["PATH"] = str(qt_bin) + os.pathsep + os.environ.get("PATH", "")


def _pick_dataset(root: Path, kind: str) -> Path:
    if kind == "anomaly":
        ascii_ds = Path(r"C:\yolo_data\sandbox_anomaly")
        local_ds = root / "datasets" / "sandbox_anomaly"
    else:
        ascii_ds = Path(r"C:\yolo_data\sandbox_labeled")
        local_ds = root / "datasets" / "sandbox_labeled"

    if ascii_ds.is_dir() and (ascii_ds / "images" / "train").is_dir():
        return ascii_ds
    return local_ds


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    _fix_qt_plugins(root)

    args = [a for a in sys.argv[1:] if a]
    kind = "car"
    if args and args[0] in {"--anomaly", "anomaly", "--debris"}:
        kind = "anomaly"
        args = args[1:]

    dataset = _pick_dataset(root, kind)
    img = dataset / "images" / "train"
    lbl = dataset / "labels" / "train"
    classes = dataset / "classes.txt"

    if len(args) >= 3:
        img = Path(args[0])
        classes = Path(args[1])
        lbl = Path(args[2])
    elif len(args) == 2:
        img = Path(args[0])
        lbl = Path(args[1])

    # Hard guard: refuse paths that commonly crash Qt file dialogs
    for p in (img, lbl, classes):
        s = str(p)
        if "(" in s or ")" in s:
            raise SystemExit(
                "Path contains parentheses and will crash LabelImg:\n"
                f"  {p}\n"
                "Use C:\\yolo_data\\sandbox_anomaly\\ instead.\n"
                "Do NOT change Save Dir inside LabelImg to the project (2) folder."
            )

    if not img.is_dir():
        raise SystemExit(f"Image dir not found: {img}\nPut images into: {img}")
    if not classes.is_file():
        raise SystemExit(f"Classes file not found: {classes}")
    if not lbl.is_dir():
        lbl.mkdir(parents=True, exist_ok=True)

    # LabelImg YOLO mode reads classes.txt from the label save dir
    shutil.copy2(classes, lbl / "classes.txt")
    val_lbl = dataset / "labels" / "val"
    if val_lbl.is_dir():
        shutil.copy2(classes, val_lbl / "classes.txt")

    print("=" * 50)
    print(f"Mode   : {kind}")
    print(f"Dataset: {dataset}")
    print(f"Images : {img}")
    print(f"Classes: {classes}")
    print(f"Labels : {lbl}")
    print("=" * 50)
    print("IMPORTANT: Prefer C:\\yolo_data\\... paths.")
    print("If LabelImg still crashes when drawing boxes, use label-anomaly.bat instead.")
    print()
    print("In LabelImg:")
    print("  1. Click YOLO format (not PascalVOC)")
    print("  2. View -> Auto Save")
    print("  3. W = draw box, D/A = next/prev")
    if kind == "anomaly":
        print("  4. Class = debris (异物)")
        print("  Tip: safer tool = ai\\yolov8\\label-anomaly.bat")

    from labelImg.labelImg import main as labelimg_main

    sys.argv = ["labelImg", str(img), str(classes), str(lbl)]
    labelimg_main()


if __name__ == "__main__":
    main()
