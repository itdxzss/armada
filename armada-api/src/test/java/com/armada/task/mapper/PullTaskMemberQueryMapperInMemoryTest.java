package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.dto.PullTaskMemberQuerySettlement;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
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
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

@SpringJUnitConfig(PullTaskMemberQueryMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskMemberQueryMapperInMemoryTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskMemberQueryMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        com.armada.shared.tenant.TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void insertBindExpireAndRetryKeepOneOpenAttemptPerBusinessKey() {
        PullTaskMemberQuery first = query(1, 100L);
        assertThat(mapper.insertInitialized(first)).isEqualTo(1);
        assertThat(first.getId()).isPositive();
        assertThat(mapper.selectNextAttemptNo(11L, "manager:601")).isEqualTo(2);
        assertThatThrownBy(() -> mapper.insertInitialized(query(2, 200L)))
                .isInstanceOf(DataAccessException.class);

        assertThat(mapper.bindCommandId(
                first.getId(), PullTaskMemberQueryStatus.PENDING.code(), "cmd-query-1", 110L))
                .isEqualTo(1);
        assertThat(mapper.selectByCommandId("cmd-query-1").getId()).isEqualTo(first.getId());
        assertThat(mapper.expirePending(
                first.getId(), PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.EXPIRED.code(), 201L,
                "QUERY_TIMEOUT", "member query timed out"))
                .isEqualTo(1);
        assertThat(mapper.expirePending(
                first.getId(), PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.EXPIRED.code(), 202L,
                "QUERY_TIMEOUT", "duplicate"))
                .isZero();

        PullTaskMemberQuery retry = query(2, 202L);
        assertThat(mapper.insertInitialized(retry)).isEqualTo(1);
        PullTaskMemberQuery latest = mapper.selectLatestByBusinessKey(11L, "manager:601");
        assertThat(latest.getId()).isEqualTo(retry.getId());
        assertThat(latest.getAttemptNo()).isEqualTo(2);
    }

    @Test
    void cancelPendingOnlyTouchesRequestedTaskAndExecution() {
        PullTaskMemberQuery first = query(1, 100L);
        mapper.insertInitialized(first);
        PullTaskMemberQuery other = query(1, 100L);
        other.setBusinessKey("manager:602");
        other.setGroupExecutionId(12L);
        mapper.insertInitialized(other);

        assertThat(mapper.cancelPending(
                9L, 11L, PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.CANCELED.code(), 300L,
                "PULL_TASK_ENDED", "pull task ended"))
                .isEqualTo(1);
        assertThat(mapper.selectById(first.getId()).getQueryStatus())
                .isEqualTo(PullTaskMemberQueryStatus.CANCELED.code());
        assertThat(mapper.selectById(other.getId()).getQueryStatus())
                .isEqualTo(PullTaskMemberQueryStatus.PENDING.code());
    }

    @Test
    void settlePendingIsIdempotentAndRejectsOldCommand() {
        PullTaskMemberQuery first = query(1, 100L);
        mapper.insertInitialized(first);
        mapper.bindCommandId(
                first.getId(), PullTaskMemberQueryStatus.PENDING.code(), "cmd-query-1", 110L);

        PullTaskMemberQuerySettlement wrong = new PullTaskMemberQuerySettlement(
                first.getId(), "old-command", PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.SUCCEEDED.code(), "[]", null, null, 150L);
        assertThat(mapper.settlePending(wrong)).isZero();

        PullTaskMemberQuerySettlement success = new PullTaskMemberQuerySettlement(
                first.getId(), "cmd-query-1", PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.SUCCEEDED.code(),
                "[{\"targetJid\":\"456@s.whatsapp.net\",\"inGroup\":false,\"admin\":false}]",
                null, null, 151L);
        assertThat(mapper.settlePending(success)).isEqualTo(1);
        assertThat(mapper.settlePending(success)).isZero();
        PullTaskMemberQuery stored = mapper.selectById(first.getId());
        assertThat(stored.getQueryStatus()).isEqualTo(PullTaskMemberQueryStatus.SUCCEEDED.code());
        assertThat(stored.getCompletedAt()).isEqualTo(151L);
    }

    private static PullTaskMemberQuery query(int attemptNo, long now) {
        PullTaskMemberQuery row = new PullTaskMemberQuery();
        row.setTenantId(7L);
        row.setTaskId(9L);
        row.setGroupExecutionId(11L);
        row.setBusinessKey("manager:601");
        row.setPurpose("MANAGER_JOIN");
        row.setAccountId(382L);
        row.setProtocolAccountId("acc-web");
        row.setProtocolBackend("WEB");
        row.setWsPhone("911");
        row.setGroupJid("123@g.us");
        row.setTargetJidsJson("[\"456@s.whatsapp.net\"]");
        row.setQueryStatus(PullTaskMemberQueryStatus.PENDING.code());
        row.setAttemptNo(attemptNo);
        row.setRequestedAt(now);
        row.setDeadlineAt(now + 100L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_member_query_mapper_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMemberQueryMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMemberQueryMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMemberQueryMapper.class);
        }
    }
}
