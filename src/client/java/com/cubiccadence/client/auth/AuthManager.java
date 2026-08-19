package com.cubiccadence.client.auth;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthState;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.provider.MusicProvider;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the asynchronous client-side authentication state machine. */
public final class AuthManager implements AutoCloseable {
    private static final long REFRESH_SKEW_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long MIN_POLL_INTERVAL_MS = 1_000L;

    private final MusicProvider provider;
    private final SecureTokenStore tokenStore;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong operation = new AtomicLong();
    private final AtomicBoolean pollInFlight = new AtomicBoolean();

    private volatile AuthState state = AuthState.SIGNED_OUT;
    private volatile AuthSession session;
    private volatile AuthorizationChallenge challenge;
    private volatile String lastError;
    private volatile ScheduledFuture<?> pollingTask;
    private volatile ScheduledFuture<?> refreshTask;
    private volatile boolean closed;

    public AuthManager(MusicProvider provider, SecureTokenStore tokenStore) {
        this(provider, tokenStore, Clock.systemUTC());
    }

    AuthManager(MusicProvider provider, SecureTokenStore tokenStore, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cubic-cadence-auth-poll");
            thread.setDaemon(true);
            return thread;
        });
    }

    public AuthState getState() {
        return state;
    }

    public Optional<AuthSession> getSession() {
        return Optional.ofNullable(session);
    }

    public Optional<AuthorizationChallenge> getChallenge() {
        return Optional.ofNullable(challenge);
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public CompletableFuture<Void> beginLogin() {
        ensureOpen();
        long currentOperation = operation.incrementAndGet();
        cancelPolling();
        cancelRefresh();
        session = null;
        challenge = null;
        lastError = null;
        state = AuthState.AUTHORIZING;
        return provider.beginLogin()
                .thenAccept(created -> {
                    if (!isCurrent(currentOperation)) {
                        return;
                    }
                    validateChallenge(created);
                    challenge = created;
                    schedulePolling(currentOperation, created);
                })
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null && isCurrent(currentOperation)) {
                        fail("Unable to start authorization");
                    }
                });
    }

    public CompletableFuture<Void> pollAuthorization() {
        ensureOpen();
        AuthorizationChallenge activeChallenge = challenge;
        if (activeChallenge == null || state != AuthState.AUTHORIZING) {
            return CompletableFuture.completedFuture(null);
        }
        return pollAuthorization(operation.get(), activeChallenge);
    }

    public CompletableFuture<Void> restoreSession() {
        ensureOpen();
        long currentOperation = operation.incrementAndGet();
        cancelPolling();
        cancelRefresh();
        lastError = null;
        final Optional<AuthSession> stored;
        try {
            stored = tokenStore.load();
        } catch (RuntimeException exception) {
            safeClearStore();
            fail("Stored session could not be read");
            return CompletableFuture.completedFuture(null);
        }
        if (stored.isEmpty()) {
            state = AuthState.SIGNED_OUT;
            session = null;
            return CompletableFuture.completedFuture(null);
        }
        AuthSession restored = stored.get();
        if (!provider.id().equals(restored.providerId())) {
            safeClearStore();
            state = AuthState.SIGNED_OUT;
            session = null;
            return CompletableFuture.completedFuture(null);
        }
        session = restored;
        if (restored.isUsableAt(clock.millis(), REFRESH_SKEW_MS)) {
            state = AuthState.SIGNED_IN;
            scheduleRefresh(restored);
            return CompletableFuture.completedFuture(null);
        }
        return refresh(currentOperation, restored);
    }

    public CompletableFuture<Void> refresh() {
        ensureOpen();
        AuthSession activeSession = session;
        if (activeSession == null) {
            state = AuthState.SIGNED_OUT;
            return CompletableFuture.completedFuture(null);
        }
        long currentOperation = operation.incrementAndGet();
        cancelPolling();
        cancelRefresh();
        return refresh(currentOperation, activeSession);
    }

    public CompletableFuture<Void> logout() {
        ensureOpen();
        operation.incrementAndGet();
        cancelPolling();
        cancelRefresh();
        AuthSession activeSession = session;
        session = null;
        challenge = null;
        lastError = null;
        safeClearStore();
        state = AuthState.SIGNED_OUT;
        if (activeSession == null) {
            return CompletableFuture.completedFuture(null);
        }
        return provider.logout(activeSession).exceptionally(throwable -> null);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        operation.incrementAndGet();
        cancelPolling();
        cancelRefresh();
        scheduler.shutdownNow();
    }

    private CompletableFuture<Void> refresh(long currentOperation, AuthSession activeSession) {
        state = AuthState.REFRESHING;
        lastError = null;
        return provider.refresh(activeSession)
                .thenAccept(refreshed -> {
                    if (!isCurrent(currentOperation)) {
                        return;
                    }
                    validateSession(refreshed);
                    tokenStore.save(refreshed);
                    session = refreshed;
                    state = AuthState.SIGNED_IN;
                    scheduleRefresh(refreshed);
                })
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null && isCurrent(currentOperation)) {
                        session = null;
                        safeClearStore();
                        state = AuthState.EXPIRED;
                        lastError = "Session refresh failed; authorization is required";
                    }
                });
    }

    private CompletableFuture<Void> pollAuthorization(long currentOperation, AuthorizationChallenge activeChallenge) {
        if (!pollInFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        if (clock.millis() >= activeChallenge.expiresAtEpochMs()) {
            pollInFlight.set(false);
            expireAuthorization();
            return CompletableFuture.completedFuture(null);
        }
        return provider.pollAuthorization(activeChallenge.authorizationId())
                .thenAccept(result -> handleAuthorizationResult(currentOperation, result))
                .whenComplete((ignored, throwable) -> {
                    pollInFlight.set(false);
                    if (throwable != null && isCurrent(currentOperation)) {
                        fail("Authorization status check failed");
                    }
                });
    }

    private void handleAuthorizationResult(long currentOperation, AuthorizationResult result) {
        if (!isCurrent(currentOperation)) {
            return;
        }
        if (result == null || result.status() == null) {
            throw new IllegalStateException("Gateway returned an invalid authorization result");
        }
        switch (result.status()) {
            case PENDING, SCANNED -> state = AuthState.AUTHORIZING;
            case AUTHORIZED -> {
                validateSession(result.session());
                tokenStore.save(result.session());
                session = result.session();
                challenge = null;
                lastError = null;
                state = AuthState.SIGNED_IN;
                cancelPolling();
                scheduleRefresh(result.session());
            }
            case DENIED -> {
                cancelPolling();
                challenge = null;
                state = AuthState.SIGNED_OUT;
                lastError = "Authorization was denied";
            }
            case EXPIRED -> expireAuthorization();
        }
    }

    private void schedulePolling(long currentOperation, AuthorizationChallenge created) {
        long interval = Math.max(MIN_POLL_INTERVAL_MS, created.pollIntervalMs());
        pollingTask = scheduler.scheduleWithFixedDelay(
                () -> pollAuthorization(currentOperation, created), interval, interval, TimeUnit.MILLISECONDS
        );
    }

    private void scheduleRefresh(AuthSession activeSession) {
        cancelRefresh();
        long delayMs = Math.max(
                MIN_POLL_INTERVAL_MS,
                activeSession.expiresAtEpochMs() - clock.millis() - REFRESH_SKEW_MS
        );
        refreshTask = scheduler.schedule(() -> {
            refresh();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void expireAuthorization() {
        cancelPolling();
        challenge = null;
        session = null;
        state = AuthState.EXPIRED;
        lastError = "Authorization request expired";
    }

    private void fail(String message) {
        cancelPolling();
        state = AuthState.ERROR;
        lastError = message;
    }

    private void validateChallenge(AuthorizationChallenge created) {
        if (created == null || created.authorizationId() == null || created.authorizationId().isBlank()
                || created.expiresAtEpochMs() <= clock.millis()) {
            throw new IllegalStateException("Gateway returned an invalid authorization challenge");
        }
        if (created.authorizationUrl() == null || created.authorizationUrl().isBlank()) {
            throw new IllegalStateException("Gateway returned no browser authorization URL");
        }
        try {
            URI authorizationUri = URI.create(created.authorizationUrl());
            if (!"https".equalsIgnoreCase(authorizationUri.getScheme()) || authorizationUri.getHost() == null) {
                throw new IllegalStateException("Gateway returned an unsafe authorization URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Gateway returned an invalid authorization URL", exception);
        }
    }

    private void validateSession(AuthSession candidate) {
        if (candidate == null || !provider.id().equals(candidate.providerId())
                || !candidate.isUsableAt(clock.millis(), 0L)) {
            throw new IllegalStateException("Gateway returned an invalid session");
        }
    }

    private boolean isCurrent(long expectedOperation) {
        return !closed && operation.get() == expectedOperation;
    }

    private void cancelPolling() {
        ScheduledFuture<?> activeTask = pollingTask;
        pollingTask = null;
        if (activeTask != null) {
            activeTask.cancel(false);
        }
    }

    private void cancelRefresh() {
        ScheduledFuture<?> activeTask = refreshTask;
        refreshTask = null;
        if (activeTask != null) {
            activeTask.cancel(false);
        }
    }

    private void safeClearStore() {
        try {
            tokenStore.clear();
        } catch (RuntimeException ignored) {
            // Local logout must complete even if a damaged store cannot be removed.
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("AuthManager is closed");
        }
    }
}
