package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.account.mapper.AccountMapper;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupCurrentLocalMapper;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.dto.GroupLinkProfileDTO;
import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupDetailProtocolPorts;
import com.armada.group.service.GroupDetailSnapshotReader;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupPreviewPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 MySQL 下验证旧入口对新群模型本地字段的兼容双写。 */
@Testcontainers
class GroupCurrentLocalWriteMySqlTest {

    private static final long TENANT_ID = 7L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_local_write")
            .withUsername("armada")
            .withPassword("armada");

    private static JdbcTemplate jdbc;
    private static GroupLinkMapper groupLinkMapper;
    private static GroupLinkPreviewMapper previewMapper;
    private static GroupCurrentLocalPersistence currentLocalPersistence;
    private static AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;

    @BeforeAll
    static void configureMysqlAndMappers() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createLegacySchema();
        GroupCurrentSnapshotMySqlTestSupport.executeV117(dataSource);
        SqlSessionTemplate session = buildSqlSessionTemplate(dataSource);
        groupLinkMapper = session.getMapper(GroupLinkMapper.class);
        previewMapper = session.getMapper(GroupLinkPreviewMapper.class);
        currentLocalPersistence = new GroupCurrentLocalPersistence(
                session.getMapper(GroupCurrentLocalMapper.class));
        currentSnapshotPersistence = new AccountGroupCurrentSnapshotPersistenceImpl(
                session.getMapper(AccountGroupCurrentSnapshotMapper.class), new ObjectMapper());
    }

    @AfterAll
    static void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM wa_group_invite");
        jdbc.update("DELETE FROM wa_group_profile");
        jdbc.update("DELETE FROM wa_group");
        jdbc.update("DELETE FROM group_link_preview");
        jdbc.update("DELETE FROM group_link");
        TenantContext.set(TENANT_ID);
    }

    @Test
    void updateProfileAlsoWritesResolvedGroupLocalFields() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, remark,
                  origin, membership_state, created_at, updated_at
                ) VALUES (10, 7, 'https://chat.whatsapp.com/LocalFields',
                          '旧名称', '旧备注', 1, 1, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, invite_code,
                  created_at, updated_at
                ) VALUES (7, 10, '120363-local-fields@g.us', 'LocalFields', 100, 100)
                """);
        jdbc.update("""
                INSERT INTO wa_group (
                  tenant_id, group_jid, origin, created_at, updated_at
                ) VALUES (7, '120363-local-fields@g.us', 1, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO wa_group_invite (
                  tenant_id, invite_code, origin, created_at, updated_at
                ) VALUES (7, 'LocalFields', 1, 100, 100)
                """);

        service().updateProfile(10L, new GroupLinkProfileDTO(
                " 新名称 ", " 新备注 ", " https://cdn.example.test/new.jpg "));

        assertThat(jdbc.queryForMap("""
                SELECT display_name, remark, avatar_url
                FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-local-fields@g.us'
                """))
                .containsEntry("display_name", "新名称")
                .containsEntry("remark", "新备注")
                .containsEntry("avatar_url", "https://cdn.example.test/new.jpg");
        assertThat(jdbc.queryForMap("""
                SELECT display_name, remark, avatar_url
                FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'LocalFields'
                """))
                .containsEntry("display_name", null)
                .containsEntry("remark", null)
                .containsEntry("avatar_url", null);
    }

    @Test
    void updateProfileAlsoWritesUnresolvedInviteLocalFields() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, remark,
                  origin, membership_state, created_at, updated_at
                ) VALUES (11, 7, 'https://chat.whatsapp.com/UnresolvedFields',
                          '旧名称', '旧备注', 1, 1, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, invite_code, created_at, updated_at
                ) VALUES (7, 11, 'UnresolvedFields', 100, 100)
                """);
        jdbc.update("""
                INSERT INTO wa_group_invite (
                  tenant_id, invite_code, origin, created_at, updated_at
                ) VALUES (7, 'UnresolvedFields', 1, 100, 100)
                """);

        service().updateProfile(11L, new GroupLinkProfileDTO(
                " 邀请名称 ", " 邀请备注 ", " https://cdn.example.test/invite.jpg "));

        assertThat(jdbc.queryForMap("""
                SELECT display_name, remark, avatar_url
                FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'UnresolvedFields'
                """))
                .containsEntry("display_name", "邀请名称")
                .containsEntry("remark", "邀请备注")
                .containsEntry("avatar_url", "https://cdn.example.test/invite.jpg");
    }

    @Test
    void migrateAlsoWritesInviteLabel() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, label_id, origin, membership_state,
                  created_at, updated_at
                ) VALUES (12, 7, 'https://chat.whatsapp.com/MoveLabel', 1, 1, 1, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, invite_code, created_at, updated_at
                ) VALUES (7, 12, 'MoveLabel', 100, 100)
                """);
        jdbc.update("""
                INSERT INTO wa_group_invite (
                  tenant_id, invite_code, label_id, origin, created_at, updated_at
                ) VALUES (7, 'MoveLabel', 1, 1, 100, 100)
                """);
        GroupLinkLabelMapper labelMapper = mock(GroupLinkLabelMapper.class);
        GroupLinkLabel label = new GroupLinkLabel();
        label.setId(9L);
        org.mockito.Mockito.when(labelMapper.selectById(9L)).thenReturn(label);

        service(mock(GroupFolderMapper.class), labelMapper).migrate(java.util.List.of(12L), 9L);

        assertThat(jdbc.queryForObject("""
                SELECT label_id FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'MoveLabel'
                """, Long.class)).isEqualTo(9L);
    }

    @Test
    void assignFolderAlsoWritesResolvedGroupFolder() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, folder_id, origin, membership_state,
                  created_at, updated_at
                ) VALUES (13, 7, 'wa://group/120363-folder@g.us', NULL, 5, 2, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, created_at, updated_at
                ) VALUES (7, 13, '120363-folder@g.us', 100, 100)
                """);
        jdbc.update("""
                INSERT INTO wa_group (
                  tenant_id, group_jid, origin, created_at, updated_at
                ) VALUES (7, '120363-folder@g.us', 5, 100, 100)
                """);
        GroupFolderMapper folderMapper = mock(GroupFolderMapper.class);
        GroupFolder folder = new GroupFolder();
        folder.setId(8L);
        org.mockito.Mockito.when(folderMapper.selectActiveByIdsForUpdate(java.util.List.of(8L)))
                .thenReturn(java.util.List.of(folder));

        service(folderMapper, mock(GroupLinkLabelMapper.class))
                .assignFolder(java.util.List.of(13L), 8L);

        assertThat(jdbc.queryForObject("""
                SELECT folder_id FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-folder@g.us'
                """, Long.class)).isEqualTo(8L);
    }

    @Test
    void detailSubjectAlsoWritesResolvedGroupDisplayName() {
        seedResolvedGroup(14L, "120363-detail-subject@g.us", "DetailSubject");
        GroupProfilePort profilePort = mock(GroupProfilePort.class);
        GroupExecutionAccountSelector selector = selector(14L);

        detailService(profilePort, selector)
                .updateSubject(14L, new GroupSubjectCommandDTO(" 新详情群名 "));

        assertThat(jdbc.queryForObject("""
                SELECT display_name FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-detail-subject@g.us'
                """, String.class)).isEqualTo("新详情群名");
    }

    @Test
    void detailAvatarAlsoWritesResolvedGroupAvatar() {
        seedResolvedGroup(15L, "120363-detail-avatar@g.us", "DetailAvatar");
        GroupProfilePort profilePort = mock(GroupProfilePort.class);
        GroupExecutionAccountSelector selector = selector(15L);
        org.mockito.Mockito.when(profilePort.updatePicture(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("120363-detail-avatar@g.us"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new GroupPictureResult(
                        true, "https://pps.whatsapp.net/detail-new.jpg"));

        detailService(profilePort, selector).updateAvatar(
                15L,
                new MockMultipartFile(
                        "file", "avatar.jpg", "image/jpeg",
                        "avatar".getBytes(StandardCharsets.UTF_8)));

        assertThat(jdbc.queryForObject("""
                SELECT avatar_url FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-detail-avatar@g.us'
                """, String.class)).isEqualTo("https://pps.whatsapp.net/detail-new.jpg");
    }

    @Test
    void metadataSnapshotAlsoWritesLegacyListNameAndAvatar() {
        seedResolvedGroup(16L, "120363-metadata-local@g.us", "MetadataLocal");
        GroupLinkPreviewMapper snapshotPreviewMapper = mock(GroupLinkPreviewMapper.class);
        com.armada.group.model.entity.GroupLinkPreview preview =
                new com.armada.group.model.entity.GroupLinkPreview();
        preview.setGroupLinkId(16L);
        preview.setGroupJid("120363-metadata-local@g.us");
        preview.setWaSubject("metadata 新名称");
        preview.setAvatarUrl("https://pps.whatsapp.net/metadata-new.jpg");
        preview.setMetadataObservedAt(2_000L);
        preview.setUpdatedAt(2_000L);
        org.mockito.Mockito.when(snapshotPreviewMapper.upsertMetadataSnapshot(preview))
                .thenReturn(1);

        new GroupMetadataSnapshotPersistenceImpl(
                snapshotPreviewMapper,
                mock(WhatsappGroupMemberSnapshotMapper.class),
                groupLinkMapper,
                mock(AccountGroupMembershipMapper.class),
                mock(GroupInviteLinkService.class),
                currentSnapshotPersistence,
                currentLocalPersistence)
                .persist(preview, java.util.List.of());

        assertThat(jdbc.queryForMap("""
                SELECT display_name, avatar_url FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-metadata-local@g.us'
                """))
                .containsEntry("display_name", "metadata 新名称")
                .containsEntry("avatar_url", "https://pps.whatsapp.net/metadata-new.jpg");
    }

    private static GroupLinkServiceImpl service() {
        return service(mock(GroupFolderMapper.class), mock(GroupLinkLabelMapper.class));
    }

    private static GroupLinkServiceImpl service(
            GroupFolderMapper folderMapper,
            GroupLinkLabelMapper labelMapper) {
        return new GroupLinkServiceImpl(
                groupLinkMapper,
                folderMapper,
                previewMapper,
                mock(GroupLinkHealthMapper.class),
                labelMapper,
                mock(GroupConverter.class),
                mock(AccountMapper.class),
                mock(GroupPreviewPort.class),
                mock(GroupProfilePort.class),
                currentLocalPersistence);
    }

    private static GroupDetailServiceImpl detailService(
            GroupProfilePort profilePort,
            GroupExecutionAccountSelector selector) {
        return new GroupDetailServiceImpl(
                groupLinkMapper,
                previewMapper,
                selector,
                new GroupDetailProtocolPorts(
                        mock(FixedAccountGroupMetadataPort.class),
                        profilePort,
                        mock(GroupSettingsPort.class),
                        mock(GroupParticipantPort.class)),
                mock(GroupDetailSnapshotReader.class),
                mock(WhatsappGroupMemberSnapshotMapper.class),
                mock(GroupMetadataSyncTaskService.class),
                currentSnapshotPersistence,
                currentLocalPersistence);
    }

    private static GroupExecutionAccountSelector selector(long groupLinkId) {
        GroupExecutionAccountSelector selector = mock(GroupExecutionAccountSelector.class);
        org.mockito.Mockito.when(selector.require(groupLinkId)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "923300000007", true));
        return selector;
    }

    private static void seedResolvedGroup(long groupLinkId, String groupJid, String inviteCode) {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, origin, membership_state,
                  created_at, updated_at
                ) VALUES (?, 7, ?, '旧名称', 5, 2, 100, 100)
                """, groupLinkId, "wa://group/" + groupJid);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, invite_code,
                  created_at, updated_at
                ) VALUES (7, ?, ?, ?, 100, 100)
                """, groupLinkId, groupJid, inviteCode);
        jdbc.update("""
                INSERT INTO wa_group (
                  tenant_id, group_jid, origin, created_at, updated_at
                ) VALUES (7, ?, 5, 100, 100)
                """, groupJid);
    }

    private static void createLegacySchema() {
        jdbc.execute("""
                CREATE TABLE group_link (
                  id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  link_url VARCHAR(512) NOT NULL,
                  group_name VARCHAR(128) DEFAULT NULL,
                  label_id BIGINT DEFAULT NULL,
                  folder_id BIGINT DEFAULT NULL,
                  origin TINYINT NOT NULL,
                  membership_state TINYINT NOT NULL,
                  remark VARCHAR(255) DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link_preview (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) DEFAULT NULL,
                  invite_code VARCHAR(128) DEFAULT NULL,
                  avatar_url VARCHAR(512) DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_preview_link (tenant_id, group_link_id)
                ) ENGINE=InnoDB
                """);
    }

    private static SqlSessionTemplate buildSqlSessionTemplate(DataSource dataSource) throws Exception {
        MyBatisConfig myBatisConfig = new MyBatisConfig();
        MybatisPlusInterceptor interceptor =
                myBatisConfig.mybatisPlusInterceptor(myBatisConfig.tenantLineHandler());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setUseGeneratedKeys(true);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                new ClassPathResource("mapper/group/GroupLinkPreviewMapper.xml"),
                new ClassPathResource("mapper/group/GroupCurrentLocalMapper.xml"),
                new ClassPathResource("mapper/group/AccountGroupCurrentSnapshotMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建本地字段双写测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }
}
