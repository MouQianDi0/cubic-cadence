package com.cubiccadence.client.playback;

import com.cubiccadence.model.Availability;
import com.cubiccadence.model.PlaybackMode;
import com.cubiccadence.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

public final class PlaybackQueue {
    private final List<Track> tracks = new ArrayList<>();
    private final List<Integer> shuffleOrder = new ArrayList<>();
    private final RandomGenerator random;
    private int cursor = -1;
    private int shufflePosition = -1;
    private PlaybackMode mode = PlaybackMode.SEQUENTIAL;

    public PlaybackQueue() {
        this(RandomGenerator.getDefault());
    }

    PlaybackQueue(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public void setTracks(List<Track> tracks) {
        setTracks(tracks, 0);
    }

    public void setTracks(List<Track> tracks, int selectedIndex) {
        Objects.requireNonNull(tracks, "tracks");
        this.tracks.clear();
        this.tracks.addAll(tracks);
        this.cursor = this.tracks.isEmpty()
                ? -1
                : Math.max(0, Math.min(selectedIndex, this.tracks.size() - 1));
        rebuildShuffleOrder();
    }

    public Track current() {
        return trackAt(cursor);
    }

    public Track next() {
        return advance(false);
    }

    public Track nextAfterEnd() {
        return advance(true);
    }

    public Track previous() {
        if (tracks.isEmpty()) {
            return null;
        }
        if (mode == PlaybackMode.SHUFFLE) {
            if (shufflePosition > 0) {
                cursor = shuffleOrder.get(--shufflePosition);
                return current();
            }
            return null;
        }
        int previous = findPlayable(cursor - 1, -1);
        if (previous < 0 && mode == PlaybackMode.REPEAT_ALL) {
            previous = findPlayable(tracks.size() - 1, -1);
        }
        if (previous < 0) {
            return null;
        }
        cursor = previous;
        syncShufflePosition();
        return current();
    }

    public void setMode(PlaybackMode mode) {
        PlaybackMode nextMode = Objects.requireNonNull(mode, "mode");
        if (this.mode == nextMode) {
            return;
        }
        this.mode = nextMode;
        if (nextMode == PlaybackMode.SHUFFLE) {
            rebuildShuffleOrder();
        }
    }

    public PlaybackMode getMode() {
        return mode;
    }

    public List<Track> tracks() {
        return List.copyOf(tracks);
    }

    public void clear() {
        tracks.clear();
        shuffleOrder.clear();
        cursor = -1;
        shufflePosition = -1;
    }

    private Track advance(boolean naturalEnd) {
        if (tracks.isEmpty()) {
            return null;
        }
        if (naturalEnd && mode == PlaybackMode.REPEAT_ONE && current() != null) {
            return current();
        }
        if (mode == PlaybackMode.SHUFFLE) {
            return nextShuffled();
        }
        int next = findPlayable(cursor + 1, 1);
        if (next < 0 && mode == PlaybackMode.REPEAT_ALL) {
            next = findPlayable(0, 1);
        }
        if (next < 0) {
            return null;
        }
        cursor = next;
        syncShufflePosition();
        return current();
    }

    private Track nextShuffled() {
        if (shufflePosition + 1 < shuffleOrder.size()) {
            cursor = shuffleOrder.get(++shufflePosition);
            return current();
        }
        if (shuffleOrder.size() > 1) {
            int last = cursor;
            rebuildShuffleOrder();
            if (shuffleOrder.size() > 1 && shuffleOrder.get(0) == last) {
                Collections.swap(shuffleOrder, 0, 1);
            }
            shufflePosition = 0;
            cursor = shuffleOrder.get(0);
            return current();
        }
        return null;
    }

    private int findPlayable(int start, int step) {
        for (int index = start; index >= 0 && index < tracks.size(); index += step) {
            if (canAttempt(tracks.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private void rebuildShuffleOrder() {
        shuffleOrder.clear();
        for (int index = 0; index < tracks.size(); index++) {
            if (canAttempt(tracks.get(index))) {
                shuffleOrder.add(index);
            }
        }
        Collections.shuffle(shuffleOrder, new java.util.Random(random.nextLong()));
        if (cursor >= 0 && shuffleOrder.remove(Integer.valueOf(cursor))) {
            shuffleOrder.add(0, cursor);
            shufflePosition = 0;
        } else {
            shufflePosition = shuffleOrder.isEmpty() ? -1 : 0;
            if (!shuffleOrder.isEmpty()) {
                cursor = shuffleOrder.get(0);
            }
        }
    }

    private void syncShufflePosition() {
        shufflePosition = shuffleOrder.indexOf(cursor);
    }

    private Track trackAt(int index) {
        return index < 0 || index >= tracks.size() ? null : tracks.get(index);
    }

    public static boolean canAttempt(Track track) {
        Availability availability = track.availability();
        return availability == Availability.PLAYABLE || availability == Availability.UNKNOWN;
    }
}
