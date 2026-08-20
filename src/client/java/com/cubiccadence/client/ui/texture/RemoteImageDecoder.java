package com.cubiccadence.client.ui.texture;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/** Decodes ordinary web images through ImageIO and converts ARGB pixels for Minecraft. */
final class RemoteImageDecoder {
    private static final int MAX_IMAGE_SIDE = 4096;
    private static final long MAX_IMAGE_PIXELS = 16_777_216L;

    private RemoteImageDecoder() {
    }

    static NativeImage decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new ImageDecodeException("image input is unavailable");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ImageDecodeException("unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                BufferedImage bufferedImage = reader.read(0);
                if (bufferedImage == null) {
                    throw new ImageDecodeException("decoder returned no pixels");
                }
                try {
                    return toNativeImage(bufferedImage);
                } finally {
                    bufferedImage.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ImageDecodeException imageDecodeException) {
                throw imageDecodeException;
            }
            throw new ImageDecodeException("ImageIO could not decode the image", exception);
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new ImageDecodeException("image dimensions are outside the supported range");
        }
    }

    private static NativeImage toNativeImage(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        int[] pixels = bufferedImage.getRGB(0, 0, width, height, null, 0, width);
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    nativeImage.setPixel(x, y, pixels[rowOffset + x]);
                }
            }
            return nativeImage;
        } catch (RuntimeException exception) {
            nativeImage.close();
            throw exception;
        }
    }

    static final class ImageDecodeException extends RuntimeException {
        private ImageDecodeException(String message) {
            super(message);
        }

        private ImageDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
