package com.cubiccadence.model;

import java.util.List;

public record Track(
        String providerId,
        String trackId,
        String title,
        List<Artist> artists,
        String albumName,
        String coverUrl,
        long durationMs,
        Availability availability
) {
    public Track {
        artists = List.copyOf(artists);
    }
}
