package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskPullWaveDispatchAdvance;
import com.armada.task.model.dto.PullTaskPullWaveTransition;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskPullWaveType;
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

/** 拉人波次 Mapper 的 H2 MySQL 模式状态机、唯一约束与租户隔离测试。 */
@SpringJUnitConfig(PullTaskPullWaveMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullWaveMapperInMemoryTest {

    private static final long EXECUTION_ID = 501L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskPullWaveMapper waveMapper;

    @Autowired
    private PullTaskPullCallMapper callMapper;

    @Autowired
    private PullTaskPullCallMemberAttemptMapper attemptMapper;

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
    void insertDispatchingWaveGeneratesIdAndDefaults() {
        PullTaskPullWave wave = dispatchingWave(1, 1_000L);

        assertThat(waveMapper.insertInitialized(wave)).isEqualTo(1);
        assertThat(wave.getId()).isNotNull();
        assertThat(waveMapper.selectById(wave.getId()))
                .satisfies(saved -> {
                    assertThat(saved.getWaveStatus())
                            .isEqualTo(PullTaskPullWaveStatus.DISPATCHING.code());
                    assertThat(saved.getNextCallSeq()).isEqualTo(1);
                    assertThat(saved.getVersion()).isEqualTo(1);
                    assertThat(saved.getPlannedCallCount()).isEqualTo(5);
                });
    }

    @Test
    void onlyOneActiveWavePerExecutionIsAllowed() {
        waveMapper.insertInitialized(dispatchingWave(1, 1_000L));

        assertThatThrownBy(() -> waveMapper.insertInitialized(dispatchingWave(2, 2_000L)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void transitionDispatchingToCollectingUsesVersionAndStatusCas() {
        PullTaskPullWave wave = dispatchingWave(1, 1_000L);
        waveMapper.insertInitialized(wave);
        PullTaskPullWaveTransition transition = transition(
                wave, PullTaskPullWaveStatus.DISPATCHING.code(), 1,
                new PullTaskPullWaveTransition.Target(
                        PullTaskPullWaveStatus.COLLECTING.code(), 6, 5_000L, 5_000L, null));

        assertThat(waveMapper.transition(transition)).isEqualTo(1);
        assertThat(waveMapper.transition(transition)).isZero();
        assertThat(waveMapper.selectById(wave.getId()))
                .satisfies(saved -> {
                    assertThat(saved.getWaveStatus())
                            .isEqualTo(PullTaskPullWaveStatus.COLLECTING.code());
                    assertThat(saved.getNextCallSeq()).isEqualTo(6);
                    assertThat(saved.getDispatchCompletedAt()).isEqualTo(5_000L);
                    assertThat(saved.getVersion()).isEqualTo(2);
                });
    }

    @Test
    void advanceDispatchUsesPersistedVersionAndWaveCursor() {
        PullTaskPullWave wave = dispatchingWave(1, 1_000L);
        waveMapper.insertInitialized(wave);
        PullTaskPullWaveDispatchAdvance advance = new PullTaskPullWaveDispatchAdvance(
                new PullTaskPullWaveDispatchAdvance.Scope(wave.getId(), 1, 1),
                new PullTaskPullWaveDispatchAdvance.Target(
                        2, PullTaskPullWaveStatus.DISPATCHING.code(), 11_000L, null),
                new PullTaskPullWaveDispatchAdvance.Execution(
                        EXECUTION_ID, 6, "worker-1"),
                1_000L);

        assertThat(waveMapper.advanceDispatch(advance)).isEqualTo(1);
        assertThat(waveMapper.advanceDispatch(advance)).isZero();
        assertThat(waveMapper.selectById(wave.getId()))
                .satisfies(saved -> {
                    assertThat(saved.getWaveStatus())
                            .isEqualTo(PullTaskPullWaveStatus.DISPATCHING.code());
                    assertThat(saved.getNextCallSeq()).isEqualTo(2);
                    assertThat(saved.getNextDispatchAt()).isEqualTo(11_000L);
                    assertThat(saved.getVersion()).isEqualTo(2);
                });
    }

    @Test
    void wakeCollectingNeverChangesDispatchingWave() {
        PullTaskPullWave wave = dispatchingWave(1, 10_000L);
        waveMapper.insertInitialized(wave);

        assertThat(waveMapper.wakeCollecting(
                wave.getId(), EXECUTION_ID,
                PullTaskPullWaveStatus.COLLECTING.code(), 2_000L)).isZero();
        assertThat(waveMapper.selectById(wave.getId()).getNextDispatchAt()).isEqualTo(10_000L);

        assertThat(waveMapper.transition(transition(
                wave, PullTaskPullWaveStatus.DISPATCHING.code(), 1,
                new PullTaskPullWaveTransition.Target(
                        PullTaskPullWaveStatus.COLLECTING.code(),
                        6, 10_000L, 1_500L, null))))
                .isEqualTo(1);
        assertThat(waveMapper.wakeCollecting(
                wave.getId(), EXECUTION_ID,
                PullTaskPullWaveStatus.COLLECTING.code(), 2_000L)).isEqualTo(1);
        assertThat(waveMapper.selectById(wave.getId()).getNextDispatchAt()).isEqualTo(2_000L);
    }

    @Test
    void settledWaveAllowsNextActiveWave() {
        PullTaskPullWave first = dispatchingWave(1, 1_000L);
        waveMapper.insertInitialized(first);
        assertThat(waveMapper.transition(transition(
                first, PullTaskPullWaveStatus.DISPATCHING.code(), 1,
                new PullTaskPullWaveTransition.Target(
                        PullTaskPullWaveStatus.SETTLED.code(),
                        6, 1_000L, 1_000L, 2_000L))))
                .isEqualTo(1);

        PullTaskPullWave second = dispatchingWave(2, 3_000L);
        second.setWaveType(PullTaskPullWaveType.RETRY.code());
        assertThat(waveMapper.insertInitialized(second)).isEqualTo(1);
        assertThat(waveMapper.selectActiveByExecution(
                EXECUTION_ID, activeStatuses()))
                .extracting(PullTaskPullWave::getId)
                .isEqualTo(second.getId());
    }

    @Test
    void tenantIsolationHidesAnotherTenantWave() {
        PullTaskPullWave wave = dispatchingWave(1, 1_000L);
        waveMapper.insertInitialized(wave);

        TenantContext.set(8L);
        assertThat(waveMapper.selectById(wave.getId())).isNull();
        assertThat(waveMapper.selectActiveByExecution(EXECUTION_ID, activeStatuses())).isNull();
    }

    @Test
    void plannedCallAndAttemptAllowNullPullerButPersistWaveIdentity() {
        PullTaskPullWave wave = dispatchingWave(1, 1_000L);
        waveMapper.insertInitialized(wave);

        PullTaskPullCall call = plannedCall(wave.getId());
        assertThat(callMapper.insertPlanned(call)).isEqualTo(1);
        PullTaskPullCallMemberAttempt attempt = plannedAttempt(wave.getId(), call.getId());
        assertThat(attemptMapper.insertPlanned(attempt)).isEqualTo(1);

        assertThat(callMapper.selectByExecution(EXECUTION_ID))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getPullWaveId()).isEqualTo(wave.getId());
                    assertThat(saved.getWaveCallSeq()).isEqualTo(1);
                    assertThat(saved.getPullerGroupAccountId()).isNull();
                    assertThat(saved.getPullerAccountId()).isNull();
                });
        assertThat(attemptMapper.selectByCall(call.getId()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getPullWaveId()).isEqualTo(wave.getId());
                    assertThat(saved.getPullerGroupAccountId()).isNull();
                });
    }

    private PullTaskPullWave dispatchingWave(int waveNo, long nextDispatchAt) {
        PullTaskPullWave row = new PullTaskPullWave();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setWaveNo(waveNo);
        row.setWaveType(PullTaskPullWaveType.INITIAL.code());
        row.setWaveStatus(PullTaskPullWaveStatus.DISPATCHING.code());
        row.setPlannedCallCount(5);
        row.setNextDispatchAt(nextDispatchAt);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private PullTaskPullWaveTransition transition(
            PullTaskPullWave wave,
            int expectedStatus,
            int expectedVersion,
            PullTaskPullWaveTransition.Target target) {
        return new PullTaskPullWaveTransition(
                new PullTaskPullWaveTransition.Scope(
                        wave.getId(), EXECUTION_ID, expectedStatus, expectedVersion),
                target,
                target.nextDispatchAt());
    }

    private PullTaskPullCall plannedCall(long waveId) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setPullWaveId(waveId);
        row.setCallSeq(1);
        row.setWaveCallSeq(1);
        row.setPlannedMaterialCount(1);
        row.setPlannedStationCount(0);
        row.setIdempotencyKey("wave-call-1");
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private PullTaskPullCallMemberAttempt plannedAttempt(long waveId, long callId) {
        PullTaskPullCallMemberAttempt row = new PullTaskPullCallMemberAttempt();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setPullCallId(callId);
        row.setPullWaveId(waveId);
        row.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        row.setParticipantRefId(601L);
        row.setTargetPhone("8613900000001");
        row.setTargetJid("8613900000001@s.whatsapp.net");
        row.setAttemptNo(1);
        row.setFailureCountBefore(0L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private List<Integer> activeStatuses() {
        return List.of(
                PullTaskPullWaveStatus.DISPATCHING.code(),
                PullTaskPullWaveStatus.COLLECTING.code());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_pull_wave_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource,
                    interceptor,
                    "mapper/task/PullTaskPullWaveMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskPullWaveMapper pullTaskPullWaveMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullWaveMapper.class);
        }

        @Bean
        PullTaskPullCallMapper pullTaskPullCallMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean
        PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }
    }
}
