package com.cubiccadence.client.library;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.client.auth.SecureTokenStore;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaylistOwnership;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;
import com.cubiccadence.provider.PlaylistSummaryPage;
import com.cubiccadence.provider.SearchPage;
import com.cubiccadence.provider.SearchType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistDetailManagerTest {
    @Test
    void doesNotRequestTracksUntilPlaylistIsOpened() {
        FakeProvider provider = new FakeProvider();
        try (AuthManager authManager = signedInManager(provider);
             PlaylistDetailManager detailManager = new PlaylistDetailManager(provider, authManager)) {
            assertEquals(0, provider.requests.size());

            detailManager.open(playlist(75));

            assertEquals(1, provider.requests.size());
            assertEquals(0, provider.requests.getFirst().page());
            assertEquals(PlaylistDetailManager.PAGE_SIZE, provider.requests.getFirst().pageSize());
            assertEquals(PlaylistDetailManager.LoadState.READY, detailManager.getLoadState());
        }
    }

    @Test
    void pagesLazilyAndUsesSummaryCountToStopAtTheLastPage() {
        FakeProvider provider = new FakeProvider();
        provider.response = new PlaylistPage(List.of(), true, "next");
        try (AuthManager authManager = signedInManager(provider);
             PlaylistDetailManager detailManager = new PlaylistDetailManager(provider, authManager)) {
            detailManager.open(playlist(75));
            assertTrue(detailManager.hasNextPage());

            detailManager.loadPage(1);

            assertEquals(2, provider.requests.size());
            assertEquals(1, detailManager.getCurrentPage());
            assertTrue(!detailManager.hasNextPage());
        }
    }

    @Test
    void retryRepeatsOnlyTheFailedRequestedPage() {
        FakeProvider provider = new FakeProvider();
        provider.failure = true;
        try (AuthManager authManager = signedInManager(provider);
             PlaylistDetailManager detailManager = new PlaylistDetailManager(provider, authManager)) {
            detailManager.open(playlist(75));
            assertEquals(PlaylistDetailManager.LoadState.ERROR, detailManager.getLoadState());

            provider.failure = false;
            detailManager.retry();

            assertEquals(2, provider.requests.size());
            assertEquals(0, provider.requests.getLast().page());
            assertEquals(PlaylistDetailManager.LoadState.READY, detailManager.getLoadState());
        }
    }

    @Test
    void ignoresLateTrackResponseAfterLogout() {
        FakeProvider provider = new FakeProvider();
        CompletableFuture<PlaylistPage> pending = new CompletableFuture<>();
        provider.pending = pending;
        try (AuthManager authManager = signedInManager(provider);
             PlaylistDetailManager detailManager = new PlaylistDetailManager(provider, authManager)) {
            detailManager.open(playlist(75));
            authManager.logout().join();
            detailManager.tick();
            pending.complete(new PlaylistPage(List.of(), false, null));

            assertEquals(PlaylistDetailManager.LoadState.IDLE, detailManager.getLoadState());
            assertTrue(detailManager.getSelectedPlaylist().isEmpty());
            assertTrue(detailManager.getTrackPage().isEmpty());
        }
    }

    private static AuthManager signedInManager(FakeProvider provider) {
        MemoryTokenStore store = new MemoryTokenStore();
        store.save(new AuthSession("netease", "cookie", System.currentTimeMillis() + 3_600_000L));
        AuthManager authManager = new AuthManager(provider, store);
        authManager.restoreSession().join();
        return authManager;
    }

    private static PlaylistSummary playlist(int trackCount) {
        return new PlaylistSummary(
                "netease",
                "1001",
                "test",
                "",
                trackCount,
                PlaylistOwnership.CREATED
        );
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
        private final List<PageRequest> requests = new ArrayList<>();
        private PlaylistPage response = new PlaylistPage(List.of(), false, null);
        private CompletableFuture<PlaylistPage> pending;
        private boolean failure;

        @Override
        public String id() {
            return "netease";
        }

        @Override
        public CompletableFuture<AuthorizationChallenge> beginLogin() {
            return unsupported();
        }

        @Override
        public CompletableFuture<AuthorizationResult> pollAuthorization(String authorizationId) {
            return unsupported();
        }

        @Override
        public CompletableFuture<AuthSession> refresh(AuthSession session) {
            return CompletableFuture.completedFuture(session);
        }

        @Override
        public CompletableFuture<UserProfile> getCurrentUser(AuthSession session) {
            return unsupported();
        }

        @Override
        public CompletableFuture<PlaylistSummaryPage> getUserPlaylists(
                AuthSession session,
                String userId,
                PageRequest pageRequest
        ) {
            return unsupported();
        }

        @Override
        public CompletableFuture<PlaylistPage> getPlaylistTracks(
                AuthSession session,
                String playlistId,
                PageRequest pageRequest
        ) {
            requests.add(pageRequest);
            if (pending != null) {
                return pending;
            }
            return failure
                    ? CompletableFuture.failedFuture(new IllegalStateException("failed"))
                    : CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<SearchPage<?>> search(String keyword, SearchType type, PageRequest pageRequest) {
            return unsupported();
        }

        @Override
        public CompletableFuture<PlaybackSource> resolvePlaybackSource(
                AuthSession session,
                String trackId,
                AudioQuality quality
        ) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> logout(AuthSession session) {
            return CompletableFuture.completedFuture(null);
        }

        private static <T> CompletableFuture<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
