package com.cubiccadence.client.library;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthState;
import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistSummaryPage;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/** Maintains the asynchronous, in-memory view of the signed-in user's library. */
public final class MusicLibraryManager implements AutoCloseable {
    public static final int PAGE_SIZE = 8;

    private final MusicProvider provider;
    private final AuthManager authManager;
    private final AtomicLong requestGeneration = new AtomicLong();

    private volatile LoadState loadState = LoadState.IDLE;
    private volatile UserProfile profile;
    private volatile PlaylistSummaryPage playlistPage;
    private volatile int currentPage;
    private volatile int requestedPage;
    private volatile boolean closed;

    public MusicLibraryManager(MusicProvider provider, AuthManager authManager) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.authManager = Objects.requireNonNull(authManager, "authManager");
    }

    public void tick() {
        if (closed) {
            return;
        }
        AuthState authState = authManager.getState();
        if (authState == AuthState.SIGNED_IN) {
            if (profile == null && loadState == LoadState.IDLE) {
                refresh();
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
        AuthSession session = activeSession.get();
        profile = null;
        playlistPage = null;
        currentPage = 0;
        requestedPage = 0;
        loadState = LoadState.LOADING_PROFILE;

        provider.getCurrentUser(session)
                .thenCompose(loadedProfile -> {
                    if (!isCurrent(request)) {
                        throw new StaleRequestException();
                    }
                    profile = loadedProfile;
                    loadState = LoadState.LOADING_PLAYLISTS;
                    return provider.getUserPlaylists(
                            session,
                            loadedProfile.userId(),
                            new PageRequest(0, PAGE_SIZE)
                    );
                })
                .whenComplete((loadedPage, throwable) -> {
                    if (!isCurrent(request)) {
                        return;
                    }
                    if (throwable == null) {
                        playlistPage = loadedPage;
                        loadState = LoadState.READY;
                    } else if (!isStaleRequest(throwable)) {
                        loadState = LoadState.ERROR;
                    }
                });
    }

    public void loadPage(int page) {
        UserProfile activeProfile = profile;
        Optional<AuthSession> activeSession = signedInSession();
        if (page < 0 || activeProfile == null || activeSession.isEmpty()) {
            return;
        }
        long request = requestGeneration.incrementAndGet();
        requestedPage = page;
        loadState = LoadState.LOADING_PLAYLISTS;
        provider.getUserPlaylists(
                        activeSession.get(),
                        activeProfile.userId(),
                        new PageRequest(page, PAGE_SIZE)
                )
                .whenComplete((loadedPage, throwable) -> {
                    if (!isCurrent(request)) {
                        return;
                    }
                    if (throwable == null) {
                        playlistPage = loadedPage;
                        currentPage = page;
                        loadState = LoadState.READY;
                    } else {
                        loadState = LoadState.ERROR;
                    }
                });
    }

    public void retry() {
        if (profile == null) {
            refresh();
        } else {
            loadPage(requestedPage);
        }
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

    public boolean hasPreviousPage() {
        return currentPage > 0 && loadState != LoadState.LOADING_PLAYLISTS;
    }

    public boolean hasNextPage() {
        return playlistPage != null && playlistPage.hasNext() && loadState != LoadState.LOADING_PLAYLISTS;
    }

    public void clear() {
        requestGeneration.incrementAndGet();
        profile = null;
        playlistPage = null;
        currentPage = 0;
        requestedPage = 0;
        loadState = LoadState.IDLE;
    }

    @Override
    public void close() {
        closed = true;
        clear();
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

    private static boolean isStaleRequest(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
        return cause instanceof StaleRequestException;
    }

    public enum LoadState {
        IDLE,
        LOADING_PROFILE,
        LOADING_PLAYLISTS,
        READY,
        ERROR
    }

    private static final class StaleRequestException extends RuntimeException {
    }
}
