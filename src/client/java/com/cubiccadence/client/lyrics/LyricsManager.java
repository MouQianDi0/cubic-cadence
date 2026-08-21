package com.cubiccadence.client.lyrics;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.model.SyncedLyrics;
import com.cubiccadence.model.Track;
import com.cubiccadence.provider.MusicProvider;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Loads lyrics on demand and discards responses that arrive after the track changes. */
public final class LyricsManager implements AutoCloseable {
    private static final int MAX_CACHE_ENTRIES = 64;

    private final MusicProvider provider;
    private final Supplier<Optional<AuthSession>> sessionSupplier;
    private final Executor callbackExecutor;
    private final AtomicLong requestGeneration = new AtomicLong();
    private final Set<TrackKey> preloading = new HashSet<>();
    private final Map<TrackKey, SyncedLyrics> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TrackKey, SyncedLyrics> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private volatile TrackKey currentKey;
    private volatile SyncedLyrics lyrics;
    private volatile LyricsLoadState state = LyricsLoadState.IDLE;
    private volatile boolean closed;

    public LyricsManager(
            MusicProvider provider,
            Supplier<Optional<AuthSession>> sessionSupplier,
            Executor callbackExecutor
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    public LyricsLoadState getState() {
        return state;
    }

    public Optional<SyncedLyrics> getLyrics() {
        return Optional.ofNullable(lyrics);
    }

    public void tick(Track track) {
        if (closed) {
            return;
        }
        if (track == null) {
            if (currentKey != null || state != LyricsLoadState.IDLE) {
                clearCurrent();
            }
            return;
        }
        TrackKey key = new TrackKey(track.providerId(), track.trackId());
        if (key.equals(currentKey)) {
            return;
        }
        long request = requestGeneration.incrementAndGet();
        currentKey = key;
        lyrics = null;

        SyncedLyrics cached = cache.get(key);
        if (cached != null) {
            install(cached);
            return;
        }
        AuthSession session = sessionSupplier.get().orElse(null);
        if (session == null || !provider.id().equals(key.providerId())) {
            state = LyricsLoadState.ERROR;
            return;
        }
        state = LyricsLoadState.LOADING;
        provider.getLyrics(session, key.trackId())
                .whenComplete((loaded, throwable) -> callbackExecutor.execute(() -> {
                    if (closed || requestGeneration.get() != request || !key.equals(currentKey)) {
                        return;
                    }
                    if (throwable != null || loaded == null
                            || !key.providerId().equals(loaded.providerId())
                            || !key.trackId().equals(loaded.trackId())) {
                        lyrics = null;
                        state = LyricsLoadState.ERROR;
                        return;
                    }
                    cache.put(key, loaded);
                    install(loaded);
                }));
    }

    /** Fetches lyrics for an upcoming track without disturbing the current one. */
    public void preload(Track track) {
        if (closed || track == null) {
            return;
        }
        TrackKey key = new TrackKey(track.providerId(), track.trackId());
        if (cache.containsKey(key)) {
            return;
        }
        AuthSession session = sessionSupplier.get().orElse(null);
        if (session == null || !provider.id().equals(key.providerId())) {
            return;
        }
        synchronized (preloading) {
            if (!preloading.add(key)) {
                return;
            }
        }
        provider.getLyrics(session, key.trackId())
                .whenComplete((loaded, throwable) -> callbackExecutor.execute(() -> {
                    synchronized (preloading) {
                        preloading.remove(key);
                    }
                    if (closed) {
                        return;
                    }
                    if (throwable != null || loaded == null
                            || !key.providerId().equals(loaded.providerId())
                            || !key.trackId().equals(loaded.trackId())) {
                        return;
                    }
                    cache.put(key, loaded);
                }));
    }

    public void clearCurrent() {
        requestGeneration.incrementAndGet();
        currentKey = null;
        lyrics = null;
        state = LyricsLoadState.IDLE;
    }

    private void install(SyncedLyrics loaded) {
        lyrics = loaded;
        state = loaded.lines().isEmpty() ? LyricsLoadState.UNAVAILABLE : LyricsLoadState.READY;
    }

    @Override
    public void close() {
        closed = true;
        clearCurrent();
        preloading.clear();
        cache.clear();
    }

    private record TrackKey(String providerId, String trackId) {
        private TrackKey {
            providerId = providerId == null ? "" : providerId;
            trackId = trackId == null ? "" : trackId;
        }
    }
}
