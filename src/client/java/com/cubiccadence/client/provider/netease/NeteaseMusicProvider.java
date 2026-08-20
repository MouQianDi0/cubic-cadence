package com.cubiccadence.client.provider.netease;

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

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public class NeteaseMusicProvider implements MusicProvider {
    public static final String PROVIDER_ID = "netease";

    private final NeteaseApiClient apiClient;
    private final NeteaseAuthClient authClient;

    public NeteaseMusicProvider(URI apiEnhancedBaseUri) {
        this.apiClient = new NeteaseApiClient(apiEnhancedBaseUri);
        this.authClient = new NeteaseAuthClient(apiEnhancedBaseUri);
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<AuthorizationChallenge> beginLogin() {
        return authClient.beginLogin();
    }

    @Override
    public CompletableFuture<AuthorizationResult> pollAuthorization(String authorizationId) {
        return authClient.pollAuthorization(authorizationId);
    }

    @Override
    public CompletableFuture<AuthSession> refresh(AuthSession session) {
        return authClient.refresh(session);
    }

    @Override
    public CompletableFuture<UserProfile> getCurrentUser(AuthSession session) {
        return apiClient.getCurrentUser(session);
    }

    @Override
    public CompletableFuture<PlaylistSummaryPage> getUserPlaylists(
            AuthSession session,
            String userId,
            PageRequest pageRequest
    ) {
        return apiClient.getUserPlaylists(session, userId, pageRequest);
    }

    @Override
    public CompletableFuture<PlaylistPage> getPlaylistTracks(String playlistId, PageRequest pageRequest) {
        // TODO: implement NCM-LIB-003 / NCM-LIB-004 / NCM-TRACK-001
        return unsupported();
    }

    @Override
    public CompletableFuture<SearchPage<?>> search(String keyword, SearchType type, PageRequest pageRequest) {
        // TODO: implement NCM-SEARCH-001 to NCM-SEARCH-003
        return unsupported();
    }

    @Override
    public CompletableFuture<PlaybackSource> resolvePlaybackSource(String trackId, AudioQuality quality) {
        // TODO: implement NCM-PLAY-001 / NCM-PLAY-002
        return unsupported();
    }

    @Override
    public CompletableFuture<Void> logout(AuthSession session) {
        return authClient.logout(session);
    }

    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("netease provider not implemented"));
    }
}
