package com.cubiccadence.client.auth;

import com.cubiccadence.auth.AuthSession;

import java.util.Optional;

public interface SecureTokenStore {
    void save(AuthSession session);

    Optional<AuthSession> load();

    void clear();
}
