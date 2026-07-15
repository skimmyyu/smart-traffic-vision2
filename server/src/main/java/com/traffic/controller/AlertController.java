package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.AlertCreateRequest;
import com.traffic.entity.Alert;
import com.traffic.service.AlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public Result<List<Alert>> list(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(alertService.listRecent(limit));
    }

    @PostMapping
    public Result<Alert> add(@RequestBody AlertCreateRequest request) {
        return Result.ok(alertService.add(request));
    }
}
