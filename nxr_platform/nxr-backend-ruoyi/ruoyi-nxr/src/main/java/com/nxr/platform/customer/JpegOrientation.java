package com.nxr.platform.customer;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads only the EXIF orientation; preview images never copy location metadata. */
final class JpegOrientation {
    private JpegOrientation() { }

    static int read(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216) return 1;
        try {
            int position = 2;
            while (position + 4 <= bytes.length && (bytes[position] & 255) == 255) {
                int marker = bytes[position + 1] & 255;
                if (marker == 218 || marker == 217) break;
                int length = ((bytes[position + 2] & 255) << 8) | (bytes[position + 3] & 255);
                if (length < 2 || position + 2L + length > bytes.length) return 1;
                if (marker == 225 && length >= 16 && bytes[position + 4] == 'E' && bytes[position + 5] == 'x'
                    && bytes[position + 6] == 'i' && bytes[position + 7] == 'f' && bytes[position + 8] == 0 && bytes[position + 9] == 0) {
                    ByteBuffer tiff = ByteBuffer.wrap(bytes, position + 10, length - 8).slice();
                    short order = tiff.getShort(0);
                    if (order != 0x4949 && order != 0x4d4d) return 1;
                    tiff.order(order == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    if (Short.toUnsignedInt(tiff.getShort(2)) != 42) return 1;
                    long offset = Integer.toUnsignedLong(tiff.getInt(4));
                    if (offset + 2 > tiff.limit()) return 1;
                    int entries = Short.toUnsignedInt(tiff.getShort((int) offset));
                    for (int index = 0; index < entries; index++) {
                        long entry = offset + 2L + 12L * index;
                        if (entry + 12 > tiff.limit()) return 1;
                        int cursor = (int) entry;
                        if (Short.toUnsignedInt(tiff.getShort(cursor)) == 0x112 && tiff.getShort(cursor + 2) == 3 && tiff.getInt(cursor + 4) == 1) {
                            int orientation = Short.toUnsignedInt(tiff.getShort(cursor + 8));
                            return orientation >= 1 && orientation <= 8 ? orientation : 1;
                        }
                    }
                }
                position += length + 2;
            }
        } catch (IndexOutOfBoundsException ignored) { }
        return 1;
    }

    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) return image;
        int width = image.getWidth(), height = image.getHeight();
        BufferedImage result = new BufferedImage(orientation >= 5 ? height : width, orientation >= 5 ? width : height, BufferedImage.TYPE_INT_RGB);
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
        var graphics = result.createGraphics();
        try { graphics.drawImage(image, transform, null); } finally { graphics.dispose(); }
        return result;
    }
}
