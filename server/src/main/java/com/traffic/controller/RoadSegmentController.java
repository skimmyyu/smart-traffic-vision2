package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.entity.RoadSegment;
import com.traffic.service.RoadSegmentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/road-segments")
public class RoadSegmentController {

    private final RoadSegmentService roadSegmentService;

    public RoadSegmentController(RoadSegmentService roadSegmentService) {
        this.roadSegmentService = roadSegmentService;
    }

    @GetMapping
    public Result<List<RoadSegment>> list() {
        return Result.ok(roadSegmentService.listAll());
    }

    @PostMapping
    public Result<RoadSegment> create(@RequestBody Map<String, Object> body) {
        return Result.ok(roadSegmentService.create(body));
    }

    @PutMapping("/{id}")
    public Result<RoadSegment> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(roadSegmentService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roadSegmentService.delete(id);
        return Result.ok(null);
    }
}
