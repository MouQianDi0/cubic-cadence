package com.cubiccadence.client.cache;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class CoverCache {
    public CompletableFuture<Optional<byte[]>> get(String url) {
        // TODO: fetch the cover image bytes
        return CompletableFuture.completedFuture(Optional.empty());
    }

    public void evict(String url) {
        // TODO: evict a cached cover
    }

    public void clear() {
        // TODO: clear the cover cache
    }
}
