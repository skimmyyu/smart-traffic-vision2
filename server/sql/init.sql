-- 智慧交通视觉感知系统 — MySQL 初始化脚本
-- 用法：mysql -u root -p < server/sql/init.sql
-- 说明：仅建库建表 + 插入/修正演示数据，不修改表结构

CREATE DATABASE IF NOT EXISTS traffic_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE traffic_db;

-- 边缘设备
CREATE TABLE IF NOT EXISTS devices (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '设备id',
  name         VARCHAR(64)  NOT NULL COMMENT '设备名称',
  ip           VARCHAR(64)  NULL COMMENT 'IP地址',
  stream_url   VARCHAR(256) NULL COMMENT '视频流地址',
  status       VARCHAR(16)  NOT NULL DEFAULT 'offline' COMMENT '设备状态 online/offline',
  last_online  DATETIME     NULL COMMENT '最后在线时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='边缘设备';

-- 白名单
CREATE TABLE IF NOT EXISTS whitelist (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '编号',
  plate_number  VARCHAR(16) NOT NULL UNIQUE COMMENT '车牌号码'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='白名单';

-- 车牌识别记录
CREATE TABLE IF NOT EXISTS plate_records (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '车牌识别记录id',
  plate_number  VARCHAR(16) NOT NULL COMMENT '车牌号码',
  pass_result   VARCHAR(16) NOT NULL COMMENT '通行结果 allow/deny',
  recognized_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '识别时间',
  INDEX idx_plate_recognized (plate_number, recognized_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车牌识别记录';

-- 警告
CREATE TABLE IF NOT EXISTS alerts (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '警告id',
  alert_type  VARCHAR(32) NOT NULL COMMENT '告警类型',
  description TEXT        NULL COMMENT '告警描述',
  location    JSON        NULL COMMENT '告警位置',
  occurred_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='警告';

-- 拥堵统计
CREATE TABLE IF NOT EXISTS congestion_logs (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '拥堵统计id',
  vehicle_count  INT  NOT NULL DEFAULT 0 COMMENT '车辆数量',
  heatmap_data   JSON NULL COMMENT '热力图数据',
  stat_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '统计时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拥堵统计';

-- 禁停区（每路监控可画多个多边形，坐标为相对画面 0~1）
CREATE TABLE IF NOT EXISTS parking_zones (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '禁停区id',
  channel_id   VARCHAR(32)  NOT NULL COMMENT '监控通道 live1~live12',
  name         VARCHAR(64)  NOT NULL DEFAULT '禁停区' COMMENT '区域名称',
  points       JSON         NOT NULL COMMENT '归一化多边形 [[x,y],...] 取值0~1',
  enabled      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_parking_zones_channel (channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='禁停区多边形';

-- 沙盘俯视路段（拥堵热力）
CREATE TABLE IF NOT EXISTS road_segments (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(64)  NOT NULL COMMENT '路段名称',
  capacity     INT          NOT NULL DEFAULT 4 COMMENT '满载参考车数',
  map_points   JSON         NOT NULL COMMENT '俯视图归一化多边形',
  enabled      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙盘俯视路段';

-- 监控画面 ROI → 路段映射
CREATE TABLE IF NOT EXISTS camera_road_rois (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel_id   VARCHAR(32)  NOT NULL,
  segment_id   BIGINT       NOT NULL,
  name         VARCHAR(64)  NOT NULL DEFAULT 'ROI',
  points       JSON         NOT NULL,
  enabled      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_camera_rois_channel (channel_id),
  INDEX idx_camera_rois_segment (segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控画面到路段的ROI映射';

-- 演示用白名单
INSERT INTO whitelist (plate_number) VALUES
  ('京A12345'),
  ('京C88888')
ON DUPLICATE KEY UPDATE plate_number = VALUES(plate_number);

-- 704 沙盘固定摄像头（无账号密码，默认 live12 道路1）
INSERT INTO devices (name, ip, stream_url, status, last_online)
SELECT '704沙盘摄像头', '10.126.59.120', 'rtsp://10.126.59.120:8554/live/live12', 'online', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM devices WHERE name = '704沙盘摄像头');

UPDATE devices
SET ip = '10.126.59.120',
    stream_url = 'rtsp://10.126.59.120:8554/live/live12',
    status = 'online',
    last_online = NOW()
WHERE name = '704沙盘摄像头';

-- 修正旧的 Tailscale / 手机 IP 摄像头示例数据（仅改数据）
UPDATE devices
SET ip = '10.126.59.120',
    stream_url = 'rtsp://10.126.59.120:8554/live/live12'
WHERE ip LIKE '100.%'
   OR stream_url LIKE '%admin:admin%'
   OR stream_url LIKE '%100.%';

-- 演示用识别/告警/拥堵数据
INSERT INTO plate_records (plate_number, pass_result, recognized_at)
SELECT '京A12345', 'allow', NOW() - INTERVAL 10 MINUTE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM plate_records LIMIT 1);

INSERT INTO alerts (alert_type, description, location, occurred_at)
SELECT 'parking_violation', '禁停区车辆停留超过 20 秒', '{"x":320,"y":240}', NOW() - INTERVAL 5 MINUTE FROM DUAL
WHERE (SELECT COUNT(*) FROM alerts) = 0;

INSERT INTO congestion_logs (vehicle_count, heatmap_data, stat_time)
SELECT 5, '[[0,1,2],[1,3,2],[0,1,1]]', NOW() - INTERVAL 2 MINUTE FROM DUAL
WHERE (SELECT COUNT(*) FROM congestion_logs) = 0;
