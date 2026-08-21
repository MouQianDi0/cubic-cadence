package com.cubiccadence.client.ui.hud;

import com.cubiccadence.model.PlaybackState;
import com.cubiccadence.model.Track;

public record NowPlayingSnapshot(
        Track track,
        PlaybackState playbackState,
        long positionMs,
        long durationMs,
        String currentLyric,
        String nextLyric
) {
    public NowPlayingSnapshot {
        positionMs = Math.max(0L, positionMs);
        durationMs = Math.max(0L, durationMs);
        currentLyric = currentLyric == null ? "" : currentLyric.trim();
        nextLyric = nextLyric == null ? "" : nextLyric.trim();
    }
}
