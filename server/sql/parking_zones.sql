-- 禁停区表（可单独执行）
-- mysql -u root -p traffic_db < server/sql/parking_zones.sql

USE traffic_db;

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
