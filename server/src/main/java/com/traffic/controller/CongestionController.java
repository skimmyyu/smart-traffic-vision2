package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.entity.CongestionLog;
import com.traffic.service.CongestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/congestion")
public class CongestionController {

    private final CongestionService congestionService;

    public CongestionController(CongestionService congestionService) {
        this.congestionService = congestionService;
    }

    @GetMapping("/logs")
    public Result<List<CongestionLog>> logs(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(congestionService.listRecent(limit));
    }

    @GetMapping("/latest")
    public Result<CongestionLog> latest() {
        return Result.ok(congestionService.latest());
    }

    @GetMapping("/latest-snapshot")
    public Result<Map<String, Object>> latestSnapshot() {
        return Result.ok(congestionService.latestRoadSnapshotPayload());
    }
}
