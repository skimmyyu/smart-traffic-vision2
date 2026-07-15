package com.traffic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.dto.PlateRecordCreateRequest;
import com.traffic.entity.PlateRecord;
import com.traffic.entity.Whitelist;
import com.traffic.mapper.PlateRecordMapper;
import com.traffic.mapper.WhitelistMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlateRecordService {

    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(5);

    private final PlateRecordMapper plateRecordMapper;
    private final WhitelistMapper whitelistMapper;
    private final RealtimePushService realtimePushService;
    private final ObjectMapper objectMapper;

    public PlateRecordService(PlateRecordMapper plateRecordMapper,
                              WhitelistMapper whitelistMapper,
                              RealtimePushService realtimePushService,
                              ObjectMapper objectMapper) {
        this.plateRecordMapper = plateRecordMapper;
        this.whitelistMapper = whitelistMapper;
        this.realtimePushService = realtimePushService;
        this.objectMapper = objectMapper;
    }

    public List<PlateRecord> listRecent(int limit) {
        return plateRecordMapper.findRecent(Math.min(Math.max(limit, 1), 200));
    }

    public PlateRecord add(PlateRecordCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getPlateNumber())) {
            throw new IllegalArgumentException("车牌号码不能为空");
        }
        return savePlate(request.getPlateNumber().trim(), true);
    }

    public PlateRecord savePlate(String plateNumber, boolean push) {
        PlateRecord latest = plateRecordMapper.findLatestByPlate(plateNumber);
        if (latest != null && latest.getRecognizedAt() != null) {
            Duration gap = Duration.between(latest.getRecognizedAt(), LocalDateTime.now());
            if (gap.compareTo(DEDUP_WINDOW) < 0) {
                return latest;
            }
        }

        Whitelist whitelist = whitelistMapper.findByPlateNumber(plateNumber);
        String passResult = whitelist != null ? "allow" : "deny";

        PlateRecord record = new PlateRecord();
        record.setPlateNumber(plateNumber);
        record.setPassResult(passResult);
        record.setRecognizedAt(LocalDateTime.now());
        plateRecordMapper.insert(record);

        if (push) {
            pushDetectionResult(record);
        }
        return record;
    }

    private void pushDetectionResult(PlateRecord record) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "detection_result");
        payload.put("timestamp", record.getRecognizedAt().toString());
        Map<String, Object> data = new HashMap<>();
        data.put("plateNumber", record.getPlateNumber());
        data.put("decision", record.getPassResult());
        data.put("bbox", new int[]{120, 80, 280, 160});
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
