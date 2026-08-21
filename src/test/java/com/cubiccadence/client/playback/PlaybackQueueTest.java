package com.cubiccadence.client.playback;

import com.cubiccadence.model.Availability;
import com.cubiccadence.model.PlaybackMode;
import com.cubiccadence.model.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlaybackQueueTest {
    @Test
    void sequentialModeStartsAtSelectionAndSkipsRestrictedTracks() {
        Track first = track("1", Availability.PLAYABLE);
        Track restricted = track("2", Availability.COPYRIGHT_RESTRICTED);
        Track third = track("3", Availability.UNKNOWN);
        PlaybackQueue queue = new PlaybackQueue(new Random(1));

        queue.setTracks(List.of(first, restricted, third), 0);

        assertSame(first, queue.current());
        assertSame(third, queue.next());
        assertNull(queue.next());
        assertSame(first, queue.previous());
    }

    @Test
    void repeatOneOnlyRepeatsForNaturalCompletion() {
        Track first = track("1", Availability.PLAYABLE);
        Track second = track("2", Availability.PLAYABLE);
        PlaybackQueue queue = new PlaybackQueue(new Random(1));
        queue.setTracks(List.of(first, second), 0);
        queue.setMode(PlaybackMode.REPEAT_ONE);

        assertSame(first, queue.nextAfterEnd());
        assertSame(second, queue.next());
    }

    @Test
    void repeatAllWrapsAtQueueBoundary() {
        Track first = track("1", Availability.PLAYABLE);
        Track second = track("2", Availability.PLAYABLE);
        PlaybackQueue queue = new PlaybackQueue(new Random(1));
        queue.setTracks(List.of(first, second), 1);
        queue.setMode(PlaybackMode.REPEAT_ALL);

        assertSame(first, queue.nextAfterEnd());
        assertSame(second, queue.previous());
    }

    @Test
    void shuffleKeepsSelectedTrackFirstAndPreviousUsesPlayedOrder() {
        PlaybackQueue queue = new PlaybackQueue(new Random(7));
        List<Track> tracks = List.of(
                track("1", Availability.PLAYABLE),
                track("2", Availability.PLAYABLE),
                track("3", Availability.PLAYABLE)
        );
        queue.setTracks(tracks, 1);
        queue.setMode(PlaybackMode.SHUFFLE);

        Track selected = queue.current();
        Track next = queue.next();

        assertEquals("2", selected.trackId());
        assertSame(selected, queue.previous());
        assertSame(next, queue.next());
    }

    @Test
    void peekNextAfterEndDoesNotAdvanceTheQueue() {
        Track first = track("1", Availability.PLAYABLE);
        Track second = track("2", Availability.PLAYABLE);
        PlaybackQueue queue = new PlaybackQueue(new Random(1));
        queue.setTracks(List.of(first, second), 0);

        assertSame(second, queue.peekNextAfterEnd());
        assertSame(first, queue.current());
        assertSame(second, queue.nextAfterEnd());
        assertSame(second, queue.current());
    }

    @Test
    void peekNextAfterEndWrapsForRepeatAll() {
        Track first = track("1", Availability.PLAYABLE);
        Track second = track("2", Availability.PLAYABLE);
        PlaybackQueue queue = new PlaybackQueue(new Random(1));
        queue.setTracks(List.of(first, second), 1);
        queue.setMode(PlaybackMode.REPEAT_ALL);

        assertSame(first, queue.peekNextAfterEnd());
        assertSame(second, queue.current());
    }

    @Test
    void peekNextAfterEndRepeatsCurrentForRepeatOne() {
        Track first = track("1", Availability.PLAYABLE);
        Track second = track("2", Availability.PLAYABLE);
        PlaybackQueue queue = new PlaybackQueue(new Random(1));
        queue.setTracks(List.of(first, second), 0);
        queue.setMode(PlaybackMode.REPEAT_ONE);

        assertSame(first, queue.peekNextAfterEnd());
    }

    @Test
    void peekNextAfterEndReturnsUpcomingShuffleEntryWithoutAdvancing() {
        PlaybackQueue queue = new PlaybackQueue(new Random(7));
        List<Track> tracks = List.of(
                track("1", Availability.PLAYABLE),
                track("2", Availability.PLAYABLE),
                track("3", Availability.PLAYABLE)
        );
        queue.setTracks(tracks, 1);
        queue.setMode(PlaybackMode.SHUFFLE);

        Track peeked = queue.peekNextAfterEnd();
        Track advanced = queue.next();

        assertSame(peeked, advanced);
    }

    private static Track track(String id, Availability availability) {
        return new Track("netease", id, "track-" + id, List.of(), "album", "", 60_000L, availability);
    }
}
