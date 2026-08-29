package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskButtonDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskMessageContentDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import com.armada.hyperlink.template.service.HyperlinkMessageContentValidator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 将任务 Save wire 规范化为模板与任务共享的内容模型和冻结配置。 */
@Service
public class HyperlinkTaskConfigurationFactory {
    /** 竞品隐藏但冻结为服务端常量的单账号逻辑发送并发。 */
    public static final int ACCOUNT_SEND_CONCURRENCY = 20;
    private static final long MINUTE_MILLIS = 60_000L;
    private final HyperlinkMessageContentValidator contentValidator;
    private final ObjectMapper objectMapper;
    private final HyperlinkAccountFilterNormalizer accountFilterNormalizer;
    private final Clock clock;

    @Autowired
    public HyperlinkTaskConfigurationFactory(HyperlinkMessageContentValidator contentValidator,
            ObjectMapper objectMapper, HyperlinkAccountFilterNormalizer accountFilterNormalizer) {
        this(contentValidator, objectMapper, accountFilterNormalizer, Clock.systemUTC());
    }

    HyperlinkTaskConfigurationFactory(HyperlinkMessageContentValidator contentValidator,
            ObjectMapper objectMapper, HyperlinkAccountFilterNormalizer accountFilterNormalizer,
            Clock clock) {
        this.contentValidator = contentValidator;
        this.objectMapper = objectMapper;
        this.accountFilterNormalizer = accountFilterNormalizer;
        this.clock = clock;
    }

    public Normalized normalizeForCreate(HyperlinkTaskSaveDTO request) {
        return normalize(request, false, false);
    }

    public Normalized normalizeForUpdate(
            HyperlinkTaskSaveDTO request, Integer existingMessageType) {
        if (request == null || request.messageType() == null
                || !request.messageType().equals(existingMessageType)) {
            throw validation("编辑任务不能修改消息类型");
        }
        return normalize(request, true, Integer.valueOf(2).equals(existingMessageType));
    }

    private Normalized normalize(HyperlinkTaskSaveDTO request, boolean update,
            boolean historicalDoubleImageAllowed) {
        if (request == null || request.taskName() == null || request.taskName().trim().isEmpty()
                || request.taskName().trim().length() > 128 || request.enabled() == null) {
            throw validation("任务名称或 enabled 非法");
        }
        HyperlinkTaskMode taskMode = HyperlinkTaskMode.fromApi(request.taskMode());
        HyperlinkTaskStartMode startMode = HyperlinkTaskStartMode.fromApi(request.startMode());
        HyperlinkAccountFilterDTO accountFilter = accountFilterNormalizer.normalize(
                request.accountFilter());
        if (request.messageType() == null || request.messageContent() == null) {
            throw validation("消息类型和消息内容必填");
        }
        HyperlinkMessageContent submittedContent = toSharedContent(
                request.messageType(), request.messageContent());
        HyperlinkMessageContent content = historicalDoubleImageAllowed
                ? contentValidator.validateAndNormalize(submittedContent, true)
                : contentValidator.validateAndNormalize(submittedContent);
        if (request.messageIntervalMinSeconds() == null || request.messageIntervalMaxSeconds() == null) {
            throw validation("消息间隔必填");
        }
        int minMs = intervalMs(request.messageIntervalMinSeconds());
        int maxMs = intervalMs(request.messageIntervalMaxSeconds());
        if (minMs > maxMs) { throw validation("消息间隔下界不能大于上界"); }
        int maxExecuting = positive(request.maxExecutingAccounts(), "maxExecutingAccounts 必须大于 0");
        int maxUse = nonNegative(request.maxUseAccounts(), "maxUseAccounts 不能小于 0");
        int maxSend = nonNegative(request.maxSendPerAccount(), "maxSendPerAccount 不能小于 0");
        if (maxExecuting * ACCOUNT_SEND_CONCURRENCY > 10_000
                || (maxUse > 0 && maxExecuting > maxUse)) {
            throw validation("执行账号数超出任务容量");
        }
        ModeFields modeFields = normalizeModeFields(taskMode, request.plannedEndAt(),
                request.cycleIntervalMinutes(), maxUse, maxExecuting);
        if (taskMode == HyperlinkTaskMode.CYCLE && maxUse < maxExecuting) {
            throw validation("周期任务的周期间隔和账号上限非法");
        }
        int delay = normalizeDelay(startMode, request.delayMinutes());
        if (startMode == HyperlinkTaskStartMode.SCHEDULED && request.enabled() && delay < 1) {
            throw validation("延后启用任务 delayMinutes 必须大于 0");
        }
        if (request.enabled() && request.dataPackageId() == null) {
            throw validation("启用任务必须选择数据包");
        }
        if (!update && request.sourceTaskId() == null && request.enabled()
                && (request.quoteToken() == null || request.quoteToken().isBlank())) {
            throw validation("普通启用创建必须携带 quoteToken");
        }
        boolean shortLink = content.buttons().stream()
                .anyMatch(button -> Boolean.TRUE.equals(button.useShortLink()));
        return new Normalized(taskMode, startMode, content, accountFilter,
                modeFields.plannedEndAt(), modeFields.cycleIntervalMinutes(), minMs, maxMs,
                maxExecuting, maxUse, maxSend, delay, request.enabled(), shortLink);
    }

    public HyperlinkTask task(HyperlinkTaskSaveDTO request, Normalized value,
            long createdBy, long now) {
        HyperlinkTask task = new HyperlinkTask();
        task.setTenantId(TenantContext.get());
        task.setTaskName(request.taskName().trim());
        task.setTaskType(value.taskMode().code());
        task.setStartMode(value.startMode().code());
        task.setTaskDelayMinutes(value.delayMinutes());
        task.setTaskPlannedEndAt(value.plannedEndAt());
        task.setTaskIntervalMinutes(value.cycleIntervalMinutes());
        task.setDataPackageId(request.dataPackageId());
        task.setAccountFilter(json(value.accountFilter()));
        task.setMaxUseAccount(value.maxUseAccounts());
        task.setConcurrentNum(value.maxExecutingAccounts());
        task.setAccountMaxSendNum(value.maxSendPerAccount());
        task.setAccountSendConcurrency(ACCOUNT_SEND_CONCURRENCY);
        task.setMsgIntervalMinMs(value.minMs());
        task.setMsgIntervalMaxMs(value.maxMs());
        task.setShortLinkEnabled(value.shortLink());
        task.setVersion(1);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    public HyperlinkTaskContent content(long taskId, HyperlinkMessageContent value, long now) {
        HyperlinkTaskContent content = new HyperlinkTaskContent();
        content.setHyperlinkTaskId(taskId);
        content.setMessageSchemaVersion(value.schemaVersion());
        content.setMessageType(value.messageType());
        content.setTitle(value.title());
        content.setContent(value.content());
        content.setLinkDescription(value.linkDescription());
        content.setPromotionLink(value.promotionLink());
        content.setButtons(json(value.buttons()));
        content.setCardText(value.cardText());
        content.setLinkPreviewAssetId(value.linkPreviewAssetId());
        content.setBodyMainAssetId(value.bodyMainAssetId());
        content.setCreatedAt(now);
        content.setUpdatedAt(now);
        return content;
    }

    public HyperlinkTaskRuntime runtime(long taskId, boolean enabled, long now) {
        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setHyperlinkTaskId(taskId);
        runtime.setEnabled(enabled);
        runtime.setRunStatus(0);
        runtime.setProvisionStatus(enabled ? 1 : 0);
        runtime.setCreatedAt(now);
        runtime.setUpdatedAt(now);
        return runtime;
    }

    public boolean frozenScopeChanged(HyperlinkTask before, HyperlinkTask after) {
        return !java.util.Objects.equals(before.getTaskType(), after.getTaskType())
                || !java.util.Objects.equals(before.getDataPackageId(), after.getDataPackageId())
                || !java.util.Objects.equals(before.getDataPackageGeneration(), after.getDataPackageGeneration())
                || !java.util.Objects.equals(before.getTargetCountryIso2sSnapshot(),
                        after.getTargetCountryIso2sSnapshot())
                || !java.util.Objects.equals(before.getAccountFilter(), after.getAccountFilter())
                || !java.util.Objects.equals(before.getMaxUseAccount(), after.getMaxUseAccount())
                || !java.util.Objects.equals(before.getConcurrentNum(), after.getConcurrentNum())
                || !java.util.Objects.equals(before.getAccountMaxSendNum(), after.getAccountMaxSendNum());
    }

    /** 将报价中的数据包代次、名称和国家集合写入任务冻结快照。 */
    public void applyPackageSnapshot(HyperlinkTask task,
            HyperlinkQuoteTokenService.QuoteClaims claims) {
        if (claims == null) { return; }
        task.setDataPackageGeneration(claims.dataPackageGeneration());
        task.setDataPackageNameSnapshot(claims.quote().dataPackageName());
        task.setTargetCountryIso2sSnapshot(json(claims.quote().pricingBreakdown().stream()
                .map(row -> row.recipientCountryIso2()).distinct().toList()));
    }

    private HyperlinkMessageContent toSharedContent(int messageType, HyperlinkTaskMessageContentDTO dto) {
        List<HyperlinkButton> buttons = dto.buttons() == null ? List.of() : dto.buttons().stream()
                .map(this::toSharedButton).toList();
        return new HyperlinkMessageContent(1, messageType, dto.title(), dto.content(),
                dto.linkDescription(), dto.promotionLink(), buttons, dto.cardText(),
                dto.linkPreviewAssetId(), dto.bodyMainAssetId());
    }

    private HyperlinkButton toSharedButton(HyperlinkTaskButtonDTO button) {
        if (button == null || !"CTA_URL".equals(button.type())) {
            throw validation("一期按钮类型只支持 CTA_URL");
        }
        return new HyperlinkButton(HyperlinkButtonType.CTA_URL, button.displayText(),
                button.url(), button.useShortLink(), 1);
    }

    private int intervalMs(BigDecimal seconds) {
        if (seconds == null) {
            throw validation("消息间隔必填");
        }
        if (seconds.scale() > 1 || seconds.compareTo(BigDecimal.ZERO) < 0
                || seconds.compareTo(BigDecimal.TEN) > 0) {
            throw validation("消息间隔必须为 0 到 10 秒且最多 0.1 秒精度");
        }
        return seconds.multiply(BigDecimal.valueOf(1000))
                .setScale(0, RoundingMode.UNNECESSARY).intValueExact();
    }

    private int positive(Integer value, String message) {
        if (value == null || value < 1) { throw validation(message); }
        return value;
    }

    private int nonNegative(Integer value, String message) {
        if (value == null || value < 0) { throw validation(message); }
        return value;
    }

    private ModeFields normalizeModeFields(HyperlinkTaskMode mode, Long plannedEndAt,
            Integer cycleIntervalMinutes, int maxUse, int maxExecuting) {
        if (mode == HyperlinkTaskMode.ROLLING) {
            if (plannedEndAt == null || plannedEndAt < clock.millis() + MINUTE_MILLIS) {
                throw validation("预发布计划结束时间至少晚于当前 1 分钟");
            }
            return new ModeFields(plannedEndAt, 0);
        }
        if (mode == HyperlinkTaskMode.CYCLE) {
            if (cycleIntervalMinutes == null || cycleIntervalMinutes < 1
                    || maxUse < 1 || maxUse < maxExecuting) {
                throw validation("周期任务的周期间隔和账号上限非法");
            }
            return new ModeFields(null, cycleIntervalMinutes);
        }
        return new ModeFields(null, 0);
    }

    private int normalizeDelay(HyperlinkTaskStartMode mode, Integer delayMinutes) {
        int delay = nonNegative(delayMinutes, "delayMinutes 不能小于 0");
        return mode == HyperlinkTaskStartMode.NOW ? 0 : delay;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw validation("任务 JSON 快照无法序列化"); }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private record ModeFields(Long plannedEndAt, int cycleIntervalMinutes) { }

    public record Normalized(HyperlinkTaskMode taskMode, HyperlinkTaskStartMode startMode,
            HyperlinkMessageContent content, HyperlinkAccountFilterDTO accountFilter,
            Long plannedEndAt, int cycleIntervalMinutes, int minMs, int maxMs,
            int maxExecutingAccounts, int maxUseAccounts, int maxSendPerAccount,
            int delayMinutes, boolean enabled, boolean shortLink) { }
}
