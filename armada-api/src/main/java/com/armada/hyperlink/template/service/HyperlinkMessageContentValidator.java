package com.armada.hyperlink.template.service;

import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import com.armada.hyperlink.template.model.enums.HyperlinkMessageType;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.util.HttpUrlValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/** 模板与未来任务共用的超链消息内容校验和归一化组件。 */
@Component
public class HyperlinkMessageContentValidator {

    /** 一期消息结构版本。 */
    private static final int SCHEMA_VERSION = 1;
    /** 标题最大字符数。 */
    private static final int TITLE_MAX_LENGTH = 1024;
    /** 单图文正文最大字符数。 */
    private static final int SINGLE_CONTENT_MAX_LENGTH = 2000;
    /** 普通按钮协议 Body 最大字符数。 */
    private static final int NORMAL_BUTTON_BODY_MAX_LENGTH = 1024;
    /** 卡片按钮协议 Footer 最大字符数。 */
    private static final int CARD_BUTTON_FOOTER_MAX_LENGTH = 60;
    /** 链接描述最大字符数。 */
    private static final int LINK_DESCRIPTION_MAX_LENGTH = 512;
    /** 绝对 URL 最大字符数。 */
    private static final int URL_MAX_LENGTH = 2048;
    /** 卡片正文最大字符数。 */
    private static final int CARD_TEXT_MAX_LENGTH = 500;
    /** 按钮展示文字最大字符数。 */
    private static final int BUTTON_TEXT_MAX_LENGTH = 20;
    /** 一期唯一按钮排序值。 */
    private static final int BUTTON_SORT = 1;
    /** 一期超链模板图片最大字节数。 */
    private static final int MAX_IMAGE_BYTES = 500 * 1024;
    /** 一期允许绑定的图片 MIME。 */
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    /** 图片格式与大小校验的稳定提示。 */
    private static final String IMAGE_VALIDATION_MESSAGE = "图片必须是可解码的 JPEG 且不超过 500KB";

    /** 复用营销图片服务按当前租户重新读取图片内容。 */
    private final MarketingTemplateFileService fileService;

    public HyperlinkMessageContentValidator(MarketingTemplateFileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 校验消息字段矩阵，并清空当前消息类型不生效的字段。
     *
     * @param input 调用方提交的完整消息内容
     * @return 可以安全持久化的规范化消息内容
     * @throws BusinessException 字段、按钮、URL 或图片不符合一期合同时抛出
     */
    public HyperlinkMessageContent validateAndNormalize(HyperlinkMessageContent input) {
        return validateAndNormalize(input, false);
    }

    /**
     * 校验消息字段矩阵，并允许已有任务在消息类型锁定为历史双图文时修改内容。
     *
     * <p>该兼容开关只供任务编辑路径使用；新建和模板继续调用单参数入口拒绝类型 2。</p>
     */
    public HyperlinkMessageContent validateAndNormalize(
            HyperlinkMessageContent input, boolean historicalDoubleImageAllowed) {
        if (input == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "消息内容不能为空");
        }
        if (!Integer.valueOf(SCHEMA_VERSION).equals(input.schemaVersion())) {
            throw new BusinessException(ErrorCode.VALIDATION, "schemaVersion 一期只支持 1");
        }
        HyperlinkMessageType type = HyperlinkMessageType.fromCode(input.messageType());
        if (type == HyperlinkMessageType.DOUBLE_IMAGE_TEXT && !historicalDoubleImageAllowed) {
            throw new BusinessException(ErrorCode.VALIDATION, "一期暂不支持双图文");
        }

        String title = required(input.title(), "标题不能为空", TITLE_MAX_LENGTH, "标题最长 1024 字符");
        return switch (type) {
            case SINGLE_LINK_PREVIEW -> normalizeSingle(input, title);
            case NORMAL_BUTTON -> normalizeButton(input, title, false);
            case CARD_BUTTON -> normalizeButton(input, title, true);
            case DOUBLE_IMAGE_TEXT -> normalizeHistoricalDoubleImage(input, title);
        };
    }

    private HyperlinkMessageContent normalizeHistoricalDoubleImage(
            HyperlinkMessageContent input, String title) {
        String content = required(input.content(), "双图文正文不能为空",
                SINGLE_CONTENT_MAX_LENGTH, "双图文正文最长 2000 字符");
        String description = required(input.linkDescription(), "链接描述不能为空",
                LINK_DESCRIPTION_MAX_LENGTH, "链接描述最长 512 字符");
        String promotionLink = required(input.promotionLink(), "推广链接不能为空",
                URL_MAX_LENGTH, "推广链接最长 2048 字符");
        requireHttpUrl(promotionLink, "推广链接必须是合法的绝对 http/https URL");
        if (input.linkPreviewAssetId() != null) {
            validateAsset(input.linkPreviewAssetId());
        }
        if (input.bodyMainAssetId() != null) {
            validateAsset(input.bodyMainAssetId());
        }
        return new HyperlinkMessageContent(
                SCHEMA_VERSION, HyperlinkMessageType.DOUBLE_IMAGE_TEXT.code(), title, content,
                description, promotionLink, List.of(), null,
                input.linkPreviewAssetId(), input.bodyMainAssetId());
    }

    private HyperlinkMessageContent normalizeSingle(HyperlinkMessageContent input, String title) {
        String content = required(
                input.content(), "单图文正文不能为空", SINGLE_CONTENT_MAX_LENGTH, "单图文正文最长 2000 字符");
        String description = required(input.linkDescription(), "链接描述不能为空",
                LINK_DESCRIPTION_MAX_LENGTH, "链接描述最长 512 字符");
        String promotionLink = optional(
                input.promotionLink(), URL_MAX_LENGTH, "推广链接最长 2048 字符");
        if (promotionLink != null) {
            requireHttpUrl(promotionLink, "推广链接必须是合法的绝对 http/https URL");
        }
        if (input.linkPreviewAssetId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "单图文必须选择链接预览图");
        }
        validateAsset(input.linkPreviewAssetId());
        return new HyperlinkMessageContent(
                SCHEMA_VERSION, HyperlinkMessageType.SINGLE_LINK_PREVIEW.code(), title, content,
                description, promotionLink, List.of(), null, input.linkPreviewAssetId(), null);
    }

    private HyperlinkMessageContent normalizeButton(
            HyperlinkMessageContent input,
            String title,
            boolean card) {
        String content = card
                ? optional(input.content(), CARD_BUTTON_FOOTER_MAX_LENGTH,
                        "卡片按钮副标题小字最长 60 字符")
                : optional(input.content(), NORMAL_BUTTON_BODY_MAX_LENGTH,
                        "普通按钮底部小字最长 1024 字符");
        List<HyperlinkButton> buttons = normalizeButtons(input.buttons());
        String cardText = card
                ? required(input.cardText(), "卡片按钮必须填写卡片正文",
                        CARD_TEXT_MAX_LENGTH, "卡片正文最长 500 字符")
                : null;
        if (input.bodyMainAssetId() != null) {
            validateAsset(input.bodyMainAssetId());
        }
        int type = card ? HyperlinkMessageType.CARD_BUTTON.code() : HyperlinkMessageType.NORMAL_BUTTON.code();
        return new HyperlinkMessageContent(
                SCHEMA_VERSION, type, title, content, null, null, buttons, cardText,
                null, input.bodyMainAssetId());
    }

    private List<HyperlinkButton> normalizeButtons(List<HyperlinkButton> buttons) {
        if (buttons == null || buttons.size() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮消息必须恰好配置 1 个 URL 按钮");
        }
        HyperlinkButton button = buttons.get(0);
        if (button == null || button.type() != HyperlinkButtonType.CTA_URL) {
            throw new BusinessException(ErrorCode.VALIDATION, "一期按钮类型只支持 CTA_URL");
        }
        String displayText = required(
                button.displayText(), "按钮文字不能为空", BUTTON_TEXT_MAX_LENGTH, "按钮文字最长 20 字符");
        String targetValue = required(
                button.targetValue(), "按钮目标 URL 不能为空", URL_MAX_LENGTH, "按钮目标 URL 最长 2048 字符");
        requireHttpUrl(targetValue, "按钮目标必须是合法的绝对 http/https URL");
        if (button.useShortLink() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "useShortLink 必须显式传 boolean");
        }
        if (!Integer.valueOf(BUTTON_SORT).equals(button.sort())) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮 sort 一期必须为 1");
        }
        return List.of(new HyperlinkButton(
                HyperlinkButtonType.CTA_URL, displayText, targetValue, button.useShortLink(), BUTTON_SORT));
    }

    private void validateAsset(Long assetId) {
        MarketingTemplateFileContent file;
        try {
            file = fileService.lockContentForBinding(assetId);
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.NOT_FOUND.code()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "图片不存在或已删除");
            }
            throw exception;
        }
        byte[] content = file.content();
        if (!JPEG_CONTENT_TYPE.equalsIgnoreCase(file.contentType())
                || content == null
                || content.length > MAX_IMAGE_BYTES
                || !isDecodableJpeg(content)) {
            throw new BusinessException(ErrorCode.VALIDATION, IMAGE_VALIDATION_MESSAGE);
        }
    }

    private static boolean isDecodableJpeg(byte[] content) {
        if (content.length < 3
                || (content[0] & 0xff) != 0xff
                || (content[1] & 0xff) != 0xd8
                || (content[2] & 0xff) != 0xff) {
            return false;
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return ImageIO.read(input) != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private static String required(String value, String emptyMessage, int maxLength, String lengthMessage) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION, emptyMessage);
        }
        requireMaxLength(normalized, maxLength, lengthMessage);
        return normalized;
    }

    private static String optional(String value, int maxLength, String lengthMessage) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            requireMaxLength(normalized, maxLength, lengthMessage);
        }
        return normalized;
    }

    private static void requireMaxLength(String value, int maxLength, String message) {
        if (value.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
    }

    private static void requireHttpUrl(String value, String message) {
        if (!HttpUrlValidator.isHttpUrl(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
