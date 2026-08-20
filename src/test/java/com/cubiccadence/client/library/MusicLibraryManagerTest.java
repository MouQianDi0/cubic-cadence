package com.cubiccadence.client.library;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.client.auth.SecureTokenStore;
import com.cubiccadence.model.MembershipTier;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;
import com.cubiccadence.provider.PlaylistSummaryPage;
import com.cubiccadence.provider.SearchPage;
import com.cubiccadence.provider.SearchType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicLibraryManagerTest {
    @Test
    void loadsProfileAndEightItemPlaylistPageAfterSessionRestore() {
        FakeProvider provider = new FakeProvider();
        MemoryTokenStore store = validStore();
        provider.profileFuture = CompletableFuture.completedFuture(profile());

        try (AuthManager authManager = new AuthManager(provider, store);
             MusicLibraryManager libraryManager = new MusicLibraryManager(provider, authManager)) {
            authManager.restoreSession().join();
            libraryManager.tick();

            assertEquals(MusicLibraryManager.LoadState.READY, libraryManager.getLoadState());
            assertEquals("42", libraryManager.getProfile().orElseThrow().userId());
            assertEquals(MusicLibraryManager.PAGE_SIZE, provider.lastPageRequest.pageSize());
            assertEquals(0, provider.lastPageRequest.page());
        }
    }

    @Test
    void ignoresLateProfileResponseAfterLogout() {
        FakeProvider provider = new FakeProvider();
        MemoryTokenStore store = validStore();
        provider.profileFuture = new CompletableFuture<>();

        try (AuthManager authManager = new AuthManager(provider, store);
             MusicLibraryManager libraryManager = new MusicLibraryManager(provider, authManager)) {
            authManager.restoreSession().join();
            libraryManager.tick();
            assertEquals(MusicLibraryManager.LoadState.LOADING_PROFILE, libraryManager.getLoadState());

            authManager.logout().join();
            libraryManager.tick();
            provider.profileFuture.complete(profile());

            assertEquals(MusicLibraryManager.LoadState.IDLE, libraryManager.getLoadState());
            assertTrue(libraryManager.getProfile().isEmpty());
            assertTrue(libraryManager.getPlaylistPage().isEmpty());
        }
    }

    private static MemoryTokenStore validStore() {
        MemoryTokenStore store = new MemoryTokenStore();
        store.save(new AuthSession("netease", "cookie-value", System.currentTimeMillis() + 3_600_000L));
        return store;
    }

    private static UserProfile profile() {
        return new UserProfile("netease", "42", "test", "", 8, MembershipTier.BLACK_VINYL_VIP);
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
        private CompletableFuture<UserProfile> profileFuture;
        private PageRequest lastPageRequest;

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
            return unsupported();
        }

        @Override
        public CompletableFuture<UserProfile> getCurrentUser(AuthSession session) {
            return profileFuture;
        }

        @Override
        public CompletableFuture<PlaylistSummaryPage> getUserPlaylists(
                AuthSession session,
                String userId,
                PageRequest pageRequest
        ) {
            lastPageRequest = pageRequest;
            return CompletableFuture.completedFuture(new PlaylistSummaryPage(java.util.List.of(), false, 0));
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

        @Override
        public CompletableFuture<Void> logout(AuthSession session) {
            return CompletableFuture.completedFuture(null);
        }

        private static <T> CompletableFuture<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
