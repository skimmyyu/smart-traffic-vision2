package com.traffic.service;

import com.traffic.config.MediamtxProperties;
import com.traffic.core.StreamManager;
import com.traffic.dto.StreamChannelDto;
import com.traffic.dto.StreamSwitchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StreamChannelService {

    private static final Logger log = LoggerFactory.getLogger(StreamChannelService.class);
    private static final Pattern CAM1_SOURCE_PATTERN = Pattern.compile(
            "(  cam1:\\r?\\n(?:    [^\\r\\n]+\\r?\\n)*?    source:) rtsp://[^\\r\\n]+",
            Pattern.MULTILINE
    );
    private static final Pattern CAM1_ON_DEMAND_PATTERN = Pattern.compile(
            "(  cam1:\\r?\\n(?:    [^\\r\\n]+\\r?\\n)*?    sourceOnDemand:) (?:true|false)",
            Pattern.MULTILINE
    );
    private static final long SWITCH_GRACE_MS = 12000L;

    private final MediamtxProperties properties;
    private final StreamManager streamManager;
    private final AtomicReference<String> currentChannelId = new AtomicReference<>("live12");
    private final AtomicLong lastSwitchAtMs = new AtomicLong(0);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public StreamChannelService(MediamtxProperties properties, StreamManager streamManager) {
        this.properties = properties;
        this.streamManager = streamManager;
    }

    public List<StreamChannelDto> listChannels() {
        List<StreamChannelDto> list = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : channelDefinitions().entrySet()) {
            String id = entry.getKey();
            String[] meta = entry.getValue();
            list.add(new StreamChannelDto(id, meta[0], buildRtspUrl(id), meta[1]));
        }
        return list;
    }

    public StreamSwitchResultDto getCurrent() {
        String channelId = currentChannelId.get();
        Map<String, String[]> defs = channelDefinitions();
        String[] meta = defs.get(channelId);
        if (meta == null) {
            channelId = "live12";
            meta = defs.get(channelId);
        }
        StreamSwitchResultDto result = new StreamSwitchResultDto();
        result.setChannelId(channelId);
        result.setChannelName(meta[0]);
        result.setRtspUrl(buildRtspUrl(channelId));
        result.setHlsUrl(buildHlsUrl());
        result.setWebrtcUrl(buildWebRtcUrl("cam1"));
        result.setSwitchedAt(lastSwitchAtMs.get());
        return result;
    }

    public StreamSwitchResultDto switchChannel(String channelId) {
        if (!channelDefinitions().containsKey(channelId)) {
            throw new IllegalArgumentException("未知监控通道: " + channelId);
        }
        String rtspUrl = buildRtspUrl(channelId);
        applyMediamtxSandboxSource(rtspUrl, channelId);
        return buildCurrentResult(channelId);
    }

    /** Patch cam1 upstream sandbox URL only (never overwrites cam-phone). */
    private void applyMediamtxSandboxSource(String upstreamRtspUrl, String channelId) {
        boolean apiOk = patchMediamtxSourceViaApi(upstreamRtspUrl, true);
        if (!apiOk) {
            updateMediamtxSource(upstreamRtspUrl, true);
        }
        currentChannelId.set(channelId);
        lastSwitchAtMs.set(System.currentTimeMillis());
        streamManager.switchPullUrl(localMediamtxRtsp("cam1"), SWITCH_GRACE_MS);
    }

    private String localMediamtxRtsp(String pathName) {
        return "rtsp://127.0.0.1:" + properties.getSandboxPort() + "/" + pathName;
    }

    private String ipCameraMediamtxPath() {
        String path = properties.getIpCameraMediamtxPath();
        return (path == null || path.isBlank()) ? "cam-phone" : path.trim();
    }

    /** Point clients at cam-phone (no cam1 hot-swap). */
    public StreamSwitchResultDto switchToIpCamera() {
        lastSwitchAtMs.set(System.currentTimeMillis());
        streamManager.switchPullUrl(localMediamtxRtsp(ipCameraMediamtxPath()), SWITCH_GRACE_MS);
        log.info("Switched pull/HLS to IP Camera path {}", ipCameraMediamtxPath());
        return buildIpCameraResult();
    }

    /** Point clients back at cam1; patch sandbox upstream only if channel changed. */
    public StreamSwitchResultDto switchToSandboxChannel(String channelId) {
        if (!channelDefinitions().containsKey(channelId)) {
            channelId = currentChannelId.get();
            if (!channelDefinitions().containsKey(channelId)) {
                channelId = "live12";
            }
        }
        lastSwitchAtMs.set(System.currentTimeMillis());
        String rtspUrl = buildRtspUrl(channelId);
        if (!channelId.equals(currentChannelId.get())) {
            applyMediamtxSandboxSource(rtspUrl, channelId);
        } else {
            streamManager.switchPullUrl(localMediamtxRtsp("cam1"), SWITCH_GRACE_MS);
        }
        log.info("Switched pull/HLS to sandbox cam1 channel {}", channelId);
        return buildCurrentResult(channelId);
    }

    public String buildIpCameraRtspUrl() {
        String path = properties.getIpCameraPath();
        if (path == null || path.isBlank()) {
            path = "live";
        }
        path = path.replaceAll("^/+", "");
        String user = properties.getIpCameraUsername();
        String pass = properties.getIpCameraPassword();
        String auth = "";
        if (user != null && !user.isBlank()) {
            String encUser = URLEncoder.encode(user.trim(), StandardCharsets.UTF_8);
            String encPass = pass == null ? "" : URLEncoder.encode(pass, StandardCharsets.UTF_8);
            auth = encUser + ":" + encPass + "@";
        }
        return String.format("rtsp://%s%s:%d/%s",
                auth,
                properties.getIpCameraHost(),
                properties.getIpCameraPort(),
                path);
    }

    private StreamSwitchResultDto buildCurrentResult(String channelId) {
        String[] meta = channelDefinitions().get(channelId);
        StreamSwitchResultDto result = new StreamSwitchResultDto();
        result.setChannelId(channelId);
        result.setChannelName(meta[0]);
        result.setRtspUrl(buildRtspUrl(channelId));
        result.setHlsUrl(buildHlsUrl());
        result.setWebrtcUrl(buildWebRtcUrl("cam1"));
        result.setSwitchedAt(lastSwitchAtMs.get());
        return result;
    }

    private StreamSwitchResultDto buildIpCameraResult() {
        StreamSwitchResultDto result = new StreamSwitchResultDto();
        result.setChannelId("ip-camera");
        result.setChannelName("手机 IP 摄像头");
        result.setRtspUrl(buildIpCameraRtspUrl());
        result.setHlsUrl(buildIpCameraHlsUrl());
        result.setWebrtcUrl(buildWebRtcUrl(ipCameraMediamtxPath()));
        result.setSwitchedAt(lastSwitchAtMs.get());
        return result;
    }

    private String buildIpCameraHlsUrl() {
        return properties.getHlsBaseUrl() + "/" + ipCameraMediamtxPath() + "/index.m3u8";
    }

    public String getIpCameraHlsUrl() {
        return buildIpCameraHlsUrl();
    }

    public String getIpCameraWebrtcUrl() {
        return buildWebRtcUrl(ipCameraMediamtxPath());
    }

    /** Epoch millis of last channel switch; 0 if never switched in this process. */
    public long getLastSwitchAtMs() {
        return lastSwitchAtMs.get();
    }

    private String buildRtspUrl(String channelId) {
        return String.format("rtsp://%s:%d/live/%s",
                properties.getSandboxHost(),
                properties.getSandboxPort(),
                channelId);
    }

    private String buildHlsUrl() {
        return properties.getHlsBaseUrl() + "/cam1/index.m3u8";
    }

    private String buildWebRtcUrl(String pathName) {
        String base = properties.getWebrtcBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://127.0.0.1:8889";
        }
        return base.replaceAll("/$", "") + "/" + pathName + "/whep";
    }

    private boolean patchMediamtxSourceViaApi(String rtspUrl, boolean sourceOnDemand) {
        String base = properties.getApiBaseUrl();
        if (base == null || base.isBlank()) {
            return false;
        }
        String url = base.replaceAll("/$", "") + "/v3/config/paths/patch/cam1";
        String escaped = rtspUrl.replace("\\", "\\\\").replace("\"", "\\\"");
        String body = String.format(
                "{\"source\":\"%s\",\"sourceOnDemand\":%s,\"sourceOnDemandCloseAfter\":\"60s\"}",
                escaped,
                sourceOnDemand
        );
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            }
            log.warn("MediaMTX API patch failed: HTTP {} {}", response.statusCode(), response.body());
            return false;
        } catch (Exception ex) {
            log.warn("MediaMTX API patch error: {}", ex.getMessage());
            return false;
        }
    }

    private void updateMediamtxSource(String rtspUrl, boolean sourceOnDemand) {
        Path configPath = Paths.get(properties.getConfigPath()).toAbsolutePath().normalize();
        if (!Files.exists(configPath)) {
            throw new IllegalStateException("找不到 mediamtx.yml: " + configPath);
        }
        try {
            String content = Files.readString(configPath, StandardCharsets.UTF_8);
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }
            Matcher matcher = CAM1_SOURCE_PATTERN.matcher(content);
            if (!matcher.find()) {
                throw new IllegalStateException("mediamtx.yml 中未找到 cam1 source 配置");
            }
            content = matcher.replaceFirst("$1 " + rtspUrl);
            Matcher onDemand = CAM1_ON_DEMAND_PATTERN.matcher(content);
            if (onDemand.find()) {
                content = onDemand.replaceFirst("$1 " + sourceOnDemand);
            }
            Files.writeString(configPath, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("更新 mediamtx.yml 失败: " + ex.getMessage(), ex);
        }
    }

    private Map<String, String[]> channelDefinitions() {
        Map<String, String[]> map = new LinkedHashMap<>();
        map.put("live1", new String[]{"桥面", "车牌/车流"});
        map.put("live2", new String[]{"停车场出口", "闸机/禁停"});
        map.put("live3", new String[]{"隧道旁道路", "道路异常/行人"});
        map.put("live4", new String[]{"道路4", "道路"});
        map.put("live5", new String[]{"桥出口", "闸机/车牌"});
        map.put("live6", new String[]{"桥入口", "闸机/道路异常"});
        map.put("live7", new String[]{"道路2", "道路异常/拥堵"});
        map.put("live8", new String[]{"隧道出口", "隧道"});
        map.put("live9", new String[]{"隧道入口", "车辆计数"});
        map.put("live10", new String[]{"道路3", "拥堵"});
        map.put("live11", new String[]{"停车场入口", "闸机/禁停"});
        map.put("live12", new String[]{"道路1", "道路异常/拥堵"});
        return map;
    }
}
