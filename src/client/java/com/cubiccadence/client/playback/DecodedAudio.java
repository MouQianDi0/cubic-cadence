package com.cubiccadence.client.playback;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/**
 * A fully decoded PCM buffer ready for OpenAL upload.
 *
 * @param pcm        interleaved little-endian PCM samples in a readable ByteBuffer
 * @param format     the javax.sound.sampled format describing the PCM data
 * @param durationMs total duration in milliseconds
 */
public record DecodedAudio(
        ByteBuffer pcm,
        AudioFormat format,
        long durationMs
) {
    public DecodedAudio {
        if (pcm == null || format == null) {
            throw new IllegalArgumentException("PCM data and format are required");
        }
        if (!pcm.hasRemaining()) {
            throw new IllegalArgumentException("PCM data must not be empty");
        }
        if (durationMs <= 0L) {
            throw new IllegalArgumentException("Audio duration must be positive");
        }
    }
}
