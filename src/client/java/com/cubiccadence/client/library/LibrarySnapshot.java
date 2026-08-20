package com.cubiccadence.client.library;

import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;

import java.util.List;

/** Public account metadata cached locally for cache-first library startup. */
public record LibrarySnapshot(
        UserProfile profile,
        List<PlaylistSummary> playlists,
        long syncedAtEpochMillis
) {
    public LibrarySnapshot {
        playlists = List.copyOf(playlists);
    }
}
