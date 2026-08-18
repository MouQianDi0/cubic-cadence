package com.cubiccadence.provider;

import com.cubiccadence.model.Track;

import java.util.List;

public record PlaylistPage(
        List<Track> tracks,
        boolean hasNext,
        String nextCursor
) {
}
