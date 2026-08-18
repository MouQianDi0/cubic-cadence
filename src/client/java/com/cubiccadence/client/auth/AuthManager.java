package com.cubiccadence.client.auth;

import com.cubiccadence.auth.AuthState;
import com.cubiccadence.provider.MusicProvider;

import java.util.concurrent.CompletableFuture;

public class AuthManager {
    private final MusicProvider provider;
    private final SecureTokenStore tokenStore;
    private volatile AuthState state = AuthState.SIGNED_OUT;

    public AuthManager(MusicProvider provider, SecureTokenStore tokenStore) {
        this.provider = provider;
        this.tokenStore = tokenStore;
    }

    public AuthState getState() {
        return state;
    }

    public CompletableFuture<Void> beginLogin() {
        // TODO: start the provider authorization flow
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> pollAuthorization() {
        // TODO: poll the authorization result
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> restoreSession() {
        // TODO: restore the session from secure storage
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> refresh() {
        // TODO: refresh the access token
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> logout() {
        // TODO: clear the session
        return CompletableFuture.completedFuture(null);
    }
}
