package com.cubiccadence.auth;

public record AuthSession(
        String providerId,
        String cookie,
        long expiresAtEpochMs
) {
    public boolean isUsableAt(long epochMs, long refreshSkewMs) {
        return cookie != null
                && !cookie.isBlank()
                && expiresAtEpochMs > epochMs + Math.max(0L, refreshSkewMs);
    }
}
