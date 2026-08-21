package com.cubiccadence.model;

/** A provider-neutral, line-synchronized lyric entry. */
public record LyricLine(
        long startTimeMs,
        String text,
        String translatedText
) {
    public LyricLine {
        startTimeMs = Math.max(0L, startTimeMs);
        text = text == null ? "" : text.trim();
        translatedText = translatedText == null ? "" : translatedText.trim();
    }
}
