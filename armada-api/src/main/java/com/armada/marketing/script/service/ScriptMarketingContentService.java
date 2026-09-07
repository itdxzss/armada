package com.armada.marketing.script.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.service.MarketingMessageCommandFactory;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.util.HttpUrlValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 固定步骤的 JSON 与消息校验，复用现有内容组合与账号解析能力。 */
@Service
public class ScriptMarketingContentService {
    private final ObjectMapper json;
    private final MarketingTemplateConverter converter;
    private final MarketingMessageComposer composer;
    private final MarketingTemplateFileMapper files;
    private final AccountProtocolLookupService accounts;
    /** 注入现有模板组合、素材和账号能力。 */
    public ScriptMarketingContentService(ObjectMapper json, MarketingTemplateConverter converter,
            MarketingMessageComposer composer, MarketingTemplateFileMapper files,
            AccountProtocolLookupService accounts) {
        this.json = json; this.converter = converter; this.composer = composer;
        this.files = files; this.accounts = accounts;
    }
    /** 验证最低角色配置、固定账号归属和每条真实消息。 */
    public void validate(List<ScriptMarketingStepDTO> steps) {
        if (steps == null || steps.size() < 2 || steps.size() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少配置管理员和推手，最多 100 项");
        }
        Map<Long, String> roles = new HashMap<>();
        for (var step : steps) {
            if (step == null || step.accountId() == null
                    || step.role() == null || !List.of("ADMIN", "PROMOTER").contains(step.role())) {
                throw new BusinessException(ErrorCode.VALIDATION, "每项必须选择角色和账号");
            }
            String previous = roles.putIfAbsent(step.accountId(), step.role());
            if (previous != null && !previous.equals(step.role())) {
                throw new BusinessException(ErrorCode.VALIDATION, "同一账号的角色必须一致");
            }
            var account = accounts.findActiveProtocolRef(step.accountId()).orElseThrow(() ->
                    new BusinessException(ErrorCode.VALIDATION, "账号不存在或不可用：" + step.accountId()));
            var message = payload(step);
            // 保存和启动时提前拦截协议明确不支持的配置，避免整项到执行时才失败。
            if (account.backend() == ProtocolBackend.ANDROID && message.type() == MessageType.BUTTON_CARD
                    && (message.content().buttonCard().buttons().size() != 1
                    || !"link".equals(message.content().buttonCard().buttons().get(0).type()))) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "Android 账号只支持一个跳转链接按钮，请调整账号 " + step.accountId() + " 的消息配置");
            }
        }
        if (!roles.containsValue("ADMIN") || !roles.containsValue("PROMOTER")) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少选择一个管理员和一个不同账号的推手");
        }
    }
    /** 验证当前素材并生成现有协议支持的文字、图文、链接或按钮消息。 */
    public MessageSendCommand.MessagePayload payload(ScriptMarketingStepDTO step) {
        var message = step.message();
        if (message == null || message.content() == null || message.content().isBlank()
                || message.content().length() > 10000
                || (message.bodyText() != null && message.bodyText().length() > 10000)) {
            throw new BusinessException(ErrorCode.VALIDATION, "消息内容必填，标题和正文分别不超过 10000 字");
        }
        LinkMode mode = LinkMode.fromCode(message.linkMode());
        int buttons = message.buttons() == null ? 0 : message.buttons().size();
        if ((mode == LinkMode.BUTTON && (buttons < 1 || buttons > 3))
                || (mode != LinkMode.BUTTON && buttons > 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮消息须配置 1–3 个按钮，其他类型不携带按钮");
        }
        if (message.promotionLink() != null && !message.promotionLink().isBlank()
                && !HttpUrlValidator.isHttpUrl(message.promotionLink())) {
            throw new BusinessException(ErrorCode.VALIDATION, "推广链接必须是有效的 http(s) 链接");
        }
        MarketingTemplateFile image = null;
        if (message.imageFileId() != null) {
            image = files.selectById(message.imageFileId());
            if (image == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "图片素材不存在或无权访问");
            }
        }
        return MarketingMessageCommandFactory.payload(composer.compose(converter.toEntity(message), image));
    }
    /** 持久化业务人员填写的完整有序内容。 */
    public String encode(List<ScriptMarketingStepDTO> steps) {
        try { return json.writeValueAsString(steps); }
        catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "剧本配置无法保存");
        }
    }
    /** 恢复数据库内固定的发送顺序。 */
    public List<ScriptMarketingStepDTO> decode(String value) {
        try { return json.readValue(value, new TypeReference<List<ScriptMarketingStepDTO>>() { }); }
        catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "剧本配置无法读取");
        }
    }
}
