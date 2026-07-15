package com.traffic.dto;

public class StreamSwitchResultDto {

    private String channelId;
    private String channelName;
    private String rtspUrl;
    private String hlsUrl;
    private String webrtcUrl;
    /** Epoch millis of last channel switch (0 if never). Used by live_bridge to reconnect fast. */
    private long switchedAt;

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public String getHlsUrl() {
        return hlsUrl;
    }

    public void setHlsUrl(String hlsUrl) {
        this.hlsUrl = hlsUrl;
    }

    public String getWebrtcUrl() {
        return webrtcUrl;
    }

    public void setWebrtcUrl(String webrtcUrl) {
        this.webrtcUrl = webrtcUrl;
    }

    public long getSwitchedAt() {
        return switchedAt;
    }

    public void setSwitchedAt(long switchedAt) {
        this.switchedAt = switchedAt;
    }
}
