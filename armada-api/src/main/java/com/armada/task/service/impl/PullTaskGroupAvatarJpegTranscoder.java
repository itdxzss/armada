package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 把运营上传的群头像原图转成 WhatsApp 群头像要求的 640×640 方形 JPEG。
 *
 * <p>为什么由 armada 转而不是交给协议层：Web 那条路能直接吃非方形 PNG，是因为它底层库替它做了
 * 缩放；安卓是自研协议，没有这一层。转码留在协议侧就会变成两端各写一套、行为还不一致，
 * 所以统一在这里转好，协议两侧纯透传。</p>
 *
 * <p><b>非方形输入按居中裁切</b>，不补白边。理由：客户端把群头像按圆形显示，补白会让白边落进
 * 圆形可视区，同时把主体等比缩小；头像本就是给人一眼认出来的小图，主体几乎都在画面中央，
 * 裁掉两侧边缘的损失远小于主体缩水。</p>
 */
final class PullTaskGroupAvatarJpegTranscoder {

    /** WhatsApp 群头像边长。 */
    static final int EDGE_PIXELS = 640;

    /** 输出 MIME 类型，固定 JPEG。 */
    static final String MIMETYPE = "image/jpeg";

    /** ImageIO 的 JPEG 写出格式名。 */
    private static final String JPEG_FORMAT = "jpg";

    private PullTaskGroupAvatarJpegTranscoder() {
    }

    /**
     * 转码为 640×640 方形 JPEG。
     *
     * @param source 原图字节，PNG 或 JPEG
     * @return 640×640 JPEG 字节
     * @throws BusinessException 当原图解不开或写出失败时抛出
     */
    static byte[] toSquareJpeg(byte[] source) {
        BufferedImage image = read(source);
        BufferedImage square = centerCrop(image);
        // 画布用 TYPE_INT_RGB 并先铺白：JPEG 没有 alpha 通道，PNG 的透明区直接转过去会被当成
        // 黑色，运营看到的就是一张黑底头像。铺白后透明像素按 SrcOver 合成到白底上。
        BufferedImage canvas = new BufferedImage(EDGE_PIXELS, EDGE_PIXELS,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, EDGE_PIXELS, EDGE_PIXELS);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(square, 0, 0, EDGE_PIXELS, EDGE_PIXELS, null);
        } finally {
            graphics.dispose();
        }
        return write(canvas);
    }

    /** 取原图中央的最大正方形；本就是方形时原样返回。 */
    private static BufferedImage centerCrop(BufferedImage image) {
        int edge = Math.min(image.getWidth(), image.getHeight());
        if (edge == image.getWidth() && edge == image.getHeight()) {
            return image;
        }
        int x = (image.getWidth() - edge) / 2;
        int y = (image.getHeight() - edge) / 2;
        return image.getSubimage(x, y, edge, edge);
    }

    private static BufferedImage read(byte[] source) {
        if (source == null || source.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像内容为空，无法转码");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "群头像不是可识别的图片");
            }
            return image;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像读取失败，无法转码");
        }
    }

    private static byte[] write(BufferedImage canvas) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(canvas, JPEG_FORMAT, output)) {
                throw new BusinessException(ErrorCode.CONFLICT, "群头像 JPEG 编码器不可用");
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "群头像转码写出失败");
        }
        return output.toByteArray();
    }
}
