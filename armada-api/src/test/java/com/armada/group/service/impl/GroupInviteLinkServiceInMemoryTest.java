package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupInvitePort;
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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 当前群邀请码事实和健康状态原子写入的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(GroupInviteLinkServiceInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupInviteLinkServiceInMemoryTest {

    private static final long GROUP_LINK_ID = 51L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupInviteLinkService service;

    @Autowired
    private GroupLinkPreviewMapper previewMapper;

    @Autowired
    private GroupLinkHealthMapper healthMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_link (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    link_url VARCHAR(255) NOT NULL,
                    group_id BIGINT,
                    group_invite_id BIGINT,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL
                )
                """);
        execute("""
                CREATE TABLE wa_group_profile (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    current_invite_id BIGINT
                )
                """);
        execute("""
                CREATE TABLE wa_group_invite (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    invite_code VARCHAR(128) NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE group_link_preview (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(128),
                    invite_code VARCHAR(64),
                    invite_code_observed_at BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_group_link_preview_link UNIQUE (tenant_id, group_link_id)
                )
                """);
        execute("""
                CREATE TABLE group_link_health (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    health_status TINYINT,
                    is_banned TINYINT,
                    current_count INT,
                    last_check_at BIGINT,
                    last_health_error VARCHAR(255),
                    health_failure_count INT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_group_link_health_link UNIQUE (tenant_id, group_link_id)
                )
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void passiveNewInviteObservationRestoresInvalidHealthWithTheCurrentCode() {
        GroupLinkHealth invalid = new GroupLinkHealth();
        invalid.setGroupLinkId(GROUP_LINK_ID);
        invalid.setHealthStatus(GroupLinkHealthStatus.LINK_INVALID.code());
        invalid.setBanned(false);
        invalid.setLastCheckAt(1_000L);
        invalid.setLastHealthError("INVITE_REVOKED");
        invalid.setHealthFailureCount(2);
        invalid.setCreatedAt(1_000L);
        invalid.setUpdatedAt(1_000L);
        healthMapper.upsert(invalid);

        service.applyCurrentInvite(new GroupInviteLinkObservation(
                "evt-new-link", null, "120363group@g.us", "NewInviteCode_2026",
                ProtocolBackend.ANDROID, "wgp2_notification", 2_000L));

        GroupLinkPreview preview = previewMapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(preview.getInviteCode()).isEqualTo("NewInviteCode_2026");
        assertThat(preview.getInviteCodeObservedAt()).isEqualTo(2_000L);
        GroupLinkHealth health = healthMapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(health.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.AVAILABLE.code());
        assertThat(health.getLastHealthError()).isNull();
        assertThat(health.getHealthFailureCount()).isZero();
        assertThat(health.getLastCheckAt()).isEqualTo(2_000L);
    }

    @Test
    void staleInviteObservationDoesNotOverwriteNewerInvalidHealth() {
        GroupLinkHealth invalid = new GroupLinkHealth();
        invalid.setGroupLinkId(GROUP_LINK_ID);
        invalid.setHealthStatus(GroupLinkHealthStatus.LINK_INVALID.code());
        invalid.setBanned(false);
        invalid.setLastCheckAt(3_000L);
        invalid.setLastHealthError("INVITE_REVOKED");
        invalid.setHealthFailureCount(2);
        invalid.setCreatedAt(3_000L);
        invalid.setUpdatedAt(3_000L);
        healthMapper.upsert(invalid);

        service.applyCurrentInvite(new GroupInviteLinkObservation(
                "evt-stale-link", GROUP_LINK_ID, "120363group@g.us", "StaleInviteCode",
                ProtocolBackend.ANDROID, "wgp2_notification", 2_000L));

        GroupLinkHealth health = healthMapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(health.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.LINK_INVALID.code());
        assertThat(health.getLastHealthError()).isEqualTo("INVITE_REVOKED");
        assertThat(health.getHealthFailureCount()).isEqualTo(2);
        assertThat(health.getLastCheckAt()).isEqualTo(3_000L);
    }

    @Test
    void successfulJoinBindsTheOriginalGroupLinkToItsWhatsappJid() {
        service.bindGroupJid(GROUP_LINK_ID, "120363joined@g.us", 2_500L);

        GroupLinkPreview preview = previewMapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(preview.getGroupJid()).isEqualTo("120363joined@g.us");
        assertThat(preview.getInviteCode()).isNull();
        assertThat(healthMapper.selectByGroupLinkId(GROUP_LINK_ID)).isNull();
    }

    @Test
    void currentInviteCodeResolvesBackToTheActiveOriginalGroupEntry() throws SQLException {
        execute("INSERT INTO group_link (id, tenant_id, link_url, deleted_at) "
                + "VALUES (51, 7, 'chat.whatsapp.com/OriginalCode', NULL)");
        service.applyCurrentInvite(new GroupInviteLinkObservation(
                "evt-current-code", GROUP_LINK_ID, "120363group@g.us", "CurrentCode",
                ProtocolBackend.ANDROID, "ACTIVE_QUERY", 2_000L));
        execute("INSERT INTO wa_group_invite "
                + "(id, tenant_id, invite_code, updated_at, deleted_at) "
                + "VALUES (61, 7, 'CurrentCode', 2000, NULL)");
        execute("UPDATE group_link SET group_invite_id = 61 WHERE id = 51");

        assertThat(previewMapper.selectActiveGroupLinkIdByInviteCode("CurrentCode"))
                .isEqualTo(GROUP_LINK_ID);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 本测试所需的最小 MyBatis、租户拦截器和邀请码服务配置。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_invite_link_service_test;"
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
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources(
                    "classpath*:mapper/group/GroupLink*Mapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupLinkPreviewMapper groupLinkPreviewMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkPreviewMapper.class);
        }

        @Bean
        GroupLinkHealthMapper groupLinkHealthMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkHealthMapper.class);
        }

        @Bean
        GroupLinkMapper groupLinkMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkMapper.class);
        }

        @Bean
        GroupLinkRegistryService groupLinkRegistryService() {
            GroupLinkRegistryService registry = mock(GroupLinkRegistryService.class);
            when(registry.registerAccountObservedGroup(
                    "120363group@g.us", null, ProtocolBackend.ANDROID, 2_000L))
                    .thenReturn(GROUP_LINK_ID);
            return registry;
        }

        @Bean
        GroupInviteLinkService groupInviteLinkService(
                GroupLinkRegistryService registry,
                GroupLinkPreviewMapper previewMapper,
                GroupLinkMapper groupLinkMapper,
                GroupLinkHealthMapper healthMapper,
                GroupExecutionAccountSelector accountSelector,
                GroupInvitePort invitePort,
                GroupCurrentInvitePersistence currentInvitePersistence) {
            return new GroupInviteLinkServiceImpl(
                    registry, previewMapper, groupLinkMapper, healthMapper, accountSelector,
                    invitePort, currentInvitePersistence);
        }

        @Bean
        GroupCurrentInvitePersistence groupCurrentInvitePersistence() {
            return mock(GroupCurrentInvitePersistence.class);
        }

        @Bean
        GroupExecutionAccountSelector groupExecutionAccountSelector() {
            return mock(GroupExecutionAccountSelector.class);
        }

        @Bean
        GroupInvitePort groupInvitePort() {
            return mock(GroupInvitePort.class);
        }
    }
}
