package com.traffic.service;

import com.traffic.dto.ModelInfoDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ModelService {

    private final AtomicReference<String> activeModelId = new AtomicReference<>("yolov8n");

    public List<ModelInfoDto> listModels() {
        String active = activeModelId.get();
        List<ModelInfoDto> list = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : modelDefinitions().entrySet()) {
            String id = entry.getKey();
            String[] meta = entry.getValue();
            list.add(new ModelInfoDto(id, meta[0], meta[1], id.equals(active)));
        }
        return list;
    }

    public ModelInfoDto switchModel(String modelId) {
        if (!modelDefinitions().containsKey(modelId)) {
            throw new IllegalArgumentException("未知模型: " + modelId);
        }
        activeModelId.set(modelId);
        String[] meta = modelDefinitions().get(modelId);
        return new ModelInfoDto(modelId, meta[0], meta[1], true);
    }

    public String getActiveModelId() {
        return activeModelId.get();
    }

    public ModelInfoDto getActiveModel() {
        String id = activeModelId.get();
        String[] meta = modelDefinitions().get(id);
        if (meta == null) {
            id = "yolov8n";
            meta = modelDefinitions().get(id);
        }
        return new ModelInfoDto(id, meta[0], meta[1], true);
    }

    private Map<String, String[]> modelDefinitions() {
        Map<String, String[]> map = new LinkedHashMap<>();
        map.put("yolov8n", new String[]{"小组车辆模型 v4", "sandbox-car-v4/best.pt，只检测车辆"});
        map.put("plate_det", new String[]{"小组车牌检测", "小组车辆模型 + plate_det.onnx 定位车牌"});
        map.put("plate_ocr", new String[]{"小组车牌识别 OCR", "小组车辆模型 + plate_det.onnx + ppocr.onnx"});
        map.put("anomaly", new String[]{"场景异常对比", "冻结正常基准 → 快速差分（屏蔽行人和车辆）"});
        map.put("parking", new String[]{"禁停检测", "ByteTrack Java + 禁停区停留≥20秒告警"});
        map.put("congestion", new String[]{"拥堵热力", "当前监控 ROI → 沙盘路段车数涂色"});
        return map;
    }
}
