package com.cubiccadence.provider;

import java.util.List;

public record SearchPage<T>(
        List<T> items,
        boolean hasNext,
        Integer total,
        String nextCursor
) {
}
