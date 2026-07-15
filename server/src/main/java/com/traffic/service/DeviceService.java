package com.traffic.service;

import com.traffic.dto.DeviceCreateRequest;
import com.traffic.entity.Device;
import com.traffic.core.StreamManager;
import com.traffic.config.MediamtxProperties;
import com.traffic.mapper.DeviceMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private static final int HEARTBEAT_TIMEOUT_SECONDS = 120;

    private static final Set<String> HIDDEN_DEVICE_NAMES = Set.of(
            "本机电脑摄像头", "手机摄像头", "手机浏览器采集"
    );

    private final DeviceMapper deviceMapper;
    private final CameraSourceService cameraSourceService;
    private final StreamChannelService streamChannelService;
    private final StreamManager streamManager;
    private final MediamtxProperties mediamtxProperties;

    public DeviceService(DeviceMapper deviceMapper,
                         CameraSourceService cameraSourceService,
                         StreamChannelService streamChannelService,
                         StreamManager streamManager,
                         MediamtxProperties mediamtxProperties) {
        this.deviceMapper = deviceMapper;
        this.cameraSourceService = cameraSourceService;
        this.streamChannelService = streamChannelService;
        this.streamManager = streamManager;
        this.mediamtxProperties = mediamtxProperties;
    }

    public List<Device> listAll() {
        List<Device> devices = deviceMapper.findAll().stream()
                .filter(device -> !isHiddenDevice(device))
                .collect(Collectors.toList());
        String currentChannel = streamChannelService.getCurrent().getChannelId();
        for (Device device : devices) {
            String url = device.getStreamUrl() == null ? "" : device.getStreamUrl();
            boolean liveStatusKnown = false;
            boolean online = false;
            if (url.startsWith("browser-camera://local")) {
                liveStatusKnown = true;
                online = cameraSourceService.isOnline(CameraSourceService.LOCAL);
            } else if (url.startsWith("browser-camera://phone")) {
                liveStatusKnown = true;
                online = cameraSourceService.isOnline(CameraSourceService.PHONE);
            } else if (url.startsWith("rtsp://" + mediamtxProperties.getIpCameraHost())) {
                liveStatusKnown = true;
                online = CameraSourceService.IP_CAMERA.equals(cameraSourceService.getActiveSource())
                        && streamManager.isOnline();
            } else if (url.endsWith("/" + currentChannel)) {
                liveStatusKnown = true;
                online = CameraSourceService.SANDBOX.equals(cameraSourceService.getActiveSource())
                        && streamManager.isOnline();
            }
            if (liveStatusKnown) {
                device.setStatus(online ? "online" : "offline");
                if (online) device.setLastOnline(LocalDateTime.now());
            }
        }
        return devices;
    }

    @PostConstruct
    public void ensureBuiltInCameras() {
        try {
            purgeHiddenDevices();
            ensureDevice("手机 IP 摄像头 (Tailscale)",
                    mediamtxProperties.getIpCameraHost(),
                    streamChannelService.buildIpCameraRtspUrl());

            Map<String, String> channels = new LinkedHashMap<>();
            channels.put("live1", "桥面");
            channels.put("live2", "停车场出口");
            channels.put("live3", "隧道旁道路");
            channels.put("live4", "道路4");
            channels.put("live5", "桥出口");
            channels.put("live6", "桥入口");
            channels.put("live7", "道路2");
            channels.put("live8", "隧道出口");
            channels.put("live9", "隧道入口");
            channels.put("live10", "道路3");
            channels.put("live11", "停车场入口");
            channels.put("live12", "道路1");
            for (Map.Entry<String, String> entry : channels.entrySet()) {
                ensureDevice("沙盘摄像头 · " + entry.getValue() + " (" + entry.getKey() + ")",
                        "10.126.59.120",
                        "rtsp://10.126.59.120:8554/live/" + entry.getKey());
            }
            syncSandboxDeviceNames(channels);
        } catch (Exception ex) {
            log.warn("初始化内置摄像头设备失败: {}", ex.getMessage());
        }
    }

    private void purgeHiddenDevices() {
        for (Device device : deviceMapper.findAll()) {
            if (isHiddenDevice(device)) {
                deviceMapper.deleteById(device.getId());
            }
        }
    }

    private void syncSandboxDeviceNames(Map<String, String> channels) {
        for (Device device : deviceMapper.findAll()) {
            String url = device.getStreamUrl() == null ? "" : device.getStreamUrl();
            if (!url.contains("/live/live")) {
                continue;
            }
            for (Map.Entry<String, String> entry : channels.entrySet()) {
                if (!url.endsWith("/" + entry.getKey())) {
                    continue;
                }
                String expectedName = "沙盘摄像头 · " + entry.getValue() + " (" + entry.getKey() + ")";
                if (!expectedName.equals(device.getName())) {
                    deviceMapper.updateName(device.getId(), expectedName);
                }
                break;
            }
        }
    }

    private boolean isHiddenDevice(Device device) {
        if (device == null) {
            return true;
        }
        String url = device.getStreamUrl() == null ? "" : device.getStreamUrl();
        if (url.startsWith("browser-camera://local") || url.startsWith("browser-camera://phone")) {
            return true;
        }
        String name = device.getName() == null ? "" : device.getName();
        return HIDDEN_DEVICE_NAMES.contains(name);
    }

    private void ensureDevice(String name, String ip, String streamUrl) {
        if (deviceMapper.findByName(name) != null) return;
        Device device = new Device();
        device.setName(name);
        device.setIp(ip);
        device.setStreamUrl(streamUrl);
        device.setStatus("offline");
        device.setLastOnline(null);
        deviceMapper.insert(device);
    }

    public Device register(DeviceCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("设备名称不能为空");
        }

        Device device = new Device();
        device.setName(request.getName().trim());
        device.setIp(trimToNull(request.getIp()));
        device.setStreamUrl(trimToNull(request.getStreamUrl()));
        device.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus().trim() : "offline");
        device.setLastOnline("online".equalsIgnoreCase(device.getStatus()) ? LocalDateTime.now() : null);

        deviceMapper.insert(device);
        return deviceMapper.findById(device.getId());
    }

    public Device heartbeat(Long id) {
        Device device = deviceMapper.findById(id);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在");
        }
        deviceMapper.updateStatus(id, "online", LocalDateTime.now());
        return deviceMapper.findById(id);
    }

    @Scheduled(fixedDelay = 60000)
    public void markStaleDevicesOffline() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);
        deviceMapper.markOfflineBefore(deadline);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
