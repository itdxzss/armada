package com.armada.group.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.dto.HistoricalGroupMarketingSendDTO;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupPullExecutionVO;
import com.armada.group.service.HistoricalGroupMarketingService;
import com.armada.group.service.HistoricalGroupPullExecutionService;
import com.armada.group.service.HistoricalGroupService;
import com.armada.marketing.model.vo.MarketingComposedMessageVO;
import com.armada.marketing.service.MarketingMessageCompositionService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 历史群全部营销账号一次性发送实现。 */
@Service
public class HistoricalGroupMarketingServiceImpl implements HistoricalGroupMarketingService {

    private static final String SOURCE_HISTORICAL_GROUP_PULL = "historical_group_pull";
    private static final String ACCOUNT_UNAVAILABLE = "MARKETING_ACCOUNT_UNAVAILABLE";
    private static final String BACKEND_UNSUPPORTED = "MARKETING_BACKEND_UNSUPPORTED";
    private static final String ENQUEUE_FAILED = "MESSAGE_ENQUEUE_FAILED";

    private final HistoricalGroupPullExecutionMapper executionMapper;
    private final HistoricalGroupPullMemberMapper memberMapper;
    private final HistoricalGroupService historicalGroupService;
    private final HistoricalGroupPullExecutionService executionService;
    private final MarketingMessageCompositionService messageCompositionService;
    private final AccountProtocolLookupService accountLookupService;
    private final MessageSendPort messageSendPort;

    /**
     * 创建历史群全部营销账号发送服务。
     *
     * @param executionMapper          执行数据访问
     * @param memberMapper             成员数据访问
     * @param historicalGroupService   fresh 群详情与链接门禁服务
     * @param executionService         当前执行视图查询服务
     * @param messageCompositionService 当前租户模板组合服务
     * @param accountLookupService     营销号码协议身份查询服务
     * @param messageSendPort          统一消息发送端口
     */
    public HistoricalGroupMarketingServiceImpl(
            HistoricalGroupPullExecutionMapper executionMapper,
            HistoricalGroupPullMemberMapper memberMapper,
            HistoricalGroupService historicalGroupService,
            HistoricalGroupPullExecutionService executionService,
            MarketingMessageCompositionService messageCompositionService,
            AccountProtocolLookupService accountLookupService,
            MessageSendPort messageSendPort) {
        this.executionMapper = executionMapper;
        this.memberMapper = memberMapper;
        this.historicalGroupService = historicalGroupService;
        this.executionService = executionService;
        this.messageCompositionService = messageCompositionService;
        this.accountLookupService = accountLookupService;
        this.messageSendPort = messageSendPort;
    }

    /** {@inheritDoc} */
    @Override
    public HistoricalGroupPullExecutionVO send(
            Long executionId,
            HistoricalGroupMarketingSendDTO request) {
        Long tenantId = requireTenantId();
        Long templateId = requireTemplateId(request);
        HistoricalGroupPullExecution execution = requireExecution(tenantId, executionId);
        requirePullTerminal(execution);
        if (execution.getMarketingStatus() != HistoricalGroupMarketingStatus.NOT_STARTED.code()) {
            return executionService.getById(executionId);
        }

        HistoricalGroupDetailVO detail = historicalGroupService.getHistoricalGroupDetail(
                execution.getOperationAccountId(), execution.getGroupJid());
        requireLink(detail);
        MarketingComposedMessageVO message = messageCompositionService.compose(templateId);
        long now = System.currentTimeMillis();
        int claimed = executionMapper.claimMarketingIfNotStarted(
                executionId,
                HistoricalGroupMarketingStatus.NOT_STARTED.code(),
                HistoricalGroupMarketingStatus.SENDING.code(),
                templateId,
                now);
        if (claimed != 1) {
            return executionService.getById(executionId);
        }

        List<HistoricalGroupPullMember> marketingMembers = marketingMembers(executionId);
        Map<String, ProtocolAccountRef> accounts = accountLookupService.findActiveProtocolRefsByPhones(
                marketingMembers.stream().map(HistoricalGroupPullMember::getPhone).toList());
        for (HistoricalGroupPullMember member : marketingMembers) {
            dispatchMember(tenantId, execution, member, accounts.get(member.getPhone()), message);
        }
        finishIfNoSending(executionId);
        return executionService.getById(executionId);
    }

    private void dispatchMember(
            Long tenantId,
            HistoricalGroupPullExecution execution,
            HistoricalGroupPullMember member,
            ProtocolAccountRef account,
            MarketingComposedMessageVO message) {
        long now = System.currentTimeMillis();
        if (account == null) {
            HistoricalGroupPullMember failed = failedMember(
                    member.getId(), null, ACCOUNT_UNAVAILABLE,
                    "当前租户未找到协议身份完整的营销账号: " + member.getPhone(), now);
            memberMapper.markSendFailedIfPending(
                    failed,
                    HistoricalGroupMemberSendStatus.PENDING.code(),
                    HistoricalGroupMemberSendStatus.FAILED.code());
            return;
        }
        if (account.backend() != ProtocolBackend.WEB) {
            // Android worker 尚未回传 historical correlation；提前失败避免执行永久停在 SENDING。
            HistoricalGroupPullMember failed = failedMember(
                    member.getId(), null, BACKEND_UNSUPPORTED,
                    "当前营销账号协议类型暂不支持历史群发送结果闭环: " + account.backend(), now);
            memberMapper.markSendFailedIfPending(
                    failed,
                    HistoricalGroupMemberSendStatus.PENDING.code(),
                    HistoricalGroupMemberSendStatus.FAILED.code());
            return;
        }
        String commandId = UUID.randomUUID().toString();
        int marked = memberMapper.markSendSendingIfPending(
                member.getId(),
                HistoricalGroupMemberSendStatus.PENDING.code(),
                HistoricalGroupMemberSendStatus.SENDING.code(),
                commandId,
                now);
        if (marked != 1) {
            return;
        }
        try {
            MessageSendEnqueueItem result = enqueueOne(
                    toCommand(tenantId, execution, member, account, message, commandId));
            if (!result.accepted()) {
                markEnqueueFailed(member.getId(), commandId, result.reasonCode(), result.reasonMessage());
            }
        } catch (RuntimeException ex) {
            markEnqueueFailed(member.getId(), commandId, ENQUEUE_FAILED, exceptionMessage(ex));
        }
    }

    private MessageSendEnqueueItem enqueueOne(MessageSendCommand command) {
        MessageSendEnqueueResult result = messageSendPort.enqueue(List.of(command));
        if (result == null || result.items().size() != 1) {
            throw new IllegalStateException("消息发送端口未返回唯一入队结果");
        }
        MessageSendEnqueueItem item = result.items().get(0);
        if (item == null || !command.commandId().equals(item.commandId())) {
            throw new IllegalStateException("消息发送端口返回的命令 ID 不匹配");
        }
        return item;
    }

    private void markEnqueueFailed(
            Long memberId,
            String commandId,
            String errorCode,
            String errorMessage) {
        HistoricalGroupPullMember failed = failedMember(
                memberId, commandId,
                textOrDefault(errorCode, ENQUEUE_FAILED),
                textOrDefault(errorMessage, "消息命令入队失败"),
                System.currentTimeMillis());
        memberMapper.markSendFailedByCommandId(
                failed,
                HistoricalGroupMemberSendStatus.SENDING.code(),
                HistoricalGroupMemberSendStatus.FAILED.code());
    }

    private void finishIfNoSending(Long executionId) {
        List<HistoricalGroupPullMember> members = marketingMembers(executionId);
        if (members.stream().anyMatch(member ->
                member.getSendStatus() == HistoricalGroupMemberSendStatus.SENDING.code())) {
            return;
        }
        int successCount = (int) members.stream().filter(member ->
                member.getSendStatus() == HistoricalGroupMemberSendStatus.SUCCESS.code()).count();
        int failureCount = (int) members.stream().filter(member ->
                member.getSendStatus() == HistoricalGroupMemberSendStatus.FAILED.code()).count();
        if (successCount + failureCount != members.size()) {
            return;
        }
        HistoricalGroupMarketingStatus status = successCount > 0 && failureCount > 0
                ? HistoricalGroupMarketingStatus.PARTIAL_SUCCESS
                : successCount > 0
                        ? HistoricalGroupMarketingStatus.SUCCESS
                        : HistoricalGroupMarketingStatus.FAILED;
        HistoricalGroupPullExecution terminal = new HistoricalGroupPullExecution();
        terminal.setId(executionId);
        terminal.setMarketingStatus(status.code());
        terminal.setSendSuccessCount(successCount);
        terminal.setSendFailureCount(failureCount);
        terminal.setUpdatedAt(System.currentTimeMillis());
        executionMapper.finishMarketingIfSending(
                terminal, HistoricalGroupMarketingStatus.SENDING.code());
    }

    private List<HistoricalGroupPullMember> marketingMembers(Long executionId) {
        return memberMapper.selectOrderedByExecutionId(executionId).stream()
                .filter(member -> member.getMaterialType() == HistoricalGroupMaterialType.MARKETING.code())
                .toList();
    }

    private static MessageSendCommand toCommand(
            Long tenantId,
            HistoricalGroupPullExecution execution,
            HistoricalGroupPullMember member,
            ProtocolAccountRef account,
            MarketingComposedMessageVO message,
            String commandId) {
        return new MessageSendCommand(
                account,
                new MessageSendCommand.MessageTarget(execution.getGroupJid()),
                new MessageSendCommand.MessagePayload(
                        messageType(message.messageType()),
                        new MessageSendCommand.MessageContent(
                                message.text(),
                                media(message.imageBytes(), message.imageMimetype()),
                                linkCard(message.linkCard()),
                                buttonCard(message.buttonCard())),
                        message.mentionAll()),
                new MessageSendCommand.MessageCorrelation(
                        tenantId,
                        SOURCE_HISTORICAL_GROUP_PULL,
                        null,
                        null,
                        new MessageSendCommand.HistoricalGroupCorrelation(execution.getId(), member.getId())),
                commandId,
                0L);
    }

    private static MessageType messageType(String value) {
        try {
            return MessageType.valueOf(value);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板消息类型不支持: " + value);
        }
    }

    private static MessageSendCommand.MessageLinkCard linkCard(
            MarketingComposedMessageVO.LinkCardVO source) {
        if (source == null) {
            return null;
        }
        return new MessageSendCommand.MessageLinkCard(
                source.url(), source.title(), source.description(), media(source.thumbnail()));
    }

    private static MessageSendCommand.MessageButtonCard buttonCard(
            MarketingComposedMessageVO.ButtonCardVO source) {
        if (source == null) {
            return null;
        }
        return new MessageSendCommand.MessageButtonCard(
                source.title(),
                source.footer(),
                source.buttons().stream()
                        .map(button -> new MessageSendCommand.MessageButton(
                                button.type(), button.displayText(), button.value()))
                        .toList(),
                media(source.thumbnail()));
    }

    private static MessageSendCommand.MessageMedia media(MarketingComposedMessageVO.MediaVO source) {
        return source == null ? null : media(source.bytes(), source.mimetype());
    }

    private static MessageSendCommand.MessageMedia media(byte[] bytes, String mimetype) {
        return bytes == null || bytes.length == 0 ? null : new MessageSendCommand.MessageMedia(bytes, mimetype);
    }

    private static HistoricalGroupPullMember failedMember(
            Long memberId,
            String commandId,
            String errorCode,
            String errorMessage,
            long updatedAt) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setId(memberId);
        row.setSendCommandId(commandId);
        row.setSendErrorCode(errorCode);
        row.setSendErrorMessage(errorMessage);
        row.setUpdatedAt(updatedAt);
        return row;
    }

    private HistoricalGroupPullExecution requireExecution(Long tenantId, Long executionId) {
        if (executionId == null || executionId < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "执行 ID 必须大于 0");
        }
        HistoricalGroupPullExecution execution = executionMapper.selectByTenantAndId(tenantId, executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "历史群拉人执行不存在: " + executionId);
        }
        return execution;
    }

    private static void requirePullTerminal(HistoricalGroupPullExecution execution) {
        HistoricalGroupPullStatus status = HistoricalGroupPullStatus.fromCode(execution.getPullStatus());
        if (status == HistoricalGroupPullStatus.PENDING || status == HistoricalGroupPullStatus.RUNNING) {
            throw new BusinessException(ErrorCode.CONFLICT, "拉人执行尚未终态，不能发送营销消息");
        }
    }

    private static void requireLink(HistoricalGroupDetailVO detail) {
        if (detail == null || !detail.linkAvailable()
                || detail.inviteUrl() == null || detail.inviteUrl().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群邀请链接不可用，不能发送营销消息");
        }
    }

    private static Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }

    private static Long requireTemplateId(HistoricalGroupMarketingSendDTO request) {
        if (request == null || request.marketingTemplateId() == null || request.marketingTemplateId() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板 ID 必须大于 0");
        }
        return request.marketingTemplateId();
    }

    private static String exceptionMessage(RuntimeException ex) {
        return textOrDefault(ex.getMessage(), ex.getClass().getSimpleName());
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
