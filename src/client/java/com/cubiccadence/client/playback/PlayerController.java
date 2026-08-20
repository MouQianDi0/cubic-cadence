package com.cubiccadence.client.playback;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.client.config.ModConfig;
import com.cubiccadence.model.PlaybackAccess;
import com.cubiccadence.model.PlaybackMode;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaybackState;
import com.cubiccadence.model.Track;
import com.cubiccadence.provider.MusicProvider;
import com.cubiccadence.provider.AudioQuality;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class PlayerController {
    private final MusicProvider provider;
    private final Supplier<Optional<AuthSession>> sessionSupplier;
    private final PlaybackEngine audioEngine;
    private final PlaybackQueue queue;
    private final Executor callbackExecutor;
    private final Supplier<AudioQuality> qualitySupplier;
    private final AtomicLong operation = new AtomicLong();

    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile Track currentTrack;
    private volatile PlaybackSource currentSource;
    private volatile String lastError;
    private volatile boolean endedHandled;
    private volatile boolean expiryRetryUsed;

    public PlayerController(
            MusicProvider provider,
            Supplier<Optional<AuthSession>> sessionSupplier,
            AudioEngine audioEngine,
            Executor callbackExecutor
    ) {
        this(
                provider,
                sessionSupplier,
                audioEngine,
                new PlaybackQueue(),
                callbackExecutor,
                () -> ModConfig.getInstance().getAudioQuality()
        );
    }

    PlayerController(
            MusicProvider provider,
            Supplier<Optional<AuthSession>> sessionSupplier,
            PlaybackEngine audioEngine,
            PlaybackQueue queue,
            Executor callbackExecutor,
            Supplier<AudioQuality> qualitySupplier
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.qualitySupplier = Objects.requireNonNull(qualitySupplier, "qualitySupplier");
    }

    public PlaybackState getState() {
        return state;
    }

    public Track getCurrentTrack() {
        return currentTrack;
    }

    public Optional<PlaybackSource> getCurrentSource() {
        return Optional.ofNullable(currentSource);
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public long getPositionMs() {
        return audioEngine.getPositionMs();
    }

    public long getDurationMs() {
        long engineDuration = audioEngine.getDurationMs();
        return engineDuration > 0L || currentTrack == null ? engineDuration : currentTrack.durationMs();
    }

    public float getVolume() {
        return audioEngine.getVolume();
    }

    public boolean isSeekSupported() {
        return audioEngine.isSeekSupported();
    }

    public PlaybackMode getPlaybackMode() {
        return queue.getMode();
    }

    public boolean isTrial() {
        PlaybackSource source = currentSource;
        return source != null && source.access() == PlaybackAccess.TRIAL;
    }

    public void playQueue(List<Track> tracks, int selectedIndex) {
        queue.setTracks(tracks, selectedIndex);
        Track selected = queue.current();
        if (selected == null) {
            fail("playback_error.cubic-cadence.empty_queue");
            return;
        }
        expiryRetryUsed = false;
        playSelected(selected, 0);
    }

    public void play(Track track) {
        Objects.requireNonNull(track, "track");
        queue.setTracks(List.of(track));
        expiryRetryUsed = false;
        playSelected(track, 0);
    }

    public void pause() {
        if (state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING) {
            audioEngine.pause();
            state = audioEngine.getState();
        }
    }

    public void resume() {
        if (state == PlaybackState.PAUSED) {
            audioEngine.resume();
            state = audioEngine.getState();
        }
    }

    public void next() {
        Track next = queue.next();
        if (next == null) {
            stopAtQueueEnd();
            return;
        }
        expiryRetryUsed = false;
        playSelected(next, 0);
    }

    public void previous() {
        Track previous = queue.previous();
        if (previous == null) {
            return;
        }
        expiryRetryUsed = false;
        playSelected(previous, 0);
    }

    public void seekTo(long positionMs) {
        if (audioEngine.isSeekSupported()) {
            audioEngine.seek(positionMs);
        }
    }

    public void setVolume(float volume) {
        audioEngine.setVolume(volume);
    }

    public void setPlaybackMode(PlaybackMode mode) {
        queue.setMode(mode);
    }

    public void tick() {
        if (currentTrack != null && sessionSupplier.get().isEmpty()) {
            stop();
            return;
        }
        PlaybackState engineState = audioEngine.getState();
        if (engineState == PlaybackState.ERROR) {
            PlaybackSource source = currentSource;
            if (!expiryRetryUsed && currentTrack != null && source != null
                    && source.expiresAtEpochMs() > 0L
                    && source.expiresAtEpochMs() <= System.currentTimeMillis() + 5_000L) {
                expiryRetryUsed = true;
                playSelected(currentTrack, 0);
                return;
            }
            state = PlaybackState.ERROR;
            lastError = "playback_error.cubic-cadence.stream_failed";
            return;
        }
        if (engineState == PlaybackState.ENDED) {
            if (!endedHandled) {
                endedHandled = true;
                Track next = queue.nextAfterEnd();
                if (next == null) {
                    stopAtQueueEnd();
                } else {
                    expiryRetryUsed = false;
                    playSelected(next, 0);
                }
            }
            return;
        }
        if (state != PlaybackState.RESOLVING) {
            state = engineState;
        }
    }

    public void stop() {
        operation.incrementAndGet();
        audioEngine.stop();
        currentTrack = null;
        currentSource = null;
        lastError = null;
        endedHandled = false;
        expiryRetryUsed = false;
        state = PlaybackState.IDLE;
    }

    private void playSelected(Track track, int skipped) {
        if (!PlaybackQueue.canAttempt(track)) {
            skipOrFail(skipped, "playback_error.cubic-cadence.restricted");
            return;
        }
        AuthSession session = sessionSupplier.get().orElse(null);
        if (session == null) {
            fail("playback_error.cubic-cadence.session_required");
            return;
        }
        AudioQuality quality = qualitySupplier.get();
        if (quality == AudioQuality.LOSSLESS) {
            fail("playback_error.cubic-cadence.lossless_unsupported");
            return;
        }
        long expectedOperation = operation.incrementAndGet();
        audioEngine.stop();
        currentTrack = track;
        currentSource = null;
        lastError = null;
        endedHandled = false;
        state = PlaybackState.RESOLVING;
        provider.resolvePlaybackSource(session, track.trackId(), quality)
                .whenComplete((source, throwable) -> callbackExecutor.execute(() -> {
                    if (operation.get() != expectedOperation) {
                        return;
                    }
                    if (throwable != null || source == null) {
                        skipOrFail(skipped, "playback_error.cubic-cadence.resolve_failed");
                        return;
                    }
                    currentSource = source;
                    state = PlaybackState.BUFFERING;
                    audioEngine.play(source);
                }));
    }

    private void skipOrFail(int skipped, String message) {
        if (skipped + 1 < Math.max(1, queue.tracks().size())) {
            Track next = queue.next();
            if (next != null) {
                expiryRetryUsed = false;
                playSelected(next, skipped + 1);
                return;
            }
        }
        fail(message);
    }

    private void stopAtQueueEnd() {
        operation.incrementAndGet();
        audioEngine.stop();
        currentSource = null;
        endedHandled = true;
        state = PlaybackState.ENDED;
    }

    private void fail(String message) {
        operation.incrementAndGet();
        audioEngine.stop();
        lastError = message;
        state = PlaybackState.ERROR;
    }
}
