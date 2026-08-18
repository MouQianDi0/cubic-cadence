package com.cubiccadence.client.playback;

import com.cubiccadence.model.PlaybackMode;
import com.cubiccadence.model.Track;

import java.util.ArrayList;
import java.util.List;

public class PlaybackQueue {
    private final List<Track> tracks = new ArrayList<>();
    private int cursor = -1;
    private PlaybackMode mode = PlaybackMode.SEQUENTIAL;

    public void setTracks(List<Track> tracks) {
        this.tracks.clear();
        this.tracks.addAll(tracks);
        this.cursor = this.tracks.isEmpty() ? -1 : 0;
    }

    public Track current() {
        if (cursor < 0 || cursor >= tracks.size()) {
            return null;
        }
        return tracks.get(cursor);
    }

    public Track next() {
        // TODO: advance the cursor according to PlaybackMode
        return null;
    }

    public Track previous() {
        // TODO: retreat the cursor according to PlaybackMode
        return null;
    }

    public void setMode(PlaybackMode mode) {
        this.mode = mode;
    }

    public PlaybackMode getMode() {
        return mode;
    }

    public void clear() {
        tracks.clear();
        cursor = -1;
    }
}
