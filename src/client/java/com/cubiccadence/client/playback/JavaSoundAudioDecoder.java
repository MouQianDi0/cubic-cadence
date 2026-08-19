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
 * Decodes WAV and MP3 into signed 16-bit little-endian PCM. WAV uses the JDK
 * built-in reader; MP3 is decoded through its JavaSound SPI provider, invoked
 * directly so the decoder does not depend on ServiceLoader picking it up from a
 * Fabric nested jar.
 */
public class JavaSoundAudioDecoder implements AudioDecoder {
    private static final int TARGET_BITS = 16;

    private final javazoom.spi.mpeg.sampled.file.MpegAudioFileReader mp3Reader;
    private final javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider mp3Converter;

    public JavaSoundAudioDecoder() {
        this.mp3Reader = new javazoom.spi.mpeg.sampled.file.MpegAudioFileReader();
        this.mp3Converter = new javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider();
    }

    @Override
    public boolean supports(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase();
        return normalized.contains("wav")
                || normalized.contains("wave")
                || normalized.contains("mpeg")
                || normalized.contains("mp3");
    }

    @Override
    public DecodedAudio decode(byte[] encodedBytes) throws IOException {
        if (encodedBytes == null || encodedBytes.length == 0) {
            throw new IOException("Audio data is empty");
        }
        switch (detectFormat(encodedBytes)) {
            case WAV -> {
                return decodeWav(encodedBytes);
            }
            case MP3 -> {
                return decodeMp3(encodedBytes);
            }
            default -> throw new IOException("Unsupported or unrecognized audio data");
        }
    }

    private DecodedAudio decodeWav(byte[] encodedBytes) throws IOException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(encodedBytes))) {
            AudioFormat pcmFormat = targetPcmFormat(source.getFormat());
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, source)) {
                byte[] pcmBytes = pcmStream.readAllBytes();
                return toDecodedAudio(pcmBytes, pcmFormat);
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported or malformed WAV data", e);
        }
    }

    private DecodedAudio decodeMp3(byte[] encodedBytes) throws IOException {
        try {
            AudioInputStream source = mp3Reader.getAudioInputStream(new ByteArrayInputStream(encodedBytes));
            AudioFormat pcmFormat = targetPcmFormat(source.getFormat());
            try (AudioInputStream pcmStream = mp3Converter.getAudioInputStream(pcmFormat, source)) {
                byte[] pcmBytes = pcmStream.readAllBytes();
                return toDecodedAudio(pcmBytes, pcmFormat);
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported or malformed MP3 data", e);
        }
    }

    private static AudioFormat targetPcmFormat(AudioFormat source) {
        int channels = source.getChannels();
        float sampleRate = source.getSampleRate();
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Only mono and stereo audio are supported");
        }
        if (sampleRate <= 0.0f) {
            throw new IllegalArgumentException("Audio sample rate must be positive");
        }
        int frameSize = channels * (TARGET_BITS / 8);
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                TARGET_BITS,
                channels,
                frameSize,
                sampleRate,
                false
        );
    }

    private static DecodedAudio toDecodedAudio(byte[] pcmBytes, AudioFormat format) {
        ByteBuffer direct = ByteBuffer.allocateDirect(pcmBytes.length);
        direct.put(pcmBytes);
        direct.flip();
        return new DecodedAudio(direct, format, durationMs(pcmBytes, format));
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

    private enum ContainerFormat {
        WAV,
        MP3,
        UNKNOWN
    }

    private static ContainerFormat detectFormat(byte[] bytes) {
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return ContainerFormat.WAV;
        }
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return ContainerFormat.MP3;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF) {
            int second = bytes[1] & 0xFF;
            // MPEG audio frame sync.
            if ((second & 0xE0) == 0xE0) {
                return ContainerFormat.MP3;
            }
        }
        return ContainerFormat.UNKNOWN;
    }
}
