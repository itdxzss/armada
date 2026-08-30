package com.armada.hyperlink.strategy.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.entity.HyperlinkStrategy;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;

/** 超链策略真实 Mapper XML、分页、租户插件与乐观锁的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(HyperlinkStrategyMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkStrategyMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HyperlinkStrategyMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insertFilterPageAndDetailStayInsideCurrentTenant() throws SQLException {
        HyperlinkStrategy current = strategy("巴西周期", 3, true, 100L);
        mapper.insert(current);
        execute("""
                INSERT INTO hyperlink_strategy
                    (tenant_id, strategy_scope, strategy_name, task_type, account_filter, concurrent_num,
                     max_use_account, account_max_send_num, task_interval_minutes, is_enabled,
                     version, created_at, updated_at)
                VALUES
                    (8, 1, '其他租户周期', 3, '{"filterSchemaVersion":1}', 10,
                     50, 0, 60, 1, 1, 100, 100)
                """);

        HyperlinkStrategyQuery query = new HyperlinkStrategyQuery();
        query.setName("周期");
        query.setTaskMode("cycle");
        query.setEnabled(true);

        assertThat(mapper.countPage(query, 3)).isEqualTo(1);
        assertThat(mapper.selectPage(query, 3))
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.getId()).isEqualTo(current.getId());
                    assertThat(found.getTenantId()).isEqualTo(7L);
                    assertThat(found.getEnabled()).isTrue();
                    assertThat(found.getAccountFilter()).contains("filterSchemaVersion");
                });

        TenantContext.set(8L);
        assertThat(mapper.selectById(current.getId())).isNull();
    }

    @Test
    void optionsReturnOnlyEnabledMatchingRowsInStableOrder() {
        HyperlinkStrategy oldEnabled = strategy("巴西旧策略", 1, true, 100L);
        HyperlinkStrategy disabled = strategy("巴西停用", 1, false, 300L);
        HyperlinkStrategy newEnabled = strategy("巴西新策略", 2, true, 200L);
        mapper.insert(oldEnabled);
        mapper.insert(disabled);
        mapper.insert(newEnabled);

        assertThat(mapper.selectOptions("巴西", 10))
                .extracting(HyperlinkStrategy::getStrategyName)
                .containsExactly("巴西新策略", "巴西旧策略");
    }

    @Test
    void optimisticUpdateRejectsStaleVersionAndIncrementsCurrentVersion() {
        HyperlinkStrategy row = strategy("待编辑", 1, true, 100L);
        mapper.insert(row);

        HyperlinkStrategy update = strategy("已编辑", 3, false, 200L);
        update.setId(row.getId());
        assertThat(mapper.updateByIdAndVersion(update, 1)).isEqualTo(1);
        assertThat(mapper.updateByIdAndVersion(update, 1)).isZero();

        HyperlinkStrategy found = mapper.selectById(row.getId());
        assertThat(found.getStrategyName()).isEqualTo("已编辑");
        assertThat(found.getTaskType()).isEqualTo(3);
        assertThat(found.getEnabled()).isFalse();
        assertThat(found.getVersion()).isEqualTo(2);
    }

    @Test
    void softDeleteHidesRowAndAllowsActiveNameReuse() {
        HyperlinkStrategy row = strategy("可复用", 1, true, 100L);
        mapper.insert(row);

        assertThat(mapper.softDelete(row.getId(), 200L)).isEqualTo(1);
        assertThat(mapper.selectById(row.getId())).isNull();
        assertThat(mapper.existsByName("可复用", null)).isFalse();

        HyperlinkStrategy replacement = strategy("可复用", 2, true, 300L);
        assertThat(mapper.insert(replacement)).isEqualTo(1);
        assertThat(replacement.getId()).isNotEqualTo(row.getId());
    }

    @Test
    void taskSnapshotIsOwnedByOneTaskAndNeverLeaksIntoTemplateQueries() {
        HyperlinkStrategy snapshot = strategy("临时名称", 1, true, 100L);
        snapshot.setStrategyScope(2);
        snapshot.setStrategyName(null);
        snapshot.setSourceStrategyId(88L);
        snapshot.setConcurrentNum(0);
        mapper.insert(snapshot);

        assertThat(mapper.attachTaskOwner(snapshot.getId(), 501L, 120L)).isEqualTo(1);
        assertThat(mapper.selectById(snapshot.getId())).isNull();
        assertThat(mapper.selectOptions(null, 100)).isEmpty();

        HyperlinkStrategy update = strategy("不会写入快照", 2, true, 200L);
        update.setId(snapshot.getId());
        update.setStrategyName(null);
        update.setConcurrentNum(0);
        assertThat(mapper.updateTaskSnapshot(update, 501L)).isEqualTo(1);

        assertThat(mapper.selectTaskSnapshotByOwner(501L))
                .satisfies(found -> {
                    assertThat(found.getStrategyScope()).isEqualTo(2);
                    assertThat(found.getOwnerTaskId()).isEqualTo(501L);
                    assertThat(found.getSourceStrategyId()).isEqualTo(88L);
                    assertThat(found.getTaskType()).isEqualTo(2);
                    assertThat(found.getConcurrentNum()).isZero();
                    assertThat(found.getVersion()).isEqualTo(2);
                });
    }

    private static HyperlinkStrategy strategy(
            String name, int taskType, boolean enabled, long timestamp) {
        HyperlinkStrategy row = new HyperlinkStrategy();
        row.setStrategyScope(1);
        row.setStrategyName(name);
        row.setTaskType(taskType);
        row.setAccountFilter("{\"filterSchemaVersion\":1}");
        row.setConcurrentNum(10);
        row.setMaxUseAccount(taskType == 3 ? 50 : 0);
        row.setAccountMaxSendNum(0);
        row.setTaskIntervalMinutes(taskType == 3 ? 60 : 0);
        row.setEnabled(enabled);
        row.setVersion(1);
        row.setCreatedAt(timestamp);
        row.setUpdatedAt(timestamp);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE hyperlink_strategy (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    strategy_scope TINYINT NOT NULL,
                    owner_task_id BIGINT,
                    source_strategy_id BIGINT,
                    strategy_name VARCHAR(128),
                    task_type TINYINT NOT NULL,
                    account_filter VARCHAR(8192) NOT NULL,
                    concurrent_num INT NOT NULL DEFAULT 10,
                    max_use_account INT NOT NULL DEFAULT 0,
                    account_max_send_num INT NOT NULL DEFAULT 0,
                    task_interval_minutes INT NOT NULL DEFAULT 0,
                    is_enabled TINYINT NOT NULL DEFAULT 1,
                    version INT NOT NULL DEFAULT 1,
                    created_by BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT,
                    template_active TINYINT GENERATED ALWAYS AS
                        (CASE WHEN strategy_scope = 1 AND deleted_at IS NULL THEN 1 ELSE NULL END),
                    UNIQUE (tenant_id, strategy_name, template_active),
                    UNIQUE (tenant_id, owner_task_id),
                    CHECK (concurrent_num BETWEEN 0 AND 100),
                    CHECK (max_use_account = 0 OR concurrent_num = 0
                        OR max_use_account >= concurrent_num),
                    CHECK ((task_type = 3 AND max_use_account >= 1
                            AND ((strategy_scope = 1 AND task_interval_minutes >= 30)
                                OR (strategy_scope = 2 AND task_interval_minutes >= 1)))
                        OR (task_type IN (1, 2) AND task_interval_minutes = 0))
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:hyperlink_strategy_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/hyperlink/strategy/HyperlinkStrategyMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        HyperlinkStrategyMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkStrategyMapper.class);
        }
    }
}
