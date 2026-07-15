package com.traffic.dto;

public class StatisticsOverviewDto {

    private int todayPlateCount;
    private int todayAlertCount;
    private double avgVehicleCount;
    private int whitelistCount;
    private int deviceCount;
    private int onlineDeviceCount;
    private int todayParkingViolationCount;
    private int todayRoadAnomalyCount;

    public int getTodayPlateCount() {
        return todayPlateCount;
    }

    public void setTodayPlateCount(int todayPlateCount) {
        this.todayPlateCount = todayPlateCount;
    }

    public int getTodayAlertCount() {
        return todayAlertCount;
    }

    public void setTodayAlertCount(int todayAlertCount) {
        this.todayAlertCount = todayAlertCount;
    }

    public double getAvgVehicleCount() {
        return avgVehicleCount;
    }

    public void setAvgVehicleCount(double avgVehicleCount) {
        this.avgVehicleCount = avgVehicleCount;
    }

    public int getWhitelistCount() {
        return whitelistCount;
    }

    public void setWhitelistCount(int whitelistCount) {
        this.whitelistCount = whitelistCount;
    }

    public int getDeviceCount() {
        return deviceCount;
    }

    public void setDeviceCount(int deviceCount) {
        this.deviceCount = deviceCount;
    }

    public int getOnlineDeviceCount() {
        return onlineDeviceCount;
    }

    public void setOnlineDeviceCount(int onlineDeviceCount) {
        this.onlineDeviceCount = onlineDeviceCount;
    }

    public int getTodayParkingViolationCount() { return todayParkingViolationCount; }
    public void setTodayParkingViolationCount(int value) { this.todayParkingViolationCount = value; }
    public int getTodayRoadAnomalyCount() { return todayRoadAnomalyCount; }
    public void setTodayRoadAnomalyCount(int value) { this.todayRoadAnomalyCount = value; }
}
