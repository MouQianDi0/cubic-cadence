package com.cubiccadence.provider;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.SyncedLyrics;
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
            AuthSession session,
            String playlistId,
            PageRequest pageRequest
    );

    CompletableFuture<SearchPage<?>> search(
            String keyword,
            SearchType type,
            PageRequest pageRequest
    );

    CompletableFuture<PlaybackSource> resolvePlaybackSource(
            AuthSession session,
            String trackId,
            AudioQuality quality
    );

    default CompletableFuture<SyncedLyrics> getLyrics(AuthSession session, String trackId) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("lyrics are not supported by this provider")
        );
    }

    CompletableFuture<Void> logout(AuthSession session);
}
