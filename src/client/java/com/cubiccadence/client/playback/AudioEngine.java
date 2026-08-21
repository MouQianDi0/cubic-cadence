package com.cubiccadence.client.playback;

import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.client.mixin.ChannelAccessor;
import com.cubiccadence.client.mixin.SoundBufferLibraryAccessor;
import com.cubiccadence.client.mixin.SoundEngineAccessor;
import com.cubiccadence.client.mixin.SoundManagerAccessor;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaybackState;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.OpenAlUtil;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decodes audio away from the client thread and submits it to Minecraft's
 * existing SoundEngine. It never creates a second OpenAL device or modifies
 * MusicManager.currentMusic, so vanilla music and Cubic Cadence can coexist.
 */
public class AudioEngine implements PlaybackEngine, AutoCloseable {
    private static final Identifier LOCAL_EVENT_ID = CubicCadenceClient.id("local_music");
    private static final Identifier LOCAL_BUFFER_ID = CubicCadenceClient.id("generated/local_music");

    private final AudioDecoder decoder;
    private final ExecutorService decodeExecutor;
    private final ExecutorService streamExecutor;
    private final ExecutorService streamCleanupExecutor;
    private final HttpClient streamHttpClient;
    private final AtomicLong operationSequence = new AtomicLong();

    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile float volume = 1.0f;
    private volatile long durationMs;
    private volatile String lastError;
    private volatile boolean closed;

    private Identifier cachedResource;
    private DecodedAudio cachedAudio;
    private SoundBuffer soundBuffer;
    private SoundBuffer streamingBootstrapBuffer;
    private JavaSoundStreamingAudioStream activeStream;
    private JavaSoundStreamingAudioStream preloadedStream;
    private PlaybackSource preloadedSource;
    private long preloadGeneration;
    private LocalMusicSoundInstance currentInstance;
    private boolean streaming;
    private long playbackStartedNanos;
    private long pausedAtNanos;
    private long totalPausedNanos;

    public AudioEngine(AudioDecoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "cubic-cadence-audio-decoder");
            thread.setDaemon(true);
            return thread;
        };
        this.decodeExecutor = Executors.newSingleThreadExecutor(threadFactory);
        this.streamExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "cubic-cadence-online-audio");
            thread.setDaemon(true);
            return thread;
        });
        this.streamCleanupExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "cubic-cadence-audio-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.streamHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void start() {
        ensureOpen();
    }

    public void playLocal(Identifier resourceId) {
        Objects.requireNonNull(resourceId, "resourceId");
        ensureOpen();

        long operation = this.operationSequence.incrementAndGet();
        stopCurrentInstance();
        this.lastError = null;

        if (resourceId.equals(this.cachedResource) && this.cachedAudio != null) {
            installAndPlay(operation, this.cachedAudio);
            return;
        }

        this.state = PlaybackState.BUFFERING;
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture
                .supplyAsync(() -> decodeResource(minecraft, resourceId), this.decodeExecutor)
                .whenComplete((decoded, throwable) -> minecraft.execute(() -> {
                    if (operation != this.operationSequence.get() || this.closed) {
                        return;
                    }
                    if (throwable != null) {
                        fail("Unable to decode local test audio", throwable);
                        return;
                    }
                    this.cachedResource = resourceId;
                    this.cachedAudio = decoded;
                    installAndPlay(operation, decoded);
                }));
    }

    public void play(PlaybackSource source) {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if ("http".equalsIgnoreCase(source.uri().getScheme())
                || "https".equalsIgnoreCase(source.uri().getScheme())) {
            playOnline(source);
            return;
        }
        if (!"file".equalsIgnoreCase(source.uri().getScheme())) {
            fail("Unsupported playback source scheme", null);
            return;
        }
        long operation = this.operationSequence.incrementAndGet();
        stopCurrentInstance();
        this.lastError = null;
        this.state = PlaybackState.BUFFERING;
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture
                .supplyAsync(() -> decodeFile(Path.of(source.uri()), source.contentType()), this.decodeExecutor)
                .whenComplete((decoded, throwable) -> minecraft.execute(() -> {
                    if (operation != this.operationSequence.get() || this.closed) {
                        return;
                    }
                    if (throwable != null) {
                        fail("Unable to decode local playback source", throwable);
                        return;
                    }
                    installAndPlay(operation, decoded);
                }));
    }

    @Override
    public void preload(PlaybackSource source) {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!("http".equalsIgnoreCase(source.uri().getScheme())
                || "https".equalsIgnoreCase(source.uri().getScheme()))) {
            return;
        }
        long generation = ++this.preloadGeneration;
        Minecraft minecraft = Minecraft.getInstance();
        JavaSoundStreamingAudioStream.open(
                        source,
                        this.streamHttpClient,
                        this.streamExecutor,
                        this.streamCleanupExecutor
                )
                .whenComplete((stream, throwable) -> minecraft.execute(() -> {
                    if (this.closed || generation != this.preloadGeneration) {
                        closeQuietly(stream);
                        return;
                    }
                    if (throwable != null || stream == null) {
                        return;
                    }
                    JavaSoundStreamingAudioStream previous = this.preloadedStream;
                    this.preloadedStream = stream;
                    this.preloadedSource = source;
                    if (previous != null && previous != stream) {
                        closeQuietly(previous);
                    }
                }));
    }

    @Override
    public void cancelPreload() {
        this.preloadGeneration++;
        JavaSoundStreamingAudioStream stream = this.preloadedStream;
        this.preloadedStream = null;
        this.preloadedSource = null;
        closeQuietly(stream);
    }

    public void pause() {
        if (this.state != PlaybackState.PLAYING || this.currentInstance == null) {
            return;
        }
        ChannelAccess.ChannelHandle handle = currentHandle();
        if (handle == null) {
            return;
        }
        handle.execute(Channel::pause);
        this.pausedAtNanos = System.nanoTime();
        this.state = PlaybackState.PAUSED;
    }

    public void resume() {
        if (this.state != PlaybackState.PAUSED || this.currentInstance == null) {
            return;
        }
        ChannelAccess.ChannelHandle handle = currentHandle();
        if (handle == null) {
            return;
        }
        handle.execute(Channel::unpause);
        if (this.pausedAtNanos != 0L) {
            this.totalPausedNanos += System.nanoTime() - this.pausedAtNanos;
            this.pausedAtNanos = 0L;
        }
        this.state = PlaybackState.PLAYING;
    }

    public void stop() {
        this.operationSequence.incrementAndGet();
        stopCurrentInstance();
        this.state = PlaybackState.IDLE;
        this.durationMs = 0L;
        resetClock();
    }

    public void seek(long positionMs) {
        if (this.streaming) {
            return;
        }
        PlaybackState currentState = this.state;
        if ((currentState != PlaybackState.PLAYING && currentState != PlaybackState.PAUSED)
                || this.durationMs <= 0L) {
            return;
        }

        ChannelAccess.ChannelHandle handle = currentHandle();
        if (handle == null) {
            return;
        }

        long clampedPositionMs = Math.max(0L, Math.min(positionMs, this.durationMs));
        long effectivePositionMs = Math.min(clampedPositionMs, Math.max(0L, this.durationMs - 1L));
        handle.execute(channel -> AL10.alSourcef(
                ((ChannelAccessor) channel).cubicCadence$getSource(),
                AL11.AL_SEC_OFFSET,
                effectivePositionMs / 1000.0f
        ));
        resetClockTo(effectivePositionMs, currentState == PlaybackState.PAUSED);
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        LocalMusicSoundInstance instance = this.currentInstance;
        if (instance != null) {
            instance.setTrackVolume(this.volume);
        }
    }

    public float getVolume() {
        return this.volume;
    }

    public PlaybackState getState() {
        return this.state;
    }

    public String getLastError() {
        return this.lastError;
    }

    public long getPositionMs() {
        if (this.playbackStartedNanos == 0L) {
            return 0L;
        }
        long endNanos = (this.state == PlaybackState.PAUSED || this.state == PlaybackState.BUFFERING)
                && this.pausedAtNanos != 0L
                ? this.pausedAtNanos
                : System.nanoTime();
        long elapsedNanos = Math.max(0L, endNanos - this.playbackStartedNanos - this.totalPausedNanos);
        return Math.min(this.durationMs, elapsedNanos / 1_000_000L);
    }

    public long getDurationMs() {
        return this.durationMs;
    }

    public boolean isSeekSupported() {
        return !this.streaming;
    }

    public void tick() {
        LocalMusicSoundInstance instance = this.currentInstance;
        if (instance == null || (this.state != PlaybackState.PLAYING
                && this.state != PlaybackState.PAUSED
                && this.state != PlaybackState.BUFFERING)) {
            return;
        }
        JavaSoundStreamingAudioStream stream = this.activeStream;
        updateStreamingBufferingState(stream);
        if (!Minecraft.getInstance().getSoundManager().isActive(instance)) {
            this.currentInstance = null;
            this.activeStream = null;
            this.streaming = false;
            closeQuietly(stream);
            if (stream != null && stream.streamState() == JavaSoundStreamingAudioStream.StreamState.FAILED) {
                this.lastError = stream.failure() == null
                        ? "Online audio stream failed"
                        : stream.failure().getMessage();
                this.state = PlaybackState.ERROR;
            } else if (stream == null
                    || stream.streamState() == JavaSoundStreamingAudioStream.StreamState.EOF) {
                this.state = PlaybackState.ENDED;
            } else {
                this.state = PlaybackState.ERROR;
                this.lastError = "Online audio channel stopped before the stream completed";
            }
            this.pausedAtNanos = 0L;
            return;
        }
        if (this.state == PlaybackState.PAUSED) {
            ChannelAccess.ChannelHandle handle = currentHandle();
            if (handle != null) {
                handle.execute(Channel::pause);
            }
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        stop();
        cancelPreload();
        this.closed = true;
        this.decodeExecutor.shutdownNow();
        this.streamExecutor.shutdownNow();
        this.streamCleanupExecutor.shutdown();
        this.cachedAudio = null;
        this.cachedResource = null;
        if (this.soundBuffer != null) {
            this.soundBuffer.discardAlBuffer();
        }
        this.soundBuffer = null;
        if (this.streamingBootstrapBuffer != null) {
            this.streamingBootstrapBuffer.discardAlBuffer();
        }
        this.streamingBootstrapBuffer = null;
    }

    private void playOnline(PlaybackSource source) {
        long operation = this.operationSequence.incrementAndGet();
        stopCurrentInstance();
        this.lastError = null;
        this.durationMs = source.playableDurationMs();
        this.state = PlaybackState.BUFFERING;
        Minecraft minecraft = Minecraft.getInstance();

        JavaSoundStreamingAudioStream ready = consumePreloaded(source);
        if (ready != null) {
            installStreamingAndPlay(operation, ready);
            return;
        }

        JavaSoundStreamingAudioStream.open(
                        source,
                        this.streamHttpClient,
                        this.streamExecutor,
                        this.streamCleanupExecutor
                )
                .whenComplete((stream, throwable) -> minecraft.execute(() -> {
                    if (operation != this.operationSequence.get() || this.closed) {
                        closeQuietly(stream);
                        return;
                    }
                    if (throwable != null) {
                        fail("Unable to buffer online MP3 stream", null);
                        return;
                    }
                    installStreamingAndPlay(operation, stream);
                }));
    }

    private JavaSoundStreamingAudioStream consumePreloaded(PlaybackSource source) {
        JavaSoundStreamingAudioStream stream = this.preloadedStream;
        if (stream == null || this.preloadedSource == null || !this.preloadedSource.equals(source)) {
            return null;
        }
        this.preloadedStream = null;
        this.preloadedSource = null;
        if (stream.streamState() != JavaSoundStreamingAudioStream.StreamState.RUNNING) {
            closeQuietly(stream);
            return null;
        }
        return stream;
    }

    private DecodedAudio decodeResource(Minecraft minecraft, Identifier resourceId) {
        try (InputStream input = minecraft.getResourceManager().open(resourceId)) {
            return this.decoder.decode(input.readAllBytes());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to read " + resourceId, exception);
        }
    }

    private DecodedAudio decodeFile(Path path, String contentType) {
        if (!this.decoder.supports(contentType)) {
            throw new IllegalArgumentException("Unsupported local audio type: " + contentType);
        }
        try {
            return this.decoder.decode(Files.readAllBytes(path));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to read local audio file", exception);
        }
    }

    private void installAndPlay(long operation, DecodedAudio decoded) {
        if (operation != this.operationSequence.get() || this.closed) {
            return;
        }

        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        SoundEngine soundEngine = ((SoundManagerAccessor) soundManager).cubicCadence$getSoundEngine();
        SoundBufferLibrary soundBuffers = ((SoundEngineAccessor) soundEngine).cubicCadence$getSoundBuffers();

        LocalMusicSoundInstance instance = new LocalMusicSoundInstance(
                LOCAL_EVENT_ID,
                LOCAL_BUFFER_ID,
                this.volume
        );
        if (this.soundBuffer != null) {
            this.soundBuffer.discardAlBuffer();
        }
        this.soundBuffer = new SoundBuffer(decoded.pcm().duplicate(), decoded.format());
        Map<Identifier, CompletableFuture<SoundBuffer>> cache =
                ((SoundBufferLibraryAccessor) soundBuffers).cubicCadence$getCache();
        cache.put(instance.bufferPath(), CompletableFuture.completedFuture(this.soundBuffer));

        SoundEngine.PlayResult result = soundManager.play(instance);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            fail("Minecraft SoundEngine did not start the local track", null);
            return;
        }

        this.currentInstance = instance;
        this.streaming = false;
        this.durationMs = decoded.durationMs();
        this.playbackStartedNanos = System.nanoTime();
        this.pausedAtNanos = 0L;
        this.totalPausedNanos = 0L;
        this.state = PlaybackState.PLAYING;
    }

    private void installStreamingAndPlay(long operation, JavaSoundStreamingAudioStream stream) {
        if (operation != this.operationSequence.get() || this.closed) {
            closeQuietly(stream);
            return;
        }
        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        SoundEngine soundEngine = ((SoundManagerAccessor) soundManager).cubicCadence$getSoundEngine();
        SoundBufferLibrary soundBuffers = ((SoundEngineAccessor) soundEngine).cubicCadence$getSoundBuffers();
        SoundBuffer previousBootstrapBuffer = this.streamingBootstrapBuffer;
        AudioFormat silenceFormat = new AudioFormat(44_100.0f, 16, 2, true, false);
        SoundBuffer bootstrapBuffer = new SoundBuffer(ByteBuffer.allocateDirect(4_410 * 4), silenceFormat);
        this.streamingBootstrapBuffer = bootstrapBuffer;
        LocalMusicSoundInstance instance = new LocalMusicSoundInstance(
                LOCAL_EVENT_ID,
                LOCAL_BUFFER_ID,
                this.volume
        );
        Map<Identifier, CompletableFuture<SoundBuffer>> cache =
                ((SoundBufferLibraryAccessor) soundBuffers).cubicCadence$getCache();
        cache.put(instance.bufferPath(), CompletableFuture.completedFuture(bootstrapBuffer));
        SoundEngine.PlayResult result = soundManager.play(instance);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            closeQuietly(stream);
            fail("Minecraft SoundEngine did not start the online track", null);
            return;
        }
        this.currentInstance = instance;
        this.activeStream = stream;
        this.streaming = true;
        ChannelAccess.ChannelHandle handle = currentHandle();
        if (handle == null) {
            stopCurrentInstance();
            fail("Minecraft SoundEngine did not create an online audio channel", null);
            return;
        }
        handle.execute(channel -> {
            if (operation != this.operationSequence.get() || this.closed) {
                closeQuietly(stream);
                return;
            }
            if (previousBootstrapBuffer != null && previousBootstrapBuffer != bootstrapBuffer) {
                previousBootstrapBuffer.discardAlBuffer();
            }
            channel.stop();
            int sourceId = ((ChannelAccessor) channel).cubicCadence$getSource();
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
            if (OpenAlUtil.checkALError("Detaching Cubic Cadence bootstrap buffer")) {
                failStreamingChannel(instance, stream, channel, operation,
                        "Unable to detach the online audio bootstrap buffer");
                return;
            }
            bootstrapBuffer.discardAlBuffer();
            if (this.streamingBootstrapBuffer == bootstrapBuffer) {
                this.streamingBootstrapBuffer = null;
            }
            channel.attachBufferStream(stream);
            int queuedBuffers = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
            if (OpenAlUtil.checkALError("Inspecting Cubic Cadence stream queue") || queuedBuffers <= 0) {
                failStreamingChannel(instance, stream, channel, operation,
                        "Minecraft SoundEngine could not queue the online audio stream");
                return;
            }
            channel.setVolume(this.volume);
            channel.play();
            this.playbackStartedNanos = System.nanoTime();
            this.pausedAtNanos = 0L;
            this.totalPausedNanos = 0L;
            this.state = PlaybackState.PLAYING;
        });
    }

    private void failStreamingChannel(
            LocalMusicSoundInstance instance,
            JavaSoundStreamingAudioStream stream,
            Channel channel,
            long operation,
            String message
    ) {
        channel.stop();
        closeQuietly(stream);
        if (operation != this.operationSequence.get()) {
            return;
        }
        instance.requestStop();
        if (this.currentInstance == instance) {
            this.currentInstance = null;
        }
        if (this.activeStream == stream) {
            this.activeStream = null;
        }
        this.streaming = false;
        fail(message, null);
    }

    private ChannelAccess.ChannelHandle currentHandle() {
        LocalMusicSoundInstance instance = this.currentInstance;
        if (instance == null) {
            return null;
        }
        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        SoundEngine soundEngine = ((SoundManagerAccessor) soundManager).cubicCadence$getSoundEngine();
        return ((SoundEngineAccessor) soundEngine).cubicCadence$getInstanceToChannel().get(instance);
    }

    private void stopCurrentInstance() {
        LocalMusicSoundInstance instance = this.currentInstance;
        if (instance != null) {
            instance.requestStop();
            Minecraft.getInstance().getSoundManager().stop(instance);
            this.currentInstance = null;
        }
        JavaSoundStreamingAudioStream stream = this.activeStream;
        this.activeStream = null;
        this.streaming = false;
        closeQuietly(stream);
    }

    private void updateStreamingBufferingState(JavaSoundStreamingAudioStream stream) {
        if (stream == null
                || stream.streamState() != JavaSoundStreamingAudioStream.StreamState.RUNNING
                || this.state == PlaybackState.PAUSED) {
            return;
        }
        if (stream.isStarved()) {
            if (this.state == PlaybackState.PLAYING) {
                this.pausedAtNanos = System.nanoTime();
                this.state = PlaybackState.BUFFERING;
            }
            return;
        }
        if (this.state == PlaybackState.BUFFERING && this.playbackStartedNanos != 0L) {
            long now = System.nanoTime();
            if (this.pausedAtNanos != 0L) {
                this.totalPausedNanos += now - this.pausedAtNanos;
                this.pausedAtNanos = 0L;
            }
            this.state = PlaybackState.PLAYING;
        }
    }

    private void fail(String message, Throwable throwable) {
        this.lastError = message;
        this.state = PlaybackState.ERROR;
        if (throwable == null) {
            CubicCadenceClient.LOGGER.error(message);
        } else {
            CubicCadenceClient.LOGGER.error(message, throwable);
        }
    }

    private void resetClock() {
        this.playbackStartedNanos = 0L;
        this.pausedAtNanos = 0L;
        this.totalPausedNanos = 0L;
    }

    private static void closeQuietly(JavaSoundStreamingAudioStream stream) {
        if (stream == null) {
            return;
        }
        stream.close();
    }

    private void resetClockTo(long positionMs, boolean paused) {
        long now = System.nanoTime();
        this.playbackStartedNanos = now - positionMs * 1_000_000L;
        this.pausedAtNanos = paused ? now : 0L;
        this.totalPausedNanos = 0L;
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Audio engine has already been closed");
        }
    }
}
