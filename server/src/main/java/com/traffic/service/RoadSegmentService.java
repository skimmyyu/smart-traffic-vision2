package com.traffic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.ai.ZoneGeometry;
import com.traffic.entity.RoadSegment;
import com.traffic.mapper.RoadSegmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class RoadSegmentService {

    private final RoadSegmentMapper roadSegmentMapper;
    private final ObjectMapper objectMapper;

    public RoadSegmentService(RoadSegmentMapper roadSegmentMapper, ObjectMapper objectMapper) {
        this.roadSegmentMapper = roadSegmentMapper;
        this.objectMapper = objectMapper;
    }

    public List<RoadSegment> listAll() {
        return roadSegmentMapper.findAll();
    }

    public List<RoadSegment> listEnabled() {
        return roadSegmentMapper.findEnabled();
    }

    public RoadSegment get(Long id) {
        RoadSegment seg = roadSegmentMapper.findById(id);
        if (seg == null) {
            throw new IllegalArgumentException("路段不存在: " + id);
        }
        return seg;
    }

    public RoadSegment create(Map<String, Object> body) {
        RoadSegment seg = fromBody(body, true);
        LocalDateTime now = LocalDateTime.now();
        seg.setCreatedAt(now);
        seg.setUpdatedAt(now);
        roadSegmentMapper.insert(seg);
        return roadSegmentMapper.findById(seg.getId());
    }

    public RoadSegment update(Long id, Map<String, Object> body) {
        RoadSegment existing = get(id);
        if (body.get("name") != null) {
            existing.setName(String.valueOf(body.get("name")).trim());
        }
        if (body.get("capacity") != null) {
            existing.setCapacity(toInt(body.get("capacity"), existing.getCapacity()));
        }
        if (body.get("mapPoints") != null || body.get("points") != null) {
            Object raw = body.get("mapPoints") != null ? body.get("mapPoints") : body.get("points");
            existing.setMapPoints(normalizePoints(raw));
        }
        if (body.get("enabled") != null) {
            existing.setEnabled(toBool(body.get("enabled"), true));
        }
        existing.setUpdatedAt(LocalDateTime.now());
        roadSegmentMapper.update(existing);
        return roadSegmentMapper.findById(id);
    }

    public void delete(Long id) {
        get(id);
        roadSegmentMapper.deleteById(id);
    }

    private RoadSegment fromBody(Map<String, Object> body, boolean creating) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        RoadSegment seg = new RoadSegment();
        String name = body.get("name") != null ? String.valueOf(body.get("name")).trim() : "";
        if (!StringUtils.hasText(name)) {
            name = creating ? "路段" : "";
        }
        seg.setName(name);
        seg.setCapacity(toInt(body.get("capacity"), 4));
        Object raw = body.get("mapPoints") != null ? body.get("mapPoints") : body.get("points");
        if (creating && raw == null) {
            throw new IllegalArgumentException("mapPoints 不能为空");
        }
        if (raw != null) {
            seg.setMapPoints(normalizePoints(raw));
        }
        seg.setEnabled(toBool(body.get("enabled"), true));
        return seg;
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
            throw new IllegalArgumentException("mapPoints 无法序列化", ex);
        }
        var pts = ZoneGeometry.parsePoints(objectMapper, json);
        if (pts.size() < 3) {
            throw new IllegalArgumentException("路段至少需要 3 个顶点");
        }
        try {
            double[][] arr = new double[pts.size()][2];
            for (int i = 0; i < pts.size(); i++) {
                arr[i][0] = clamp01(pts.get(i)[0]);
                arr[i][1] = clamp01(pts.get(i)[1]);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception ex) {
            throw new IllegalArgumentException("mapPoints 格式无效", ex);
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return Math.max(1, n.intValue());
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(v)));
        } catch (Exception ex) {
            return def;
        }
    }

    private static boolean toBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
