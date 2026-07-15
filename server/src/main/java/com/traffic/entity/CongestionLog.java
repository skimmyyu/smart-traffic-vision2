package com.traffic.entity;

import java.time.LocalDateTime;

public class CongestionLog {

    private Long id;
    private Integer vehicleCount;
    private String heatmapData;
    private LocalDateTime statTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getVehicleCount() {
        return vehicleCount;
    }

    public void setVehicleCount(Integer vehicleCount) {
        this.vehicleCount = vehicleCount;
    }

    public String getHeatmapData() {
        return heatmapData;
    }

    public void setHeatmapData(String heatmapData) {
        this.heatmapData = heatmapData;
    }

    public LocalDateTime getStatTime() {
        return statTime;
    }

    public void setStatTime(LocalDateTime statTime) {
        this.statTime = statTime;
    }
}
