package com.cubiccadence.client.auth;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthState;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.auth.AuthorizationStatus;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;
import com.cubiccadence.provider.SearchPage;
import com.cubiccadence.provider.SearchType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthManagerTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test
    void completesAuthorizationAndPersistsGatewaySession() {
        FakeProvider provider = new FakeProvider();
        MemoryTokenStore store = new MemoryTokenStore();
        AuthSession session = new AuthSession("netease", "cookie-value", NOW + 3_600_000L);
        provider.authorizationResult = new AuthorizationResult(AuthorizationStatus.AUTHORIZED, session, null);

        try (AuthManager manager = new AuthManager(provider, store, CLOCK)) {
            manager.beginLogin().join();
            manager.pollAuthorization().join();

            assertEquals(AuthState.SIGNED_IN, manager.getState());
            assertEquals(Optional.of(session), store.load());
        }
    }

    @Test
    void restoresAStillValidSessionWithoutRefreshing() {
        FakeProvider provider = new FakeProvider();
        MemoryTokenStore store = new MemoryTokenStore();
        store.save(new AuthSession("netease", "cookie-value", NOW + 3_600_000L));

        try (AuthManager manager = new AuthManager(provider, store, CLOCK)) {
            manager.restoreSession().join();

            assertEquals(AuthState.SIGNED_IN, manager.getState());
            assertEquals(0, provider.refreshCalls);
        }
    }

    @Test
    void refreshesAnExpiringSessionOnlyOnce() {
        FakeProvider provider = new FakeProvider();
        MemoryTokenStore store = new MemoryTokenStore();
        store.save(new AuthSession("netease", "old-cookie", NOW + 1_000L));
        provider.refreshedSession = new AuthSession("netease", "new-cookie", NOW + 3_600_000L);

        try (AuthManager manager = new AuthManager(provider, store, CLOCK)) {
            manager.restoreSession().join();

            assertEquals(AuthState.SIGNED_IN, manager.getState());
            assertEquals(1, provider.refreshCalls);
            assertEquals("new-cookie", store.load().orElseThrow().cookie());
        }
    }

    @Test
    void logoutClearsLocalStateEvenWhenRemoteRevocationFails() {
        FakeProvider provider = new FakeProvider();
        provider.logoutFailure = true;
        MemoryTokenStore store = new MemoryTokenStore();
        store.save(new AuthSession("netease", "cookie-value", NOW + 3_600_000L));

        try (AuthManager manager = new AuthManager(provider, store, CLOCK)) {
            manager.restoreSession().join();
            manager.logout().join();

            assertEquals(AuthState.SIGNED_OUT, manager.getState());
            assertTrue(store.load().isEmpty());
        }
    }

    @Test
    void exposesScannedStatusWithoutCompletingAuthorization() {
        FakeProvider provider = new FakeProvider();
        MemoryTokenStore store = new MemoryTokenStore();
        provider.authorizationResult = new AuthorizationResult(AuthorizationStatus.SCANNED, null, null);

        try (AuthManager manager = new AuthManager(provider, store, CLOCK)) {
            manager.beginLogin().join();
            manager.pollAuthorization().join();

            assertEquals(AuthState.AUTHORIZING, manager.getState());
            assertEquals(AuthorizationStatus.SCANNED, manager.getLastStatus());
        }
    }

    private static final class MemoryTokenStore implements SecureTokenStore {
        private AuthSession session;

        @Override
        public void save(AuthSession session) {
            this.session = session;
        }

        @Override
        public Optional<AuthSession> load() {
            return Optional.ofNullable(session);
        }

        @Override
        public void clear() {
            session = null;
        }
    }

    private static final class FakeProvider implements MusicProvider {
        private AuthorizationResult authorizationResult = new AuthorizationResult(
                AuthorizationStatus.PENDING, null, null
        );
        private AuthSession refreshedSession;
        private int refreshCalls;
        private boolean logoutFailure;

        @Override
        public String id() {
            return "netease";
        }

        @Override
        public CompletableFuture<AuthorizationChallenge> beginLogin() {
            return CompletableFuture.completedFuture(new AuthorizationChallenge(
                    "authorization-id", "https://music.163.com/", null, NOW + 60_000L, 60_000L
            ));
        }

        @Override
        public CompletableFuture<AuthorizationResult> pollAuthorization(String authorizationId) {
            return CompletableFuture.completedFuture(authorizationResult);
        }

        @Override
        public CompletableFuture<AuthSession> refresh(AuthSession session) {
            refreshCalls++;
            return CompletableFuture.completedFuture(refreshedSession);
        }

        @Override
        public CompletableFuture<Void> logout(AuthSession session) {
            return logoutFailure
                    ? CompletableFuture.failedFuture(new IllegalStateException("remote unavailable"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<UserProfile> getCurrentUser() {
            return unsupported();
        }

        @Override
        public CompletableFuture<List<PlaylistSummary>> getUserPlaylists() {
            return unsupported();
        }

        @Override
        public CompletableFuture<PlaylistPage> getPlaylistTracks(String playlistId, PageRequest pageRequest) {
            return unsupported();
        }

        @Override
        public CompletableFuture<SearchPage<?>> search(String keyword, SearchType type, PageRequest pageRequest) {
            return unsupported();
        }

        @Override
        public CompletableFuture<PlaybackSource> resolvePlaybackSource(String trackId, AudioQuality quality) {
            return unsupported();
        }

        private static <T> CompletableFuture<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
