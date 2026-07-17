package com.armada.group.service.impl;

import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 历史群全部营销账号的协议发送结果处理器。 */
@Service
public class HistoricalGroupSendResultServiceImpl implements ProtocolMessageSendResultReportedSink {

    private static final Logger log = LoggerFactory.getLogger(HistoricalGroupSendResultServiceImpl.class);
    private static final String SOURCE = "historical_group_pull";
    private static final int ERROR_CODE_MAX_CHARS = 64;

    private final HistoricalGroupPullExecutionMapper executionMapper;
    private final HistoricalGroupPullMemberMapper memberMapper;

    /**
     * 创建历史群协议发送结果处理器。
     *
     * @param executionMapper 执行聚合数据访问
     * @param memberMapper    营销成员结果数据访问
     */
    public HistoricalGroupSendResultServiceImpl(
            HistoricalGroupPullExecutionMapper executionMapper,
            HistoricalGroupPullMemberMapper memberMapper) {
        this.executionMapper = executionMapper;
        this.memberMapper = memberMapper;
    }

    /** 历史群结果只由本处理器消费，不能落入现有营销重试逻辑。 */
    @Override
    public boolean supports(ProtocolMessageSendResultReportedEvent event) {
        return event != null && SOURCE.equals(event.source());
    }

    /**
     * 幂等写入首个发送结果，并在全部 A 成员终态后聚合执行结果。
     *
     * <p>先锁定执行行，使同一执行下并发到达的最后几个结果串行处理；协议失败只保存原始错误，
     * 不创建任何重试或替换命令。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        validateEnvelope(event);
        Long previousTenantId = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            HistoricalGroupPullExecution execution = executionMapper.selectByTenantAndIdForUpdate(
                    event.tenantId(), event.historicalExecutionId());
            if (execution == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "历史群营销执行不存在: " + event.historicalExecutionId());
            }
            HistoricalGroupPullMember member = memberMapper.selectByTenantAndId(
                    event.tenantId(), event.historicalMemberId());
            validateIdentity(event, execution, member);

            if (!Integer.valueOf(HistoricalGroupMarketingStatus.SENDING.code())
                    .equals(execution.getMarketingStatus())) {
                log.info("历史群营销结果已跳过 executionId={} memberId={} commandId={} reason=execution_final",
                        execution.getId(), member.getId(), event.commandId());
                return;
            }

            HistoricalGroupPullMember result = resultRow(event);
            int updated;
            try {
                updated = memberMapper.updateSendResultIfSending(
                        result, HistoricalGroupMemberSendStatus.SENDING.code());
            } catch (DuplicateKeyException duplicateEvent) {
                // send_result_event_id 在租户内唯一；重复投递不得触发 Kafka 业务重试。
                log.info("历史群营销结果已跳过 executionId={} memberId={} eventId={} reason=duplicate_event",
                        execution.getId(), member.getId(), event.eventId());
                return;
            }
            if (updated != 1) {
                log.info("历史群营销结果已跳过 executionId={} memberId={} commandId={} reason=duplicate_or_final",
                        execution.getId(), member.getId(), event.commandId());
                return;
            }
            aggregateIfComplete(execution.getId());
            log.info("历史群营销结果已回写 executionId={} memberId={} commandId={} success={} eventId={}",
                    execution.getId(), member.getId(), event.commandId(), event.success(), event.eventId());
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    private void aggregateIfComplete(Long executionId) {
        List<HistoricalGroupPullMember> marketingMembers = memberMapper.selectOrderedByExecutionId(executionId)
                .stream()
                .filter(member -> Integer.valueOf(HistoricalGroupMaterialType.MARKETING.code())
                        .equals(member.getMaterialType()))
                .toList();
        int successCount = countStatus(marketingMembers, HistoricalGroupMemberSendStatus.SUCCESS);
        int failureCount = countStatus(marketingMembers, HistoricalGroupMemberSendStatus.FAILED);
        if (marketingMembers.isEmpty() || successCount + failureCount != marketingMembers.size()) {
            return;
        }
        HistoricalGroupPullExecution terminal = new HistoricalGroupPullExecution();
        terminal.setId(executionId);
        terminal.setMarketingStatus(terminalStatus(successCount, failureCount).code());
        terminal.setSendSuccessCount(successCount);
        terminal.setSendFailureCount(failureCount);
        terminal.setUpdatedAt(System.currentTimeMillis());
        executionMapper.finishMarketingIfSending(
                terminal, HistoricalGroupMarketingStatus.SENDING.code());
    }

    private static int countStatus(
            List<HistoricalGroupPullMember> members,
            HistoricalGroupMemberSendStatus status) {
        return (int) members.stream()
                .filter(member -> Integer.valueOf(status.code()).equals(member.getSendStatus()))
                .count();
    }

    private static HistoricalGroupMarketingStatus terminalStatus(int successCount, int failureCount) {
        if (successCount > 0 && failureCount == 0) {
            return HistoricalGroupMarketingStatus.SUCCESS;
        }
        if (successCount > 0) {
            return HistoricalGroupMarketingStatus.PARTIAL_SUCCESS;
        }
        return HistoricalGroupMarketingStatus.FAILED;
    }

    private static HistoricalGroupPullMember resultRow(ProtocolMessageSendResultReportedEvent event) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setId(event.historicalMemberId());
        row.setTenantId(event.tenantId());
        row.setExecutionId(event.historicalExecutionId());
        row.setSendCommandId(event.commandId());
        row.setSendResultEventId(event.eventId());
        row.setSendStatus(event.success()
                ? HistoricalGroupMemberSendStatus.SUCCESS.code()
                : HistoricalGroupMemberSendStatus.FAILED.code());
        row.setSendErrorCode(event.success() ? null : errorCode(event.reasonCode()));
        row.setSendErrorMessage(event.success() ? null : errorMessage(event));
        row.setUpdatedAt(event.timestamp() == null || event.timestamp() < 1
                ? System.currentTimeMillis() : event.timestamp());
        return row;
    }

    private static void validateEnvelope(ProtocolMessageSendResultReportedEvent event) {
        if (event == null || !SOURCE.equals(event.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "不是历史群营销发送结果");
        }
        if (event.tenantId() == null || event.tenantId() < 1
                || event.historicalExecutionId() == null || event.historicalExecutionId() < 1
                || event.historicalMemberId() == null || event.historicalMemberId() < 1
                || !StringUtils.hasText(event.eventId()) || !StringUtils.hasText(event.commandId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "历史群营销发送结果关联信息不完整");
        }
        if (event.eventId().length() > 64 || event.commandId().length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION, "历史群营销发送结果关联 ID 超过 64 个字符");
        }
    }

    private static void validateIdentity(
            ProtocolMessageSendResultReportedEvent event,
            HistoricalGroupPullExecution execution,
            HistoricalGroupPullMember member) {
        if (member == null
                || !Objects.equals(member.getTenantId(), event.tenantId())
                || !Objects.equals(member.getExecutionId(), execution.getId())
                || !Integer.valueOf(HistoricalGroupMaterialType.MARKETING.code())
                        .equals(member.getMaterialType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "历史群营销结果成员关联不匹配");
        }
        if (!Objects.equals(member.getSendCommandId(), event.commandId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "历史群营销结果命令关联不匹配");
        }
        if (!Objects.equals(execution.getGroupJid(), event.groupJid())) {
            throw new BusinessException(ErrorCode.CONFLICT, "历史群营销结果目标群关联不匹配");
        }
    }

    private static String errorCode(String value) {
        String code = StringUtils.hasText(value) ? value : "MESSAGE_SEND_FAILED";
        return code.length() <= ERROR_CODE_MAX_CHARS ? code : code.substring(0, ERROR_CODE_MAX_CHARS);
    }

    private static String errorMessage(ProtocolMessageSendResultReportedEvent event) {
        if (StringUtils.hasText(event.reasonMessage())) {
            return event.reasonMessage();
        }
        return StringUtils.hasText(event.reasonCode()) ? event.reasonCode() : "消息发送失败";
    }

    private static void restoreTenant(Long previousTenantId) {
        if (previousTenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenantId);
        }
    }
}
