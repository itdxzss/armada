package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.entity.PullTask;
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

/** 拉群任务生命周期乐观锁与 STANDARD 草稿可见性的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskLifecycleMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskLifecycleMapperInMemoryTest {

    private static final long TENANT = 7L;

    private static final String FIXTURES = """
            INSERT INTO pull_task
              (id, tenant_id, task_type, task_name, mode, status, version,
               config_json, created_at, updated_at)
            VALUES
              (1, 7, 'STANDARD', '普通草稿', 'GROUP_LINK', 'DRAFT', 1, '{}', 100, 100),
              (2, 7, 'STANDARD', '待启动任务', 'GROUP_LINK', 'WAIT_START', 1, '{}', 100, 100),
              (3, 7, 'GROUP_MARKETING', '营销草稿', 'OLD_LINK', 'DRAFT', 1, '{}', 100, 100),
              (4, 8, 'STANDARD', '他租户任务', 'GROUP_LINK', 'WAIT_START', 1, '{}', 100, 100)
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT);
        PullTaskNormalLinkH2Support.resetSchema(dataSource, FIXTURES);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void standardDraftIsHiddenFromListButMarketingDraftStaysVisible() {
        var filter = new PullTaskQuery().toFilter();
        List<PullTask> rows = mapper.selectPage(filter, 0, 50);

        // STANDARD 草稿是创建页未提交的计划,不进列表(ADR-0007);
        // GROUP_MARKETING 的 DRAFT 是既有可见状态,不能被一起隐藏。
        assertThat(rows).extracting(PullTask::getId).containsExactlyInAnyOrder(2L, 3L);

        // selectPage 与 countPage 共享同一个 <sql id="filter"> 片段;这里钉住两者
        // 口径一致，防止未来有人把其中一条语句的筛选条件内联后与另一条脱节，
        // 导致分页总数与页内实际行数对不上。
        assertThat(mapper.countPage(filter)).isEqualTo(rows.size());
    }

    @Test
    void listExcludesOtherTenants() {
        List<PullTask> rows = mapper.selectPage(new PullTaskQuery().toFilter(), 0, 50);

        assertThat(rows).extracting(PullTask::getId).doesNotContain(4L);
    }

    @Test
    void freezeDraftToWaitStartSucceedsOnceAndIsRejectedOnRepeat() {
        assertThat(mapper.updateStatusWithVersion(1L, "DRAFT", "WAIT_START", 1, null, null, 500L))
                .isEqualTo(1);

        // 重复提交:前置状态已不满足,返回 0 行,不产生第二次副作用。
        assertThat(mapper.updateStatusWithVersion(1L, "DRAFT", "WAIT_START", 1, null, null, 600L))
                .isZero();

        PullTask task = mapper.selectLifecycle(1L);
        assertThat(task.getStatus()).isEqualTo("WAIT_START");
        assertThat(task.getVersion()).isEqualTo(2);
    }

    @Test
    void staleVersionIsRejected() {
        assertThat(mapper.updateStatusWithVersion(2L, "WAIT_START", "EXECUTING", 1, 700L, null, 700L))
                .isEqualTo(1);

        // 另一个会话拿着旧版本号提交,必须被乐观锁挡掉。
        assertThat(mapper.updateStatusWithVersion(2L, "EXECUTING", "PAUSED", 1, null, null, 800L))
                .isZero();
    }

    @Test
    void startedAtIsNotOverwrittenByLaterTransitions() {
        mapper.updateStatusWithVersion(2L, "WAIT_START", "EXECUTING", 1, 700L, null, 700L);
        assertThat(mapper.selectLifecycle(2L).getStartedAt()).isEqualTo(700L);
        assertThat(mapper.selectLifecycle(2L).getFinishedAt()).isNull();

        mapper.updateStatusWithVersion(2L, "EXECUTING", "COMPLETED", 2, null, 900L, 900L);
        PullTask done = mapper.selectLifecycle(2L);
        assertThat(done.getStartedAt()).isEqualTo(700L);
        assertThat(done.getFinishedAt()).isEqualTo(900L);
    }

    @Test
    void lifecycleUpdateCannotCrossTenant() {
        assertThat(mapper.updateStatusWithVersion(4L, "WAIT_START", "EXECUTING", 1, 700L, null, 700L))
                .isZero();
        assertThat(mapper.selectLifecycle(4L)).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_lifecycle_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMapper.class);
        }
    }
}
