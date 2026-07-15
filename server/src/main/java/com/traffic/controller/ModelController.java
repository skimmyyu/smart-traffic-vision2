package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.ModelInfoDto;
import com.traffic.service.ModelService;
import com.traffic.service.ModelParameterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelService modelService;
    private final ModelParameterService parameterService;

    public ModelController(ModelService modelService, ModelParameterService parameterService) {
        this.modelService = modelService;
        this.parameterService = parameterService;
    }

    @GetMapping
    public Result<List<ModelInfoDto>> list() {
        return Result.ok(modelService.listModels());
    }

    @GetMapping("/active")
    public Result<ModelInfoDto> active() {
        return Result.ok(modelService.getActiveModel());
    }

    @GetMapping("/active-id")
    public Result<Map<String, String>> activeId() {
        return Result.ok(Map.of("modelId", modelService.getActiveModelId()));
    }

    @PostMapping("/switch/{modelId}")
    public Result<ModelInfoDto> switchModel(@PathVariable String modelId) {
        return Result.ok(modelService.switchModel(modelId));
    }

    @GetMapping("/{modelId}/parameters")
    public Result<Map<String, Object>> parameters(@PathVariable String modelId) {
        return Result.ok(parameterService.get(modelId));
    }

    @PutMapping("/{modelId}/parameters")
    public Result<Map<String, Object>> updateParameters(@PathVariable String modelId,
                                                        @RequestBody Map<String, Object> body) {
        return Result.ok(parameterService.update(modelId, body));
    }
}
