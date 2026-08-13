package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.AccountGroupMembership;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 完整 metadata 中上控成员与账号群关系对齐查询的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(AccountGroupMembershipControlledSnapshotMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountGroupMembershipControlledSnapshotMapperH2Test {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountGroupMembershipMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selectsOnlyCurrentTenantActiveControlledMembersWithFreshRoles() {
        assertThat(mapper.selectControlledMembershipsByGroupLinkId(201L))
                .extracting(
                        AccountGroupMembership::getAccountId,
                        AccountGroupMembership::getGroupLinkId,
                        AccountGroupMembership::getGroupJid,
                        AccountGroupMembership::getAdmin)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                301L, 201L, "120363snapshot@g.us", true),
                        org.assertj.core.groups.Tuple.tuple(
                                302L, 201L, "120363snapshot@g.us", false));
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE whatsapp_group_member_snapshot (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, group_jid VARCHAR(128) NOT NULL,
                  participant_jid VARCHAR(128) NOT NULL, phone VARCHAR(32),
                  is_admin TINYINT NOT NULL DEFAULT 0
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO account (id, tenant_id, ws_phone, deleted_at) VALUES
                  (301, 7, '1001', NULL),
                  (302, 7, '1002', NULL),
                  (303, 7, '1003', 999),
                  (401, 8, '1004', NULL)
                """, """
                INSERT INTO whatsapp_group_member_snapshot
                  (tenant_id, group_link_id, group_jid, participant_jid, phone, is_admin)
                VALUES
                  (7, 201, '120363snapshot@g.us', '1001@s.whatsapp.net', '1001', 1),
                  (7, 201, '120363snapshot@g.us', '1001@lid', '1001', 0),
                  (7, 201, '120363snapshot@g.us', '1002@s.whatsapp.net', '1002', 0),
                  (7, 201, '120363snapshot@g.us', '1003@s.whatsapp.net', '1003', 1),
                  (7, 201, '120363snapshot@g.us', '1004@s.whatsapp.net', '1004', 1),
                  (7, 201, '120363snapshot@g.us', 'external@s.whatsapp.net', '9999', 1),
                  (8, 201, '120363snapshot@g.us', '1004@s.whatsapp.net', '1004', 1)
                """);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 本测试加载真实账号群关系 Mapper XML，并启用生产租户拦截器。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:controlled_snapshot_membership_mapper_test;"
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
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/AccountGroupMembershipMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        AccountGroupMembershipMapper accountGroupMembershipMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountGroupMembershipMapper.class);
        }
    }
}
