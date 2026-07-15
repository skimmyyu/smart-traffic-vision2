package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.WhitelistCreateRequest;
import com.traffic.entity.Whitelist;
import com.traffic.service.WhitelistService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/whitelist")
public class WhitelistController {

    private final WhitelistService whitelistService;

    public WhitelistController(WhitelistService whitelistService) {
        this.whitelistService = whitelistService;
    }

    @GetMapping
    public Result<List<Whitelist>> list() {
        return Result.ok(whitelistService.listAll());
    }

    @PostMapping
    public Result<Whitelist> add(@RequestBody WhitelistCreateRequest request) {
        return Result.ok(whitelistService.add(request));
    }

    @DeleteMapping("/{plateNumber}")
    public Result<Void> remove(@PathVariable String plateNumber) {
        whitelistService.remove(plateNumber);
        return Result.ok(null);
    }
}
