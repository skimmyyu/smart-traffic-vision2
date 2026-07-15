"""
Simple YOLO box labeler (tkinter) — avoids LabelImg/PyQt crashes.

Usage:
  .venv\\Scripts\\python.exe scripts\\simple_yolo_labeler.py
  .venv\\Scripts\\python.exe scripts\\simple_yolo_labeler.py --dataset C:\\yolo_data\\sandbox_anomaly

Controls:
  Left drag  : draw box (class = debris / 异物)
  Delete/Backspace : remove last box
  D / Right  : next image (auto-save)
  A / Left   : prev image (auto-save)
  S          : save
  R          : clear all boxes on current image
"""
from __future__ import annotations

import argparse
import tkinter as tk
from pathlib import Path
from tkinter import messagebox

from PIL import Image, ImageTk


IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Simple YOLO labeler")
    p.add_argument(
        "--dataset",
        type=str,
        default=r"C:\yolo_data\sandbox_anomaly",
        help="dataset root with images/train and labels/train",
    )
    p.add_argument("--class-name", type=str, default="debris")
    p.add_argument("--class-id", type=int, default=0)
    return p.parse_args()


class SimpleYoloLabeler:
    def __init__(self, dataset: Path, class_id: int, class_name: str) -> None:
        self.dataset = dataset
        self.img_dir = dataset / "images" / "train"
        self.lbl_dir = dataset / "labels" / "train"
        self.class_id = class_id
        self.class_name = class_name

        self.lbl_dir.mkdir(parents=True, exist_ok=True)
        self.images = sorted(
            [p for p in self.img_dir.iterdir() if p.suffix.lower() in IMG_EXTS]
        )
        if not self.images:
            raise SystemExit(
                f"No images in {self.img_dir}\n"
                f"Put jpg/png files there first, then reopen."
            )

        self.idx = 0
        self.boxes: list[tuple[float, float, float, float]] = []  # xyxy pixel
        self.scale = 1.0
        self.offset_x = 0
        self.offset_y = 0
        self.img_w = 1
        self.img_h = 1
        self.tk_img = None
        self.drag_start: tuple[int, int] | None = None
        self.temp_rect = None

        self.root = tk.Tk()
        self.root.title("异物标注 (Simple YOLO Labeler)")
        self.root.geometry("1100x780")

        top = tk.Frame(self.root)
        top.pack(fill=tk.X, padx=8, pady=6)
        self.info = tk.Label(top, text="", anchor="w", font=("Segoe UI", 11))
        self.info.pack(side=tk.LEFT, fill=tk.X, expand=True)

        btns = tk.Frame(top)
        btns.pack(side=tk.RIGHT)
        tk.Button(btns, text="上一张 (A)", command=self.prev_image).pack(side=tk.LEFT, padx=2)
        tk.Button(btns, text="下一张 (D)", command=self.next_image).pack(side=tk.LEFT, padx=2)
        tk.Button(btns, text="保存 (S)", command=self.save_labels).pack(side=tk.LEFT, padx=2)
        tk.Button(btns, text="删最后框", command=self.undo_box).pack(side=tk.LEFT, padx=2)
        tk.Button(btns, text="清空本图", command=self.clear_boxes).pack(side=tk.LEFT, padx=2)

        tip = tk.Label(
            self.root,
            text="拖拽画框=异物(debris) | Delete=删最后框 | A/D=上一张/下一张(自动保存) | S=保存",
            anchor="w",
            fg="#333",
        )
        tip.pack(fill=tk.X, padx=8)

        self.canvas = tk.Canvas(self.root, bg="#1e1e1e", highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)
        self.root.bind("<Configure>", self.on_resize)
        self.root.bind("a", lambda e: self.prev_image())
        self.root.bind("A", lambda e: self.prev_image())
        self.root.bind("d", lambda e: self.next_image())
        self.root.bind("D", lambda e: self.next_image())
        self.root.bind("s", lambda e: self.save_labels())
        self.root.bind("S", lambda e: self.save_labels())
        self.root.bind("r", lambda e: self.clear_boxes())
        self.root.bind("R", lambda e: self.clear_boxes())
        self.root.bind("<Delete>", lambda e: self.undo_box())
        self.root.bind("<BackSpace>", lambda e: self.undo_box())
        self.root.bind("<Left>", lambda e: self.prev_image())
        self.root.bind("<Right>", lambda e: self.next_image())

        # Canvas size is 1x1 until mapped; load after first layout
        self.root.after_idle(lambda: self.load_image(0))
        self.root.mainloop()

    def label_path(self) -> Path:
        return self.lbl_dir / f"{self.images[self.idx].stem}.txt"

    def load_image(self, idx: int) -> None:
        self.idx = max(0, min(idx, len(self.images) - 1))
        path = self.images[self.idx]
        self.pil = Image.open(path).convert("RGB")
        self.img_w, self.img_h = self.pil.size
        if self.img_w < 1 or self.img_h < 1:
            raise SystemExit(f"Invalid image size: {path} ({self.img_w}x{self.img_h})")
        self.boxes = self.read_yolo(self.label_path())
        self.root.update_idletasks()
        self.redraw()
        self.update_info()

    def update_info(self) -> None:
        p = self.images[self.idx]
        self.info.config(
            text=f"[{self.idx + 1}/{len(self.images)}] {p.name}  |  "
            f"boxes={len(self.boxes)}  |  class={self.class_id}:{self.class_name}  |  "
            f"save→ {self.label_path()}"
        )

    def read_yolo(self, path: Path) -> list[tuple[float, float, float, float]]:
        if not path.is_file():
            return []
        out: list[tuple[float, float, float, float]] = []
        for line in path.read_text(encoding="utf-8").splitlines():
            parts = line.strip().split()
            if len(parts) != 5:
                continue
            _, xc, yc, bw, bh = parts
            xc, yc, bw, bh = map(float, (xc, yc, bw, bh))
            x1 = (xc - bw / 2) * self.img_w
            y1 = (yc - bh / 2) * self.img_h
            x2 = (xc + bw / 2) * self.img_w
            y2 = (yc + bh / 2) * self.img_h
            out.append((x1, y1, x2, y2))
        return out

    def save_labels(self) -> None:
        lines: list[str] = []
        for x1, y1, x2, y2 in self.boxes:
            x1, x2 = sorted((max(0, x1), min(self.img_w, x2)))
            y1, y2 = sorted((max(0, y1), min(self.img_h, y2)))
            bw = x2 - x1
            bh = y2 - y1
            if bw < 2 or bh < 2:
                continue
            xc = ((x1 + x2) / 2) / self.img_w
            yc = ((y1 + y2) / 2) / self.img_h
            nw = bw / self.img_w
            nh = bh / self.img_h
            lines.append(
                f"{self.class_id} {xc:.6f} {yc:.6f} {nw:.6f} {nh:.6f}"
            )
        path = self.label_path()
        if lines:
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        elif path.exists() and path.name != "classes.txt":
            path.unlink()
        self.update_info()

    def canvas_to_image(self, cx: int, cy: int) -> tuple[float, float]:
        ix = (cx - self.offset_x) / max(self.scale, 1e-6)
        iy = (cy - self.offset_y) / max(self.scale, 1e-6)
        return ix, iy

    def image_to_canvas(self, ix: float, iy: float) -> tuple[float, float]:
        return ix * self.scale + self.offset_x, iy * self.scale + self.offset_y

    def redraw(self, event=None) -> None:
        if not hasattr(self, "pil"):
            return
        self.canvas.delete("all")
        cw = self.canvas.winfo_width()
        ch = self.canvas.winfo_height()
        # Before first layout tk reports 1x1 — use window fallback
        if cw < 50 or ch < 50:
            cw = max(cw, 1000)
            ch = max(ch, 650)
        self.scale = min(cw / self.img_w, ch / self.img_h)
        disp_w = max(1, int(self.img_w * self.scale))
        disp_h = max(1, int(self.img_h * self.scale))
        self.offset_x = (cw - disp_w) // 2
        self.offset_y = (ch - disp_h) // 2
        disp = self.pil.resize((disp_w, disp_h), Image.Resampling.BILINEAR)
        self.tk_img = ImageTk.PhotoImage(disp)
        self.canvas.create_image(self.offset_x, self.offset_y, anchor="nw", image=self.tk_img)
        for x1, y1, x2, y2 in self.boxes:
            c1 = self.image_to_canvas(x1, y1)
            c2 = self.image_to_canvas(x2, y2)
            self.canvas.create_rectangle(*c1, *c2, outline="#e11d48", width=2)
            self.canvas.create_text(
                c1[0] + 4,
                c1[1] + 4,
                text=self.class_name,
                anchor="nw",
                fill="#e11d48",
                font=("Segoe UI", 10, "bold"),
            )

    def on_resize(self, _event=None) -> None:
        if hasattr(self, "pil"):
            self.redraw()

    def on_press(self, event) -> None:
        self.drag_start = (event.x, event.y)
        if self.temp_rect is not None:
            self.canvas.delete(self.temp_rect)
            self.temp_rect = None

    def on_drag(self, event) -> None:
        if self.drag_start is None:
            return
        x0, y0 = self.drag_start
        if self.temp_rect is not None:
            self.canvas.delete(self.temp_rect)
        self.temp_rect = self.canvas.create_rectangle(
            x0, y0, event.x, event.y, outline="#fbbf24", width=2, dash=(4, 2)
        )

    def on_release(self, event) -> None:
        if self.drag_start is None:
            return
        x0, y0 = self.drag_start
        self.drag_start = None
        if self.temp_rect is not None:
            self.canvas.delete(self.temp_rect)
            self.temp_rect = None
        if abs(event.x - x0) < 4 or abs(event.y - y0) < 4:
            return
        ix1, iy1 = self.canvas_to_image(x0, y0)
        ix2, iy2 = self.canvas_to_image(event.x, event.y)
        x1, x2 = sorted((ix1, ix2))
        y1, y2 = sorted((iy1, iy2))
        x1 = max(0, min(self.img_w, x1))
        x2 = max(0, min(self.img_w, x2))
        y1 = max(0, min(self.img_h, y1))
        y2 = max(0, min(self.img_h, y2))
        if x2 - x1 < 2 or y2 - y1 < 2:
            return
        self.boxes.append((x1, y1, x2, y2))
        self.save_labels()
        self.redraw()
        self.update_info()

    def undo_box(self) -> None:
        if self.boxes:
            self.boxes.pop()
            self.save_labels()
            self.redraw()
            self.update_info()

    def clear_boxes(self) -> None:
        self.boxes = []
        self.save_labels()
        self.redraw()
        self.update_info()

    def next_image(self) -> None:
        self.save_labels()
        if self.idx >= len(self.images) - 1:
            messagebox.showinfo("完成", "已经是最后一张。")
            return
        self.load_image(self.idx + 1)

    def prev_image(self) -> None:
        self.save_labels()
        if self.idx <= 0:
            return
        self.load_image(self.idx - 1)


def main() -> None:
    args = parse_args()
    dataset = Path(args.dataset)
    img_dir = dataset / "images" / "train"
    if not img_dir.is_dir():
        raise SystemExit(f"Missing: {img_dir}")
    # Ensure pillow
    try:
        import PIL  # noqa: F401
    except ImportError as ex:
        raise SystemExit("Need Pillow: pip install pillow") from ex
    SimpleYoloLabeler(dataset, args.class_id, args.class_name)


if __name__ == "__main__":
    main()
