package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.entity.CameraRoadRoi;
import com.traffic.service.CameraRoadRoiService;
import com.traffic.service.RoadCongestionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RoadCongestionController {

    private final CameraRoadRoiService cameraRoadRoiService;
    private final RoadCongestionService roadCongestionService;

    public RoadCongestionController(CameraRoadRoiService cameraRoadRoiService,
                                    RoadCongestionService roadCongestionService) {
        this.cameraRoadRoiService = cameraRoadRoiService;
        this.roadCongestionService = roadCongestionService;
    }

    @GetMapping("/camera-rois")
    public Result<List<CameraRoadRoi>> listRois(@RequestParam String channelId) {
        return Result.ok(cameraRoadRoiService.listByChannel(channelId));
    }

    @PostMapping("/camera-rois")
    public Result<CameraRoadRoi> createRoi(@RequestBody Map<String, Object> body) {
        return Result.ok(cameraRoadRoiService.create(body));
    }

    @PutMapping("/camera-rois/{id}")
    public Result<CameraRoadRoi> updateRoi(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(cameraRoadRoiService.update(id, body));
    }

    @DeleteMapping("/camera-rois/{id}")
    public Result<Void> deleteRoi(@PathVariable Long id) {
        cameraRoadRoiService.delete(id);
        return Result.ok(null);
    }

    @GetMapping("/road-congestion/current")
    public Result<Map<String, Object>> current() {
        return Result.ok(roadCongestionService.snapshot());
    }

    @GetMapping("/road-congestion/latest-snapshot")
    public Result<Map<String, Object>> latestSnapshot() {
        return Result.ok(roadCongestionService.latestSnapshotFromDb());
    }

    @PostMapping("/road-congestion/persist")
    public Result<Map<String, Object>> persist() {
        return Result.ok(roadCongestionService.forcePersistSnapshot());
    }

    @GetMapping("/road-congestion/db-context")
    public Result<Map<String, Object>> dbContext() {
        return Result.ok(roadCongestionService.dbContext());
    }
}
