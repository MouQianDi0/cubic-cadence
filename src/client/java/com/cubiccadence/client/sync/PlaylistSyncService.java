package com.cubiccadence.client.sync;

import com.cubiccadence.client.cache.CacheService;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.PlaylistPage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PlaylistSyncService {
    private final MusicProvider provider;
    private final CacheService cache;

    public PlaylistSyncService(MusicProvider provider, CacheService cache) {
        this.provider = provider;
        this.cache = cache;
    }

    public CompletableFuture<List<PlaylistSummary>> sync() {
        // TODO: cache-first playlist sync
        return CompletableFuture.completedFuture(List.of());
    }

    public CompletableFuture<PlaylistPage> loadTracks(String playlistId, PageRequest pageRequest) {
        // TODO: load playlist tracks
        return CompletableFuture.failedFuture(new UnsupportedOperationException("not implemented"));
    }
}
