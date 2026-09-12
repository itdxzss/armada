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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        steps = normalizeSteps(steps);
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
    /** 校验角色和逐项间隔；新任务账号在启动时分配，仍能复核旧任务的固定管理员。 */
    public void validateRoles(List<ScriptMarketingStepDTO> steps) {
        steps = normalizeSteps(steps);
        if (steps == null || steps.size() < 2 || steps.size() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION, "请配置 2–100 个发送项");
        }
        Map<String, ScriptMarketingStepDTO> roles = new HashMap<>();
        for (var step : steps) {
            if (step == null || step.role() == null || !List.of("ADMIN", "PROMOTER").contains(step.role())
                    || step.roleKey() == null || step.roleKey().isBlank() || step.roleKey().length() > 32
                    || !step.roleKey().equals(step.roleKey().trim())) {
                throw new BusinessException(ErrorCode.VALIDATION, "每项请选择管理员或推手，并填写角色名称");
            }
            var previous = roles.putIfAbsent(step.roleKey(), step);
            if (previous != null && (!previous.role().equals(step.role())
                    || !Objects.equals(previous.accountId(), step.accountId()))) {
                throw new BusinessException(ErrorCode.VALIDATION, "同一角色的类型和管理员账号必须一致");
            }
            if ("PROMOTER".equals(step.role()) && step.accountId() != null) {
                throw new BusinessException(ErrorCode.VALIDATION, "推手在启动时按群随机分配，无需手动绑定账号");
            }
            if (step.waitMinSeconds() == null || step.waitMaxSeconds() == null
                    || step.waitMinSeconds() < 0 || step.waitMaxSeconds() < step.waitMinSeconds()
                    || step.waitMaxSeconds() > 86400) {
                throw new BusinessException(ErrorCode.VALIDATION, "每项等待区间须为 0–86400 秒，最大值不小于最小值");
            }
            payload(step);
        }
        if (roles.values().stream().noneMatch(s -> "ADMIN".equals(s.role()))
                || roles.values().stream().noneMatch(s -> "PROMOTER".equals(s.role()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少配置一个管理员和一个推手角色");
        }
    }
    /** 随机候选必须支持该角色全部消息，Android 不支持的按钮不能随机抽中后再失败。 */
    public boolean supports(ProtocolBackend backend, ScriptMarketingStepDTO step) {
        if (backend != ProtocolBackend.ANDROID || step.message().linkMode() != LinkMode.BUTTON.code()) return true;
        var buttons = step.message().buttons();
        return buttons != null && buttons.size() == 1
                && buttons.get(0).type() == com.armada.marketing.model.ButtonType.LINK_JUMP;
    }
    /** 保存每个群的固定角色映射，不在恢复时重新抽样。 */
    public String encodeBindings(Map<String, Long> bindings) {
        try { return json.writeValueAsString(bindings); }
        catch (JsonProcessingException ex) { throw new BusinessException(ErrorCode.VALIDATION, "角色绑定无法保存"); }
    }
    /** 读取原绑定；草稿尚未分配时返回空集。 */
    public Map<String, Long> decodeBindings(String value) {
        if (value == null) return Map.of();
        try { return json.readValue(value, new TypeReference<Map<String, Long>>() { }); }
        catch (JsonProcessingException ex) { throw new BusinessException(ErrorCode.VALIDATION, "角色绑定无法读取"); }
    }
    /** 验证当前素材并生成现有协议支持的文字、图文、链接或按钮消息。 */
    public MessageSendCommand.MessagePayload payload(ScriptMarketingStepDTO step) {
        var message = step.message();
        boolean imageOnly = message != null && Integer.valueOf(LinkMode.IMAGE_TEXT.code()).equals(message.linkMode()) && message.imageFileId() != null;
        if (message == null || (!imageOnly && (message.content() == null || message.content().isBlank()))
                || (message.content() != null && message.content().length() > 10000)
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
        try { return json.writeValueAsString(normalizeSteps(steps)); }
        catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "剧本配置无法保存");
        }
    }
    /** 恢复数据库内固定的发送顺序。 */
    public List<ScriptMarketingStepDTO> decode(String value) {
        try { return normalizeSteps(json.readValue(value, new TypeReference<List<ScriptMarketingStepDTO>>() { })); }
        catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "剧本配置无法读取");
        }
    }
    /** 旧列表按位置归一化；含引用的列表必须携带完整身份，不能根据位置猜测关系。 */
    private List<ScriptMarketingStepDTO> normalizeSteps(List<ScriptMarketingStepDTO> steps) {
        if (steps == null || steps.size() < 2 || steps.size() > 100 || steps.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.VALIDATION, "请配置 2–100 个有效发送项");
        }
        boolean hasReply = steps.stream().anyMatch(step -> step.replyToStepId() != null && !step.replyToStepId().isBlank());
        var seen = new HashSet<String>();
        var result = new ArrayList<ScriptMarketingStepDTO>();
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            String id = step.stepId();
            if (id == null && !hasReply) id = "legacy_" + index;
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || seen.contains(id)) {
                throw new BusinessException(ErrorCode.VALIDATION, "第 " + (index + 1) + " 句标识缺失、重复或无效");
            }
            String target = step.replyToStepId();
            if (target != null && target.isEmpty()) target = null;
            if (target != null && !seen.contains(target)) {
                throw new BusinessException(ErrorCode.VALIDATION, "第 " + (index + 1) + " 句回复目标必须是前面的一句对话");
            }
            seen.add(id);
            result.add(new ScriptMarketingStepDTO(step.role(), step.accountId(), step.message(), step.roleKey(),
                    step.waitMinSeconds(), step.waitMaxSeconds(), id, target));
        }
        return List.copyOf(result);
    }
}
