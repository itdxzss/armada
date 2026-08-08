package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskParticipantType;
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

/** 逐号码执行台账 Mapper 的 H2 MySQL 模式约束与租户隔离测试。 */
@SpringJUnitConfig(PullTaskPullCallMemberAttemptMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullCallMemberAttemptMapperInMemoryTest {

    private static final long EXECUTION_ID = 501L;
    private static final long PARTICIPANT_ID = 601L;
    private static final String TARGET_JID = "8613900000001@s.whatsapp.net";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskPullCallMemberAttemptMapper mapper;

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
    void insertPlannedFillsGeneratedIdAndFrozenIdentity() {
        PullTaskPullCallMemberAttempt row = planned(801L, PARTICIPANT_ID, 1);

        assertThat(mapper.insertPlanned(row)).isEqualTo(1);

        assertThat(row.getId()).isNotNull();
        assertThat(mapper.selectByCall(801L))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getLifecycleStatus())
                            .isEqualTo(PullTaskParticipantAttemptStatus.PLANNED.code());
                    assertThat(saved.getActiveSlot()).isEqualTo(1);
                    assertThat(saved.getParticipantType())
                            .isEqualTo(PullTaskParticipantType.MATERIAL.code());
                    assertThat(saved.getTargetJid()).isEqualTo(TARGET_JID);
                });
    }

    @Test
    void duplicateParticipantInOneCallIsRejected() {
        mapper.insertPlanned(planned(801L, PARTICIPANT_ID, 1));

        assertThatThrownBy(() -> mapper.insertPlanned(planned(801L, PARTICIPANT_ID, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void twoActiveAttemptsForOneParticipantAreRejected() {
        mapper.insertPlanned(planned(801L, PARTICIPANT_ID, 1));

        assertThatThrownBy(() -> mapper.insertPlanned(planned(802L, PARTICIPANT_ID, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void releaseClearsActiveSlotAndAllowsLaterAttempt() {
        PullTaskPullCallMemberAttempt first = planned(801L, PARTICIPANT_ID, 1);
        mapper.insertPlanned(first);
        PullTaskParticipantAttemptTransition transition =
                new PullTaskParticipantAttemptTransition(
                        new PullTaskParticipantAttemptTransition.Scope(first.getId(), 200L),
                        new PullTaskParticipantAttemptTransition.Expected(List.of(
                                PullTaskParticipantAttemptStatus.PLANNED.code())),
                        new PullTaskParticipantAttemptTransition.Target(
                                PullTaskParticipantAttemptStatus.RELEASED.code(),
                                "UNKNOWN", PullTaskParticipantExecutionState.NOT_STARTED, 200L),
                        new PullTaskFactResult(
                                "ACCOUNT_NOT_ONLINE", "offline", null, 200L));

        assertThat(mapper.transition(transition)).isEqualTo(1);
        assertThat(mapper.selectByCall(801L))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getLifecycleStatus())
                            .isEqualTo(PullTaskParticipantAttemptStatus.RELEASED.code());
                    assertThat(saved.getActiveSlot()).isNull();
                    assertThat(saved.getReleasedAt()).isEqualTo(200L);
                });

        PullTaskPullCallMemberAttempt second = planned(802L, PARTICIPANT_ID, 2);
        assertThat(mapper.insertPlanned(second)).isEqualTo(1);
    }

    @Test
    void selectByCallAndTargetMatchesFrozenTargetJid() {
        PullTaskPullCallMemberAttempt expected = planned(801L, PARTICIPANT_ID, 1);
        mapper.insertPlanned(expected);

        assertThat(mapper.selectByCallAndTarget(801L, TARGET_JID))
                .extracting(PullTaskPullCallMemberAttempt::getId)
                .isEqualTo(expected.getId());
        assertThat(mapper.selectByCallAndTarget(801L, "8613900000002@s.whatsapp.net"))
                .isNull();
    }

    @Test
    void sameIdsInAnotherTenantRemainInvisible() {
        mapper.insertPlanned(planned(801L, PARTICIPANT_ID, 1));

        TenantContext.set(8L);
        assertThat(mapper.selectByCall(801L)).isEmpty();
        assertThat(mapper.selectByCallAndTarget(801L, TARGET_JID)).isNull();
    }

    private PullTaskPullCallMemberAttempt planned(long callId, long participantId, int attemptNo) {
        PullTaskPullCallMemberAttempt row = new PullTaskPullCallMemberAttempt();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setPullCallId(callId);
        row.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        row.setParticipantRefId(participantId);
        row.setTargetPhone("8613900000001");
        row.setTargetJid(TARGET_JID);
        row.setPullerGroupAccountId(701L);
        row.setAttemptNo(attemptNo);
        row.setFailureCountBefore(0L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_attempt_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor,
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }
    }
}
