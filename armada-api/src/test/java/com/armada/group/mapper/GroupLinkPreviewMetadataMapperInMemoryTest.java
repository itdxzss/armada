package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.GroupLinkPreview;
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

/** 群详情元数据快照三态更新与观察时间保护的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(GroupLinkPreviewMetadataMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupLinkPreviewMetadataMapperInMemoryTest {

    private static final long GROUP_LINK_ID = 101L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupLinkPreviewMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_link_preview (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(128),
                    invite_code VARCHAR(64),
                    invite_code_observed_at BIGINT,
                    wa_subject VARCHAR(255),
                    wa_description VARCHAR(1024),
                    member_size INT,
                    owner_phone VARCHAR(32),
                    announce_only TINYINT,
                    admin_only_edit_info TINYINT,
                    member_add_mode TINYINT,
                    join_approval_mode TINYINT,
                    ephemeral_duration_seconds INT,
                    group_created_at BIGINT,
                    creator_country_iso2 VARCHAR(2),
                    creator_continent_code VARCHAR(24),
                    avatar_url VARCHAR(512),
                    last_preview_at BIGINT,
                    metadata_observed_at BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_group_link_preview_link UNIQUE (tenant_id, group_link_id)
                )
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void newerObservationWinsAndExplicitNullCanClearObservedFields() {
        GroupLinkPreview newest = metadata("最新群名", "最新描述", 2_000L);
        newest.setOwnerPhone("8613800000000");
        newest.setOwnerPhoneObserved(true);
        newest.setAnnounceOnly(true);
        newest.setAnnounceOnlyObserved(true);
        newest.setAdminOnlyEditInfo(true);
        newest.setAdminOnlyEditInfoObserved(true);
        newest.setMemberAddMode(false);
        newest.setMemberAddModeObserved(true);
        newest.setJoinApprovalMode(true);
        newest.setJoinApprovalModeObserved(true);
        newest.setEphemeralDurationSeconds(86_400);
        newest.setEphemeralDurationObserved(true);
        newest.setGroupCreatedAt(1_700_000_000L);
        newest.setCreatorCountryIso2("CN");
        newest.setCreatorContinentCode("ASIA");
        newest.setCreatorCountryObserved(true);
        mapper.upsertMetadataSnapshot(newest);

        GroupLinkPreview stale = metadata("旧群名", "旧描述", 1_000L);
        stale.setOwnerPhone("51943333070");
        stale.setOwnerPhoneObserved(true);
        stale.setGroupCreatedAt(1_600_000_000L);
        mapper.upsertMetadataSnapshot(stale);

        GroupLinkPreview afterStale = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(afterStale.getWaSubject()).isEqualTo("最新群名");
        assertThat(afterStale.getWaDescription()).isEqualTo("最新描述");
        assertThat(afterStale.getOwnerPhone()).isEqualTo("8613800000000");
        assertThat(afterStale.getGroupCreatedAt()).isEqualTo(1_700_000_000L);
        assertThat(afterStale.getMetadataObservedAt()).isEqualTo(2_000L);

        GroupLinkPreview clear = metadata("再次更新", null, 3_000L);
        clear.setWaDescriptionObserved(true);
        clear.setOwnerPhoneObserved(true);
        clear.setCreatorCountryObserved(true);
        mapper.upsertMetadataSnapshot(clear);

        GroupLinkPreview updated = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(updated.getWaSubject()).isEqualTo("再次更新");
        assertThat(updated.getWaDescription()).isNull();
        assertThat(updated.getOwnerPhone()).isNull();
        assertThat(updated.getCreatorCountryIso2()).isNull();
        assertThat(updated.getCreatorContinentCode()).isNull();
        assertThat(updated.getAnnounceOnly()).isTrue();
        assertThat(updated.getEphemeralDurationSeconds()).isEqualTo(86_400);
        assertThat(updated.getGroupCreatedAt()).isEqualTo(1_700_000_000L);
        assertThat(updated.getMetadataObservedAt()).isEqualTo(3_000L);
    }

    @Test
    void inviteCodeUsesItsOwnObservationClockAcrossEventAndMetadataSources() {
        mapper.upsertInviteLinkChange(inviteChange("event-new", 2_000L));

        GroupLinkPreview staleMetadata = metadata("旧元数据", null, 1_000L);
        staleMetadata.setInviteCode("metadata-stale");
        mapper.upsertMetadataSnapshot(staleMetadata);

        GroupLinkPreview afterStale = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(afterStale.getInviteCode()).isEqualTo("event-new");
        assertThat(afterStale.getInviteCodeObservedAt()).isEqualTo(2_000L);

        GroupLinkPreview newerMetadata = metadata("新元数据", null, 3_000L);
        newerMetadata.setInviteCode("metadata-newest");
        mapper.upsertMetadataSnapshot(newerMetadata);

        GroupLinkPreview updated = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(updated.getInviteCode()).isEqualTo("metadata-newest");
        assertThat(updated.getInviteCodeObservedAt()).isEqualTo(3_000L);

        mapper.upsertInviteLinkChange(inviteChange("event-stale", 2_500L));
        GroupLinkPreview afterStaleEvent = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(afterStaleEvent.getInviteCode()).isEqualTo("metadata-newest");
        assertThat(afterStaleEvent.getInviteCodeObservedAt()).isEqualTo(3_000L);
    }

    @Test
    void confirmedPermissionUpdateWinsOverOlderMetadataAndIsImmediatelyReadable() {
        GroupLinkPreview initial = metadata("群名", null, 1_000L);
        initial.setAnnounceOnly(false);
        initial.setAnnounceOnlyObserved(true);
        initial.setMemberAddMode(true);
        initial.setMemberAddModeObserved(true);
        mapper.upsertMetadataSnapshot(initial);

        mapper.updateAnnounceOnly(GROUP_LINK_ID, true, 2_000L);
        mapper.updateMemberAddMode(GROUP_LINK_ID, false, 2_000L);

        GroupLinkPreview immediatelyVisible = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(immediatelyVisible.getAnnounceOnly()).isTrue();
        assertThat(immediatelyVisible.getMemberAddMode()).isFalse();
        assertThat(immediatelyVisible.getMetadataObservedAt()).isEqualTo(2_000L);

        GroupLinkPreview stale = metadata("旧任务", null, 1_500L);
        stale.setAnnounceOnly(false);
        stale.setAnnounceOnlyObserved(true);
        stale.setMemberAddMode(true);
        stale.setMemberAddModeObserved(true);
        mapper.upsertMetadataSnapshot(stale);

        GroupLinkPreview afterStale = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(afterStale.getAnnounceOnly()).isTrue();
        assertThat(afterStale.getMemberAddMode()).isFalse();
    }

    private static GroupLinkPreview metadata(String subject, String description, long observedAt) {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(GROUP_LINK_ID);
        row.setGroupJid("120363-preview@g.us");
        row.setWaSubject(subject);
        row.setWaDescription(description);
        row.setWaDescriptionObserved(description != null);
        row.setMemberSize(20);
        row.setMetadataObservedAt(observedAt);
        row.setLastPreviewAt(observedAt);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        return row;
    }

    private static GroupLinkPreview inviteChange(String inviteCode, long observedAt) {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(GROUP_LINK_ID);
        row.setGroupJid("120363-preview@g.us");
        row.setInviteCode(inviteCode);
        row.setInviteCodeObservedAt(observedAt);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        return row;
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
            dataSource.setURL("jdbc:h2:mem:group_link_preview_metadata_mapper_test;"
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
                    "mapper/group/GroupLinkPreviewMapper.xml"));
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
    }
}
