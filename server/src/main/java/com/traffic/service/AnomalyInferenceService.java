package com.traffic.service;

import com.traffic.ai.BackgroundDiffAssist;
import com.traffic.ai.DetectionBox;
import com.traffic.ai.YoloV8OnnxDetector;
import com.traffic.config.AnomalyProperties;
import com.traffic.core.StreamManager;
import com.traffic.dto.InferencePushRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Road anomaly pipeline:
 * train on debris appearance → learn/freeze background → diff change ROIs →
 * crop ROI to anomaly.onnx → if debris, draw on original frame.
 */
@Service
public class AnomalyInferenceService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyInferenceService.class);

    private final AnomalyProperties properties;
    private final ModelService modelService;
    private final StreamManager streamManager;
    private final StreamChannelService streamChannelService;
    private final CameraRoadRoiService cameraRoadRoiService;
    private final CameraSourceService cameraSourceService;
    private final InferenceService inferenceService;
    private final AlertService alertService;

    private volatile YoloV8OnnxDetector detector;
    private volatile YoloV8OnnxDetector vehicleDetector;
    private volatile BackgroundDiffAssist bgAssist;
    private final AtomicLong lastAlertMs = new AtomicLong(0);
    private final AtomicLong lastLogMs = new AtomicLong(0);
    private volatile long lastSwitchAt = -1L;
    private volatile String lastMode = "";
    private volatile String lastCameraSource = "";
    private volatile long baselineSetAt = 0L;
    private volatile List<DetectionBox> baselineDynamicMasks = List.of();
    private volatile byte[] baselineJpeg;
    private static final Set<String> DYNAMIC_CLASSES = Set.of(
            "person", "car", "bus", "truck", "motorcycle", "bicycle", "traffic light"
    );
    /** Person-like classes: only mask when present at baseline (avoid standee false positives). */
    private static final Set<String> PERSON_LIKE_CLASSES = Set.of("person");
    /** Brief hold only while ROI still present but model flickers (~0.45s @150ms). */
    private static final int STICKY_HOLD_FRAMES = 3;
    /** Hold last box through brief diff gaps (~0.45s @150ms). */
    private static final int DISPLAY_HOLD_FRAMES = 3;
    private final List<StickyDet> stickyDets = new ArrayList<>();
    private final List<StickyDet> displaySticky = new ArrayList<>();

    private static final class StickyDet {
        int x1, y1, x2, y2;
        float conf;
        int hold;
        boolean matched;
    }

    public AnomalyInferenceService(AnomalyProperties properties,
                                   ModelService modelService,
                                   StreamManager streamManager,
                                   StreamChannelService streamChannelService,
                                   CameraRoadRoiService cameraRoadRoiService,
                                   CameraSourceService cameraSourceService,
                                   InferenceService inferenceService,
                                   AlertService alertService) {
        this.properties = properties;
        this.modelService = modelService;
        this.streamManager = streamManager;
        this.streamChannelService = streamChannelService;
        this.cameraRoadRoiService = cameraRoadRoiService;
        this.cameraSourceService = cameraSourceService;
        this.inferenceService = inferenceService;
        this.alertService = alertService;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("Anomaly ONNX inference disabled by config");
            return;
        }
        try {
            ensureDetector();
            log.info("Anomaly ONNX+bgDiff ready (switch mode to 'anomaly' to run)");
        } catch (Exception ex) {
            log.warn("Anomaly model not loaded yet: {} (will retry on first tick)", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${anomaly.interval-ms:350}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        String mode = modelService.getActiveModelId();
        if (!"anomaly".equalsIgnoreCase(mode)) {
            lastMode = mode == null ? "" : mode;
            return;
        }

        // Entering anomaly mode → relearn background
        if (!"anomaly".equalsIgnoreCase(lastMode)) {
            resetBackground("mode -> anomaly");
        }
        lastMode = "anomaly";

        String cameraSource = cameraSourceService.getActiveSource();
        if (!cameraSource.equals(lastCameraSource)) {
            if (!lastCameraSource.isBlank()) {
                resetBackground("camera source -> " + cameraSource);
            }
            lastCameraSource = cameraSource;
        }

        // Channel switch → relearn background (RTSP sources only; local/phone JPEG must keep manual baseline)
        if (isRtspBackedSource(cameraSource)) {
            long switchedAt = 0L;
            try {
                switchedAt = streamChannelService.getCurrent().getSwitchedAt();
            } catch (Exception ignored) {
                // keep previous
            }
            if (switchedAt > 0 && switchedAt != lastSwitchAt) {
                if (lastSwitchAt > 0) {
                    resetBackground("channel switch");
                }
                lastSwitchAt = switchedAt;
            }
        }

        try {
            ensureDetector();
        } catch (Exception ex) {
            logEvery(10_000, "Anomaly detector unavailable: " + ex.getMessage());
            return;
        }

        BufferedImage frame = currentFrame();
        if (frame == null) {
            logEvery(5_000, "Anomaly waiting for " + cameraSource + " frame...");
            return;
        }

        try {
            List<DetectionBox> vehicles = detectVehicles(frame);
            List<DetectionBox> dynamicMasks = buildDynamicMasks(vehicles);
            if (properties.isBgDiffEnabled() && bgAssist != null) {
                boolean uploadedFrame = CameraSourceService.LOCAL.equals(cameraSource)
                        || CameraSourceService.PHONE.equals(cameraSource);
                bgAssist.setVolatilityDecay(uploadedFrame ? 0.35f : 0.25f);
            }

            // Scene-comparison pipeline:
            //  1) freeze a normal reference frame
            //  2) mask current + baseline people/vehicles
            //  3) report only persistent differences against that reference
            List<DetectionBox> bgBoxes = List.of();
            boolean bgReady = true;
            if (properties.isBgDiffEnabled() && bgAssist != null) {
                bgAssist.setMinAreaRatio(properties.getBgMinAreaRatio());
                bgBoxes = bgAssist.update(frame, dynamicMasks);
                bgReady = bgAssist.isReady();
                if (bgReady && (bgAssist.getLastRawBoxCount() > 0 || bgAssist.getLastForegroundRatio() > 0.001f)) {
                    logEvery(3_000, String.format(
                            "Anomaly realtime-diff foreground=%.3f%% raw=%d confirmed=%d masks=%d",
                            bgAssist.getLastForegroundRatio() * 100f,
                            bgAssist.getLastRawBoxCount(),
                            bgAssist.getLastConfirmedBoxCount(),
                            dynamicMasks.size()));
                }
                if (!bgReady) {
                    logEvery(3_000, "Anomaly bgDiff warming up (" + bgAssist.warmupLeft() + ")");
                }
            }

            List<DetectionBox> fused;
            if (!properties.isBgDiffEnabled()) {
                // Fallback switch: pure full-frame model
                List<DetectionBox> yoloRaw = detector.detect(frame);
                yoloRaw = suppressOverlaps(yoloRaw, vehicles, properties.getVehicleSuppressIou());
                fused = filterByHard(yoloRaw);
            } else if (!bgReady) {
                // Still learning background — do not judge debris yet
                fused = List.of();
            } else {
                fused = backgroundChanges(bgBoxes, dynamicMasks, frame.getWidth(), frame.getHeight());
            }

            float minDisplayConfidence = Math.max(0f, Math.min(1f, properties.getMinDisplayConfidence()));
            fused = fused.stream()
                    .filter(box -> box.getConfidence() >= minDisplayConfidence)
                    .toList();
            fused = stabilizeDisplay(fused);
            fused = limitBoxes(fused, properties.getMaxBoxes());
            float upscale = streamManager.getFrameUpscaleFactor();
            int pushW = streamManager.getNativeFrameWidth() > 0
                    ? streamManager.getNativeFrameWidth() : frame.getWidth();
            int pushH = streamManager.getNativeFrameHeight() > 0
                    ? streamManager.getNativeFrameHeight() : frame.getHeight();
            if (upscale > 1.001f) {
                fused = upscaleBoxes(fused, upscale);
            }
            pushResult(pushW, pushH, fused, bgReady);
            maybeAlert(fused, frame.getWidth(), frame.getHeight());
        } catch (Exception ex) {
            log.warn("Anomaly inference failed: {}", ex.toString());
        }
    }

    private List<DetectionBox> filterByHard(List<DetectionBox> yolo) {
        float hard = properties.getConfThreshold();
        List<DetectionBox> out = new ArrayList<>();
        for (DetectionBox y : yolo) {
            if (y.getConfidence() >= hard) {
                out.add(y);
            }
        }
        return out;
    }

    /**
     * Diff ROIs → crop → anomaly.onnx.
     * Hysteresis: enter at hard-ish thr (cut 0.2–0.3 FPs), keep with slightly softer thr.
     * Sticky is short and only while the change ROI still exists (no long ghost after remove).
     */
    private List<DetectionBox> judgeDiffRegionsWithModel(BufferedImage frame,
                                                        List<DetectionBox> bg,
                                                        List<DetectionBox> vehicles) throws Exception {
        float enterThr = Math.max(properties.getConfThreshold(), properties.getSoftConfThreshold());
        float keepThr = Math.max(0.28f, enterThr - 0.08f);

        for (StickyDet s : stickyDets) {
            s.matched = false;
        }

        List<DetectionBox> out = new ArrayList<>();
        int yes = 0;
        int stickyKept = 0;
        int no = 0;

        List<DetectionBox> proposals = new ArrayList<>(bg);
        proposals.sort(Comparator
                .comparingInt(DetectionBox::getTrackHits).reversed()
                .thenComparing(b -> (b.getX2() - b.getX1()) * (b.getY2() - b.getY1()),
                        Comparator.reverseOrder()));
        if (proposals.size() > properties.getMaxBoxes()) {
            proposals = proposals.subList(0, properties.getMaxBoxes());
        }

        for (DetectionBox proposal : proposals) {
            StickyDet sticky = findSticky(proposal);
            DetectionBox scored = scoreCropRegion(frame, proposal);
            float score = scored != null ? scored.getConfidence() : 0f;
            boolean isSticky = sticky != null && sticky.hold > 0;
            float need = isSticky ? keepThr : enterThr;

            if (scored != null && score >= need) {
                // Smooth confidence so UI does not jump 20↔60 every frame
                float conf = score;
                if (sticky != null) {
                    conf = 0.65f * sticky.conf + 0.35f * score;
                }
                DetectionBox emit = new DetectionBox(
                        "debris",
                        conf,
                        proposal.getX1(), proposal.getY1(), proposal.getX2(), proposal.getY2(),
                        proposal.getTrackHits()
                );
                emit = suppressIfVehicle(emit, vehicles, properties.getVehicleSuppressIou());
                if (emit == null) {
                    no++;
                    continue;
                }
                upsertSticky(emit, conf);
                out.add(emit);
                yes++;
                continue;
            }

            // Very short hold: ROI still here, model missed 1–2 frames only
            if (sticky != null && sticky.hold > 0) {
                sticky.matched = true;
                sticky.hold--;
                sticky.x1 = proposal.getX1();
                sticky.y1 = proposal.getY1();
                sticky.x2 = proposal.getX2();
                sticky.y2 = proposal.getY2();
                out.add(new DetectionBox(
                        "debris", sticky.conf,
                        sticky.x1, sticky.y1, sticky.x2, sticky.y2,
                        proposal.getTrackHits()));
                stickyKept++;
                continue;
            }

            no++;
        }

        // ROI gone (object removed) → drop sticky immediately (no multi-second ghost)
        stickyDets.removeIf(s -> !s.matched);

        logEvery(2_000, String.format(
                "Anomaly ROI-judge yes=%d sticky=%d no=%d proposals=%d out=%d enter=%.2f",
                yes, stickyKept, no, proposals.size(), out.size(), enterThr));
        return out;
    }

    /**
     * Crop an expanded ROI around the bg proposal and run anomaly.onnx on that patch only.
     * Returns best debris box in full-frame coordinates, or a zero-area sentinel with conf only.
     */
    private DetectionBox scoreCropRegion(BufferedImage frame, DetectionBox proposal) throws Exception {
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        DetectionBox region = expandBox(proposal, 0.55f, fw, fh);
        region = enforceMinCrop(region, fw, fh, 80);

        int x1 = clamp(region.getX1(), 0, fw - 1);
        int y1 = clamp(region.getY1(), 0, fh - 1);
        int x2Incl = clamp(region.getX2(), x1, fw - 1);
        int y2Incl = clamp(region.getY2(), y1, fh - 1);
        int cw = x2Incl - x1 + 1;
        int ch = y2Incl - y1 + 1;
        if (cw < 8 || ch < 8) {
            return null;
        }

        BufferedImage crop = frame.getSubimage(x1, y1, cw, ch);
        List<DetectionBox> dets = detector.detect(crop);
        if (dets.isEmpty()) {
            return null;
        }

        DetectionBox best = dets.get(0);
        for (DetectionBox d : dets) {
            if (d.getConfidence() > best.getConfidence()) {
                best = d;
            }
        }

        // Map crop-local inclusive xyxy → full frame
        int fx1 = clamp(x1 + best.getX1(), 0, fw - 1);
        int fy1 = clamp(y1 + best.getY1(), 0, fh - 1);
        int fx2 = clamp(x1 + best.getX2(), fx1, fw - 1);
        int fy2 = clamp(y1 + best.getY2(), fy1, fh - 1);
        return new DetectionBox("debris", best.getConfidence(), fx1, fy1, fx2, fy2, proposal.getTrackHits());
    }

    private static DetectionBox enforceMinCrop(DetectionBox b, int fw, int fh, int minSide) {
        int bw = Math.max(1, b.getX2() - b.getX1() + 1);
        int bh = Math.max(1, b.getY2() - b.getY1() + 1);
        int needX = Math.max(0, minSide - bw);
        int needY = Math.max(0, minSide - bh);
        if (needX == 0 && needY == 0) {
            return b;
        }
        int x1 = Math.max(0, b.getX1() - needX / 2);
        int y1 = Math.max(0, b.getY1() - needY / 2);
        int x2 = Math.min(fw - 1, b.getX2() + (needX - needX / 2));
        int y2 = Math.min(fh - 1, b.getY2() + (needY - needY / 2));
        return new DetectionBox(b.getClassName(), b.getConfidence(), x1, y1, x2, y2, b.getTrackHits());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private DetectionBox suppressIfVehicle(DetectionBox d, List<DetectionBox> vehicles, float thr) {
        if (d == null || vehicles == null || vehicles.isEmpty()) {
            return d;
        }
        for (DetectionBox v : vehicles) {
            if (iou(d, v) >= thr || centerInside(d, v)) {
                return null;
            }
        }
        return d;
    }

    private StickyDet findSticky(DetectionBox box) {
        StickyDet best = null;
        float bestIou = 0f;
        for (StickyDet s : stickyDets) {
            float i = iou(box, new DetectionBox("debris", s.conf, s.x1, s.y1, s.x2, s.y2));
            if (i >= 0.15f && i > bestIou) {
                bestIou = i;
                best = s;
            }
        }
        return best;
    }

    private void upsertSticky(DetectionBox box, float conf) {
        StickyDet s = findSticky(box);
        if (s == null) {
            s = new StickyDet();
            stickyDets.add(s);
        }
        s.x1 = box.getX1();
        s.y1 = box.getY1();
        s.x2 = box.getX2();
        s.y2 = box.getY2();
        s.conf = Math.max(s.conf, conf);
        s.hold = STICKY_HOLD_FRAMES;
        s.matched = true;
    }

    private static DetectionBox expandBox(DetectionBox b, float ratio, int maxW, int maxH) {
        int bw = Math.max(1, b.getX2() - b.getX1());
        int bh = Math.max(1, b.getY2() - b.getY1());
        int padX = Math.round(bw * ratio);
        int padY = Math.round(bh * ratio);
        int x1 = Math.max(0, b.getX1() - padX);
        int y1 = Math.max(0, b.getY1() - padY);
        int x2 = b.getX2() + padX;
        int y2 = b.getY2() + padY;
        if (maxW < Integer.MAX_VALUE) {
            x2 = Math.min(maxW - 1, x2);
        }
        if (maxH < Integer.MAX_VALUE) {
            y2 = Math.min(maxH - 1, y2);
        }
        return new DetectionBox(b.getClassName(), b.getConfidence(), x1, y1, x2, y2);
    }

    private synchronized void ensureBgAssist() {
        if (bgAssist == null && properties.isBgDiffEnabled()) {
            BackgroundDiffAssist.Config cfg = new BackgroundDiffAssist.Config();
            cfg.warmupFrames = properties.resolveBgWarmupFrames();
            cfg.diffThreshold = properties.getBgDiffThreshold();
            cfg.maxFgRatio = properties.getBgMaxFgRatio();
            cfg.minAreaRatio = properties.getBgMinAreaRatio();
            cfg.maxAreaRatio = properties.getBgMaxAreaRatio();
            cfg.persistHits = properties.getBgPersistHits();
            cfg.missLimit = 4;
            cfg.matchIou = 0.12f;
            cfg.maxFgHardRatio = 0.40f;
            cfg.maxBoxes = Math.max(properties.getMaxBoxes() * 2, 8);
            cfg.maxAspect = 3.5f;
            bgAssist = new BackgroundDiffAssist(cfg);
            log.info("Background-diff: learn ~{}s ({} frames @ {}ms), then FREEZE baseline",
                    properties.getBgLearnSeconds() > 0 ? properties.getBgLearnSeconds() : (cfg.warmupFrames * properties.getIntervalMs() / 1000f),
                    cfg.warmupFrames,
                    properties.getIntervalMs());
        }
    }

    private synchronized void ensureDetector() throws Exception {
        if (detector == null) {
            Path model = YoloV8OnnxDetector.resolveModelPath(properties.getModelPath(), "anomaly.onnx");
            String[] names = properties.getClassNames().split("\\s*,\\s*");
            // Load with soft threshold so mid-score candidates can be rescued by bg-diff
            float loadConf = Math.min(0.05f, Math.min(properties.getSoftConfThreshold(), properties.getConfThreshold()));
            detector = new YoloV8OnnxDetector(
                    model,
                    properties.getImgsz(),
                    loadConf,
                    properties.getIouThreshold(),
                    Math.max(properties.getMaxBoxes() * 3, 15),
                    names
            );
        }
        if (vehicleDetector == null) {
            try {
                Path vModel = YoloV8OnnxDetector.resolveModelPath(
                        properties.getVehicleModelPath(), "vehicle.onnx");
                String[] vNames = properties.getVehicleClassNames().split("\\s*,\\s*");
                vehicleDetector = new YoloV8OnnxDetector(
                        vModel,
                        properties.getImgsz(),
                        properties.getVehicleConfThreshold(),
                        properties.getIouThreshold(),
                        20,
                        vNames
                );
                log.info("Vehicle mask ONNX ready for anomaly suppress");
            } catch (Exception ex) {
                log.warn("Vehicle mask unavailable: {}", ex.getMessage());
            }
        }
        ensureBgAssist();
    }

    /** Capture baseline from server RTSP / camera frame buffer. */
    public synchronized Map<String, Object> captureCurrentBaseline() throws Exception {
        BufferedImage frame = currentFrame();
        if (frame == null) {
            throw new IllegalStateException("当前摄像头还没有可用画面");
        }
        return applyBaselineFrame(frame, "server");
    }

    /** Capture baseline from browser-uploaded JPEG (matches on-screen HLS preview). */
    public synchronized Map<String, Object> captureBaselineFromJpeg(byte[] jpeg) throws Exception {
        if (jpeg == null || jpeg.length == 0) {
            return captureCurrentBaseline();
        }
        BufferedImage frame = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (frame == null || frame.getWidth() < 2 || frame.getHeight() < 2) {
            throw new IllegalArgumentException("无法解析基准画面");
        }
        return applyBaselineFrame(frame, "display");
    }

    private Map<String, Object> applyBaselineFrame(BufferedImage frame, String captureFrom) throws Exception {
        ensureBgAssist();
        if (bgAssist == null) {
            throw new IllegalStateException("背景差分未启用");
        }
        try {
            ensureDetector();
        } catch (Exception ex) {
            log.warn("Anomaly ONNX unavailable for baseline mask: {}", ex.getMessage());
        }
        List<DetectionBox> dynamicObjects = detectVehicles(frame);
        bgAssist.setBaseline(frame);
        baselineDynamicMasks = List.copyOf(dynamicObjects);
        baselineJpeg = encodeBaselineJpeg(frame);
        stickyDets.clear();
        baselineSetAt = System.currentTimeMillis();
        lastCameraSource = cameraSourceService.getActiveSource();
        Map<String, Object> result = new HashMap<>();
        result.put("source", lastCameraSource);
        result.put("captureFrom", captureFrom);
        result.put("width", frame.getWidth());
        result.put("height", frame.getHeight());
        result.put("dynamicObjects", dynamicObjects.size());
        result.put("baselineSetAt", baselineSetAt);
        result.put("foregroundPercent", bgAssist.getLastForegroundRatio() * 100.0);
        result.put("rawRegions", bgAssist.getLastRawBoxCount());
        result.put("candidateRegions", bgAssist.getLastConfirmedBoxCount());
        result.put("ready", true);
        log.info("Anomaly baseline captured from {}: source={} {}x{} dynamicObjects={}",
                captureFrom, lastCameraSource, frame.getWidth(), frame.getHeight(), dynamicObjects.size());
        return result;
    }

    private static byte[] encodeBaselineJpeg(BufferedImage frame) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(frame, "jpg", out);
            return out.toByteArray();
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.94f);
            }
            writer.write(null, new IIOImage(frame, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    public byte[] getBaselineJpeg() {
        byte[] bytes = baselineJpeg;
        return bytes == null ? null : bytes.clone();
    }

    public synchronized Map<String, Object> getBaselineStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("source", cameraSourceService.getActiveSource());
        result.put("ready", bgAssist != null && bgAssist.isReady());
        result.put("warmupLeft", bgAssist == null ? 0 : bgAssist.warmupLeft());
        result.put("baselineSetAt", baselineSetAt);
        result.put("foregroundPercent", bgAssist == null ? 0.0 : bgAssist.getLastForegroundRatio() * 100.0);
        result.put("rawRegions", bgAssist == null ? 0 : bgAssist.getLastRawBoxCount());
        result.put("candidateRegions", bgAssist == null ? 0 : bgAssist.getLastConfirmedBoxCount());
        result.put("cameraOnline", isRtspBackedSource(cameraSourceService.getActiveSource())
                ? streamManager.isOnline()
                : cameraSourceService.isOnline(cameraSourceService.getActiveSource()));
        return result;
    }

    private BufferedImage currentFrame() {
        String source = cameraSourceService.getActiveSource();
        if (isRtspBackedSource(source)) {
            return streamManager.getLatestFrameCopy();
        }
        return cameraSourceService.getFrameCopy(source);
    }

    private static boolean isRtspBackedSource(String source) {
        return CameraSourceService.SANDBOX.equals(source)
                || CameraSourceService.IP_CAMERA.equals(source);
    }

    private void resetBackground(String reason) {
        if (bgAssist != null) {
            bgAssist.reset();
            log.info("Anomaly bgDiff reset: {}", reason);
        }
        stickyDets.clear();
        displaySticky.clear();
        baselineSetAt = 0L;
        baselineDynamicMasks = List.of();
        baselineJpeg = null;
    }

    /**
     * Baseline masks always apply. Current-frame detections are added only when they
     * overlap baseline slots — new person-like blobs (cartoon standees) are not erased.
     */
    private List<DetectionBox> buildDynamicMasks(List<DetectionBox> currentDetections) {
        List<DetectionBox> masks = new ArrayList<>(baselineDynamicMasks);
        if (currentDetections == null || currentDetections.isEmpty()) {
            return masks;
        }
        for (DetectionBox det : currentDetections) {
            String cls = det.getClassName().toLowerCase();
            // Fixed traffic lights / LED stacks are never scene anomalies.
            if ("traffic light".equals(cls)) {
                masks.add(det);
                continue;
            }
            if (PERSON_LIKE_CLASSES.contains(cls)) {
                if (overlapsAny(det, baselineDynamicMasks, 0.10f)) {
                    masks.add(det);
                }
                continue;
            }
            if (overlapsAny(det, baselineDynamicMasks, 0.10f)) {
                masks.add(det);
            }
        }
        return masks;
    }

    private List<DetectionBox> detectVehicles(BufferedImage frame) {
        if (vehicleDetector == null || frame == null) {
            return List.of();
        }
        try {
            return vehicleDetector.detect(frame).stream()
                    .filter(box -> DYNAMIC_CLASSES.contains(box.getClassName().toLowerCase()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Vehicle mask detect skipped: {}", ex.toString());
            return List.of();
        }
    }

    private List<DetectionBox> backgroundChanges(List<DetectionBox> changes,
                                                 List<DetectionBox> dynamicMasks,
                                                 int frameW, int frameH) {
        List<DetectionBox> result = new ArrayList<>();
        for (DetectionBox change : changes) {
            if (overlapsDynamicMask(change, dynamicMasks)) {
                continue;
            }
            if (isLedStripLike(change, frameW, frameH)) {
                continue;
            }
            if (touchesFrameEdge(change.getX1(), change.getY1(), change.getX2(), change.getY2(),
                    frameW, frameH, 0.035f)) {
                continue;
            }
            int stableFrames = Math.max(1,
                    change.getTrackHits() - properties.getBgPersistHits() + 1);
            float confidence = Math.min(0.95f,
                    properties.getBgOnlyConf()
                            + properties.getBgBoost()
                            + Math.min(0.28f, Math.max(0, stableFrames - 1) * 0.06f));
            result.add(new DetectionBox(
                    "anomaly",
                    confidence,
                    change.getX1(), change.getY1(), change.getX2(), change.getY2(),
                    change.getTrackHits()
            ));
        }
        return result;
    }

    private static boolean touchesFrameEdge(int x1, int y1, int x2, int y2,
                                          int frameW, int frameH, float marginRatio) {
        int mx = Math.max(4, Math.round(frameW * marginRatio));
        int my = Math.max(4, Math.round(frameH * marginRatio));
        return x1 <= mx || y1 <= my || x2 >= frameW - mx || y2 >= frameH - my;
    }

    private static boolean isLedStripLike(DetectionBox box, int frameW, int frameH) {
        int bw = Math.max(1, box.getX2() - box.getX1());
        int bh = Math.max(1, box.getY2() - box.getY1());
        float aspect = bw / (float) bh;
        float heightRatio = bh / (float) frameH;
        float widthRatio = bw / (float) frameW;
        if (aspect < 0.30f && heightRatio > 0.08f && (bw <= 24 || widthRatio < 0.020f)) {
            return true;
        }
        if (aspect > 1.5f && heightRatio < 0.07f) {
            return true;
        }
        return aspect > 1.25f && heightRatio < 0.05f && widthRatio > 0.05f;
    }

    /** Hold boxes briefly when diff flickers so the UI does not flash empty every other frame. */
    private List<DetectionBox> stabilizeDisplay(List<DetectionBox> fused) {
        for (StickyDet s : displaySticky) {
            s.matched = false;
        }
        List<DetectionBox> out = new ArrayList<>();
        for (DetectionBox box : fused) {
            StickyDet sticky = findDisplaySticky(box);
            if (sticky == null) {
                sticky = new StickyDet();
                sticky.x1 = box.getX1();
                sticky.y1 = box.getY1();
                sticky.x2 = box.getX2();
                sticky.y2 = box.getY2();
                sticky.conf = box.getConfidence();
                sticky.hold = DISPLAY_HOLD_FRAMES;
                sticky.matched = true;
                displaySticky.add(sticky);
            } else {
                sticky.matched = true;
                sticky.hold = DISPLAY_HOLD_FRAMES;
                sticky.conf = 0.55f * sticky.conf + 0.45f * box.getConfidence();
                sticky.x1 = Math.round(0.35f * sticky.x1 + 0.65f * box.getX1());
                sticky.y1 = Math.round(0.35f * sticky.y1 + 0.65f * box.getY1());
                sticky.x2 = Math.round(0.35f * sticky.x2 + 0.65f * box.getX2());
                sticky.y2 = Math.round(0.35f * sticky.y2 + 0.65f * box.getY2());
            }
            out.add(stickyToAnomaly(sticky, box.getTrackHits()));
        }
        for (StickyDet s : displaySticky) {
            if (!s.matched && s.hold > 0) {
                s.hold--;
                out.add(stickyToAnomaly(s, 1));
            }
        }
        displaySticky.removeIf(s -> !s.matched && s.hold <= 0);
        return out;
    }

    private StickyDet findDisplaySticky(DetectionBox box) {
        StickyDet best = null;
        float bestIou = 0.12f;
        for (StickyDet s : displaySticky) {
            float i = iouBox(s.x1, s.y1, s.x2, s.y2, box);
            if (i >= bestIou) {
                bestIou = i;
                best = s;
            }
        }
        return best;
    }

    private static DetectionBox stickyToAnomaly(StickyDet s, int trackHits) {
        return new DetectionBox("anomaly", s.conf, s.x1, s.y1, s.x2, s.y2, trackHits);
    }

    private static float iouBox(int x1, int y1, int x2, int y2, DetectionBox b) {
        int ix1 = Math.max(x1, b.getX1());
        int iy1 = Math.max(y1, b.getY1());
        int ix2 = Math.min(x2, b.getX2());
        int iy2 = Math.min(y2, b.getY2());
        int iw = Math.max(0, ix2 - ix1);
        int ih = Math.max(0, iy2 - iy1);
        float inter = iw * (float) ih;
        float areaA = Math.max(1, (x2 - x1) * (y2 - y1));
        float areaB = Math.max(1, (b.getX2() - b.getX1()) * (b.getY2() - b.getY1()));
        return inter / (areaA + areaB - inter + 1e-6f);
    }

    private boolean overlapsDynamicMask(DetectionBox change, List<DetectionBox> masks) {
        if (masks == null || masks.isEmpty()) return false;
        float overlapThreshold = Math.max(0.25f, properties.getVehicleSuppressIou());
        for (DetectionBox mask : masks) {
            // The mask was already erased before connected-component analysis.
            // Only reject a residual whose own center still lies on a road user;
            // do not discard a larger real anomaly merely because it surrounds
            // or touches a small vehicle box.
            if (iou(change, mask) >= overlapThreshold || centerInside(change, mask)) return true;
        }
        return false;
    }

    private List<DetectionBox> suppressOverlaps(List<DetectionBox> debris,
                                                List<DetectionBox> vehicles,
                                                float thr) {
        if (debris.isEmpty() || vehicles.isEmpty()) {
            return debris;
        }
        List<DetectionBox> kept = new ArrayList<>();
        for (DetectionBox d : debris) {
            boolean hit = false;
            for (DetectionBox v : vehicles) {
                if (iou(d, v) >= thr || centerInside(d, v)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                kept.add(d);
            }
        }
        return kept;
    }

    private static boolean overlapsAny(DetectionBox a, List<DetectionBox> others, float thr) {
        if (others == null || others.isEmpty()) {
            return false;
        }
        for (DetectionBox b : others) {
            if (iou(a, b) >= thr || centerInside(a, b) || centerInside(b, a)) {
                return true;
            }
        }
        return false;
    }

    private static boolean centerInside(DetectionBox inner, DetectionBox outer) {
        int cx = (inner.getX1() + inner.getX2()) / 2;
        int cy = (inner.getY1() + inner.getY2()) / 2;
        return cx >= outer.getX1() && cx <= outer.getX2()
                && cy >= outer.getY1() && cy <= outer.getY2();
    }

    private static float iou(DetectionBox a, DetectionBox b) {
        int ix1 = Math.max(a.getX1(), b.getX1());
        int iy1 = Math.max(a.getY1(), b.getY1());
        int ix2 = Math.min(a.getX2(), b.getX2());
        int iy2 = Math.min(a.getY2(), b.getY2());
        int iw = Math.max(0, ix2 - ix1);
        int ih = Math.max(0, iy2 - iy1);
        float inter = iw * (float) ih;
        float areaA = Math.max(1, (a.getX2() - a.getX1()) * (a.getY2() - a.getY1()));
        float areaB = Math.max(1, (b.getX2() - b.getX1()) * (b.getY2() - b.getY1()));
        return inter / (areaA + areaB - inter + 1e-6f);
    }

    private static List<DetectionBox> limitBoxes(List<DetectionBox> boxes, int max) {
        if (boxes.size() <= max) {
            return boxes;
        }
        List<DetectionBox> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(DetectionBox::getConfidence).reversed());
        return sorted.subList(0, max);
    }

    private static List<DetectionBox> upscaleBoxes(List<DetectionBox> boxes, float factor) {
        if (factor <= 1.001f || boxes.isEmpty()) {
            return boxes;
        }
        List<DetectionBox> out = new ArrayList<>(boxes.size());
        for (DetectionBox box : boxes) {
            out.add(new DetectionBox(
                    box.getClassName(),
                    box.getConfidence(),
                    Math.round(box.getX1() * factor),
                    Math.round(box.getY1() * factor),
                    Math.round(box.getX2() * factor),
                    Math.round(box.getY2() * factor),
                    box.getTrackHits()
            ));
        }
        return out;
    }

    private void pushResult(int width, int height, List<DetectionBox> boxes, boolean bgReady) {
        List<Map<String, Object>> detections = new ArrayList<>();
        for (DetectionBox box : boxes) {
            Map<String, Object> item = new HashMap<>();
            item.put("className", box.getClassName());
            item.put("confidence", Math.round(box.getConfidence() * 1000.0) / 1000.0);
            item.put("bbox", box.bboxList());
            detections.add(item);
        }

        String summary;
        if (!bgReady && properties.isBgDiffEnabled()) {
            summary = boxes.isEmpty()
                    ? "异物×0 | 差分学习中"
                    : ("异物×" + boxes.size() + " | 差分学习中");
        } else if (properties.isBgDiffEnabled()) {
            summary = boxes.isEmpty() ? "异物×0 | 差分区→模型" : ("异物×" + boxes.size() + " | 差分区→模型");
        } else {
            summary = boxes.isEmpty() ? "异物×0 | 仅模型" : ("异物×" + boxes.size() + " | 仅模型");
        }

        if (!boxes.isEmpty()) {
            logEvery(2_000, "Anomaly push " + summary
                    + " topConf=" + String.format("%.3f", boxes.get(0).getConfidence()));
        } else {
            logEvery(8_000, "Anomaly push " + summary);
        }

        InferencePushRequest req = new InferencePushRequest();
        req.setSource("anomaly.onnx+bgdiff:anomaly");
        req.setVehicleCount(0);
        req.setSummary(summary);
        req.setDetections(detections);
        req.setPlates(List.of());
        req.setImageWidth(width);
        req.setImageHeight(height);
        req.setSaveCongestion(false);
        req.setSavePlates(false);
        req.setBgLearning(!bgReady && properties.isBgDiffEnabled());
        inferenceService.pushDetection(req);
    }

    private void maybeAlert(List<DetectionBox> boxes, int frameWidth, int frameHeight) {
        if (boxes.isEmpty() || properties.getAlertCooldownMs() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastAlertMs.get();
        if (now - last < properties.getAlertCooldownMs()) {
            return;
        }
        if (!lastAlertMs.compareAndSet(last, now)) {
            return;
        }
        DetectionBox first = boxes.get(0);
        String source = cameraSourceService.getActiveSource();
        String cameraId = source;
        String cameraName = switch (source) {
            case CameraSourceService.LOCAL -> "本机摄像头";
            case CameraSourceService.PHONE -> "手机摄像头";
            default -> "沙盘摄像头";
        };
        String mappedRegion = null;
        if (CameraSourceService.SANDBOX.equals(source)) {
            try {
                var channel = streamChannelService.getCurrent();
                cameraId = channel.getChannelId();
                cameraName = channel.getChannelName();
                double nx = (first.getX1() + first.getX2()) * 0.5 / Math.max(1, frameWidth);
                double ny = (first.getY1() + first.getY2()) * 0.5 / Math.max(1, frameHeight);
                mappedRegion = cameraRoadRoiService.resolveRegionName(cameraId, nx, ny);
            } catch (Exception ignored) {
                cameraName = "沙盘当前摄像头";
            }
        }
        double nx = (first.getX1() + first.getX2()) * 0.5 / Math.max(1, frameWidth);
        double ny = (first.getY1() + first.getY2()) * 0.5 / Math.max(1, frameHeight);
        String regionName = mappedRegion != null
                ? mappedRegion + "（" + describeFrameRegion(nx, ny) + "）"
                : describeFrameRegion(nx, ny);
        String location = String.format(
                "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"cameraId\":\"%s\",\"cameraName\":\"%s\",\"region\":\"%s\"}",
                first.getX1(),
                first.getY1(),
                first.getX2() - first.getX1(),
                first.getY2() - first.getY1(),
                jsonEscape(cameraId),
                jsonEscape(cameraName),
                jsonEscape(regionName)
        );
        try {
            alertService.saveAlert(
                    "road_anomaly",
                    "检测到道路异常 ×" + boxes.size() + "，摄像头：" + cameraName
                            + "（" + cameraId + "），区域：" + regionName,
                    location,
                    true
            );
        } catch (Exception ex) {
            log.debug("Alert save skipped: {}", ex.toString());
        }
    }

    private static String describeFrameRegion(double x, double y) {
        String vertical = y < 0.33 ? "上部" : y > 0.66 ? "下部" : "中部";
        String horizontal = x < 0.33 ? "左侧" : x > 0.66 ? "右侧" : "中央";
        return "画面" + vertical + horizontal;
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void logEvery(long minIntervalMs, String message) {
        long now = System.currentTimeMillis();
        long last = lastLogMs.get();
        if (now - last >= minIntervalMs && lastLogMs.compareAndSet(last, now)) {
            log.info(message);
        }
    }

    @PreDestroy
    public void shutdown() {
        YoloV8OnnxDetector d = detector;
        detector = null;
        if (d != null) {
            d.close();
        }
        YoloV8OnnxDetector v = vehicleDetector;
        vehicleDetector = null;
        if (v != null) {
            v.close();
        }
        bgAssist = null;
    }
}
