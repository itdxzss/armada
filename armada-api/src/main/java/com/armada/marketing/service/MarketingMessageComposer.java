package com.armada.marketing.service;

import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.util.HttpUrlValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将营销模板和可选图片文件转换为协议层可发送的消息 payload。
 *
 * <p>这里不关心目标群和账号,只负责把模板内容收敛成协议层可发送的消息类型。
 * 发送轮次 worker 会把该结果复制到每个目标的协议命令里。</p>
 */
@Component
public class MarketingMessageComposer {
    private static final ObjectMapper BUTTONS_JSON = new ObjectMapper();
    private static final TypeReference<List<MessageButton>> BUTTON_LIST = new TypeReference<>() {
    };

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
        MediaPayload thumbnail = mediaPayload(imageFile);
        if (mode == LinkMode.IMAGE_TEXT && thumbnail != null) {
            return new ComposedMessage("IMAGE", text, thumbnail.bytes(), thumbnail.mimetype());
        }
        if (mode == LinkMode.BUTTON) {
            return composeButtonCard(template, text, thumbnail);
        }
        if (mode == LinkMode.NORMAL
                && thumbnail != null
                && HttpUrlValidator.isHttpUrl(template.getPromotionLink())) {
            return new ComposedMessage(
                    "LINK_CARD",
                    linkCardText(template),
                    null,
                    null,
                    new LinkCardPayload(
                            template.getPromotionLink().trim(),
                            linkCardTitle(template),
                            trimToNull(template.getBodyText()),
                            thumbnail),
                    null);
        }
        if (mode == LinkMode.NORMAL && StringUtils.hasText(template.getPromotionLink())) {
            return new ComposedMessage("LINK", text, null, null);
        }
        return new ComposedMessage("TEXT", text, null, null);
    }

    /**
     * BUTTON 模式必须真实携带按钮。这里不做文本降级,避免用户以为发了按钮但协议层收到纯文本。
     */
    private static ComposedMessage composeButtonCard(MarketingTemplate template, String text, MediaPayload thumbnail) {
        List<ButtonPayload> buttons = buttonsFromJson(template.getButtons()).stream()
                .map(MarketingMessageComposer::buttonPayload)
                .toList();
        if (buttons.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮超链消息类型至少需要一个按钮");
        }
        return new ComposedMessage(
                "BUTTON_CARD",
                text,
                null,
                null,
                null,
                new ButtonCardPayload(linkCardTitle(template), null, buttons, thumbnail));
    }

    /**
     * 解析前端保存的按钮 JSON。空配置按无按钮处理,非法 JSON 直接暴露为表单配置错误。
     */
    private static List<MessageButton> buttonsFromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return BUTTONS_JSON.readValue(json, BUTTON_LIST);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮配置格式不正确");
        }
    }

    /**
     * 将业务侧按钮定义转换为协议侧按钮 payload,并在这里集中校验不同按钮类型的必填参数。
     */
    private static ButtonPayload buttonPayload(MessageButton button) {
        if (button == null || button.type() == null || !StringUtils.hasText(button.text())) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮配置不完整");
        }
        if (button.type() == ButtonType.LINK_JUMP) {
            if (!HttpUrlValidator.isHttpUrl(button.param())) {
                throw new BusinessException(ErrorCode.VALIDATION, "跳转链接格式不正确");
            }
            return new ButtonPayload("link", button.text().trim(), button.param().trim());
        }
        if (button.type() == ButtonType.COPY_CONTENT) {
            if (!StringUtils.hasText(button.param())) {
                throw new BusinessException(ErrorCode.VALIDATION, "复制按钮必须填写参数");
            }
            return new ButtonPayload("copy", button.text().trim(), button.param().trim());
        }
        return new ButtonPayload("quick", button.text().trim(), null);
    }

    /**
     * 图片文件只有真实携带二进制内容时才参与组包;空文件视为没有图片,由上层决定降级策略。
     */
    private static MediaPayload mediaPayload(MarketingTemplateFile imageFile) {
        if (imageFile == null || imageFile.getContent() == null || imageFile.getContent().length == 0) {
            return null;
        }
        return new MediaPayload(imageFile.getContent(), imageFile.getContentType());
    }

    /**
     * link preview 的正文优先使用模板标题,其次正文,最后用推广链接兜底,避免卡片文本为空。
     */
    private static String linkCardText(MarketingTemplate template) {
        return template.getPromotionLink().trim();
    }

    /**
     * link/button 卡片标题优先取模板标题,其次模板名称,再兜底推广链接。
     */
    private static String linkCardTitle(MarketingTemplate template) {
        String content = trimToNull(template.getContent());
        if (content != null) {
            return content;
        }
        String name = trimToNull(template.getTemplateName());
        if (name != null) {
            return name;
        }
        String link = trimToNull(template.getPromotionLink());
        return link == null ? "" : link;
    }

    /**
     * 统一把空白字符串归一成 null,让上层的优先级选择可以只判断 null。
     */
    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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

    /**
     * 向正文追加一个非空段落;已有内容时插入换行,保持 WhatsApp 文本的自然段结构。
     */
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
     * @param messageType   TEXT/LINK/IMAGE/LINK_CARD/BUTTON_CARD
     * @param text          文本正文;图片消息时作为 caption
     * @param imageBytes    图片二进制;非图片消息为空
     * @param imageMimetype 图片 MIME 类型;非图片消息为空
     */
    public record ComposedMessage(
            String messageType,
            String text,
            byte[] imageBytes,
            String imageMimetype,
            LinkCardPayload linkCard,
            ButtonCardPayload buttonCard
    ) {
        public ComposedMessage(String messageType, String text, byte[] imageBytes, String imageMimetype) {
            this(messageType, text, imageBytes, imageMimetype, null, null);
        }
    }

    public record MediaPayload(
            byte[] bytes,
            String mimetype
    ) {
    }

    public record LinkCardPayload(
            String url,
            String title,
            String description,
            MediaPayload thumbnail
    ) {
    }

    public record ButtonCardPayload(
            String title,
            String footer,
            List<ButtonPayload> buttons,
            MediaPayload thumbnail
    ) {
    }

    public record ButtonPayload(
            String type,
            String displayText,
            String value
    ) {
    }
}
