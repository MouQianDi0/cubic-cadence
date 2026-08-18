package com.cubiccadence.provider;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MusicProvider {
    String id();

    CompletableFuture<AuthSession> beginLogin();

    CompletableFuture<AuthSession> pollAuthorization(String authorizationId);

    CompletableFuture<AuthSession> refresh(AuthSession session);

    CompletableFuture<UserProfile> getCurrentUser();

    CompletableFuture<List<PlaylistSummary>> getUserPlaylists();

    CompletableFuture<PlaylistPage> getPlaylistTracks(
            String playlistId,
            PageRequest pageRequest
    );

    CompletableFuture<SearchPage<?>> search(
            String keyword,
            SearchType type,
            PageRequest pageRequest
    );

    CompletableFuture<PlaybackSource> resolvePlaybackSource(
            String trackId,
            AudioQuality quality
    );

    CompletableFuture<Void> logout();
}
