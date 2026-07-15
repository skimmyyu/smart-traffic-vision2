package com.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "anomaly")
public class AnomalyProperties {

    private boolean enabled = true;
    private String modelPath = "../models/anomaly.onnx";
    private String vehicleModelPath = "../models/vehicle.onnx";
    /** Hard accept threshold for YOLO debris */
    private float confThreshold = 0.25f;
    /** Soft YOLO candidates that can be kept if background-diff agrees */
    private float softConfThreshold = 0.12f;
    private float vehicleConfThreshold = 0.25f;
    private float iouThreshold = 0.45f;
    private float vehicleSuppressIou = 0.12f;
    private int imgsz = 640;
    private long intervalMs = 350;
    private String classNames = "debris";
    private String vehicleClassNames = "car";
    private long alertCooldownMs = 8000;
    private int maxBoxes = 5;

    /** Fuse background-difference assist with anomaly.onnx */
    private boolean bgDiffEnabled = true;
    /** Learn background for this many seconds, then freeze forever (until channel/mode reset) */
    private float bgLearnSeconds = 10f;
    /** Optional fixed frame count; if >0 overrides bgLearnSeconds */
    private int bgWarmupFrames = 0;
    private float bgDiffThreshold = 28f;
    private float bgMaxFgRatio = 0.05f;
    private float bgMinAreaRatio = 0.03f;
    private float bgMaxAreaRatio = 0.12f;
    private int bgPersistHits = 1;
    /** Confidence assigned to bg-only confirmed boxes */
    private float bgOnlyConf = 0.40f;
    /** Candidates keep tracking internally but are hidden until reaching this confidence. */
    private float minDisplayConfidence = 0.60f;
    /** Extra confidence when soft YOLO overlaps a bg blob */
    private float bgBoost = 0.15f;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelPath() {
        return modelPath;
    }

    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    public String getVehicleModelPath() {
        return vehicleModelPath;
    }

    public void setVehicleModelPath(String vehicleModelPath) {
        this.vehicleModelPath = vehicleModelPath;
    }

    public float getConfThreshold() {
        return confThreshold;
    }

    public void setConfThreshold(float confThreshold) {
        this.confThreshold = confThreshold;
    }

    public float getSoftConfThreshold() {
        return softConfThreshold;
    }

    public void setSoftConfThreshold(float softConfThreshold) {
        this.softConfThreshold = softConfThreshold;
    }

    public float getVehicleConfThreshold() {
        return vehicleConfThreshold;
    }

    public void setVehicleConfThreshold(float vehicleConfThreshold) {
        this.vehicleConfThreshold = vehicleConfThreshold;
    }

    public float getIouThreshold() {
        return iouThreshold;
    }

    public void setIouThreshold(float iouThreshold) {
        this.iouThreshold = iouThreshold;
    }

    public float getVehicleSuppressIou() {
        return vehicleSuppressIou;
    }

    public void setVehicleSuppressIou(float vehicleSuppressIou) {
        this.vehicleSuppressIou = vehicleSuppressIou;
    }

    public int getImgsz() {
        return imgsz;
    }

    public void setImgsz(int imgsz) {
        this.imgsz = imgsz;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public String getClassNames() {
        return classNames;
    }

    public void setClassNames(String classNames) {
        this.classNames = classNames;
    }

    public String getVehicleClassNames() {
        return vehicleClassNames;
    }

    public void setVehicleClassNames(String vehicleClassNames) {
        this.vehicleClassNames = vehicleClassNames;
    }

    public long getAlertCooldownMs() {
        return alertCooldownMs;
    }

    public void setAlertCooldownMs(long alertCooldownMs) {
        this.alertCooldownMs = alertCooldownMs;
    }

    public int getMaxBoxes() {
        return maxBoxes;
    }

    public void setMaxBoxes(int maxBoxes) {
        this.maxBoxes = maxBoxes;
    }

    public boolean isBgDiffEnabled() {
        return bgDiffEnabled;
    }

    public void setBgDiffEnabled(boolean bgDiffEnabled) {
        this.bgDiffEnabled = bgDiffEnabled;
    }

    public float getBgLearnSeconds() {
        return bgLearnSeconds;
    }

    public void setBgLearnSeconds(float bgLearnSeconds) {
        this.bgLearnSeconds = bgLearnSeconds;
    }

    public int getBgWarmupFrames() {
        return bgWarmupFrames;
    }

    public void setBgWarmupFrames(int bgWarmupFrames) {
        this.bgWarmupFrames = bgWarmupFrames;
    }

    /** Resolve learn-window length in frames from seconds or explicit override. */
    public int resolveBgWarmupFrames() {
        if (bgWarmupFrames > 0) {
            return bgWarmupFrames;
        }
        long interval = Math.max(1L, intervalMs);
        float seconds = bgLearnSeconds > 0f ? bgLearnSeconds : 10f;
        return Math.max(8, Math.round(seconds * 1000f / interval));
    }

    public float getBgDiffThreshold() {
        return bgDiffThreshold;
    }

    public void setBgDiffThreshold(float bgDiffThreshold) {
        this.bgDiffThreshold = bgDiffThreshold;
    }

    public float getBgMaxFgRatio() {
        return bgMaxFgRatio;
    }

    public void setBgMaxFgRatio(float bgMaxFgRatio) {
        this.bgMaxFgRatio = bgMaxFgRatio;
    }

    public float getBgMinAreaRatio() {
        return bgMinAreaRatio;
    }

    public void setBgMinAreaRatio(float bgMinAreaRatio) {
        this.bgMinAreaRatio = bgMinAreaRatio;
    }

    public float getBgMaxAreaRatio() {
        return bgMaxAreaRatio;
    }

    public void setBgMaxAreaRatio(float bgMaxAreaRatio) {
        this.bgMaxAreaRatio = bgMaxAreaRatio;
    }

    public int getBgPersistHits() {
        return bgPersistHits;
    }

    public void setBgPersistHits(int bgPersistHits) {
        this.bgPersistHits = bgPersistHits;
    }

    public float getBgOnlyConf() {
        return bgOnlyConf;
    }

    public void setBgOnlyConf(float bgOnlyConf) {
        this.bgOnlyConf = bgOnlyConf;
    }

    public float getMinDisplayConfidence() {
        return minDisplayConfidence;
    }

    public void setMinDisplayConfidence(float minDisplayConfidence) {
        this.minDisplayConfidence = minDisplayConfidence;
    }

    public float getBgBoost() {
        return bgBoost;
    }

    public void setBgBoost(float bgBoost) {
        this.bgBoost = bgBoost;
    }
}
