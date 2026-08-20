package com.cubiccadence.client.library;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthState;
import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistSummaryPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/** Maintains a cache-first, asynchronously refreshed view of the signed-in user's library. */
public final class MusicLibraryManager implements AutoCloseable {
    public static final int PAGE_SIZE = 8;
    private static final int SYNC_PAGE_SIZE = 50;
    private static final int MAX_SYNC_PAGES = 200;

    private final MusicProvider provider;
    private final AuthManager authManager;
    private final LibraryCacheStore cacheStore;
    private final AtomicLong requestGeneration = new AtomicLong();

    private volatile LoadState loadState = LoadState.IDLE;
    private volatile UserProfile profile;
    private volatile List<PlaylistSummary> allPlaylists = List.of();
    private volatile PlaylistSummaryPage playlistPage;
    private volatile int currentPage;
    private volatile int syncLoadedCount;
    private volatile Integer syncTotal;
    private volatile long syncedAtEpochMillis;
    private volatile boolean refreshWarning;
    private volatile boolean closed;

    public MusicLibraryManager(MusicProvider provider, AuthManager authManager) {
        this(provider, authManager, new LibraryCacheStore());
    }

    MusicLibraryManager(MusicProvider provider, AuthManager authManager, LibraryCacheStore cacheStore) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.authManager = Objects.requireNonNull(authManager, "authManager");
        this.cacheStore = Objects.requireNonNull(cacheStore, "cacheStore");
    }

    public void tick() {
        if (closed) {
            return;
        }
        AuthState authState = authManager.getState();
        if (authState == AuthState.SIGNED_IN) {
            if (loadState == LoadState.IDLE) {
                startCacheFirstSync();
            }
            return;
        }
        if (authState != AuthState.REFRESHING && hasLibraryState()) {
            clear();
        }
    }

    public void refresh() {
        Optional<AuthSession> activeSession = signedInSession();
        if (activeSession.isEmpty()) {
            clear();
            return;
        }
        long request = requestGeneration.incrementAndGet();
        refreshWarning = false;
        startNetworkRefresh(request, activeSession.get(), playlistPage != null);
    }

    public void loadPage(int page) {
        if (page < 0 || playlistPage == null) {
            return;
        }
        int pageCount = Math.max(1, (allPlaylists.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pageCount) {
            return;
        }
        currentPage = page;
        rebuildCurrentPage();
    }

    public void retry() {
        refresh();
    }

    public Optional<UserProfile> getProfile() {
        return Optional.ofNullable(profile);
    }

    public Optional<PlaylistSummaryPage> getPlaylistPage() {
        return Optional.ofNullable(playlistPage);
    }

    public LoadState getLoadState() {
        return loadState;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getSyncLoadedCount() {
        return syncLoadedCount;
    }

    public Optional<Integer> getSyncTotal() {
        return Optional.ofNullable(syncTotal);
    }

    public long getSyncedAtEpochMillis() {
        return syncedAtEpochMillis;
    }

    public boolean hasRefreshWarning() {
        return refreshWarning;
    }

    public boolean isRefreshing() {
        return loadState == LoadState.LOADING_CACHE || loadState == LoadState.LOADING_PROFILE
                || loadState == LoadState.SYNCING_PLAYLISTS || loadState == LoadState.REFRESHING;
    }

    public boolean hasPreviousPage() {
        return currentPage > 0 && playlistPage != null;
    }

    public boolean hasNextPage() {
        return playlistPage != null && playlistPage.hasNext();
    }

    public void clear() {
        requestGeneration.incrementAndGet();
        profile = null;
        allPlaylists = List.of();
        playlistPage = null;
        currentPage = 0;
        syncLoadedCount = 0;
        syncTotal = null;
        syncedAtEpochMillis = 0L;
        refreshWarning = false;
        loadState = LoadState.IDLE;
    }

    /** Clears in-memory state and queues removal of non-secret account/library cache data. */
    public void clearPrivateData() {
        clear();
        cacheStore.deleteAsync();
    }

    @Override
    public void close() {
        closed = true;
        clear();
        cacheStore.close();
    }

    private void startCacheFirstSync() {
        Optional<AuthSession> activeSession = signedInSession();
        if (activeSession.isEmpty()) {
            return;
        }
        long request = requestGeneration.incrementAndGet();
        loadState = LoadState.LOADING_CACHE;
        refreshWarning = false;
        cacheStore.readAsync().whenComplete((cached, cacheFailure) -> {
            if (!isCurrent(request)) {
                return;
            }
            boolean cacheApplied = false;
            if (cacheFailure == null && cached != null && cached.isPresent()) {
                LibrarySnapshot snapshot = cached.get();
                if (provider.id().equals(snapshot.profile().providerId())) {
                    applySnapshot(snapshot);
                    cacheApplied = true;
                }
            }
            startNetworkRefresh(request, activeSession.get(), cacheApplied);
        });
    }

    private void startNetworkRefresh(long request, AuthSession session, boolean keepVisibleData) {
        loadState = keepVisibleData ? LoadState.REFRESHING : LoadState.LOADING_PROFILE;
        syncLoadedCount = 0;
        syncTotal = null;
        provider.getCurrentUser(session)
                .thenCompose(loadedProfile -> {
                    requireCurrent(request);
                    if (profile != null && !profile.userId().equals(loadedProfile.userId())) {
                        allPlaylists = List.of();
                        playlistPage = null;
                        currentPage = 0;
                        syncedAtEpochMillis = 0L;
                    }
                    profile = loadedProfile;
                    loadState = LoadState.SYNCING_PLAYLISTS;
                    return fetchAllPlaylists(session, loadedProfile.userId(), 0, new ArrayList<>(), request);
                })
                .whenComplete((loadedPlaylists, throwable) -> completeRefresh(request, loadedPlaylists, throwable));
    }

    private CompletableFuture<List<PlaylistSummary>> fetchAllPlaylists(
            AuthSession session,
            String userId,
            int page,
            List<PlaylistSummary> accumulated,
            long request
    ) {
        if (page >= MAX_SYNC_PAGES) {
            return CompletableFuture.failedFuture(new IllegalStateException("playlist sync exceeded its safety limit"));
        }
        return provider.getUserPlaylists(session, userId, new PageRequest(page, SYNC_PAGE_SIZE))
                .thenCompose(loadedPage -> {
                    requireCurrent(request);
                    accumulated.addAll(loadedPage.items());
                    syncLoadedCount = accumulated.size();
                    syncTotal = loadedPage.total();
                    boolean reachedReportedTotal = loadedPage.total() != null
                            && accumulated.size() >= loadedPage.total();
                    if (!loadedPage.hasNext() || loadedPage.items().isEmpty() || reachedReportedTotal) {
                        return CompletableFuture.completedFuture(List.copyOf(accumulated));
                    }
                    return fetchAllPlaylists(session, userId, page + 1, accumulated, request);
                });
    }

    private void completeRefresh(long request, List<PlaylistSummary> loadedPlaylists, Throwable throwable) {
        if (!isCurrent(request)) {
            return;
        }
        if (throwable == null) {
            allPlaylists = List.copyOf(loadedPlaylists);
            int pageCount = Math.max(1, (allPlaylists.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            currentPage = Math.min(currentPage, pageCount - 1);
            rebuildCurrentPage();
            syncedAtEpochMillis = System.currentTimeMillis();
            refreshWarning = false;
            loadState = LoadState.READY;
            cacheStore.writeAsync(new LibrarySnapshot(profile, allPlaylists, syncedAtEpochMillis));
            return;
        }
        if (!isStaleRequest(throwable)) {
            if (playlistPage != null) {
                refreshWarning = true;
                loadState = LoadState.READY;
            } else {
                loadState = LoadState.ERROR;
            }
        }
    }

    private void applySnapshot(LibrarySnapshot snapshot) {
        profile = snapshot.profile();
        allPlaylists = snapshot.playlists();
        currentPage = 0;
        syncedAtEpochMillis = snapshot.syncedAtEpochMillis();
        rebuildCurrentPage();
    }

    private void rebuildCurrentPage() {
        int from = Math.min(currentPage * PAGE_SIZE, allPlaylists.size());
        int to = Math.min(from + PAGE_SIZE, allPlaylists.size());
        boolean hasNext = to < allPlaylists.size();
        playlistPage = new PlaylistSummaryPage(allPlaylists.subList(from, to), hasNext, allPlaylists.size());
    }

    private Optional<AuthSession> signedInSession() {
        return authManager.getState() == AuthState.SIGNED_IN
                ? authManager.getSession()
                : Optional.empty();
    }

    private boolean hasLibraryState() {
        return profile != null || playlistPage != null || loadState != LoadState.IDLE;
    }

    private boolean isCurrent(long request) {
        return !closed && requestGeneration.get() == request
                && authManager.getState() == AuthState.SIGNED_IN;
    }

    private void requireCurrent(long request) {
        if (!isCurrent(request)) {
            throw new StaleRequestException();
        }
    }

    private static boolean isStaleRequest(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
        return cause instanceof StaleRequestException;
    }

    public enum LoadState {
        IDLE,
        LOADING_CACHE,
        LOADING_PROFILE,
        SYNCING_PLAYLISTS,
        REFRESHING,
        READY,
        ERROR
    }

    private static final class StaleRequestException extends RuntimeException {
    }
}
