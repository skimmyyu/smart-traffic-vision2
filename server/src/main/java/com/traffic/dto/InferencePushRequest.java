package com.traffic.dto;

import java.util.List;
import java.util.Map;

public class InferencePushRequest {

    private String source;
    private String cameraId;
    private String cameraName;
    private Long capturedAt;
    private Integer vehicleCount;
    private String summary;
    private List<Map<String, Object>> detections;
    private Boolean saveCongestion;
    private Boolean savePlates;
    private List<Map<String, Object>> plates;
    /** vehicle | plate_result — plate_result only saves DB + plate_event WS */
    private String pushKind;
    /** Synced plate boxes for display; not merged into detections on vehicle push */
    private List<Map<String, Object>> plateOverlays;
    private Integer imageWidth;
    private Integer imageHeight;
    /** 异常检测：背景差分仍在学习中，前端据此显示提示 */
    private Boolean bgLearning;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCameraId() {
        return cameraId;
    }

    public void setCameraId(String cameraId) {
        this.cameraId = cameraId;
    }

    public String getCameraName() {
        return cameraName;
    }

    public void setCameraName(String cameraName) {
        this.cameraName = cameraName;
    }

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Integer getVehicleCount() {
        return vehicleCount;
    }

    public void setVehicleCount(Integer vehicleCount) {
        this.vehicleCount = vehicleCount;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<Map<String, Object>> getDetections() {
        return detections;
    }

    public void setDetections(List<Map<String, Object>> detections) {
        this.detections = detections;
    }

    public Boolean getSaveCongestion() {
        return saveCongestion;
    }

    public void setSaveCongestion(Boolean saveCongestion) {
        this.saveCongestion = saveCongestion;
    }

    public Integer getImageWidth() {
        return imageWidth;
    }

    public void setImageWidth(Integer imageWidth) {
        this.imageWidth = imageWidth;
    }

    public Integer getImageHeight() {
        return imageHeight;
    }

    public void setImageHeight(Integer imageHeight) {
        this.imageHeight = imageHeight;
    }

    public Boolean getSavePlates() {
        return savePlates;
    }

    public void setSavePlates(Boolean savePlates) {
        this.savePlates = savePlates;
    }

    public List<Map<String, Object>> getPlates() {
        return plates;
    }

    public void setPlates(List<Map<String, Object>> plates) {
        this.plates = plates;
    }

    public String getPushKind() {
        return pushKind;
    }

    public void setPushKind(String pushKind) {
        this.pushKind = pushKind;
    }

    public List<Map<String, Object>> getPlateOverlays() {
        return plateOverlays;
    }

    public void setPlateOverlays(List<Map<String, Object>> plateOverlays) {
        this.plateOverlays = plateOverlays;
    }

    public Boolean getBgLearning() {
        return bgLearning;
    }

    public void setBgLearning(Boolean bgLearning) {
        this.bgLearning = bgLearning;
    }
}
