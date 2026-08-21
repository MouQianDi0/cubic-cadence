package com.cubiccadence.lyrics;

import com.cubiccadence.model.SyncedLyrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LrcParserTest {
    @Test
    void parsesMultipleTimestampsFractionsAndOffset() {
        SyncedLyrics lyrics = LrcParser.parse(
                "netease",
                "1",
                """
                        [offset:+100]
                        [00:01.2][00:02.34]同一句
                        [01:03.456]最后一句
                        [ar:歌手]
                        malformed
                        """,
                ""
        );

        assertEquals(3, lyrics.lines().size());
        assertEquals(1_300L, lyrics.lines().get(0).startTimeMs());
        assertEquals(2_440L, lyrics.lines().get(1).startTimeMs());
        assertEquals(63_556L, lyrics.lines().get(2).startTimeMs());
    }

    @Test
    void alignsTranslationsAndFindsCurrentAndNextLines() {
        SyncedLyrics lyrics = LrcParser.parse(
                "netease",
                "2",
                "[00:01.00]第一行\n[00:03.00]第二行",
                "[00:03.00]Second line\n[00:02.00]Ignored"
        );

        assertTrue(lyrics.currentLine(500L).isEmpty());
        assertEquals("第一行", lyrics.nextLine(500L).orElseThrow().text());
        assertEquals("第一行", lyrics.currentLine(2_000L).orElseThrow().text());
        assertEquals("第二行", lyrics.nextLine(2_000L).orElseThrow().text());
        assertEquals("Second line", lyrics.currentLine(3_000L).orElseThrow().translatedText());
        assertTrue(lyrics.nextLine(3_000L).isEmpty());
    }

    @Test
    void ignoresBlankAndMetadataOnlyLines() {
        SyncedLyrics lyrics = LrcParser.parse(
                "netease",
                "3",
                "[00:01.00]\n{\"t\":0,\"c\":[]}\n[00:02.00]有效歌词",
                null
        );

        assertEquals(1, lyrics.lines().size());
        assertEquals("有效歌词", lyrics.lines().getFirst().text());
    }
}
