package com.cubiccadence.lyrics;

import com.cubiccadence.model.LyricLine;
import com.cubiccadence.model.SyncedLyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses standard LRC timestamps and aligns translated lines by timestamp. */
public final class LrcParser {
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]"
    );
    private static final Pattern OFFSET = Pattern.compile(
            "(?im)^\\[offset:([+-]?\\d+)]\\s*$"
    );

    private LrcParser() {
    }

    public static SyncedLyrics parse(
            String providerId,
            String trackId,
            String original,
            String translated
    ) {
        Map<Long, String> originalLines = parseLines(original);
        Map<Long, String> translatedLines = parseLines(translated);
        List<LyricLine> lines = new ArrayList<>(originalLines.size());
        originalLines.forEach((timestamp, text) -> lines.add(new LyricLine(
                timestamp,
                text,
                translatedLines.getOrDefault(timestamp, "")
        )));
        return new SyncedLyrics(providerId, trackId, lines);
    }

    static Map<Long, String> parseLines(String source) {
        TreeMap<Long, String> parsed = new TreeMap<>();
        if (source == null || source.isBlank()) {
            return parsed;
        }
        long offset = parseOffset(source);
        for (String line : source.split("\\R")) {
            Matcher matcher = TIMESTAMP.matcher(line);
            List<Long> timestamps = new ArrayList<>();
            int textStart = -1;
            while (matcher.find()) {
                timestamps.add(timestamp(matcher));
                textStart = matcher.end();
            }
            if (timestamps.isEmpty() || textStart < 0) {
                continue;
            }
            String text = line.substring(textStart).trim();
            if (text.isBlank() || text.startsWith("{")) {
                continue;
            }
            for (long timestamp : timestamps) {
                parsed.putIfAbsent(Math.max(0L, timestamp + offset), text);
            }
        }
        return parsed;
    }

    private static long parseOffset(String source) {
        Matcher matcher = OFFSET.matcher(source);
        if (!matcher.find()) {
            return 0L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long timestamp(Matcher matcher) {
        long minutes = Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));
        String fraction = matcher.group(3);
        long milliseconds = 0L;
        if (fraction != null) {
            milliseconds = switch (fraction.length()) {
                case 1 -> Long.parseLong(fraction) * 100L;
                case 2 -> Long.parseLong(fraction) * 10L;
                default -> Long.parseLong(fraction.substring(0, 3));
            };
        }
        return minutes * 60_000L + seconds * 1_000L + milliseconds;
    }
}
