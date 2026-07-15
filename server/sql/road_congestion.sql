-- 沙盘路网拥堵热力图表
USE traffic_db;

CREATE TABLE IF NOT EXISTS road_segments (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(64)  NOT NULL COMMENT '路段名称',
  capacity     INT          NOT NULL DEFAULT 4 COMMENT '满载参考车数',
  map_points   JSON         NOT NULL COMMENT '俯视图归一化多边形 [[x,y],...]',
  enabled      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙盘俯视路段';

CREATE TABLE IF NOT EXISTS camera_road_rois (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel_id   VARCHAR(32)  NOT NULL COMMENT '监控通道',
  segment_id   BIGINT       NOT NULL COMMENT '绑定路段',
  name         VARCHAR(64)  NOT NULL DEFAULT 'ROI' COMMENT 'ROI名称',
  points       JSON         NOT NULL COMMENT '监控画面归一化多边形',
  enabled      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_camera_rois_channel (channel_id),
  INDEX idx_camera_rois_segment (segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控画面到路段的ROI映射';
