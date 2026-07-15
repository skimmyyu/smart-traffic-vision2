package com.traffic.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Single detection box in original image pixel coordinates (xyxy).
 */
public class DetectionBox {

    private final String className;
    private final float confidence;
    private final int x1;
    private final int y1;
    private final int x2;
    private final int y2;
    /** Background-track consecutive hits (0 for model-only boxes). */
    private final int trackHits;

    public DetectionBox(String className, float confidence, int x1, int y1, int x2, int y2) {
        this(className, confidence, x1, y1, x2, y2, 0);
    }

    public DetectionBox(String className, float confidence, int x1, int y1, int x2, int y2, int trackHits) {
        this.className = className;
        this.confidence = confidence;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.trackHits = Math.max(0, trackHits);
    }

    public String getClassName() {
        return className;
    }

    public float getConfidence() {
        return confidence;
    }

    public int getX1() {
        return x1;
    }

    public int getY1() {
        return y1;
    }

    public int getX2() {
        return x2;
    }

    public int getY2() {
        return y2;
    }

    public int getTrackHits() {
        return trackHits;
    }

    public List<Integer> bboxList() {
        List<Integer> box = new ArrayList<>(4);
        box.add(x1);
        box.add(y1);
        box.add(x2);
        box.add(y2);
        return box;
    }
}
