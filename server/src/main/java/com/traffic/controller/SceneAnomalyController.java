package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.service.AnomalyInferenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/scene-anomaly")
public class SceneAnomalyController {

    private final AnomalyInferenceService anomalyInferenceService;

    public SceneAnomalyController(AnomalyInferenceService anomalyInferenceService) {
        this.anomalyInferenceService = anomalyInferenceService;
    }

    @PostMapping(value = "/baseline", consumes = MediaType.IMAGE_JPEG_VALUE)
    public Result<Map<String, Object>> captureBaselineFromDisplay(@RequestBody byte[] jpeg) {
        try {
            return Result.ok(anomalyInferenceService.captureBaselineFromJpeg(jpeg));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Result.of(400, ex.getMessage(), null);
        } catch (Exception ex) {
            return Result.of(500, baselineErrorMessage(ex), null);
        }
    }

    @PostMapping(value = "/baseline", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Map<String, Object>> captureBaseline() {
        try {
            return Result.ok(anomalyInferenceService.captureCurrentBaseline());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Result.of(400, ex.getMessage(), null);
        } catch (Exception ex) {
            return Result.of(500, baselineErrorMessage(ex), null);
        }
    }

    private static String baselineErrorMessage(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName();
        }
        return "设置基准失败: " + msg;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(anomalyInferenceService.getBaselineStatus());
    }

    @GetMapping(value = "/baseline/image", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> baselineImage() {
        byte[] jpeg = anomalyInferenceService.getBaselineJpeg();
        if (jpeg == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(jpeg);
    }
}
