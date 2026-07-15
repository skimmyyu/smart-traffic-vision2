package com.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stream")
public class StreamProperties {

    private String rtspUrl = "rtsp://127.0.0.1:8554/cam1";
    /** Downscale grabbed frames to this max width (0 = native). Lowers ONNX/bg-diff latency. */
    private int maxFrameWidth = 960;
    private long reconnectDelayMs = 3000;
    private long offlineTimeoutMs = 5000;

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public int getMaxFrameWidth() {
        return maxFrameWidth;
    }

    public void setMaxFrameWidth(int maxFrameWidth) {
        this.maxFrameWidth = maxFrameWidth;
    }

    public long getReconnectDelayMs() {
        return reconnectDelayMs;
    }

    public void setReconnectDelayMs(long reconnectDelayMs) {
        this.reconnectDelayMs = reconnectDelayMs;
    }

    public long getOfflineTimeoutMs() {
        return offlineTimeoutMs;
    }

    public void setOfflineTimeoutMs(long offlineTimeoutMs) {
        this.offlineTimeoutMs = offlineTimeoutMs;
    }
}
