package com.cubiccadence.provider;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.UserProfile;

import java.util.concurrent.CompletableFuture;

public interface MusicProvider {
    String id();

    CompletableFuture<AuthorizationChallenge> beginLogin();

    CompletableFuture<AuthorizationResult> pollAuthorization(String authorizationId);

    CompletableFuture<AuthSession> refresh(AuthSession session);

    CompletableFuture<UserProfile> getCurrentUser(AuthSession session);

    CompletableFuture<PlaylistSummaryPage> getUserPlaylists(
            AuthSession session,
            String userId,
            PageRequest pageRequest
    );

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

    CompletableFuture<Void> logout(AuthSession session);
}
