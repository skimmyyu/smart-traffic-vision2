package com.traffic.service;

import com.traffic.config.AnomalyProperties;
import com.traffic.config.ParkingProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelParameterService {

    private final Map<String, Map<String, Object>> parameters = new ConcurrentHashMap<>();
    private final AnomalyProperties anomaly;
    private final ParkingProperties parking;

    public ModelParameterService(AnomalyProperties anomaly, ParkingProperties parking) {
        this.anomaly = anomaly;
        this.parking = parking;
        parameters.put("yolov8n", values("confidence", .35, "imageSize", 640, "intervalMs", 120));
        parameters.put("plate_det", values("confidence", .35, "ocrConfidence", .45, "imageSize", 640));
        parameters.put("plate_ocr", values("confidence", .35, "ocrConfidence", .45, "imageSize", 640));
        parameters.put("anomaly", values(
                "diffThreshold", anomaly.getBgDiffThreshold(),
                "minAreaPercent", anomaly.getBgMinAreaRatio() * 100.0,
                "minConfidencePercent", anomaly.getMinDisplayConfidence() * 100.0,
                "persistenceFrames", anomaly.getBgPersistHits(),
                "intervalMs", anomaly.getIntervalMs()));
        parameters.put("parking", values("confidence", .35, "dwellSeconds", parking.getDwellThresholdMs() / 1000, "stillThresholdPx", parking.getStillThresholdPx()));
        parameters.put("congestion", values("confidence", .35, "imageSize", 640, "intervalMs", 120));
    }

    public Map<String, Object> get(String modelId) {
        Map<String, Object> value = parameters.get(modelId);
        if (value == null) throw new IllegalArgumentException("未知模型: " + modelId);
        return new LinkedHashMap<>(value);
    }

    public Map<String, Object> update(String modelId, Map<String, Object> incoming) {
        Map<String, Object> current = parameters.get(modelId);
        if (current == null) throw new IllegalArgumentException("未知模型: " + modelId);
        if (incoming == null) return get(modelId);
        for (String key : current.keySet()) {
            if (incoming.containsKey(key) && incoming.get(key) instanceof Number) {
                current.put(key, incoming.get(key));
            }
        }
        if ("anomaly".equals(modelId)) {
            anomaly.setBgDiffThreshold(number(current, "diffThreshold").floatValue());
            float minAreaRatio = number(current, "minAreaPercent").floatValue() / 100f;
            anomaly.setBgMinAreaRatio(Math.max(0.00001f, Math.min(0.12f, minAreaRatio)));
            float minConfidence = number(current, "minConfidencePercent").floatValue() / 100f;
            anomaly.setMinDisplayConfidence(Math.max(0f, Math.min(1f, minConfidence)));
            anomaly.setBgPersistHits(Math.max(1, number(current, "persistenceFrames").intValue()));
            anomaly.setIntervalMs(Math.max(80, number(current, "intervalMs").longValue()));
        } else if ("parking".equals(modelId)) {
            parking.setDwellThresholdMs(Math.max(1, number(current, "dwellSeconds").longValue()) * 1000);
            parking.setStillThresholdPx(Math.max(1, number(current, "stillThresholdPx").doubleValue()));
        }
        return get(modelId);
    }

    private static Number number(Map<String, Object> map, String key) {
        return (Number) map.get(key);
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new ConcurrentHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return map;
    }
}
