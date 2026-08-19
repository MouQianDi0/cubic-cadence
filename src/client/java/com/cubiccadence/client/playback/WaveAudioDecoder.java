package com.cubiccadence.client.playback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Decodes WAV audio into signed 16-bit little-endian PCM using the JDK's
 * javax.sound.sampled SPI. This keeps stage 2 dependency-free while still
 * exercising the full file -> PCM -> OpenAL pipeline.
 */
public class WaveAudioDecoder implements AudioDecoder {
    @Override
    public boolean supports(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase();
        return normalized.contains("wav") || normalized.contains("wave");
    }

    @Override
    public DecodedAudio decode(byte[] encodedBytes) throws IOException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(encodedBytes))) {
            AudioFormat pcmFormat = toTargetPcm(source.getFormat());
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, source)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                pcmStream.transferTo(buffer);
                byte[] pcmBytes = buffer.toByteArray();
                ByteBuffer direct = ByteBuffer.allocateDirect(pcmBytes.length);
                direct.put(pcmBytes);
                direct.flip();
                return new DecodedAudio(direct, pcmFormat, durationMs(pcmBytes, pcmFormat));
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported or malformed WAV data", e);
        }
    }

    private static AudioFormat toTargetPcm(AudioFormat source) {
        int channels = source.getChannels();
        float sampleRate = source.getSampleRate();
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Only mono and stereo WAV audio are supported");
        }
        if (sampleRate <= 0.0f) {
            throw new IllegalArgumentException("WAV sample rate must be positive");
        }
        int frameSize = channels * 2;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                frameSize,
                sampleRate,
                false
        );
    }

    private static long durationMs(byte[] pcmBytes, AudioFormat format) {
        int frameSize = format.getFrameSize();
        float sampleRate = format.getSampleRate();
        if (frameSize <= 0 || sampleRate <= 0) {
            return 0L;
        }
        long frames = pcmBytes.length / frameSize;
        return Math.round(frames * 1000.0 / sampleRate);
    }
}
