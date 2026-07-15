package com.traffic.ai;

/**
 * Simple axis-aligned box used by ByteTrack (xyxy + score).
 */
public class TrackDetection {

    public final float x1;
    public final float y1;
    public final float x2;
    public final float y2;
    public final float score;
    public final String className;

    public TrackDetection(float x1, float y1, float x2, float y2, float score, String className) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.score = score;
        this.className = className == null ? "car" : className;
    }

    public float cx() {
        return (x1 + x2) * 0.5f;
    }

    public float cy() {
        return (y1 + y2) * 0.5f;
    }

    public float width() {
        return Math.max(1f, x2 - x1);
    }

    public float height() {
        return Math.max(1f, y2 - y1);
    }

    public static float iou(TrackDetection a, TrackDetection b) {
        float ix1 = Math.max(a.x1, b.x1);
        float iy1 = Math.max(a.y1, b.y1);
        float ix2 = Math.min(a.x2, b.x2);
        float iy2 = Math.min(a.y2, b.y2);
        float iw = Math.max(0f, ix2 - ix1);
        float ih = Math.max(0f, iy2 - iy1);
        float inter = iw * ih;
        if (inter <= 0f) {
            return 0f;
        }
        float areaA = a.width() * a.height();
        float areaB = b.width() * b.height();
        float union = areaA + areaB - inter;
        return union > 0f ? inter / union : 0f;
    }
}
