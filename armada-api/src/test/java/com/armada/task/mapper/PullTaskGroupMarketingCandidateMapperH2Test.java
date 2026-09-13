package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 候选群统计、管理员过滤与候选账号列表使用真实 Mapper 和租户插件。 */
@SpringJUnitConfig(PullTaskGroupMarketingCandidateMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
public class PullTaskGroupMarketingCandidateMapperH2Test {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupMarketingCandidateMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("CREATE ALIAS SUBSTRING_INDEX FOR \"com.armada.task.mapper.PullTaskGroupMarketingCandidateMapperH2Test.substringIndex\"");
        execute("CREATE TABLE account(id BIGINT PRIMARY KEY, tenant_id BIGINT, ws_phone VARCHAR(50), account_group_id BIGINT, deleted_at BIGINT)");
        execute("CREATE TABLE account_state(account_id BIGINT, tenant_id BIGINT, account_state INT, login_state INT)");
        execute("CREATE TABLE wa_group(id BIGINT, tenant_id BIGINT, group_jid VARCHAR(50), deleted_at BIGINT, display_name VARCHAR(50), avatar_url VARCHAR(50), group_classification INT)");
        execute("CREATE TABLE wa_account_group_binding(tenant_id BIGINT, group_id BIGINT, participant_id BIGINT, account_id BIGINT, last_observed_at BIGINT)");
        execute("CREATE TABLE wa_group_participant(id BIGINT, tenant_id BIGINT, group_id BIGINT, presence_status INT, role INT)");
        execute("CREATE TABLE group_link(id BIGINT, tenant_id BIGINT, group_id BIGINT, deleted_at BIGINT)");
        execute("CREATE TABLE group_link_preview(tenant_id BIGINT, group_link_id BIGINT, owner_phone VARCHAR(50))");
        execute("CREATE TABLE wa_group_profile(tenant_id BIGINT, group_id BIGINT, subject VARCHAR(50), wa_created_at BIGINT, checked_member_count INT, member_count INT, announce_only BOOLEAN, metadata_observed_at BIGINT, last_checked_at BIGINT, health_status INT, banned BOOLEAN, last_error_code VARCHAR(50))");
        execute("CREATE TABLE join_task(id BIGINT, tenant_id BIGINT, name VARCHAR(50), deleted_at BIGINT)");
        execute("CREATE TABLE join_task_result(id BIGINT, tenant_id BIGINT, group_jid VARCHAR(50), join_task_id BIGINT, joined_at BIGINT, promoted_at BIGINT, status VARCHAR(20), is_admin INT)");
        execute("CREATE TABLE pull_task_group_marketing_group_occupancy(tenant_id BIGINT, group_jid VARCHAR(50), occupancy_type VARCHAR(20), reservation_token VARCHAR(50), task_id BIGINT, task_name_snapshot VARCHAR(50), created_by BIGINT, last_validated_at BIGINT, last_validation_reason VARCHAR(50), released_at BIGINT)");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (long tenant : List.of(7L, 8L)) {
            jdbc.update("INSERT INTO wa_group VALUES(1, ?, 'group@g.us', NULL, 'test', NULL, 1)", tenant);
            jdbc.update("INSERT INTO group_link VALUES(1, ?, 1, NULL)", tenant);
            for (int state : List.of(2, 3, 5, 6, 7, 8)) {
                for (int login : List.of(1, 2, 3)) {
                    long id = tenant * 100 + state * 10 + login;
                    jdbc.update("INSERT INTO account VALUES(?, ?, ?, 10, NULL)", id, tenant, "phone" + id);
                    jdbc.update("INSERT INTO account_state VALUES(?, ?, ?, ?)", id, tenant, state, login);
                    jdbc.update("INSERT INTO wa_account_group_binding VALUES(?, 1, ?, ?, 100)", tenant, id, id);
                    jdbc.update("INSERT INTO wa_group_participant VALUES(?, ?, 1, 1, 2)", id, tenant);
                }
            }
        }
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void candidateAccountsAllowTakeoverOnlyOnlineAndKeepNormalOfflineWaitingCandidates() {
        assertThat(mapper.selectAccountsByGroupJids(List.of("group@g.us")))
                .extracting(row -> row.getAccountId())
                .containsExactly(721L, 761L, 771L, 722L, 723L);
    }

    @Test
    void groupSummaryAndManagerFilterUseTheSameEligibility() {
        PullTaskGroupMarketingCandidateQuery query = new PullTaskGroupMarketingCandidateQuery();
        query.setAccountGroupId(10L);
        query.setManagerPhone("phone771");
        var rows = mapper.selectPageByTenant(7L, query, 0, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEligibleAccountCount()).isEqualTo(5);
        assertThat(rows.get(0).getOnlineAccountCount()).isEqualTo(3);
        query.setManagerPhone("phone772");
        assertThat(mapper.selectPageByTenant(7L, query, 0, 10)).isEmpty();
        query.setManagerPhone("phone871");
        assertThat(mapper.selectPageByTenant(7L, query, 0, 10)).isEmpty();
    }

    /** H2 缺失的 MySQL 函数，仅测试环境模拟本查询使用的正向首段语义。 */
    public static String substringIndex(String value, String delimiter, int count) {
        if (value == null) return null;
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
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
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:pull_group_candidate_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
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
            factory.setMapperLocations(new ClassPathResource("mapper/task/PullTaskGroupMarketingCandidateMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskGroupMarketingCandidateMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupMarketingCandidateMapper.class);
        }

    }
}
