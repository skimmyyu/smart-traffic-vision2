package com.traffic.ai;

/**
 * One ByteTrack track state.
 */
public class STrack {

    public enum State {
        New, Tracked, Lost, Removed
    }

    private static int nextId = 1;

    private final int trackId;
    private State state = State.New;
    private TrackDetection detection;
    private String className;
    private float score;
    private int trackletLen;
    private int timeSinceUpdate;
    private long lastSeenMs;

    // dwell / stationary state (filled by parking service)
    private long stillSinceMs;
    private float stillAnchorX;
    private float stillAnchorY;
    private boolean inZone;
    private String zoneName;
    private long lastAlertMs;

    public STrack(TrackDetection det) {
        this.trackId = nextId++;
        this.detection = det;
        this.className = det.className;
        this.score = det.score;
        this.trackletLen = 1;
        this.timeSinceUpdate = 0;
        this.lastSeenMs = System.currentTimeMillis();
        this.stillSinceMs = 0;
        this.stillAnchorX = det.cx();
        this.stillAnchorY = det.cy();
    }

    public static synchronized void resetIdCounter() {
        nextId = 1;
    }

    public void update(TrackDetection det) {
        this.detection = det;
        this.className = det.className;
        this.score = det.score;
        this.trackletLen++;
        this.timeSinceUpdate = 0;
        this.lastSeenMs = System.currentTimeMillis();
        this.state = State.Tracked;
    }

    public void markLost() {
        this.state = State.Lost;
        this.timeSinceUpdate++;
    }

    public void markRemoved() {
        this.state = State.Removed;
    }

    public void activate() {
        this.state = State.Tracked;
    }

    public int getTrackId() {
        return trackId;
    }

    public State getState() {
        return state;
    }

    public TrackDetection getDetection() {
        return detection;
    }

    public String getClassName() {
        return className;
    }

    public float getScore() {
        return score;
    }

    public int getTrackletLen() {
        return trackletLen;
    }

    public int getTimeSinceUpdate() {
        return timeSinceUpdate;
    }

    public long getLastSeenMs() {
        return lastSeenMs;
    }

    public long getStillSinceMs() {
        return stillSinceMs;
    }

    public void setStillSinceMs(long stillSinceMs) {
        this.stillSinceMs = stillSinceMs;
    }

    public float getStillAnchorX() {
        return stillAnchorX;
    }

    public float getStillAnchorY() {
        return stillAnchorY;
    }

    public void setStillAnchor(float x, float y) {
        this.stillAnchorX = x;
        this.stillAnchorY = y;
    }

    public boolean isInZone() {
        return inZone;
    }

    public void setInZone(boolean inZone) {
        this.inZone = inZone;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public long getLastAlertMs() {
        return lastAlertMs;
    }

    public void setLastAlertMs(long lastAlertMs) {
        this.lastAlertMs = lastAlertMs;
    }

    public float cx() {
        return detection.cx();
    }

    public float cy() {
        return detection.cy();
    }
}
