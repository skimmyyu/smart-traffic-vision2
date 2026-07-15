package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.StatisticsOverviewDto;
import com.traffic.service.StatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/overview")
    public Result<StatisticsOverviewDto> overview() {
        return Result.ok(statisticsService.overview());
    }
}
