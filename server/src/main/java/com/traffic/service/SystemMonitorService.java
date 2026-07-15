package com.traffic.service;

import com.traffic.core.StreamManager;
import com.traffic.dto.SystemStatusDto;
import com.traffic.dto.StreamStatusDto;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

@Service
public class SystemMonitorService {

    private final StreamManager streamManager;

    public SystemMonitorService(StreamManager streamManager) {
        this.streamManager = streamManager;
    }

    public SystemStatusDto getSystemStatus() {
        SystemStatusDto dto = new SystemStatusDto();
        dto.setCpuUsage(round1(getCpuUsagePercent()));
        dto.setMemoryUsage(round1(getMemoryUsagePercent()));
        dto.setGpuUsage(0.0);
        dto.setStreamOnline(streamManager.isOnline());
        dto.setFps(streamManager.getFps());
        return dto;
    }

    public StreamStatusDto getStreamStatus() {
        return streamManager.getStreamStatus();
    }

    private double getCpuUsagePercent() {
        OperatingSystemMXBean osBean =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double load = osBean.getCpuLoad();
        if (load < 0) {
            return 0.0;
        }
        return load * 100.0;
    }

    private double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long max = runtime.maxMemory();
        if (max <= 0) {
            return 0.0;
        }
        long used = total - free;
        return used * 100.0 / max;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
