-- 修正 devices 表中的沙盘摄像头数据（不修改表结构）
-- 用法：mysql -u root -p traffic_db < server/sql/fix_sandbox_device.sql

USE traffic_db;

UPDATE devices
SET ip = '10.126.59.120',
    stream_url = 'rtsp://10.126.59.120:8554/live/live12',
    status = 'online',
    last_online = NOW()
WHERE name = '704沙盘摄像头';

UPDATE devices
SET ip = '10.126.59.120',
    stream_url = 'rtsp://10.126.59.120:8554/live/live12'
WHERE ip LIKE '100.%'
   OR stream_url LIKE '%admin:admin%'
   OR stream_url LIKE '%100.%';

INSERT INTO devices (name, ip, stream_url, status, last_online)
SELECT '704沙盘摄像头', '10.126.59.120', 'rtsp://10.126.59.120:8554/live/live12', 'online', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM devices WHERE name = '704沙盘摄像头');
