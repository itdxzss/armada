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
import com.armada.group.mapper.GroupLinkImportBatchMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.GroupModelBackfillMapper;
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
    private static GroupModelBackfillMapper backfillMapper;
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
        backfillMapper = session.getMapper(GroupModelBackfillMapper.class);
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
        jdbc.update("DELETE FROM wa_account_group_binding");
        jdbc.update("DELETE FROM account_group_sync_state");
        jdbc.update("DELETE FROM wa_group_participant");
        jdbc.update("DELETE FROM wa_group_invite");
        jdbc.update("DELETE FROM wa_group_profile");
        jdbc.update("DELETE FROM wa_group");
        jdbc.update("DELETE FROM whatsapp_group_departed_member");
        jdbc.update("DELETE FROM whatsapp_group_member_join_fact");
        jdbc.update("DELETE FROM whatsapp_group_member_state");
        jdbc.update("DELETE FROM whatsapp_group_member_cache");
        jdbc.update("DELETE FROM account_group_membership");
        jdbc.update("DELETE FROM account_group_baseline");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM group_link_health");
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
                  tenant_id, group_jid, origin, created_at, updated_at, deleted_at
                ) VALUES (7, '120363-local-fields@g.us', 1, 100, 100, 150)
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
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-local-fields@g.us'
                """, Long.class)).isNull();
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
                  tenant_id, invite_code, origin, created_at, updated_at, deleted_at
                ) VALUES (7, 'UnresolvedFields', 1, 100, 100, 150)
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
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'UnresolvedFields'
                """, Long.class)).isNull();
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
                  tenant_id, invite_code, label_id, origin, created_at, updated_at, deleted_at
                ) VALUES (7, 'MoveLabel', 1, 1, 100, 100, 150)
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
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'MoveLabel'
                """, Long.class)).isNull();
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
                  tenant_id, group_jid, origin, created_at, updated_at, deleted_at
                ) VALUES (7, '120363-folder@g.us', 5, 100, 100, 150)
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
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-folder@g.us'
                """, Long.class)).isNull();
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

    @Test
    void batchDeleteSoftDeletesGroupOnlyAfterLastActiveAlias() {
        seedGroupAlias(17L, "https://chat.whatsapp.com/GroupAliasOne", "120363-alias@g.us");
        seedGroupAlias(18L, "https://chat.whatsapp.com/GroupAliasTwo", "120363-alias@g.us");
        jdbc.update("""
                INSERT INTO wa_group (
                  tenant_id, group_jid, origin, created_at, updated_at
                ) VALUES (7, '120363-alias@g.us', 1, 100, 100)
                """);

        service().batchDelete(java.util.List.of(17L));
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-alias@g.us'
                """, Long.class)).isNull();

        service().batchDelete(java.util.List.of(18L));
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-alias@g.us'
                """, Long.class)).isNotNull();
    }

    @Test
    void batchDeleteDoesNotRetireInvitePoolRow() {
        seedInviteAlias(19L, "https://chat.whatsapp.com/InviteAliasOne", "SharedInvite");
        seedInviteAlias(20L, "https://chat.whatsapp.com/InviteAliasTwo", "SharedInvite");
        jdbc.update("""
                INSERT INTO wa_group_invite (
                  tenant_id, invite_code, origin, created_at, updated_at
                ) VALUES (7, 'SharedInvite', 1, 100, 100)
                """);

        service().batchDelete(java.util.List.of(19L));
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'SharedInvite'
                """, Long.class)).isNull();

        service().batchDelete(java.util.List.of(20L));
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'SharedInvite'
                """, Long.class)).isNull();
    }

    @Test
    void labelDeleteDoesNotRetireInvitePoolRow() {
        seedInviteAlias(21L, "https://chat.whatsapp.com/LabelAliasOne", "LabelShared");
        seedInviteAlias(22L, "https://chat.whatsapp.com/LabelAliasTwo", "LabelShared");
        jdbc.update("UPDATE group_link SET label_id = 30 WHERE id = 21");
        jdbc.update("UPDATE group_link SET label_id = 31 WHERE id = 22");
        jdbc.update("""
                INSERT INTO wa_group_invite (
                  tenant_id, invite_code, origin, created_at, updated_at
                ) VALUES (7, 'LabelShared', 1, 100, 100)
                """);

        labelService().batchDelete(java.util.List.of(30L));
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'LabelShared'
                """, Long.class)).isNull();

        labelService().batchDelete(java.util.List.of(31L));
        assertThat(jdbc.queryForObject("""
                SELECT deleted_at FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'LabelShared'
                """, Long.class)).isNull();
    }

    @Test
    void folderDeleteAlsoClearsResolvedGroupFolder() {
        seedResolvedGroup(23L, "120363-folder-delete@g.us", "FolderDelete");
        jdbc.update("UPDATE group_link SET folder_id = 40 WHERE id = 23");
        jdbc.update("""
                UPDATE wa_group SET folder_id = 40
                WHERE tenant_id = 7 AND group_jid = '120363-folder-delete@g.us'
                """);
        GroupFolderMapper folderMapper = mock(GroupFolderMapper.class);
        GroupFolder folder = new GroupFolder();
        folder.setId(40L);
        org.mockito.Mockito.when(folderMapper.selectActiveByIdsForUpdate(java.util.List.of(40L)))
                .thenReturn(java.util.List.of(folder));
        org.mockito.Mockito.when(folderMapper.softDeleteByIds(
                org.mockito.ArgumentMatchers.eq(java.util.List.of(40L)),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        new GroupFolderServiceImpl(folderMapper, groupLinkMapper, currentLocalPersistence)
                .batchDelete(java.util.List.of(40L));

        assertThat(jdbc.queryForObject("""
                SELECT folder_id FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-folder-delete@g.us'
                """, Long.class)).isNull();
    }

    @Test
    void groupBackfillIsTenantScopedAndIdempotent() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, folder_id, origin,
                  membership_state, remark, created_at, updated_at
                ) VALUES
                  (50, 7, 'wa://group/120363-backfill@g.us', '本地群名', 9, 3,
                   2, '回填备注', 100, 200),
                  (51, 8, 'wa://group/120363-other-tenant@g.us', '其他租户', NULL, 5,
                   2, NULL, 120, 220)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, avatar_url, created_at, updated_at
                ) VALUES
                  (7, 50, '120363-BACKFILL@g.us', 'https://cdn.example/backfill.jpg', 110, 300),
                  (8, 51, '120363-other-tenant@g.us', NULL, 130, 230)
                """);

        assertThat(backfillMapper.countInvalidGroupSources()).isZero();
        assertThat(backfillMapper.countDuplicateGroupJids()).isZero();
        assertThat(backfillMapper.backfillGroups(500)).isEqualTo(2);
        assertThat(backfillMapper.backfillGroups(500)).isZero();

        assertThat(jdbc.queryForMap("""
                SELECT folder_id, display_name, avatar_url, remark, origin,
                       created_at, updated_at, deleted_at
                FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-backfill@g.us'
                """))
                .containsEntry("folder_id", 9L)
                .containsEntry("display_name", "本地群名")
                .containsEntry("avatar_url", "https://cdn.example/backfill.jpg")
                .containsEntry("remark", "回填备注")
                .containsEntry("origin", 3)
                .containsEntry("created_at", 100L)
                .containsEntry("updated_at", 300L)
                .containsEntry("deleted_at", null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_group WHERE tenant_id = 8",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void groupBackfillReportsDuplicateTenantGroupJid() {
        seedGroupAlias(52L, "wa://group/duplicate-one@g.us", "120363-duplicate@g.us");
        seedGroupAlias(53L, "wa://group/duplicate-two@g.us", "120363-DUPLICATE@g.us");

        assertThat(backfillMapper.countDuplicateGroupJids()).isEqualTo(1);
    }

    @Test
    void groupBackfillDoesNotOverwriteNewerDualWrite() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, folder_id, origin,
                  membership_state, remark, created_at, updated_at
                ) VALUES (54, 7, 'wa://group/120363-newer@g.us', '旧表名称', 9, 3,
                          2, '旧表备注', 100, 200)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, avatar_url, created_at, updated_at
                ) VALUES (7, 54, '120363-newer@g.us', 'https://cdn.example/legacy.jpg',
                          110, 300)
                """);
        jdbc.update("""
                INSERT INTO wa_group (
                  tenant_id, group_jid, folder_id, display_name, avatar_url, remark,
                  origin, created_at, updated_at
                ) VALUES (7, '120363-newer@g.us', 19, '实时名称',
                          'https://cdn.example/current.jpg', '实时备注', 4, 90, 400)
                """);

        assertThat(backfillMapper.backfillGroups(500)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT folder_id, display_name, avatar_url, remark, origin,
                       created_at, updated_at
                FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-newer@g.us'
                """))
                .containsEntry("folder_id", 19L)
                .containsEntry("display_name", "实时名称")
                .containsEntry("avatar_url", "https://cdn.example/current.jpg")
                .containsEntry("remark", "实时备注")
                .containsEntry("origin", 4)
                .containsEntry("created_at", 90L)
                .containsEntry("updated_at", 400L);
    }

    @Test
    void profileAndInviteBackfillAreIdempotentAndKeepFieldOwnership() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, label_id, origin,
                  membership_state, remark, created_at, updated_at
                ) VALUES
                  (55, 7, 'https://chat.whatsapp.com/ResolvedCode', '已解析本地名', 6, 1,
                   2, '群备注', 100, 200),
                  (56, 7, 'https://chat.whatsapp.com/UnresolvedCode', '未解析本地名', 8, 1,
                   1, '邀请备注', 120, 220)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, invite_code,
                  invite_code_observed_at, wa_subject, wa_description, member_size,
                  announce_only, admin_only_edit_info, member_add_mode, join_approval_mode,
                  ephemeral_duration_seconds, group_created_at, avatar_url,
                  last_preview_at, metadata_observed_at, created_at, updated_at
                ) VALUES
                  (7, 55, '120363-PROFILE@g.us', 'ResolvedCode', 350,
                   'WhatsApp群名', '群描述', 41, 1, 1, 1, 0, 86400, 123,
                   'https://cdn.example/resolved.jpg', 300, 320, 110, 330),
                  (7, 56, NULL, 'UnresolvedCode', 360,
                   '公开预览名', NULL, 33, NULL, NULL, NULL, NULL, NULL, NULL,
                   'https://cdn.example/unresolved.jpg', 310, NULL, 130, 340)
                """);
        jdbc.update("""
                INSERT INTO group_link_health (
                  tenant_id, group_link_id, health_status, is_banned, current_count,
                  last_check_at, last_health_error, health_failure_count, created_at, updated_at
                ) VALUES
                  (7, 55, 1, 0, 42, 340, NULL, 0, 120, 340),
                  (7, 56, 2, 0, 34, 345, 'LINK_INVALID', 2, 140, 345)
                """);

        assertThat(backfillMapper.countInviteConflicts()).isZero();
        assertThat(backfillMapper.backfillGroups(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillGroups(500)).isZero();
        assertThat(backfillMapper.backfillProfiles(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillProfiles(500)).isZero();
        assertThat(backfillMapper.backfillInvites(500)).isEqualTo(2);
        assertThat(backfillMapper.backfillInvites(500)).isZero();
        assertThat(backfillMapper.backfillCurrentInvitePointers(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillCurrentInvitePointers(500)).isZero();

        Long groupId = jdbc.queryForObject("""
                SELECT id FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-profile@g.us'
                """, Long.class);
        Long resolvedInviteId = jdbc.queryForObject("""
                SELECT id FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'ResolvedCode'
                """, Long.class);
        assertThat(jdbc.queryForMap("""
                SELECT subject, description, member_count, wa_created_at,
                       announce_only, admin_only_edit_info, member_add_mode,
                       join_approval_mode, ephemeral_duration_seconds,
                       metadata_observed_at, current_invite_id, current_invite_observed_at
                FROM wa_group_profile
                WHERE tenant_id = 7 AND group_id = ?
                """, groupId))
                .containsEntry("subject", "WhatsApp群名")
                .containsEntry("description", "群描述")
                .containsEntry("member_count", 42)
                .containsEntry("wa_created_at", 123000L)
                .containsEntry("announce_only", 1)
                .containsEntry("admin_only_edit_info", 1)
                .containsEntry("member_add_mode", 1)
                .containsEntry("join_approval_mode", 0)
                .containsEntry("ephemeral_duration_seconds", 86400)
                .containsEntry("metadata_observed_at", 320L)
                .containsEntry("current_invite_id", resolvedInviteId)
                .containsEntry("current_invite_observed_at", 350L);
        assertThat(jdbc.queryForMap("""
                SELECT group_id, label_id, display_name, avatar_url, remark,
                       preview_subject, health_status, banned, checked_member_count,
                       last_checked_at, last_error_code, failure_count
                FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'ResolvedCode'
                """))
                .containsEntry("group_id", groupId)
                .containsEntry("label_id", 6L)
                .containsEntry("display_name", null)
                .containsEntry("avatar_url", null)
                .containsEntry("remark", null)
                .containsEntry("preview_subject", null)
                .containsEntry("health_status", 1)
                .containsEntry("banned", 0)
                .containsEntry("checked_member_count", 42)
                .containsEntry("last_checked_at", 340L)
                .containsEntry("last_error_code", null)
                .containsEntry("failure_count", 0);
        assertThat(jdbc.queryForMap("""
                SELECT group_id, label_id, display_name, avatar_url, remark,
                       preview_subject, preview_observed_at, health_status,
                       checked_member_count, last_error_code, failure_count
                FROM wa_group_invite
                WHERE tenant_id = 7 AND invite_code = 'UnresolvedCode'
                """))
                .containsEntry("group_id", null)
                .containsEntry("label_id", 8L)
                .containsEntry("display_name", "未解析本地名")
                .containsEntry("avatar_url", "https://cdn.example/unresolved.jpg")
                .containsEntry("remark", "邀请备注")
                .containsEntry("preview_subject", "公开预览名")
                .containsEntry("preview_observed_at", 310L)
                .containsEntry("health_status", 2)
                .containsEntry("checked_member_count", 34)
                .containsEntry("last_error_code", "LINK_INVALID")
                .containsEntry("failure_count", 2);
    }

    @Test
    void inviteBackfillReportsDuplicateCodeWithinTenant() {
        seedInviteAlias(57L, "https://chat.whatsapp.com/DuplicateOne", "SameCode");
        seedInviteAlias(58L, "https://chat.whatsapp.com/DuplicateTwo", "SameCode");

        assertThat(backfillMapper.countInviteConflicts()).isEqualTo(1);
    }

    @Test
    void participantBindingAndSyncBackfillPreserveLegacyBaselineMeaning() {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, origin, membership_state,
                  created_at, updated_at
                ) VALUES
                  (60, 7, 'wa://group/120363-history@g.us', '历史群', 5, 2, 100, 100),
                  (61, 7, 'wa://group/120363-current@g.us', '当前群', 5, 2, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, wa_subject,
                  created_at, updated_at
                ) VALUES
                  (7, 60, '120363-history@g.us', '历史群', 100, 100),
                  (7, 61, '120363-current@g.us', '当前群', 100, 100)
                """);
        jdbc.update("""
                UPDATE group_link_preview
                SET owner_phone = '923300000099',
                    creator_country_iso2 = 'PK',
                    metadata_observed_at = 2500
                WHERE tenant_id = 7 AND group_link_id = 61
                """);
        jdbc.update("""
                INSERT INTO account (
                  id, tenant_id, ws_phone, group_baseline_state,
                  created_at, updated_at
                ) VALUES (100, 7, '923300000001', 2, 50, 3000)
                """);
        jdbc.update("""
                INSERT INTO account_group_baseline (
                  tenant_id, account_id, baseline_group_jids,
                  baseline_group_subjects, group_count, captured_at,
                  last_group_sync_requested_at, created_at, updated_at
                ) VALUES (
                  7, 100, JSON_ARRAY('120363-history@g.us'),
                  JSON_OBJECT('120363-history@g.us', '历史群快照'),
                  1, 1000, 900, 1000, 1000
                )
                """);
        jdbc.update("""
                INSERT INTO account_group_membership (
                  id, tenant_id, account_id, group_link_id, group_jid,
                  is_admin, membership_status, status_source, status_updated_at,
                  joined_at, last_seen_at, created_at, updated_at
                ) VALUES (
                  200, 7, 100, 61, '120363-current@g.us',
                  1, 1, 'ACCOUNT_SNAPSHOT', 3000,
                  2000, 3000, 2000, 3000
                )
                """);
        jdbc.update("""
                INSERT INTO whatsapp_group_member_cache (
                  tenant_id, group_jid, subject, announce_only,
                  snapshot_at, snapshot_version, observer_account_id,
                  created_at, updated_at
                ) VALUES (
                  7, '120363-current@g.us', '当前群', 0,
                  2500, 'snapshot-v1', 100, 2500, 2500
                )
                """);
        jdbc.update("""
                INSERT INTO whatsapp_group_member_state (
                  tenant_id, group_jid, participant_jid, phone,
                  is_admin, is_owner, role, is_in_group, state_source,
                  state_updated_at, source_event_id, snapshot_version,
                  observer_account_id, created_at, updated_at
                ) VALUES (
                  7, '120363-current@g.us', '923300000099@s.whatsapp.net',
                  '923300000099', 1, 1, 'superadmin', 1, 'FULL_SNAPSHOT',
                  2500, 'snapshot-v1:owner', 'snapshot-v1', 100, 2500, 2500
                )
                """);
        jdbc.update("""
                INSERT INTO whatsapp_group_member_join_fact (
                  tenant_id, group_jid, participant_jid, phone,
                  joined_at, event_at, source_event_id, observer_account_id,
                  created_at, updated_at
                ) VALUES (
                  7, '120363-current@g.us', '923300000098@s.whatsapp.net',
                  '923300000098', 1800, 1800, 'join-98', 100, 1800, 1800
                )
                """);
        jdbc.update("""
                INSERT INTO whatsapp_group_departed_member (
                  tenant_id, group_jid, participant_jid, phone,
                  exited_at, exit_type, event_at, source_event_id, source_type,
                  created_at, updated_at
                ) VALUES (
                  7, '120363-current@g.us', '923300000097@s.whatsapp.net',
                  '923300000097', 1700, 'LEFT', 1700, 'exit-97',
                  'WGP2_NOTIFICATION', 1700, 1700
                )
                """);

        assertThat(backfillMapper.backfillGroups(500)).isEqualTo(2);
        assertThat(backfillMapper.countParticipantConflicts()).isZero();
        assertThat(backfillMapper.countBindingConflicts()).isZero();
        assertThat(backfillMapper.backfillProfiles(500)).isEqualTo(2);
        assertThat(backfillMapper.backfillMemberSnapshotHeaders(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillProfileOwners(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillParticipants(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillAccountParticipants(500)).isEqualTo(2);
        assertThat(backfillMapper.backfillParticipantJoinFacts(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillParticipantExitFacts(500)).isEqualTo(1);
        assertThat(backfillMapper.backfillAccountGroupBindings(500)).isEqualTo(2);
        assertThat(backfillMapper.backfillAccountGroupSyncStates(500)).isEqualTo(1);

        assertThat(backfillMapper.backfillMemberSnapshotHeaders(500)).isZero();
        assertThat(backfillMapper.backfillProfileOwners(500)).isZero();
        assertThat(backfillMapper.backfillParticipants(500)).isZero();
        assertThat(backfillMapper.backfillAccountParticipants(500)).isZero();
        assertThat(backfillMapper.backfillParticipantJoinFacts(500)).isZero();
        assertThat(backfillMapper.backfillParticipantExitFacts(500)).isZero();
        assertThat(backfillMapper.backfillAccountGroupBindings(500)).isZero();
        assertThat(backfillMapper.backfillAccountGroupSyncStates(500)).isZero();

        Long historyGroupId = jdbc.queryForObject("""
                SELECT id FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-history@g.us'
                """, Long.class);
        Long currentGroupId = jdbc.queryForObject("""
                SELECT id FROM wa_group
                WHERE tenant_id = 7 AND group_jid = '120363-current@g.us'
                """, Long.class);
        assertThat(jdbc.queryForMap("""
                SELECT was_in_initial_baseline, baseline_subject_snapshot,
                       membership_active_since_at, first_post_control_observed_at
                FROM wa_account_group_binding
                WHERE tenant_id = 7 AND account_id = 100 AND group_id = ?
                """, historyGroupId))
                .containsEntry("was_in_initial_baseline", 1)
                .containsEntry("baseline_subject_snapshot", "历史群快照")
                .containsEntry("membership_active_since_at", null)
                .containsEntry("first_post_control_observed_at", null);
        assertThat(jdbc.queryForMap("""
                SELECT was_in_initial_baseline, membership_active_since_at,
                       first_post_control_observed_at
                FROM wa_account_group_binding
                WHERE tenant_id = 7 AND account_id = 100 AND group_id = ?
                """, currentGroupId))
                .containsEntry("was_in_initial_baseline", null)
                .containsEntry("membership_active_since_at", 2000L)
                .containsEntry("first_post_control_observed_at", null);
        assertThat(jdbc.queryForMap("""
                SELECT member_count, member_snapshot_at, member_snapshot_version
                FROM wa_group_profile
                WHERE tenant_id = 7 AND group_id = ?
                """, currentGroupId))
                .containsEntry("member_count", 1)
                .containsEntry("member_snapshot_at", 2500L)
                .containsEntry("member_snapshot_version", "snapshot-v1");
        assertThat(jdbc.queryForMap("""
                SELECT presence_status, role, last_snapshot_version, phone_country_iso2
                FROM wa_group_participant
                WHERE tenant_id = 7 AND group_id = ?
                  AND pn_jid = '923300000099@s.whatsapp.net'
                """, currentGroupId))
                .containsEntry("presence_status", 1)
                .containsEntry("role", 3)
                .containsEntry("last_snapshot_version", "snapshot-v1")
                .containsEntry("phone_country_iso2", "PK");
        assertThat(jdbc.queryForMap("""
                SELECT baseline_state, baseline_completeness,
                       baseline_captured_at, baseline_group_count,
                       last_sync_requested_at, last_reported_at
                FROM account_group_sync_state
                WHERE tenant_id = 7 AND account_id = 100
                """))
                .containsEntry("baseline_state", 2)
                .containsEntry("baseline_completeness", 2)
                .containsEntry("baseline_captured_at", 1000L)
                .containsEntry("baseline_group_count", 1)
                .containsEntry("last_sync_requested_at", 900L)
                .containsEntry("last_reported_at", null);
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

    private static GroupLinkLabelServiceImpl labelService() {
        GroupLinkLabelMapper labelMapper = mock(GroupLinkLabelMapper.class);
        org.mockito.Mockito.when(labelMapper.softDeleteByIds(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        return new GroupLinkLabelServiceImpl(
                labelMapper,
                groupLinkMapper,
                mock(GroupLinkImportBatchMapper.class),
                mock(GroupConverter.class),
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

    private static void seedGroupAlias(long groupLinkId, String linkUrl, String groupJid) {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, origin, membership_state, created_at, updated_at
                ) VALUES (?, 7, ?, 1, 1, 100, 100)
                """, groupLinkId, linkUrl);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, created_at, updated_at
                ) VALUES (7, ?, ?, 100, 100)
                """, groupLinkId, groupJid);
    }

    private static void seedInviteAlias(long groupLinkId, String linkUrl, String inviteCode) {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, origin, membership_state, created_at, updated_at
                ) VALUES (?, 7, ?, 1, 1, 100, 100)
                """, groupLinkId, linkUrl);
        jdbc.update("""
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, invite_code, created_at, updated_at
                ) VALUES (7, ?, ?, 100, 100)
                """, groupLinkId, inviteCode);
    }

    private static void createLegacySchema() {
        GroupCurrentSnapshotMySqlTestSupport.createLegacyContextSchema(jdbc);
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
                CREATE TABLE account_group_membership (
                  id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  is_admin TINYINT DEFAULT NULL,
                  membership_status TINYINT NOT NULL,
                  status_source VARCHAR(64) DEFAULT NULL,
                  status_updated_at BIGINT NOT NULL,
                  last_exit_type TINYINT DEFAULT NULL,
                  last_exited_at BIGINT DEFAULT NULL,
                  joined_at BIGINT DEFAULT NULL,
                  last_seen_at BIGINT DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_membership_active
                    (tenant_id, account_id, group_jid, deleted_at)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE whatsapp_group_member_cache (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  subject VARCHAR(255) DEFAULT NULL,
                  announce_only TINYINT DEFAULT NULL,
                  snapshot_at BIGINT NOT NULL,
                  snapshot_version VARCHAR(64) NOT NULL,
                  observer_account_id BIGINT NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_member_cache (tenant_id, group_jid)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE whatsapp_group_member_state (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  participant_jid VARCHAR(191) NOT NULL,
                  phone VARCHAR(32) DEFAULT NULL,
                  is_admin TINYINT DEFAULT NULL,
                  is_owner TINYINT DEFAULT NULL,
                  role VARCHAR(32) DEFAULT NULL,
                  is_in_group TINYINT NOT NULL,
                  state_source VARCHAR(32) NOT NULL,
                  state_updated_at BIGINT NOT NULL,
                  source_event_id VARCHAR(255) NOT NULL,
                  snapshot_version VARCHAR(64) DEFAULT NULL,
                  observer_account_id BIGINT DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_member_state
                    (tenant_id, group_jid, participant_jid)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE whatsapp_group_member_join_fact (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  participant_jid VARCHAR(191) NOT NULL,
                  phone VARCHAR(32) DEFAULT NULL,
                  joined_at BIGINT NOT NULL,
                  event_at BIGINT NOT NULL,
                  source_event_id VARCHAR(255) NOT NULL,
                  observer_account_id BIGINT NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_member_join
                    (tenant_id, group_jid, participant_jid)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE whatsapp_group_departed_member (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  participant_jid VARCHAR(191) NOT NULL,
                  phone VARCHAR(32) DEFAULT NULL,
                  exited_at BIGINT NOT NULL,
                  exit_type VARCHAR(16) NOT NULL,
                  event_at BIGINT NOT NULL,
                  source_event_id VARCHAR(255) NOT NULL,
                  source_type VARCHAR(32) NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_departed_member
                    (tenant_id, group_jid, participant_jid)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link_preview (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) DEFAULT NULL,
                  invite_code VARCHAR(128) DEFAULT NULL,
                  invite_code_observed_at BIGINT DEFAULT NULL,
                  wa_subject VARCHAR(255) DEFAULT NULL,
                  wa_description VARCHAR(1024) DEFAULT NULL,
                  member_size INT DEFAULT NULL,
                  announce_only TINYINT DEFAULT NULL,
                  admin_only_edit_info TINYINT DEFAULT NULL,
                  member_add_mode TINYINT DEFAULT NULL,
                  join_approval_mode TINYINT DEFAULT NULL,
                  ephemeral_duration_seconds INT DEFAULT NULL,
                  group_created_at BIGINT DEFAULT NULL,
                  avatar_url VARCHAR(512) DEFAULT NULL,
                  owner_phone VARCHAR(32) DEFAULT NULL,
                  creator_country_iso2 CHAR(2) DEFAULT NULL,
                  creator_continent_code VARCHAR(24) DEFAULT NULL,
                  last_preview_at BIGINT DEFAULT NULL,
                  metadata_observed_at BIGINT DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_preview_link (tenant_id, group_link_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link_health (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  health_status TINYINT DEFAULT NULL,
                  is_banned TINYINT DEFAULT NULL,
                  current_count INT DEFAULT NULL,
                  last_check_at BIGINT DEFAULT NULL,
                  last_health_error VARCHAR(64) DEFAULT NULL,
                  health_failure_count INT NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_health_link (tenant_id, group_link_id)
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
                new ClassPathResource("mapper/group/AccountGroupCurrentSnapshotMapper.xml"),
                new ClassPathResource("mapper/group/GroupModelBackfillMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建本地字段双写测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }
}
