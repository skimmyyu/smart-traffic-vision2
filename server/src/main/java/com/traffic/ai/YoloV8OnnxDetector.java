package com.traffic.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * YOLOv8 ONNX detector via ONNX Runtime Java.
 * Expects Ultralytics export output shape [1, 4+nc, N] (no built-in NMS).
 */
public class YoloV8OnnxDetector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(YoloV8OnnxDetector.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int imgsz;
    private final float confThreshold;
    private final float iouThreshold;
    private final int maxBoxes;
    private final String[] classNames;

    public YoloV8OnnxDetector(Path modelPath,
                              int imgsz,
                              float confThreshold,
                              float iouThreshold,
                              int maxBoxes,
                              String[] classNames) throws OrtException {
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("ONNX model not found: " + modelPath.toAbsolutePath());
        }
        this.imgsz = imgsz;
        this.confThreshold = confThreshold;
        this.iouThreshold = iouThreshold;
        this.maxBoxes = Math.max(1, maxBoxes);
        this.classNames = classNames != null && classNames.length > 0
                ? classNames
                : new String[]{"debris"};

        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath.toAbsolutePath().toString(), opts);
        this.inputName = session.getInputNames().iterator().next();
        log.info("Loaded YOLO ONNX: {} input={} imgsz={} conf={} classes={}",
                modelPath.toAbsolutePath(), inputName, imgsz, confThreshold, Arrays.toString(this.classNames));
    }

    public List<DetectionBox> detect(BufferedImage src) throws OrtException {
        if (src == null || src.getWidth() < 2 || src.getHeight() < 2) {
            return Collections.emptyList();
        }

        int origW = src.getWidth();
        int origH = src.getHeight();
        Letterbox lb = letterbox(src, imgsz);

        float[] input = imageToNchw(lb.image, imgsz);
        long[] shape = new long[]{1, 3, imgsz, imgsz};

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {

            Object value = result.get(0).getValue();
            float[][][] raw = to3d(value);
            return postprocess(raw, lb, origW, origH);
        }
    }

    private static float[][][] to3d(Object value) {
        if (value instanceof float[][][] arr) {
            return arr;
        }
        throw new IllegalStateException("Unexpected ONNX output type: " + value.getClass());
    }

    private List<DetectionBox> postprocess(float[][][] raw, Letterbox lb, int origW, int origH) {
        // raw: [1][C][N] where C = 4 + nc
        float[][] pred = raw[0];
        int channels = pred.length;
        int anchors = pred[0].length;
        int nc = channels - 4;
        if (nc <= 0) {
            return Collections.emptyList();
        }

        List<DetectionBox> candidates = new ArrayList<>();
        for (int i = 0; i < anchors; i++) {
            float bestScore = 0f;
            int bestCls = 0;
            for (int c = 0; c < nc; c++) {
                float s = pred[4 + c][i];
                if (s > bestScore) {
                    bestScore = s;
                    bestCls = c;
                }
            }
            if (bestScore < confThreshold) {
                continue;
            }

            float cx = pred[0][i];
            float cy = pred[1][i];
            float bw = pred[2][i];
            float bh = pred[3][i];

            float x1 = (cx - bw / 2f - lb.padX) / lb.scale;
            float y1 = (cy - bh / 2f - lb.padY) / lb.scale;
            float x2 = (cx + bw / 2f - lb.padX) / lb.scale;
            float y2 = (cy + bh / 2f - lb.padY) / lb.scale;

            int ix1 = clamp((int) Math.floor(x1), 0, origW - 1);
            int iy1 = clamp((int) Math.floor(y1), 0, origH - 1);
            int ix2 = clamp((int) Math.ceil(x2), 0, origW - 1);
            int iy2 = clamp((int) Math.ceil(y2), 0, origH - 1);
            if (ix2 - ix1 < 2 || iy2 - iy1 < 2) {
                continue;
            }

            String name = bestCls < classNames.length ? classNames[bestCls] : ("class_" + bestCls);
            candidates.add(new DetectionBox(name, bestScore, ix1, iy1, ix2, iy2));
        }

        return nms(candidates, iouThreshold).stream().limit(maxBoxes).toList();
    }

    private static List<DetectionBox> nms(List<DetectionBox> boxes, float iouThr) {
        List<DetectionBox> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(DetectionBox::getConfidence).reversed());
        List<DetectionBox> kept = new ArrayList<>();
        boolean[] removed = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (removed[i]) {
                continue;
            }
            DetectionBox a = sorted.get(i);
            kept.add(a);
            for (int j = i + 1; j < sorted.size(); j++) {
                if (removed[j]) {
                    continue;
                }
                if (iou(a, sorted.get(j)) >= iouThr) {
                    removed[j] = true;
                }
            }
        }
        return kept;
    }

    private static float iou(DetectionBox a, DetectionBox b) {
        int ix1 = Math.max(a.getX1(), b.getX1());
        int iy1 = Math.max(a.getY1(), b.getY1());
        int ix2 = Math.min(a.getX2(), b.getX2());
        int iy2 = Math.min(a.getY2(), b.getY2());
        int iw = Math.max(0, ix2 - ix1);
        int ih = Math.max(0, iy2 - iy1);
        float inter = iw * ih;
        float areaA = Math.max(1, (a.getX2() - a.getX1()) * (a.getY2() - a.getY1()));
        float areaB = Math.max(1, (b.getX2() - b.getX1()) * (b.getY2() - b.getY1()));
        return inter / (areaA + areaB - inter + 1e-6f);
    }

    private static Letterbox letterbox(BufferedImage src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        float scale = Math.min(size / (float) w, size / (float) h);
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        int padX = (size - nw) / 2;
        int padY = (size - nh) / 2;

        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new java.awt.Color(114, 114, 114));
        g.fillRect(0, 0, size, size);
        Image scaled = src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
        g.drawImage(scaled, padX, padY, null);
        g.dispose();
        return new Letterbox(canvas, scale, padX, padY);
    }

    private static float[] imageToNchw(BufferedImage image, int size) {
        BufferedImage bgr = image;
        if (bgr.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage tmp = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = tmp.createGraphics();
            g.drawImage(bgr, 0, 0, null);
            g.dispose();
            bgr = tmp;
        }
        byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        float[] out = new float[3 * size * size];
        int plane = size * size;
        // OpenCV/Java BGR byte order -> RGB CHW normalized
        for (int i = 0, p = 0; i < plane; i++, p += 3) {
            int b = data[p] & 0xff;
            int g = data[p + 1] & 0xff;
            int r = data[p + 2] & 0xff;
            out[i] = r / 255f;
            out[plane + i] = g / 255f;
            out[2 * plane + i] = b / 255f;
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static final class Letterbox {
        final BufferedImage image;
        final float scale;
        final float padX;
        final float padY;

        Letterbox(BufferedImage image, float scale, float padX, float padY) {
            this.image = image;
            this.scale = scale;
            this.padX = padX;
            this.padY = padY;
        }
    }

    /** Resolve model file from several common locations. */
    public static Path resolveModelPath(String configured) {
        return resolveModelPath(configured, "anomaly.onnx");
    }

    public static Path resolveModelPath(String configured, String defaultFileName) {
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        Path cwd = Path.of("").toAbsolutePath();
        String name = defaultFileName != null ? defaultFileName : "model.onnx";
        candidates.add(cwd.resolve("models/" + name));
        candidates.add(cwd.resolve("../models/" + name));
        candidates.add(cwd.resolve("../../models/" + name));
        if (cwd.getParent() != null) {
            candidates.add(cwd.getParent().resolve("models/" + name));
        }

        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return Path.of(configured != null ? configured : "models/" + name).toAbsolutePath();
    }
}
