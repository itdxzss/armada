package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 群头像转码：WhatsApp 群头像要 640×640 方形 JPEG，转码由 armada 侧做完，协议两侧纯透传。
 *
 * <p>为什么必须钉住：Web 那条路即使发个非方形 PNG 也能成，因为它底层库替它缩放了；安卓是自研
 * 协议没有这一层，同一张图在两条路上结果不一样。转码回归时这里先红，比等运营发现安卓群头像
 * 是黑的要早得多。</p>
 */
class PullTaskGroupAvatarJpegTranscoderTest {

    @Test
    @DisplayName("输出是 JPEG 且尺寸恰好 640×640")
    void outputIsSixFortySquareJpeg() throws Exception {
        byte[] jpeg = PullTaskGroupAvatarJpegTranscoder.toSquareJpeg(png(200, 200, Color.RED));

        assertThat(isJpeg(jpeg)).as("输出必须是 JPEG，实际前三字节=%s", header(jpeg)).isTrue();
        BufferedImage decoded = decode(jpeg);
        assertThat(decoded.getWidth()).isEqualTo(640);
        assertThat(decoded.getHeight()).isEqualTo(640);
    }

    @Test
    @DisplayName("原图比 640 小也放大到 640×640，不留原尺寸")
    void smallerSourceIsScaledUp() throws Exception {
        byte[] jpeg = PullTaskGroupAvatarJpegTranscoder.toSquareJpeg(png(64, 64, Color.BLUE));

        BufferedImage decoded = decode(jpeg);
        assertThat(decoded.getWidth()).isEqualTo(640);
        assertThat(decoded.getHeight()).isEqualTo(640);
    }

    /**
     * 非方形按**居中裁切**，不补白、不拉伸。
     *
     * <p>用一张 1200×600 的图：正中间 600×600 是绿色，左右各 300 宽是红色。居中裁切的结果
     * 应该整幅都是绿色——出现红色说明没裁而是拉伸了，出现白色说明补了白边。</p>
     */
    @Test
    @DisplayName("非方形输入居中裁切：裁掉两侧，不补白也不拉伸")
    void nonSquareSourceIsCenterCropped() throws Exception {
        BufferedImage wide = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = wide.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 1200, 600);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(300, 0, 600, 600);
        graphics.dispose();

        BufferedImage decoded = decode(
                PullTaskGroupAvatarJpegTranscoder.toSquareJpeg(encodePng(wide)));

        assertColorAt(decoded, 20, 320, Color.GREEN);
        assertColorAt(decoded, 320, 320, Color.GREEN);
        assertColorAt(decoded, 619, 320, Color.GREEN);
    }

    /**
     * PNG 透明底必须先合成白底。
     *
     * <p>JPEG 没有 alpha 通道，透明像素直接转过去会被当成黑色，运营看到的是一张黑底头像。</p>
     */
    @Test
    @DisplayName("PNG 透明区合成白底，不得变黑")
    void transparentPngBecomesWhiteNotBlack() throws Exception {
        BufferedImage transparent = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);

        BufferedImage decoded = decode(
                PullTaskGroupAvatarJpegTranscoder.toSquareJpeg(encodePng(transparent)));

        assertColorAt(decoded, 320, 320, Color.WHITE);
    }

    @Test
    @DisplayName("原图不是可识别图片时抛业务异常，不发一张坏头像")
    void unreadableSourceIsRejected() {
        byte[] notAnImage = "definitely-not-an-image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> PullTaskGroupAvatarJpegTranscoder.toSquareJpeg(notAnImage))
                .isInstanceOf(BusinessException.class);
    }

    /** JPEG 有损，逐通道给 16 的容差；这里只区分红/绿/白/黑，容差远小于色差。 */
    private static void assertColorAt(BufferedImage image, int x, int y, Color expected) {
        Color actual = new Color(image.getRGB(x, y));
        assertThat(channelDistance(actual, expected))
                .as("(%d,%d) 期望 %s，实际 %s", x, y, expected, actual)
                .isLessThanOrEqualTo(16);
    }

    private static int channelDistance(Color left, Color right) {
        return Math.max(Math.abs(left.getRed() - right.getRed()),
                Math.max(Math.abs(left.getGreen() - right.getGreen()),
                        Math.abs(left.getBlue() - right.getBlue())));
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0xff
                && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff;
    }

    private static String header(byte[] bytes) {
        return bytes.length < 3 ? "<too short>"
                : String.format("%02x %02x %02x", bytes[0], bytes[1], bytes[2]);
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("转码输出必须能被解码回图片").isNotNull();
        return image;
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return encodePng(image);
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
