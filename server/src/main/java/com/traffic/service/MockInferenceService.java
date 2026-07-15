package com.traffic.service;

import com.traffic.config.MockInferenceProperties;
import com.traffic.core.StreamManager;
import com.traffic.dto.StreamStatusDto;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非 AI 推理的演示数据生成器，用于联调前端与 WebSocket。
 * 待 ONNX 模型接入后可关闭 mock.inference.enabled。
 */
@Service
public class MockInferenceService {

    private static final String[] PLATES = {"京A12345", "京C88888", "京B99999", "京D11111"};
    private static final String[] ALERT_TYPES = {"parking_violation", "road_anomaly", "congestion_warning"};
    private static final String[] HEATMAP_SAMPLES = {
            "[[0,1,2],[1,3,2],[0,1,1]]",
            "[[1,2,3],[2,4,3],[1,2,2]]",
            "[[0,0,1],[1,2,1],[0,1,0]]"
    };

    private final MockInferenceProperties properties;
    private final PlateRecordService plateRecordService;
    private final AlertService alertService;
    private final CongestionService congestionService;
    private final StreamManager streamManager;
    private final RealtimePushService realtimePushService;
    private final Random random = new Random();
    private final AtomicInteger tick = new AtomicInteger();

    public MockInferenceService(MockInferenceProperties properties,
                                PlateRecordService plateRecordService,
                                AlertService alertService,
                                CongestionService congestionService,
                                StreamManager streamManager,
                                RealtimePushService realtimePushService) {
        this.properties = properties;
        this.plateRecordService = plateRecordService;
        this.alertService = alertService;
        this.congestionService = congestionService;
        this.streamManager = streamManager;
        this.realtimePushService = realtimePushService;
    }

    @Scheduled(fixedDelayString = "${mock.inference.interval-ms:8000}")
    public void emitMockEvents() {
        if (!properties.isEnabled()) {
            return;
        }

        int step = tick.getAndIncrement() % 3;
        switch (step) {
            case 0 -> plateRecordService.savePlate(PLATES[random.nextInt(PLATES.length)], true);
            case 1 -> alertService.saveAlert(
                    ALERT_TYPES[random.nextInt(ALERT_TYPES.length)],
                    "模拟告警：等待 AI 模型接入前的演示数据",
                    "{\"x\":" + (100 + random.nextInt(200)) + ",\"y\":" + (80 + random.nextInt(120)) + "}",
                    true
            );
            default -> congestionService.save(
                    2 + random.nextInt(8),
                    HEATMAP_SAMPLES[random.nextInt(HEATMAP_SAMPLES.length)],
                    true
            );
        }

        pushStreamStatus();
    }

    private void pushStreamStatus() {
        StreamStatusDto status = streamManager.getStreamStatus();
        String json = String.format(
                "{\"type\":\"stream_status\",\"timestamp\":\"%s\",\"data\":{\"online\":%s,\"fps\":%.1f,\"latencyMs\":%d}}",
                java.time.LocalDateTime.now(),
                status.isOnline(),
                status.getFps(),
                status.getLastFrameAgeMs()
        );
        realtimePushService.broadcast(json);
    }
}
