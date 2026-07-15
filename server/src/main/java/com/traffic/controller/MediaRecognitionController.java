package com.traffic.controller;

import com.traffic.common.Result;
import com.traffic.service.MediaRecognitionService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/media-recognition")
public class MediaRecognitionController {

    private final MediaRecognitionService service;

    public MediaRecognitionController(MediaRecognitionService service) {
        this.service = service;
    }

    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> recognize(@RequestParam("file") MultipartFile file) throws Exception {
        return Result.ok(service.recognize(file));
    }

    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> submit(@RequestParam("file") MultipartFile file) throws Exception {
        return Result.ok(service.submit(file));
    }

    @GetMapping("/jobs")
    public Result<List<Map<String, Object>>> jobs() {
        return Result.ok(service.listJobs());
    }

    @GetMapping("/jobs/{id}")
    public Result<Map<String, Object>> job(@PathVariable String id) {
        return Result.ok(service.getJob(id));
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history() throws Exception {
        return Result.ok(service.listHistory());
    }

    @GetMapping("/result/{id}/{filename}")
    public ResponseEntity<Resource> result(@PathVariable String id, @PathVariable String filename) {
        Resource resource = service.resultResource(id, filename);
        MediaType type = filename.endsWith(".webm")
                ? MediaType.valueOf("video/webm")
                : filename.endsWith(".mp4") ? MediaType.valueOf("video/mp4") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type).body(resource);
    }
}
