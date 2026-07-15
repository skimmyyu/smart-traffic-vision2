package com.traffic.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ByteTrack-style multi-object tracker (IoU association, high/low score cascade).
 * Reference: Zhang et al., "ByteTrack: Multi-Object Tracking by Associating Every Detection Box".
 */
public class ByteTrackTracker {

    private final float trackHighThresh;
    private final float trackLowThresh;
    private final float matchThresh;
    private final int trackBuffer;

    private final List<STrack> tracked = new ArrayList<>();
    private final List<STrack> lost = new ArrayList<>();

    public ByteTrackTracker(float trackHighThresh, float trackLowThresh, float matchThresh, int trackBuffer) {
        this.trackHighThresh = trackHighThresh;
        this.trackLowThresh = trackLowThresh;
        this.matchThresh = matchThresh;
        this.trackBuffer = Math.max(1, trackBuffer);
    }

    public synchronized void reset() {
        tracked.clear();
        lost.clear();
        STrack.resetIdCounter();
    }

    /**
     * Update tracker with current-frame detections; returns active (Tracked) tracks.
     */
    public synchronized List<STrack> update(List<TrackDetection> detections) {
        List<TrackDetection> high = new ArrayList<>();
        List<TrackDetection> low = new ArrayList<>();
        for (TrackDetection d : detections) {
            if (d.score >= trackHighThresh) {
                high.add(d);
            } else if (d.score >= trackLowThresh) {
                low.add(d);
            }
        }

        List<STrack> pool = new ArrayList<>(tracked.size() + lost.size());
        pool.addAll(tracked);
        pool.addAll(lost);

        // 1) Associate high-score detections with existing tracks
        MatchResult first = associate(pool, high, matchThresh);
        List<STrack> activated = new ArrayList<>();
        List<STrack> refound = new ArrayList<>();

        for (int[] pair : first.matches) {
            STrack track = pool.get(pair[0]);
            TrackDetection det = high.get(pair[1]);
            if (track.getState() == STrack.State.Tracked) {
                track.update(det);
                activated.add(track);
            } else {
                track.update(det);
                track.activate();
                refound.add(track);
            }
        }

        List<STrack> unmatchedTracks = new ArrayList<>();
        for (int i : first.unmatchedTracks) {
            unmatchedTracks.add(pool.get(i));
        }

        // 2) Associate remaining tracks with low-score detections
        MatchResult second = associate(unmatchedTracks, low, matchThresh);
        for (int[] pair : second.matches) {
            STrack track = unmatchedTracks.get(pair[0]);
            TrackDetection det = low.get(pair[1]);
            if (track.getState() == STrack.State.Tracked) {
                track.update(det);
                activated.add(track);
            } else {
                track.update(det);
                track.activate();
                refound.add(track);
            }
        }

        List<STrack> stillUnmatched = new ArrayList<>();
        for (int i : second.unmatchedTracks) {
            stillUnmatched.add(unmatchedTracks.get(i));
        }
        for (STrack t : stillUnmatched) {
            t.markLost();
        }

        // 3) New tracks from unmatched high-score detections
        List<STrack> newTracks = new ArrayList<>();
        for (int i : first.unmatchedDets) {
            TrackDetection det = high.get(i);
            STrack t = new STrack(det);
            t.activate();
            newTracks.add(t);
        }

        // 4) Rebuild tracked / lost lists
        List<STrack> nextTracked = new ArrayList<>();
        nextTracked.addAll(activated);
        nextTracked.addAll(refound);
        nextTracked.addAll(newTracks);

        List<STrack> nextLost = new ArrayList<>();
        for (STrack t : stillUnmatched) {
            if (t.getTimeSinceUpdate() > trackBuffer) {
                t.markRemoved();
            } else {
                nextLost.add(t);
            }
        }
        // Keep previously lost that were not in pool match this frame? already handled via pool.
        // Also drop removed from lost that got refound (already in nextTracked).

        tracked.clear();
        tracked.addAll(nextTracked);
        lost.clear();
        lost.addAll(nextLost);

        return new ArrayList<>(tracked);
    }

    private static MatchResult associate(List<STrack> tracks, List<TrackDetection> dets, float iouThresh) {
        MatchResult result = new MatchResult();
        int n = tracks.size();
        int m = dets.size();
        if (n == 0 || m == 0) {
            for (int i = 0; i < n; i++) {
                result.unmatchedTracks.add(i);
            }
            for (int j = 0; j < m; j++) {
                result.unmatchedDets.add(j);
            }
            return result;
        }

        float[][] cost = new float[n][m];
        for (int i = 0; i < n; i++) {
            TrackDetection td = tracks.get(i).getDetection();
            for (int j = 0; j < m; j++) {
                cost[i][j] = 1f - TrackDetection.iou(td, dets.get(j));
            }
        }

        boolean[] trackUsed = new boolean[n];
        boolean[] detUsed = new boolean[m];

        // Greedy IoU matching (sorted by cost ascending)
        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                candidates.add(new int[]{i, j});
            }
        }
        candidates.sort(Comparator.comparingDouble(a -> cost[a[0]][a[1]]));

        for (int[] c : candidates) {
            int i = c[0];
            int j = c[1];
            if (trackUsed[i] || detUsed[j]) {
                continue;
            }
            float iou = 1f - cost[i][j];
            if (iou < iouThresh) {
                continue;
            }
            trackUsed[i] = true;
            detUsed[j] = true;
            result.matches.add(new int[]{i, j});
        }

        for (int i = 0; i < n; i++) {
            if (!trackUsed[i]) {
                result.unmatchedTracks.add(i);
            }
        }
        for (int j = 0; j < m; j++) {
            if (!detUsed[j]) {
                result.unmatchedDets.add(j);
            }
        }
        return result;
    }

    private static final class MatchResult {
        final List<int[]> matches = new ArrayList<>();
        final List<Integer> unmatchedTracks = new ArrayList<>();
        final List<Integer> unmatchedDets = new ArrayList<>();
    }
}
