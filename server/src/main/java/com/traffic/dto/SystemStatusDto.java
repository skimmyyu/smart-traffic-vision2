package com.traffic.dto;

public class SystemStatusDto {

    private double cpuUsage;
    private double memoryUsage;
    private double gpuUsage;
    private boolean streamOnline;
    private double fps;

    public double getCpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public double getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(double memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public double getGpuUsage() {
        return gpuUsage;
    }

    public void setGpuUsage(double gpuUsage) {
        this.gpuUsage = gpuUsage;
    }

    public boolean isStreamOnline() {
        return streamOnline;
    }

    public void setStreamOnline(boolean streamOnline) {
        this.streamOnline = streamOnline;
    }

    public double getFps() {
        return fps;
    }

    public void setFps(double fps) {
        this.fps = fps;
    }
}
