package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 历史群营销发送结果的真库身份、幂等与聚合测试。 */
class HistoricalGroupSendResultServiceImplDbTest extends DbTestBase {

    @Autowired
    private HistoricalGroupSendResultServiceImpl service;

    @Autowired
    private HistoricalGroupPullExecutionMapper executionMapper;

    @Autowired
    private HistoricalGroupPullMemberMapper memberMapper;

    @Test
    void storesCompleteFirstResultsAndFinalizesOnlyAfterEveryMarketingMemberIsTerminal() {
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution execution = execution("result-" + now, now);
        executionMapper.insert(execution);
        HistoricalGroupPullMember first = member(
                execution.getId(), 1, "8613900000001", "cmd-result-a-" + now, now);
        HistoricalGroupPullMember second = member(
                execution.getId(), 2, "8613900000002", "cmd-result-b-" + now, now);
        memberMapper.batchInsert(List.of(first, second));
        List<HistoricalGroupPullMember> persisted = memberMapper.selectOrderedByExecutionId(execution.getId());
        first = persisted.get(0);
        second = persisted.get(1);
        String completeError = "协议原始错误详情-" + "很长但不能脱敏或截断".repeat(100);

        service.handleSendResultReported(event(
                execution, first, false, "evt-result-a-" + now, "SEND_FAILED", completeError, now + 1));

        HistoricalGroupPullMember firstResult =
                memberMapper.selectByTenantAndId(TEST_TENANT_ID, first.getId());
        HistoricalGroupPullExecution waiting =
                executionMapper.selectByTenantAndId(TEST_TENANT_ID, execution.getId());
        assertThat(firstResult.getSendStatus()).isEqualTo(HistoricalGroupMemberSendStatus.FAILED.code());
        assertThat(firstResult.getSendErrorCode()).isEqualTo("SEND_FAILED");
        assertThat(firstResult.getSendErrorMessage()).isEqualTo(completeError);
        assertThat(waiting.getMarketingStatus()).isEqualTo(HistoricalGroupMarketingStatus.SENDING.code());

        // 同一命令的迟到成功结果不得覆盖首个失败事实，也不得触发重试。
        service.handleSendResultReported(event(
                execution, first, true, "evt-result-a-late-" + now, null, null, now + 2));
        assertThat(memberMapper.selectByTenantAndId(TEST_TENANT_ID, first.getId()).getSendResultEventId())
                .isEqualTo("evt-result-a-" + now);

        service.handleSendResultReported(event(
                execution, second, true, "evt-result-b-" + now, null, null, now + 3));

        HistoricalGroupPullExecution completed =
                executionMapper.selectByTenantAndId(TEST_TENANT_ID, execution.getId());
        assertThat(completed.getMarketingStatus())
                .isEqualTo(HistoricalGroupMarketingStatus.PARTIAL_SUCCESS.code());
        assertThat(completed.getSendSuccessCount()).isEqualTo(1);
        assertThat(completed.getSendFailureCount()).isEqualTo(1);
    }

    @Test
    void rejectsWrongCommandIdentityWithoutChangingTheMember() {
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution execution = execution("identity-" + now, now);
        executionMapper.insert(execution);
        HistoricalGroupPullMember member = member(
                execution.getId(), 1, "8613900000011", "cmd-identity-" + now, now);
        memberMapper.batchInsert(List.of(member));
        member = memberMapper.selectOrderedByExecutionId(execution.getId()).get(0);
        ProtocolMessageSendResultReportedEvent wrong = new ProtocolMessageSendResultReportedEvent(
                "evt-wrong-" + now, TEST_TENANT_ID, null, null, null, null,
                "protocol-account-1", execution.getGroupJid(), "cmd-not-owned", false,
                null, "SEND_FAILED", "错误命令不能污染成员", now + 1, "worker-db",
                null, null, "historical_group_pull", "UNCONFIRMED",
                "PRECHECK_SKIPPED_BY_SOURCE", now, execution.getId(), member.getId());

        assertThatThrownBy(() -> service.handleSendResultReported(wrong))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("命令");

        HistoricalGroupPullMember unchanged =
                memberMapper.selectByTenantAndId(TEST_TENANT_ID, member.getId());
        assertThat(unchanged.getSendStatus()).isEqualTo(HistoricalGroupMemberSendStatus.SENDING.code());
        assertThat(unchanged.getSendResultEventId()).isNull();
    }

    private HistoricalGroupPullExecution execution(String idempotencyKey, long now) {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setIdempotencyKey(idempotencyKey);
        row.setOperationAccountId(1001L);
        row.setGroupJid("120363result@g.us");
        row.setPullerAccountGroupId(2001L);
        row.setSingleAddCount(5);
        row.setMarketingCount(2);
        row.setPullStatus(HistoricalGroupPullStatus.SUCCESS.code());
        row.setMarketingStatus(HistoricalGroupMarketingStatus.SENDING.code());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static HistoricalGroupPullMember member(
            Long executionId,
            int lineNo,
            String phone,
            String commandId,
            long now) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setExecutionId(executionId);
        row.setLineNo(lineNo);
        row.setPhone(phone);
        row.setMaterialType(HistoricalGroupMaterialType.MARKETING.code());
        row.setProtocolAccountIdSnapshot("protocol-account-1");
        row.setSendStatus(HistoricalGroupMemberSendStatus.SENDING.code());
        row.setSendCommandId(commandId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static ProtocolMessageSendResultReportedEvent event(
            HistoricalGroupPullExecution execution,
            HistoricalGroupPullMember member,
            boolean success,
            String eventId,
            String reasonCode,
            String reasonMessage,
            long timestamp) {
        return new ProtocolMessageSendResultReportedEvent(
                eventId, TEST_TENANT_ID, null, null, null, null,
                "protocol-account-1", execution.getGroupJid(), member.getSendCommandId(), success,
                success ? "wamid." + member.getId() : null, reasonCode, reasonMessage,
                timestamp, "worker-db", null, null, "historical_group_pull",
                "UNCONFIRMED", "PRECHECK_SKIPPED_BY_SOURCE", timestamp - 1,
                execution.getId(), member.getId());
    }
}
