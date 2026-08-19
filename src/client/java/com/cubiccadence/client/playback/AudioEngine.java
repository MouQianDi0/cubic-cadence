package com.cubiccadence.client.playback;

import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.client.mixin.ChannelAccessor;
import com.cubiccadence.client.mixin.SoundBufferLibraryAccessor;
import com.cubiccadence.client.mixin.SoundEngineAccessor;
import com.cubiccadence.client.mixin.SoundManagerAccessor;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaybackState;
import com.mojang.blaze3d.audio.Channel;
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
public class AudioEngine implements AutoCloseable {
    private static final Identifier LOCAL_EVENT_ID = CubicCadenceClient.id("local_music");
    private static final Identifier LOCAL_BUFFER_ID = CubicCadenceClient.id("generated/local_music");

    private final AudioDecoder decoder;
    private final ExecutorService decodeExecutor;
    private final AtomicLong operationSequence = new AtomicLong();

    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile float volume = 1.0f;
    private volatile long durationMs;
    private volatile String lastError;
    private volatile boolean closed;

    private Identifier cachedResource;
    private DecodedAudio cachedAudio;
    private SoundBuffer soundBuffer;
    private LocalMusicSoundInstance currentInstance;
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
        if (!"file".equalsIgnoreCase(source.uri().getScheme())) {
            fail("Stage 2 only supports local file playback", null);
            return;
        }

        ensureOpen();
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
        long endNanos = this.state == PlaybackState.PAUSED && this.pausedAtNanos != 0L
                ? this.pausedAtNanos
                : System.nanoTime();
        long elapsedNanos = Math.max(0L, endNanos - this.playbackStartedNanos - this.totalPausedNanos);
        return Math.min(this.durationMs, elapsedNanos / 1_000_000L);
    }

    public long getDurationMs() {
        return this.durationMs;
    }

    public void tick() {
        LocalMusicSoundInstance instance = this.currentInstance;
        if (instance == null || (this.state != PlaybackState.PLAYING && this.state != PlaybackState.PAUSED)) {
            return;
        }
        if (!Minecraft.getInstance().getSoundManager().isActive(instance)) {
            this.currentInstance = null;
            this.state = PlaybackState.ENDED;
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
        this.closed = true;
        this.decodeExecutor.shutdownNow();
        this.cachedAudio = null;
        this.cachedResource = null;
        if (this.soundBuffer != null) {
            this.soundBuffer.discardAlBuffer();
        }
        this.soundBuffer = null;
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
        this.durationMs = decoded.durationMs();
        this.playbackStartedNanos = System.nanoTime();
        this.pausedAtNanos = 0L;
        this.totalPausedNanos = 0L;
        this.state = PlaybackState.PLAYING;
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
        if (instance == null) {
            return;
        }
        instance.requestStop();
        Minecraft.getInstance().getSoundManager().stop(instance);
        this.currentInstance = null;
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
