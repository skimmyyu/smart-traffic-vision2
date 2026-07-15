package com.traffic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.ai.ZoneGeometry;
import com.traffic.entity.CameraRoadRoi;
import com.traffic.mapper.CameraRoadRoiMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class CameraRoadRoiService {

    private final CameraRoadRoiMapper cameraRoadRoiMapper;
    private final RoadSegmentService roadSegmentService;
    private final ObjectMapper objectMapper;

    public CameraRoadRoiService(CameraRoadRoiMapper cameraRoadRoiMapper,
                                RoadSegmentService roadSegmentService,
                                ObjectMapper objectMapper) {
        this.cameraRoadRoiMapper = cameraRoadRoiMapper;
        this.roadSegmentService = roadSegmentService;
        this.objectMapper = objectMapper;
    }

    public List<CameraRoadRoi> listByChannel(String channelId) {
        if (!StringUtils.hasText(channelId)) {
            throw new IllegalArgumentException("channelId 不能为空");
        }
        return cameraRoadRoiMapper.findByChannel(channelId.trim());
    }

    public List<CameraRoadRoi> listEnabledByChannel(String channelId) {
        if (!StringUtils.hasText(channelId)) {
            return List.of();
        }
        return cameraRoadRoiMapper.findEnabledByChannel(channelId.trim());
    }

    public List<CameraRoadRoi> listAll() {
        return cameraRoadRoiMapper.findAll();
    }

    /** Resolve an anomaly point to the user-drawn region in that camera view. */
    public String resolveRegionName(String channelId, double normalizedX, double normalizedY) {
        for (CameraRoadRoi roi : listEnabledByChannel(channelId)) {
            var points = ZoneGeometry.parsePoints(objectMapper, roi.getPoints());
            if (points.size() >= 3 && ZoneGeometry.contains(points, normalizedX, normalizedY)) {
                return StringUtils.hasText(roi.getName()) ? roi.getName() : "已划分区域";
            }
        }
        return null;
    }

    public CameraRoadRoi create(Map<String, Object> body) {
        CameraRoadRoi roi = fromBody(body, true);
        roadSegmentService.get(roi.getSegmentId());
        LocalDateTime now = LocalDateTime.now();
        roi.setCreatedAt(now);
        roi.setUpdatedAt(now);
        cameraRoadRoiMapper.insert(roi);
        return cameraRoadRoiMapper.findById(roi.getId());
    }

    public CameraRoadRoi update(Long id, Map<String, Object> body) {
        CameraRoadRoi existing = cameraRoadRoiMapper.findById(id);
        if (existing == null) {
            throw new IllegalArgumentException("ROI 不存在: " + id);
        }
        if (body.get("channelId") != null) {
            existing.setChannelId(String.valueOf(body.get("channelId")).trim());
        }
        if (body.get("segmentId") != null) {
            Long segId = toLong(body.get("segmentId"));
            roadSegmentService.get(segId);
            existing.setSegmentId(segId);
        }
        if (body.get("name") != null) {
            existing.setName(String.valueOf(body.get("name")).trim());
        }
        if (body.get("points") != null) {
            existing.setPoints(normalizePoints(body.get("points")));
        }
        if (body.get("enabled") != null) {
            existing.setEnabled(toBool(body.get("enabled"), true));
        }
        existing.setUpdatedAt(LocalDateTime.now());
        cameraRoadRoiMapper.update(existing);
        return cameraRoadRoiMapper.findById(id);
    }

    public void delete(Long id) {
        if (cameraRoadRoiMapper.findById(id) == null) {
            throw new IllegalArgumentException("ROI 不存在: " + id);
        }
        cameraRoadRoiMapper.deleteById(id);
    }

    private CameraRoadRoi fromBody(Map<String, Object> body, boolean creating) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        CameraRoadRoi roi = new CameraRoadRoi();
        if (!StringUtils.hasText(String.valueOf(body.getOrDefault("channelId", "")))) {
            throw new IllegalArgumentException("channelId 不能为空");
        }
        roi.setChannelId(String.valueOf(body.get("channelId")).trim());
        Long segId = toLong(body.get("segmentId"));
        if (segId == null) {
            throw new IllegalArgumentException("segmentId 不能为空");
        }
        roi.setSegmentId(segId);
        String name = body.get("name") != null ? String.valueOf(body.get("name")).trim() : "ROI";
        roi.setName(StringUtils.hasText(name) ? name : "ROI");
        if (creating && body.get("points") == null) {
            throw new IllegalArgumentException("points 不能为空");
        }
        if (body.get("points") != null) {
            roi.setPoints(normalizePoints(body.get("points")));
        }
        roi.setEnabled(toBool(body.get("enabled"), true));
        return roi;
    }

    private String normalizePoints(Object raw) {
        String json;
        try {
            if (raw instanceof String s) {
                json = s;
            } else {
                json = objectMapper.writeValueAsString(raw);
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("points 无法序列化", ex);
        }
        var pts = ZoneGeometry.parsePoints(objectMapper, json);
        if (pts.size() < 3) {
            throw new IllegalArgumentException("ROI 至少需要 3 个顶点");
        }
        try {
            double[][] arr = new double[pts.size()][2];
            for (int i = 0; i < pts.size(); i++) {
                arr[i][0] = clamp01(pts.get(i)[0]);
                arr[i][1] = clamp01(pts.get(i)[1]);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception ex) {
            throw new IllegalArgumentException("points 格式无效", ex);
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean toBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
