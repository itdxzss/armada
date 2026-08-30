package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskDetailRow;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 详情 JOIN 使用真实 Mapper XML，并以显式 tenant 条件隔离任务、内容和运行态。 */
@SpringJUnitConfig(HyperlinkTaskDetailMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTaskDetailMapperH2Test {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private HyperlinkTaskMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS", taskSchema(), strategySchema(), contentSchema(), runtimeSchema(),
                "INSERT INTO hyperlink_task VALUES "
                        + "(11,7,'任务 A',2,15,NULL,31,NULL,NULL,NULL,NULL,NULL,111,"
                        + "20,500,700,TRUE,4,8,1000,2000)",
                "INSERT INTO hyperlink_strategy VALUES "
                        + "(111,7,2,11,3,60,'{\"filterSchemaVersion\":1,\"groupIds\":[9]}',5,3,100)",
                "INSERT INTO hyperlink_task_content VALUES "
                        + "(11,7,1,3,'标题','正文',NULL,NULL,"
                        + "'[{\"type\":\"CTA_URL\",\"displayText\":\"查看\","
                        + "\"targetValue\":\"https://example.com\",\"useShortLink\":true,\"sort\":1}]',"
                        + "NULL,NULL,55,1000,2000)",
                "INSERT INTO hyperlink_task_runtime VALUES (11,7,TRUE,0,2,1000,2000)",
                "INSERT INTO hyperlink_task VALUES "
                        + "(12,8,'其他租户',1,0,NULL,NULL,NULL,NULL,NULL,NULL,NULL,112,"
                        + "20,500,700,FALSE,1,9,1000,1000)",
                "INSERT INTO hyperlink_strategy VALUES "
                        + "(112,8,2,12,1,0,'{\"filterSchemaVersion\":1}',0,1,0)",
                "INSERT INTO hyperlink_task_content VALUES "
                        + "(12,8,1,1,'标题','正文','描述','https://example.com','[]',"
                        + "NULL,66,NULL,1000,1000)",
                "INSERT INTO hyperlink_task_runtime VALUES (12,8,FALSE,0,0,1000,1000)");
    }

    @Test
    void readsCompleteFrozenFormFactsWithoutScanningExecutionTables() {
        HyperlinkTaskDetailRow row = mapper.selectDetailById(7L, 11L);

        assertThat(row.taskName()).isEqualTo("任务 A");
        assertThat(row.messageType()).isEqualTo(3);
        assertThat(row.buttons()).contains("targetValue");
        assertThat(row.accountFilter()).contains("groupIds");
        assertThat(row.enabled()).isTrue();
        assertThat(row.runStatus()).isZero();
        assertThat(row.bodyMainAssetId()).isEqualTo(55L);
    }

    @Test
    void returnsNoRowAcrossTenantBoundary() {
        assertThat(mapper.selectDetailById(8L, 11L)).isNull();
        assertThat(mapper.selectDetailById(7L, 12L)).isNull();
    }

    private String taskSchema() {
        return """
                CREATE TABLE hyperlink_task (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, task_name VARCHAR(128),
                  start_mode INT, task_delay_minutes INT,
                  task_planned_end_at BIGINT, data_package_id BIGINT,
                  data_package_generation INT, data_package_name_snapshot VARCHAR(128),
                  target_country_iso2s_snapshot VARCHAR(1024), source_template_id BIGINT,
                  source_template_version INT, hyperlink_strategy_id BIGINT,
                  account_send_concurrency INT,
                  msg_interval_min_ms INT, msg_interval_max_ms INT,
                  is_short_link_enabled BOOLEAN, version INT, created_by BIGINT,
                  created_at BIGINT, updated_at BIGINT)
                """;
    }

    private String strategySchema() {
        return """
                CREATE TABLE hyperlink_strategy (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, strategy_scope INT NOT NULL,
                  owner_task_id BIGINT, task_type INT NOT NULL, task_interval_minutes INT NOT NULL,
                  account_filter VARCHAR(4096) NOT NULL, max_use_account INT NOT NULL,
                  concurrent_num INT NOT NULL, account_max_send_num INT NOT NULL)
                """;
    }

    private String contentSchema() {
        return """
                CREATE TABLE hyperlink_task_content (
                  hyperlink_task_id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  message_schema_version INT, message_type INT, title VARCHAR(1024), content CLOB,
                  link_description VARCHAR(512), promotion_link VARCHAR(2048), buttons VARCHAR(4096),
                  card_text VARCHAR(500), link_preview_asset_id BIGINT, body_main_asset_id BIGINT,
                  created_at BIGINT, updated_at BIGINT)
                """;
    }

    private String runtimeSchema() {
        return """
                CREATE TABLE hyperlink_task_runtime (
                  hyperlink_task_id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  is_enabled BOOLEAN, run_status INT, provision_status INT,
                  created_at BIGINT, updated_at BIGINT)
                """;
    }

    private void execute(String... sqls) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_task_detail;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskMapper.class);
        }
    }
}
