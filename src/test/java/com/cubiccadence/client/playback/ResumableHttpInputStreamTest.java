package com.cubiccadence.client.playback;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumableHttpInputStreamTest {
    @Test
    void networkFailureResumesAtTheExactDeliveredByte() throws Exception {
        byte[] audio = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        List<Long> offsets = new ArrayList<>();
        ResumableHttpInputStream stream = new ResumableHttpInputStream(offset -> {
            offsets.add(offset);
            if (offset == 0L) {
                return connection(new FailAfterInputStream(audio, 6), 200, null);
            }
            return rangedConnection(audio, offset);
        }, Runnable::run, 3);

        byte[] decodedInput = stream.readAllBytes();

        assertArrayEquals(audio, decodedInput);
        assertEquals(List.of(0L, 6L), offsets);
        assertEquals(1, stream.retriesUsed());
        assertEquals(audio.length, stream.deliveredBytes());
        stream.close();
    }

    @Test
    void starvationCanForceARangeReconnectWithoutRestartingTheStream() throws Exception {
        byte[] audio = "seamless-resume".getBytes(StandardCharsets.US_ASCII);
        List<Long> offsets = new ArrayList<>();
        ResumableHttpInputStream stream = new ResumableHttpInputStream(offset -> {
            offsets.add(offset);
            return offset == 0L
                    ? connection(new ByteArrayInputStream(audio), 200, null)
                    : rangedConnection(audio, offset);
        }, Runnable::run, 3);
        byte[] prefix = stream.readNBytes(5);

        assertTrue(stream.requestReconnect());
        byte[] suffix = stream.readAllBytes();

        byte[] combined = Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, combined, prefix.length, suffix.length);
        assertArrayEquals(audio, combined);
        assertEquals(List.of(0L, 5L), offsets);
        assertEquals(1, stream.retriesUsed());
        stream.close();
    }

    @Test
    void retriesOnlyThreeTimesWhenTheCdnIgnoresRange() throws Exception {
        byte[] audio = "range-required".getBytes(StandardCharsets.US_ASCII);
        List<Long> offsets = new ArrayList<>();
        ResumableHttpInputStream stream = new ResumableHttpInputStream(offset -> {
            offsets.add(offset);
            if (offset == 0L) {
                return connection(new FailAfterInputStream(audio, 4), 200, null);
            }
            return connection(new ByteArrayInputStream(audio), 200, null);
        }, Runnable::run, 3);

        IOException failure = assertThrows(IOException.class, stream::readAllBytes);

        assertTrue(failure.getMessage().contains("after 3 retries"));
        assertEquals(List.of(0L, 4L, 4L, 4L), offsets);
        assertEquals(3, stream.retriesUsed());
        assertFalse(stream.requestReconnect());
        stream.close();
    }

    @Test
    void rejectsMismatchedContentRange() throws Exception {
        byte[] audio = "range-position".getBytes(StandardCharsets.US_ASCII);
        ResumableHttpInputStream stream = new ResumableHttpInputStream(offset -> {
            if (offset == 0L) {
                return connection(new FailAfterInputStream(audio, 5), 200, null);
            }
            return connection(
                    new ByteArrayInputStream(Arrays.copyOfRange(audio, (int) offset, audio.length)),
                    206,
                    "bytes " + (offset + 1L) + "-" + (audio.length - 1L) + "/" + audio.length
            );
        }, Runnable::run, 3);

        IOException failure = assertThrows(IOException.class, stream::readAllBytes);

        assertTrue(failure.getMessage().contains("after 3 retries"));
        assertEquals(3, stream.retriesUsed());
        stream.close();
    }

    private static ResumableHttpInputStream.Connection rangedConnection(byte[] audio, long offset) {
        return connection(
                new ByteArrayInputStream(Arrays.copyOfRange(audio, (int) offset, audio.length)),
                206,
                "bytes " + offset + "-" + (audio.length - 1L) + "/" + audio.length
        );
    }

    private static ResumableHttpInputStream.Connection connection(
            InputStream body,
            int status,
            String contentRange
    ) {
        return new ResumableHttpInputStream.Connection(body, status, Optional.ofNullable(contentRange));
    }

    private static final class FailAfterInputStream extends InputStream {
        private final byte[] data;
        private final int failAfter;
        private int position;

        private FailAfterInputStream(byte[] data, int failAfter) {
            this.data = data;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (position >= failAfter) {
                throw new IOException("simulated network interruption");
            }
            int count = Math.min(length, failAfter - position);
            System.arraycopy(data, position, bytes, offset, count);
            position += count;
            return count;
        }
    }
}
