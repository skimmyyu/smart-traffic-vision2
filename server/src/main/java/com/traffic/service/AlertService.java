package com.traffic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.dto.AlertCreateRequest;
import com.traffic.entity.Alert;
import com.traffic.mapper.AlertMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertService {

    private final AlertMapper alertMapper;
    private final RealtimePushService realtimePushService;
    private final ObjectMapper objectMapper;

    public AlertService(AlertMapper alertMapper,
                        RealtimePushService realtimePushService,
                        ObjectMapper objectMapper) {
        this.alertMapper = alertMapper;
        this.realtimePushService = realtimePushService;
        this.objectMapper = objectMapper;
    }

    public List<Alert> listRecent(int limit) {
        return alertMapper.findRecent(Math.min(Math.max(limit, 1), 200));
    }

    public Alert add(AlertCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getAlertType())) {
            throw new IllegalArgumentException("告警类型不能为空");
        }
        return saveAlert(
                request.getAlertType().trim(),
                request.getDescription(),
                request.getLocation(),
                true
        );
    }

    public Alert saveAlert(String alertType, String description, String location, boolean push) {
        Alert alert = new Alert();
        alert.setAlertType(alertType);
        alert.setDescription(StringUtils.hasText(description) ? description.trim() : null);
        alert.setLocation(StringUtils.hasText(location) ? location.trim() : null);
        alert.setOccurredAt(LocalDateTime.now());
        alertMapper.insert(alert);

        if (push) {
            pushAlert(alert);
        }
        return alert;
    }

    private void pushAlert(Alert alert) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "alert");
        payload.put("timestamp", alert.getOccurredAt().toString());
        Map<String, Object> data = new HashMap<>();
        data.put("alertType", alert.getAlertType());
        data.put("message", alert.getDescription());
        data.put("location", alert.getLocation());
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
