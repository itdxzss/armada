package com.armada.account.wbcompanion.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.wbcompanion.model.entity.WbCompanionBinding;
import com.armada.account.wbcompanion.model.entity.WbCompanionTask;
import com.armada.account.wbcompanion.model.enums.WbCompanionStep;
import com.armada.account.wbcompanion.model.enums.WbCompanionTaskStatus;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** V219 迁移在 H2 MySQL 模式下建表，真实 Mapper XML 与租户拦截器一起验证任务和绑定的读写。 */
@SpringJUnitConfig(WbCompanionMapperH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class WbCompanionMapperH2Test {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private WbCompanionTaskMapper taskMapper;
    @Autowired
    private WbCompanionBindingMapper bindingMapper;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        String migration = new ClassPathResource("db/migration/V219__wb_companion.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        // H2 不认 MySQL 的表选项、列字符集和 VIRTUAL 关键字，去掉后其余 DDL（含生成列唯一键）原样执行。
        String h2 = migration.replaceAll("(?m)^--.*$", "")
                .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                .replace(" VIRTUAL", "")
                .replaceAll("\\) ENGINE=InnoDB[^;]*;", ");");
        for (String statement : h2.split(";")) {
            if (!statement.isBlank()) {
                execute(statement);
            }
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void oneRunningTaskPerAccountAndTenantIsolation() {
        WbCompanionTask first = task(9001L, 1000L);
        taskMapper.insert(first);
        assertThat(first.getId()).isNotNull();
        assertThatThrownBy(() -> taskMapper.insert(task(9001L, 1000L))).isInstanceOf(DuplicateKeyException.class);

        // 结束后同一账号可以再建任务。
        assertThat(taskMapper.stop(first.getId(), 2000L)).isEqualTo(1);
        taskMapper.insert(task(9001L, 3000L));
        assertThat(taskMapper.selectRecentByAccount(9001L, 10)).hasSize(2)
                .first().satisfies(latest -> assertThat(latest.getStatus()).isEqualTo(WbCompanionTaskStatus.RUNNING));
        assertThat(taskMapper.selectRunningByAccountIds(List.of(9001L, 9002L))).hasSize(1);

        TenantContext.set(8L);
        assertThat(taskMapper.selectById(first.getId())).isNull();
        assertThat(taskMapper.selectRunningByAccountIds(List.of(9001L))).isEmpty();
    }

    @Test
    void dueScanCrossesTenantsAndClaimIsExclusive() {
        WbCompanionTask own = task(9001L, 1000L);
        taskMapper.insert(own);
        TenantContext.set(8L);
        WbCompanionTask other = task(9002L, 1500L);
        taskMapper.insert(other);
        WbCompanionTask later = task(9003L, 9000L);
        taskMapper.insert(later);

        assertThat(taskMapper.selectDue(2000L, 10)).extracting(WbCompanionTask::getId, WbCompanionTask::getTenantId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(own.getId(), 7L),
                        org.assertj.core.groups.Tuple.tuple(other.getId(), 8L));

        assertThat(taskMapper.claim(other.getId(), 1500L, 31_500L, 2000L)).isEqualTo(1);
        assertThat(taskMapper.claim(other.getId(), 1500L, 31_500L, 2000L)).isZero();
        TenantContext.set(7L);
        assertThat(taskMapper.claim(other.getId(), 31_500L, 61_500L, 2000L)).isZero();
    }

    @Test
    void progressUpdatesOnlyRunningTasks() {
        WbCompanionTask task = task(9001L, 1000L);
        taskMapper.insert(task);
        task.setStep(WbCompanionStep.WB_STARTED);
        task.setWbPairingId("p1");
        task.setWbRole("MAIN");
        task.setPairCode("ABCDEFGH");
        task.setStepDeadline(21_000L);
        task.setNextRunAt(2_500L);
        task.setUpdatedAt(1_000L);
        assertThat(taskMapper.updateProgress(task)).isEqualTo(1);
        WbCompanionTask saved = taskMapper.selectById(task.getId());
        assertThat(saved.getPairCode()).isEqualTo("ABCDEFGH");
        assertThat(saved.getWbRole()).isEqualTo("MAIN");

        taskMapper.stop(task.getId(), 3000L);
        WbCompanionTask stopped = taskMapper.selectById(task.getId());
        assertThat(stopped.getStatus()).isEqualTo(WbCompanionTaskStatus.STOPPED);
        assertThat(stopped.getPairCode()).isNull();
        // 已停止的任务不会被推进结果覆盖回去。
        task.setSucceededCount(1);
        assertThat(taskMapper.updateProgress(task)).isZero();
    }

    @Test
    void bindingLifecycle() {
        WbCompanionBinding binding = new WbCompanionBinding();
        binding.setAccountId(9001L);
        binding.setWbEnvId("test1");
        binding.setWbMemberId(120L);
        binding.setWbUsername("wb_jack01");
        binding.setWbDeviceId(10482L);
        binding.setConnectionCount(1);
        binding.setOnlineCount(1);
        binding.setSyncedAt(1000L);
        binding.setCreatedAt(1000L);
        binding.setUpdatedAt(1000L);
        bindingMapper.insert(binding);
        assertThatThrownBy(() -> bindingMapper.insert(binding)).isInstanceOf(DuplicateKeyException.class);

        binding.setConnectionCount(4);
        binding.setOnlineCount(3);
        bindingMapper.updateSnapshot(binding);
        assertThat(bindingMapper.selectByAccountIds(List.of(9001L, 9002L))).singleElement()
                .satisfies(row -> {
                    assertThat(row.getTenantId()).isEqualTo(7L);
                    assertThat(row.getConnectionCount()).isEqualTo(4);
                    assertThat(row.getOnlineCount()).isEqualTo(3);
                });

        TenantContext.set(8L);
        assertThat(bindingMapper.selectByAccountId(9001L)).isNull();
        TenantContext.set(7L);
        assertThat(bindingMapper.deleteByAccountId(9001L)).isEqualTo(1);
        assertThat(bindingMapper.selectByAccountId(9001L)).isNull();
    }

    private static WbCompanionTask task(Long accountId, long nextRunAt) {
        WbCompanionTask task = new WbCompanionTask();
        task.setAccountId(accountId);
        task.setWsPhone("628123460001");
        task.setWbEnvId("test1");
        task.setWbMemberId(120L);
        task.setWbUsername("wb_jack01");
        task.setBaseCount(0);
        task.setTargetCount(4);
        task.setSucceededCount(0);
        task.setStatus(WbCompanionTaskStatus.RUNNING);
        task.setStep(WbCompanionStep.WAITING);
        task.setNextRunAt(nextRunAt);
        task.setCreatedAt(nextRunAt);
        task.setUpdatedAt(nextRunAt);
        return task;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:wb_companion;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/account/WbCompanionTaskMapper.xml"),
                    new ClassPathResource("mapper/account/WbCompanionBindingMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        WbCompanionTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(WbCompanionTaskMapper.class);
        }

        @Bean
        WbCompanionBindingMapper bindingMapper(SqlSessionTemplate template) {
            return template.getMapper(WbCompanionBindingMapper.class);
        }
    }
}
