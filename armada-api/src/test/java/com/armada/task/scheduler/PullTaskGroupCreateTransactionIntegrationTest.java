package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupProfileCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 新群建群七步的真实 Mapper 检查点与角色事实测试。 */
@SpringJUnitConfig(PullTaskGroupCreateTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupCreateTransactionIntegrationTest {

    private static final long NOW = 1_000L;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private PullTaskGroupCreateTransactionService transactions;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private GroupLinkRegistryService groupRegistry;
    @Autowired private PullTaskGroupProfileDispatcher profileDispatcher;
    @Autowired private ProtocolCommandOutboxService outboxService;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(
                dataSource, task(), standardSetting(), enabledGroupSetting(), execution());
        reset(accountLookup, groupRegistry, profileDispatcher, outboxService);
        when(outboxService.enqueuePullTaskGroupProfileCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("profile-repair"), 1));
        when(accountLookup.findOnlinePullTaskAccountsStrictByGroupId(16L))
                .thenReturn(List.of(account(901L)));
        when(accountLookup.findOnlinePullTaskAccountsStrictByGroupId(11L))
                .thenReturn(List.of(account(902L)));
        when(accountLookup.findOnlinePullTaskAccountsStrictByGroupId(13L))
                .thenReturn(List.of(account(903L), account(904L)));
        when(accountLookup.findActiveProtocolRef(901L))
                .thenReturn(Optional.of(account(901L)));
        when(accountLookup.findOnlineProtocolRefs(List.of(901L)))
                .thenReturn(List.of(account(901L)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NEW_GROUP", "SIMPLE_NEW_GROUP"})
    void persistsRolesResultsInviteAndRegistrationBeforeManagerJoin(String mode) {
        jdbc.update("UPDATE pull_task SET creation_mode=? WHERE id=1", mode);
        GroupMetadataResult verifiedMetadata = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, true, false, null, null,
                false, null, false, false, List.of());
        PullTaskGroupExecution selectCandidate = executionMapper.selectById(11L);

        assertThat(transactions.prepareRoles(selectCandidate, NOW, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskGroupCreateStep.CREATE_GROUP.code());
        assertThat(stringColumn("create_operation_id", "pull_task_group_execution", 11L))
                .isEqualTo("ptgc:7:11");
        assertThat(stringColumn("group_subject", "pull_task_group_execution", 11L))
                .isEqualTo("印度料子包");
        assertThat(roleAccounts(PullTaskGroupAccountRole.PROMOTER)).containsExactly(901L);
        assertThat(roleAccounts(PullTaskGroupAccountRole.MANAGER)).containsExactly(902L);
        assertThat(roleAccounts(PullTaskGroupAccountRole.STATION)).containsExactly(903L, 904L);

        PullTaskGroupExecution createCandidate = reclaim(NOW + 1);
        var prepared = transactions.prepareCreate(createCandidate, NOW + 1, 2_000L);
        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.command().account().armadaAccountId()).isEqualTo(901L);
        assertThat(prepared.command().participants()).containsExactly(
                "8613800000902", "8613800000903", "8613800000904");
        assertThat(prepared.command().operationId()).isEqualTo("ptgc:7:11");

        GroupCreateResult created = new GroupCreateResult(
                "120363group@g.us", true, List.of(
                new GroupCreateParticipantResult(
                        "8613800000902@s.whatsapp.net", "OK", "200"),
                new GroupCreateParticipantResult(
                        "8613800000903@s.whatsapp.net", "SUCCESS", null),
                new GroupCreateParticipantResult(
                        "8613800000904@s.whatsapp.net", "PRIVACY_BLOCKED", "403")));
        assertThat(transactions.completeCreate(
                createCandidate, created, NOW + 4_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(membership(PullTaskGroupAccountRole.PROMOTER, 901L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(membership(PullTaskGroupAccountRole.MANAGER, 902L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(membership(PullTaskGroupAccountRole.STATION, 903L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(membership(PullTaskGroupAccountRole.STATION, 904L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());

        PullTaskGroupExecution profileCandidate = reclaim(NOW + 2);
        seedProfileAction(3, NOW);
        var profile = transactions.prepareProfile(profileCandidate, 2_000L, NOW + 2);
        assertThat(profile.ready()).isTrue();
        assertThat(transactions.completeProfile(
                profileCandidate, profile, verifiedMetadata, NOW + 5_000L, NOW + 2))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(profileDispatcher).dispatchIfDue(
                eq(profileCandidate), eq(com.armada.task.model.enums
                        .PullTaskGroupSettingTiming.BEFORE_PULL), eq(NOW + 2));

        PullTaskGroupExecution inviteCandidate = reclaim(NOW + 3);
        assertThat(transactions.prepareInvite(inviteCandidate, 2_000L, NOW + 3).ready())
                .isTrue();
        assertThat(transactions.completeInvite(
                inviteCandidate,
                new GroupInviteResult(
                        "120363group@g.us", "Invite123", null),
                NOW + 6_000L,
                NOW + 3))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(stringColumn("normalized_link", "pull_task_group_execution", 11L))
                .isEqualTo("chat.whatsapp.com/Invite123");

        PullTaskGroupExecution settingsCandidate = reclaim(NOW + 4);
        var finalCheck = transactions.prepareProfile(settingsCandidate, 2_000L, NOW + 4);
        assertThat(transactions.completeProfile(
                settingsCandidate, finalCheck, verifiedMetadata, NOW + 7_000L, NOW + 4))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        when(groupRegistry.registerSelfBuiltGroup(
                eq("120363group@g.us"), eq("印度料子包"), eq(901L),
                eq("8613800000901"), eq(3), anyLong())).thenReturn(21L);
        PullTaskGroupExecution registerCandidate = reclaim(NOW + 5);
        assertThat(transactions.registerGroup(registerCandidate, NOW + 5))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(intColumn("stage", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(longColumn("group_link_id", "pull_task_group_execution", 11L))
                .isEqualTo(21L);
        verify(groupRegistry).registerKnownMembership(
                21L, "120363group@g.us", 902L, false, NOW + 5);
        verify(groupRegistry).registerKnownMembership(
                21L, "120363group@g.us", 903L, false, NOW + 5);
    }

    @Test
    void numberedSubjectSurvivesRolePreparationAndDefiniteCreateRetry() {
        jdbc.update("UPDATE pull_task_group_execution SET group_subject='印度料子包-1' WHERE id=11");
        assertThat(transactions.prepareRoles(executionMapper.selectById(11L), NOW, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        PullTaskGroupExecution candidate = reclaim(NOW + 1);
        var first = transactions.prepareCreate(candidate, NOW + 1, 2_000L);
        assertThat(first.command().subject()).isEqualTo("印度料子包-1");

        assertThat(transactions.failCreate(candidate,
                new ProtocolException(ProtocolErrorCode.ACCOUNT_NOT_ONLINE, "offline"), 2_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        var retried = transactions.prepareCreate(reclaim(NOW + 2_001L), NOW + 2_001L, 2_000L);
        assertThat(retried.command().subject()).isEqualTo(first.command().subject());
        assertThat(retried.command().operationId()).isEqualTo(first.command().operationId());
    }

    @Test
    void numberedProfileRequiresTheActualNumberedNameBeforeAdvancing() {
        jdbc.update("UPDATE pull_task_group_execution SET group_subject='印度料子包-1' WHERE id=11");
        var candidate = profileCandidate(3, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 1);
        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.subject()).isEqualTo("印度料子包-1");

        assertThat(transactions.completeProfile(candidate, prepared, metadata("完整简介"), NOW + 2_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
        assertThat(stringColumn("profile_verified_command_id", "pull_task_group_execution", 11L)).isNull();

        var resumed = reclaim(NOW + 2_001L);
        var ready = transactions.prepareProfile(resumed, 2_000L, NOW + 2_001L);
        GroupMetadataResult numbered = new GroupMetadataResult(
                "120363group@g.us", "印度料子包-1", "完整简介", null, null, null, false,
                null, null, null, null, null, null, false, null, false, false, List.of());
        assertThat(transactions.completeProfile(resumed, ready, numbered, NOW + 4_000L, NOW + 2_001L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(stringColumn("profile_verified_command_id", "pull_task_group_execution", 11L))
                .isEqualTo("profile-1");
    }

    @Test
    void unconfirmedCreatePausesWithoutChangingOperationIdOrAttemptCount() {
        assertThat(transactions.prepareRoles(executionMapper.selectById(11L), NOW, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        PullTaskGroupExecution createCandidate = reclaim(NOW + 1);

        assertThat(transactions.failCreate(
                createCandidate,
                new ProtocolException(
                        ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED,
                        "timeout after submit"),
                2_000L,
                NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isOne();
        assertThat(intColumn("create_attempt_count", "pull_task_group_execution", 11L))
                .isZero();
        assertThat(stringColumn("create_operation_id", "pull_task_group_execution", 11L))
                .isEqualTo("ptgc:7:11");
        assertThat(stringColumn("reason_code", "pull_task_group_execution", 11L))
                .isEqualTo("GROUP_CREATE_RESULT_UNCONFIRMED");
    }

    @Test
    void submittedProfileDoesNotAdvanceUntilItsResultCanBeVerified() {
        var candidate = profileCandidate(2, NOW);
        var pending = transactions.prepareProfile(candidate, 2_000L, NOW + 1);
        assertThat(pending.ready()).isFalse();
        assertThat(pending.completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
        assertThat(intColumn("stage", "pull_task_group_execution", 11L)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_pull_call", Integer.class)).isZero();
    }

    @Test
    void offlineCreatorWaitsWithoutPausingOrChangingTheCreatedGroup() {
        var candidate = profileCandidate(5, NOW);
        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of());
        org.mockito.Mockito.clearInvocations(profileDispatcher);

        var waiting = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);

        assertThat(waiting.ready()).isFalse();
        assertThat(waiting.completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isZero();
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
        assertThat(stringColumn("group_jid", "pull_task_group_execution", 11L))
                .isEqualTo("120363group@g.us");
        assertThat(stringColumn("reason_code", "pull_task_group_execution", 11L))
                .isEqualTo("GROUP_CREATOR_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(profileDispatcher);

        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of(account(901L)));
        var resumed = reclaim(NOW + 34_000L);
        var ready = transactions.prepareProfile(resumed, 2_000L, NOW + 34_000L);
        assertThat(ready.ready()).isTrue();
        assertThat(transactions.completeProfile(resumed, ready, metadata("完整简介"),
                NOW + 35_000L, NOW + 34_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(5);
        assertThat(intColumn("create_attempt_count", "pull_task_group_execution", 11L)).isZero();
    }

    @Test
    void unavailableMetadataAfterTimeoutKeepsWaitingWithoutResubmitting() {
        var candidate = profileCandidate(5, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        org.mockito.Mockito.clearInvocations(profileDispatcher);

        assertThat(transactions.completeProfile(candidate, ready, null,
                NOW + 35_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isZero();
        assertThat(stringColumn("reason_code", "pull_task_group_execution", 11L))
                .isEqualTo("GROUP_PROFILE_UNCONFIRMED");
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isOne();
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(profileDispatcher);
    }

    @Test
    void unknownProfileRepairsOnlyMissingDescriptionThenVerifiesBeforeContinuing() {
        var candidate = profileCandidate(5, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);

        assertThat(transactions.completeProfile(candidate, ready, metadata("旧简介"),
                NOW + 35_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ProtocolPullTaskGroupProfileCommandRequest>> commands =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskGroupProfileCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.actionId()).isEqualTo(71L);
            assertThat(command.manager().armadaAccountId()).isEqualTo(901L);
            assertThat(command.repair()).isEqualTo(new ProtocolPullTaskGroupProfileCommandRequest.Repair(false, true, false, false));
        });
        assertThat(intColumn("action_status", "pull_task_account_action", 71L)).isEqualTo(2);
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isEqualTo(2);
        assertThat(actionMapper.transitionManagerAdminResult(
                71L, "profile-1", 1, List.of(2, 5), 3, false, null, null, NOW + 32_000L)).isZero();
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isZero();
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_pull_call", Integer.class)).isZero();
        assertThat(transactions.completeProfile(candidate, ready, metadata("完整简介"),
                NOW + 35_000L, NOW + 32_000L)).isEqualTo(PullTaskExecutionDispatchResult.LOST);

        jdbc.update("UPDATE pull_task_account_action SET action_status=3 WHERE id=71");
        var resumed = reclaim(NOW + 35_000L);
        var verified = transactions.prepareProfile(resumed, 2_000L, NOW + 35_000L);
        assertThat(transactions.completeProfile(resumed, verified, metadata("完整简介"),
                NOW + 36_000L, NOW + 35_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(stringColumn("profile_verified_command_id", "pull_task_group_execution", 11L))
                .isEqualTo("profile-repair");
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(5);
        assertThat(stringColumn("group_jid", "pull_task_group_execution", 11L)).isEqualTo("120363group@g.us");
        assertThat(intColumn("create_attempt_count", "pull_task_group_execution", 11L)).isZero();
    }

    @Test
    void definiteOfflineFailureRetriesCompleteSettingsOnlyAfterCreatorReturns() {
        var candidate = profileCandidate(4, NOW);
        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of());
        assertThat(transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L).ready()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(outboxService);
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isOne();

        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of(account(901L)));
        var resumed = reclaim(NOW + 35_000L);
        assertThat(transactions.prepareProfile(resumed, 2_000L, NOW + 35_000L).ready()).isFalse();
        verify(outboxService).enqueuePullTaskGroupProfileCommands(List.of(
                new ProtocolPullTaskGroupProfileCommandRequest(7L, 1L, 11L, 71L, account(901L), null)));
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isEqualTo(2);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"5,3", "2,1"})
    void exhaustedOrStillSubmittedProfileIsNeverRepaired(int status, int attempts) {
        var candidate = profileCandidate(status, NOW);
        jdbc.update("UPDATE pull_task_account_action SET attempt_no=? WHERE id=71", attempts);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        assertThat(transactions.completeProfile(candidate, ready, metadata("旧简介"),
                NOW + 35_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        org.mockito.Mockito.verifyNoInteractions(outboxService);
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isEqualTo(attempts);
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isOne();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "manual_paused=1", "execution_status=6", "version=version+1", "lock_expires_at=1000"})
    void lifecycleChangeDuringMetadataReadPreventsRepair(String change) {
        var candidate = profileCandidate(5, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        jdbc.update("UPDATE pull_task_group_execution SET " + change + " WHERE id=11");

        assertThat(transactions.completeProfile(candidate, ready, metadata("旧简介"),
                NOW + 35_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.LOST);
        org.mockito.Mockito.verifyNoInteractions(outboxService);
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isOne();
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
    }

    @Test
    void creatorDisconnectingDuringReadKeepsUnknownActionForNextOnlineAttempt() {
        var candidate = profileCandidate(5, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of());

        assertThat(transactions.completeProfile(candidate, ready, metadata("旧简介"),
                NOW + 35_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        org.mockito.Mockito.verifyNoInteractions(outboxService);
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isOne();
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isZero();
    }

    @Test
    void protocolSuccessWithWrongDescriptionNeverReleasesMaterialPulling() {
        var candidate = profileCandidate(3, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        assertThat(ready.ready()).isTrue();
        assertThat(transactions.completeProfile(candidate, ready, metadata("旧简介"), NOW + 35_000L, NOW + 31_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isOne();
        assertThat(stringColumn("group_jid", "pull_task_group_execution", 11L)).isEqualTo("120363group@g.us");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_pull_call", Integer.class)).isZero();
    }

    @Test
    void unknownProfileRequiresMatchingLiveMetadataAndKeepsUnknownActionFact() {
        var candidate = profileCandidate(5, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 1);
        assertThat(transactions.completeProfile(candidate, ready, metadata("完整简介"), NOW + 5_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(intColumn("action_status", "pull_task_account_action", 71L)).isEqualTo(5);
        assertThat(stringColumn("profile_verified_command_id", "pull_task_group_execution", 11L)).isEqualTo("profile-1");
        assertThat(longColumn("profile_verified_at", "pull_task_group_execution", 11L)).isEqualTo(NOW + 1);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(5);
        assertThat(transactions.completeProfile(candidate, ready, metadata("完整简介"), NOW + 5_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.LOST);
    }

    @Test
    void expiredQueryAndLateAttemptCannotAdvanceTheExecution() {
        var candidate = profileCandidate(3, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 1);
        jdbc.update("UPDATE pull_task_account_action SET command_id='new-attempt', attempt_no=2 WHERE id=71");
        assertThat(transactions.completeProfile(candidate, ready, metadata("完整简介"), NOW + 5_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.LOST);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
    }

    @Test
    void pausedOrCanceledExecutionCannotEnqueueAProfileCommand() {
        var stale = profileCandidate(3, NOW);
        jdbc.update("UPDATE pull_task_group_execution SET manual_paused=1,version=version+1 WHERE id=11");
        org.mockito.Mockito.clearInvocations(profileDispatcher);
        var result = transactions.prepareProfile(stale, 2_000L, NOW + 1);
        assertThat(result.completedResult()).isEqualTo(PullTaskExecutionDispatchResult.LOST);
        org.mockito.Mockito.verifyNoInteractions(profileDispatcher);
    }

    @Test
    void brokenActionIdentityPausesInsteadOfResettingItsTimeoutForever() {
        var candidate = profileCandidate(2, NOW);
        jdbc.update("UPDATE pull_task_account_action SET submitted_at=NULL WHERE id=71");
        var result = transactions.prepareProfile(candidate, 2_000L, NOW + 1);
        assertThat(result.ready()).isFalse();
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isOne();
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(4);
    }

    @Test
    void canceledActionAndExpiredLeaseCannotReleaseTheGate() {
        var candidate = profileCandidate(3, NOW);
        var ready = transactions.prepareProfile(candidate, 2_000L, NOW + 1);
        jdbc.update("UPDATE pull_task_account_action SET action_status=6 WHERE id=71");
        assertThat(transactions.completeProfile(candidate, ready, metadata("完整简介"), NOW + 5_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.LOST);
        jdbc.update("UPDATE pull_task_account_action SET action_status=3 WHERE id=71");
        jdbc.update("UPDATE pull_task_group_execution SET lock_expires_at=? WHERE id=11", NOW);
        assertThat(transactions.completeProfile(candidate, ready, metadata("完整简介"), NOW + 5_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.LOST);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
    }

    @Test
    void historicalRegisterStepWithoutEvidenceReturnsToVerificationWithoutRecreatingGroup() {
        var candidate = profileCandidate(3, NOW);
        jdbc.update("UPDATE pull_task_group_execution SET create_step=7 WHERE id=11");
        assertThat(transactions.registerGroup(reclaim(NOW + 1), NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L)).isEqualTo(6);
        assertThat(stringColumn("group_jid", "pull_task_group_execution", 11L)).isEqualTo("120363group@g.us");
        org.mockito.Mockito.verifyNoInteractions(groupRegistry);
    }

    @Test
    void simplifiedNewGroupAllowsEmptyDescriptionAndRequiresCreatorPermissionReadback() {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        jdbc.update("UPDATE pull_task_standard_group_setting SET group_description=NULL WHERE task_id=1");
        var candidate = profileCandidate(3, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        assertThat(prepared.ready()).isTrue();
        GroupMetadataResult verified = new GroupMetadataResult("120363group@g.us", "印度料子包", "",
                null, null, null, false, null, null, true, false, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, verified,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedCommandId()).isEqualTo("profile-1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {"false,false", "true,true", "null,false", "true,null"},
            nullValues = "null")
    void simplifiedNewGroupCannotAdvanceWithClosedOrUnknownPermissions(Boolean memberAdd, Boolean approval) {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        var candidate = profileCandidate(3, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        GroupMetadataResult unverified = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, memberAdd, approval, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, unverified,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskGroupExecution saved = executionMapper.selectById(11L);
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
        assertThat(saved.getCreateStep()).isEqualTo(PullTaskGroupCreateStep.APPLY_PROFILE.code());
        assertThat(saved.getProfileVerifiedAt()).isNull();
        assertThat(saved.getManualPaused()).isOne();
        org.mockito.Mockito.verifyNoInteractions(groupRegistry);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,true", "false,true"})
    void simplifiedUnknownResultRepairsExplicitPermissionMismatchThenRequiresReadback(
            boolean memberAdd, boolean approval) {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        var candidate = profileCandidate(5, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        GroupMetadataResult mismatch = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, memberAdd, approval, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, mismatch,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ProtocolPullTaskGroupProfileCommandRequest>> commands =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskGroupProfileCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.repair().subject()).isFalse();
            assertThat(command.repair().description()).isFalse();
            assertThat(command.repair().memberPermissions()).isTrue();
        });
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isEqualTo(2);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        assertThat(executionMapper.selectById(11L).getManualPaused()).isZero();
        jdbc.update("UPDATE pull_task_account_action SET action_status=3 WHERE id=71");
        var resumed = reclaim(NOW + 35_000L);
        var verified = transactions.prepareProfile(resumed, 2_000L, NOW + 35_000L);
        GroupMetadataResult correct = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, true, false, null, null,
                false, null, false, false, List.of());
        assertThat(transactions.completeProfile(resumed, verified, correct,
                NOW + 36_000L, NOW + 35_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedCommandId()).isEqualTo("profile-repair");
    }

    @Test
    void simplifiedPermissionRepairStopsAtThirdAttempt() {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        var candidate = profileCandidate(5, NOW);
        jdbc.update("UPDATE pull_task_account_action SET attempt_no=3 WHERE id=71");
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        GroupMetadataResult mismatch = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, false, false, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, mismatch,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isEqualTo(3);
        assertThat(executionMapper.selectById(11L).getManualPaused()).isOne();
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(outboxService, groupRegistry);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false,true,完整简介", "true,true,完整简介",
            "false,false,完整简介", "true,false,完整简介",
            "false,true,旧简介", "true,true,旧简介"})
    void simplifiedUnknownAvatarRequiresAvatarRepairSuccessBeforeProfileVerification(
            boolean deleteCreator, boolean memberAdd, String description) {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        jdbc.update("UPDATE pull_task_standard_group_setting SET avatar_file_key='frozen-avatar.png' WHERE task_id=1");
        var candidate = profileCandidate(5, NOW);
        // 本例从建群结果恢复，分别覆盖后续注销开关，不重复测试创建者预留流程。
        jdbc.update("UPDATE pull_task SET is_creator_delete_after_takeover=? WHERE id=1",
                deleteCreator ? 1 : 0);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        GroupMetadataResult observed = new GroupMetadataResult("120363group@g.us", "印度料子包", description,
                null, null, null, false, null, null, memberAdd, false, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, observed,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ProtocolPullTaskGroupProfileCommandRequest>> commands =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskGroupProfileCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.repair().avatar()).isTrue();
            assertThat(command.repair().subject()).isFalse();
            assertThat(command.repair().description()).isEqualTo(!"完整简介".equals(description));
            assertThat(command.repair().memberPermissions()).isEqualTo(!memberAdd);
        });
        PullTaskGroupExecution awaiting = executionMapper.selectById(11L);
        assertThat(awaiting.getProfileVerifiedAt()).isNull();
        assertThat(awaiting.getProfileVerifiedCommandId()).isNull();
        assertThat(awaiting.getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
        assertThat(awaiting.getCreateStep()).isEqualTo(PullTaskGroupCreateStep.APPLY_PROFILE.code());
        assertThat(awaiting.getManualPaused()).isZero();
        org.mockito.Mockito.verifyNoInteractions(groupRegistry);

        jdbc.update("UPDATE pull_task_account_action SET action_status=3 WHERE id=71");
        var resumed = reclaim(NOW + 35_000L);
        var verified = transactions.prepareProfile(resumed, 2_000L, NOW + 35_000L);
        GroupMetadataResult correct = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, true, false, null, null,
                false, null, false, false, List.of());
        assertThat(transactions.completeProfile(resumed, verified, correct,
                NOW + 36_000L, NOW + 35_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedCommandId()).isEqualTo("profile-repair");
        assertThat(executionMapper.selectById(11L).getCreateStep())
                .isEqualTo(PullTaskGroupCreateStep.CAPTURE_INVITE_LINK.code());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 3})
    void simplifiedUnknownAvatarCannotReuseMatchingReadbackAfterRepair(int attempt) {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        jdbc.update("UPDATE pull_task_standard_group_setting SET avatar_file_key='frozen-avatar.png' WHERE task_id=1");
        var candidate = profileCandidate(5, NOW);
        jdbc.update("UPDATE pull_task_account_action SET attempt_no=? WHERE id=71", attempt);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        GroupMetadataResult correct = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, true, false, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, correct,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        assertThat(executionMapper.selectById(11L).getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
        assertThat(executionMapper.selectById(11L).getManualPaused()).isEqualTo(attempt == 3 ? 1 : 0);
        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isEqualTo(3);
        if (attempt == 3) {
            org.mockito.Mockito.verifyNoInteractions(outboxService);
        }
        org.mockito.Mockito.verifyNoInteractions(groupRegistry);
    }

    @Test
    void legacyNewGroupAvatarUnknownStillUsesExistingReadbackRule() {
        jdbc.update("UPDATE pull_task_standard_group_setting SET avatar_file_key='frozen-avatar.png' WHERE task_id=1");
        var candidate = profileCandidate(5, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);

        assertThat(transactions.completeProfile(candidate, prepared, metadata("完整简介"),
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedCommandId()).isEqualTo("profile-1");
        org.mockito.Mockito.verifyNoInteractions(outboxService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {"false,null", "null,true"}, nullValues = "null")
    void simplifiedAvatarRepairDoesNotInferMissingPermissionReadback(Boolean memberAdd, Boolean approval) {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        jdbc.update("UPDATE pull_task_standard_group_setting SET avatar_file_key='frozen-avatar.png' WHERE task_id=1");
        var candidate = profileCandidate(5, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        GroupMetadataResult unknown = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, memberAdd, approval, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, unknown,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ProtocolPullTaskGroupProfileCommandRequest>> commands =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskGroupProfileCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command ->
                assertThat(command.repair()).isEqualTo(
                        new ProtocolPullTaskGroupProfileCommandRequest.Repair(false, false, false, true)));

        jdbc.update("UPDATE pull_task_account_action SET action_status=3 WHERE id=71");
        var resumed = reclaim(NOW + 62_000L);
        var verified = transactions.prepareProfile(resumed, 2_000L, NOW + 62_000L);
        assertThat(transactions.completeProfile(resumed, verified, unknown,
                NOW + 63_000L, NOW + 62_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        assertThat(executionMapper.selectById(11L).getManualPaused()).isOne();
        org.mockito.Mockito.verifyNoInteractions(groupRegistry);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "null,false", "true,null", "false,null", "null,true", "null,null"}, nullValues = "null")
    void simplifiedUnknownPermissionFieldsNeverTriggerPermissionRepair(Boolean memberAdd, Boolean approval) {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        var candidate = profileCandidate(5, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);
        GroupMetadataResult unknown = new GroupMetadataResult("120363group@g.us", "印度料子包", "完整简介",
                null, null, null, false, null, null, memberAdd, approval, null, null,
                false, null, false, false, List.of());

        assertThat(transactions.completeProfile(candidate, prepared, unknown,
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(intColumn("attempt_no", "pull_task_account_action", 71L)).isOne();
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(outboxService, groupRegistry);
    }

    @Test
    void simplifiedUnknownPermissionsDoNotReuseMatchingProfileAsSuccess() {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=1");
        var candidate = profileCandidate(5, NOW);
        var prepared = transactions.prepareProfile(candidate, 2_000L, NOW + 31_000L);

        assertThat(transactions.completeProfile(candidate, prepared, metadata("完整简介"),
                NOW + 32_000L, NOW + 31_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(executionMapper.selectById(11L).getProfileVerifiedAt()).isNull();
        assertThat(executionMapper.selectById(11L).getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
        org.mockito.Mockito.verifyNoInteractions(groupRegistry);
    }

    @Test
    void missingRequiredProfilePreventsCreatingAGroup() {
        jdbc.update("UPDATE pull_task_standard_group_setting SET group_description=NULL WHERE task_id=1");
        assertThat(transactions.prepareRoles(executionMapper.selectById(11L), NOW, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_group_account", Integer.class)).isZero();
    }

    private PullTaskGroupExecution profileCandidate(int status, long submittedAt) {
        transactions.prepareRoles(executionMapper.selectById(11L), NOW, 2_000L);
        jdbc.update("UPDATE pull_task_group_execution SET create_step=4, group_jid='120363group@g.us' WHERE id=11");
        seedProfileAction(status, submittedAt);
        return reclaim(NOW + 31_000L);
    }

    private void seedProfileAction(int status, long submittedAt) {
        long creatorRole = jdbc.queryForObject(
                "SELECT id FROM pull_task_group_account WHERE group_execution_id=11 AND role_type=4", Long.class);
        jdbc.update("INSERT INTO pull_task_account_action (id,tenant_id,task_id,group_execution_id,action_type,"
                + "actor_group_account_id,target_group_account_id,action_status,command_id,attempt_no,submitted_at,created_at,updated_at)"
                + " VALUES (71,7,1,11,7,?,?,?,'profile-1',1,?,100,100)", creatorRole, creatorRole, status, submittedAt);
    }

    private static GroupMetadataResult metadata(String description) {
        return new GroupMetadataResult("120363group@g.us", "印度料子包", description,
                null, null, null, false, null, null, null, null, null, null,
                false, null, false, false, List.of());
    }

    private PullTaskGroupExecution reclaim(long now) {
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner = 'worker', "
                + "lock_expires_at = ? WHERE id = 11", now + 10_000L);
        return executionMapper.selectById(11L);
    }

    private List<Long> roleAccounts(PullTaskGroupAccountRole role) {
        return accountMapper.selectByExecutionAndRole(11L, role.code()).stream()
                .map(row -> row.getAccountId())
                .toList();
    }

    private int membership(PullTaskGroupAccountRole role, long accountId) {
        return accountMapper.selectByExecutionAndRole(11L, role.code()).stream()
                .filter(row -> row.getAccountId() == accountId)
                .findFirst().orElseThrow().getMembershipStatus();
    }

    private int intColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                Integer.class, id);
    }

    private long longColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                Long.class, id);
    }

    private String stringColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                String.class, id);
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(
                id, ProtocolBackend.WEB, "acc-" + id, "8613800000" + id);
    }

    private static String task() {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, creation_mode, status, "
                + "version, config_json, created_at, updated_at) VALUES "
                + "(1, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'NEW_GROUP', "
                + "'EXECUTING', 1, '{}', 100, 100)";
    }

    private static String standardSetting() {
        return "INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, material_admin_timing, puller_sync_mode, "
                + "pull_count_min, pull_count_max, pull_interval_seconds, "
                + "puller_count_per_group, station_count_per_call, initial_station_count, "
                + "concurrent_group_count, manager_group_id, puller_group_id, "
                + "station_group_id, creator_group_id, manager_group_name, puller_group_name, "
                + "station_group_name, creator_group_name, created_at, updated_at) VALUES "
                + "(7, 1, 1, 1, 5, 10, 30, 1, 1, 2, 1, 11, 12, 13, 16, "
                + "'管理', '拉手', '站台', '建群人', 100, 100)";
    }

    private static String enabledGroupSetting() {
        return "INSERT INTO pull_task_standard_group_setting "
                + "(tenant_id, task_id, is_group_setting_enabled, setting_timing, group_name, "
                + "group_description, is_material_filename_as_group_name, edit_permission_mode, mute_mode, "
                + "link_permission_mode, disappearing_message_mode, created_at, updated_at) "
                + "VALUES (7, 1, 1, 1, '印度料子包', '完整简介', 0, 0, 0, 2, 0, 100, 100)";
    }

    private static String execution() {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, source_file_index, source_file_name, "
                + "execution_status, stage, create_step, create_attempt_count, manual_paused, "
                + "next_run_at, lock_owner, lock_expires_at, version, created_at, updated_at) "
                + "VALUES (11, 7, 1, 1, 1, '印度料子包.txt', 2, 9, 1, 0, 0, 0, "
                + "'worker', 10000, 2, 100, 100)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_create_tx_test");
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskStandardGroupSettingMapper.xml", "mapper/task/PullTaskAccountActionMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean PullTaskStandardGroupSettingMapper groupSettingMapper(
                SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardGroupSettingMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean GroupCreatePort groupCreatePort() {
            return mock(GroupCreatePort.class);
        }

        @Bean GroupInvitePort groupInvitePort() {
            return mock(GroupInvitePort.class);
        }

        @Bean GroupLinkRegistryService groupRegistry() {
            return mock(GroupLinkRegistryService.class);
        }

        @Bean ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean PullTaskGroupProfileDispatcher profileDispatcher(
                PullTaskStandardGroupSettingMapper settingMapper, PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper, AccountProtocolLookupService accountLookup,
                ProtocolCommandOutboxService outboxService) {
            return spy(new PullTaskGroupProfileDispatcher(
                    settingMapper, actionMapper, accountMapper, accountLookup, outboxService));
        }

        @Bean PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean FixedAccountGroupMetadataPort metadataPort() { return mock(FixedAccountGroupMetadataPort.class); }

        @Bean PullTaskGroupCreatePersistence persistence(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskStandardGroupSettingMapper groupSettingMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper, PullTaskAccountActionMapper actionMapper) {
            return new PullTaskGroupCreatePersistence(
                    taskMapper, settingMapper, groupSettingMapper, executionMapper, accountMapper, actionMapper);
        }

        @Bean PullTaskGroupCreateResources resources(
                AccountProtocolLookupService accountLookup,
                GroupCreatePort groupCreatePort,
                GroupInvitePort groupInvitePort,
                GroupLinkRegistryService groupRegistry,
                PullTaskGroupProfileDispatcher profileDispatcher, FixedAccountGroupMetadataPort metadataPort) {
            return new PullTaskGroupCreateResources(
                    accountLookup, groupCreatePort, groupInvitePort,
                    groupRegistry, profileDispatcher, metadataPort);
        }

        @Bean PullTaskGroupCreateTransactionService transactions(
                PullTaskGroupCreatePersistence persistence,
                PullTaskGroupCreateResources resources) {
            return new PullTaskGroupCreateTransactionService(persistence, resources, org.mockito.Mockito.mock(PullTaskCreatorDeletionTransactionService.class));
        }
    }
}
