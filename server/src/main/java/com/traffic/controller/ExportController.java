package com.traffic.controller;

import com.traffic.entity.Alert;
import com.traffic.entity.CongestionLog;
import com.traffic.entity.PlateRecord;
import com.traffic.service.AlertService;
import com.traffic.service.CongestionService;
import com.traffic.service.PlateRecordService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final PlateRecordService plateRecordService;
    private final AlertService alertService;
    private final CongestionService congestionService;

    public ExportController(PlateRecordService plateRecordService,
                            AlertService alertService,
                            CongestionService congestionService) {
        this.plateRecordService = plateRecordService;
        this.alertService = alertService;
        this.congestionService = congestionService;
    }

    @GetMapping(value = "/plate-records", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportPlateRecords() {
        List<PlateRecord> rows = plateRecordService.listRecent(500);
        StringBuilder sb = new StringBuilder();
        sb.append("id,plate_number,pass_result,camera_id,camera_name,recognized_at\n");
        for (PlateRecord row : rows) {
            sb.append(row.getId()).append(',')
                    .append(csv(row.getPlateNumber())).append(',')
                    .append(csv(row.getPassResult())).append(',')
                    .append(csv(row.getCameraId())).append(',')
                    .append(csv(row.getCameraName())).append(',')
                    .append(csv(String.valueOf(row.getRecognizedAt()))).append('\n');
        }
        return csvResponse("plate_records.csv", sb.toString());
    }

    @GetMapping(value = "/alerts", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportAlerts() {
        List<Alert> rows = alertService.listRecent(500);
        StringBuilder sb = new StringBuilder();
        sb.append("id,alert_type,description,location,occurred_at\n");
        for (Alert row : rows) {
            sb.append(row.getId()).append(',')
                    .append(csv(row.getAlertType())).append(',')
                    .append(csv(row.getDescription())).append(',')
                    .append(csv(row.getLocation())).append(',')
                    .append(csv(String.valueOf(row.getOccurredAt()))).append('\n');
        }
        return csvResponse("alerts.csv", sb.toString());
    }

    @GetMapping(value = "/congestion", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCongestion() {
        List<CongestionLog> rows = congestionService.listRecent(500);
        StringBuilder sb = new StringBuilder();
        sb.append("id,vehicle_count,heatmap_data,stat_time\n");
        for (CongestionLog row : rows) {
            sb.append(row.getId()).append(',')
                    .append(row.getVehicleCount()).append(',')
                    .append(csv(row.getHeatmapData())).append(',')
                    .append(csv(String.valueOf(row.getStatTime()))).append('\n');
        }
        return csvResponse("congestion_logs.csv", sb.toString());
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private static ResponseEntity<byte[]> csvResponse(String filename, String content) {
        byte[] body = ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
