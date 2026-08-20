package com.cubiccadence.client.library;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthState;
import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Loads one playlist's track metadata only after the player opens its detail view. */
public final class PlaylistDetailManager implements AutoCloseable {
    public static final int PAGE_SIZE = 50;

    private final MusicProvider provider;
    private final AuthManager authManager;
    private final AtomicLong requestGeneration = new AtomicLong();

    private volatile PlaylistSummary selectedPlaylist;
    private volatile PlaylistPage trackPage;
    private volatile LoadState loadState = LoadState.IDLE;
    private volatile int currentPage;
    private volatile int requestedPage;
    private volatile boolean closed;

    public PlaylistDetailManager(MusicProvider provider, AuthManager authManager) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.authManager = Objects.requireNonNull(authManager, "authManager");
    }

    public void tick() {
        AuthState authState = authManager.getState();
        if (authState != AuthState.SIGNED_IN && authState != AuthState.REFRESHING && hasState()) {
            clear();
        }
    }

    public void open(PlaylistSummary playlist) {
        Objects.requireNonNull(playlist, "playlist");
        if (closed) {
            return;
        }
        if (samePlaylist(playlist) && trackPage != null) {
            selectedPlaylist = playlist;
            return;
        }
        selectedPlaylist = playlist;
        trackPage = null;
        currentPage = 0;
        requestedPage = 0;
        loadPage(0);
    }

    public void loadPage(int page) {
        PlaylistSummary playlist = selectedPlaylist;
        Optional<AuthSession> activeSession = signedInSession();
        if (closed || page < 0 || playlist == null || activeSession.isEmpty()) {
            if (playlist != null && !closed) {
                loadState = LoadState.ERROR;
            }
            return;
        }
        long request = requestGeneration.incrementAndGet();
        requestedPage = page;
        trackPage = null;
        loadState = LoadState.LOADING;
        provider.getPlaylistTracks(
                        activeSession.get(),
                        playlist.playlistId(),
                        new PageRequest(page, PAGE_SIZE)
                )
                .whenComplete((loadedPage, throwable) -> {
                    if (!isCurrent(request, playlist)) {
                        return;
                    }
                    if (throwable == null) {
                        trackPage = loadedPage;
                        currentPage = page;
                        loadState = LoadState.READY;
                    } else {
                        loadState = LoadState.ERROR;
                    }
                });
    }

    public void retry() {
        loadPage(requestedPage);
    }

    public Optional<PlaylistSummary> getSelectedPlaylist() {
        return Optional.ofNullable(selectedPlaylist);
    }

    public Optional<PlaylistPage> getTrackPage() {
        return Optional.ofNullable(trackPage);
    }

    public LoadState getLoadState() {
        return loadState;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public boolean hasPreviousPage() {
        return trackPage != null && currentPage > 0 && loadState != LoadState.LOADING;
    }

    public boolean hasNextPage() {
        PlaylistSummary playlist = selectedPlaylist;
        if (trackPage == null || playlist == null || loadState == LoadState.LOADING || !trackPage.hasNext()) {
            return false;
        }
        if (playlist.trackCount() <= 0) {
            return true;
        }
        return (long) (currentPage + 1) * PAGE_SIZE < playlist.trackCount();
    }

    public void clear() {
        requestGeneration.incrementAndGet();
        selectedPlaylist = null;
        trackPage = null;
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

    private boolean samePlaylist(PlaylistSummary playlist) {
        return selectedPlaylist != null
                && selectedPlaylist.providerId().equals(playlist.providerId())
                && selectedPlaylist.playlistId().equals(playlist.playlistId());
    }

    private boolean hasState() {
        return selectedPlaylist != null || trackPage != null || loadState != LoadState.IDLE;
    }

    private boolean isCurrent(long request, PlaylistSummary playlist) {
        AuthState authState = authManager.getState();
        return !closed && requestGeneration.get() == request && samePlaylist(playlist)
                && (authState == AuthState.SIGNED_IN || authState == AuthState.REFRESHING);
    }

    public enum LoadState {
        IDLE,
        LOADING,
        READY,
        ERROR
    }
}
