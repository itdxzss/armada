package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 粘性拉手选择、代际 CAS 与计划调用同步绑定测试。 */
@SpringJUnitConfig(PullTaskStickyPullerTransactionServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStickyPullerTransactionServiceTest {

    private static final long EXECUTION_ID = 501L;
    private static final long PULLER_A_ACCOUNT_ID = 902L;
    private static final long PULLER_B_ACCOUNT_ID = 903L;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper groupAccountMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private PullTaskStickyPullerTransactionService service;

    private PullTaskGroupAccount pullerA;
    private PullTaskGroupAccount pullerB;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, execution_status, "
                + "stage, lock_owner, lock_expires_at, version, created_at, updated_at) VALUES ("
                + EXECUTION_ID + ", 7, 100, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, "
                + "'material.txt', 2, " + PullTaskExecutionStage.PULL_EXECUTION.code()
                + ", 'worker-1', 10000, 6, 100, 100)");
        pullerA = insertPuller(PULLER_A_ACCOUNT_ID, 1);
        pullerB = insertPuller(PULLER_B_ACCOUNT_ID, 2);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void firstDispatchSelectsAtCursorAndCreatesGenerationOne() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        PullTaskPullCall call = createCall(1);

        PullTaskStickyPullerSelection selected = service.bindForDispatch(
                execution(), call, "worker-1", 1_000L);

        assertThat(selected.ready()).isTrue();
        assertThat(selected.role().getId()).isEqualTo(pullerA.getId());
        assertThat(selected.assignmentSeq()).isEqualTo(1L);
        assertExecution(pullerA.getId(), 1L, 2);
    }

    @Test
    void secondCallReusesCurrentPullerWithoutMovingCursor() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        service.bindForDispatch(execution(), createCall(1), "worker-1", 1_000L);

        PullTaskStickyPullerSelection selected = service.bindForDispatch(
                execution(), createCall(2), "worker-1", 2_000L);

        assertThat(selected.role().getId()).isEqualTo(pullerA.getId());
        assertThat(selected.assignmentSeq()).isEqualTo(1L);
        assertExecution(pullerA.getId(), 1L, 2);
    }

    @Test
    void offlineCurrentPullerSelectsNextAndIncrementsGenerationOnce() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()), List.of(protocolB()));
        service.bindForDispatch(execution(), createCall(1), "worker-1", 1_000L);

        PullTaskStickyPullerSelection selected = service.bindForDispatch(
                execution(), createCall(2), "worker-1", 2_000L);

        assertThat(selected.role().getId()).isEqualTo(pullerB.getId());
        assertThat(selected.assignmentSeq()).isEqualTo(2L);
        assertExecution(pullerB.getId(), 2L, 0);
        assertThat(groupAccountMapper.selectById(pullerA.getId()).getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
    }

    @Test
    void noReplacementClearsCurrentAssignmentAndReturnsWaitResource() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()), List.of());
        service.bindForDispatch(execution(), createCall(1), "worker-1", 1_000L);

        PullTaskStickyPullerSelection selected = service.bindForDispatch(
                execution(), createCall(2), "worker-1", 2_000L);

        assertThat(selected.ready()).isFalse();
        assertThat(selected.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskGroupExecution saved = execution();
        assertThat(saved.getActivePullerGroupAccountId()).isNull();
        assertThat(saved.getPullerAssignmentSeq()).isEqualTo(1L);
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                EXECUTION_ID, PullTaskGroupAccountRole.PULLER.code()))
                .allSatisfy(row -> assertThat(row.getReleasedAt()).isNull());
    }

    @Test
    void clearingUnavailablePullerKeepsGenerationUntilReplacementIsAssigned() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        PullTaskPullCall first = createCall(1);
        service.bindForDispatch(execution(), first, "worker-1", 1_000L);
        PullTaskPullCall boundFirst = call(1);

        assertThat(service.invalidateIfCurrent(
                execution(), boundFirst, "ACCOUNT_NOT_ONLINE", 1_500L)).isTrue();
        assertExecution(null, 1L, 2);

        PullTaskStickyPullerSelection replacement = service.bindForDispatch(
                execution(), createCall(2), "worker-1", 2_000L);
        assertThat(replacement.role().getId()).isEqualTo(pullerB.getId());
        assertThat(replacement.assignmentSeq()).isEqualTo(2L);
    }

    @Test
    void accountStateEventClearsCurrentRoleWithoutRebindingSubmittedCall() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        service.bindForDispatch(execution(), createCall(1), "worker-1", 1_000L);
        PullTaskPullCall submitted = call(1);
        callMapper.markSubmitted(submitted.getId(), "cmd-old-puller", 1_100L);

        assertThat(service.invalidateCurrentRole(
                execution(), pullerA, "ACCOUNT_UNBOUND", 1_200L)).isTrue();

        assertExecution(null, 1L, 2);
        assertThat(call(1).getPullerGroupAccountId()).isEqualTo(pullerA.getId());
        assertThat(call(1).getPullerAssignmentSeq()).isEqualTo(1L);
        assertThat(call(1).getCommandId()).isEqualTo("cmd-old-puller");
    }

    @Test
    void transportFailureDoesNotInvalidateStickyPuller() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        service.bindForDispatch(execution(), createCall(1), "worker-1", 1_000L);

        assertThat(service.invalidateIfCurrent(
                execution(), call(1), "TIMEOUT", 1_500L)).isFalse();
        assertExecution(pullerA.getId(), 1L, 2);
    }

    @Test
    void callbackFromOldGenerationCannotInvalidateNewPuller() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        service.bindForDispatch(execution(), createCall(1), "worker-1", 1_000L);
        PullTaskPullCall oldA = call(1);
        service.invalidateIfCurrent(execution(), oldA, "ACCOUNT_NOT_ONLINE", 1_100L);
        service.bindForDispatch(execution(), createCall(2), "worker-1", 1_200L);

        assertThat(service.invalidateIfCurrent(
                execution(), oldA, "RATE_LIMITED", 1_300L)).isFalse();
        assertExecution(pullerB.getId(), 2L, 0);
    }

    @Test
    void firstAAfterAtoBtoAReuseCannotInvalidateSecondA() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        service.bindForDispatch(execution(), createCall(1), "worker-1", 1_000L);
        PullTaskPullCall firstA = call(1);
        service.invalidateIfCurrent(execution(), firstA, "ACCOUNT_NOT_ONLINE", 1_100L);
        service.bindForDispatch(execution(), createCall(2), "worker-1", 1_200L);
        PullTaskPullCall callB = call(2);
        service.invalidateIfCurrent(execution(), callB, "RATE_LIMITED", 1_300L);
        service.bindForDispatch(execution(), createCall(3), "worker-1", 1_400L);

        assertThat(service.invalidateIfCurrent(
                execution(), firstA, "ACCOUNT_NOT_ONLINE", 1_500L)).isFalse();
        assertExecution(pullerA.getId(), 3L, 2);
    }

    @Test
    void plannedCallAndAttemptsReceiveTheSamePullerAndGeneration() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));
        PullTaskPullCall call = createCall(1);

        service.bindForDispatch(execution(), call, "worker-1", 1_000L);

        PullTaskPullCall savedCall = call(1);
        PullTaskPullCallMemberAttempt savedAttempt = attemptMapper.selectByCall(savedCall.getId()).get(0);
        assertThat(savedCall.getPullerGroupAccountId()).isEqualTo(pullerA.getId());
        assertThat(savedCall.getPullerAccountId()).isEqualTo(PULLER_A_ACCOUNT_ID);
        assertThat(savedCall.getPullerAssignmentSeq()).isEqualTo(1L);
        assertThat(savedAttempt.getPullerGroupAccountId()).isEqualTo(pullerA.getId());
        assertThat(savedAttempt.getPullerAssignmentSeq()).isEqualTo(1L);
    }

    @Test
    void submittedCallCannotBeRebound() {
        PullTaskPullCall call = createCall(1);
        callMapper.markSubmitted(call.getId(), "cmd-1", 900L);
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(
                PULLER_A_ACCOUNT_ID, PULLER_B_ACCOUNT_ID)))
                .thenReturn(List.of(protocolA(), protocolB()));

        PullTaskStickyPullerSelection selected = service.bindForDispatch(
                execution(), call, "worker-1", 1_000L);

        assertThat(selected.ready()).isFalse();
        assertThat(selected.result()).isEqualTo(PullTaskExecutionDispatchResult.LOST);
        assertExecution(null, 0L, 0);
        assertThat(call(1).getPullerGroupAccountId()).isNull();
    }

    private PullTaskPullCall createCall(int callSeq) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setCallSeq(callSeq);
        row.setWaveCallSeq(callSeq);
        row.setPlannedMaterialCount(1);
        row.setPlannedStationCount(0);
        row.setIdempotencyKey("sticky-call-" + callSeq);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        callMapper.insertPlanned(row);

        PullTaskPullCallMemberAttempt attempt = new PullTaskPullCallMemberAttempt();
        attempt.setTaskId(100L);
        attempt.setGroupExecutionId(EXECUTION_ID);
        attempt.setPullCallId(row.getId());
        attempt.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        attempt.setParticipantRefId(600L + callSeq);
        attempt.setTargetPhone("861390000000" + callSeq);
        attempt.setTargetJid("861390000000" + callSeq + "@s.whatsapp.net");
        attempt.setAttemptNo(1);
        attempt.setFailureCountBefore(0L);
        attempt.setCreatedAt(100L);
        attempt.setUpdatedAt(100L);
        attemptMapper.insertPlanned(attempt);
        return row;
    }

    private PullTaskGroupAccount insertPuller(long accountId, int roleSeq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setAccountId(accountId);
        row.setAccountPhone("8613800000" + accountId);
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(2);
        row.setOccupiedAt(100L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        groupAccountMapper.insert(row);
        groupAccountMapper.updateMembership(
                row.getId(), PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 100L, 100L);
        return row;
    }

    private PullTaskGroupExecution execution() {
        TenantContext.set(7L);
        return executionMapper.selectById(EXECUTION_ID);
    }

    private PullTaskPullCall call(int callSeq) {
        TenantContext.set(7L);
        return callMapper.selectByExecution(EXECUTION_ID).stream()
                .filter(row -> row.getCallSeq() == callSeq)
                .findFirst().orElseThrow();
    }

    private void assertExecution(Long pullerId, long assignmentSeq, int nextPullerIndex) {
        PullTaskGroupExecution saved = execution();
        assertThat(saved.getActivePullerGroupAccountId()).isEqualTo(pullerId);
        assertThat(saved.getPullerAssignmentSeq()).isEqualTo(assignmentSeq);
        assertThat(saved.getNextPullerIndex()).isEqualTo(nextPullerIndex);
    }

    private static ProtocolAccountRef protocolA() {
        return protocol(PULLER_A_ACCOUNT_ID);
    }

    private static ProtocolAccountRef protocolB() {
        return protocol(PULLER_B_ACCOUNT_ID);
    }

    private static ProtocolAccountRef protocol(long accountId) {
        return new ProtocolAccountRef(
                accountId, ProtocolBackend.WEB,
                "puller-" + accountId, "8613800000" + accountId);
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_sticky_puller_test");
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor,
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper groupAccountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskPullCallMapper callMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean PullTaskStickyPullerTransactionService stickyPullerService(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper groupAccountMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                AccountProtocolLookupService accountLookup) {
            return new PullTaskStickyPullerTransactionService(
                    executionMapper, groupAccountMapper, callMapper, attemptMapper, accountLookup);
        }
    }
}
