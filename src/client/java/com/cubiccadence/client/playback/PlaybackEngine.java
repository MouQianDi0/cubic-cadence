package com.cubiccadence.client.playback;

import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaybackState;

interface PlaybackEngine {
    void play(PlaybackSource source);

    /**
     * Optionally opens and pre-buffers an online source in the background so a
     * later {@link #play(PlaybackSource)} call can start immediately. The call
     * is best-effort and must be safe to ignore; local sources may no-op.
     */
    void preload(PlaybackSource source);

    /** Discards any pending preload without affecting the active playback. */
    void cancelPreload();

    void pause();

    void resume();

    void stop();

    void seek(long positionMs);

    void setVolume(float volume);

    float getVolume();

    PlaybackState getState();

    String getLastError();

    long getPositionMs();

    long getDurationMs();

    boolean isSeekSupported();
}
