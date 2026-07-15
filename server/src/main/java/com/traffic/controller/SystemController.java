package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.SystemStatusDto;
import com.traffic.dto.StreamStatusDto;
import com.traffic.service.SystemMonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemMonitorService systemMonitorService;

    public SystemController(SystemMonitorService systemMonitorService) {
        this.systemMonitorService = systemMonitorService;
    }

    @GetMapping("/status")
    public Result<SystemStatusDto> status() {
        return Result.ok(systemMonitorService.getSystemStatus());
    }

    @GetMapping("/stream")
    public Result<StreamStatusDto> stream() {
        return Result.ok(systemMonitorService.getStreamStatus());
    }
}
