package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号动作 Mapper 的 H2 MySQL 模式测试：动作幂等、双向独立与回调定位。 */
@SpringJUnitConfig(PullTaskAccountActionMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskAccountActionMapperInMemoryTest {

    private static final long EXECUTION = 501L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskAccountActionMapper mapper;

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
    void repeatedInsertOfTheSameActionIsAbsorbed() {
        PullTaskAccountAction action =
                action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L);
        assertThat(mapper.insertIfAbsent(action)).isEqualTo(1);

        // 服务重启后重放同一步：唯一键吸收，不产生第二行也不发第二次命令。
        assertThat(mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L)))
                .isZero();
        assertThat(mapper.selectByExecutionAndType(
                EXECUTION, PullTaskAccountActionType.SAVE_CONTACT.code())).hasSize(1);
    }

    @Test
    void twoDirectionsOfTheSameContactPairAreIndependentRows() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L));
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 22L, 11L));

        // 双向加好友是 actor/target 互换的两行，各自独立记录结果。
        assertThat(mapper.selectByExecutionAndType(
                EXECUTION, PullTaskAccountActionType.SAVE_CONTACT.code())).hasSize(2);
    }

    @Test
    void joinByLinkUsesSelfAsActorSoTheIdempotencyKeyWorks() {
        // 踩链接没有真正的发起方，但 actor 必须写目标自身 ID：
        // MySQL 唯一索引中 NULL 互不相等，留空会让同一账号可以无限重复插入。
        assertThat(mapper.insertIfAbsent(action(PullTaskAccountActionType.JOIN_BY_LINK, 33L, 33L)))
                .isEqualTo(1);
        assertThat(mapper.insertIfAbsent(action(PullTaskAccountActionType.JOIN_BY_LINK, 33L, 33L)))
                .isZero();
    }

    @Test
    void pendingActionsExcludeFinishedOnes() {
        PullTaskAccountAction first =
                action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L);
        mapper.insertIfAbsent(first);
        mapper.insertIfAbsent(action(PullTaskAccountActionType.INVITE_TO_GROUP, 11L, 22L));

        Long firstId = mapper.selectByExecutionAndType(
                EXECUTION, PullTaskAccountActionType.SAVE_CONTACT.code()).get(0).getId();
        mapper.markSubmitted(firstId, "cmd-1", 800L);
        mapper.writeBackResult(firstId, PullTaskActionStatus.FAILED.code(),
                "PRIVACY", "对方隐私设置", 850L);

        assertThat(mapper.selectPending(EXECUTION))
                .extracting(PullTaskAccountAction::getActionType)
                .containsExactly(PullTaskAccountActionType.INVITE_TO_GROUP.code());
    }

    @Test
    void callbackIsLocatedByCommandId() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.INVITE_TO_GROUP, 11L, 22L));
        Long id = mapper.selectPending(EXECUTION).get(0).getId();
        mapper.markSubmitted(id, "cmd-invite-1", 800L);

        PullTaskAccountAction found = mapper.selectByCommandId("cmd-invite-1");
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(found.getSubmittedAt()).isEqualTo(800L);
    }

    @Test
    void unknownResultIsStoredDistinctFromFailure() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L));
        Long id = mapper.selectPending(EXECUTION).get(0).getId();
        mapper.markSubmitted(id, "cmd-2", 800L);

        mapper.writeBackResult(id, PullTaskActionStatus.UNKNOWN.code(), "TIMEOUT", "协议超时", 850L);

        assertThat(mapper.selectByCommandId("cmd-2").getActionStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
    }

    @Test
    void terminalResultCannotBeOverwrittenByLateWriteBack() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L));
        Long id = mapper.selectPending(EXECUTION).get(0).getId();
        mapper.markSubmitted(id, "cmd-terminal", 800L);

        assertThat(mapper.writeBackResult(id, PullTaskActionStatus.SUCCESS.code(),
                null, null, 850L)).isEqualTo(1);
        assertThat(mapper.writeBackResult(id, PullTaskActionStatus.FAILED.code(),
                "LATE", "迟到结果", 900L)).isZero();
        assertThat(mapper.selectByCommandId("cmd-terminal").getActionStatus())
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
    }

    @Test
    void unknownResultConvergesByCallerSuppliedCasStates() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.INVITE_TO_GROUP, 11L, 22L));
        Long id = mapper.selectPending(EXECUTION).get(0).getId();
        mapper.markSubmitted(id, "cmd-converge", 800L);
        mapper.writeBackResult(id, PullTaskActionStatus.UNKNOWN.code(),
                "TIMEOUT", "协议超时", 850L);

        PullTaskFactTransition transition = new PullTaskFactTransition(
                id, List.of(PullTaskActionStatus.SUBMITTED.code(),
                        PullTaskActionStatus.UNKNOWN.code()),
                PullTaskActionStatus.SUCCESS.code(),
                PullTaskFactResult.success(null, 900L), 900L);
        assertThat(mapper.transitionResult(transition)).isEqualTo(1);
        assertThat(mapper.transitionResult(new PullTaskFactTransition(
                id, List.of(PullTaskActionStatus.UNKNOWN.code()),
                PullTaskActionStatus.FAILED.code(),
                PullTaskFactResult.reason("LATE", "迟到失败"), 950L))).isZero();
        assertThat(mapper.selectByExecutionAndStatuses(
                EXECUTION, List.of(PullTaskActionStatus.SUCCESS.code())))
                .singleElement().satisfies(row -> {
                    assertThat(row.getActionStatus())
                            .isEqualTo(PullTaskActionStatus.SUCCESS.code());
                    assertThat(row.getReasonCode()).isNull();
                });
    }

    @Test
    void otherTenantActionsAreInvisible() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L));

        TenantContext.set(8L);
        assertThat(mapper.selectPending(EXECUTION)).isEmpty();
        assertThat(mapper.selectByCommandId("cmd-1")).isNull();
    }

    private PullTaskAccountAction action(PullTaskAccountActionType type, long actor, long target) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION);
        row.setActionType(type.code());
        row.setActorGroupAccountId(actor);
        row.setTargetGroupAccountId(target);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_account_action_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskAccountActionMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskAccountActionMapper pullTaskAccountActionMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskAccountActionMapper.class);
        }
    }
}
