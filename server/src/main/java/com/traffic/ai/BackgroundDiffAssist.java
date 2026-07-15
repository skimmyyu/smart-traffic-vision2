package com.traffic.ai;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Background-difference assist for road anomaly.
 * Learns a grayscale background for a fixed warmup window (~10s), then FREEZES it.
 * All later anomaly candidates are differences vs that frozen baseline
 * (vehicles should be masked by caller).
 */
public class BackgroundDiffAssist {

    private static final float CHROMA_WEIGHT = 0.35f;
    /** Pixels with inter-frame volatility at/above this are treated as LEDs/leaves/reflections. */
    private static final float VOLATILITY_LIMIT = 3f;
    /** Baseline green canopy — H.264 chroma on trees should not become debris. */
    private boolean[] baselineFoliageMask;
    /** User froze baseline via UI — keep it across minor frame-size drift. */
    private boolean manualBaseline;

    public static final class Config {
        /** Frames spent learning; after that background never updates until reset() */
        public int warmupFrames = 36;
        public float diffThreshold = 28f;
        public float maxFgRatio = 0.05f;
        /** Above this, skip blob tracking entirely (huge occlusion / hand covering most of view) */
        public float maxFgHardRatio = 0.22f;
        public float minAreaRatio = 0.002f;
        public float maxAreaRatio = 0.12f;
        public int minSidePx = 16;
        public float maxAspect = 3.5f;
        public int persistHits = 1;
        public int missLimit = 12;
        public float matchIou = 0.15f;
        public int maxBoxes = 5;
    }

    private static final class Track {
        int x1, y1, x2, y2;
        int hits;
        int misses;
        float area;
    }

    private final Config cfg;
    private int frameI;
    private boolean ready;
    private float[] bg; // grayscale float — frozen after warmup
    /** Frozen RGB baseline used for chroma-aware comparison. */
    private int[] bgRgb;
    private int bgW;
    private int bgH;
    /** Learns pixels that keep changing (trees, LED animations, reflections). */
    private float[] volatility;
    private float[] previousGray;
    private int[] previousRgb;
    private final List<Track> tracks = new ArrayList<>();
    private volatile float lastForegroundRatio;
    private volatile int lastRawBoxCount;
    private volatile int lastConfirmedBoxCount;
    /** Decay per frame for inter-frame volatility map; higher = faster release of new static objects. */
    private float volatilityDecay = 0.25f;

    public BackgroundDiffAssist(Config cfg) {
        this.cfg = cfg != null ? cfg : new Config();
        reset();
    }

    public void reset() {
        frameI = 0;
        ready = false;
        bg = null;
        bgRgb = null;
        bgW = 0;
        bgH = 0;
        volatility = null;
        previousGray = null;
        previousRgb = null;
        tracks.clear();
        lastForegroundRatio = 0f;
        lastRawBoxCount = 0;
        lastConfirmedBoxCount = 0;
        baselineFoliageMask = null;
        manualBaseline = false;
    }

    /** Freeze one user-selected frame as the baseline immediately. */
    public void setBaseline(BufferedImage frame) {
        if (frame == null || frame.getWidth() < 2 || frame.getHeight() < 2) {
            throw new IllegalArgumentException("基准画面不可用");
        }
        bgW = frame.getWidth();
        bgH = frame.getHeight();
        // Manual freeze: keep a sharp single frame (no temporal average, no extra blur).
        bg = toGrayFloat(frame);
        bgRgb = toRgb(frame);
        previousGray = bg.clone();
        previousRgb = bgRgb.clone();
        volatility = new float[bg.length];
        frameI = cfg.warmupFrames;
        ready = true;
        manualBaseline = true;
        tracks.clear();
        baselineFoliageMask = buildFoliageMask(bgRgb, bgW, bgH);
    }

    public boolean isReady() {
        return ready;
    }

    /** Apply the UI setting without rebuilding or resetting the learned baseline. */
    public void setMinAreaRatio(float value) {
        cfg.minAreaRatio = Math.max(0.00001f, Math.min(value, cfg.maxAreaRatio));
    }

    /** Uploaded JPEG / test video: faster decay so looped frames release standees sooner. */
    public void setVolatilityDecay(float decay) {
        volatilityDecay = Math.max(0.05f, Math.min(2f, decay));
    }

    public int warmupLeft() {
        return Math.max(0, cfg.warmupFrames - frameI);
    }

    public float getLastForegroundRatio() {
        return lastForegroundRatio;
    }

    public int getLastRawBoxCount() {
        return lastRawBoxCount;
    }

    public int getLastConfirmedBoxCount() {
        return lastConfirmedBoxCount;
    }

    /**
     * During warmup: accumulate background mean and return no proposals.
     * After warmup: background is frozen; only foreground blobs vs that baseline are returned.
     */
    public List<DetectionBox> update(BufferedImage frame, List<DetectionBox> vehicles) {
        if (frame == null || frame.getWidth() < 2 || frame.getHeight() < 2) {
            return List.of();
        }
        if (ready && manualBaseline && bg != null
                && (frame.getWidth() != bgW || frame.getHeight() != bgH)) {
            frame = resizeFrame(frame, bgW, bgH);
        }
        int w = frame.getWidth();
        int h = frame.getHeight();
        // RTSP/H.264 introduces block-edge and chroma jitter that is absent in
        // uploaded files. A small spatial blur makes both paths comparable.
        float[] gray = blur3x3(toGrayFloat(frame), w, h);
        int[] rgb = toRgb(frame);
        frameI++;

        // —— Learn phase: build baseline, then freeze ——
        if (!ready) {
            if (bgW != w || bgH != h || bg == null) {
                bgW = w;
                bgH = h;
                bg = gray.clone();
                bgRgb = rgb.clone();
            } else {
                // Online mean: each frame equally weighted into the baseline
                float a = 1f / frameI;
                for (int i = 0; i < bg.length; i++) {
                    bg[i] = (1f - a) * bg[i] + a * gray[i];
                }
                // Keep a clean recent colour reference while grayscale uses the
                // temporal mean to suppress compression noise.
                bgRgb = rgb.clone();
            }
            if (frameI >= cfg.warmupFrames) {
                ready = true;
                baselineFoliageMask = buildFoliageMask(bgRgb, w, h);
            }
            return List.of();
        }

        if (bg == null || bgW != w || bgH != h) {
            if (manualBaseline && bg != null) {
                return List.of();
            }
            // Size change after freeze → re-learn from this frame
            bg = gray.clone();
            bgRgb = rgb.clone();
            bgW = w;
            bgH = h;
            frameI = 1;
            ready = false;
            tracks.clear();
            previousGray = gray.clone();
            previousRgb = rgb.clone();
            volatility = new float[gray.length];
            return List.of();
        }

        if (previousGray == null || previousGray.length != gray.length) {
            previousGray = gray.clone();
            previousRgb = rgb.clone();
            volatility = new float[gray.length];
        }

        // Repeated inter-frame motion is environmental noise, not a newly
        // placed static object. A one-off change adds too little to be hidden;
        // swaying leaves, running LEDs and moving reflections accumulate and
        // stay suppressed until the area has been quiet for a while.
        for (int i = 0; i < gray.length; i++) {
            float movement = Math.max(
                    Math.abs(gray[i] - previousGray[i]),
                    chromaDifference(rgb[i], previousRgb[i]) * CHROMA_WEIGHT);
            boolean foliage = isFoliagePixel(rgb[i]);
            float moveThr = foliage ? 6f : 10f;
            float volInc = foliage ? 3.4f : 2.5f;
            if (movement > moveThr) {
                volatility[i] = Math.min(24f, volatility[i] + volInc);
            } else {
                // Once an inserted object becomes still, release it quickly so
                // it can be compared with the baseline within a few frames.
                // Environmental motion is intermittent: leaves can pause between
                // gusts and LEDs between animation steps. Decay slowly enough to
                // remember that repeated movement, while a one-off object placement
                // remains below VOLATILITY_LIMIT and is detected immediately.
                volatility[i] = Math.max(0f, volatility[i] - volatilityDecay);
            }
            previousGray[i] = gray[i];
            previousRgb[i] = rgb[i];
        }

        // —— Frozen baseline: difference only, never update bg ——
        float[] grayN = matchMeanStd(gray, bg);

        boolean[] fg = new boolean[w * h];
        int fgCount = 0;
        for (int i = 0; i < fg.length; i++) {
            float luminanceDiff = Math.abs(grayN[i] - bg[i]);
            float chromaDiff = bgRgb == null ? 0f : chromaDifference(rgb[i], bgRgb[i]) * CHROMA_WEIGHT;
            float threshold = cfg.diffThreshold;
            if (baselineFoliageMask != null && baselineFoliageMask[i]) {
                threshold *= 1.9f;
            } else if (isFoliagePixel(rgb[i]) && bgRgb != null && isFoliagePixel(bgRgb[i])) {
                threshold *= 1.55f;
            }
            if (Math.max(luminanceDiff, chromaDiff) > threshold
                    && volatility[i] < VOLATILITY_LIMIT) {
                fg[i] = true;
                fgCount++;
            }
        }

        fg = bridgeSmallGaps(fg, w, h);

        if (vehicles != null) {
            for (DetectionBox v : vehicles) {
                // Traffic-light / LED areas need a wider erase than moving vehicles.
                float expand = isTrafficLight(v) ? 0.55f : 0.25f;
                eraseBox(fg, w, h, v, expand);
            }
        }

        // H.264 block edges at frame border → ignore a thin outer band
        eraseBorderBand(fg, w, h, 0.04f);

        fgCount = countTrue(fg);
        float fgRatio = fgCount / (float) (w * h);
        lastForegroundRatio = fgRatio;
        // Even under large disturbance (hand placing object), keep tracking blobs —
        // otherwise first detect waits until the hand leaves + re-confirm.
        List<DetectionBox> raw = blobsFromMask(fg, w, h, volatility, rgb);
        lastRawBoxCount = raw.size();
        if (fgRatio > cfg.maxFgHardRatio && raw.isEmpty()) {
            decayTracksOnly();
            List<DetectionBox> confirmed = confirmedBoxes();
            lastConfirmedBoxCount = confirmed.size();
            return confirmed;
        }
        updateTracks(raw);
        List<DetectionBox> confirmed = confirmedBoxes();
        lastConfirmedBoxCount = confirmed.size();
        return confirmed;
    }

    private void decayTracksOnly() {
        Iterator<Track> it = tracks.iterator();
        while (it.hasNext()) {
            Track t = it.next();
            t.misses++;
            if (t.misses > cfg.missLimit) {
                it.remove();
            }
        }
    }

    private void updateTracks(List<DetectionBox> raw) {
        boolean[] used = new boolean[raw.size()];
        for (Track t : tracks) {
            int best = -1;
            float bestIou = 0f;
            for (int i = 0; i < raw.size(); i++) {
                if (used[i]) {
                    continue;
                }
                float iou = iou(t, raw.get(i));
                if (iou > bestIou) {
                    bestIou = iou;
                    best = i;
                }
            }
            if (best >= 0 && bestIou >= cfg.matchIou) {
                DetectionBox b = raw.get(best);
                used[best] = true;
                t.x1 = Math.round(0.35f * t.x1 + 0.65f * b.getX1());
                t.y1 = Math.round(0.35f * t.y1 + 0.65f * b.getY1());
                t.x2 = Math.round(0.35f * t.x2 + 0.65f * b.getX2());
                t.y2 = Math.round(0.35f * t.y2 + 0.65f * b.getY2());
                t.hits++;
                t.misses = 0;
                t.area = (t.x2 - t.x1) * (float) (t.y2 - t.y1);
            } else {
                t.misses++;
            }
        }
        for (int i = 0; i < raw.size(); i++) {
            if (used[i]) {
                continue;
            }
            DetectionBox b = raw.get(i);
            Track t = new Track();
            t.x1 = b.getX1();
            t.y1 = b.getY1();
            t.x2 = b.getX2();
            t.y2 = b.getY2();
            t.hits = 1;
            t.misses = 0;
            t.area = (t.x2 - t.x1) * (float) (t.y2 - t.y1);
            tracks.add(t);
        }
        tracks.removeIf(t -> t.misses > cfg.missLimit);
    }

    private List<DetectionBox> confirmedBoxes() {
        List<DetectionBox> out = new ArrayList<>();
        List<Track> sorted = new ArrayList<>(tracks);
        sorted.sort(Comparator.comparingDouble((Track t) -> t.area).reversed());
        for (Track t : sorted) {
            if (t.hits < cfg.persistHits) {
                continue;
            }
            out.add(new DetectionBox("debris", 0.40f, t.x1, t.y1, t.x2, t.y2, t.hits));
            if (out.size() >= cfg.maxBoxes) {
                break;
            }
        }
        return out;
    }

    private List<DetectionBox> blobsFromMask(boolean[] fg, int w, int h, float[] volatility, int[] rgb) {
        boolean[] seen = new boolean[fg.length];
        float areaFrame = w * (float) h;
        float minA = areaFrame * cfg.minAreaRatio;
        float maxA = areaFrame * cfg.maxAreaRatio;
        List<DetectionBox> boxes = new ArrayList<>();

        int[] qx = new int[fg.length];
        int[] qy = new int[fg.length];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = y * w + x;
                if (!fg[s] || seen[s]) {
                    continue;
                }
                int qh = 0;
                int qt = 0;
                qx[qt] = x;
                qy[qt] = y;
                qt++;
                seen[s] = true;
                int minX = x, maxX = x, minY = y, maxY = y, count = 0;
                float volSum = volatility != null && volatility.length == fg.length ? volatility[s] : 0f;
                while (qh < qt) {
                    int cx = qx[qh];
                    int cy = qy[qh];
                    qh++;
                    count++;
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy);
                    maxY = Math.max(maxY, cy);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) {
                                continue;
                            }
                            int nx = cx + dx;
                            int ny = cy + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                                continue;
                            }
                            int ni = ny * w + nx;
                            if (!fg[ni] || seen[ni]) {
                                continue;
                            }
                            seen[ni] = true;
                            if (volatility != null && volatility.length == fg.length) {
                                volSum += volatility[ni];
                            }
                            qx[qt] = nx;
                            qy[qt] = ny;
                            qt++;
                        }
                    }
                }
                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;
                if (bw < cfg.minSidePx || bh < cfg.minSidePx) {
                    continue;
                }
                float boxArea = bw * (float) bh;
                if (boxArea < minA || boxArea > maxA) {
                    continue;
                }
                float aspect = bw / Math.max(1f, (float) bh);
                float fill = count / Math.max(1f, boxArea);
                float meanVol = count > 0 ? volSum / count : 0f;
                if (aspect > cfg.maxAspect) {
                    continue;
                }
                if (isLedStripBlob(bw, bh, w, h, aspect, fill, meanVol)) {
                    continue;
                }
                if (touchesFrameEdge(minX, minY, maxX + 1, maxY + 1, w, h, 0.035f)) {
                    continue;
                }
                float minFill = aspect < 0.45f ? 0.10f : 0.18f;
                if (fill < minFill) {
                    continue;
                }
                if (isTreeLikeBlob(rgb, w, h, minX, minY, maxX + 1, maxY + 1)) {
                    continue;
                }
                boxes.add(new DetectionBox("debris", 0.35f, minX, minY, maxX + 1, maxY + 1));
            }
        }
        boxes.sort(Comparator.comparingDouble(
                (DetectionBox b) -> (b.getX2() - b.getX1()) * (b.getY2() - b.getY1())).reversed());
        if (boxes.size() > cfg.maxBoxes * 2) {
            return boxes.subList(0, cfg.maxBoxes * 2);
        }
        return boxes;
    }

    /** Green-dominant pixels (sandbox trees / grass). */
    private static boolean isFoliagePixel(int argb) {
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>> 8) & 0xff;
        int b = argb & 0xff;
        int max = Math.max(r, Math.max(g, b));
        if (max < 38) {
            return false;
        }
        return g >= 48 && g >= r + 10 && g >= b + 6 && (g - Math.min(r, b)) >= 8;
    }

    private boolean[] buildFoliageMask(int[] rgb, int w, int h) {
        boolean[] mask = new boolean[w * h];
        if (rgb == null || rgb.length != mask.length) {
            return mask;
        }
        for (int i = 0; i < mask.length; i++) {
            mask[i] = isFoliagePixel(rgb[i]);
        }
        return mask;
    }

    /** Drop canopy blobs that were already green in the frozen baseline. */
    private boolean isTreeLikeBlob(int[] rgb, int frameW, int frameH,
                                     int x1, int y1, int x2, int y2) {
        if (rgb == null || rgb.length != frameW * frameH) {
            return false;
        }
        int green = 0;
        int baselineGreen = 0;
        int total = 0;
        int stepX = Math.max(1, (x2 - x1) / 6);
        int stepY = Math.max(1, (y2 - y1) / 6);
        for (int y = y1; y < y2; y += stepY) {
            int row = y * frameW;
            for (int x = x1; x < x2; x += stepX) {
                int i = row + x;
                total++;
                if (isFoliagePixel(rgb[i])) {
                    green++;
                }
                if (baselineFoliageMask != null && baselineFoliageMask[i]) {
                    baselineGreen++;
                }
            }
        }
        if (total == 0) {
            return false;
        }
        float greenRatio = green / (float) total;
        float baselineRatio = baselineGreen / (float) total;
        if (baselineRatio >= 0.32f && greenRatio >= 0.28f) {
            return true;
        }
        int bw = Math.max(1, x2 - x1);
        int bh = Math.max(1, y2 - y1);
        float areaRatio = (bw * (float) bh) / (frameW * (float) frameH);
        return greenRatio >= 0.40f && areaRatio >= 0.006f && areaRatio <= 0.14f;
    }

    private static boolean isTrafficLight(DetectionBox box) {
        return box != null && "traffic light".equalsIgnoreCase(box.getClassName());
    }

    /** Vertical/horizontal LED strips on the sandbox (distinct from wider standees). */
    private static boolean isLedStripBlob(int bw, int bh, int frameW, int frameH,
                                          float aspect, float fill, float meanVolatility) {
        float heightRatio = bh / (float) frameH;
        float widthRatio = bw / (float) frameW;

        // Vertical LED strips: thin, tall; often solid colour or flickering.
        if (aspect < 0.30f && heightRatio > 0.08f) {
            if (bw <= 24 || widthRatio < 0.020f) {
                return true;
            }
            if (bw <= 30 && fill >= 0.40f) {
                return true;
            }
            if (bw <= 34 && meanVolatility >= 1.8f) {
                return true;
            }
        }

        // Horizontal LED (less common).
        if (aspect > 1.5f && heightRatio < 0.07f) {
            return true;
        }
        return aspect > 1.25f && heightRatio < 0.05f && widthRatio > 0.05f;
    }

    private static void eraseBorderBand(boolean[] fg, int w, int h, float ratio) {
        int bx = Math.max(3, Math.round(w * ratio));
        int by = Math.max(3, Math.round(h * ratio));
        for (int y = 0; y < h; y++) {
            int row = y * w;
            boolean edgeRow = y < by || y >= h - by;
            for (int x = 0; x < w; x++) {
                if (edgeRow || x < bx || x >= w - bx) {
                    fg[row + x] = false;
                }
            }
        }
    }

    private static boolean touchesFrameEdge(int x1, int y1, int x2, int y2,
                                          int frameW, int frameH, float marginRatio) {
        int mx = Math.max(4, Math.round(frameW * marginRatio));
        int my = Math.max(4, Math.round(frameH * marginRatio));
        return x1 <= mx || y1 <= my || x2 >= frameW - mx || y2 >= frameH - my;
    }

    private static void eraseBox(boolean[] fg, int w, int h, DetectionBox box, float expand) {
        int bw = Math.max(1, box.getX2() - box.getX1());
        int bh = Math.max(1, box.getY2() - box.getY1());
        int padX = Math.round(bw * expand);
        int padY = Math.round(bh * expand);
        int x1 = Math.max(0, box.getX1() - padX);
        int y1 = Math.max(0, box.getY1() - padY);
        int x2 = Math.min(w - 1, box.getX2() + padX);
        int y2 = Math.min(h - 1, box.getY2() + padY);
        for (int y = y1; y <= y2; y++) {
            int row = y * w;
            for (int x = x1; x <= x2; x++) {
                fg[row + x] = false;
            }
        }
    }

    /** Fill one-pixel H.264 fragmentation gaps without expanding large regions. */
    private static boolean[] bridgeSmallGaps(boolean[] input, int w, int h) {
        boolean[] current = input;
        for (int pass = 0; pass < 2; pass++) {
            boolean[] next = current.clone();
            for (int y = 1; y < h - 1; y++) {
                int row = y * w;
                for (int x = 1; x < w - 1; x++) {
                    int i = row + x;
                    if (current[i]) continue;
                    boolean horizontal = current[i - 1] && current[i + 1];
                    boolean vertical = current[i - w] && current[i + w];
                    boolean diagonalA = current[i - w - 1] && current[i + w + 1];
                    boolean diagonalB = current[i - w + 1] && current[i + w - 1];
                    if (horizontal || vertical || diagonalA || diagonalB) next[i] = true;
                }
            }
            current = next;
        }
        return current;
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private static float[] blur3x3(float[] src, int w, int h) {
        if (w < 3 || h < 3) return src;
        float[] horizontal = new float[src.length];
        float[] out = new float[src.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            horizontal[row] = src[row];
            horizontal[row + w - 1] = src[row + w - 1];
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                horizontal[i] = (src[i - 1] + 2f * src[i] + src[i + 1]) * 0.25f;
            }
        }
        System.arraycopy(horizontal, 0, out, 0, w);
        System.arraycopy(horizontal, (h - 1) * w, out, (h - 1) * w, w);
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x;
                out[i] = (horizontal[i - w] + 2f * horizontal[i] + horizontal[i + w]) * 0.25f;
            }
        }
        return out;
    }

    private static float[] toGrayFloat(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] rgb = src.getRGB(0, 0, w, h, null, 0, w);
        float[] gray = new float[w * h];
        for (int i = 0; i < rgb.length; i++) {
            int c = rgb[i];
            int r = (c >>> 16) & 0xff;
            int g = (c >>> 8) & 0xff;
            int b = c & 0xff;
            gray[i] = 0.114f * b + 0.587f * g + 0.299f * r;
        }
        return gray;
    }

    private static int[] toRgb(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        return src.getRGB(0, 0, w, h, null, 0, w);
    }

    /** Colour difference with uniform brightness shifts largely cancelled. */
    private static float chromaDifference(int a, int b) {
        int ar = (a >>> 16) & 0xff;
        int ag = (a >>> 8) & 0xff;
        int ab = a & 0xff;
        int br = (b >>> 16) & 0xff;
        int bg = (b >>> 8) & 0xff;
        int bb = b & 0xff;
        int redGreen = Math.abs((ar - ag) - (br - bg));
        int blueGreen = Math.abs((ab - ag) - (bb - bg));
        return Math.max(redGreen, blueGreen);
    }

    private static float[] matchMeanStd(float[] cur, float[] ref) {
        float cMean = 0, rMean = 0;
        for (int i = 0; i < cur.length; i++) {
            cMean += cur[i];
            rMean += ref[i];
        }
        cMean /= cur.length;
        rMean /= ref.length;
        float cVar = 0, rVar = 0;
        for (int i = 0; i < cur.length; i++) {
            float cd = cur[i] - cMean;
            float rd = ref[i] - rMean;
            cVar += cd * cd;
            rVar += rd * rd;
        }
        float cStd = (float) Math.sqrt(cVar / cur.length) + 1e-3f;
        float rStd = (float) Math.sqrt(rVar / ref.length) + 1e-3f;
        float[] out = new float[cur.length];
        for (int i = 0; i < cur.length; i++) {
            out[i] = (cur[i] - cMean) * (rStd / cStd) + rMean;
        }
        return out;
    }

    private static float iou(Track t, DetectionBox b) {
        int ix1 = Math.max(t.x1, b.getX1());
        int iy1 = Math.max(t.y1, b.getY1());
        int ix2 = Math.min(t.x2, b.getX2());
        int iy2 = Math.min(t.y2, b.getY2());
        int iw = Math.max(0, ix2 - ix1);
        int ih = Math.max(0, iy2 - iy1);
        float inter = iw * (float) ih;
        float areaA = Math.max(1, (t.x2 - t.x1) * (t.y2 - t.y1));
        float areaB = Math.max(1, (b.getX2() - b.getX1()) * (b.getY2() - b.getY1()));
        return inter / (areaA + areaB - inter + 1e-6f);
    }

    private static BufferedImage resizeFrame(BufferedImage src, int targetW, int targetH) {
        if (src == null || targetW < 2 || targetH < 2) {
            return src;
        }
        if (src.getWidth() == targetW && src.getHeight() == targetH) {
            return src;
        }
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return out;
    }
}
