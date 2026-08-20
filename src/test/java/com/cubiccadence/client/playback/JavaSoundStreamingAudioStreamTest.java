package com.cubiccadence.client.playback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSoundStreamingAudioStreamTest {
    private static final AudioFormat FORMAT = new AudioFormat(44_100.0f, 16, 2, true, false);

    private final ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService cleanupExecutor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutors() {
        producerExecutor.shutdownNow();
        cleanupExecutor.shutdownNow();
    }

    @Test
    void temporaryStarvationReturnsSilenceInsteadOfEof() throws Exception {
        JavaSoundStreamingAudioStream stream = stream(new ByteArrayInputStream(new byte[0]), 0L);

        ByteBuffer buffer = stream.read(4_096);

        assertNotNull(buffer);
        assertEquals(4_096, buffer.remaining());
        assertTrue(stream.isStarved());
        assertEquals(JavaSoundStreamingAudioStream.StreamState.RUNNING, stream.streamState());
        while (buffer.hasRemaining()) {
            assertEquals(0, buffer.get());
        }
        stream.close();
    }

    @Test
    void realDataClearsTemporaryStarvation() throws Exception {
        byte[] pcm = new byte[JavaSoundStreamingAudioStream.PCM_CHUNK_BYTES];
        Arrays.fill(pcm, (byte) 7);
        JavaSoundStreamingAudioStream stream = stream(new ByteArrayInputStream(pcm), 0L);
        assertNotNull(stream.read(1_024));
        assertTrue(stream.isStarved());

        stream.start(producerExecutor);
        waitUntil(() -> stream.bufferedChunkCount() > 0
                || stream.streamState() != JavaSoundStreamingAudioStream.StreamState.RUNNING);
        ByteBuffer recovered = stream.read(1_024);

        assertNotNull(recovered);
        assertFalse(stream.isStarved());
        assertEquals(7, recovered.get());
        stream.close();
    }

    @Test
    void trueEofRemainsDistinctFromTemporaryStarvation() throws Exception {
        byte[] oneSecond = new byte[(int) (FORMAT.getFrameRate() * FORMAT.getFrameSize())];
        JavaSoundStreamingAudioStream stream = stream(new ByteArrayInputStream(oneSecond), 1_000L);
        stream.start(producerExecutor);
        stream.ready().get(2, TimeUnit.SECONDS);

        ByteBuffer audio = stream.read(oneSecond.length + 1_024);

        assertNotNull(audio);
        assertEquals(oneSecond.length, audio.remaining());
        assertNull(stream.read(1_024));
        assertEquals(JavaSoundStreamingAudioStream.StreamState.EOF, stream.streamState());
        stream.close();
    }

    @Test
    void decoderFailureIsStickyAfterEndMarkerWasConsumed() throws Exception {
        JavaSoundStreamingAudioStream stream = stream(new FailingInputStream(), 0L);
        stream.start(producerExecutor);
        assertThrows(Exception.class, () -> stream.ready().get(2, TimeUnit.SECONDS));

        assertThrows(IOException.class, () -> stream.read(1_024));
        assertThrows(IOException.class, () -> stream.read(1_024));
        assertEquals(JavaSoundStreamingAudioStream.StreamState.FAILED, stream.streamState());
        stream.close();
    }

    @Test
    void shortDecodedAudioIsReportedAsPrematureFailure() throws Exception {
        byte[] oneSecond = new byte[(int) (FORMAT.getFrameRate() * FORMAT.getFrameSize())];
        JavaSoundStreamingAudioStream stream = stream(new ByteArrayInputStream(oneSecond), 60_000L);
        stream.start(producerExecutor);

        assertThrows(Exception.class, () -> stream.ready().get(2, TimeUnit.SECONDS));
        ByteBuffer remainingAudio = stream.read(oneSecond.length);
        assertNotNull(remainingAudio);
        assertEquals(oneSecond.length, remainingAudio.remaining());
        IOException failure = assertThrows(IOException.class, () -> stream.read(1_024));
        assertTrue(failure.getMessage().contains("ended prematurely"));
        assertEquals(JavaSoundStreamingAudioStream.StreamState.FAILED, stream.streamState());
        stream.close();
    }

    @Test
    void closeNeverWaitsForBlockingNetworkResourceClose() throws Exception {
        BlockingCloseInputStream input = new BlockingCloseInputStream();
        JavaSoundStreamingAudioStream stream = stream(input, 0L);

        assertTimeoutPreemptively(Duration.ofMillis(200), stream::close);
        assertTrue(input.closeStarted.await(2, TimeUnit.SECONDS));
        assertEquals(JavaSoundStreamingAudioStream.StreamState.CANCELLED, stream.streamState());

        input.allowClose.countDown();
        assertTrue(input.closeFinished.await(2, TimeUnit.SECONDS));
    }

    @Test
    void pcmQueueHasAOneMebibyteHardLimit() throws Exception {
        byte[] pcm = new byte[JavaSoundStreamingAudioStream.PCM_CHUNK_BYTES * 20];
        JavaSoundStreamingAudioStream stream = stream(new ByteArrayInputStream(pcm), 0L);
        stream.start(producerExecutor);
        waitUntil(() -> stream.bufferedChunkCount() == JavaSoundStreamingAudioStream.MAX_BUFFERED_CHUNKS);

        assertEquals(1_048_576L, JavaSoundStreamingAudioStream.MAX_BUFFERED_PCM_BYTES);
        assertEquals(JavaSoundStreamingAudioStream.MAX_BUFFERED_CHUNKS, stream.bufferedChunkCount());
        stream.close();
    }

    private JavaSoundStreamingAudioStream stream(InputStream input, long expectedDurationMs) {
        AudioInputStream audio = new AudioInputStream(input, FORMAT, -1L);
        return new JavaSoundStreamingAudioStream(audio, audio, expectedDurationMs, cleanupExecutor);
    }

    private static void waitUntil(CheckedBooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (!condition.getAsBoolean()) {
                Thread.onSpinWait();
            }
        });
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("simulated network failure");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            throw new IOException("simulated network failure");
        }
    }

    private static final class BlockingCloseInputStream extends InputStream {
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeStarted.countDown();
            try {
                allowClose.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            } finally {
                closeFinished.countDown();
            }
        }
    }
}
