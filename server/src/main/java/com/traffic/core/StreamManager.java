package com.traffic.core;

import com.traffic.config.StreamProperties;
import com.traffic.dto.StreamStatusDto;
import jakarta.annotation.PreDestroy;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class StreamManager {

    private static final Logger log = LoggerFactory.getLogger(StreamManager.class);
    private static final long START_TIMEOUT_SEC = 10;
    private static final long GRAB_TIMEOUT_MS = 5000;

    static {
        // 抑制 FFmpeg swscaler 像素格式警告（yuvj420p 全量程，不影响拉流）
        av_log_set_level(AV_LOG_ERROR);
    }

    private final StreamProperties properties;
    private final ExecutorService grabExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stream-grab");
        t.setDaemon(true);
        return t;
    });
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();
    private final Object latestFrameLock = new Object();
    private volatile BufferedImage latestFrame;
    private volatile int nativeFrameWidth;
    private volatile int nativeFrameHeight;
    /** scaledWidth / nativeWidth — 1.0 when not downscaled */
    private volatile float frameContentScale = 1f;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean reconnectRequested = new AtomicBoolean(false);
    private final AtomicLong switchGraceUntilMs = new AtomicLong(0);
    private final AtomicLong lastFrameTimeMs = new AtomicLong(0);
    private final AtomicLong totalFrames = new AtomicLong(0);
    private final AtomicLong fpsFrameCount = new AtomicLong(0);
    private final AtomicLong fpsWindowStartMs = new AtomicLong(System.currentTimeMillis());
    private volatile double currentFps = 0.0;
    private volatile String lastError = "";

    private volatile String pullUrl;

    public StreamManager(StreamProperties properties) {
        this.properties = properties;
        this.pullUrl = properties.getRtspUrl();
        Thread worker = new Thread(this::pullLoop, "stream-pull");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Force RTSP reconnect after MediaMTX source switch.
     * Keeps "online" true during grace so UI does not flash offline.
     */
    public void requestReconnect(long graceMs) {
        requestReconnect(graceMs, null);
    }

    /** Switch local MediaMTX relay path (e.g. cam1 vs cam-phone) and reconnect. */
    public void switchPullUrl(String rtspUrl, long graceMs) {
        if (rtspUrl != null && !rtspUrl.isBlank()) {
            pullUrl = rtspUrl.trim();
        }
        requestReconnect(graceMs, pullUrl);
    }

    private void requestReconnect(long graceMs, String urlHint) {
        long grace = Math.max(graceMs, 3000L);
        long now = System.currentTimeMillis();
        switchGraceUntilMs.set(now + grace);
        // Refresh heartbeat so isOnline stays true while MediaMTX settles
        lastFrameTimeMs.set(now);
        reconnectRequested.set(true);
        log.info("Stream reconnect requested, grace={}ms url={}", grace, urlHint != null ? urlHint : pullUrl);
    }

    private String effectivePullUrl() {
        String url = pullUrl;
        return (url != null && !url.isBlank()) ? url : properties.getRtspUrl();
    }

    private void pullLoop() {
        while (running.get()) {
            FFmpegFrameGrabber grabber = null;
            try {
                log.info("Connecting stream: {}", effectivePullUrl());
                grabber = createGrabber();
                startGrabberWithTimeout(grabber);
                log.info("Stream connected: {}x{}", grabber.getImageWidth(), grabber.getImageHeight());
                lastError = "";
                onFrameReceived();

                while (running.get()) {
                    if (reconnectRequested.compareAndSet(true, false)) {
                        log.info("Breaking stream loop for channel-switch reconnect");
                        lastError = "channel switch reconnect";
                        break;
                    }

                    Frame frame = grabWithTimeout(grabber);
                    if (frame != null) {
                        cacheLatestFrame(frame);
                        onFrameReceived();
                        continue;
                    }

                    long age = System.currentTimeMillis() - lastFrameTimeMs.get();
                    // During switch grace, reconnect sooner instead of waiting full offline timeout
                    long stallLimit = inSwitchGrace()
                            ? Math.min(properties.getOfflineTimeoutMs(), 2500L)
                            : properties.getOfflineTimeoutMs();
                    if (age > stallLimit) {
                        log.warn("Stream stalled for {} ms, reconnecting", age);
                        lastError = "stream stalled, reconnecting";
                        break;
                    }
                    Thread.sleep(30);
                }
            } catch (Exception ex) {
                lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.warn("Stream pull failed: {}", lastError);
            } finally {
                closeGrabber(grabber);
            }

            if (running.get()) {
                long delay = inSwitchGrace() ? 200L : properties.getReconnectDelayMs();
                sleepQuietly(delay);
            }
        }
    }

    private void cacheLatestFrame(Frame frame) {
        try {
            BufferedImage converted = frameConverter.convert(frame);
            if (converted == null) {
                return;
            }
            nativeFrameWidth = converted.getWidth();
            nativeFrameHeight = converted.getHeight();
            BufferedImage scaled = downscaleIfNeeded(converted);
            frameContentScale = scaled.getWidth() / (float) Math.max(1, nativeFrameWidth);
            BufferedImage copy = new BufferedImage(
                    scaled.getWidth(),
                    scaled.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR
            );
            copy.getGraphics().drawImage(scaled, 0, 0, null);
            synchronized (latestFrameLock) {
                latestFrame = copy;
            }
        } catch (Exception ex) {
            log.debug("Frame convert skipped: {}", ex.toString());
        }
    }

    private BufferedImage downscaleIfNeeded(BufferedImage src) {
        int maxW = properties.getMaxFrameWidth();
        if (maxW <= 0 || src.getWidth() <= maxW) {
            return src;
        }
        int newW = maxW;
        int newH = Math.max(1, Math.round(src.getHeight() * (maxW / (float) src.getWidth())));
        BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();
        return out;
    }

    /** Latest RTSP frame copy for ONNX inference (null if stream not ready). */
    public BufferedImage getLatestFrameCopy() {
        synchronized (latestFrameLock) {
            if (latestFrame == null) {
                return null;
            }
            BufferedImage src = latestFrame;
            BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            copy.getGraphics().drawImage(src, 0, 0, null);
            return copy;
        }
    }

    public int getNativeFrameWidth() {
        return nativeFrameWidth;
    }

    public int getNativeFrameHeight() {
        return nativeFrameHeight;
    }

    /** Multiply inference bbox coords by 1/this to map onto native playback resolution. */
    public float getFrameUpscaleFactor() {
        float scale = frameContentScale;
        return scale > 0f && scale < 1f ? 1f / scale : 1f;
    }

    private boolean inSwitchGrace() {
        return System.currentTimeMillis() < switchGraceUntilMs.get();
    }

    private Frame grabWithTimeout(FFmpegFrameGrabber grabber) throws Exception {
        Future<Frame> future = grabExecutor.submit(new Callable<Frame>() {
            @Override
            public Frame call() throws Exception {
                Frame image = grabber.grabImage();
                if (image != null) {
                    return image;
                }
                return grabber.grab();
            }
        });
        try {
            return future.get(GRAB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return null;
        }
    }

    private void startGrabberWithTimeout(FFmpegFrameGrabber grabber) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stream-start");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Void> future = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    grabber.start();
                    return null;
                }
            });
            future.get(START_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("RTSP connect timeout, check MediaMTX and phone camera", ex);
        } finally {
            executor.shutdownNow();
        }
    }

    private FFmpegFrameGrabber createGrabber() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(effectivePullUrl());
        grabber.setFormat("rtsp");
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("stimeout", "15000000");
        grabber.setOption("rw_timeout", "15000000");
        grabber.setOption("timeout", "15000000");
        grabber.setOption("max_delay", "500000");
        // Prefer latest frames after channel switch (reduce stale buffer)
        grabber.setOption("fflags", "nobuffer");
        grabber.setOption("flags", "low_delay");
        return grabber;
    }

    private void onFrameReceived() {
        long now = System.currentTimeMillis();
        lastFrameTimeMs.set(now);
        totalFrames.incrementAndGet();

        long windowStart = fpsWindowStartMs.get();
        long count = fpsFrameCount.incrementAndGet();
        if (now - windowStart >= 1000) {
            currentFps = count * 1000.0 / Math.max(now - windowStart, 1);
            fpsFrameCount.set(0);
            fpsWindowStartMs.set(now);
        }
    }

    private void closeGrabber(FFmpegFrameGrabber grabber) {
        if (grabber == null) {
            return;
        }
        try {
            grabber.stop();
        } catch (Exception ignored) {
            // ignore stop errors during reconnect
        }
        try {
            grabber.release();
        } catch (Exception ignored) {
            // ignore release errors during reconnect
        }
    }

    public boolean isOnline() {
        if (inSwitchGrace()) {
            return true;
        }
        long last = lastFrameTimeMs.get();
        if (last == 0) {
            return false;
        }
        return System.currentTimeMillis() - last <= properties.getOfflineTimeoutMs();
    }

    public double getFps() {
        return isOnline() ? round1(currentFps) : 0.0;
    }

    public StreamStatusDto getStreamStatus() {
        long last = lastFrameTimeMs.get();
        long ageMs = last == 0 ? -1 : System.currentTimeMillis() - last;

        StreamStatusDto dto = new StreamStatusDto();
        dto.setOnline(isOnline());
        dto.setFps(getFps());
        dto.setLastFrameAgeMs(ageMs);
        dto.setRtspUrl(effectivePullUrl());
        dto.setTotalFrames(totalFrames.get());
        dto.setLastError(lastError);
        return dto;
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        reconnectRequested.set(true);
        grabExecutor.shutdownNow();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
