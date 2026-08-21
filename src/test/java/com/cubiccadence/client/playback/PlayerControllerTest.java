package com.cubiccadence.client.playback;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.model.Availability;
import com.cubiccadence.model.PlaybackAccess;
import com.cubiccadence.model.PlaybackMode;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaybackState;
import com.cubiccadence.model.Track;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;
import com.cubiccadence.provider.PlaylistSummaryPage;
import com.cubiccadence.provider.SearchPage;
import com.cubiccadence.provider.SearchType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerControllerTest {
    private static final AuthSession SESSION = new AuthSession("netease", "MUSIC_U=secret", Long.MAX_VALUE);

    @Test
    void resolvesSelectedTrackAndStartsBuffering() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        Track track = track("1");
        provider.sources.put("1", CompletableFuture.completedFuture(source("1")));

        controller.playQueue(List.of(track), 0);

        assertSame(track, controller.getCurrentTrack());
        assertEquals(PlaybackState.BUFFERING, controller.getState());
        assertEquals("/1.mp3", engine.played.uri().getPath());
        assertEquals(AudioQuality.STANDARD, provider.lastQuality);
    }

    @Test
    void staleResolutionCannotReplaceNewerTrack() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        CompletableFuture<PlaybackSource> first = new CompletableFuture<>();
        provider.sources.put("1", first);
        provider.sources.put("2", CompletableFuture.completedFuture(source("2")));

        controller.playQueue(List.of(track("1"), track("2")), 0);
        controller.next();
        first.complete(source("1"));

        assertEquals("2", controller.getCurrentTrack().trackId());
        assertEquals("/2.mp3", engine.played.uri().getPath());
    }

    @Test
    void repeatOneReResolvesAfterNaturalCompletion() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        provider.sources.put("1", CompletableFuture.completedFuture(source("1")));
        controller.setPlaybackMode(PlaybackMode.REPEAT_ONE);
        controller.play(track("1"));
        int firstResolveCount = provider.resolveCount;

        engine.state = PlaybackState.ENDED;
        controller.tick();

        assertEquals(firstResolveCount + 1, provider.resolveCount);
        assertEquals("1", controller.getCurrentTrack().trackId());
    }

    @Test
    void refusesPlaybackWithoutAuthenticatedSession() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.empty());

        controller.play(track("1"));

        assertEquals(PlaybackState.ERROR, controller.getState());
        assertNull(engine.played);
    }

    @Test
    void retriesAnExpiredTemporarySourceOnlyOnce() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        PlaybackSource expired = new PlaybackSource(
                URI.create("https://media.example/expired.mp3"),
                "audio/mpeg",
                System.currentTimeMillis() - 1L,
                Map.of(),
                AudioQuality.STANDARD,
                192000,
                PlaybackAccess.FULL,
                60_000L,
                0L
        );
        provider.sources.put("1", CompletableFuture.completedFuture(expired));
        controller.play(track("1"));

        engine.state = PlaybackState.ERROR;
        controller.tick();
        engine.state = PlaybackState.ERROR;
        controller.tick();

        assertEquals(2, provider.resolveCount);
        assertEquals(PlaybackState.ERROR, controller.getState());
    }

    @Test
    void streamingFailureDoesNotAdvanceToTheNextTrack() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        provider.sources.put("1", CompletableFuture.completedFuture(source("1")));
        provider.sources.put("2", CompletableFuture.completedFuture(source("2")));
        controller.playQueue(List.of(track("1"), track("2")), 0);
        int resolveCountBeforeFailure = provider.resolveCount;

        engine.state = PlaybackState.ERROR;
        controller.tick();

        assertEquals(PlaybackState.ERROR, controller.getState());
        assertEquals("1", controller.getCurrentTrack().trackId());
        assertEquals(resolveCountBeforeFailure, provider.resolveCount);
    }

    @Test
    void exposesOriginalTimelinePositionForTrialLyrics() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        PlaybackSource trial = new PlaybackSource(
                URI.create("https://media.example/trial.mp3"),
                "audio/mpeg",
                System.currentTimeMillis() + 60_000L,
                Map.of(),
                AudioQuality.STANDARD,
                192000,
                PlaybackAccess.TRIAL,
                30_000L,
                45_000L
        );
        provider.sources.put("1", CompletableFuture.completedFuture(trial));
        controller.play(track("1"));
        engine.positionMs = 5_000L;

        assertEquals(50_000L, controller.getTimelinePositionMs());
        assertEquals(5_000L, controller.getPositionMs());
    }

    @Test
    void preloadsNextTrackAndReusesItOnNaturalEnd() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        provider.sources.put("1", CompletableFuture.completedFuture(source("1")));
        provider.sources.put("2", CompletableFuture.completedFuture(source("2")));
        controller.playQueue(List.of(track("1"), track("2")), 0);

        engine.state = PlaybackState.PLAYING;
        controller.tick();

        assertEquals(2, provider.resolveCount);
        assertNotNull(engine.preloaded);

        engine.state = PlaybackState.ENDED;
        controller.tick();

        assertEquals("2", controller.getCurrentTrack().trackId());
        assertEquals(2, provider.resolveCount);
        assertEquals("/2.mp3", engine.played.uri().getPath());
    }

    @Test
    void manualNextReusesTheAlreadyPreloadedTrack() {
        FakeProvider provider = new FakeProvider();
        FakeEngine engine = new FakeEngine();
        PlayerController controller = controller(provider, engine, Optional.of(SESSION));
        provider.sources.put("1", CompletableFuture.completedFuture(source("1")));
        provider.sources.put("2", CompletableFuture.completedFuture(source("2")));
        controller.playQueue(List.of(track("1"), track("2")), 0);

        engine.state = PlaybackState.PLAYING;
        controller.tick();
        int resolveCountAfterPreload = provider.resolveCount;

        controller.next();

        assertEquals("2", controller.getCurrentTrack().trackId());
        assertEquals(resolveCountAfterPreload, provider.resolveCount);
        assertEquals("/2.mp3", engine.played.uri().getPath());
    }

    private static PlayerController controller(
            FakeProvider provider,
            FakeEngine engine,
            Optional<AuthSession> session
    ) {
        return new PlayerController(
                provider,
                () -> session,
                engine,
                new PlaybackQueue(new java.util.Random(1)),
                Runnable::run,
                () -> AudioQuality.STANDARD
        );
    }

    private static Track track(String id) {
        return new Track("netease", id, "track-" + id, List.of(), "album", "", 60_000L, Availability.PLAYABLE);
    }

    private static PlaybackSource source(String id) {
        return new PlaybackSource(
                URI.create("https://media.example/" + id + ".mp3"),
                "audio/mpeg",
                System.currentTimeMillis() + 60_000L,
                Map.of(),
                AudioQuality.STANDARD,
                192000,
                PlaybackAccess.FULL,
                60_000L,
                0L
        );
    }

    private static final class FakeEngine implements PlaybackEngine {
        private PlaybackState state = PlaybackState.IDLE;
        private PlaybackSource played;
        private PlaybackSource preloaded;
        private long positionMs;

        @Override public void play(PlaybackSource source) { played = source; state = PlaybackState.BUFFERING; }
        @Override public void preload(PlaybackSource source) { preloaded = source; }
        @Override public void cancelPreload() { preloaded = null; }
        @Override public void pause() { state = PlaybackState.PAUSED; }
        @Override public void resume() { state = PlaybackState.PLAYING; }
        @Override public void stop() { state = PlaybackState.IDLE; }
        @Override public void seek(long positionMs) { }
        @Override public void setVolume(float volume) { }
        @Override public float getVolume() { return 1.0f; }
        @Override public PlaybackState getState() { return state; }
        @Override public String getLastError() { return null; }
        @Override public long getPositionMs() { return positionMs; }
        @Override public long getDurationMs() { return played == null ? 0L : played.playableDurationMs(); }
        @Override public boolean isSeekSupported() { return false; }
    }

    private static final class FakeProvider implements MusicProvider {
        private final Map<String, CompletableFuture<PlaybackSource>> sources = new HashMap<>();
        private int resolveCount;
        private AudioQuality lastQuality;

        @Override public String id() { return "netease"; }
        @Override public CompletableFuture<AuthorizationChallenge> beginLogin() { return unsupported(); }
        @Override public CompletableFuture<AuthorizationResult> pollAuthorization(String id) { return unsupported(); }
        @Override public CompletableFuture<AuthSession> refresh(AuthSession session) { return unsupported(); }
        @Override public CompletableFuture<UserProfile> getCurrentUser(AuthSession session) { return unsupported(); }
        @Override public CompletableFuture<PlaylistSummaryPage> getUserPlaylists(AuthSession session, String userId, PageRequest request) { return unsupported(); }
        @Override public CompletableFuture<PlaylistPage> getPlaylistTracks(AuthSession session, String id, PageRequest request) { return unsupported(); }
        @Override public CompletableFuture<SearchPage<?>> search(String keyword, SearchType type, PageRequest request) { return unsupported(); }

        @Override
        public CompletableFuture<PlaybackSource> resolvePlaybackSource(
                AuthSession session,
                String trackId,
                AudioQuality quality
        ) {
            resolveCount++;
            lastQuality = quality;
            return sources.getOrDefault(trackId, unsupported());
        }

        @Override public CompletableFuture<Void> logout(AuthSession session) { return CompletableFuture.completedFuture(null); }

        private static <T> CompletableFuture<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
