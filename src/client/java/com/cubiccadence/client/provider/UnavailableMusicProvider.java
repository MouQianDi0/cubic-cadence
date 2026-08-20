package com.cubiccadence.client.provider;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;
import com.cubiccadence.provider.PlaylistSummaryPage;
import com.cubiccadence.provider.SearchPage;
import com.cubiccadence.provider.SearchType;

import java.util.concurrent.CompletableFuture;

/** Fail-closed provider used until an api-enhanced base URL has been configured. */
public final class UnavailableMusicProvider implements MusicProvider {
    @Override
    public String id() {
        return "netease";
    }

    @Override
    public CompletableFuture<AuthorizationChallenge> beginLogin() {
        return unavailable();
    }

    @Override
    public CompletableFuture<AuthorizationResult> pollAuthorization(String authorizationId) {
        return unavailable();
    }

    @Override
    public CompletableFuture<AuthSession> refresh(AuthSession session) {
        return unavailable();
    }

    @Override
    public CompletableFuture<UserProfile> getCurrentUser(AuthSession session) {
        return unavailable();
    }

    @Override
    public CompletableFuture<PlaylistSummaryPage> getUserPlaylists(
            AuthSession session,
            String userId,
            PageRequest pageRequest
    ) {
        return unavailable();
    }

    @Override
    public CompletableFuture<PlaylistPage> getPlaylistTracks(
            AuthSession session,
            String playlistId,
            PageRequest pageRequest
    ) {
        return unavailable();
    }

    @Override
    public CompletableFuture<SearchPage<?>> search(String keyword, SearchType type, PageRequest pageRequest) {
        return unavailable();
    }

    @Override
    public CompletableFuture<PlaybackSource> resolvePlaybackSource(
            AuthSession session,
            String trackId,
            AudioQuality quality
    ) {
        return unavailable();
    }

    @Override
    public CompletableFuture<Void> logout(AuthSession session) {
        return CompletableFuture.completedFuture(null);
    }

    private static <T> CompletableFuture<T> unavailable() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("api-enhanced service is not configured")
        );
    }
}
