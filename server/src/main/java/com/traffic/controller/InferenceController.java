package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.InferencePushRequest;
import com.traffic.service.InferenceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/inference")
public class InferenceController {

    private final InferenceService inferenceService;

    public InferenceController(InferenceService inferenceService) {
        this.inferenceService = inferenceService;
    }

    @PostMapping("/push")
    public Result<Map<String, Object>> push(@RequestBody InferencePushRequest request) {
        return Result.ok(inferenceService.pushDetection(request));
    }
}
