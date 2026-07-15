package com.traffic.dto;

public class StreamChannelDto {

    private String id;
    private String name;
    private String rtspUrl;
    private String scene;

    public StreamChannelDto() {
    }

    public StreamChannelDto(String id, String name, String rtspUrl, String scene) {
        this.id = id;
        this.name = name;
        this.rtspUrl = rtspUrl;
        this.scene = scene;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }
}
