package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskPlannedCallPrune;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullCallRosterCheckStatus;
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

/** 拉人调用 Mapper 的 H2 MySQL 模式测试：幂等键、恢复重投与账号级间隔查询。 */
@SpringJUnitConfig(PullTaskPullCallMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullCallMapperInMemoryTest {

    private static final long EXECUTION = 501L;
    private static final long PULLER_ACCOUNT = 900L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskPullCallMapper mapper;

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
    void insertPlannedFillsGeneratedIdAndDefaultsToPlanned() {
        PullTaskPullCall call = planned(1, "idem-1");
        mapper.insertPlanned(call);

        assertThat(call.getId()).isNotNull();
        assertThat(mapper.selectPlannedByExecution(EXECUTION))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getCallStatus()).isEqualTo(PullTaskPullCallStatus.PLANNED.code());
                    assertThat(row.getIdempotencyKey()).isEqualTo("idem-1");
                    assertThat(row.getPlannedMaterialCount()).isEqualTo(5);
                    assertThat(row.getPlannedStationCount()).isEqualTo(2);
                });
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        mapper.insertPlanned(planned(1, "idem-1"));

        assertThatThrownBy(() -> mapper.insertPlanned(planned(2, "idem-1")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateCallSeqWithinOneExecutionIsRejected() {
        mapper.insertPlanned(planned(1, "idem-1"));

        assertThatThrownBy(() -> mapper.insertPlanned(planned(1, "idem-2")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void plannedCallsSurviveRestartForReplayWithTheOriginalKey() {
        mapper.insertPlanned(planned(1, "idem-1"));
        PullTaskPullCall submitted = planned(2, "idem-2");
        mapper.insertPlanned(submitted);
        mapper.markSubmitted(submitted.getId(), "cmd-2", 800L);

        // 只有仍处于"计划"的调用需要恢复重投；已提交的不得重发。
        assertThat(mapper.selectPlannedByExecution(EXECUTION))
                .extracting(PullTaskPullCall::getIdempotencyKey)
                .containsExactly("idem-1");
    }

    @Test
    void lastSubmittedAtDrivesTheAccountLevelInterval() {
        PullTaskPullCall first = planned(1, "idem-1");
        mapper.insertPlanned(first);
        mapper.markSubmitted(first.getId(), "cmd-1", 1000L);

        PullTaskPullCall second = planned(2, "idem-2");
        mapper.insertPlanned(second);
        mapper.markSubmitted(second.getId(), "cmd-2", 1500L);

        // 拉人间隔只约束同一拉手账号的连续调用。
        assertThat(mapper.selectLastSubmittedAtByPuller(PULLER_ACCOUNT)).isEqualTo(1500L);
        assertThat(mapper.selectLastSubmittedAtByPuller(999L)).isNull();
    }

    @Test
    void callbackIsLocatedByCommandId() {
        PullTaskPullCall call = planned(1, "idem-1");
        mapper.insertPlanned(call);
        mapper.markSubmitted(call.getId(), "cmd-1", 1000L);

        mapper.writeBackResult(call.getId(),
                PullTaskPullCallStatus.WRITTEN_BACK.code(), null, null, 1100L);

        PullTaskPullCall found = mapper.selectByCommandId("cmd-1");
        assertThat(found.getId()).isEqualTo(call.getId());
        assertThat(found.getCallStatus()).isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(found.getResultAt()).isEqualTo(1100L);
    }

    @Test
    void unknownCallConvergesWithoutReturningToPlanned() {
        PullTaskPullCall call = planned(1, "idem-1");
        mapper.insertPlanned(call);
        mapper.markSubmitted(call.getId(), "cmd-1", 1000L);
        mapper.writeBackResult(call.getId(), PullTaskPullCallStatus.UNKNOWN.code(),
                "TIMEOUT", "协议超时", 1100L);

        assertThat(mapper.transitionResult(new PullTaskFactTransition(
                call.getId(), List.of(PullTaskPullCallStatus.SUBMITTED.code(),
                        PullTaskPullCallStatus.UNKNOWN.code()),
                PullTaskPullCallStatus.WRITTEN_BACK.code(),
                PullTaskFactResult.success(null, 1200L), 1200L))).isEqualTo(1);
        assertThat(mapper.selectByCommandId("cmd-1").getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(mapper.selectPlannedByExecution(EXECUTION)).isEmpty();
    }

    @Test
    void rosterCheckCanBeClaimedOnlyOnceAndNeverReturnsToNotStarted() {
        PullTaskPullCall call = planned(1, "idem-1");
        mapper.insertPlanned(call);
        mapper.markSubmitted(call.getId(), "cmd-1", 1_000L);

        assertThat(mapper.claimRosterCheck(
                call.getId(), PullTaskPullCallRosterCheckStatus.NOT_STARTED.code(),
                PullTaskPullCallRosterCheckStatus.CLAIMED.code(), 61_000L)).isEqualTo(1);
        assertThat(mapper.claimRosterCheck(
                call.getId(), PullTaskPullCallRosterCheckStatus.NOT_STARTED.code(),
                PullTaskPullCallRosterCheckStatus.CLAIMED.code(), 61_001L)).isZero();
        assertThat(mapper.finishRosterCheck(
                call.getId(), PullTaskPullCallRosterCheckStatus.CLAIMED.code(),
                PullTaskPullCallRosterCheckStatus.SUCCEEDED.code(), 61_100L)).isEqualTo(1);

        PullTaskPullCall saved = mapper.selectByCommandId("cmd-1");
        assertThat(saved.getRosterCheckStatus())
                .isEqualTo(PullTaskPullCallRosterCheckStatus.SUCCEEDED.code());
        assertThat(saved.getRosterCheckStartedAt()).isEqualTo(61_000L);
        assertThat(saved.getRosterCheckFinishedAt()).isEqualTo(61_100L);
    }

    @Test
    void lateSuccessPrunesOnlyOneParticipantAndCancelsOnlyAnEmptyPlannedCall() {
        PullTaskPullCall call = planned(1, "idem-1");
        call.setPlannedMaterialCount(1);
        call.setPlannedStationCount(1);
        mapper.insertPlanned(call);

        assertThat(mapper.prunePlannedParticipant(new PullTaskPlannedCallPrune(
                call.getId(), PullTaskParticipantType.MATERIAL.code(),
                PullTaskPullCallStatus.PLANNED.code(), 1_000L))).isEqualTo(1);
        PullTaskPullCall afterMaterial = mapper.selectByExecution(EXECUTION).get(0);
        assertThat(afterMaterial.getPlannedMaterialCount()).isZero();
        assertThat(afterMaterial.getPlannedStationCount()).isEqualTo(1);
        assertThat(afterMaterial.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.PLANNED.code());

        assertThat(mapper.prunePlannedParticipant(new PullTaskPlannedCallPrune(
                call.getId(), PullTaskParticipantType.STATION.code(),
                PullTaskPullCallStatus.PLANNED.code(), 2_000L))).isEqualTo(1);
        PullTaskPullCall emptyCall = mapper.selectByExecution(EXECUTION).get(0);
        assertThat(emptyCall.getPlannedMaterialCount()).isZero();
        assertThat(emptyCall.getPlannedStationCount()).isZero();
        assertThat(emptyCall.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.CANCELED.code());
        assertThat(emptyCall.getReasonCode()).isEqualTo("LATE_PARTICIPANT_SUCCESS");
        assertThat(emptyCall.getResultAt()).isEqualTo(2_000L);
    }

    @Test
    void otherTenantCallsAreInvisible() {
        mapper.insertPlanned(planned(1, "idem-1"));

        TenantContext.set(8L);
        assertThat(mapper.selectPlannedByExecution(EXECUTION)).isEmpty();
        assertThat(mapper.selectLastSubmittedAtByPuller(PULLER_ACCOUNT)).isNull();
        assertThat(mapper.selectByCommandId("cmd-1")).isNull();
    }

    private PullTaskPullCall planned(int callSeq, String idempotencyKey) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION);
        row.setCallSeq(callSeq);
        row.setPullerGroupAccountId(701L);
        row.setPullerAccountId(PULLER_ACCOUNT);
        row.setPlannedMaterialCount(5);
        row.setPlannedStationCount(2);
        row.setIdempotencyKey(idempotencyKey);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_pull_call_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskPullCallMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskPullCallMapper pullTaskPullCallMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskPullCallMapper.class);
        }
    }
}
