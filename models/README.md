# AI models (design doc §3.3)

| File | Role |
|------|------|
| `anomaly.onnx` | Road debris detection (YOLOv8, class=`debris`) via ONNX Runtime Java |
| `anomaly.pt` | Same weights (PyTorch), for reference / re-export |
| `plate_det.onnx` | PP-OCRv4 text/plate detection (inside vehicle crop) |
| `ppocr.onnx` | PP-OCRv4 Chinese recognition |
| `ppocr_cls.onnx` | text angle classifier |

**Anomaly pipeline (Java):** RTSP frame → letterbox 640 → `anomaly.onnx` → NMS → WebSocket.  
Activate by switching frontend model to「道路异常检测」.

**Plate pipeline:** vehicle YOLO → crop → plate_det + ppocr → plate number.
