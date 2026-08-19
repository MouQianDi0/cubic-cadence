package com.cubiccadence.auth;

/**
 * A short-lived authorization request created by the Cubic Cadence gateway.
 * It contains no NetEase developer secret or user token.
 */
public record AuthorizationChallenge(
        String authorizationId,
        String authorizationUrl,
        String qrCodeContent,
        long expiresAtEpochMs,
        long pollIntervalMs
) {
}
