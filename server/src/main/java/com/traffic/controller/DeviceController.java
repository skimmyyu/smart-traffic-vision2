package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.DeviceCreateRequest;
import com.traffic.entity.Device;
import com.traffic.service.DeviceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public Result<List<Device>> list() {
        return Result.ok(deviceService.listAll());
    }

    @PostMapping
    public Result<Device> register(@RequestBody DeviceCreateRequest request) {
        return Result.ok(deviceService.register(request));
    }

    @PostMapping("/{id}/heartbeat")
    public Result<Device> heartbeat(@PathVariable Long id) {
        return Result.ok(deviceService.heartbeat(id));
    }
}
