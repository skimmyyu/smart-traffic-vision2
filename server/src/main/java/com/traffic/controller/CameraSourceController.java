package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.config.MediamtxProperties;
import com.traffic.core.StreamManager;
import com.traffic.dto.StreamSwitchResultDto;
import com.traffic.service.CameraSourceService;
import com.traffic.service.StreamChannelService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.Inet4Address;
import java.net.NetworkInterface;

@RestController
@RequestMapping("/api/camera")
public class CameraSourceController {

    private final CameraSourceService cameraSourceService;
    private final StreamChannelService streamChannelService;
    private final StreamManager streamManager;
    private final MediamtxProperties mediamtxProperties;

    public CameraSourceController(CameraSourceService cameraSourceService,
                                  StreamChannelService streamChannelService,
                                  StreamManager streamManager,
                                  MediamtxProperties mediamtxProperties) {
        this.cameraSourceService = cameraSourceService;
        this.streamChannelService = streamChannelService;
        this.streamManager = streamManager;
        this.mediamtxProperties = mediamtxProperties;
    }

    @GetMapping("/sources")
    public Result<Map<String, Object>> sources() {
        String active = cameraSourceService.getActiveSource();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeSource", active);
        data.put("sources", List.of(
                status(CameraSourceService.SANDBOX, "沙盘摄像头", active),
                status(CameraSourceService.IP_CAMERA, "手机 IP 摄像头 (Tailscale)", active),
                status(CameraSourceService.LOCAL, "本机摄像头", active),
                status(CameraSourceService.PHONE, "手机浏览器采集", active)
        ));
        data.put("phoneCaptureUrls", phoneCaptureUrls());
        data.put("ipCamera", ipCameraInfo());
        return Result.ok(data);
    }

    @PostMapping("/source/{sourceId}")
    public Result<Map<String, Object>> select(@PathVariable String sourceId) {
        cameraSourceService.select(sourceId);
        StreamSwitchResultDto stream = switchStreamForSource(sourceId);
        Map<String, Object> item = status(sourceId, sourceLabel(sourceId), sourceId);
        if (stream != null) {
            item.put("hlsUrl", stream.getHlsUrl());
            item.put("webrtcUrl", stream.getWebrtcUrl());
            item.put("rtspUrl", stream.getRtspUrl());
            item.put("channelId", stream.getChannelId());
            item.put("channelName", stream.getChannelName());
            item.put("switchedAt", stream.getSwitchedAt());
            item.put("settleMs", CameraSourceService.IP_CAMERA.equals(sourceId) ? 1000 : 2000);
        }
        return Result.ok(item);
    }

    @PostMapping(value = "/frame/{sourceId}", consumes = MediaType.IMAGE_JPEG_VALUE)
    public Result<Map<String, Object>> upload(@PathVariable String sourceId, @RequestBody byte[] jpeg) {
        cameraSourceService.acceptJpeg(sourceId, jpeg);
        return Result.ok(Map.of(
                "sourceId", sourceId,
                "updatedAt", cameraSourceService.getUpdatedAt(sourceId),
                "online", true
        ));
    }

    @GetMapping(value = "/frame/{sourceId}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> frame(@PathVariable String sourceId,
                                        @RequestParam(required = false) Long t) {
        byte[] jpeg = cameraSourceService.getJpegCopy(sourceId);
        if (jpeg == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(jpeg);
    }

    private StreamSwitchResultDto switchStreamForSource(String sourceId) {
        return switch (sourceId) {
            case CameraSourceService.IP_CAMERA -> streamChannelService.switchToIpCamera();
            case CameraSourceService.SANDBOX -> streamChannelService.switchToSandboxChannel(
                    streamChannelService.getCurrent().getChannelId());
            default -> null;
        };
    }

    private Map<String, Object> status(String id, String name, String activeSource) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        boolean online;
        if (CameraSourceService.SANDBOX.equals(id) || CameraSourceService.IP_CAMERA.equals(id)) {
            online = CameraSourceService.SANDBOX.equals(activeSource) || CameraSourceService.IP_CAMERA.equals(activeSource)
                    ? streamManager.isOnline()
                    : false;
        } else {
            online = cameraSourceService.isOnline(id);
        }
        item.put("online", online);
        item.put("updatedAt", cameraSourceService.getUpdatedAt(id));
        return item;
    }

    private String sourceLabel(String sourceId) {
        return switch (sourceId) {
            case CameraSourceService.LOCAL -> "本机摄像头";
            case CameraSourceService.PHONE -> "手机浏览器采集";
            case CameraSourceService.IP_CAMERA -> "手机 IP 摄像头";
            default -> "沙盘摄像头";
        };
    }

    private Map<String, Object> ipCameraInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        String user = mediamtxProperties.getIpCameraUsername();
        boolean auth = user != null && !user.isBlank();
        String host = mediamtxProperties.getIpCameraHost();
        int port = mediamtxProperties.getIpCameraPort();
        String path = mediamtxProperties.getIpCameraPath();
        String displayRtsp = auth
                ? String.format("rtsp://%s:***@%s:%d/%s", user.trim(), host, port, path)
                : streamChannelService.buildIpCameraRtspUrl();
        info.put("rtspUrl", displayRtsp);
        info.put("tailscaleHost", host);
        info.put("authRequired", auth);
        info.put("hlsUrl", streamChannelService.getIpCameraHlsUrl());
        info.put("webrtcUrl", streamChannelService.getIpCameraWebrtcUrl());
        info.put("setupHint", "RTSP 需要账号密码时在 application.yml 配置 ip-camera-username/password；手机 App 内可关闭鉴权或查看凭据");
        return info;
    }

    private List<String> phoneCaptureUrls() {
        List<String> urls = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                String nicName = (nic.getName() + " " + nic.getDisplayName()).toLowerCase();
                if (nicName.contains("vmware") || nicName.contains("virtual")
                        || nicName.contains("vethernet") || nicName.contains("hyper-v")
                        || nicName.contains("docker") || nicName.contains("wsl")) continue;
                for (var address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        urls.add("http://" + address.getHostAddress() + ":5173/phone-capture");
                    }
                }
            }
        } catch (Exception ignored) {
            // UI can still use the current host when interface discovery is restricted.
        }
        return urls.stream().distinct().sorted().toList();
    }
}
