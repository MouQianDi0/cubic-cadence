package com.cubiccadence.model;

import com.cubiccadence.provider.AudioQuality;

import java.net.URI;
import java.util.Map;

public record PlaybackSource(
        URI uri,
        String contentType,
        long expiresAtEpochMs,
        Map<String, String> requestHeaders,
        AudioQuality quality,
        Integer bitrate,
        PlaybackAccess access,
        long playableDurationMs,
        long timelineOffsetMs
) {
    public PlaybackSource {
        if (uri == null || uri.getScheme() == null) {
            throw new IllegalArgumentException("playback source URI must be absolute");
        }
        contentType = contentType == null ? "" : contentType.trim();
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        if (quality == null) {
            throw new IllegalArgumentException("playback quality is required");
        }
        access = access == null ? PlaybackAccess.FULL : access;
        playableDurationMs = Math.max(0L, playableDurationMs);
        timelineOffsetMs = Math.max(0L, timelineOffsetMs);
    }
}
