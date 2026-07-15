# 云边端协同智慧交通视觉感知系统

704 智慧交通沙盘 · Java 17 + Spring Boot + Vue 3 + MySQL + MediaMTX

## 快速启动

```bat
:: 推荐：项目根目录一键启动（会开 3 个窗口）
start-all.bat

:: 或分步启动
mediamtx\stop-mediamtx.bat
mediamtx\start-mediamtx.bat
server\stop-server.bat
server\start-server.bat
web\start-web.bat
```

浏览器打开：http://localhost:5173/monitor

前端页面：监控中心、场景监控（闸机/禁停/道路/热力图）、通行记录、告警、统计、概览、设置

## 端口

| 服务 | 端口 |
|------|------|
| Vue 前端 | 5173 |
| Spring Boot API + WebSocket | 8080 |
| MediaMTX RTSP | 8554 |
| MediaMTX HLS | 8888 |
| MySQL | 3306 |

## 主要 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/system/status | 系统资源 |
| GET | /api/system/stream | 拉流状态 |
| GET | /api/stream/channels | 12 路监控通道 |
| POST | /api/stream/switch/{id} | 切换沙盘摄像头 |
| GET | /api/plate-records | 车牌识别记录 |
| GET | /api/alerts | 告警列表 |
| GET | /api/congestion/logs | 拥堵历史 |
| GET | /api/statistics/overview | 统计概览 |
| GET | /api/whitelist | 白名单 |
| GET | /api/devices | 设备列表 |
| POST | /api/devices/{id}/heartbeat | 设备心跳 |
| GET | /api/export/plate-records | 导出通行记录 CSV |
| GET | /api/export/alerts | 导出告警 CSV |
| GET | /api/export/congestion | 导出拥堵 CSV |

WebSocket：`ws://127.0.0.1:8080/ws/live`

## 视频源（704 沙盘摄像头）

采集端为学院 **704 智慧交通沙盘固定摄像头**（非手机 Tailscale 方案）：

| 项目 | 值 |
|------|-----|
| RTSP 服务器 | `10.126.59.120:8554` |
| 地址格式 | `rtsp://10.126.59.120:8554/live/liveN`（N=1～12） |
| 认证 | 无账号密码 |
| 本地转发 | MediaMTX 拉流到 `rtsp://127.0.0.1:8554/cam1` |
| HLS 播放 | `http://127.0.0.1:8888/cam1/index.m3u8` |

通道示例：live6 桥入口、live12 道路1、live8 隧道事故识别。完整列表见 `mediamtx/SANDBOX摄像头.txt`。

## 说明

- AI：`mock.inference.enabled=false` 时需运行 `ai/yolov8/live-bridge.bat`；为 `true` 时用演示数据
- MySQL：本机 `root` / `123456`，库名 `traffic_db`；首次可运行 `init-database.bat`
- 前端本机地址：`http://127.0.0.1:8080` / `http://localhost:5173/monitor`

## 目录

```
smart-traffic-vision/
├── mediamtx/     # 流媒体服务
├── server/       # Java 后端
├── web/          # Vue 前端
└── docs/images/  # 架构图
```
