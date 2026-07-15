package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.PlateRecordCreateRequest;
import com.traffic.entity.PlateRecord;
import com.traffic.service.PlateRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plate-records")
public class PlateRecordController {

    private final PlateRecordService plateRecordService;

    public PlateRecordController(PlateRecordService plateRecordService) {
        this.plateRecordService = plateRecordService;
    }

    @GetMapping
    public Result<List<PlateRecord>> list(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(plateRecordService.listRecent(limit));
    }

    @PostMapping
    public Result<PlateRecord> add(@RequestBody PlateRecordCreateRequest request) {
        return Result.ok(plateRecordService.add(request));
    }
}
