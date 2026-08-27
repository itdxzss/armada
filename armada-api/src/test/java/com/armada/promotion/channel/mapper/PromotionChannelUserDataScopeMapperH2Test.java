package com.armada.promotion.channel.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.entity.PromotionChannel;
import com.armada.shared.security.DataScope;
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

/** 使用生产 Mapper XML 验证推广渠道管理面的用户与租户隔离。 */
@SpringJUnitConfig(PromotionChannelUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PromotionChannelUserDataScopeMapperH2Test {

    private static final DataScope USER_ONE_SCOPE = DataScope.self(1001L);
    private static final DataScope USER_TWO_SCOPE = DataScope.self(1002L);
    private static final DataScope ADMIN_SCOPE = DataScope.all(9001L);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PromotionChannelMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void managementPageDetailAndProbeRespectSelfAndAdminScopes() {
        assertThat(count(USER_ONE_SCOPE)).isEqualTo(1);
        assertThat(count(USER_TWO_SCOPE)).isEqualTo(1);
        assertThat(count(ADMIN_SCOPE)).isEqualTo(2);

        assertThat(mapper.selectDetailById(101L, USER_ONE_SCOPE)).isNotNull();
        assertThat(mapper.selectDetailById(102L, USER_ONE_SCOPE)).isNull();
        assertThat(mapper.selectDetailById(102L, ADMIN_SCOPE)).isNotNull();

        assertThat(mapper.selectProbeConfigByChannelIdForScope(101L, USER_ONE_SCOPE)).isNotNull();
        assertThat(mapper.selectProbeConfigByChannelIdForScope(102L, USER_ONE_SCOPE)).isNull();
        assertThat(mapper.selectProbeConfigByChannelIdForScope(102L, ADMIN_SCOPE)).isNotNull();

    }

    @Test
    void missingSystemAndCrossTenantScopesFailClosed() {
        assertThat(count(null)).isZero();
        assertThat(count(DataScope.system("promotion maintenance"))).isZero();
        assertThat(mapper.selectDetailById(101L, null)).isNull();
        assertThat(mapper.selectProbeConfigByChannelIdForScope(
                101L, DataScope.system("promotion maintenance"))).isNull();

        TenantContext.set(8L);
        assertThat(count(ADMIN_SCOPE)).isEqualTo(1);
        assertThat(mapper.selectDetailById(101L, ADMIN_SCOPE)).isNull();
        assertThat(mapper.selectDetailById(103L, ADMIN_SCOPE)).isNotNull();
    }

    @Test
    void updateSqlPreservesOwnerWhileRecordingTheActualActor() {
        PromotionChannel row = new PromotionChannel();
        row.setId(102L);
        row.setOwnerUserId(9001L);
        row.setChannelName("admin-updated");
        row.setPromotionDomainId(202L);
        row.setThemeColor("#112233");
        row.setIsAppDownloadShown(0);
        row.setTargetCountry("BR");
        row.setPreselectedCountry("BR");
        row.setPlatform(3);
        row.setIsInAppOpenAllowed(1);
        row.setIsMarketingAllowed(0);
        row.setStatus(1);
        row.setUpdatedBy(9001L);
        row.setUpdatedAt(99L);

        assertThat(mapper.updateChannel(row)).isEqualTo(1);
        PromotionChannel saved = mapper.selectActiveChannelById(102L, ADMIN_SCOPE);
        assertThat(saved.getOwnerUserId()).isEqualTo(1002L);
        assertThat(saved.getUpdatedBy()).isEqualTo(9001L);
    }

    private long count(DataScope scope) {
        PromotionChannelQuery query = new PromotionChannelQuery();
        query.applyDataScope(scope);
        return mapper.countPage(query);
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE promotion_landing_template (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  template_code VARCHAR(64) NOT NULL,
                  template_name VARCHAR(128) NOT NULL,
                  status INT NOT NULL,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE promotion_domain (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  domain_host VARCHAR(255) NOT NULL,
                  landing_template_id BIGINT NOT NULL,
                  is_active INT NOT NULL,
                  created_by BIGINT,
                  updated_by BIGINT,
                  created_at BIGINT,
                  updated_at BIGINT,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE promotion_channel (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  channel_code VARCHAR(32) NOT NULL,
                  channel_name VARCHAR(128) NOT NULL,
                  owner_user_id BIGINT NOT NULL,
                  promotion_domain_id BIGINT NOT NULL,
                  theme_color VARCHAR(7),
                  is_app_download_shown INT,
                  target_country_value VARCHAR(16),
                  preselected_country_value VARCHAR(16),
                  platform INT,
                  is_in_app_open_allowed INT,
                  is_marketing_allowed INT,
                  status INT,
                  created_by BIGINT,
                  updated_by BIGINT,
                  created_at BIGINT,
                  updated_at BIGINT,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE promotion_channel_tracking_config (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  channel_id BIGINT NOT NULL,
                  provider_type INT,
                  tracking_id VARCHAR(128),
                  access_token_ciphertext VARBINARY(512),
                  encryption_key_id VARCHAR(64),
                  token_fingerprint VARBINARY(64),
                  token_expires_at BIGINT,
                  lead_event_name VARCHAR(64),
                  login_request_event_name VARCHAR(64),
                  login_success_event_name VARCHAR(64),
                  last_probe_status INT,
                  last_probe_event_name VARCHAR(64),
                  last_probe_event_id VARCHAR(128),
                  last_probe_error_code VARCHAR(64),
                  last_probe_error_message VARCHAR(255),
                  last_probed_at BIGINT,
                  created_by BIGINT,
                  updated_by BIGINT,
                  created_at BIGINT,
                  updated_at BIGINT,
                  deleted_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO promotion_landing_template
                  (id, tenant_id, template_code, template_name, status, deleted_at)
                VALUES
                  (301, 7, 'T1', 'tenant-7-template', 1, NULL),
                  (303, 8, 'T2', 'tenant-8-template', 1, NULL)
                """, """
                INSERT INTO promotion_domain
                  (id, tenant_id, domain_host, landing_template_id, is_active,
                   created_by, updated_by, created_at, updated_at, deleted_at)
                VALUES
                  (201, 7, 'u1.example.test', 301, 1, 1001, 1001, 1, 1, NULL),
                  (202, 7, 'u2.example.test', 301, 1, 1002, 1002, 1, 1, NULL),
                  (203, 8, 'other.example.test', 303, 1, 1001, 1001, 1, 1, NULL)
                """, """
                INSERT INTO promotion_channel
                  (id, tenant_id, channel_code, channel_name, owner_user_id,
                   promotion_domain_id, theme_color, is_app_download_shown,
                   target_country_value, preselected_country_value, platform,
                   is_in_app_open_allowed, is_marketing_allowed, status,
                   created_by, updated_by, created_at, updated_at, deleted_at)
                VALUES
                  (101, 7, 'u1code', 'u1', 1001, 201, '#111111', 1,
                   'IN', 'IN', 1, 1, 1, 1, 1001, 1001, 1, 1, NULL),
                  (102, 7, 'u2code', 'u2', 1002, 202, '#222222', 1,
                   'BR', 'BR', 1, 1, 1, 1, 1002, 1002, 2, 2, NULL),
                  (103, 8, 'other', 'other-tenant', 1001, 203, '#333333', 1,
                   'US', 'US', 1, 1, 1, 1, 1001, 1001, 3, 3, NULL)
                """, """
                INSERT INTO promotion_channel_tracking_config
                  (id, tenant_id, channel_id, provider_type, tracking_id,
                   access_token_ciphertext, encryption_key_id, token_fingerprint,
                   lead_event_name, login_request_event_name, login_success_event_name,
                   created_by, updated_by, created_at, updated_at, deleted_at)
                VALUES
                  (401, 7, 101, 1, 'pixel-u1', X'01', 'key-v1', X'11',
                   'Lead', 'InitiateCheckout', 'CompleteRegistration', 1001, 1001, 1, 1, NULL),
                  (402, 7, 102, 1, 'pixel-u2', X'02', 'key-v1', X'22',
                   'Lead', 'InitiateCheckout', 'CompleteRegistration', 1002, 1002, 2, 2, NULL),
                  (403, 8, 103, 1, 'pixel-other', X'03', 'key-v1', X'33',
                   'Lead', 'InitiateCheckout', 'CompleteRegistration', 1001, 1001, 3, 3, NULL)
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

    /** 只加载推广渠道生产 XML 与生产租户插件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:promotion_channel_user_scope_mapper_test;"
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
                    "mapper/promotion/channel/PromotionChannelMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PromotionChannelMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(PromotionChannelMapper.class);
        }
    }
}
