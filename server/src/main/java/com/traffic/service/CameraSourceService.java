package com.traffic.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Stores browser/phone camera frames and the source selected by the UI. */
@Service
public class CameraSourceService {

    public static final String SANDBOX = "sandbox";
    public static final String LOCAL = "local";
    public static final String PHONE = "phone";
    /** Phone IP Camera app RTSP pulled via Tailscale → MediaMTX cam-phone (separate from cam1) */
    public static final String IP_CAMERA = "ip-camera";

    private final AtomicReference<String> activeSource = new AtomicReference<>(SANDBOX);
    private final Map<String, BufferedImage> frames = new ConcurrentHashMap<>();
    private final Map<String, byte[]> jpegFrames = new ConcurrentHashMap<>();
    private final Map<String, Long> updatedAt = new ConcurrentHashMap<>();

    public String select(String sourceId) {
        validateExternalOrSandbox(sourceId);
        activeSource.set(sourceId);
        return sourceId;
    }

    public String getActiveSource() {
        return activeSource.get();
    }

    public void acceptJpeg(String sourceId, byte[] jpeg) {
        validateExternal(sourceId);
        if (jpeg == null || jpeg.length == 0 || jpeg.length > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("摄像头画面为空或超过 5MB");
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (decoded == null || decoded.getWidth() < 2 || decoded.getHeight() < 2) {
                throw new IllegalArgumentException("无法解析摄像头 JPEG 画面");
            }
            BufferedImage bgr = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bgr.createGraphics();
            try {
                g.drawImage(decoded, 0, 0, null);
            } finally {
                g.dispose();
            }
            frames.put(sourceId, bgr);
            jpegFrames.put(sourceId, jpeg.clone());
            updatedAt.put(sourceId, System.currentTimeMillis());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("摄像头画面解析失败", ex);
        }
    }

    public BufferedImage getFrameCopy(String sourceId) {
        BufferedImage src = frames.get(sourceId);
        if (src == null) return null;
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    public byte[] getJpegCopy(String sourceId) {
        byte[] bytes = jpegFrames.get(sourceId);
        return bytes == null ? null : bytes.clone();
    }

    public long getUpdatedAt(String sourceId) {
        return updatedAt.getOrDefault(sourceId, 0L);
    }

    public boolean isOnline(String sourceId) {
        if (SANDBOX.equals(sourceId)) return true;
        long last = getUpdatedAt(sourceId);
        return last > 0 && System.currentTimeMillis() - last < 3000;
    }

    private static void validateExternalOrSandbox(String sourceId) {
        if (!SANDBOX.equals(sourceId) && !LOCAL.equals(sourceId)
                && !PHONE.equals(sourceId) && !IP_CAMERA.equals(sourceId)) {
            throw new IllegalArgumentException("未知摄像头来源: " + sourceId);
        }
    }

    private static void validateExternal(String sourceId) {
        if (!LOCAL.equals(sourceId) && !PHONE.equals(sourceId)) {
            throw new IllegalArgumentException("该来源不接受浏览器画面: " + sourceId);
        }
    }
}
