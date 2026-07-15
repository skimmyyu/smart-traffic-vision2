package com.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parking")
public class ParkingProperties {

    /** Master switch for parking violation evaluation */
    private boolean enabled = true;
    /** Vehicle must stay still this long inside a zone before alert (ms) */
    private long dwellThresholdMs = 20_000;
    /** Max centroid drift (pixels) to still count as "stationary" */
    private double stillThresholdPx = 18.0;
    /** Per-track alert cooldown after a parking_violation (ms) */
    private long alertCooldownMs = 60_000;
    /** ByteTrack: high-score detection threshold */
    private float trackHighThresh = 0.5f;
    /** ByteTrack: low-score detection threshold */
    private float trackLowThresh = 0.1f;
    /** ByteTrack: match IoU threshold */
    private float trackMatchThresh = 0.3f;
    /** Frames a lost track is kept before removal */
    private int trackBuffer = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDwellThresholdMs() {
        return dwellThresholdMs;
    }

    public void setDwellThresholdMs(long dwellThresholdMs) {
        this.dwellThresholdMs = dwellThresholdMs;
    }

    public double getStillThresholdPx() {
        return stillThresholdPx;
    }

    public void setStillThresholdPx(double stillThresholdPx) {
        this.stillThresholdPx = stillThresholdPx;
    }

    public long getAlertCooldownMs() {
        return alertCooldownMs;
    }

    public void setAlertCooldownMs(long alertCooldownMs) {
        this.alertCooldownMs = alertCooldownMs;
    }

    public float getTrackHighThresh() {
        return trackHighThresh;
    }

    public void setTrackHighThresh(float trackHighThresh) {
        this.trackHighThresh = trackHighThresh;
    }

    public float getTrackLowThresh() {
        return trackLowThresh;
    }

    public void setTrackLowThresh(float trackLowThresh) {
        this.trackLowThresh = trackLowThresh;
    }

    public float getTrackMatchThresh() {
        return trackMatchThresh;
    }

    public void setTrackMatchThresh(float trackMatchThresh) {
        this.trackMatchThresh = trackMatchThresh;
    }

    public int getTrackBuffer() {
        return trackBuffer;
    }

    public void setTrackBuffer(int trackBuffer) {
        this.trackBuffer = trackBuffer;
    }
}
