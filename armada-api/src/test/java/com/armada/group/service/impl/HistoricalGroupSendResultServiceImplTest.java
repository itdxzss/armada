package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** 历史群营销协议结果回写测试。 */
@ExtendWith(MockitoExtension.class)
class HistoricalGroupSendResultServiceImplTest {

    private static final long TENANT_ID = 71L;
    private static final long EXECUTION_ID = 901L;
    private static final long MEMBER_ID = 301L;
    private static final String COMMAND_ID = "cmd-history-301";

    @Mock
    private HistoricalGroupPullExecutionMapper executionMapper;
    @Mock
    private HistoricalGroupPullMemberMapper memberMapper;

    private HistoricalGroupSendResultServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HistoricalGroupSendResultServiceImpl(executionMapper, memberMapper);
        TenantContext.set(99L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void supportsOnlyHistoricalGroupResults() {
        assertThat(service.supports(event(true, "evt-supported", COMMAND_ID))).isTrue();
        assertThat(service.supports(null)).isFalse();
        ProtocolMessageSendResultReportedEvent ordinary = new ProtocolMessageSendResultReportedEvent(
                "evt-ordinary", TENANT_ID, 1L, 2L, 3L, 1L,
                "acc-history-1", "120363history@g.us", "cmd-ordinary", true,
                "wamid.1", null, null, 1783159200000L, "worker-a",
                null, null, "marketing_task", null, null, null, null, null,
                null, null, null);
        assertThat(service.supports(ordinary)).isFalse();
    }

    @Test
    void writesFirstCompleteFailureAndFinalizesPartialAfterEveryMarketingMemberIsTerminal() {
        HistoricalGroupPullExecution execution = execution();
        HistoricalGroupPullMember target = member(MEMBER_ID, HistoricalGroupMemberSendStatus.SENDING);
        HistoricalGroupPullMember succeeded = member(302L, HistoricalGroupMemberSendStatus.SUCCESS);
        HistoricalGroupPullMember failed = member(MEMBER_ID, HistoricalGroupMemberSendStatus.FAILED);
        when(executionMapper.selectByTenantAndIdForUpdate(TENANT_ID, EXECUTION_ID)).thenReturn(execution);
        when(memberMapper.selectByTenantAndId(TENANT_ID, MEMBER_ID)).thenReturn(target);
        when(memberMapper.updateSendResultIfSending(any(), anyInt())).thenReturn(1);
        when(memberMapper.selectOrderedByExecutionId(EXECUTION_ID)).thenReturn(List.of(succeeded, failed));

        service.handleSendResultReported(event(false, "evt-failure", COMMAND_ID));

        ArgumentCaptor<HistoricalGroupPullMember> result =
                ArgumentCaptor.forClass(HistoricalGroupPullMember.class);
        verify(memberMapper).updateSendResultIfSending(
                result.capture(),
                org.mockito.ArgumentMatchers.eq(HistoricalGroupMemberSendStatus.SENDING.code()));
        assertThat(result.getValue().getExecutionId()).isEqualTo(EXECUTION_ID);
        assertThat(result.getValue().getSendResultEventId()).isEqualTo("evt-failure");
        assertThat(result.getValue().getSendErrorCode()).isEqualTo("SEND_FAILED");
        assertThat(result.getValue().getSendErrorMessage()).isEqualTo("完整协议失败详情");
        ArgumentCaptor<HistoricalGroupPullExecution> terminal =
                ArgumentCaptor.forClass(HistoricalGroupPullExecution.class);
        verify(executionMapper).finishMarketingIfSending(
                terminal.capture(),
                org.mockito.ArgumentMatchers.eq(HistoricalGroupMarketingStatus.SENDING.code()));
        assertThat(terminal.getValue().getMarketingStatus())
                .isEqualTo(HistoricalGroupMarketingStatus.PARTIAL_SUCCESS.code());
        assertThat(terminal.getValue().getSendSuccessCount()).isEqualTo(1);
        assertThat(terminal.getValue().getSendFailureCount()).isEqualTo(1);
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void rejectsMemberOrCommandThatDoesNotBelongToTheLockedExecution() {
        HistoricalGroupPullMember wrongMember = member(MEMBER_ID, HistoricalGroupMemberSendStatus.SENDING);
        wrongMember.setExecutionId(EXECUTION_ID + 1);
        when(executionMapper.selectByTenantAndIdForUpdate(TENANT_ID, EXECUTION_ID))
                .thenReturn(execution());
        when(memberMapper.selectByTenantAndId(TENANT_ID, MEMBER_ID)).thenReturn(wrongMember);

        assertThatThrownBy(() -> service.handleSendResultReported(event(false, "evt-wrong", COMMAND_ID)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成员");
        verify(memberMapper, never()).updateSendResultIfSending(any(), anyInt());
    }

    @Test
    void rejectsACommandThatDoesNotMatchTheHistoricalMember() {
        when(executionMapper.selectByTenantAndIdForUpdate(TENANT_ID, EXECUTION_ID))
                .thenReturn(execution());
        when(memberMapper.selectByTenantAndId(TENANT_ID, MEMBER_ID))
                .thenReturn(member(MEMBER_ID, HistoricalGroupMemberSendStatus.SENDING));

        assertThatThrownBy(() -> service.handleSendResultReported(
                event(false, "evt-wrong-command", "cmd-other")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("命令");

        verify(memberMapper, never()).updateSendResultIfSending(any(), anyInt());
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void ignoresDuplicateOrLateResultWithoutReaggregating() {
        when(executionMapper.selectByTenantAndIdForUpdate(TENANT_ID, EXECUTION_ID))
                .thenReturn(execution());
        when(memberMapper.selectByTenantAndId(TENANT_ID, MEMBER_ID))
                .thenReturn(member(MEMBER_ID, HistoricalGroupMemberSendStatus.SENDING));
        when(memberMapper.updateSendResultIfSending(any(), anyInt())).thenReturn(0);

        service.handleSendResultReported(event(true, "evt-duplicate", COMMAND_ID));

        verify(memberMapper, never()).selectOrderedByExecutionId(EXECUTION_ID);
        verify(executionMapper, never()).finishMarketingIfSending(any(), anyInt());
    }

    @Test
    void ignoresAnEventIdAlreadyConsumedByAnotherResultWithoutRetrying() {
        when(executionMapper.selectByTenantAndIdForUpdate(TENANT_ID, EXECUTION_ID))
                .thenReturn(execution());
        when(memberMapper.selectByTenantAndId(TENANT_ID, MEMBER_ID))
                .thenReturn(member(MEMBER_ID, HistoricalGroupMemberSendStatus.SENDING));
        when(memberMapper.updateSendResultIfSending(any(), anyInt()))
                .thenThrow(new DuplicateKeyException("duplicate send_result_event_id"));

        service.handleSendResultReported(event(true, "evt-duplicate-key", COMMAND_ID));

        verify(memberMapper, never()).selectOrderedByExecutionId(EXECUTION_ID);
        verify(executionMapper, never()).finishMarketingIfSending(any(), anyInt());
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    private static HistoricalGroupPullExecution execution() {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setId(EXECUTION_ID);
        row.setTenantId(TENANT_ID);
        row.setGroupJid("120363history@g.us");
        row.setMarketingStatus(HistoricalGroupMarketingStatus.SENDING.code());
        return row;
    }

    private static HistoricalGroupPullMember member(
            Long id,
            HistoricalGroupMemberSendStatus status) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setExecutionId(EXECUTION_ID);
        row.setMaterialType(HistoricalGroupMaterialType.MARKETING.code());
        row.setProtocolAccountIdSnapshot("acc-history-1");
        row.setSendCommandId(id.equals(MEMBER_ID) ? COMMAND_ID : "cmd-history-302");
        row.setSendStatus(status.code());
        return row;
    }

    private static ProtocolMessageSendResultReportedEvent event(
            boolean success,
            String eventId,
            String commandId) {
        return new ProtocolMessageSendResultReportedEvent(
                eventId, TENANT_ID, null, null, null, null,
                "acc-history-1", "120363history@g.us", commandId, success,
                success ? "wamid.1" : null,
                success ? null : "SEND_FAILED",
                success ? null : "完整协议失败详情",
                1783159200000L, "worker-a", null, null,
                "historical_group_pull", "UNCONFIRMED", "PRECHECK_SKIPPED_BY_SOURCE",
                1783159199000L, EXECUTION_ID, MEMBER_ID,
                null, null, null);
    }
}
