package com.traffic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.ai.ZoneGeometry;
import com.traffic.entity.CameraRoadRoi;
import com.traffic.entity.CongestionLog;
import com.traffic.entity.RoadSegment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Current-channel road congestion: YOLO boxes → camera ROI → segment counts → WS paint.
 */
@Service
public class RoadCongestionService {

    private static final Logger log = LoggerFactory.getLogger(RoadCongestionService.class);
    private static final Set<String> VEHICLE_CLASSES = Set.of(
            "car", "bus", "truck", "motorcycle", "bicycle"
    );

    private static final long PERSIST_INTERVAL_MS = 30_000L;

    private final CameraRoadRoiService cameraRoadRoiService;
    private final RoadSegmentService roadSegmentService;
    private final StreamChannelService streamChannelService;
    private final RealtimePushService realtimePushService;
    private final CongestionService congestionService;
    private final ObjectMapper objectMapper;

    /** Latest counts per segment (survives channel switch for other segments). */
    private final ConcurrentHashMap<Long, Integer> segmentCounts = new ConcurrentHashMap<>();
    private volatile String lastChannelId = "";
    private volatile long lastPersistMs = 0L;
    private volatile String lastPersistSignature = "";

    public RoadCongestionService(CameraRoadRoiService cameraRoadRoiService,
                                 RoadSegmentService roadSegmentService,
                                 StreamChannelService streamChannelService,
                                 RealtimePushService realtimePushService,
                                 CongestionService congestionService,
                                 ObjectMapper objectMapper) {
        this.cameraRoadRoiService = cameraRoadRoiService;
        this.roadSegmentService = roadSegmentService;
        this.streamChannelService = streamChannelService;
        this.realtimePushService = realtimePushService;
        this.congestionService = congestionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void restoreCountsFromDatabase() {
        try {
            Map<String, Object> snap = congestionService.latestRoadSnapshotPayload();
            if (snap != null) {
                applyCountsFromPayload(snap);
                log.info("Restored segment counts from congestion_logs id={}", snap.get("logId"));
            }
        } catch (Exception ex) {
            log.debug("No congestion snapshot to restore: {}", ex.getMessage());
        }
    }

    /**
     * Count vehicles in current-channel ROIs, update segment counts, broadcast, return enriched detections.
     */
    public List<Map<String, Object>> evaluate(List<Map<String, Object>> detections,
                                              Integer imageWidth,
                                              Integer imageHeight) {
        String channelId = streamChannelService.getCurrent().getChannelId();
        int width = imageWidth != null && imageWidth > 0 ? imageWidth : 1920;
        int height = imageHeight != null && imageHeight > 0 ? imageHeight : 1080;

        List<CameraRoadRoi> rois;
        try {
            rois = cameraRoadRoiService.listEnabledByChannel(channelId);
        } catch (Exception ex) {
            log.warn("Load camera ROIs failed: {}", ex.getMessage());
            rois = List.of();
        }

        Map<Long, Integer> channelCounts = new LinkedHashMap<>();
        List<RoiPoly> polys = new ArrayList<>();
        for (CameraRoadRoi roi : rois) {
            List<double[]> pts = ZoneGeometry.parsePoints(objectMapper, roi.getPoints());
            if (pts.size() < 3 || roi.getSegmentId() == null) {
                continue;
            }
            polys.add(new RoiPoly(roi.getId(), roi.getSegmentId(), roi.getName(), pts));
            channelCounts.putIfAbsent(roi.getSegmentId(), 0);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        if (detections != null) {
            for (Map<String, Object> det : detections) {
                if (det == null) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>(det);
                String className = String.valueOf(det.getOrDefault("className", ""));
                if ("plate".equalsIgnoreCase(className) || "debris".equalsIgnoreCase(className)) {
                    out.add(item);
                    continue;
                }
                if (!isVehicle(className)) {
                    out.add(item);
                    continue;
                }
                Object bboxObj = det.get("bbox");
                if (!(bboxObj instanceof List<?> bbox) || bbox.size() < 4) {
                    out.add(item);
                    continue;
                }
                double cx = (toDouble(bbox.get(0)) + toDouble(bbox.get(2))) * 0.5;
                double cy = (toDouble(bbox.get(1)) + toDouble(bbox.get(3))) * 0.5;
                double nx = cx / width;
                double ny = cy / height;

                Long hitSeg = null;
                Long hitRoi = null;
                String hitName = null;
                for (RoiPoly poly : polys) {
                    if (ZoneGeometry.contains(poly.points, nx, ny)) {
                        hitSeg = poly.segmentId;
                        hitRoi = poly.roiId;
                        hitName = poly.name;
                        channelCounts.merge(poly.segmentId, 1, Integer::sum);
                        break;
                    }
                }
                if (hitSeg != null) {
                    item.put("inRoadRoi", true);
                    item.put("segmentId", hitSeg);
                    item.put("roiId", hitRoi);
                    item.put("roiName", hitName);
                } else {
                    item.put("inRoadRoi", false);
                }
                out.add(item);
            }
        }

        // Reset counts for segments belonging to this channel's ROIs, keep others
        if (!channelId.equals(lastChannelId)) {
            lastChannelId = channelId;
        }
        for (Long segId : channelCounts.keySet()) {
            segmentCounts.put(segId, channelCounts.getOrDefault(segId, 0));
        }

        broadcast(channelId);
        return out;
    }

    public Map<String, Object> snapshot() {
        String channelId = streamChannelService.getCurrent().getChannelId();
        return buildPayload(channelId);
    }

    /** Load latest congestion_logs road_segments snapshot and sync in-memory counts. */
    public Map<String, Object> latestSnapshotFromDb() {
        Map<String, Object> snap = congestionService.latestRoadSnapshotPayload();
        if (snap == null) {
            return null;
        }
        applyCountsFromPayload(snap);
        return snap;
    }

    /** Force-write current segment counts into congestion_logs (bypass throttle). */
    public Map<String, Object> forcePersistSnapshot() {
        lastPersistMs = 0L;
        lastPersistSignature = "";
        String channelId = streamChannelService.getCurrent().getChannelId();
        Map<String, Object> data = buildPayload(channelId);
        maybePersist(data);
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("version", 2);
        result.put("mode", "road_segments");
        CongestionLog log = congestionService.latest();
        if (log != null) {
            result.put("persistedLogId", log.getId());
            result.put("persistedAt", log.getStatTime());
        }
        return result;
    }

    /** Expose table linkage for heatmap UI: road_segments ↔ camera_road_rois ↔ congestion_logs. */
    public Map<String, Object> dbContext() {
        List<RoadSegment> segments = roadSegmentService.listAll();
        List<CameraRoadRoi> rois = cameraRoadRoiService.listAll();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("segmentCount", segments.size());
        ctx.put("enabledSegmentCount", segments.stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).count());
        ctx.put("roiCount", rois.size());
        ctx.put("enabledRoiCount", rois.stream().filter(r -> Boolean.TRUE.equals(r.getEnabled())).count());

        List<Map<String, Object>> mappings = new ArrayList<>();
        for (CameraRoadRoi roi : rois) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roiId", roi.getId());
            row.put("channelId", roi.getChannelId());
            row.put("segmentId", roi.getSegmentId());
            row.put("name", roi.getName());
            row.put("enabled", roi.getEnabled());
            segments.stream()
                    .filter(s -> s.getId().equals(roi.getSegmentId()))
                    .findFirst()
                    .ifPresent(seg -> row.put("segmentName", seg.getName()));
            mappings.add(row);
        }
        ctx.put("mappings", mappings);

        Map<String, Object> latest = congestionService.latestRoadSnapshotPayload();
        if (latest != null) {
            Map<String, Object> logInfo = new LinkedHashMap<>();
            logInfo.put("logId", latest.get("logId"));
            logInfo.put("statTime", latest.get("statTime"));
            logInfo.put("vehicleCount", latest.get("vehicleCount"));
            logInfo.put("mode", latest.getOrDefault("mode", "road_segments"));
            ctx.put("latestLog", logInfo);
        }
        ctx.put("persistIntervalSec", PERSIST_INTERVAL_MS / 1000);
        return ctx;
    }

    private void applyCountsFromPayload(Map<String, Object> data) {
        if (data == null) {
            return;
        }
        Object segments = data.get("segments");
        if (!(segments instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Long id = toLong(map.get("id"));
                if (id != null) {
                    segmentCounts.put(id, toInt(map.get("count")));
                }
            }
        }
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ex) {
            return null;
        }
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ex) {
            return 0;
        }
    }

    private void broadcast(String channelId) {
        Map<String, Object> data = buildPayload(channelId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "road_congestion");
        payload.put("timestamp", java.time.LocalDateTime.now().toString());
        payload.put("data", data);
        realtimePushService.broadcast(toJson(payload));
        maybePersist(data);
    }

    /** Write segment snapshot into congestion_logs.heatmap_data (throttled). */
    private void maybePersist(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String signature = persistSignature(data);
        if (signature.equals(lastPersistSignature) && now - lastPersistMs < PERSIST_INTERVAL_MS) {
            return;
        }
        lastPersistMs = now;
        lastPersistSignature = signature;
        try {
            Map<String, Object> heatmap = new LinkedHashMap<>(data);
            heatmap.put("version", 2);
            heatmap.put("mode", "road_segments");
            String json = objectMapper.writeValueAsString(heatmap);
            int total = ((Number) data.getOrDefault("vehicleCount", 0)).intValue();
            congestionService.save(total, json, false);
        } catch (Exception ex) {
            log.warn("Persist road congestion log failed: {}", ex.getMessage());
        }
    }

    private static String persistSignature(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append(data.getOrDefault("channelId", ""));
        sb.append('|').append(data.getOrDefault("vehicleCount", 0));
        Object segments = data.get("segments");
        if (segments instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    sb.append('|').append(map.get("id")).append('=').append(map.get("count"));
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> buildPayload(String channelId) {
        List<RoadSegment> all;
        try {
            all = roadSegmentService.listEnabled();
        } catch (Exception ex) {
            all = List.of();
        }
        List<Map<String, Object>> segments = new ArrayList<>();
        int total = 0;
        for (RoadSegment seg : all) {
            int count = segmentCounts.getOrDefault(seg.getId(), 0);
            int capacity = seg.getCapacity() != null && seg.getCapacity() > 0 ? seg.getCapacity() : 4;
            double level = Math.min(1.0, count / (double) capacity);
            Map<String, Object> item = new HashMap<>();
            item.put("id", seg.getId());
            item.put("name", seg.getName());
            item.put("capacity", capacity);
            item.put("count", count);
            item.put("level", Math.round(level * 1000.0) / 1000.0);
            item.put("mapPoints", seg.getMapPoints());
            segments.add(item);
            total += count;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("channelId", channelId);
        data.put("vehicleCount", total);
        data.put("segments", segments);
        return data;
    }

    private boolean isVehicle(String className) {
        if (className == null) {
            return false;
        }
        String c = className.toLowerCase();
        if (VEHICLE_CLASSES.contains(c)) {
            return true;
        }
        return c.contains("car") || c.contains("vehicle");
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化拥堵推送失败", ex);
        }
    }

    private record RoiPoly(Long roiId, Long segmentId, String name, List<double[]> points) {
    }
}
