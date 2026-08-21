package com.cubiccadence.client.playback;

import com.cubiccadence.model.PlaybackSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one logical response body alive across transient HTTP failures by
 * reopening the resource at the exact byte that was last delivered.
 */
final class ResumableHttpInputStream extends InputStream {
    private static final Logger LOGGER = LoggerFactory.getLogger("cubic-cadence");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-\\d+/(?:\\d+|\\*)",
            Pattern.CASE_INSENSITIVE);

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(long offset) throws IOException, InterruptedException;
    }

    record Connection(InputStream body, int statusCode, Optional<String> contentRange) {
        Connection {
            Objects.requireNonNull(body, "body");
            contentRange = contentRange == null ? Optional.empty() : contentRange;
        }
    }

    private final ConnectionFactory connectionFactory;
    private final Executor cleanupExecutor;
    private final int maxRetries;
    private final AtomicBoolean reconnectRequested = new AtomicBoolean();

    private volatile InputStream currentBody;
    private volatile boolean closed;
    private volatile int retriesUsed;
    private long deliveredBytes;

    static ResumableHttpInputStream open(
            PlaybackSource source,
            HttpClient httpClient,
            Executor cleanupExecutor,
            Duration requestTimeout,
            int maxRetries
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        ConnectionFactory connectionFactory = offset -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(source.uri())
                    .timeout(requestTimeout)
                    .header("Accept", "audio/mpeg,audio/*;q=0.8")
                    .header("User-Agent", "Cubic-Cadence/1.0")
                    .GET();
            if (offset > 0L) {
                builder.header("Range", "bytes=" + offset + "-");
            }
            for (Map.Entry<String, String> header : source.requestHeaders().entrySet()) {
                if (!header.getKey().equalsIgnoreCase("cookie")
                        && !header.getKey().equalsIgnoreCase("authorization")
                        && !header.getKey().equalsIgnoreCase("range")) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            return new Connection(
                    response.body(),
                    response.statusCode(),
                    response.headers().firstValue("Content-Range")
            );
        };
        return new ResumableHttpInputStream(connectionFactory, cleanupExecutor, maxRetries);
    }

    ResumableHttpInputStream(
            ConnectionFactory connectionFactory,
            Executor cleanupExecutor,
            int maxRetries
    ) throws IOException {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        try {
            this.currentBody = openValidatedConnection(0L, false);
        } catch (IOException exception) {
            this.reconnectRequested.set(true);
            this.currentBody = reconnect(exception);
        }
    }

    /** Called by the PCM consumer when no decoded data has arrived before the stall deadline. */
    boolean requestReconnect() {
        if (closed) {
            return false;
        }
        if (reconnectRequested.get()) {
            return true;
        }
        if (retriesUsed >= maxRetries || !reconnectRequested.compareAndSet(false, true)) {
            return reconnectRequested.get();
        }
        closeAsync(currentBody);
        return true;
    }

    int retriesUsed() {
        return retriesUsed;
    }

    long deliveredBytes() {
        return deliveredBytes;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int read = read(one, 0, 1);
        return read < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        while (true) {
            ensureOpen();
            if (reconnectRequested.get()) {
                currentBody = reconnect(new IOException("Online audio reconnect was requested"));
                continue;
            }
            InputStream body = currentBody;
            if (body == null) {
                currentBody = reconnect(new IOException("Online audio response body is unavailable"));
                continue;
            }
            try {
                int read = body.read(bytes, offset, length);
                if (read > 0) {
                    deliveredBytes += read;
                    return read;
                }
                if (read < 0 && reconnectRequested.get()) {
                    currentBody = reconnect(new IOException("Online audio response was closed for reconnect"));
                    continue;
                }
                return read;
            } catch (IOException exception) {
                if (closed) {
                    throw new IOException("Online audio stream is closed", exception);
                }
                reconnectRequested.set(true);
                currentBody = reconnect(exception);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        reconnectRequested.set(false);
        InputStream body = currentBody;
        currentBody = null;
        if (body != null) {
            body.close();
        }
    }

    private InputStream reconnect(IOException initialFailure) throws IOException {
        IOException failure = initialFailure;
        closeAsync(currentBody);
        currentBody = null;
        while (!closed && retriesUsed < maxRetries) {
            int attempt = ++retriesUsed;
            try {
                InputStream resumed = openValidatedConnection(deliveredBytes, deliveredBytes > 0L);
                reconnectRequested.set(false);
                LOGGER.info(
                        "Cubic Cadence resumed online audio at byte {} (retry {}/{})",
                        deliveredBytes,
                        attempt,
                        maxRetries
                );
                return resumed;
            } catch (IOException exception) {
                failure = exception;
                LOGGER.warn(
                        "Cubic Cadence online audio reconnect failed at byte {} (retry {}/{})",
                        deliveredBytes,
                        attempt,
                        maxRetries,
                        exception
                );
            }
        }
        reconnectRequested.set(false);
        if (closed) {
            throw new IOException("Online audio stream is closed", failure);
        }
        throw new IOException(
                "Online audio could not resume at byte " + deliveredBytes
                        + " after " + maxRetries + " retries",
                failure
        );
    }

    private InputStream openValidatedConnection(long offset, boolean resuming) throws IOException {
        Connection connection;
        try {
            connection = connectionFactory.open(offset);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening online audio", exception);
        }
        InputStream body = connection.body();
        if (!resuming) {
            if (connection.statusCode() >= 200 && connection.statusCode() < 300) {
                return body;
            }
            closeAsync(body);
            throw new IOException("Audio CDN returned HTTP " + connection.statusCode());
        }
        if (connection.statusCode() != 206) {
            closeAsync(body);
            throw new IOException(
                    "Audio CDN does not support safe resume: expected HTTP 206 but received "
                            + connection.statusCode()
            );
        }
        String header = connection.contentRange().orElse("");
        Matcher matcher = CONTENT_RANGE.matcher(header.trim());
        if (!matcher.matches() || parseRangeStart(matcher.group(1)) != offset) {
            closeAsync(body);
            throw new IOException(
                    "Audio CDN returned an invalid Content-Range for byte " + offset
            );
        }
        return body;
    }

    private static long parseRangeStart(String value) throws IOException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Audio CDN returned an invalid Content-Range", exception);
        }
    }

    private void closeAsync(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            cleanupExecutor.execute(() -> {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // The reconnect failure carries the actionable error.
                }
            });
        } catch (RuntimeException ignored) {
            // Never move a potentially blocking close onto the sound or client thread.
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Online audio stream is closed");
        }
    }
}
