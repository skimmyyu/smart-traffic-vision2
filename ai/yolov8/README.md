# YOLOv8n 训练环境（smart-traffic-vision）

## 目录

```
ai/yolov8/
├── .venv/                 # Python 虚拟环境
├── weights/yolov8n.pt     # 预训练权重
├── datasets/traffic/      # 你的数据集（YOLO 格式）
├── scripts/               # 训练 / 推理 / 导出
└── runs/                  # 训练与预测输出
```

## 一键准备

在 `ai/yolov8` 目录（**必须用 `.venv` 里的 Python**，不要直接用系统 `python`）：

```bat
:: 方式 A：双击 / 调用 bat（推荐）
capture.bat --count 100 --interval 1
train.bat --epochs 50 --batch 8

:: 方式 B：先激活再跑
.venv\Scripts\activate
python scripts\download_weights.py
python scripts\capture_frames.py --count 100 --interval 1
python scripts\train.py --epochs 50 --batch 8
python scripts\export_onnx.py

:: 方式 C：不激活，直接指定解释器
.venv\Scripts\python.exe scripts\capture_frames.py --count 100 --interval 1
```

## 数据集格式

`datasets/traffic/images/train/*.jpg`  
`datasets/traffic/labels/train/*.txt`（YOLO：`class cx cy w h`，归一化 0~1）

类别见 `datasets/traffic/data.yaml`（car/bus/truck/...）。

## 标注工具推荐

- [LabelImg](https://github.com/HumanSignal/labelImg)（选 YOLO 格式）
- Roboflow / CVAT

## 注意

- 本机 GPU：RTX 5070，需 PyTorch **cu128**（已装在 `.venv`）
- 没有标注数据时 `train.py` 会直接报错提示，不会空跑
- 训练产物：`runs/detect/traffic-yolov8n/weights/best.pt`
