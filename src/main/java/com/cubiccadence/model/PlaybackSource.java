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
        Integer bitrate
) {
}
