package com.armada.marketing.service;

import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MarketingMessageComposer {

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

    public record ComposedMessage(
            String messageType,
            String text,
            byte[] imageBytes,
            String imageMimetype
    ) {
    }
}
