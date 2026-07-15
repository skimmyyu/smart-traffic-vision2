package com.traffic.dto;

public class StreamStatusDto {

    private boolean online;
    private double fps;
    private long lastFrameAgeMs;
    private String rtspUrl;
    private long totalFrames;
    private String lastError = "";

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public double getFps() {
        return fps;
    }

    public void setFps(double fps) {
        this.fps = fps;
    }

    public long getLastFrameAgeMs() {
        return lastFrameAgeMs;
    }

    public void setLastFrameAgeMs(long lastFrameAgeMs) {
        this.lastFrameAgeMs = lastFrameAgeMs;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public long getTotalFrames() {
        return totalFrames;
    }

    public void setTotalFrames(long totalFrames) {
        this.totalFrames = totalFrames;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
