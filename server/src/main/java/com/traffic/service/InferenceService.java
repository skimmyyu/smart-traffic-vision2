package com.traffic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.dto.InferencePushRequest;
import com.traffic.entity.PlateRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InferenceService {

    private final RealtimePushService realtimePushService;
    private final CongestionService congestionService;
    private final PlateRecordService plateRecordService;
    private final ModelService modelService;
    private final ParkingViolationService parkingViolationService;
    private final RoadCongestionService roadCongestionService;
    private final ObjectMapper objectMapper;

    public InferenceService(RealtimePushService realtimePushService,
                            CongestionService congestionService,
                            PlateRecordService plateRecordService,
                            ModelService modelService,
                            ParkingViolationService parkingViolationService,
                            RoadCongestionService roadCongestionService,
                            ObjectMapper objectMapper) {
        this.realtimePushService = realtimePushService;
        this.congestionService = congestionService;
        this.plateRecordService = plateRecordService;
        this.modelService = modelService;
        this.parkingViolationService = parkingViolationService;
        this.roadCongestionService = roadCongestionService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> pushDetection(InferencePushRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String pushKind = request.getPushKind();
        if ("plate_result".equalsIgnoreCase(pushKind)) {
            return pushPlateResult(request);
        }
        return pushVehicleFrame(request);
    }

    /** OCR 完成后仅入库 + plate_event，不广播叠加框。 */
    private Map<String, Object> pushPlateResult(InferencePushRequest request) {
        List<Map<String, Object>> plates = request.getPlates() == null
                ? Collections.emptyList()
                : request.getPlates();
        List<Map<String, Object>> savedPlates = savePlatesToDb(plates);
        String timestamp = LocalDateTime.now().toString();

        if (!savedPlates.isEmpty()) {
            Map<String, Object> data = new HashMap<>();
            data.put("plates", savedPlates);
            data.put("cameraId", request.getCameraId());
            data.put("cameraName", request.getCameraName());
            data.put("capturedAt", request.getCapturedAt());
            data.put("mode", modelService.getActiveModelId());
            if (!savedPlates.isEmpty()) {
                Map<String, Object> first = savedPlates.get(0);
                data.put("plateNumber", first.get("plateNumber"));
                data.put("decision", first.get("decision"));
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "plate_event");
            payload.put("timestamp", timestamp);
            payload.put("data", data);
            realtimePushService.broadcast(toJson(payload));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pushed", !savedPlates.isEmpty());
        result.put("vehicleCount", 0);
        result.put("plateCount", savedPlates.size());
        result.put("timestamp", timestamp);
        return result;
    }

    /** 每帧车辆框先推；车牌框走 plateOverlays，不入 detections。 */
    private Map<String, Object> pushVehicleFrame(InferencePushRequest request) {
        List<Map<String, Object>> detections = request.getDetections() == null
                ? Collections.emptyList()
                : new ArrayList<>(request.getDetections());
        detections.removeIf(d -> d != null
                && "plate".equalsIgnoreCase(String.valueOf(d.get("className"))));

        List<Map<String, Object>> plateOverlays = request.getPlateOverlays() == null
                ? Collections.emptyList()
                : request.getPlateOverlays();

        String activeModel = modelService.getActiveModelId();
        if ("parking".equalsIgnoreCase(activeModel)) {
            detections = parkingViolationService.enrichAndEvaluate(
                    detections,
                    request.getImageWidth(),
                    request.getImageHeight()
            );
        } else if ("congestion".equalsIgnoreCase(activeModel)) {
            detections = roadCongestionService.evaluate(
                    detections,
                    request.getImageWidth(),
                    request.getImageHeight()
            );
        }

        int vehicleCount = request.getVehicleCount() != null
                ? request.getVehicleCount()
                : detections.size();
        String source = StringUtils.hasText(request.getSource()) ? request.getSource() : "yolov8n";
        String summary = StringUtils.hasText(request.getSummary())
                ? request.getSummary()
                : ("检测到 " + vehicleCount + " 个目标");
        if ("parking".equalsIgnoreCase(activeModel)) {
            long inZone = detections.stream()
                    .filter(d -> Boolean.TRUE.equals(d.get("inZone")))
                    .count();
            summary = summary + " | 禁停区内×" + inZone;
        } else if ("congestion".equalsIgnoreCase(activeModel)) {
            long inRoi = detections.stream()
                    .filter(d -> Boolean.TRUE.equals(d.get("inRoadRoi")))
                    .count();
            summary = summary + " | 路段ROI内×" + inRoi;
        }
        String timestamp = LocalDateTime.now().toString();

        Map<String, Object> data = new HashMap<>();
        data.put("source", source);
        data.put("vehicleCount", vehicleCount);
        data.put("summary", summary);
        data.put("detections", detections);
        data.put("plateOverlays", plateOverlays);
        data.put("plates", Collections.emptyList());
        data.put("mode", activeModel);
        data.put("cameraId", request.getCameraId());
        data.put("cameraName", request.getCameraName());
        data.put("capturedAt", request.getCapturedAt());
        if (request.getImageWidth() != null) {
            data.put("imageWidth", request.getImageWidth());
        }
        if (request.getImageHeight() != null) {
            data.put("imageHeight", request.getImageHeight());
        }
        if (request.getBgLearning() != null) {
            data.put("bgLearning", request.getBgLearning());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "detection_result");
        payload.put("timestamp", timestamp);
        payload.put("data", data);
        realtimePushService.broadcast(toJson(payload));

        if (Boolean.TRUE.equals(request.getSaveCongestion()) && vehicleCount > 0) {
            congestionService.save(vehicleCount, "[]", true);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pushed", true);
        result.put("vehicleCount", vehicleCount);
        result.put("plateCount", 0);
        result.put("timestamp", timestamp);
        return result;
    }

    private List<Map<String, Object>> savePlatesToDb(List<Map<String, Object>> plates) {
        List<Map<String, Object>> savedPlates = new ArrayList<>();
        for (Map<String, Object> plate : plates) {
            Object raw = plate.get("plateNumber");
            if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
                continue;
            }
            String plateNumber = String.valueOf(raw).trim();
            PlateRecord record = plateRecordService.savePlate(plateNumber, false);
            Map<String, Object> item = new HashMap<>();
            item.put("plateNumber", record.getPlateNumber());
            item.put("decision", record.getPassResult());
            item.put("confidence", plate.get("confidence"));
            item.put("bbox", plate.get("bbox"));
            item.put("vehicleBbox", plate.get("vehicleBbox"));
            savedPlates.add(item);
        }
        return savedPlates;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化推送消息失败", ex);
        }
    }
}
