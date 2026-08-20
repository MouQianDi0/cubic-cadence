package com.cubiccadence.client.ui.texture;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteImageDecoderTest {
    @Test
    void decodesPngThroughImageIoAndPreservesArgbPixels() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xFFFF0000);
        source.setRGB(1, 0, 0x8000FF00);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);

        try (NativeImage decoded = RemoteImageDecoder.decode(bytes.toByteArray())) {
            assertEquals(2, decoded.getWidth());
            assertEquals(1, decoded.getHeight());
            assertEquals(0xFFFF0000, decoded.getPixel(0, 0));
            assertEquals(0x8000FF00, decoded.getPixel(1, 0));
        }
    }

    @Test
    void rejectsUnknownFormats() {
        assertThrows(
                RemoteImageDecoder.ImageDecodeException.class,
                () -> RemoteImageDecoder.decode(new byte[]{1, 2, 3, 4})
        );
    }
}
