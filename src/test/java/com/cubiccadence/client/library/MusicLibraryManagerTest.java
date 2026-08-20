package com.cubiccadence.client.library;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.client.auth.SecureTokenStore;
import com.cubiccadence.model.MembershipTier;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicLibraryManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void syncsAllSummariesAndPaginatesEightItemsLocally() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.playlistResponder = request -> {
            if (request.page() == 0) {
                return CompletableFuture.completedFuture(new PlaylistSummaryPage(playlists(0, 50), true, 62));
            }
            return CompletableFuture.completedFuture(new PlaylistSummaryPage(playlists(50, 12), false, 62));
        };

        try (AuthManager authManager = new AuthManager(provider, validStore());
             MusicLibraryManager libraryManager = manager(provider, authManager)) {
            authManager.restoreSession().join();
            libraryManager.tick();
            await(() -> libraryManager.getLoadState() == MusicLibraryManager.LoadState.READY);

            assertEquals("42", libraryManager.getProfile().orElseThrow().userId());
            assertEquals(2, provider.pageRequests.size());
            assertEquals(50, provider.pageRequests.getFirst().pageSize());
            assertEquals(8, libraryManager.getPlaylistPage().orElseThrow().items().size());
            assertTrue(libraryManager.hasNextPage());

            libraryManager.loadPage(7);
            assertEquals(7, libraryManager.getCurrentPage());
            assertEquals(6, libraryManager.getPlaylistPage().orElseThrow().items().size());
        }
    }

    @Test
    void displaysCacheBeforeBackgroundRefreshCompletes() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.profileFuture = new CompletableFuture<>();
        LibraryCacheStore cacheStore = new LibraryCacheStore(temporaryDirectory.resolve("library.json"));
        cacheStore.writeAsync(new LibrarySnapshot(profile(), playlists(0, 9), 1234L)).join();

        try (AuthManager authManager = new AuthManager(provider, validStore());
             MusicLibraryManager libraryManager = new MusicLibraryManager(provider, authManager, cacheStore)) {
            authManager.restoreSession().join();
            libraryManager.tick();
            await(() -> libraryManager.getPlaylistPage().isPresent());

            assertEquals(MusicLibraryManager.LoadState.REFRESHING, libraryManager.getLoadState());
            assertEquals(8, libraryManager.getPlaylistPage().orElseThrow().items().size());
            assertEquals(1234L, libraryManager.getSyncedAtEpochMillis());
        }
    }

    @Test
    void keepsCachedLibraryWhenBackgroundRefreshFails() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.profileFuture = CompletableFuture.failedFuture(new IllegalStateException("offline"));
        LibraryCacheStore cacheStore = new LibraryCacheStore(temporaryDirectory.resolve("library.json"));
        cacheStore.writeAsync(new LibrarySnapshot(profile(), playlists(0, 1), 1234L)).join();

        try (AuthManager authManager = new AuthManager(provider, validStore());
             MusicLibraryManager libraryManager = new MusicLibraryManager(provider, authManager, cacheStore)) {
            authManager.restoreSession().join();
            libraryManager.tick();
            await(libraryManager::hasRefreshWarning);

            assertEquals(MusicLibraryManager.LoadState.READY, libraryManager.getLoadState());
            assertEquals(1, libraryManager.getPlaylistPage().orElseThrow().items().size());
        }
    }

    @Test
    void ignoresLateProfileResponseAfterLogout() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.profileFuture = new CompletableFuture<>();

        try (AuthManager authManager = new AuthManager(provider, validStore());
             MusicLibraryManager libraryManager = manager(provider, authManager)) {
            authManager.restoreSession().join();
            libraryManager.tick();
            await(() -> libraryManager.getLoadState() == MusicLibraryManager.LoadState.LOADING_PROFILE);

            authManager.logout().join();
            libraryManager.tick();
            provider.profileFuture.complete(profile());

            assertEquals(MusicLibraryManager.LoadState.IDLE, libraryManager.getLoadState());
            assertTrue(libraryManager.getProfile().isEmpty());
            assertTrue(libraryManager.getPlaylistPage().isEmpty());
        }
    }

    @Test
    void explicitPrivateDataClearDeletesLibraryCache() {
        FakeProvider provider = new FakeProvider();
        LibraryCacheStore cacheStore = new LibraryCacheStore(temporaryDirectory.resolve("library.json"));
        cacheStore.writeAsync(new LibrarySnapshot(profile(), playlists(0, 1), 1234L)).join();

        try (AuthManager authManager = new AuthManager(provider, validStore());
             MusicLibraryManager libraryManager = new MusicLibraryManager(provider, authManager, cacheStore)) {
            libraryManager.clearPrivateData();
            assertTrue(cacheStore.readAsync().join().isEmpty());
        }
    }

    private MusicLibraryManager manager(FakeProvider provider, AuthManager authManager) {
        return new MusicLibraryManager(
                provider,
                authManager,
                new LibraryCacheStore(temporaryDirectory.resolve("library.json"))
        );
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static MemoryTokenStore validStore() {
        MemoryTokenStore store = new MemoryTokenStore();
        store.save(new AuthSession("netease", "cookie-value", System.currentTimeMillis() + 3_600_000L));
        return store;
    }

    private static UserProfile profile() {
        return new UserProfile("netease", "42", "test", "", 8, MembershipTier.BLACK_VINYL_VIP);
    }

    private static List<PlaylistSummary> playlists(int start, int count) {
        List<PlaylistSummary> playlists = new ArrayList<>(count);
        for (int index = start; index < start + count; index++) {
            playlists.add(new PlaylistSummary(
                    "netease",
                    Integer.toString(index),
                    "playlist-" + index,
                    "",
                    index,
                    index == 0 ? PlaylistOwnership.SPECIAL : PlaylistOwnership.CREATED
            ));
        }
        return playlists;
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
        private CompletableFuture<UserProfile> profileFuture = CompletableFuture.completedFuture(profile());
        private Function<PageRequest, CompletableFuture<PlaylistSummaryPage>> playlistResponder =
                ignored -> CompletableFuture.completedFuture(new PlaylistSummaryPage(List.of(), false, 0));
        private final List<PageRequest> pageRequests = new CopyOnWriteArrayList<>();

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
            pageRequests.add(pageRequest);
            return playlistResponder.apply(pageRequest);
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
