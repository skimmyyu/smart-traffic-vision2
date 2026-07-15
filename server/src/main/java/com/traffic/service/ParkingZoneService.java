package com.traffic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.dto.ParkingZoneRequest;
import com.traffic.entity.ParkingZone;
import com.traffic.mapper.ParkingZoneMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ParkingZoneService {

    private final ParkingZoneMapper parkingZoneMapper;
    private final ObjectMapper objectMapper;

    public ParkingZoneService(ParkingZoneMapper parkingZoneMapper, ObjectMapper objectMapper) {
        this.parkingZoneMapper = parkingZoneMapper;
        this.objectMapper = objectMapper;
    }

    public List<ParkingZone> listByChannel(String channelId) {
        if (!StringUtils.hasText(channelId)) {
            throw new IllegalArgumentException("channelId 不能为空");
        }
        return parkingZoneMapper.findByChannel(channelId.trim());
    }

    public List<ParkingZone> listEnabledByChannel(String channelId) {
        if (!StringUtils.hasText(channelId)) {
            return List.of();
        }
        return parkingZoneMapper.findEnabledByChannel(channelId.trim());
    }

    public ParkingZone create(ParkingZoneRequest request) {
        validate(request, true);
        ParkingZone zone = new ParkingZone();
        zone.setChannelId(request.getChannelId().trim());
        zone.setName(StringUtils.hasText(request.getName()) ? request.getName().trim() : "禁停区");
        zone.setPoints(normalizePointsJson(request.getPoints()));
        zone.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));
        LocalDateTime now = LocalDateTime.now();
        zone.setCreatedAt(now);
        zone.setUpdatedAt(now);
        parkingZoneMapper.insert(zone);
        return parkingZoneMapper.findById(zone.getId());
    }

    public ParkingZone update(Long id, ParkingZoneRequest request) {
        ParkingZone existing = parkingZoneMapper.findById(id);
        if (existing == null) {
            throw new IllegalArgumentException("禁停区不存在: " + id);
        }
        if (StringUtils.hasText(request.getName())) {
            existing.setName(request.getName().trim());
        }
        if (StringUtils.hasText(request.getPoints())) {
            existing.setPoints(normalizePointsJson(request.getPoints()));
        }
        if (request.getEnabled() != null) {
            existing.setEnabled(request.getEnabled());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        parkingZoneMapper.update(existing);
        return parkingZoneMapper.findById(id);
    }

    public void delete(Long id) {
        if (parkingZoneMapper.findById(id) == null) {
            throw new IllegalArgumentException("禁停区不存在: " + id);
        }
        parkingZoneMapper.deleteById(id);
    }

    private void validate(ParkingZoneRequest request, boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (creating && !StringUtils.hasText(request.getChannelId())) {
            throw new IllegalArgumentException("channelId 不能为空");
        }
        if (creating && !StringUtils.hasText(request.getPoints())) {
            throw new IllegalArgumentException("points 不能为空");
        }
        if (StringUtils.hasText(request.getPoints())) {
            var pts = com.traffic.ai.ZoneGeometry.parsePoints(objectMapper, request.getPoints());
            if (pts.size() < 3) {
                throw new IllegalArgumentException("禁停区至少需要 3 个顶点");
            }
        }
    }

    private String normalizePointsJson(String points) {
        var pts = com.traffic.ai.ZoneGeometry.parsePoints(objectMapper, points);
        if (pts.size() < 3) {
            throw new IllegalArgumentException("禁停区至少需要 3 个顶点");
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
}
