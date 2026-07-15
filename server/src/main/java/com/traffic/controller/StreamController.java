package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.dto.StreamChannelDto;
import com.traffic.dto.StreamSwitchResultDto;
import com.traffic.service.CameraSourceService;
import com.traffic.service.StreamChannelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final StreamChannelService streamChannelService;
    private final CameraSourceService cameraSourceService;

    public StreamController(StreamChannelService streamChannelService,
                            CameraSourceService cameraSourceService) {
        this.streamChannelService = streamChannelService;
        this.cameraSourceService = cameraSourceService;
    }

    @GetMapping("/channels")
    public Result<List<StreamChannelDto>> channels() {
        return Result.ok(streamChannelService.listChannels());
    }

    @GetMapping("/current")
    public Result<StreamSwitchResultDto> current() {
        return Result.ok(streamChannelService.getCurrent());
    }

    @PostMapping("/switch/{channelId}")
    public Result<StreamSwitchResultDto> switchChannel(@PathVariable String channelId) {
        if (!CameraSourceService.SANDBOX.equals(cameraSourceService.getActiveSource())) {
            return Result.fail("当前非沙盘摄像头模式，无法切换沙盘通道");
        }
        return Result.ok(streamChannelService.switchChannel(channelId));
    }
}
