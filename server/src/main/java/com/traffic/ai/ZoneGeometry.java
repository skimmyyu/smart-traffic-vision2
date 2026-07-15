package com.traffic.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Polygon helpers for no-parking zones (normalized or pixel coordinates).
 */
public final class ZoneGeometry {

    private ZoneGeometry() {
    }

    public static List<double[]> parsePoints(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<List<Number>> raw = mapper.readValue(json, new TypeReference<>() {});
            List<double[]> pts = new ArrayList<>(raw.size());
            for (List<Number> p : raw) {
                if (p == null || p.size() < 2) {
                    continue;
                }
                pts.add(new double[]{p.get(0).doubleValue(), p.get(1).doubleValue()});
            }
            return pts;
        } catch (Exception ignored) {
            // also accept [{x,y},...]
            try {
                List<Map<String, Object>> raw = mapper.readValue(json, new TypeReference<>() {});
                List<double[]> pts = new ArrayList<>(raw.size());
                for (Map<String, Object> p : raw) {
                    if (p == null || p.get("x") == null || p.get("y") == null) {
                        continue;
                    }
                    pts.add(new double[]{
                            ((Number) p.get("x")).doubleValue(),
                            ((Number) p.get("y")).doubleValue()
                    });
                }
                return pts;
            } catch (Exception ex) {
                return List.of();
            }
        }
    }

    /** Ray-casting point-in-polygon. */
    public static boolean contains(List<double[]> polygon, double x, double y) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i)[0];
            double yi = polygon.get(i)[1];
            double xj = polygon.get(j)[0];
            double yj = polygon.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-12) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    public static double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
