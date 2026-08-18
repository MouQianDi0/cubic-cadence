package com.cubiccadence.client.playback;

import com.cubiccadence.model.PlaybackSource;

public class AudioEngine {
    public void start() {
        // TODO: initialize OpenAL output
    }

    public void stop() {
        // TODO: release OpenAL resources
    }

    public void play(PlaybackSource source) {
        // TODO: read, decode and play the source
    }

    public void pause() {
        // TODO: pause playback
    }

    public void resume() {
        // TODO: resume playback
    }

    public void seek(long positionMs) {
        // TODO: seek to the given position
    }

    public void setVolume(float volume) {
        // TODO: set the output volume
    }

    public long getPositionMs() {
        // TODO: return the current playback position
        return 0;
    }

    public long getDurationMs() {
        // TODO: return the total duration
        return 0;
    }
}
