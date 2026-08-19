package com.cubiccadence.auth;

public record AuthorizationResult(
        AuthorizationStatus status,
        AuthSession session,
        String message
) {
}
