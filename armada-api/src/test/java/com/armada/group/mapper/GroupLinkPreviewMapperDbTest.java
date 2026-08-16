package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
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

/** GroupLinkPreviewMapper 的 H2 MySQL 模式测试，加载真实 XML 和租户拦截器。 */
@SpringJUnitConfig(GroupLinkPreviewMapperDbTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupLinkPreviewMapperDbTest {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    @Autowired
    private GroupLinkPreviewMapper previewMapper;

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
    void selectByGroupLinkIdsReturnsNamedRowsOnlyWithinCurrentTenant() throws SQLException {
        execute("""
                INSERT INTO group_link_preview (
                    tenant_id, group_link_id, wa_subject, created_at, updated_at
                ) VALUES
                    (7, 101, '当前租户群 A', 1, 1),
                    (7, 102, '   ', 1, 1),
                    (7, 103, '当前租户群 B', 1, 1),
                    (8, 101, '其他租户群', 1, 1)
                """);

        assertThat(previewMapper.selectByGroupLinkIds(List.of())).isEmpty();
        assertThat(previewMapper.selectByGroupLinkIds(List.of(101L, 102L, 103L)))
                .satisfiesExactly(
                        row -> {
                            assertThat(row.getGroupLinkId()).isEqualTo(101L);
                            assertThat(row.getWaSubject()).isEqualTo("当前租户群 A");
                        },
                        row -> {
                            assertThat(row.getGroupLinkId()).isEqualTo(103L);
                            assertThat(row.getWaSubject()).isEqualTo("当前租户群 B");
                        });
    }

    @Test
    void upsert_insertsAndUpdatesUniquePreviewRow() {
        GroupLink link = insertLink("chat.whatsapp.com/PreviewRoundTrip");
        long now = System.currentTimeMillis();

        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(link.getId());
        row.setGroupJid("120363-preview@g.us");
        row.setInviteCode("PreviewRoundTrip");
        row.setWaSubject("预览群");
        row.setMemberSize(12);
        row.setOwnerPhone("919999999999");
        row.setOwnerPhoneObserved(true);
        row.setAnnounceOnly(Boolean.TRUE);
        row.setAvatarUrl("https://example.test/avatar.jpg");
        row.setLastPreviewAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        assertThat(previewMapper.upsert(row)).isEqualTo(1);
        GroupLinkPreview inserted = previewMapper.selectByGroupLinkId(link.getId());
        assertThat(inserted).isNotNull();
        assertThat(inserted.getGroupJid()).isEqualTo("120363-preview@g.us");
        assertThat(inserted.getWaSubject()).isEqualTo("预览群");
        assertThat(inserted.getMemberSize()).isEqualTo(12);
        assertThat(inserted.getAnnounceOnly()).isTrue();

        GroupLinkPreview update = new GroupLinkPreview();
        update.setGroupLinkId(link.getId());
        update.setGroupJid("120363-preview-updated@g.us");
        update.setInviteCode("PreviewRoundTrip");
        update.setWaSubject("预览群-更新");
        update.setMemberSize(18);
        update.setOwnerPhone("918888888888");
        update.setOwnerPhoneObserved(true);
        update.setAnnounceOnly(Boolean.FALSE);
        update.setAvatarUrl("https://example.test/avatar2.jpg");
        update.setLastPreviewAt(now + 1_000);
        update.setCreatedAt(now);
        update.setUpdatedAt(now + 1_000);

        previewMapper.upsert(update);
        GroupLinkPreview updated = previewMapper.selectByGroupLinkId(link.getId());
        assertThat(updated.getId()).isEqualTo(inserted.getId());
        assertThat(updated.getGroupJid()).isEqualTo("120363-preview-updated@g.us");
        assertThat(updated.getWaSubject()).isEqualTo("预览群-更新");
        assertThat(updated.getMemberSize()).isEqualTo(18);
        assertThat(updated.getAnnounceOnly()).isFalse();
    }

    @Test
    void upsertAvatarUrl_updatesOnlyAvatarAndProtocolPreviewPreservesLocalAvatarWhenBlank() {
        GroupLink link = insertLink("chat.whatsapp.com/PreviewAvatarOnly");
        long now = System.currentTimeMillis();

        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(link.getId());
        row.setGroupJid("120363-avatar@g.us");
        row.setInviteCode("PreviewAvatarOnly");
        row.setWaSubject("头像群");
        row.setMemberSize(20);
        row.setOwnerPhone("917777777777");
        row.setOwnerPhoneObserved(true);
        row.setAnnounceOnly(Boolean.FALSE);
        row.setAvatarUrl("https://example.test/protocol.jpg");
        row.setLastPreviewAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        previewMapper.upsert(row);

        previewMapper.upsertAvatarUrl(link.getId(), "https://example.test/local.jpg", now + 1_000);
        GroupLinkPreview avatarUpdated = previewMapper.selectByGroupLinkId(link.getId());
        assertThat(avatarUpdated.getGroupJid()).isEqualTo("120363-avatar@g.us");
        assertThat(avatarUpdated.getWaSubject()).isEqualTo("头像群");
        assertThat(avatarUpdated.getMemberSize()).isEqualTo(20);
        assertThat(avatarUpdated.getAvatarUrl()).isEqualTo("https://example.test/local.jpg");

        GroupLinkPreview protocolRefresh = new GroupLinkPreview();
        protocolRefresh.setGroupLinkId(link.getId());
        protocolRefresh.setGroupJid("120363-avatar-updated@g.us");
        protocolRefresh.setInviteCode("PreviewAvatarOnly");
        protocolRefresh.setWaSubject("头像群-协议刷新");
        protocolRefresh.setMemberSize(21);
        protocolRefresh.setOwnerPhone("916666666666");
        protocolRefresh.setOwnerPhoneObserved(true);
        protocolRefresh.setAnnounceOnly(Boolean.TRUE);
        protocolRefresh.setLastPreviewAt(now + 2_000);
        protocolRefresh.setCreatedAt(now);
        protocolRefresh.setUpdatedAt(now + 2_000);
        previewMapper.upsert(protocolRefresh);

        GroupLinkPreview protocolUpdated = previewMapper.selectByGroupLinkId(link.getId());
        assertThat(protocolUpdated.getGroupJid()).isEqualTo("120363-avatar-updated@g.us");
        assertThat(protocolUpdated.getWaSubject()).isEqualTo("头像群-协议刷新");
        assertThat(protocolUpdated.getMemberSize()).isEqualTo(21);
        assertThat(protocolUpdated.getAvatarUrl()).isEqualTo("https://example.test/local.jpg");

        previewMapper.upsertAvatarUrl(link.getId(), null, now + 3_000);
        GroupLinkPreview cleared = previewMapper.selectByGroupLinkId(link.getId());
        assertThat(cleared.getGroupJid()).isEqualTo("120363-avatar-updated@g.us");
        assertThat(cleared.getAvatarUrl()).isNull();
    }

    @Test
    void upsert_appliesOwnerPhoneObservationThreeState() {
        GroupLink link = insertLink("chat.whatsapp.com/PreviewOwnerObservation");
        long now = System.currentTimeMillis();

        previewMapper.upsert(ownerPreview(link.getId(), "8613800000000", true, now));

        previewMapper.upsert(ownerPreview(link.getId(), null, false, now + 1_000));
        assertThat(previewMapper.selectByGroupLinkId(link.getId()).getOwnerPhone())
                .isEqualTo("8613800000000");

        previewMapper.upsert(ownerPreview(link.getId(), null, true, now + 2_000));
        assertThat(previewMapper.selectByGroupLinkId(link.getId()).getOwnerPhone()).isNull();

        previewMapper.upsert(ownerPreview(link.getId(), "51943333070", true, now + 3_000));
        assertThat(previewMapper.selectByGroupLinkId(link.getId()).getOwnerPhone())
                .isEqualTo("51943333070");
    }

    private static GroupLinkPreview ownerPreview(
            Long groupLinkId,
            String ownerPhone,
            boolean ownerPhoneObserved,
            long now) {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid("120363-owner-observation@g.us");
        row.setInviteCode("PreviewOwnerObservation");
        row.setWaSubject("群主观察态群");
        row.setOwnerPhone(ownerPhone);
        row.setOwnerPhoneObserved(ownerPhoneObserved);
        row.setLastPreviewAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private GroupLink insertLink(String url) {
        GroupLink link = new GroupLink();
        link.setLinkUrl(url);
        long now = System.currentTimeMillis();
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        groupLinkMapper.insert(link);
        return link;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_link (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    link_url VARCHAR(255) NOT NULL,
                    group_name VARCHAR(128),
                    label_id BIGINT,
                    import_batch_id BIGINT,
                    origin TINYINT NOT NULL DEFAULT 1,
                    membership_state TINYINT NOT NULL DEFAULT 1,
                    remark VARCHAR(255),
                    created_by BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT,
                    CONSTRAINT uq_group_link_url UNIQUE (tenant_id, link_url)
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
            dataSource.setURL("jdbc:h2:mem:group_link_preview_mapper_test;"
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
            factory.setMapperLocations(
                    new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                    new ClassPathResource("mapper/group/GroupLinkPreviewMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupLinkMapper groupLinkMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkMapper.class);
        }

        @Bean
        GroupLinkPreviewMapper groupLinkPreviewMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkPreviewMapper.class);
        }
    }
}
