package com.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mediamtx")
public class MediamtxProperties {

    private String configPath = "../mediamtx/mediamtx.yml";
    private String sandboxHost = "10.126.59.120";
    private int sandboxPort = 8554;
    private String hlsBaseUrl = "http://127.0.0.1:8888";
    private String webrtcBaseUrl = "http://127.0.0.1:8889";
    /** MediaMTX control API, used for hot path source switch */
    private String apiBaseUrl = "http://127.0.0.1:9997";
    /** Tailscale IP of phone running IP Camera app */
    private String ipCameraHost = "100.71.110.18";
    private int ipCameraPort = 8554;
    /** RTSP path segment after host:port, e.g. live → rtsp://host:8554/live */
    private String ipCameraPath = "live";
    /** MediaMTX path name for phone (local relay: rtsp://127.0.0.1:8554/cam-phone) */
    private String ipCameraMediamtxPath = "cam-phone";
    /** IP Camera app RTSP auth (401 without these) */
    private String ipCameraUsername = "";
    private String ipCameraPassword = "";

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getSandboxHost() {
        return sandboxHost;
    }

    public void setSandboxHost(String sandboxHost) {
        this.sandboxHost = sandboxHost;
    }

    public int getSandboxPort() {
        return sandboxPort;
    }

    public void setSandboxPort(int sandboxPort) {
        this.sandboxPort = sandboxPort;
    }

    public String getHlsBaseUrl() {
        return hlsBaseUrl;
    }

    public void setHlsBaseUrl(String hlsBaseUrl) {
        this.hlsBaseUrl = hlsBaseUrl;
    }

    public String getWebrtcBaseUrl() {
        return webrtcBaseUrl;
    }

    public void setWebrtcBaseUrl(String webrtcBaseUrl) {
        this.webrtcBaseUrl = webrtcBaseUrl;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getIpCameraHost() {
        return ipCameraHost;
    }

    public void setIpCameraHost(String ipCameraHost) {
        this.ipCameraHost = ipCameraHost;
    }

    public int getIpCameraPort() {
        return ipCameraPort;
    }

    public void setIpCameraPort(int ipCameraPort) {
        this.ipCameraPort = ipCameraPort;
    }

    public String getIpCameraPath() {
        return ipCameraPath;
    }

    public void setIpCameraPath(String ipCameraPath) {
        this.ipCameraPath = ipCameraPath;
    }

    public String getIpCameraUsername() {
        return ipCameraUsername;
    }

    public void setIpCameraUsername(String ipCameraUsername) {
        this.ipCameraUsername = ipCameraUsername;
    }

    public String getIpCameraPassword() {
        return ipCameraPassword;
    }

    public void setIpCameraPassword(String ipCameraPassword) {
        this.ipCameraPassword = ipCameraPassword;
    }

    public String getIpCameraMediamtxPath() {
        return ipCameraMediamtxPath;
    }

    public void setIpCameraMediamtxPath(String ipCameraMediamtxPath) {
        this.ipCameraMediamtxPath = ipCameraMediamtxPath;
    }
}
