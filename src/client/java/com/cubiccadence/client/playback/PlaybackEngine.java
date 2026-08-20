package com.cubiccadence.client.playback;

import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaybackState;

interface PlaybackEngine {
    void play(PlaybackSource source);

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
