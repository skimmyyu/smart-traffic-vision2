package com.traffic.service;

import com.traffic.dto.StatisticsOverviewDto;
import com.traffic.entity.Device;
import com.traffic.mapper.AlertMapper;
import com.traffic.mapper.CongestionLogMapper;
import com.traffic.mapper.DeviceMapper;
import com.traffic.mapper.PlateRecordMapper;
import com.traffic.mapper.WhitelistMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StatisticsService {

    private final PlateRecordMapper plateRecordMapper;
    private final AlertMapper alertMapper;
    private final CongestionLogMapper congestionLogMapper;
    private final WhitelistMapper whitelistMapper;
    private final DeviceMapper deviceMapper;

    public StatisticsService(PlateRecordMapper plateRecordMapper,
                             AlertMapper alertMapper,
                             CongestionLogMapper congestionLogMapper,
                             WhitelistMapper whitelistMapper,
                             DeviceMapper deviceMapper) {
        this.plateRecordMapper = plateRecordMapper;
        this.alertMapper = alertMapper;
        this.congestionLogMapper = congestionLogMapper;
        this.whitelistMapper = whitelistMapper;
        this.deviceMapper = deviceMapper;
    }

    public StatisticsOverviewDto overview() {
        StatisticsOverviewDto dto = new StatisticsOverviewDto();
        dto.setTodayPlateCount(plateRecordMapper.countToday());
        dto.setTodayAlertCount(alertMapper.countToday());
        dto.setTodayParkingViolationCount(alertMapper.countTodayByType("parking_violation"));
        dto.setTodayRoadAnomalyCount(alertMapper.countTodayByType("road_anomaly"));
        dto.setAvgVehicleCount(congestionLogMapper.avgVehicleCountToday());
        dto.setWhitelistCount(whitelistMapper.findAll().size());

        List<Device> devices = deviceMapper.findAll();
        dto.setDeviceCount(devices.size());
        dto.setOnlineDeviceCount((int) devices.stream()
                .filter(device -> "online".equalsIgnoreCase(device.getStatus()))
                .count());
        return dto;
    }
}
