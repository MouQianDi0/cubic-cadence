package com.cubiccadence.client.playback;

import com.cubiccadence.model.PlaybackSource;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads and decodes MP3 data on a background executor. Minecraft's sound
 * thread only consumes bounded PCM chunks from the queue.
 */
final class JavaSoundStreamingAudioStream implements AudioStream {
    static final int PCM_CHUNK_BYTES = 64 * 1024;
    static final int MAX_BUFFERED_CHUNKS = 16;
    static final long MAX_BUFFERED_PCM_BYTES = (long) PCM_CHUNK_BYTES * MAX_BUFFERED_CHUNKS;

    private static final int PREBUFFER_CHUNKS = 12;
    private static final long READ_WAIT_MILLIS = 50L;
    private static final long MAX_STARVATION_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long MIN_PREMATURE_EOF_TOLERANCE_MS = 5_000L;
    private static final byte[] END_OF_STREAM = new byte[0];

    enum StreamState {
        RUNNING,
        EOF,
        FAILED,
        CANCELLED
    }

    private final AudioInputStream encodedStream;
    private final AudioInputStream pcmStream;
    private final AudioFormat format;
    private final long expectedDurationMs;
    private final Executor cleanupExecutor;
    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>(MAX_BUFFERED_CHUNKS);
    private final CompletableFuture<Void> primed = new CompletableFuture<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();

    private volatile StreamState streamState = StreamState.RUNNING;
    private volatile IOException failure;
    private volatile boolean starved;
    private volatile long starvationStartedNanos;
    private volatile long decodedPcmBytes;
    private volatile Future<?> producerTask;
    private ByteBuffer pending;
    private final Object pauseMonitor = new Object();
    private volatile boolean paused;

    JavaSoundStreamingAudioStream(
            AudioInputStream encodedStream,
            AudioInputStream pcmStream,
            long expectedDurationMs,
            Executor cleanupExecutor
    ) {
        this.encodedStream = Objects.requireNonNull(encodedStream, "encodedStream");
        this.pcmStream = Objects.requireNonNull(pcmStream, "pcmStream");
        this.format = pcmStream.getFormat();
        this.expectedDurationMs = Math.max(0L, expectedDurationMs);
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
    }

    static CompletableFuture<JavaSoundStreamingAudioStream> open(
            PlaybackSource source,
            HttpClient httpClient,
            ExecutorService producerExecutor,
            Executor cleanupExecutor
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(producerExecutor, "producerExecutor");
        Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
        return CompletableFuture.supplyAsync(
                        () -> create(source, httpClient, cleanupExecutor),
                        producerExecutor
                )
                .thenApply(stream -> {
                    stream.start(producerExecutor);
                    return stream;
                })
                .thenCompose(stream -> stream.primed.thenApply(ignored -> stream));
    }

    void start(ExecutorService producerExecutor) {
        this.producerTask = producerExecutor.submit(this::produce);
    }

    CompletableFuture<Void> ready() {
        return primed;
    }

    StreamState streamState() {
        return streamState;
    }

    boolean isStarved() {
        return starved && streamState == StreamState.RUNNING;
    }

    IOException failure() {
        return failure;
    }

    long decodedDurationMs() {
        double bytesPerSecond = format.getFrameRate() * format.getFrameSize();
        if (bytesPerSecond <= 0.0d) {
            return 0L;
        }
        return Math.round(decodedPcmBytes * 1_000.0d / bytesPerSecond);
    }

    int bufferedChunkCount() {
        return chunks.size();
    }

    void pause() {
        synchronized (pauseMonitor) {
            paused = true;
        }
    }

    void resume() {
        synchronized (pauseMonitor) {
            if (!paused) {
                return;
            }
            paused = false;
            pauseMonitor.notifyAll();
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        if (requestedBytes <= 0) {
            return null;
        }
        StreamState initialState = streamState;
        if (initialState == StreamState.CANCELLED) {
            return null;
        }
        ByteBuffer output = ByteBuffer.allocateDirect(requestedBytes);
        while (output.hasRemaining()) {
            if (pending == null || !pending.hasRemaining()) {
                byte[] next = pollChunk();
                if (next == null) {
                    StreamState currentState = streamState;
                    if (currentState == StreamState.FAILED) {
                        if (output.position() == 0) {
                            throw persistentFailure();
                        }
                        break;
                    }
                    if (currentState == StreamState.EOF || currentState == StreamState.CANCELLED) {
                        break;
                    }
                    return fillStarvationSilence(output);
                }
                if (next == END_OF_STREAM || next.length == 0) {
                    StreamState currentState = streamState;
                    if (currentState == StreamState.FAILED && output.position() == 0) {
                        throw persistentFailure();
                    }
                    break;
                }
                pending = ByteBuffer.wrap(next);
                clearStarvation();
            }
            int length = Math.min(output.remaining(), pending.remaining());
            int oldLimit = pending.limit();
            pending.limit(pending.position() + length);
            output.put(pending);
            pending.limit(oldLimit);
        }
        if (output.position() == 0) {
            return null;
        }
        output.flip();
        return output;
    }

    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }
        transitionFromRunning(StreamState.CANCELLED, null);
        synchronized (pauseMonitor) {
            paused = false;
            pauseMonitor.notifyAll();
        }
        clearStarvation();
        chunks.clear();
        chunks.offer(END_OF_STREAM);
        Future<?> task = producerTask;
        if (task != null) {
            task.cancel(true);
        }
        scheduleResourceClose();
    }

    private byte[] pollChunk() throws IOException {
        try {
            return chunks.poll(READ_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for decoded audio", exception);
        }
    }

    private ByteBuffer fillStarvationSilence(ByteBuffer output) throws IOException {
        long now = System.nanoTime();
        if (!starved) {
            starved = true;
            starvationStartedNanos = now;
        } else if (now - starvationStartedNanos >= MAX_STARVATION_NANOS) {
            IOException exception = new IOException("Online audio stream stalled for more than 10 seconds");
            fail(exception);
            throw exception;
        }
        while (output.hasRemaining()) {
            output.put((byte) 0);
        }
        output.flip();
        return output;
    }

    private void clearStarvation() {
        starved = false;
        starvationStartedNanos = 0L;
    }

    private IOException persistentFailure() {
        IOException current = failure;
        return current == null ? new IOException("Online audio stream failed") : current;
    }

    private void produce() {
        try {
            while (streamState == StreamState.RUNNING) {
                awaitUnpaused();
                if (streamState != StreamState.RUNNING) {
                    return;
                }
                byte[] buffer = new byte[PCM_CHUNK_BYTES];
                int offset = 0;
                while (offset < buffer.length) {
                    int read = pcmStream.read(buffer, offset, buffer.length - offset);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    offset += read;
                    decodedPcmBytes += read;
                }
                if (offset == 0) {
                    finishNaturally();
                    return;
                }
                if (streamState != StreamState.RUNNING) {
                    return;
                }
                byte[] chunk = offset == buffer.length ? buffer : Arrays.copyOf(buffer, offset);
                chunks.put(chunk);
                if (chunks.size() >= PREBUFFER_CHUNKS) {
                    primed.complete(null);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (streamState == StreamState.RUNNING) {
                fail(new IOException("Streaming decoder was interrupted", exception));
            }
        } catch (IOException | RuntimeException exception) {
            if (streamState == StreamState.RUNNING) {
                fail(exception instanceof IOException io ? io : new IOException("Streaming decode failed", exception));
            }
        }
    }

    private void awaitUnpaused() throws InterruptedException {
        synchronized (pauseMonitor) {
            while (paused && streamState == StreamState.RUNNING) {
                pauseMonitor.wait();
            }
        }
    }

    private void finishNaturally() {
        long actualDurationMs = decodedDurationMs();
        long toleranceMs = Math.max(MIN_PREMATURE_EOF_TOLERANCE_MS, expectedDurationMs / 50L);
        if (expectedDurationMs > 0L && actualDurationMs + toleranceMs < expectedDurationMs) {
            fail(new IOException(
                    "Online audio ended prematurely after " + actualDurationMs
                            + " ms; expected " + expectedDurationMs + " ms"
            ));
            return;
        }
        if (!transitionFromRunning(StreamState.EOF, null)) {
            return;
        }
        clearStarvation();
        primed.complete(null);
        offerTerminalMarker();
        scheduleResourceClose();
    }

    private void fail(IOException exception) {
        if (!transitionFromRunning(StreamState.FAILED, exception)) {
            return;
        }
        clearStarvation();
        primed.completeExceptionally(exception);
        offerTerminalMarker();
        scheduleResourceClose();
    }

    private synchronized boolean transitionFromRunning(StreamState target, IOException cause) {
        if (streamState != StreamState.RUNNING) {
            return false;
        }
        if (target == StreamState.FAILED) {
            failure = Objects.requireNonNull(cause, "cause");
        }
        streamState = target;
        return true;
    }

    private void offerTerminalMarker() {
        while (streamState == StreamState.EOF || streamState == StreamState.FAILED) {
            try {
                if (chunks.offer(END_OF_STREAM, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void scheduleResourceClose() {
        try {
            cleanupExecutor.execute(this::closeResources);
        } catch (RuntimeException ignored) {
            // A shutting-down executor must not move blocking I/O back to the caller thread.
        }
    }

    private void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            pcmStream.close();
        } catch (IOException ignored) {
            // Playback state already carries the useful failure or cancellation reason.
        }
        if (encodedStream != pcmStream) {
            try {
                encodedStream.close();
            } catch (IOException ignored) {
                // Playback state already carries the useful failure or cancellation reason.
            }
        }
    }

    private static JavaSoundStreamingAudioStream create(
            PlaybackSource source,
            HttpClient httpClient,
            Executor cleanupExecutor
    ) {
        if (!source.contentType().toLowerCase().contains("mpeg")
                && !source.contentType().toLowerCase().contains("mp3")) {
            throw new IllegalArgumentException("Only MP3 online streams are supported");
        }
        if (source.expiresAtEpochMs() > 0L && source.expiresAtEpochMs() <= System.currentTimeMillis()) {
            throw new IllegalStateException("Playback source has expired");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(source.uri())
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "audio/mpeg,audio/*;q=0.8")
                .header("User-Agent", "Cubic-Cadence/1.0")
                .GET();
        for (Map.Entry<String, String> header : source.requestHeaders().entrySet()) {
            if (!header.getKey().equalsIgnoreCase("cookie")
                    && !header.getKey().equalsIgnoreCase("authorization")) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("Audio CDN returned HTTP " + response.statusCode());
            }
            BufferedInputStream network = new BufferedInputStream(response.body(), PCM_CHUNK_BYTES);
            javazoom.spi.mpeg.sampled.file.MpegAudioFileReader reader =
                    new javazoom.spi.mpeg.sampled.file.MpegAudioFileReader();
            AudioInputStream encoded = reader.getAudioInputStream(network);
            AudioFormat sourceFormat = encoded.getFormat();
            if (sourceFormat.getChannels() != 1 && sourceFormat.getChannels() != 2) {
                encoded.close();
                throw new IOException("Only mono and stereo streams are supported");
            }
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false
            );
            javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider converter =
                    new javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider();
            AudioInputStream pcm = converter.getAudioInputStream(pcmFormat, encoded);
            return new JavaSoundStreamingAudioStream(
                    encoded,
                    pcm,
                    source.playableDurationMs(),
                    cleanupExecutor
            );
        } catch (IOException | InterruptedException | UnsupportedAudioFileException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Unable to open online MP3 stream", exception);
        }
    }
}
