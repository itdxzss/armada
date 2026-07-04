package com.armada.marketing.service;

import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将营销模板和可选图片文件转换为协议层可发送的消息 payload。
 *
 * <p>这里不关心目标群和账号,只负责把模板内容收敛成 TEXT/LINK/IMAGE 三类消息。
 * 发送轮次 worker 会把该结果复制到每个目标的协议命令里。</p>
 */
@Component
public class MarketingMessageComposer {

    /**
     * 组合模板消息。
     *
     * <p>图文模式只有在图片文件真实存在且有内容时才发送 IMAGE;否则降级为纯文本,
     * 避免协议层收到空图片 payload。</p>
     */
    public ComposedMessage compose(MarketingTemplate template, MarketingTemplateFile imageFile) {
        if (template == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板不能为空");
        }
        LinkMode mode = LinkMode.fromCode(template.getLinkMode());
        String text = composeText(template);
        if (mode == LinkMode.IMAGE_TEXT
                && imageFile != null
                && imageFile.getContent() != null
                && imageFile.getContent().length > 0) {
            return new ComposedMessage("IMAGE", text, imageFile.getContent(), imageFile.getContentType());
        }
        if (mode == LinkMode.NORMAL && StringUtils.hasText(template.getPromotionLink())) {
            return new ComposedMessage("LINK", text, null, null);
        }
        return new ComposedMessage("TEXT", text, null, null);
    }

    /** 按标题/正文/推广链接顺序拼接,并统一做空内容与 WhatsApp 文本长度校验。 */
    private static String composeText(MarketingTemplate template) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, template.getContent());
        appendLine(sb, template.getBodyText());
        appendLine(sb, template.getPromotionLink());
        String text = sb.toString().trim();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板发送内容为空");
        }
        if (text.length() > 4096) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板发送内容超过4096字符");
        }
        return text;
    }

    private static void appendLine(StringBuilder sb, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(value.trim());
    }

    /**
     * 已组合好的协议消息内容。
     *
     * @param messageType   TEXT/LINK/IMAGE
     * @param text          文本正文;图片消息时作为 caption
     * @param imageBytes    图片二进制;非图片消息为空
     * @param imageMimetype 图片 MIME 类型;非图片消息为空
     */
    public record ComposedMessage(
            String messageType,
            String text,
            byte[] imageBytes,
            String imageMimetype
    ) {
    }
}
