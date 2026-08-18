package com.cubiccadence.client.provider.netease;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;
import com.cubiccadence.provider.SearchPage;
import com.cubiccadence.provider.SearchType;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NeteaseMusicProvider implements MusicProvider {
    public static final String PROVIDER_ID = "netease";

    private final NeteaseApiClient apiClient;
    private final NeteaseAuthClient authClient;

    public NeteaseMusicProvider() {
        this.apiClient = new NeteaseApiClient();
        this.authClient = new NeteaseAuthClient();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<AuthSession> beginLogin() {
        // TODO: implement NCM-AUTH-001 / NCM-AUTH-002
        return unsupported();
    }

    @Override
    public CompletableFuture<AuthSession> pollAuthorization(String authorizationId) {
        // TODO: poll the provider authorization result
        return unsupported();
    }

    @Override
    public CompletableFuture<AuthSession> refresh(AuthSession session) {
        // TODO: implement NCM-AUTH-003
        return unsupported();
    }

    @Override
    public CompletableFuture<UserProfile> getCurrentUser() {
        // TODO: implement NCM-USER-001
        return unsupported();
    }

    @Override
    public CompletableFuture<List<PlaylistSummary>> getUserPlaylists() {
        // TODO: implement NCM-LIB-001 / NCM-LIB-002
        return unsupported();
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
    public CompletableFuture<Void> logout() {
        // TODO: implement NCM-AUTH-005
        return unsupported();
    }

    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("netease provider not implemented"));
    }
}
