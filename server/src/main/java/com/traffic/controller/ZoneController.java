package com.traffic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.common.Result;
import com.traffic.dto.ParkingZoneRequest;
import com.traffic.entity.ParkingZone;
import com.traffic.service.ParkingZoneService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/zones")
public class ZoneController {

    private final ParkingZoneService parkingZoneService;
    private final ObjectMapper objectMapper;

    public ZoneController(ParkingZoneService parkingZoneService, ObjectMapper objectMapper) {
        this.parkingZoneService = parkingZoneService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public Result<List<ParkingZone>> list(@RequestParam String channelId) {
        return Result.ok(parkingZoneService.listByChannel(channelId));
    }

    @PostMapping
    public Result<ParkingZone> create(@RequestBody Map<String, Object> body) {
        ParkingZoneRequest request = toRequest(body);
        return Result.ok(parkingZoneService.create(request));
    }

    @PutMapping("/{id}")
    public Result<ParkingZone> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ParkingZoneRequest request = toRequest(body);
        return Result.ok(parkingZoneService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        parkingZoneService.delete(id);
        return Result.ok(null);
    }

    @SuppressWarnings("unchecked")
    private ParkingZoneRequest toRequest(Map<String, Object> body) {
        ParkingZoneRequest request = new ParkingZoneRequest();
        if (body == null) {
            return request;
        }
        Object channelId = body.get("channelId");
        if (channelId != null) {
            request.setChannelId(String.valueOf(channelId));
        }
        Object name = body.get("name");
        if (name != null) {
            request.setName(String.valueOf(name));
        }
        Object enabled = body.get("enabled");
        if (enabled instanceof Boolean b) {
            request.setEnabled(b);
        } else if (enabled != null) {
            request.setEnabled(Boolean.parseBoolean(String.valueOf(enabled)));
        }
        Object points = body.get("points");
        if (points instanceof String s && StringUtils.hasText(s)) {
            request.setPoints(s);
        } else if (points != null) {
            try {
                request.setPoints(objectMapper.writeValueAsString(points));
            } catch (Exception ex) {
                throw new IllegalArgumentException("points 无法序列化", ex);
            }
        }
        return request;
    }
}
