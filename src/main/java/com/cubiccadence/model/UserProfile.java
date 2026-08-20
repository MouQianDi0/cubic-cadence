package com.cubiccadence.model;

public record UserProfile(
        String providerId,
        String userId,
        String displayName,
        String avatarUrl,
        int level,
        MembershipTier membershipTier
) {
    public static final int UNKNOWN_LEVEL = -1;
}
