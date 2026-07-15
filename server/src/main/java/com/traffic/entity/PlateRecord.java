package com.traffic.entity;

import java.time.LocalDateTime;

public class PlateRecord {

    private Long id;
    private String plateNumber;
    private String passResult;
    private String cameraId;
    private String cameraName;
    private LocalDateTime recognizedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        this.plateNumber = plateNumber;
    }

    public String getPassResult() {
        return passResult;
    }

    public void setPassResult(String passResult) {
        this.passResult = passResult;
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

    public LocalDateTime getRecognizedAt() {
        return recognizedAt;
    }

    public void setRecognizedAt(LocalDateTime recognizedAt) {
        this.recognizedAt = recognizedAt;
    }
}
