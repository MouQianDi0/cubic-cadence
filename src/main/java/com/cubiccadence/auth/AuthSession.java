package com.cubiccadence.auth;

public record AuthSession(
        String providerId,
        String accessToken,
        String refreshToken,
        long expiresAtEpochMs
) {
}
