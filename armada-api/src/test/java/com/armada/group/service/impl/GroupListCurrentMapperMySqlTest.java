package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupListCurrentMapper;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.enums.GroupListType;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupLinkHealthCheckCandidate;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mapstruct.factory.Mappers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 MySQL 8 下对比旧群列表与六表影子列表的现有返回口径。 */
@Testcontainers
class GroupListCurrentMapperMySqlTest {

    private static final long TENANT_ID = 7L;
    private static final GroupConverter CONVERTER = Mappers.getMapper(GroupConverter.class);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_list_current")
            .withUsername("armada")
            .withPassword("armada");

    private static GroupLinkMapper legacyMapper;
    private static GroupListCurrentMapper currentMapper;
    private static GroupMetadataSyncTaskMapper metadataTaskMapper;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void configureDatabaseAndMappers() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createLegacySchema(jdbc);
        GroupCurrentSnapshotMySqlTestSupport.executeV120(dataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV121(dataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV122(dataSource);
        insertFixtures(jdbc);
        GroupCurrentSnapshotMySqlTestSupport.executeV123(dataSource);

        SqlSessionTemplate session = buildSqlSessionTemplate(dataSource);
        legacyMapper = session.getMapper(GroupLinkMapper.class);
        currentMapper = session.getMapper(GroupListCurrentMapper.class);
        metadataTaskMapper = session.getMapper(GroupMetadataSyncTaskMapper.class);
    }

    @AfterAll
    static void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void setTenant() {
        TenantContext.set(TENANT_ID);
    }

    @Test
    void resolvedAndUnresolvedRowsMatchLegacyFiltersOrderAndPagination() {
        GroupLinkQuery all = pageQuery(1, 10);
        assertSameCountAndRows(all);
        assertThat(currentMapper.selectPage(TENANT_ID, all))
                .extracting(row -> row.getId())
                .containsExactly(202L, 201L);

        assertSameCountAndRows(pageQuery(1, 1));
        assertSameCountAndRows(pageQuery(2, 1));

        GroupLinkQuery filtered = pageQuery(1, 10);
        filtered.setLabelId(11L);
        filtered.setFolderId(12L);
        filtered.setGroupType(GroupListType.BOTH);
        filtered.setAvailableAdmin(true);
        filtered.setMemberCountMin(6);
        filtered.setMemberCountMax(6);
        filtered.setCountryIso2("PK");
        filtered.setContinentCode("ASIA");
        filtered.setAgeDaysMin(1);
        filtered.setAgeDaysMax(1);
        filtered.setSourceFileName("groups.xlsx");
        filtered.setOrigin(5);
        filtered.setMembershipState(2);
        filtered.setStatus("AVAILABLE");
        filtered.setKeyword("1002");
        filtered.setNowSeconds(200_000L);

        assertSameCountAndRows(filtered);
        assertThat(currentMapper.count(TENANT_ID, filtered)).isEqualTo(1L);
    }

    @Test
    void healthCheckCandidateUsesCurrentProfileInsteadOfStaleLegacyBan() {
        jdbc.update("UPDATE group_link_health SET is_banned = 1 WHERE group_link_id = 201");
        try {
            assertThat(legacyMapper.selectHealthCheckCandidates(10, 1))
                    .extracting(GroupLinkHealthCheckCandidate::groupLinkId)
                    .contains(201L);
        } finally {
            jdbc.update("UPDATE group_link_health SET is_banned = 0 WHERE group_link_id = 201");
        }
    }

    @Test
    void deferredTaskResumeUsesCurrentSelfPresence() {
        jdbc.update("UPDATE group_metadata_sync_task SET status = 5 WHERE group_link_id = 201");
        try {
            assertThat(metadataTaskMapper.resumeDeferredForAccount(
                    301L,
                    GroupMetadataSyncStatus.DEFERRED.code(),
                    GroupMetadataSyncStatus.PENDING.code(),
                    GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
                    500L)).isEqualTo(1);

            jdbc.update("UPDATE group_metadata_sync_task SET status = 5 WHERE group_link_id = 201");
            jdbc.update("UPDATE wa_group_participant SET presence_status = 2 WHERE id = 801");
            assertThat(metadataTaskMapper.resumeDeferredForAccount(
                    301L,
                    GroupMetadataSyncStatus.DEFERRED.code(),
                    GroupMetadataSyncStatus.PENDING.code(),
                    GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
                    600L)).isZero();
        } finally {
            jdbc.update("UPDATE wa_group_participant SET presence_status = 1 WHERE id = 801");
            jdbc.update("UPDATE group_metadata_sync_task SET status = 2 WHERE group_link_id = 201");
        }
    }

    private static void assertSameCountAndRows(GroupLinkQuery query) {
        assertThat(currentMapper.count(TENANT_ID, query))
                .isEqualTo(legacyMapper.countByLabel(query));
        assertThat(currentMapper.selectPage(TENANT_ID, query))
                .extracting(CONVERTER::toGroupLinkVO)
                .containsExactlyElementsOf(
                        legacyMapper.selectPageByLabel(query).stream()
                                .map(CONVERTER::toGroupLinkVO)
                                .toList());
    }

    private static GroupLinkQuery pageQuery(int page, int pageSize) {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        return query;
    }

    private static void insertFixtures(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO group_link
                  (id, tenant_id, link_url, group_name, label_id, folder_id,
                   import_batch_id, origin, membership_state, is_historical,
                   is_post_control, sync_protocol_mask, remark, created_at, updated_at)
                VALUES
                  (201, 7, 'wa://group/resolved@g.us', '本地群名', 11, 12,
                   13, 5, 2, 1, 1, 3, '运营备注', 200, 200),
                  (202, 7, 'https://chat.whatsapp.com/unresolved-code', '未解析本地名',
                   11, NULL, 13, 1, 1, 0, 0, 0, '邀请备注', 300, 300),
                  (203, 8, 'wa://group/other-tenant@g.us', NULL, NULL, NULL,
                   NULL, 5, 2, 0, 0, 1, NULL, 400, 400)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview
                  (tenant_id, group_link_id, group_jid, invite_code, wa_subject,
                   member_size, owner_phone, avatar_url, last_preview_at,
                   creator_country_iso2, creator_continent_code, group_created_at)
                VALUES
                  (7, 201, 'resolved@g.us', 'resolved-code', 'WA群名', 5,
                   '1002', 'https://cdn.example/resolved.jpg', 120,
                   'PK', 'ASIA', 113600),
                  (7, 202, NULL, 'unresolved-code', '未解析预览群', NULL,
                   NULL, 'https://cdn.example/unresolved.jpg', 220,
                   NULL, NULL, NULL)
                """);
        jdbc.update("""
                INSERT INTO group_link_import_batch (id, tenant_id, source_file_name)
                VALUES (13, 7, 'groups.xlsx')
                """);
        jdbc.update("""
                INSERT INTO group_folder (id, tenant_id, name, deleted_at)
                VALUES (12, 7, '运营分组', NULL)
                """);
        jdbc.update("""
                INSERT INTO country
                  (id, iso2, name_zh, flag, continent_code, deleted_at)
                VALUES (21, 'PK', '巴基斯坦', '🇵🇰', 'ASIA', NULL)
                """);
        jdbc.update("""
                INSERT INTO group_link_health
                  (tenant_id, group_link_id, health_status, is_banned, current_count,
                   last_check_at, last_health_error)
                VALUES
                  (7, 201, 1, 0, 6, 130, NULL),
                  (7, 202, 2, 0, 34, 230, 'LINK_INVALID')
                """);
        jdbc.update("""
                INSERT INTO group_metadata_sync_task
                  (tenant_id, group_link_id, status, last_success_at, last_error_message)
                VALUES (7, 201, 2, 140, NULL)
                """);
        jdbc.update("""
                INSERT INTO account
                  (id, tenant_id, ws_phone, protocol_account_id, deleted_at)
                VALUES
                  (301, 7, '1001', 'acc-301', NULL),
                  (302, 7, '1002', NULL, NULL),
                  (401, 8, '1003', NULL, NULL)
                """);
        jdbc.update("""
                INSERT INTO account_state
                  (tenant_id, account_id, login_state, account_state)
                VALUES (7, 301, 1, 2)
                """);
        jdbc.update("""
                INSERT INTO account_group_membership
                  (tenant_id, group_link_id, account_id, membership_status, is_admin, deleted_at)
                VALUES (7, 201, 301, 1, 1, NULL)
                """);
        jdbc.update("""
                INSERT INTO join_task_result
                  (id, tenant_id, group_jid, status, account_id, is_admin)
                VALUES (1001, 7, 'resolved@g.us', 'SUCCESS', 301, 1)
                """);
        jdbc.update("""
                INSERT INTO whatsapp_group_member_snapshot
                  (tenant_id, group_link_id, group_jid, participant_jid, phone,
                   is_admin, snapshot_at, created_at, updated_at)
                VALUES
                  (7, 201, 'resolved@g.us', '1001@s.whatsapp.net', '1001', 1, 100, 100, 100),
                  (7, 201, 'resolved@g.us', '1002@s.whatsapp.net', '1002', 1, 100, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO wa_group
                  (id, tenant_id, group_jid, folder_id, display_name, avatar_url,
                   remark, origin, created_at, updated_at)
                VALUES (501, 7, 'resolved@g.us', 12, '本地群名',
                        'https://cdn.example/resolved.jpg', '运营备注', 5, 200, 200)
                """);
        jdbc.update("""
                INSERT INTO wa_group_invite
                  (id, tenant_id, group_id, invite_code, label_id, display_name,
                   avatar_url, remark, origin, preview_subject, preview_observed_at,
                   health_status, banned, checked_member_count, last_checked_at,
                   last_error_code, created_at, updated_at)
                VALUES
                  (701, 7, 501, 'resolved-code', 11, NULL, NULL, NULL, 5,
                   NULL, NULL, 1, 0, 6, 130, NULL, 200, 200),
                  (702, 7, NULL, 'unresolved-code', 11, '未解析本地名',
                   'https://cdn.example/unresolved.jpg', '邀请备注', 1,
                   '未解析预览群', 220, 2, 0, 34, 230, 'LINK_INVALID', 300, 300)
                """);
        jdbc.update("""
                INSERT INTO wa_group_profile
                  (id, tenant_id, group_id, subject, member_count, checked_member_count,
                   wa_created_at,
                   health_status, banned, last_checked_at, last_error_code, failure_count,
                   metadata_observed_at, current_invite_id,
                   current_invite_observed_at, created_at, updated_at)
                VALUES (601, 7, 501, 'WA群名', 5, 6, 113600000,
                        1, 0, 130, NULL, 0, 120, 701, 120, 200, 200)
                """);
        jdbc.update("""
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, pn_jid, phone, phone_country_iso2,
                   presence_status, role, role_observed_at, created_at, updated_at)
                VALUES
                  (801, 7, 501, '1001@s.whatsapp.net', '1001', NULL,
                   1, 2, 100, 100, 100),
                  (802, 7, 501, '1002@s.whatsapp.net', '1002', 'PK',
                   1, 2, 100, 100, 100),
                  (803, 7, 501, '9199@s.whatsapp.net', '9199', 'IN',
                   1, 3, 200, 200, 200)
                """);
        jdbc.update("""
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, participant_id,
                   created_at, updated_at)
                VALUES (901, 7, 301, 501, 801, 100, 100)
                """);
    }

    private static void createLegacySchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE group_link (
                  id BIGINT NOT NULL, tenant_id BIGINT NOT NULL,
                  link_url VARCHAR(255) NOT NULL, group_name VARCHAR(128),
                  label_id BIGINT, folder_id BIGINT, import_batch_id BIGINT,
                  origin TINYINT NOT NULL, membership_state TINYINT NOT NULL,
                  is_historical TINYINT DEFAULT 0, is_post_control TINYINT DEFAULT 0,
                  sync_protocol_mask TINYINT DEFAULT 0, remark VARCHAR(255),
                  deleted_at BIGINT, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link_import_batch (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  source_file_name VARCHAR(255)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link_preview (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, group_jid VARCHAR(128),
                  invite_code VARCHAR(128), wa_subject VARCHAR(255), member_size INT,
                  owner_phone VARCHAR(32), avatar_url VARCHAR(1024), last_preview_at BIGINT,
                  creator_country_iso2 VARCHAR(2), creator_continent_code VARCHAR(24),
                  group_created_at BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link_health (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, health_status TINYINT, is_banned TINYINT,
                  current_count INT, last_check_at BIGINT, last_health_error VARCHAR(64)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_folder (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  name VARCHAR(100), deleted_at BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE country (
                  id BIGINT PRIMARY KEY, iso2 VARCHAR(2), name_zh VARCHAR(64),
                  flag VARCHAR(16), continent_code VARCHAR(24), deleted_at BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE whatsapp_group_member_snapshot (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, group_jid VARCHAR(128) NOT NULL,
                  participant_jid VARCHAR(191) NOT NULL, phone VARCHAR(32),
                  is_admin TINYINT DEFAULT 0, snapshot_at BIGINT NOT NULL,
                  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
                  protocol_account_id VARCHAR(64), deleted_at BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE account_group_membership (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                  membership_status TINYINT NOT NULL, is_admin TINYINT, deleted_at BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE account_state (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, login_state TINYINT, account_state TINYINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE join_task_result (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128), status VARCHAR(32),
                  account_id BIGINT, is_admin TINYINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_metadata_sync_task (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, status TINYINT, trigger_source TINYINT,
                  next_run_at BIGINT, rerun_requested TINYINT DEFAULT 0,
                  last_success_at BIGINT, last_error_code VARCHAR(64),
                  last_error_message VARCHAR(512), updated_at BIGINT
                ) ENGINE=InnoDB
                """);
    }

    private static SqlSessionTemplate buildSqlSessionTemplate(DataSource dataSource)
            throws Exception {
        MyBatisConfig myBatisConfig = new MyBatisConfig();
        MybatisPlusInterceptor interceptor =
                myBatisConfig.mybatisPlusInterceptor(myBatisConfig.tenantLineHandler());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                new ClassPathResource("mapper/group/GroupListCurrentMapper.xml"),
                new ClassPathResource("mapper/group/GroupMetadataSyncTaskMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建群列表对账测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }
}
