package com.cubiccadence.model;

public record UserProfile(
        String providerId,
        String userId,
        String displayName,
        String avatarUrl
) {
}
