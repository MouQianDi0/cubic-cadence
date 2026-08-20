package com.cubiccadence.client.library;

import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Serializes non-secret library summaries away from the render thread. */
public final class LibraryCacheStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_CACHE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PLAYLISTS = 10_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path cacheFile;
    private final ExecutorService executor;

    public LibraryCacheStore() {
        this(FabricLoader.getInstance().getConfigDir()
                .resolve("cubic-cadence")
                .resolve("cache")
                .resolve("library.json"));
    }

    LibraryCacheStore(Path cacheFile) {
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile").toAbsolutePath().normalize();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "Cubic Cadence library cache");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    public CompletableFuture<Optional<LibrarySnapshot>> readAsync() {
        return CompletableFuture.supplyAsync(this::read, executor);
    }

    public CompletableFuture<Void> writeAsync(LibrarySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return CompletableFuture.runAsync(() -> write(snapshot), executor);
    }

    public CompletableFuture<Void> deleteAsync() {
        return CompletableFuture.runAsync(this::delete, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    private Optional<LibrarySnapshot> read() {
        if (!Files.isRegularFile(cacheFile)) {
            return Optional.empty();
        }
        try {
            long size = Files.size(cacheFile);
            if (size < 1 || size > MAX_CACHE_BYTES) {
                delete();
                return Optional.empty();
            }
            CacheEnvelope envelope = GSON.fromJson(Files.readString(cacheFile, StandardCharsets.UTF_8), CacheEnvelope.class);
            if (!isValid(envelope)) {
                delete();
                return Optional.empty();
            }
            return Optional.of(new LibrarySnapshot(
                    envelope.profile(),
                    envelope.playlists(),
                    envelope.syncedAtEpochMillis()
            ));
        } catch (IOException | RuntimeException exception) {
            CubicCadenceClient.LOGGER.warn("Could not read the cached music library");
            delete();
            return Optional.empty();
        }
    }

    private void write(LibrarySnapshot snapshot) {
        CacheEnvelope envelope = new CacheEnvelope(
                SCHEMA_VERSION,
                snapshot.profile(),
                snapshot.playlists(),
                snapshot.syncedAtEpochMillis()
        );
        if (!isValid(envelope)) {
            throw new IllegalArgumentException("library snapshot is invalid");
        }
        byte[] json = GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_CACHE_BYTES) {
            CubicCadenceClient.LOGGER.warn("Music library cache is too large to persist");
            return;
        }
        Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(temporary, json);
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            deleteQuietly(temporary);
            CubicCadenceClient.LOGGER.warn("Could not persist the cached music library");
        }
    }

    private void delete() {
        deleteQuietly(cacheFile);
        deleteQuietly(cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp"));
    }

    private static boolean isValid(CacheEnvelope envelope) {
        if (envelope == null || envelope.schemaVersion() != SCHEMA_VERSION
                || envelope.syncedAtEpochMillis() < 0 || !isValid(envelope.profile())
                || envelope.playlists() == null || envelope.playlists().size() > MAX_PLAYLISTS) {
            return false;
        }
        for (PlaylistSummary playlist : envelope.playlists()) {
            if (playlist == null || !envelope.profile().providerId().equals(playlist.providerId())
                    || isBlank(playlist.playlistId()) || playlist.name() == null
                    || playlist.coverUrl() == null || playlist.trackCount() < 0 || playlist.ownership() == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValid(UserProfile profile) {
        return profile != null && !isBlank(profile.providerId()) && !isBlank(profile.userId())
                && profile.displayName() != null && profile.avatarUrl() != null
                && profile.membershipTier() != null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A stale cache is preferable to failing the client.
        }
    }

    private record CacheEnvelope(
            int schemaVersion,
            UserProfile profile,
            List<PlaylistSummary> playlists,
            long syncedAtEpochMillis
    ) {
    }
}
