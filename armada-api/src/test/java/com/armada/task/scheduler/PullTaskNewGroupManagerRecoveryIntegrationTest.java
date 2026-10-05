package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 真实 H2 事务回归：管理员失效后不能把本行建群人选作替补管理员。 */
@SpringJUnitConfig(PullTaskResourceRecoveryTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskNewGroupManagerRecoveryIntegrationTest {

    private static final ProtocolAccountRef CREATOR = account(905L);
    private static final ProtocolAccountRef REPLACEMENT = account(906L);

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private GroupExecutionAccountSelector promoterSelector;
    @Autowired private PullTaskResourceRecoveryTransactionService service;

    private long executionId;
    private PullTaskGroupAccount creator;
    private PullTaskGroupAccount oldManager;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, promoterSelector);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        execute("INSERT INTO pull_task (id, tenant_id, task_type, task_name, mode, "
                + "creation_mode, status, config_json, created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'creator-replacement', 'NORMAL_LINK', "
                + "'NEW_GROUP', 'EXECUTING', '{}', 100, 100)");
        execute("INSERT INTO pull_task_standard_setting (tenant_id, task_id, auto_start, "
                + "material_admin_timing, pull_count_min, pull_count_max, pull_interval_seconds, "
                + "puller_count_per_group, station_count_per_call, concurrent_group_count, "
                + "puller_risk_minutes, required_manager_count, manager_group_id, puller_group_id, "
                + "manager_group_name, puller_group_name, created_at, updated_at) VALUES "
                + "(7, 100, 1, 1, 1, 2, 1, 2, 0, 1, 5, 1, 88, 89, 'manager', 'puller', 100, 100)");
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setTaskId(100L);
        execution.setSeq(1);
        execution.setGroupLinkId(9_001L);
        execution.setNormalizedLink("chat.whatsapp.com/AAAA");
        execution.setInviteCode("AAAA");
        execution.setSourceLinkLineNo(1);
        execution.setSourceFileIndex(1);
        execution.setSourceFileName("material.txt");
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);
        execution.setCreatedAt(100L);
        execution.setUpdatedAt(100L);
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        executionId = execution.getId();
        execute("UPDATE pull_task_group_execution SET execution_status=3, stage=3, version=6, "
                + "group_jid='120363group@g.us', wait_resource_type=1, "
                + "reason_code='MANAGER_ADMIN_ACTOR_UNAVAILABLE', next_run_at=0 WHERE id=" + executionId);
        creator = role(CREATOR, PullTaskGroupAccountRole.PROMOTER, 1);
        oldManager = role(account(901L), PullTaskGroupAccountRole.MANAGER, 1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void replacementSkipsCreatorInTheSameConfiguredGroup() {
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L))
                .thenReturn(List.of(CREATOR, REPLACEMENT));

        assertThat(recover(600L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertReplaced(REPLACEMENT.armadaAccountId());
    }

    @Test
    void onlyCreatorAvailableWaitsUntilAnIndependentManagerArrives() {
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(CREATOR));

        assertThat(recover(600L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        assertThat(managers()).hasSize(1);
        assertThat(executionMapper.selectById(executionId).getReasonCode())
                .isEqualTo("MANAGER_UNAVAILABLE");
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L))
                .thenReturn(List.of(CREATOR, REPLACEMENT));

        assertThat(recover(2_601L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertReplaced(REPLACEMENT.armadaAccountId());
    }

    @ParameterizedTest
    @EnumSource(value = PullTaskGroupAccountAvailability.class, names = {"AVAILABLE", "OFFLINE"})
    void existingCreatorManagerIsReplacedWithoutChangingCreatorFacts(
            PullTaskGroupAccountAvailability availability) {
        PullTaskGroupAccount conflicted = role(CREATOR, PullTaskGroupAccountRole.MANAGER, 2);
        accountMapper.markUnavailable(conflicted.getId(), availability.code(), null, null, 550L);
        when(accountLookup.findEligibleManagerProtocolRefs(anyList())).thenReturn(List.of(CREATOR));
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L))
                .thenReturn(List.of(CREATOR, REPLACEMENT));

        assertThat(recover(600L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertReplaced(REPLACEMENT.armadaAccountId());
        assertThat(accountMapper.selectById(conflicted.getId()).getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.REMOVED.code());
        assertThat(accountMapper.selectById(conflicted.getId()).getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
    }

    @Test
    void existingConflictIsRemovedEvenWhenNoReplacementIsAvailable() {
        PullTaskGroupAccount conflicted = role(CREATOR, PullTaskGroupAccountRole.MANAGER, 2);
        when(accountLookup.findEligibleManagerProtocolRefs(anyList())).thenReturn(List.of(CREATOR));
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(CREATOR));

        assertThat(recover(600L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        assertThat(managers()).hasSize(2);
        assertThat(accountMapper.selectById(conflicted.getId()).getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.REMOVED.code());
        assertThat(accountMapper.selectById(creator.getId()).getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(executionMapper.selectById(executionId).getReasonCode())
                .isEqualTo("MANAGER_UNAVAILABLE");
    }

    @Test
    void staleLeaseRollsBackConflictRemovalAndReplacement() {
        PullTaskGroupAccount conflicted = role(CREATOR, PullTaskGroupAccountRole.MANAGER, 2);
        when(accountLookup.findEligibleManagerProtocolRefs(anyList())).thenReturn(List.of(CREATOR));
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(REPLACEMENT));
        PullTaskGroupExecution candidate = claim(600L);
        candidate.setVersion(candidate.getVersion() - 1);

        assertThat(service.recover(candidate, "worker", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.LOST);

        TenantContext.set(7L);
        assertThat(managers()).hasSize(2);
        assertThat(accountMapper.selectById(conflicted.getId()).getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
    }

    @Test
    void pastedLinkPromoterIsNotTreatedAsANewGroupCreator() throws SQLException {
        execute("UPDATE pull_task SET creation_mode='PASTED_LINK' WHERE id=100");
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(CREATOR));

        assertThat(recover(600L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertReplaced(CREATOR.armadaAccountId());
    }

    @Test
    void laterPromoterDoesNotBecomeAnotherFrozenCreator() {
        role(REPLACEMENT, PullTaskGroupAccountRole.PROMOTER, 2);
        when(accountLookup.findOnlineEligibleManagersByGroupId(88L))
                .thenReturn(List.of(CREATOR, REPLACEMENT));

        assertThat(recover(600L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertReplaced(REPLACEMENT.armadaAccountId());
    }

    private void assertReplaced(long accountId) {
        TenantContext.set(7L);
        List<PullTaskGroupAccount> managers = managers();
        PullTaskGroupAccount replacement = managers.get(managers.size() - 1);
        assertThat(replacement.getAccountId()).isEqualTo(accountId);
        assertThat(replacement.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        assertThat(replacement.getAdminStatus()).isEqualTo(PullTaskGroupAccountAdminStatus.PENDING.code());
        assertThat(accountMapper.selectById(oldManager.getId()).getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.REMOVED.code());
        assertThat(accountMapper.selectById(creator.getId()).getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(executionMapper.selectById(executionId).getStage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
    }

    private List<PullTaskGroupAccount> managers() {
        return accountMapper.selectByExecutionAndRole(executionId, PullTaskGroupAccountRole.MANAGER.code());
    }

    private PullTaskExecutionDispatchResult recover(long now) {
        return service.recover(claim(now), "worker", now, 2_000L);
    }

    private PullTaskGroupExecution claim(long now) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, "worker", now + 500L),
                List.of(new PullTaskExecutionClaimState(PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        List.of(PullTaskExecutionStage.MANAGER_ADMIN.code()))),
                new PullTaskExecutionClaimCriteria.Parent(PullTaskType.STANDARD.name(),
                        "NORMAL_LINK", PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed("worker", now).get(0);
    }

    private PullTaskGroupAccount role(ProtocolAccountRef account, PullTaskGroupAccountRole role, int seq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(account.armadaAccountId());
        row.setAccountPhone(account.wsPhone());
        row.setRoleType(role.code());
        row.setRoleSeq(seq);
        row.setSourceType(PullTaskGroupAccountSource.INITIAL.code());
        row.setSelectionMode(PullTaskSelectionMode.AUTOMATIC.code());
        row.setEntryMode(PullTaskAccountEntryMode.JOIN_BY_LINK.code());
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        accountMapper.insert(row);
        accountMapper.updateMembership(row.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 510L, 510L);
        return row;
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(id, ProtocolBackend.ANDROID, "account-" + id, "8613800000" + id);
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
