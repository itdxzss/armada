package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskFactStatusCriteria;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.vo.PullTaskGroupAccountRoleCount;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 角色账号 Mapper 的 H2 MySQL 模式测试：拉手跨任务互斥、释放与重新占用。 */
@SpringJUnitConfig(PullTaskGroupAccountMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupAccountMapperInMemoryTest {

    private static final long EXEC_A = 501L;
    private static final long EXEC_B = 502L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupAccountMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void samePullerCannotServeTwoExecutionRowsAtOnce() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));

        // 另一个父任务的执行行想占同一个拉手账号：唯一键直接拒绝。
        assertThatThrownBy(() ->
                mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void releasingAPullerLetsAnotherTaskTakeIt() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);

        assertThat(mapper.releasePuller(first.getId(), 800L)).isEqualTo(1);

        // 释放后 occupancy_key 变 NULL，不再参与唯一约束。
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.PULLER.code()))
                .hasSize(1);
    }

    @Test
    void accountStateLookupReturnsOnlyOccupiedPullerRole() {
        PullTaskGroupAccount occupied =
                role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        PullTaskGroupAccount released =
                role(100L, EXEC_A, 901L, PullTaskGroupAccountRole.PULLER, 2);
        mapper.insert(occupied);
        mapper.insert(released);
        mapper.releasePuller(released.getId(), 800L);
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.MANAGER, 1));

        assertThat(mapper.selectOccupiedByAccountAndRole(
                900L, PullTaskGroupAccountRole.PULLER.code()))
                .extracting(PullTaskGroupAccount::getId)
                .containsExactly(occupied.getId());
        assertThat(mapper.selectOccupiedByAccountAndRole(
                901L, PullTaskGroupAccountRole.PULLER.code())).isEmpty();
    }

    @Test
    void reoccupyFailsWhenAnotherTaskAlreadyTookTheAccount() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);
        mapper.releasePuller(first.getId(), 800L);
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));

        // 恢复执行时重新竞争拉手；已被别人占走就必须失败，让本行进入等待拉手。
        assertThatThrownBy(() -> mapper.reoccupyPuller(first.getId(), 850L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void reoccupySucceedsWhenAccountIsStillFree() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);
        mapper.releasePuller(first.getId(), 800L);

        assertThat(mapper.reoccupyPuller(first.getId(), 850L)).isEqualTo(1);
        assertThatThrownBy(() ->
                mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void managersAndStationsNeverOccupyAcrossExecutions() {
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));
        mapper.insert(role(100L, EXEC_B, 910L, PullTaskGroupAccountRole.MANAGER, 1));
        mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1));
        mapper.insert(role(100L, EXEC_B, 920L, PullTaskGroupAccountRole.STATION, 1));

        // 管理账号要进每一条执行行；站台允许跨执行行复用。
        assertThat(mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.MANAGER.code()))
                .hasSize(1);
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.STATION.code()))
                .hasSize(1);
    }

    @Test
    void sameStationCannotEnterTheSameExecutionTwice() {
        mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1));

        assertThatThrownBy(() ->
                mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void releaseAllPullersOfExecutionFreesEveryActiveOccupation() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));
        mapper.insert(role(100L, EXEC_A, 901L, PullTaskGroupAccountRole.PULLER, 2));
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));

        // 执行行暂停或进入资源等待时释放全部拉手，管理角色不受影响。
        assertThat(mapper.releaseAllPullersOfExecution(EXEC_A, 800L)).isEqualTo(2);
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.PULLER.code()))
                .hasSize(1);
    }

    @Test
    void availableCountIsComputedPerRole() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));
        PullTaskGroupAccount cooled = role(100L, EXEC_A, 901L, PullTaskGroupAccountRole.PULLER, 2);
        mapper.insert(cooled);
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));

        mapper.markUnavailable(cooled.getId(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(), "RISK", 5000L, 800L);

        List<PullTaskGroupAccountRoleCount> counts = mapper.countAvailableByRole(EXEC_A);
        assertThat(counts)
                .extracting(PullTaskGroupAccountRoleCount::getRoleType,
                            PullTaskGroupAccountRoleCount::getAvailableCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(2, 1));
        assertThat(mapper.countAvailableByRole(
                EXEC_A, PullTaskGroupAccountAvailability.RISK_COOLDOWN.code()))
                .extracting(PullTaskGroupAccountRoleCount::getRoleType,
                            PullTaskGroupAccountRoleCount::getAvailableCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, 1));
    }

    @Test
    void expiredRiskCooldownRestoresOnlyExplicitlyValidatedAccountIds() {
        PullTaskGroupAccount cooled =
                role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(cooled);
        mapper.markUnavailable(cooled.getId(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                "RATE_LIMITED", 1_000L, 800L);

        assertThat(mapper.restoreExpiredPullerCooldowns(
                List.of(900L), PullTaskGroupAccountRole.PULLER.code(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                PullTaskGroupAccountAvailability.AVAILABLE.code(), 999L)).isZero();
        assertThat(mapper.restoreExpiredPullerCooldowns(
                List.of(901L), PullTaskGroupAccountRole.PULLER.code(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                PullTaskGroupAccountAvailability.AVAILABLE.code(), 1_000L)).isZero();
        assertThat(mapper.restoreExpiredPullerCooldowns(
                List.of(900L), PullTaskGroupAccountRole.PULLER.code(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                PullTaskGroupAccountAvailability.AVAILABLE.code(), 1_000L)).isEqualTo(1);

        PullTaskGroupAccount restored = mapper.selectByExecutionAndRole(
                EXEC_A, PullTaskGroupAccountRole.PULLER.code()).get(0);
        assertThat(restored.getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(restored.getUnavailableReasonCode()).isNull();
        assertThat(restored.getCooldownUntil()).isNull();
    }

    @Test
    void riskCooldownLookupIsAccountLevelAcrossReleasedRows() {
        PullTaskGroupAccount cooled =
                role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(cooled);
        mapper.markUnavailable(cooled.getId(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                "RATE_LIMITED", 5_000L, 800L);
        mapper.releasePuller(cooled.getId(), 810L);

        assertThat(mapper.selectAccountIdsByAvailability(
                List.of(900L, 901L), PullTaskGroupAccountRole.PULLER.code(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code()))
                .containsExactly(900L);
    }

    @Test
    void updateMembershipRecordsJoinResult() {
        PullTaskGroupAccount manager = role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1);
        mapper.insert(manager);

        // joinedAt 与 now 故意取不同值：若 XML 把两个字段的绑定顺序写反，这里能测出来。
        mapper.updateMembership(manager.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 950L, 960L);

        PullTaskGroupAccount saved =
                mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.MANAGER.code()).get(0);
        assertThat(saved.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(saved.getJoinedAt()).isEqualTo(950L);
        assertThat(saved.getUpdatedAt()).isEqualTo(960L);
    }

    @Test
    void stationMembershipUsesCasAndCountsUnknownByPullCall() throws SQLException {
        PullTaskGroupAccount station =
                role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1);
        mapper.insert(station);
        mapper.updateMembership(station.getId(),
                PullTaskGroupAccountMembershipStatus.JOINING.code(), null, 500L);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("UPDATE pull_task_group_account SET pull_call_id = 77 "
                    + "WHERE id = " + station.getId());
        }
        List<Integer> open = List.of(
                PullTaskGroupAccountMembershipStatus.JOINING.code(),
                PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
        PullTaskFactStatusCriteria criteria = new PullTaskFactStatusCriteria(77L, open);
        assertThat(mapper.countByPullCallAndMembershipStatuses(criteria)).isEqualTo(1);

        assertThat(mapper.transitionMembership(new PullTaskFactTransition(
                station.getId(), open,
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(),
                PullTaskFactResult.success(null, 600L), 610L))).isEqualTo(1);
        assertThat(mapper.countByPullCallAndMembershipStatuses(criteria)).isZero();
        assertThat(mapper.selectById(station.getId()).getJoinedAt()).isEqualTo(600L);
    }

    @Test
    void stationMembershipFailurePersistsItsOwnProtocolReason() {
        PullTaskGroupAccount station =
                role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1);
        mapper.insert(station);
        mapper.updateMembership(station.getId(),
                PullTaskGroupAccountMembershipStatus.JOINING.code(), null, 500L);

        assertThat(mapper.transitionMembership(new PullTaskFactTransition(
                station.getId(),
                List.of(PullTaskGroupAccountMembershipStatus.JOINING.code()),
                PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(),
                PullTaskFactResult.reason("PRIVACY_BLOCKED", "privacy blocked"),
                610L))).isEqualTo(1);

        PullTaskGroupAccount saved = mapper.selectById(station.getId());
        assertThat(saved.getMembershipReasonCode()).isEqualTo("PRIVACY_BLOCKED");
        assertThat(saved.getMembershipReasonMessage()).isEqualTo("privacy blocked");
        assertThat(saved.getMembershipResultAt()).isEqualTo(610L);
        assertThat(saved.getUnavailableReasonCode()).isNull();
    }

    @Test
    void joiningTransitionDoesNotPretendThatAProtocolResultWasWritten() {
        PullTaskGroupAccount station =
                role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1);
        mapper.insert(station);

        assertThat(mapper.transitionMembership(new PullTaskFactTransition(
                station.getId(),
                List.of(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code()),
                PullTaskGroupAccountMembershipStatus.JOINING.code(),
                PullTaskFactResult.empty(), 500L))).isEqualTo(1);

        PullTaskGroupAccount saved = mapper.selectById(station.getId());
        assertThat(saved.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code());
        assertThat(saved.getMembershipResultAt()).isNull();
    }

    @Test
    void otherTenantRoleRowsAreInvisible() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));

        TenantContext.set(8L);
        assertThat(mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.PULLER.code()))
                .isEmpty();
        assertThat(mapper.releaseAllPullersOfExecution(EXEC_A, 800L)).isZero();
    }

    @Test
    void stationAttemptBindingRetriesThreeTimesAndFourthFailureIsTerminal() {
        PullTaskGroupAccount station = role(
                100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1);
        mapper.insert(station);
        long priorAttemptId = 0L;

        for (int failure = 1; failure <= 4; failure++) {
            long attemptId = 1_000L + failure;
            long callId = 2_000L + failure;
            assertThat(mapper.bindMembershipAttempt(new PullTaskParticipantAttemptBinding(
                    station.getId(), attemptId, callId, 701L, 100L + failure)))
                    .isEqualTo(1);
            assertThat(mapper.bindMembershipAttempt(new PullTaskParticipantAttemptBinding(
                    station.getId(), attemptId + 100L, callId + 100L, 702L, 150L + failure)))
                    .isZero();
            if (priorAttemptId > 0) {
                assertThat(mapper.transitionMembershipAttempt(stationTransition(
                        station.getId(), priorAttemptId,
                        new PullTaskParticipantAggregateTransition.Expected(
                                List.of(PullTaskGroupAccountMembershipStatus.JOINING.code()),
                                failure - 1L),
                        new PullTaskParticipantAggregateTransition.Target(
                                PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                                failure, null, null)))).isZero();
            }
            int targetStatus = failure < 4
                    ? PullTaskGroupAccountMembershipStatus.NOT_JOINED.code()
                    : PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
            Long targetCallId = failure < 4 ? null : callId;
            assertThat(mapper.transitionMembershipAttempt(stationTransition(
                    station.getId(), attemptId,
                    new PullTaskParticipantAggregateTransition.Expected(
                            List.of(PullTaskGroupAccountMembershipStatus.JOINING.code()),
                            failure - 1L),
                    new PullTaskParticipantAggregateTransition.Target(
                            targetStatus, failure, targetCallId, null)))).isEqualTo(1);
            priorAttemptId = attemptId;
            if (failure < 4) {
                PullTaskGroupAccount saved = mapper.selectPendingStations(EXEC_A, 10).get(0);
                assertThat(saved.getMembershipFailureCount()).isEqualTo((long) failure);
                assertThat(saved.getPullCallId()).isNull();
                assertThat(saved.getActivePullAttemptId()).isNull();
            }
        }

        assertThat(mapper.selectPendingStations(EXEC_A, 10)).isEmpty();
        assertThat(mapper.selectById(station.getId()))
                .satisfies(saved -> {
                    assertThat(saved.getMembershipStatus())
                            .isEqualTo(PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code());
                    assertThat(saved.getMembershipFailureCount()).isEqualTo(4L);
                });
    }

    @Test
    void stationUnknownReleaseDoesNotConsumeFailureCount() {
        PullTaskGroupAccount station = role(
                100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1);
        mapper.insert(station);
        mapper.bindMembershipAttempt(new PullTaskParticipantAttemptBinding(
                station.getId(), 1_001L, 2_001L, 701L, 100L));

        assertThat(mapper.transitionMembershipAttempt(stationTransition(
                station.getId(), 1_001L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskGroupAccountMembershipStatus.JOINING.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                        0L, null, null)))).isEqualTo(1);

        assertThat(mapper.selectPendingStations(EXEC_A, 1)).singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getMembershipFailureCount()).isZero();
                    assertThat(saved.getPullCallId()).isNull();
                    assertThat(saved.getActivePullAttemptId()).isNull();
                });
    }

    @Test
    void stationSuccessCannotDowngradeAndLateSuccessKeepsNewerAttemptPointer() {
        PullTaskGroupAccount direct = role(
                100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1);
        PullTaskGroupAccount late = role(
                100L, EXEC_A, 921L, PullTaskGroupAccountRole.STATION, 2);
        mapper.insert(direct);
        mapper.insert(late);
        mapper.bindMembershipAttempt(new PullTaskParticipantAttemptBinding(
                direct.getId(), 1_001L, 2_001L, 701L, 100L));
        assertThat(mapper.promoteMembershipSuccess(stationTransition(
                direct.getId(), 1_001L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskGroupAccountMembershipStatus.JOINING.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code(),
                        0L, 2_001L, null)))).isEqualTo(1);
        assertThat(mapper.transitionMembershipAttempt(stationTransition(
                direct.getId(), 1_001L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskGroupAccountMembershipStatus.IN_GROUP.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(),
                        1L, 2_001L, null)))).isZero();

        mapper.bindMembershipAttempt(new PullTaskParticipantAttemptBinding(
                late.getId(), 1_010L, 2_010L, 701L, 110L));
        mapper.transitionMembershipAttempt(stationTransition(
                late.getId(), 1_010L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskGroupAccountMembershipStatus.JOINING.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                        0L, null, null)));
        mapper.bindMembershipAttempt(new PullTaskParticipantAttemptBinding(
                late.getId(), 1_011L, 2_011L, 702L, 120L));

        assertThat(mapper.promoteMembershipSuccess(stationTransition(
                late.getId(), 1_010L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code(),
                        0L, 2_010L, null)))).isEqualTo(1);
        assertThat(mapper.selectById(late.getId()))
                .satisfies(saved -> {
                    assertThat(saved.getMembershipStatus())
                            .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
                    assertThat(saved.getPullCallId()).isEqualTo(2_010L);
                    assertThat(saved.getActivePullAttemptId()).isEqualTo(1_011L);
                });
    }

    private PullTaskParticipantAggregateTransition stationTransition(
            long participantId,
            long attemptId,
            PullTaskParticipantAggregateTransition.Expected expected,
            PullTaskParticipantAggregateTransition.Target target) {
        return new PullTaskParticipantAggregateTransition(
                new PullTaskParticipantAggregateTransition.Scope(participantId, attemptId, 500L),
                expected,
                target,
                PullTaskFactResult.success(null, 500L));
    }

    @Test
    void insertPersistsEveryScalarColumnAtItsOwnDistinctValue() {
        // role_type/role_seq/source_type/selection_mode/entry_mode 全部绑定在同一段 INSERT
        // 参数游程里,必须两两互异且落在各自列的合法业务取值范围内,任何一对绑定被误换位
        // 才能被下面的断言抓到。role_type 用 STATION(3):MANAGER(1)/PULLER(2) 的编码会落进
        // source_type/selection_mode 的 {1,2} 定义域,STATION 是唯一不冲突的选择,代价是
        // entry_mode 按业务约定必须为 null——这样反而腾出了 role_type 需要的取值 3,
        // 否则 role_type 和 entry_mode 的定义域都是 {1,2,3},四个整数列无法同时两两互异。
        // occupied_at 同理只在拉手行才写入,这里保持 null 并不削弱断言:换位发生时
        // occupied_at/created_at 仍会互相串值,null 与具体数值一样能被观察到差异。
        PullTaskGroupAccount station = new PullTaskGroupAccount();
        station.setTaskId(100L);
        station.setGroupExecutionId(EXEC_A);
        station.setAccountId(900L);
        station.setAccountPhone("86138900");
        station.setRoleType(PullTaskGroupAccountRole.STATION.code());
        station.setRoleSeq(5);
        station.setSourceType(1);
        station.setSelectionMode(2);
        station.setEntryMode(null);
        station.setCreatedAt(100L);
        station.setUpdatedAt(150L);
        mapper.insert(station);

        PullTaskGroupAccount saved =
                mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.STATION.code()).get(0);
        assertThat(saved.getTaskId()).isEqualTo(100L);
        assertThat(saved.getGroupExecutionId()).isEqualTo(EXEC_A);
        assertThat(saved.getAccountId()).isEqualTo(900L);
        assertThat(saved.getAccountPhone()).isEqualTo("86138900");
        assertThat(saved.getRoleType()).isEqualTo(PullTaskGroupAccountRole.STATION.code());
        assertThat(saved.getRoleSeq()).isEqualTo(5);
        assertThat(saved.getSourceType()).isEqualTo(1);
        assertThat(saved.getSelectionMode()).isEqualTo(2);
        assertThat(saved.getEntryMode()).isNull();
        assertThat(saved.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        assertThat(saved.getAdminStatus()).isEqualTo(0);
        assertThat(saved.getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(saved.getCreatedAt()).isEqualTo(100L);
        assertThat(saved.getUpdatedAt()).isEqualTo(150L);
        assertThat(saved.getOccupiedAt()).isNull();
        assertThat(saved.getReleasedAt()).isNull();
    }

    private PullTaskGroupAccount role(long taskId, long executionId, long accountId,
                                      PullTaskGroupAccountRole roleType, int roleSeq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(taskId);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone("86138" + accountId);
        row.setRoleType(roleType.code());
        row.setRoleSeq(roleSeq);
        // source_type(域 1/2)/selection_mode(域 1/2)/entry_mode(域 1/2/3,站台为 null)
        // 都取各自域内、彼此不同的值——不用来做逐列换位检测(那由
        // insertPersistsEveryScalarColumnAtItsOwnDistinctValue 专门覆盖),这里的行为测试
        // 不读取这三列,取业务真实存在的编码即可,不取 4 这种任何列都无意义的值。
        row.setSourceType(2);
        row.setSelectionMode(1);
        row.setEntryMode(roleType == PullTaskGroupAccountRole.STATION ? null : 3);
        // created_at/updated_at/occupied_at 同理取互不相同的值。
        row.setCreatedAt(100L);
        row.setUpdatedAt(150L);
        if (roleType == PullTaskGroupAccountRole.PULLER) {
            row.setOccupiedAt(175L);
        }
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_account_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskGroupAccountMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupAccountMapper pullTaskGroupAccountMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupAccountMapper.class);
        }
    }
}
