package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.armada.task.service.impl.PullTaskMemberQueryCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullTaskMemberQueryServiceTest {

    @Test
    void returnsPendingWithoutCreatingDuplicateBeforeDeadline() {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQueryCommandService commandService =
                mock(PullTaskMemberQueryCommandService.class);
        PullTaskMemberQuery row = row(PullTaskMemberQueryStatus.PENDING, 2_000L);
        when(mapper.selectLatestByBusinessKey(11L, "manager:601")).thenReturn(row);
        PullTaskMemberQueryService service = service(mapper, commandService);

        PullTaskMemberQueryResult result = service.requestOrRead(request(), 1_000L);

        assertThat(result.state()).isEqualTo(PullTaskMemberQueryResult.State.PENDING);
        assertThat(result.queryId()).isEqualTo(701L);
        verify(commandService, never()).create(any());
    }

    @Test
    void expiresTimedOutAttemptAndCreatesExactlyOneRetry() {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQueryCommandService commandService =
                mock(PullTaskMemberQueryCommandService.class);
        PullTaskMemberQuery expired = row(PullTaskMemberQueryStatus.PENDING, 900L);
        PullTaskMemberQuery retry = row(PullTaskMemberQueryStatus.PENDING, 31_000L);
        retry.setId(702L);
        retry.setAttemptNo(2);
        when(mapper.selectLatestByBusinessKey(11L, "manager:601")).thenReturn(expired);
        when(mapper.expirePending(
                701L, PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.EXPIRED.code(), 1_000L,
                "QUERY_TIMEOUT", "member query timed out"))
                .thenReturn(1);
        when(commandService.create(any())).thenReturn(retry);
        PullTaskMemberQueryService service = service(mapper, commandService);

        PullTaskMemberQueryResult result = service.requestOrRead(request(), 1_000L);

        assertThat(result.state()).isEqualTo(PullTaskMemberQueryResult.State.PENDING);
        assertThat(result.queryId()).isEqualTo(702L);
        verify(commandService).create(any());
    }

    @Test
    void oneShotReadExpiresTimedOutActorWithoutRetryingSameActor() {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQueryCommandService commandService =
                mock(PullTaskMemberQueryCommandService.class);
        PullTaskMemberQuery expired = row(PullTaskMemberQueryStatus.PENDING, 900L);
        when(mapper.selectLatestByBusinessKey(11L, "manager:601")).thenReturn(expired);
        when(mapper.expirePending(
                701L, PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.EXPIRED.code(), 1_000L,
                "QUERY_TIMEOUT", "member query timed out"))
                .thenReturn(1);
        PullTaskMemberQueryService service = service(mapper, commandService);

        PullTaskMemberQueryResult result = service.requestOrReadOnce(request(), 1_000L);

        assertThat(result.state()).isEqualTo(PullTaskMemberQueryResult.State.FAILED);
        assertThat(result.errorCode()).isEqualTo("QUERY_TIMEOUT");
        verify(commandService, never()).create(any());
    }

    @Test
    void returnsCompletedFilteredFactsWithoutCallingProtocolAgain() {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQueryCommandService commandService =
                mock(PullTaskMemberQueryCommandService.class);
        PullTaskMemberQuery row = row(PullTaskMemberQueryStatus.SUCCEEDED, 2_000L);
        row.setResultJson("[{\"targetJid\":\"456@s.whatsapp.net\","
                + "\"participantJid\":\"456@s.whatsapp.net\","
                + "\"phoneNumber\":\"456\",\"inGroup\":true,\"admin\":false}]");
        when(mapper.selectLatestByBusinessKey(11L, "manager:601")).thenReturn(row);
        PullTaskMemberQueryService service = service(mapper, commandService);

        PullTaskMemberQueryResult result = service.requestOrRead(request(), 1_000L);

        assertThat(result.state()).isEqualTo(PullTaskMemberQueryResult.State.AVAILABLE);
        assertThat(result.members()).singleElement().satisfies(member -> {
            assertThat(member.inGroup()).isTrue();
            assertThat(member.admin()).isFalse();
        });
        verify(commandService, never()).create(any());
    }

    @Test
    void failedQueryRetriesOnlyAfterConfiguredBackoff() {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQueryCommandService commandService =
                mock(PullTaskMemberQueryCommandService.class);
        PullTaskMemberQuery failed = row(PullTaskMemberQueryStatus.FAILED, 2_000L);
        failed.setCompletedAt(1_000L);
        failed.setErrorCode("MEMBER_QUERY_FAILED");
        PullTaskMemberQuery retry = row(PullTaskMemberQueryStatus.PENDING, 41_000L);
        retry.setId(702L);
        retry.setAttemptNo(2);
        when(mapper.selectLatestByBusinessKey(11L, "manager:601")).thenReturn(failed);
        when(commandService.create(any())).thenReturn(retry);
        PullTaskMemberQueryService service = service(mapper, commandService);

        assertThat(service.requestOrRead(request(), 10_000L).state())
                .isEqualTo(PullTaskMemberQueryResult.State.FAILED);
        assertThat(service.requestOrRead(request(), 31_000L).queryId()).isEqualTo(702L);
        verify(commandService).create(any());
    }

    @Test
    void rejectsBusinessKeyReuseWithDifferentFrozenQueryIdentity() {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQueryCommandService commandService =
                mock(PullTaskMemberQueryCommandService.class);
        PullTaskMemberQuery row = row(PullTaskMemberQueryStatus.SUCCEEDED, 2_000L);
        row.setResultJson("[]");
        row.setGroupJid("different@g.us");
        when(mapper.selectLatestByBusinessKey(11L, "manager:601")).thenReturn(row);
        PullTaskMemberQueryService service = service(mapper, commandService);

        assertThatThrownBy(() -> service.requestOrRead(request(), 1_000L))
                .hasMessageContaining("业务键冻结身份不一致");
        verify(commandService, never()).create(any());
    }

    @Test
    void frozenReadReusesPersistedActorAndTargetsWhenCandidateOrderChanges() {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQueryCommandService commandService =
                mock(PullTaskMemberQueryCommandService.class);
        PullTaskMemberQuery row = row(PullTaskMemberQueryStatus.SUCCEEDED, 2_000L);
        row.setPurpose(PullTaskMemberQueryPurpose.MANAGER_ADMIN_DISCOVERY.name());
        row.setBusinessKey("manager-admin-discovery:501");
        row.setAccountId(906L);
        row.setProtocolAccountId("frozen-906");
        row.setWsPhone("906");
        row.setTargetJidsJson("[\"906@s.whatsapp.net\",\"907@s.whatsapp.net\"]");
        row.setResultJson("[]");
        when(mapper.selectLatestByBusinessKey(11L, "manager-admin-discovery:501"))
                .thenReturn(row);
        PullTaskMemberQueryService service = service(mapper, commandService);
        PullTaskMemberQueryRequest changed = new PullTaskMemberQueryRequest(
                9L, 11L, "manager-admin-discovery:501",
                PullTaskMemberQueryPurpose.MANAGER_ADMIN_DISCOVERY,
                ProtocolAccountRef.legacyWeb("changed-907"), "123@g.us",
                List.of("907@s.whatsapp.net", "906@s.whatsapp.net"));

        PullTaskMemberQueryResult result = service.requestOrRead(changed, 1_000L);

        assertThat(result.state()).isEqualTo(PullTaskMemberQueryResult.State.AVAILABLE);
        verify(commandService, never()).create(any());
    }

    private static PullTaskMemberQueryService service(
            PullTaskMemberQueryMapper mapper,
            PullTaskMemberQueryCommandService commandService) {
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setMemberQueryTimeoutMs(30_000L);
        return new PullTaskMemberQueryService(
                mapper, commandService, new ObjectMapper(), properties);
    }

    private static PullTaskMemberQueryRequest request() {
        return new PullTaskMemberQueryRequest(
                9L, 11L, "manager:601",
                PullTaskMemberQueryPurpose.MANAGER_JOIN_MEMBERSHIP,
                ProtocolAccountRef.legacyWeb("acc-web"),
                "123@g.us", List.of("456@s.whatsapp.net"));
    }

    private static PullTaskMemberQuery row(
            PullTaskMemberQueryStatus status,
            long deadlineAt) {
        PullTaskMemberQuery row = new PullTaskMemberQuery();
        row.setId(701L);
        row.setTaskId(9L);
        row.setGroupExecutionId(11L);
        row.setBusinessKey("manager:601");
        row.setPurpose(PullTaskMemberQueryPurpose.MANAGER_JOIN_MEMBERSHIP.name());
        row.setAccountId(0L);
        row.setProtocolAccountId("acc-web");
        row.setProtocolBackend("WEB");
        row.setWsPhone("acc-web");
        row.setGroupJid("123@g.us");
        row.setTargetJidsJson("[\"456@s.whatsapp.net\"]");
        row.setQueryStatus(status.code());
        row.setDeadlineAt(deadlineAt);
        row.setAttemptNo(1);
        return row;
    }
}
