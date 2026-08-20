package com.cubiccadence.provider;

import com.cubiccadence.model.PlaylistSummary;

import java.util.List;

public record PlaylistSummaryPage(
        List<PlaylistSummary> items,
        boolean hasNext,
        Integer total
) {
    public PlaylistSummaryPage {
        items = List.copyOf(items);
    }
}
