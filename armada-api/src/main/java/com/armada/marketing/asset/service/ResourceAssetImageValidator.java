package com.armada.marketing.asset.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;

/** 新素材上传与模板绑定共用的真实 JPEG 校验。 */
public final class ResourceAssetImageValidator {

    /** 素材库允许的单张图片最大字节数。 */
    public static final int MAX_IMAGE_BYTES = 500 * 1024;
    /** 素材库允许的 MIME 类型。 */
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    /** 所有 JPEG 校验失败使用的稳定业务消息。 */
    private static final String IMAGE_VALIDATION_MESSAGE = "图片必须是可解码的 JPEG 且不超过 500KB";

    private ResourceAssetImageValidator() {
    }

    /**
     * 在读取 multipart 全量字节前按声明大小执行快速拒绝，实际字节仍由后续解码校验复核。
     *
     * @param declaredSize multipart 声明的图片字节数
     * @throws BusinessException 当声明大小超过素材上限时抛出
     */
    public static void validateDeclaredSize(long declaredSize) {
        if (declaredSize > MAX_IMAGE_BYTES) {
            throw invalidImage();
        }
    }

    /**
     * 校验新上传素材的扩展名、MIME、字节大小、magic 和真实解码结果。
     *
     * @param filename 原始文件名
     * @param contentType 浏览器声明的 MIME 类型
     * @param content 实际图片字节
     * @return 解码得到的图片尺寸
     * @throws BusinessException 当任一 JPEG 约束不满足时抛出
     */
    public static Dimensions validateUpload(String filename, String contentType, byte[] content) {
        String normalized = filename == null ? "" : filename.trim().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".jpg") && !normalized.endsWith(".jpeg")) {
            throw invalidImage();
        }
        return validateBindable(contentType, content);
    }

    /**
     * 校验模板绑定素材已保存的 MIME、大小与真实图片字节。
     *
     * @param contentType 已保存 MIME 类型
     * @param content 已保存图片字节
     * @return 解码得到的图片尺寸
     * @throws BusinessException 当素材不符合绑定规则时抛出
     */
    public static Dimensions validateBindable(String contentType, byte[] content) {
        if (!JPEG_CONTENT_TYPE.equalsIgnoreCase(contentType)
                || content == null
                || content.length == 0
                || content.length > MAX_IMAGE_BYTES
                || !hasJpegMagic(content)) {
            throw invalidImage();
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw invalidImage();
            }
            return new Dimensions(image.getWidth(), image.getHeight());
        } catch (IOException exception) {
            throw invalidImage();
        }
    }

    private static boolean hasJpegMagic(byte[] content) {
        return content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff;
    }

    private static BusinessException invalidImage() {
        return new BusinessException(ErrorCode.VALIDATION, IMAGE_VALIDATION_MESSAGE);
    }

    /**
     * 解码后的图片像素尺寸。
     *
     * @param width 图片宽度像素
     * @param height 图片高度像素
     */
    public record Dimensions(int width, int height) {
    }
}
