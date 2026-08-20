package com.cubiccadence.client.ui.texture;

import com.cubiccadence.client.CubicCadenceClient;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Downloads allow-listed remote images off-thread and registers them on the render thread. */
public final class RemoteTextureCache implements AutoCloseable {
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_DISK_FILES = 512;
    private static final long RETRY_DELAY_MILLIS = 1_500L;
    private static final long NETWORK_RETRY_CYCLE_MILLIS = 30_000L;

    private final HttpClient httpClient;
    private final Path diskCacheDirectory;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong diskCacheGeneration = new AtomicLong();
    private final Object diskCacheLock = new Object();
    private volatile boolean closed;

    public RemoteTextureCache() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                FabricLoader.getInstance().getConfigDir()
                        .resolve("cubic-cadence")
                        .resolve("cache")
                        .resolve("covers")
        );
    }

    RemoteTextureCache(HttpClient httpClient, Path diskCacheDirectory) {
        this.httpClient = httpClient;
        this.diskCacheDirectory = diskCacheDirectory.toAbsolutePath().normalize();
    }

    public Optional<Identifier> getOrRequest(String url) {
        Optional<URI> normalized = normalize(url);
        if (closed || normalized.isEmpty()) {
            return Optional.empty();
        }
        String key = normalized.get().toString();
        Entry entry = entries.computeIfAbsent(
                key,
                ignored -> new Entry(normalized.get(), identifierFor(key), cacheFileFor(key))
        );
        startIfNeeded(entry, key);
        return entry.state == LoadState.READY ? Optional.of(entry.identifier) : Optional.empty();
    }

    public void retainOnly(Collection<String> urls) {
        Set<String> retained = new HashSet<>();
        for (String url : urls) {
            normalize(url).map(URI::toString).ifPresent(retained::add);
        }
        for (Map.Entry<String, Entry> cached : entries.entrySet()) {
            if (!retained.contains(cached.getKey()) && entries.remove(cached.getKey(), cached.getValue())) {
                release(cached.getValue());
            }
        }
    }

    public void clear() {
        for (Map.Entry<String, Entry> cached : entries.entrySet()) {
            if (entries.remove(cached.getKey(), cached.getValue())) {
                release(cached.getValue());
            }
        }
    }

    public void clearDiskCache() {
        diskCacheGeneration.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            synchronized (diskCacheLock) {
                if (!Files.isDirectory(diskCacheDirectory)) {
                    return;
                }
                try (var files = Files.list(diskCacheDirectory)) {
                    files.filter(Files::isRegularFile).forEach(RemoteTextureCache::deleteQuietly);
                } catch (IOException exception) {
                    CubicCadenceClient.LOGGER.warn("Could not clear the remote image disk cache");
                }
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        clear();
    }

    private void startIfNeeded(Entry entry, String key) {
        synchronized (entry) {
            if (entry.released || entry.state == LoadState.LOADING || entry.state == LoadState.READY) {
                return;
            }
            if (entry.state == LoadState.FAILED && System.nanoTime() < entry.retryAfterNanos) {
                return;
            }
            entry.attempt = 0;
            beginAttempt(entry, key, true);
        }
    }

    private void beginAttempt(Entry entry, String key, boolean allowDiskCache) {
        if (closed || entry.released || entries.get(key) != entry) {
            return;
        }
        entry.state = LoadState.LOADING;
        entry.attempt++;
        CompletableFuture<NativeImage> imageFuture = allowDiskCache
                ? loadFromDisk(entry).thenCompose(cached -> cached == null ? download(entry) : completed(cached))
                : download(entry);
        entry.future = imageFuture.whenComplete((image, throwable) -> {
            if (throwable != null || image == null) {
                Failure failure = failure(throwable);
                handleFailure(entry, key, failure);
                return;
            }
            Minecraft.getInstance().execute(() -> registerTexture(entry, key, image));
        });
    }

    private CompletableFuture<NativeImage> loadFromDisk(Entry entry) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isRegularFile(entry.cacheFile)) {
                return null;
            }
            try {
                byte[] bytes = Files.readAllBytes(entry.cacheFile);
                validateBody(bytes);
                return RemoteImageDecoder.decode(bytes);
            } catch (IOException | RuntimeException exception) {
                deleteQuietly(entry.cacheFile);
                return null;
            }
        });
    }

    private CompletableFuture<NativeImage> download(Entry entry) {
        long cacheGeneration = diskCacheGeneration.get();
        HttpRequest request = HttpRequest.newBuilder(entry.uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "image/png,image/jpeg,image/*;q=0.8")
                .header("User-Agent", "Cubic-Cadence/1.0")
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new ImageLoadException(FailureStage.HTTP, response.statusCode() >= 500, null);
                    }
                    byte[] bytes = response.body();
                    validateBody(bytes);
                    NativeImage image;
                    try {
                        image = RemoteImageDecoder.decode(bytes);
                    } catch (RemoteImageDecoder.ImageDecodeException exception) {
                        throw new ImageLoadException(FailureStage.DECODE, false, exception);
                    }
                    writeDiskCache(entry.cacheFile, bytes, cacheGeneration);
                    return image;
                });
    }

    private static void validateBody(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new ImageLoadException(FailureStage.SIZE, false, null);
        }
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private void registerTexture(Entry entry, String key, NativeImage image) {
        if (closed || entry.released || entries.get(key) != entry) {
            image.close();
            return;
        }
        try {
            DynamicTexture texture = new DynamicTexture(() -> "Cubic Cadence remote image", image);
            Minecraft.getInstance().getTextureManager().register(entry.identifier, texture);
            synchronized (entry) {
                entry.state = LoadState.READY;
                entry.future = null;
            }
        } catch (RuntimeException exception) {
            image.close();
            handleFailure(entry, key, new Failure(FailureStage.REGISTER, false, exception));
        }
    }

    private void handleFailure(Entry entry, String key, Failure failure) {
        synchronized (entry) {
            if (closed || entry.released || entries.get(key) != entry) {
                return;
            }
            if (failure.retryable && entry.attempt < MAX_ATTEMPTS) {
                entry.future = CompletableFuture.runAsync(
                        () -> {
                            synchronized (entry) {
                                beginAttempt(entry, key, false);
                            }
                        },
                        CompletableFuture.delayedExecutor(RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                );
                return;
            }
            entry.state = LoadState.FAILED;
            entry.future = null;
            entry.retryAfterNanos = failure.retryable
                    ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NETWORK_RETRY_CYCLE_MILLIS)
                    : Long.MAX_VALUE;
        }
        String detail = failure.cause == null
                ? "none"
                : failure.cause.getClass().getSimpleName();
        CubicCadenceClient.LOGGER.warn(
                "Remote image loading failed (host={}, stage={}, cause={})",
                entry.uri.getHost(),
                failure.stage.logValue,
                detail
        );
    }

    private static Failure failure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ImageLoadException imageLoadException) {
                return new Failure(
                        imageLoadException.stage,
                        imageLoadException.retryable,
                        imageLoadException.getCause()
                );
            }
            current = current.getCause();
        }
        return new Failure(FailureStage.NETWORK, true, throwable);
    }

    private void writeDiskCache(Path cacheFile, byte[] bytes, long cacheGeneration) {
        synchronized (diskCacheLock) {
            if (diskCacheGeneration.get() != cacheGeneration) {
                return;
            }
            try {
                Files.createDirectories(diskCacheDirectory);
                Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveFailure) {
                    Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                }
                pruneDiskCache();
            } catch (IOException exception) {
                CubicCadenceClient.LOGGER.debug("Could not persist a remote image cache entry");
            }
        }
    }

    private void pruneDiskCache() {
        try (var stream = Files.list(diskCacheDirectory)) {
            List<Path> files = stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".image"))
                    .sorted(Comparator.comparingLong(RemoteTextureCache::lastModifiedQuietly).reversed())
                    .toList();
            for (int index = MAX_DISK_FILES; index < files.size(); index++) {
                deleteQuietly(files.get(index));
            }
        } catch (IOException ignored) {
            // Best-effort cache pruning must not affect rendering.
        }
    }

    private void release(Entry entry) {
        synchronized (entry) {
            entry.released = true;
            if (entry.future != null) {
                entry.future.cancel(true);
                entry.future = null;
            }
            if (entry.state != LoadState.READY) {
                return;
            }
            entry.state = LoadState.RELEASED;
        }
        Minecraft.getInstance().execute(
                () -> Minecraft.getInstance().getTextureManager().release(entry.identifier)
        );
    }

    static Optional<URI> normalize(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null || !allowedHost(host)) {
                return Optional.empty();
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                uri = URI.create("https:" + uri.toString().substring(uri.getScheme().length() + 1));
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Path cacheFileFor(String key) {
        return diskCacheDirectory.resolve(hash(key) + ".image");
    }

    private static boolean allowedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("music.126.net") || normalized.endsWith(".music.126.net")
                || normalized.equals("music.163.com") || normalized.endsWith(".music.163.com");
    }

    private static Identifier identifierFor(String key) {
        return CubicCadenceClient.id("remote/" + hash(key));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long lastModifiedQuietly(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort; stale cache files are harmless.
        }
    }

    private enum LoadState {
        NEW,
        LOADING,
        READY,
        FAILED,
        RELEASED
    }

    private enum FailureStage {
        NETWORK("network"),
        HTTP("http"),
        SIZE("size"),
        DECODE("decode"),
        REGISTER("register");

        private final String logValue;

        FailureStage(String logValue) {
            this.logValue = logValue;
        }
    }

    private record Failure(FailureStage stage, boolean retryable, Throwable cause) {
    }

    private static final class Entry {
        private final URI uri;
        private final Identifier identifier;
        private final Path cacheFile;
        private volatile CompletableFuture<?> future;
        private volatile LoadState state = LoadState.NEW;
        private int attempt;
        private long retryAfterNanos;
        private boolean released;

        private Entry(URI uri, Identifier identifier, Path cacheFile) {
            this.uri = uri;
            this.identifier = identifier;
            this.cacheFile = cacheFile;
        }
    }

    private static final class ImageLoadException extends RuntimeException {
        private final FailureStage stage;
        private final boolean retryable;

        private ImageLoadException(FailureStage stage, boolean retryable, Throwable cause) {
            super(cause);
            this.stage = stage;
            this.retryable = retryable;
        }
    }
}
