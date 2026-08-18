package com.cubiccadence.client.cache;

import java.util.Optional;

public class CacheService {
    public <T> void write(String key, T value) {
        // TODO: write a cache entry
    }

    public <T> Optional<T> read(String key, Class<T> type) {
        // TODO: read a cache entry
        return Optional.empty();
    }

    public void delete(String key) {
        // TODO: delete a cache entry
    }

    public void clear() {
        // TODO: clear the cache
    }
}
