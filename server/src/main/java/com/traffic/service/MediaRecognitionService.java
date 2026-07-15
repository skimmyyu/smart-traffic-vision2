package com.traffic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.entity.Whitelist;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

@Service
public class MediaRecognitionService {

    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "bmp", "webp", "mp4", "avi", "mov", "mkv", "webm");
    private final ObjectMapper objectMapper;
    private final WhitelistService whitelistService;
    private final Path projectRoot = Paths.get("..").toAbsolutePath().normalize();
    private final Path resultRoot = Paths.get("target", "media-recognition").toAbsolutePath().normalize();
    private final ExecutorService recognitionPool = Executors.newFixedThreadPool(2);
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();

    public MediaRecognitionService(ObjectMapper objectMapper, WhitelistService whitelistService) {
        this.objectMapper = objectMapper;
        this.whitelistService = whitelistService;
    }

    public Map<String, Object> submit(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片或视频");
        String original = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String ext = extension(original);
        if (!ALLOWED.contains(ext)) throw new IllegalArgumentException("不支持的文件格式: " + ext);
        String id = UUID.randomUUID().toString().replace("-", "");
        Path dir = resultRoot.resolve(id).normalize();
        if (!dir.startsWith(resultRoot)) throw new IllegalStateException("结果目录无效");
        Files.createDirectories(dir);
        Path input = dir.resolve("input." + ext);
        file.transferTo(input);

        Map<String, Object> job = new ConcurrentHashMap<>();
        job.put("jobId", id);
        job.put("originalName", original);
        job.put("mediaType", Set.of("mp4", "avi", "mov", "mkv", "webm").contains(ext) ? "video" : "image");
        job.put("status", "queued");
        job.put("progress", 0);
        job.put("message", "等待识别");
        job.put("createdAt", System.currentTimeMillis());
        jobs.put(id, job);
        recognitionPool.submit(() -> runJob(id, original, input, dir));
        return new HashMap<>(job);
    }

    public List<Map<String, Object>> listJobs() {
        return jobs.values().stream()
                .map(job -> (Map<String, Object>) new HashMap<>(job))
                .sorted(Comparator.comparingLong(MediaRecognitionService::createdAt).reversed())
                .toList();
    }

    public Map<String, Object> getJob(String id) {
        Map<String, Object> job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("识别任务不存在");
        return new HashMap<>(job);
    }

    private void runJob(String id, String original, Path input, Path dir) {
        Map<String, Object> job = jobs.get(id);
        job.put("status", "processing");
        job.put("message", "正在加载模型");
        try {
            Map<String, Object> result = executeRecognition(id, original, input, dir, job);
            job.put("status", "completed");
            job.put("progress", 100);
            job.put("message", "识别完成");
            job.put("result", result);
            job.put("finishedAt", System.currentTimeMillis());
        } catch (Exception e) {
            job.put("status", "failed");
            job.put("message", e.getMessage() == null ? "识别失败" : e.getMessage());
            job.put("finishedAt", System.currentTimeMillis());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeRecognition(String id, String original, Path input, Path dir,
                                                    Map<String, Object> job) throws Exception {
        Path python = projectRoot.resolve("ai/yolov8/.venv/Scripts/python.exe");
        Path script = projectRoot.resolve("ai/yolov8/scripts/offline_recognize.py");
        Path weights = projectRoot.resolve("ai/yolov8/runs/detect/sandbox-car-v4/weights/best.pt");
        if (!Files.isRegularFile(python) || !Files.isRegularFile(script) || !Files.isRegularFile(weights)) {
            throw new IllegalStateException("离线识别运行环境或模型文件缺失");
        }
        ProcessBuilder pb = new ProcessBuilder(python.toString(), script.toString(),
                "--input", input.toString(), "--output-dir", dir.toString(), "--weights", weights.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(dir.resolve("recognition.log").toFile());
        Process process = pb.start();
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        Path progressFile = dir.resolve("progress.json");
        while (process.isAlive() && System.currentTimeMillis() < deadline) {
            if (job != null && Files.isRegularFile(progressFile)) {
                try {
                    Map<String, Object> progress = objectMapper.readValue(Files.readString(progressFile), new TypeReference<>() {});
                    job.put("progress", progress.getOrDefault("progress", job.get("progress")));
                    job.put("message", progress.getOrDefault("message", "正在识别"));
                } catch (Exception ignored) { }
            }
            Thread.sleep(300);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            throw new IllegalStateException("识别超时，请缩短视频后重试");
        }
        if (process.exitValue() != 0) {
            String log = Files.isRegularFile(dir.resolve("recognition.log")) ? Files.readString(dir.resolve("recognition.log")) : "";
            throw new IllegalStateException("识别失败: " + tail(log, 500));
        }
        Map<String, Object> result = objectMapper.readValue(Files.readString(dir.resolve("result.json")), new TypeReference<>() {});
        enrichAndPersist(result, id, original, dir);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void enrichAndPersist(Map<String, Object> result, String id, String original, Path dir) throws Exception {
        Set<String> allowedPlates = new HashSet<>();
        for (Whitelist w : whitelistService.listAll()) allowedPlates.add(w.getPlateNumber().toUpperCase());
        Object platesObj = result.get("plates");
        if (platesObj instanceof List<?> plates) {
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Object item : plates) {
                Map<String, Object> p = new HashMap<>((Map<String, Object>) item);
                String number = String.valueOf(p.getOrDefault("plateNumber", "")).toUpperCase();
                boolean allow = allowedPlates.contains(number);
                p.put("decision", allow ? "allow" : "deny");
                p.put("decisionText", allow ? "白名单·允许通行" : "非白名单·拒绝通行");
                enriched.add(p);
            }
            result.put("plates", enriched);
        }
        result.put("resultId", id);
        result.put("originalName", original);
        result.put("resultUrl", "/api/media-recognition/result/" + id + "/" + result.get("annotatedFile"));
        result.put("createdAt", System.currentTimeMillis());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("metadata.json").toFile(), result);
    }

    @PreDestroy
    public void shutdown() { recognitionPool.shutdownNow(); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> recognize(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片或视频");
        String original = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String ext = extension(original);
        if (!ALLOWED.contains(ext)) throw new IllegalArgumentException("不支持的文件格式: " + ext);

        String id = UUID.randomUUID().toString().replace("-", "");
        Path dir = resultRoot.resolve(id).normalize();
        if (!dir.startsWith(resultRoot)) throw new IllegalStateException("结果目录无效");
        Files.createDirectories(dir);
        Path input = dir.resolve("input." + ext);
        file.transferTo(input);

        Path python = projectRoot.resolve("ai/yolov8/.venv/Scripts/python.exe");
        Path script = projectRoot.resolve("ai/yolov8/scripts/offline_recognize.py");
        Path weights = projectRoot.resolve("ai/yolov8/runs/detect/sandbox-car-v4/weights/best.pt");
        if (!Files.isRegularFile(python) || !Files.isRegularFile(script) || !Files.isRegularFile(weights)) {
            throw new IllegalStateException("离线识别运行环境或模型文件缺失");
        }

        ProcessBuilder pb = new ProcessBuilder(
                python.toString(), script.toString(),
                "--input", input.toString(), "--output-dir", dir.toString(),
                "--weights", weights.toString()
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(dir.resolve("recognition.log").toFile());
        Process process = pb.start();
        if (!process.waitFor(Duration.ofMinutes(10).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("识别超时，请缩短视频后重试");
        }
        if (process.exitValue() != 0) {
            String log = Files.readString(dir.resolve("recognition.log"));
            throw new IllegalStateException("识别失败: " + tail(log, 500));
        }

        Map<String, Object> result = objectMapper.readValue(
                Files.readString(dir.resolve("result.json")), new TypeReference<>() {});
        Set<String> allowedPlates = new HashSet<>();
        for (Whitelist w : whitelistService.listAll()) allowedPlates.add(w.getPlateNumber().toUpperCase());
        Object platesObj = result.get("plates");
        if (platesObj instanceof List<?> plates) {
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Object item : plates) {
                Map<String, Object> p = new HashMap<>((Map<String, Object>) item);
                String number = String.valueOf(p.getOrDefault("plateNumber", "")).toUpperCase();
                boolean allow = allowedPlates.contains(number);
                p.put("decision", allow ? "allow" : "deny");
                p.put("decisionText", allow ? "白名单·允许通行" : "非白名单·拒绝通行");
                enriched.add(p);
            }
            result.put("plates", enriched);
        }
        result.put("resultId", id);
        result.put("originalName", original);
        result.put("resultUrl", "/api/media-recognition/result/" + id + "/" + result.get("annotatedFile"));
        result.put("createdAt", System.currentTimeMillis());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("metadata.json").toFile(), result);
        return result;
    }

    public List<Map<String, Object>> listHistory() throws Exception {
        if (!Files.isDirectory(resultRoot)) return List.of();
        List<Map<String, Object>> history = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(resultRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                Path metadata = dir.resolve("metadata.json");
                // Records created before history persistence did not contain
                // filenames, decisions or timestamps and were internal smoke
                // artifacts, so only expose complete persisted records.
                if (!Files.isRegularFile(metadata)) continue;
                Path source = metadata;
                try {
                    Map<String, Object> item = objectMapper.readValue(
                            Files.readString(source), new TypeReference<>() {});
                    item.putIfAbsent("resultId", id);
                    item.putIfAbsent("originalName", "历史识别素材");
                    item.putIfAbsent("createdAt", Files.getLastModifiedTime(source).toMillis());
                    Object annotated = item.get("annotatedFile");
                    if (annotated != null) {
                        item.putIfAbsent("resultUrl", "/api/media-recognition/result/" + id + "/" + annotated);
                    }
                    history.add(item);
                } catch (Exception ignored) {
                    // One damaged record must not hide the remaining history.
                }
            }
        }
        history.sort(Comparator.comparingLong(MediaRecognitionService::createdAt).reversed());
        return history.size() > 100 ? history.subList(0, 100) : history;
    }

    public Resource resultResource(String id, String filename) {
        if (!id.matches("[a-f0-9]{32}") || !filename.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("结果地址无效");
        }
        Path path = resultRoot.resolve(id).resolve(filename).normalize();
        if (!path.startsWith(resultRoot) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("识别结果不存在");
        }
        return new FileSystemResource(path);
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static long createdAt(Map<String, Object> item) {
        Object value = item.get("createdAt");
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
