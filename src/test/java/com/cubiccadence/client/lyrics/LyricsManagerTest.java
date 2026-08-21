package com.cubiccadence.client.lyrics;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.model.Availability;
import com.cubiccadence.model.LyricLine;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.SyncedLyrics;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsManagerTest {
    private static final AuthSession SESSION = new AuthSession("netease", "MUSIC_U=test", Long.MAX_VALUE);

    @Test
    void staleResponseCannotReplaceLyricsForANewerTrack() {
        FakeProvider provider = new FakeProvider();
        LyricsManager manager = new LyricsManager(provider, () -> java.util.Optional.of(SESSION), Runnable::run);
        CompletableFuture<SyncedLyrics> first = new CompletableFuture<>();
        CompletableFuture<SyncedLyrics> second = new CompletableFuture<>();
        provider.responses.put("1", first);
        provider.responses.put("2", second);

        manager.tick(track("1"));
        manager.tick(track("2"));
        second.complete(lyrics("2", "第二首"));
        first.complete(lyrics("1", "第一首"));

        assertEquals(LyricsLoadState.READY, manager.getState());
        assertEquals("2", manager.getLyrics().orElseThrow().trackId());
        assertEquals("第二首", manager.getLyrics().orElseThrow().lines().getFirst().text());
    }

    @Test
    void cachesSuccessfulAndEmptyLyricsWithinTheSession() {
        FakeProvider provider = new FakeProvider();
        LyricsManager manager = new LyricsManager(provider, () -> java.util.Optional.of(SESSION), Runnable::run);
        provider.responses.put("1", CompletableFuture.completedFuture(lyrics("1", "歌词")));
        provider.responses.put("2", CompletableFuture.completedFuture(new SyncedLyrics("netease", "2", List.of())));

        manager.tick(track("1"));
        assertEquals(LyricsLoadState.READY, manager.getState());
        manager.tick(null);
        manager.tick(track("1"));
        assertEquals(1, provider.requests.get("1"));

        manager.tick(track("2"));
        assertEquals(LyricsLoadState.UNAVAILABLE, manager.getState());
        assertTrue(manager.getLyrics().orElseThrow().lines().isEmpty());
    }

    @Test
    void preloadsUpcomingLyricsWithoutDisturbingTheCurrentTrack() {
        FakeProvider provider = new FakeProvider();
        LyricsManager manager = new LyricsManager(provider, () -> java.util.Optional.of(SESSION), Runnable::run);
        provider.responses.put("1", CompletableFuture.completedFuture(lyrics("1", "第一首")));
        provider.responses.put("2", CompletableFuture.completedFuture(lyrics("2", "第二首")));

        manager.tick(track("1"));
        assertEquals("1", manager.getLyrics().orElseThrow().trackId());

        manager.preload(track("2"));
        assertEquals("1", manager.getLyrics().orElseThrow().trackId());
        assertEquals(1, provider.requests.get("2"));

        manager.tick(track("2"));
        assertEquals("2", manager.getLyrics().orElseThrow().trackId());
        assertEquals(1, provider.requests.get("2"));
    }

    private static Track track(String id) {
        return new Track("netease", id, "track", List.of(), "album", "", 60_000L, Availability.PLAYABLE);
    }

    private static SyncedLyrics lyrics(String id, String text) {
        return new SyncedLyrics("netease", id, List.of(new LyricLine(1_000L, text, "")));
    }

    private static final class FakeProvider implements MusicProvider {
        private final Map<String, CompletableFuture<SyncedLyrics>> responses = new HashMap<>();
        private final Map<String, Integer> requests = new HashMap<>();

        @Override public String id() { return "netease"; }
        @Override public CompletableFuture<AuthorizationChallenge> beginLogin() { return unsupported(); }
        @Override public CompletableFuture<AuthorizationResult> pollAuthorization(String authorizationId) { return unsupported(); }
        @Override public CompletableFuture<AuthSession> refresh(AuthSession session) { return unsupported(); }
        @Override public CompletableFuture<UserProfile> getCurrentUser(AuthSession session) { return unsupported(); }
        @Override public CompletableFuture<PlaylistSummaryPage> getUserPlaylists(AuthSession session, String userId, PageRequest pageRequest) { return unsupported(); }
        @Override public CompletableFuture<PlaylistPage> getPlaylistTracks(AuthSession session, String playlistId, PageRequest pageRequest) { return unsupported(); }
        @Override public CompletableFuture<SearchPage<?>> search(String keyword, SearchType type, PageRequest pageRequest) { return unsupported(); }
        @Override public CompletableFuture<PlaybackSource> resolvePlaybackSource(AuthSession session, String trackId, AudioQuality quality) { return unsupported(); }

        @Override
        public CompletableFuture<SyncedLyrics> getLyrics(AuthSession session, String trackId) {
            requests.merge(trackId, 1, Integer::sum);
            return responses.getOrDefault(trackId, unsupported());
        }

        @Override public CompletableFuture<Void> logout(AuthSession session) { return CompletableFuture.completedFuture(null); }

        private static <T> CompletableFuture<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
