package com.nxr.platform.customer;
import static org.assertj.core.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
class JpegOrientationTest {
    @Test void littleEndianExifAndRotatedPreviewKeepPhonePhotoUpright() {
        byte[] jpeg = HexFormat.of().parseHex("ffd8ffe1002245786966000049492a0008000000010012010300010000000600000000000000ffd9");
        assertThat(JpegOrientation.read(jpeg)).isEqualTo(6);
        BufferedImage original = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        original.setRGB(0, 0, Color.RED.getRGB());
        BufferedImage upright = JpegOrientation.orient(original, 6);
        assertThat(upright.getWidth()).isEqualTo(3);
        assertThat(upright.getHeight()).isEqualTo(2);
        assertThat(upright.getRGB(2, 0)).isEqualTo(Color.RED.getRGB());
    }
    @Test void malformedExifOffsetsDoNotEscapeBoundsOrAllocateHugeImages() {
        assertThat(JpegOrientation.read(HexFormat.of().parseHex("ffd8ffe1002245786966000049492a00ffffffff010012010300010000000600000000000000ffd9"))).isEqualTo(1);
        assertThat(JpegOrientation.read(new byte[] { 0, 1, 2 })).isEqualTo(1);
    }
}
