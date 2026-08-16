package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 刷新群链接准入过滤的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(GroupLinkHealthRefreshBlockMapperDbTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupLinkHealthRefreshBlockMapperDbTest {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupLinkHealthMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void blockedIdsCoverBannedAndUnavailableButKeepLinkInvalidRefreshable() throws SQLException {
        execute("""
                INSERT INTO group_link
                  (id, tenant_id, group_id, group_invite_id, deleted_at)
                VALUES
                  (101, 7, 1001, NULL, NULL),
                  (102, 7, NULL, 2002, NULL),
                  (103, 7, 1003, NULL, NULL),
                  (104, 7, NULL, 2004, NULL),
                  (105, 8, 1005, NULL, NULL)
                """);
        execute("""
                INSERT INTO wa_group (id, tenant_id, group_jid)
                VALUES
                  (1001, 7, 'banned@g.us'),
                  (1003, 7, 'available@g.us'),
                  (1005, 8, 'other@g.us')
                """);
        execute("""
                INSERT INTO wa_group_profile
                  (id, tenant_id, group_id, banned, health_status)
                VALUES
                  (3001, 7, 1001, 1, NULL),
                  (3003, 7, 1003, 0, 1),
                  (3005, 8, 1005, 1, 3)
                """);
        execute("""
                INSERT INTO wa_group_invite
                  (id, tenant_id, invite_code, banned, health_status)
                VALUES
                  (2002, 7, 'unavailable', 0, 3),
                  (2004, 7, 'invalid', 0, 2)
                """);

        assertThat(mapper.selectLinkRefreshBlockedIds(
                        List.of(101L, 102L, 103L, 104L, 105L)))
                .containsExactly(101L, 102L);
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_link (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    group_id BIGINT, group_invite_id BIGINT, deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL
                )
                """);
        execute("""
                CREATE TABLE wa_group_profile (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL, banned TINYINT, health_status TINYINT
                )
                """);
        execute("""
                CREATE TABLE wa_group_invite (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    invite_code VARCHAR(128) NOT NULL, banned TINYINT, health_status TINYINT
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 本测试所需的最小 MyBatis 与租户拦截器配置。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_link_health_refresh_block_test;"
                    + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
                    "mapper/group/GroupLinkHealthMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupLinkHealthMapper groupLinkHealthMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkHealthMapper.class);
        }
    }
}
