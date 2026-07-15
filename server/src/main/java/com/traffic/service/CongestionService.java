package com.traffic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.entity.CongestionLog;
import com.traffic.mapper.CongestionLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CongestionService {

    private final CongestionLogMapper congestionLogMapper;
    private final RealtimePushService realtimePushService;
    private final ObjectMapper objectMapper;

    public CongestionService(CongestionLogMapper congestionLogMapper,
                             RealtimePushService realtimePushService,
                             ObjectMapper objectMapper) {
        this.congestionLogMapper = congestionLogMapper;
        this.realtimePushService = realtimePushService;
        this.objectMapper = objectMapper;
    }

    public List<CongestionLog> listRecent(int limit) {
        return congestionLogMapper.findRecent(Math.min(Math.max(limit, 1), 200));
    }

    public CongestionLog latest() {
        return congestionLogMapper.findLatest();
    }

    /** Parse v2 road_segments snapshot from congestion_logs.heatmap_data; legacy grid returns null. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseRoadSnapshot(CongestionLog log) {
        if (log == null || !StringUtils.hasText(log.getHeatmapData())) {
            return null;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(
                    log.getHeatmapData(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            if ("road_segments".equals(root.get("mode")) || root.containsKey("segments")) {
                return root;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Latest persisted segment-level heatmap (congestion_logs + embedded segment counts). */
    public Map<String, Object> latestRoadSnapshotPayload() {
        CongestionLog log = latest();
        Map<String, Object> payload = parseRoadSnapshot(log);
        if (payload == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("logId", log.getId());
        out.put("statTime", log.getStatTime());
        if (!out.containsKey("vehicleCount") && log.getVehicleCount() != null) {
            out.put("vehicleCount", log.getVehicleCount());
        }
        return out;
    }

    public CongestionLog save(int vehicleCount, String heatmapData, boolean push) {
        CongestionLog log = new CongestionLog();
        log.setVehicleCount(vehicleCount);
        log.setHeatmapData(heatmapData);
        log.setStatTime(LocalDateTime.now());
        congestionLogMapper.insert(log);

        if (push) {
            pushHeatmap(log);
        }
        return log;
    }

    private void pushHeatmap(CongestionLog log) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "heatmap_update");
        payload.put("timestamp", log.getStatTime().toString());
        Map<String, Object> data = new HashMap<>();
        data.put("vehicleCount", log.getVehicleCount());
        data.put("grid", log.getHeatmapData());
        payload.put("data", data);
        realtimePushService.broadcast(toJson(payload));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化推送消息失败", ex);
        }
    }
}
