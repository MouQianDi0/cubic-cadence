package com.cubiccadence.client.playback;

import com.cubiccadence.model.PlaybackMode;
import com.cubiccadence.model.PlaybackState;
import com.cubiccadence.model.Track;
import com.cubiccadence.provider.MusicProvider;

public class PlayerController {
    private final MusicProvider provider;
    private final AudioEngine audioEngine;
    private final PlaybackQueue queue;
    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile Track currentTrack;

    public PlayerController(MusicProvider provider, AudioEngine audioEngine) {
        this.provider = provider;
        this.audioEngine = audioEngine;
        this.queue = new PlaybackQueue();
    }

    public PlaybackState getState() {
        return state;
    }

    public Track getCurrentTrack() {
        return currentTrack;
    }

    public void play(Track track) {
        // TODO: resolve the playback source and start playback
    }

    public void pause() {
        // TODO: pause playback
    }

    public void resume() {
        // TODO: resume playback
    }

    public void next() {
        // TODO: play the next track
    }

    public void previous() {
        // TODO: play the previous track
    }

    public void seekTo(long positionMs) {
        // TODO: seek to the given position
    }

    public void setVolume(float volume) {
        // TODO: set the volume
    }

    public void setPlaybackMode(PlaybackMode mode) {
        // TODO: set the playback mode
    }

    public void stop() {
        // TODO: stop playback
    }
}
