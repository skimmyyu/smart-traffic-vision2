package com.traffic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.ai.ByteTrackTracker;
import com.traffic.ai.STrack;
import com.traffic.ai.TrackDetection;
import com.traffic.ai.ZoneGeometry;
import com.traffic.config.ParkingProperties;
import com.traffic.dto.StreamSwitchResultDto;
import com.traffic.entity.ParkingZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parking violation pipeline:
 * YOLO detections → ByteTrack (Java) → zone containment → stationary dwell ≥ 20s → alert.
 */
@Service
public class ParkingViolationService {

    private static final Logger log = LoggerFactory.getLogger(ParkingViolationService.class);
    private static final Set<String> VEHICLE_CLASSES = Set.of(
            "car", "bus", "truck", "motorcycle", "bicycle"
    );

    private final ParkingProperties properties;
    private final ParkingZoneService parkingZoneService;
    private final StreamChannelService streamChannelService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private ByteTrackTracker tracker;
    private String trackerChannelId = "";

    public ParkingViolationService(ParkingProperties properties,
                                   ParkingZoneService parkingZoneService,
                                   StreamChannelService streamChannelService,
                                   AlertService alertService,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.parkingZoneService = parkingZoneService;
        this.streamChannelService = streamChannelService;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.tracker = newTracker();
    }

    /**
     * Enrich detections with trackId / dwell / inZone and emit parking_violation alerts.
     */
    public List<Map<String, Object>> enrichAndEvaluate(List<Map<String, Object>> detections,
                                                       Integer imageWidth,
                                                       Integer imageHeight) {
        if (!properties.isEnabled()) {
            return detections == null ? List.of() : detections;
        }

        StreamSwitchResultDto currentChannel = streamChannelService.getCurrent();
        String channelId = currentChannel.getChannelId();
        String channelName = currentChannel.getChannelName();
        int width = imageWidth != null && imageWidth > 0 ? imageWidth : 1920;
        int height = imageHeight != null && imageHeight > 0 ? imageHeight : 1080;

        List<TrackDetection> inputs = toTrackDetections(detections);
        List<STrack> tracks;
        synchronized (lock) {
            ensureTracker(channelId);
            tracks = tracker.update(inputs);
            evaluateDwell(tracks, channelId, channelName, width, height);
        }

        return toEnrichedDetections(tracks, detections);
    }

    public void resetTracker() {
        synchronized (lock) {
            tracker.reset();
            trackerChannelId = "";
        }
    }

    private ByteTrackTracker newTracker() {
        return new ByteTrackTracker(
                properties.getTrackHighThresh(),
                properties.getTrackLowThresh(),
                properties.getTrackMatchThresh(),
                properties.getTrackBuffer()
        );
    }

    private void ensureTracker(String channelId) {
        if (!channelId.equals(trackerChannelId)) {
            tracker = newTracker();
            trackerChannelId = channelId;
            log.info("Parking ByteTrack reset for channel {}", channelId);
        }
    }

    private List<TrackDetection> toTrackDetections(List<Map<String, Object>> detections) {
        List<TrackDetection> out = new ArrayList<>();
        if (detections == null) {
            return out;
        }
        for (Map<String, Object> det : detections) {
            if (det == null) {
                continue;
            }
            String className = String.valueOf(det.getOrDefault("className", "car"));
            if ("plate".equalsIgnoreCase(className) || "debris".equalsIgnoreCase(className)) {
                continue;
            }
            if (!VEHICLE_CLASSES.contains(className.toLowerCase()) && !"obj".equalsIgnoreCase(className)) {
                // still accept unknown vehicle-like boxes from custom models
                if (!className.toLowerCase().contains("car")
                        && !className.toLowerCase().contains("vehicle")) {
                    continue;
                }
            }
            Object bboxObj = det.get("bbox");
            if (!(bboxObj instanceof List<?> bbox) || bbox.size() < 4) {
                continue;
            }
            float x1 = toFloat(bbox.get(0));
            float y1 = toFloat(bbox.get(1));
            float x2 = toFloat(bbox.get(2));
            float y2 = toFloat(bbox.get(3));
            float score = toFloat(det.getOrDefault("confidence", 0.5));
            out.add(new TrackDetection(x1, y1, x2, y2, score, className));
        }
        return out;
    }

    private void evaluateDwell(List<STrack> tracks, String channelId, String channelName,
                               int width, int height) {
        List<ParkingZone> zones;
        try {
            zones = parkingZoneService.listEnabledByChannel(channelId);
        } catch (Exception ex) {
            log.warn("Load parking zones failed (run server/sql/parking_zones.sql?): {}", ex.getMessage());
            zones = List.of();
        }
        List<ZonePoly> polys = new ArrayList<>();
        for (ParkingZone z : zones) {
            List<double[]> pts = ZoneGeometry.parsePoints(objectMapper, z.getPoints());
            if (pts.size() >= 3) {
                polys.add(new ZonePoly(z.getName(), pts));
            }
        }

        long now = System.currentTimeMillis();
        long dwellMs = properties.getDwellThresholdMs();
        double stillPx = properties.getStillThresholdPx();

        for (STrack track : tracks) {
            float cx = track.cx();
            float cy = track.cy();
            // normalized for zone test
            double nx = cx / (double) width;
            double ny = cy / (double) height;

            ZonePoly hit = null;
            for (ZonePoly poly : polys) {
                if (ZoneGeometry.contains(poly.points, nx, ny)) {
                    hit = poly;
                    break;
                }
            }

            if (hit == null) {
                track.setInZone(false);
                track.setZoneName(null);
                track.setStillSinceMs(0);
                continue;
            }

            track.setInZone(true);
            track.setZoneName(hit.name);

            double dist = ZoneGeometry.distance(cx, cy, track.getStillAnchorX(), track.getStillAnchorY());
            if (track.getStillSinceMs() <= 0 || dist > stillPx) {
                track.setStillSinceMs(now);
                track.setStillAnchor(cx, cy);
                continue;
            }

            long stayed = now - track.getStillSinceMs();
            if (stayed < dwellMs) {
                continue;
            }
            if (properties.getAlertCooldownMs() > 0
                    && now - track.getLastAlertMs() < properties.getAlertCooldownMs()) {
                continue;
            }

            track.setLastAlertMs(now);
            emitAlert(track, stayed, channelId, channelName, width, height);
        }
    }

    private void emitAlert(STrack track, long stayedMs, String cameraId, String cameraName,
                           int width, int height) {
        TrackDetection det = track.getDetection();
        int secs = (int) Math.max(1, stayedMs / 1000);
        String zone = StringUtils.hasText(track.getZoneName()) ? track.getZoneName() : "禁停区";
        String desc = String.format(
                "%s车辆停留超过 %d 秒，摄像头：%s（%s） (track#%d, %s)",
                zone, secs, cameraName, cameraId, track.getTrackId(), track.getClassName()
        );
        String location = String.format(
                "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"trackId\":%d,\"cameraId\":\"%s\",\"cameraName\":\"%s\",\"region\":\"%s\",\"imageWidth\":%d,\"imageHeight\":%d}",
                (int) det.x1,
                (int) det.y1,
                (int) Math.max(1, det.x2 - det.x1),
                (int) Math.max(1, det.y2 - det.y1),
                track.getTrackId(),
                escapeJson(cameraId),
                escapeJson(cameraName),
                escapeJson(zone),
                width,
                height
        );
        try {
            alertService.saveAlert("parking_violation", desc, location, true);
            log.info("Parking violation: {}", desc);
        } catch (Exception ex) {
            log.warn("Failed to save parking alert: {}", ex.toString());
        }
    }

    private List<Map<String, Object>> toEnrichedDetections(List<STrack> tracks,
                                                           List<Map<String, Object>> original) {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (STrack track : tracks) {
            TrackDetection det = track.getDetection();
            Map<String, Object> item = new HashMap<>();
            item.put("className", track.getClassName());
            item.put("confidence", Math.round(track.getScore() * 1000.0) / 1000.0);
            item.put("bbox", List.of(
                    (int) det.x1, (int) det.y1, (int) det.x2, (int) det.y2
            ));
            item.put("trackId", track.getTrackId());
            item.put("inZone", track.isInZone());
            if (track.getZoneName() != null) {
                item.put("zoneName", track.getZoneName());
            }
            long dwell = 0;
            if (track.isInZone() && track.getStillSinceMs() > 0) {
                dwell = Math.max(0, now - track.getStillSinceMs());
            }
            item.put("dwellMs", dwell);
            item.put("stationary", track.isInZone() && dwell > 0);
            out.add(item);
        }

        // Keep non-vehicle overlays (e.g. plate) from original push
        if (original != null) {
            for (Map<String, Object> det : original) {
                if (det == null) {
                    continue;
                }
                String className = String.valueOf(det.getOrDefault("className", ""));
                if ("plate".equalsIgnoreCase(className)) {
                    out.add(new HashMap<>(det));
                }
            }
        }
        return out;
    }

    private static float toFloat(Object v) {
        if (v instanceof Number n) {
            return n.floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(v));
        } catch (Exception ex) {
            return 0f;
        }
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ZonePoly(String name, List<double[]> points) {
    }
}
