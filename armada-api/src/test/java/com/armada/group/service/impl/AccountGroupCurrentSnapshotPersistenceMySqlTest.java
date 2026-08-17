package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.impl.GroupCurrentSnapshotMySqlTestSupport.RecordingDataSource;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupCurrentInviteMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL RR 下锁定新五表账号群快照的批量写入、分类和锁序。 */
@Testcontainers
class AccountGroupCurrentSnapshotPersistenceMySqlTest {

    private static final long TENANT_ID = 7L;
    private static final long BASELINE_CAPTURED_AT = 1_000L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_snapshot")
            .withUsername("armada")
            .withPassword("armada")
            .withCommand(
                    "--transaction-isolation=REPEATABLE-READ",
                    "--innodb-deadlock-detect=ON",
                    "--innodb-lock-wait-timeout=5");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbc;
    private static RecordingDataSource recordingDataSource;
    private static TransactionTemplate transactionTemplate;
    private static AccountGroupCurrentSnapshotPersistenceImpl persistence;
    private static GroupCurrentInvitePersistence currentInvitePersistence;

    @BeforeAll
    static void configureMysqlAndProductionMapper() throws Exception {
        DriverManagerDataSource rawDataSource = new DriverManagerDataSource();
        rawDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        rawDataSource.setUrl(MYSQL.getJdbcUrl());
        rawDataSource.setUsername(MYSQL.getUsername());
        rawDataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(rawDataSource);
        GroupCurrentSnapshotMySqlTestSupport.createLegacyContextSchema(jdbc);
        createLegacyHandleSchema();
        GroupCurrentSnapshotMySqlTestSupport.executeV120(rawDataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV121(rawDataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV122(rawDataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV124(rawDataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV127(rawDataSource);

        recordingDataSource = new RecordingDataSource(rawDataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(recordingDataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        SqlSessionTemplate sqlSessionTemplate = buildSqlSessionTemplate(recordingDataSource);
        AccountGroupCurrentSnapshotMapper mapper =
                sqlSessionTemplate.getMapper(AccountGroupCurrentSnapshotMapper.class);
        persistence = new AccountGroupCurrentSnapshotPersistenceImpl(mapper);
        currentInvitePersistence = new GroupCurrentInvitePersistence(
                sqlSessionTemplate.getMapper(GroupCurrentInviteMapper.class));
    }

    @AfterAll
    static void clearTenantContext() {
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
        jdbc.update("DELETE FROM account_group_baseline");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM group_link_preview");
        jdbc.update("DELETE FROM group_link");
        recordingDataSource.reset();
    }

    private static void createLegacyHandleSchema() {
        jdbc.execute("""
                CREATE TABLE group_link (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  group_id BIGINT DEFAULT NULL,
                  group_invite_id BIGINT DEFAULT NULL,
                  tenant_id BIGINT NOT NULL,
                  link_url VARCHAR(512) NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
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
                  member_add_mode TINYINT DEFAULT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void completeSnapshotOf400GroupsUsesAtMostTenStatementsAndClassifiesBaselineSafely()
            throws Exception {
        List<String> baselineJids = groupJids(0, 200);
        seedCapturedAccount(101L, "923300000101", baselineJids);
        List<AccountGroupsReportedEvent.Group> groups = groups(0, 400);
        Collections.reverse(groups);
        groups.add(groups.get(0));

        recordingDataSource.reset();
        writeSnapshot(101L, groups, true, 2_000L, "snapshot-400");

        assertThat(recordingDataSource.statements())
                .as("400 群必须全部走集合 SQL，不能通过 JDBC batch 隐藏服务端语句数")
                .hasSizeLessThanOrEqualTo(10)
                .noneMatch(sql -> sql.startsWith("BATCH "));
        assertThat(count("wa_group")).isEqualTo(400);
        assertThat(count("wa_group_profile")).isEqualTo(400);
        assertThat(count("wa_group_participant")).isEqualTo(400);
        assertThat(count("wa_account_group_binding")).isEqualTo(400);
        assertThat(count("account_group_sync_state")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_group WHERE display_name IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForMap(
                "SELECT member_count, checked_member_count FROM wa_group_profile p "
                        + "JOIN wa_group g ON g.id = p.group_id "
                        + "WHERE g.group_jid = '120363-snapshot-000@g.us'"))
                .containsEntry("member_count", 100)
                .containsEntry("checked_member_count", 100);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE was_in_initial_baseline = 1 "
                        + "AND first_post_control_observed_at IS NULL",
                Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE was_in_initial_baseline = 0 "
                        + "AND first_post_control_observed_at = 2000",
                Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE was_in_initial_baseline = 1 "
                        + "AND first_post_control_observed_at IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE membership_active_since_at = 2000",
                Integer.class)).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT wa_created_at FROM wa_group_profile p "
                        + "JOIN wa_group g ON g.id = p.group_id "
                        + "WHERE g.group_jid = '120363-snapshot-000@g.us'",
                Long.class)).isEqualTo(1_000_000L);
    }

    @Test
    void snapshotLinksExistingLegacyHandleToResolvedGroupId() throws Exception {
        String groupJid = groupJid(20);
        seedCapturedAccount(120L, "923300000120", List.of());
        jdbc.update("""
                INSERT INTO group_link
                  (id, tenant_id, link_url, created_at, updated_at)
                VALUES
                  (920, 7, 'wa://group/120363-snapshot-020@g.us', 100, 100),
                  (922, 7, 'https://chat.whatsapp.com/SameGroupAlias', 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview
                  (tenant_id, group_link_id, group_jid)
                VALUES (7, 920, ?), (7, 922, ?)
                """, groupJid, groupJid);

        writeSnapshot(
                120L, groups(20, 1), true, 2_000L, "snapshot-handle",
                List.of(new AccountGroupMembershipSnapshot(
                        920L, groupJid, null, "wa://group/" + groupJid, false)));

        assertThat(jdbc.queryForMap("""
                SELECT handle.group_id, current_group.group_jid
                FROM group_link handle
                JOIN wa_group current_group ON current_group.id = handle.group_id
                WHERE handle.id = 920
                """))
                .containsEntry("group_jid", groupJid);
        assertThat(jdbc.queryForObject(
                "SELECT group_id FROM group_link WHERE id = 922", Long.class)).isNull();
    }

    @Test
    void replayIsIdempotentAndCompleteSnapshotWritesEveryParticipantBeforeBinding()
            throws Exception {
        seedCapturedAccount(102L, "923300000102", List.of(groupJid(0)));
        List<AccountGroupsReportedEvent.Group> groups = groups(0, 2);
        writeSnapshot(102L, groups, true, 2_000L, "snapshot-first");

        Long firstActiveSince = scalarLong("""
                SELECT b.membership_active_since_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """);
        Long firstPost = scalarLong("""
                SELECT b.first_post_control_observed_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """);

        writeSnapshot(102L, groups, true, 3_000L, "snapshot-replay");
        assertThat(count("wa_group")).isEqualTo(2);
        assertThat(count("wa_group_profile")).isEqualTo(2);
        assertThat(count("wa_group_participant")).isEqualTo(2);
        assertThat(count("wa_account_group_binding")).isEqualTo(2);
        assertThat(scalarLong("""
                SELECT b.membership_active_since_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """)).isEqualTo(firstActiveSince);
        assertThat(scalarLong("""
                SELECT b.first_post_control_observed_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """)).isEqualTo(firstPost);

        recordingDataSource.reset();
        writeSnapshot(102L, List.of(groups.get(0)), true, 4_000L, "snapshot-missing");
        List<String> statements = recordingDataSource.statements();
        assertThat(statements).hasSizeLessThanOrEqualTo(10);
        String classificationRead = statements.stream()
                .filter(sql -> sql.contains("FROM WA_GROUP G")
                        && sql.contains("WA_ACCOUNT_GROUP_BINDING"))
                .findFirst()
                .orElseThrow();
        assertThat(classificationRead).doesNotContain("FOR UPDATE");
        String groupIdCurrentRead = statements.stream()
                .filter(sql -> sql.startsWith("SELECT GROUP_JID AS GROUPJID"))
                .findFirst()
                .orElseThrow();
        assertThat(groupIdCurrentRead)
                .contains("ORDER BY GROUP_JID ASC FOR UPDATE");
        int firstBindingDml = firstIndexContaining(statements, "wa_account_group_binding", "INSERT");
        int lastParticipantDml = lastDmlIndexContaining(statements, "wa_group_participant");
        assertThat(lastParticipantDml).isGreaterThanOrEqualTo(0);
        assertThat(firstBindingDml).isGreaterThan(lastParticipantDml);
        assertThat(jdbc.queryForObject("""
                SELECT p.presence_status
                FROM wa_group_participant p
                JOIN wa_group g ON g.id = p.group_id
                WHERE g.group_jid = '120363-snapshot-001@g.us'
                  AND p.pn_jid = '923300000102@s.whatsapp.net'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void visibleSnapshotRestoresSoftDeletedGroup() throws Exception {
        seedCapturedAccount(115L, "923300000115", List.of(groupJid(15)));
        writeSnapshot(115L, groups(15, 16), true, 2_000L, "snapshot-before-delete");
        jdbc.update("UPDATE wa_group SET deleted_at = 2500 WHERE group_jid = ?", groupJid(15));

        writeSnapshot(115L, groups(15, 16), true, 3_000L, "snapshot-after-delete");

        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM wa_group WHERE group_jid = ?",
                Long.class, groupJid(15))).isNull();
    }

    @Test
    void emptyCompleteSnapshotMarksAllPreviouslyBoundParticipantsMissing() throws Exception {
        seedCapturedAccount(103L, "923300000103", List.of(groupJid(0)));
        writeSnapshot(103L, groups(0, 1), true, 2_000L, "snapshot-visible");

        recordingDataSource.reset();
        writeSnapshot(103L, List.of(), true, 3_000L, "snapshot-empty-complete");

        assertThat(recordingDataSource.statements()).hasSizeLessThanOrEqualTo(10);
        assertThat(jdbc.queryForObject("""
                SELECT p.presence_status
                FROM wa_group_participant p
                JOIN wa_account_group_binding b ON b.participant_id = p.id
                WHERE b.account_id = 103
                """, Integer.class)).isEqualTo(2);
        assertThat(count("wa_account_group_binding")).isEqualTo(1);
    }

    @Test
    void preciseSelfMembershipDualWriteKeepsClassificationAndExitMeaning() throws Exception {
        seedCapturedAccount(104L, "923300000104", List.of(groupJid(0)));

        writeSelfMembership(
                104L, groupJid(1), AccountGroupMembershipStatus.IN_GROUP,
                2_000L, "wgp2-add", "WGP2_ADD");
        assertThat(jdbc.queryForMap("""
                SELECT p.presence_status, p.presence_source, p.last_joined_at,
                       b.was_in_initial_baseline, b.membership_active_since_at,
                       b.first_post_control_observed_at
                FROM wa_group_participant p
                JOIN wa_account_group_binding b ON b.participant_id = p.id
                JOIN wa_group g ON g.id = p.group_id
                WHERE b.account_id = 104 AND g.group_jid = ?
                """, groupJid(1)))
                .containsEntry("presence_status", 1)
                .containsEntry("presence_source", "WGP2_ADD")
                .containsEntry("last_joined_at", 2_000L)
                .containsEntry("was_in_initial_baseline", 0)
                .containsEntry("membership_active_since_at", 2_000L)
                .containsEntry("first_post_control_observed_at", 2_000L);

        writeSelfMembership(
                104L, groupJid(1), AccountGroupMembershipStatus.NOT_IN_GROUP,
                3_000L, "wgp2-remove", "WGP2_REMOVE");
        assertThat(jdbc.queryForMap("""
                SELECT p.presence_status, p.presence_source, p.last_exit_type,
                       p.last_exited_at, b.was_in_initial_baseline,
                       b.first_post_control_observed_at
                FROM wa_group_participant p
                JOIN wa_account_group_binding b ON b.participant_id = p.id
                JOIN wa_group g ON g.id = p.group_id
                WHERE b.account_id = 104 AND g.group_jid = ?
                """, groupJid(1)))
                .containsEntry("presence_status", 2)
                .containsEntry("presence_source", "WGP2_REMOVE")
                .containsEntry("last_exit_type", "UNKNOWN")
                .containsEntry("last_exited_at", 3_000L)
                .containsEntry("was_in_initial_baseline", 0)
                .containsEntry("first_post_control_observed_at", 2_000L);
    }

    @Test
    void addedGroupDecisionUsesCurrentParticipantFactAndOnlyFiresOnce() throws Exception {
        seedCapturedAccount(117L, "923300000117", List.of());
        String groupJid = groupJid(17);
        AccountGroupMembershipSnapshot current = new AccountGroupMembershipSnapshot(
                917L, groupJid, "新群", "wa://group/" + groupJid, false);

        writeSelfMembership(
                117L, groupJid, AccountGroupMembershipStatus.IN_GROUP,
                2_000L, "wgp2-add", "WGP2_ADD");
        AccountGroupMembershipChangeSet first = replaceSnapshot(
                117L, groups(17, 1), true, 2_100L, "snapshot-after-add", List.of(current));
        AccountGroupMembershipChangeSet replay = replaceSnapshot(
                117L, groups(17, 1), true, 2_200L, "snapshot-replay", List.of(current));

        assertThat(first.addedGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly(groupJid);
        assertThat(replay.addedGroups()).isEmpty();

        writeSelfMembership(
                117L, groupJid, AccountGroupMembershipStatus.NOT_IN_GROUP,
                4_000L, "wgp2-remove", "WGP2_REMOVE");
        AccountGroupMembershipChangeSet stale = replaceSnapshot(
                117L, groups(17, 1), false, 3_000L, "stale-snapshot", List.of(current));
        assertThat(stale.addedGroups()).isEmpty();
    }

    @Test
    void controlledRoleObservationCreatesBindingWithoutFabricatingJoinOrClassification()
            throws Exception {
        seedCapturedAccount(116L, "923300000116", List.of(groupJid(0)));

        writeControlledParticipantObservation(
                116L, groupJid(1), true, true,
                2_000L, "wgp2-promote", "WGP2_PROMOTE");

        assertThat(jdbc.queryForMap("""
                SELECT p.presence_status, p.presence_source, p.last_joined_at,
                       p.role, p.role_source, p.role_observed_at,
                       b.was_in_initial_baseline, b.membership_active_since_at,
                       b.first_post_control_observed_at, b.last_observed_at
                FROM wa_group_participant p
                JOIN wa_account_group_binding b ON b.participant_id = p.id
                JOIN wa_group g ON g.id = p.group_id
                WHERE b.account_id = 116 AND g.group_jid = ?
                """, groupJid(1)))
                .containsEntry("presence_status", 1)
                .containsEntry("presence_source", "WGP2_PROMOTE")
                .containsEntry("last_joined_at", null)
                .containsEntry("role", 2)
                .containsEntry("role_source", "WGP2_PROMOTE")
                .containsEntry("role_observed_at", 2_000L)
                .containsEntry("was_in_initial_baseline", null)
                .containsEntry("membership_active_since_at", null)
                .containsEntry("first_post_control_observed_at", null)
                .containsEntry("last_observed_at", 2_000L);
    }

    @Test
    void delayedAddDoesNotOverrideNewerPreciseRemoveOrCreatePostControlClassification()
            throws Exception {
        seedCapturedAccount(105L, "923300000105", List.of(groupJid(0)));

        writeSelfMembership(
                105L, groupJid(1), AccountGroupMembershipStatus.NOT_IN_GROUP,
                4_000L, "wgp2-remove-new", "WGP2_REMOVE");
        writeSelfMembership(
                105L, groupJid(1), AccountGroupMembershipStatus.IN_GROUP,
                3_000L, "wgp2-add-old", "WGP2_ADD");

        assertThat(jdbc.queryForMap("""
                SELECT p.presence_status, p.presence_source,
                       b.membership_active_since_at, b.was_in_initial_baseline,
                       b.first_post_control_observed_at
                FROM wa_group_participant p
                JOIN wa_account_group_binding b ON b.participant_id = p.id
                JOIN wa_group g ON g.id = p.group_id
                WHERE b.account_id = 105 AND g.group_jid = ?
                """, groupJid(1)))
                .containsEntry("presence_status", 2)
                .containsEntry("presence_source", "WGP2_REMOVE")
                .containsEntry("membership_active_since_at", null)
                .containsEntry("was_in_initial_baseline", null)
                .containsEntry("first_post_control_observed_at", null);
    }

    @Test
    void participantJoinFactsCreatePnAndLidParticipantsWithoutAccountBinding() {
        writeParticipantJoins(List.of(
                new WhatsappGroupJoinFact(
                        TENANT_ID, groupJid(7), "15550000007@s.whatsapp.net", "15550000007",
                        2_000L, 2_000L, "join-pn", 104L),
                new WhatsappGroupJoinFact(
                        TENANT_ID, groupJid(7), "123456789012347@lid", "5218129230974",
                        2_100L, 2_100L, "join-lid", 104L)));

        assertThat(count("wa_group")).isOne();
        assertThat(count("wa_group_participant")).isEqualTo(2);
        assertThat(count("wa_account_group_binding")).isZero();
        assertThat(jdbc.queryForList("""
                SELECT pn_jid, lid_jid, phone, presence_status, presence_source, last_joined_at
                FROM wa_group_participant
                ORDER BY COALESCE(pn_jid, lid_jid)
                """))
                .satisfiesExactly(
                        row -> assertThat(row)
                                .containsEntry("pn_jid", null)
                                .containsEntry("lid_jid", "123456789012347@lid")
                                .containsEntry("phone", "5218129230974")
                                .containsEntry("presence_status", 1)
                                .containsEntry("presence_source", "ADD_EVENT")
                                .containsEntry("last_joined_at", 2_100L),
                        row -> assertThat(row)
                                .containsEntry("pn_jid", "15550000007@s.whatsapp.net")
                                .containsEntry("lid_jid", null)
                                .containsEntry("phone", "15550000007")
                                .containsEntry("presence_status", 1)
                                .containsEntry("presence_source", "ADD_EVENT")
                                .containsEntry("last_joined_at", 2_000L));
    }

    @Test
    void newerParticipantDepartureWinsOverDelayedJoinAndKeepsBothLatestFacts() {
        writeParticipantJoins(List.of(new WhatsappGroupJoinFact(
                TENANT_ID, groupJid(8), "15550000008@s.whatsapp.net", "15550000008",
                2_000L, 2_000L, "join-first", 104L)));
        writeParticipantDepartures(List.of(new WhatsappGroupDepartureFact(
                TENANT_ID, groupJid(8), "15550000008@s.whatsapp.net", "15550000008",
                3_000L, "LEFT", 3_000L, "left-newer", "WGP2_NOTIFICATION")));
        writeParticipantJoins(List.of(new WhatsappGroupJoinFact(
                TENANT_ID, groupJid(8), "15550000008@s.whatsapp.net", "15550000008",
                2_500L, 2_500L, "join-delayed", 104L)));

        assertThat(jdbc.queryForMap("""
                SELECT presence_status, presence_source, presence_observed_at,
                       last_joined_at, last_join_source_event_id,
                       last_exit_type, last_exited_at, last_exit_source_event_id,
                       last_exit_source_type
                FROM wa_group_participant
                """))
                .containsEntry("presence_status", 2)
                .containsEntry("presence_source", "LEAVE_EVENT")
                .containsEntry("presence_observed_at", 3_000L)
                .containsEntry("last_joined_at", 2_500L)
                .containsEntry("last_join_source_event_id", "join-delayed")
                .containsEntry("last_exit_type", "LEFT")
                .containsEntry("last_exited_at", 3_000L)
                .containsEntry("last_exit_source_event_id", "left-newer")
                .containsEntry("last_exit_source_type", "WGP2_NOTIFICATION");
        assertThat(count("wa_account_group_binding")).isZero();
    }

    @Test
    void completeParticipantSnapshotUpdatesRolesHeaderAndMarksMissingParticipants() {
        writeParticipantJoins(List.of(new WhatsappGroupJoinFact(
                TENANT_ID, groupJid(9), "15550000009@s.whatsapp.net", "15550000009",
                1_500L, 1_500L, "join-before-snapshot", 104L)));

        writeParticipantSnapshot(groupJid(9), List.of(
                new GroupParticipantResult(
"15550000010:3@s.whatsapp.net", null, "+1 555 000 0010",
                        true, false, "admin"),
                new GroupParticipantResult(
"123456789012349@lid", null, null,
                        true, true, "superadmin")),
                2_000L, "member-snapshot-2000");

        assertThat(jdbc.queryForMap("""
                SELECT member_count, member_snapshot_at, member_snapshot_version
                FROM wa_group_profile
                """))
                .containsEntry("member_count", 2)
                .containsEntry("member_snapshot_at", 2_000L)
                .containsEntry("member_snapshot_version", "member-snapshot-2000");
        assertThat(jdbc.queryForList("""
                SELECT pn_jid, lid_jid, presence_status, presence_source,
                       role, role_source, last_snapshot_version
                FROM wa_group_participant
                ORDER BY COALESCE(pn_jid, lid_jid)
                """))
                .satisfiesExactly(
                        row -> assertThat(row)
                                .containsEntry("pn_jid", null)
                                .containsEntry("lid_jid", "123456789012349@lid")
                                .containsEntry("presence_status", 1)
                                .containsEntry("presence_source", "FULL_SNAPSHOT")
                                .containsEntry("role", 3)
                                .containsEntry("role_source", "FULL_SNAPSHOT")
                                .containsEntry("last_snapshot_version", "member-snapshot-2000"),
                        row -> assertThat(row)
                                .containsEntry("pn_jid", "15550000009@s.whatsapp.net")
                                .containsEntry("lid_jid", null)
                                .containsEntry("presence_status", 2)
                                .containsEntry("presence_source", "SNAPSHOT_ABSENT"),
                        row -> assertThat(row)
                                .containsEntry("pn_jid", "15550000010@s.whatsapp.net")
                                .containsEntry("lid_jid", null)
                                .containsEntry("presence_status", 1)
                                .containsEntry("role", 2)
                                .containsEntry("role_source", "FULL_SNAPSHOT")
                                .containsEntry("last_snapshot_version", "member-snapshot-2000"));
        assertThat(count("wa_account_group_binding")).isZero();
    }

    @Test
    void olderCompleteSnapshotCannotRollBackNewerHeaderOrExplicitDeparture() {
        writeParticipantSnapshot(groupJid(10), List.of(new GroupParticipantResult(
"15550000011@s.whatsapp.net", null, "15550000011",
                false, false, "member")), 2_000L, "member-snapshot-2000");
        writeParticipantDepartures(List.of(new WhatsappGroupDepartureFact(
                TENANT_ID, groupJid(10), "15550000011@s.whatsapp.net", "15550000011",
                3_000L, "LEFT", 3_000L, "left-3000", "WGP2_NOTIFICATION")));
        writeParticipantSnapshot(groupJid(10), List.of(new GroupParticipantResult(
"15550000011@s.whatsapp.net", null, "15550000011",
                true, true, "superadmin")), 2_500L, "member-snapshot-2500");
        writeParticipantSnapshot(
                groupJid(10), List.of(), 1_500L, "member-snapshot-1500");

        assertThat(jdbc.queryForMap("""
                SELECT member_count, member_snapshot_at, member_snapshot_version
                FROM wa_group_profile
                """))
                .containsEntry("member_count", 1)
                .containsEntry("member_snapshot_at", 2_500L)
                .containsEntry("member_snapshot_version", "member-snapshot-2500");
        assertThat(jdbc.queryForMap("""
                SELECT presence_status, presence_source, presence_observed_at,
                       role, role_source, role_observed_at
                FROM wa_group_participant
                """))
                .containsEntry("presence_status", 2)
                .containsEntry("presence_source", "LEAVE_EVENT")
                .containsEntry("presence_observed_at", 3_000L)
                .containsEntry("role", 3)
                .containsEntry("role_source", "FULL_SNAPSHOT")
                .containsEntry("role_observed_at", 2_500L);
    }

    @Test
    void sameTimeWinningSnapshotVersionAlsoWinsParticipantPresenceAndRole() {
        writeParticipantJoins(List.of(
                new WhatsappGroupJoinFact(
                        TENANT_ID, groupJid(11), "15550000012@s.whatsapp.net", "15550000012",
                        1_000L, 1_000L, "join-12", 104L),
                new WhatsappGroupJoinFact(
                        TENANT_ID, groupJid(11), "15550000013@s.whatsapp.net", "15550000013",
                        1_000L, 1_000L, "join-13", 104L)));
        writeParticipantSnapshot(groupJid(11), List.of(new GroupParticipantResult(
"15550000012@s.whatsapp.net", null, "15550000012",
                false, false, "member")), 2_000L, "member-snapshot-a");
        writeParticipantSnapshot(groupJid(11), List.of(
                new GroupParticipantResult(
"15550000012@s.whatsapp.net", null, "15550000012",
                        true, true, "superadmin"),
                new GroupParticipantResult(
"15550000013@s.whatsapp.net", null, "15550000013",
                        true, false, "admin")),
                2_000L, "member-snapshot-z");

        assertThat(jdbc.queryForMap("""
                SELECT member_count, member_snapshot_version
                FROM wa_group_profile
                """))
                .containsEntry("member_count", 2)
                .containsEntry("member_snapshot_version", "member-snapshot-z");
        assertThat(jdbc.queryForList("""
                SELECT pn_jid, presence_status, role, last_snapshot_version
                FROM wa_group_participant
                ORDER BY pn_jid
                """))
                .satisfiesExactly(
                        row -> assertThat(row)
                                .containsEntry("pn_jid", "15550000012@s.whatsapp.net")
                                .containsEntry("presence_status", 1)
                                .containsEntry("role", 3)
                                .containsEntry("last_snapshot_version", "member-snapshot-z"),
                        row -> assertThat(row)
                                .containsEntry("pn_jid", "15550000013@s.whatsapp.net")
                                .containsEntry("presence_status", 1)
                                .containsEntry("role", 2)
                                .containsEntry("last_snapshot_version", "member-snapshot-z"));
    }

    @Test
    void completeMetadataSnapshotUpdatesOnlyAcceptedProfileFields() {
        List<GroupParticipantResult> participants = List.of(new GroupParticipantResult(
"15550000014@s.whatsapp.net", null, "15550000014",
                false, false, "member"));
        GroupLinkPreview first = metadataPreview(groupJid(12), 2_000L, 2_100L);
        first.setWaSubject("当前群名");
        first.setWaDescription("当前描述");
        first.setWaDescriptionObserved(true);
        first.setMemberSize(1);
        first.setGroupCreatedAt(1_700_000_000L);
        first.setAnnounceOnly(true);
        first.setAnnounceOnlyObserved(true);
        first.setAdminOnlyEditInfo(false);
        first.setAdminOnlyEditInfoObserved(true);
        first.setMemberAddMode(true);
        first.setMemberAddModeObserved(true);
        first.setJoinApprovalMode(false);
        first.setJoinApprovalModeObserved(true);
        first.setEphemeralDurationSeconds(86_400);
        first.setEphemeralDurationObserved(true);
        writeCompleteMetadataSnapshot(first, participants, 2_100L, "metadata-2100");

        GroupLinkPreview stale = metadataPreview(groupJid(12), 1_500L, 1_600L);
        stale.setWaSubject("过期群名");
        stale.setWaDescription("过期描述");
        stale.setWaDescriptionObserved(true);
        stale.setMemberSize(0);
        stale.setAnnounceOnly(false);
        stale.setAnnounceOnlyObserved(true);
        writeCompleteMetadataSnapshot(stale, List.of(), 1_600L, "metadata-1600");

        GroupLinkPreview latest = metadataPreview(groupJid(12), 3_000L, 3_100L);
        latest.setWaSubject(" ");
        latest.setWaDescription(null);
        latest.setWaDescriptionObserved(true);
        latest.setMemberSize(1);
        latest.setAnnounceOnly(false);
        latest.setAnnounceOnlyObserved(true);
        latest.setAdminOnlyEditInfo(true);
        latest.setAdminOnlyEditInfoObserved(false);
        writeCompleteMetadataSnapshot(latest, participants, 3_100L, "metadata-3100");

        assertThat(jdbc.queryForMap("""
                SELECT subject, description, member_count, wa_created_at,
                       announce_only, admin_only_edit_info, member_add_mode,
                       join_approval_mode, ephemeral_duration_seconds,
                       metadata_observed_at, member_snapshot_at
                FROM wa_group_profile
                """))
                .containsEntry("subject", "当前群名")
                .containsEntry("description", null)
                .containsEntry("member_count", 1)
                .containsEntry("wa_created_at", 1_700_000_000_000L)
                .containsEntry("announce_only", 0)
                .containsEntry("admin_only_edit_info", 0)
                .containsEntry("member_add_mode", 1)
                .containsEntry("join_approval_mode", 0)
                .containsEntry("ephemeral_duration_seconds", 86_400)
                .containsEntry("metadata_observed_at", 3_000L)
                .containsEntry("member_snapshot_at", 3_100L);
    }

    @Test
    void currentInviteBindsAfterGroupResolutionAndRejectsDelayedRotation() {
        writeCurrentInvite(null, "invite-a", 1_000L);
        assertThat(jdbc.queryForMap("SELECT group_id, health_status, banned, last_checked_at FROM wa_group_invite WHERE invite_code = 'invite-a'"))
                .containsEntry("group_id", null)
                .containsEntry("health_status", 1)
                .containsEntry("banned", 0)
                .containsEntry("last_checked_at", 1_000L);

        writeCurrentInvite(groupJid(13), "invite-a", 1_500L);
        writeCurrentInvite(groupJid(13), "invite-b", 2_000L);
        writeCurrentInvite(groupJid(13), "invite-a", 1_700L);

        assertThat(jdbc.queryForMap("SELECT invite.invite_code, profile.current_invite_observed_at "
                        + "FROM wa_group_profile profile JOIN wa_group_invite invite ON invite.id = profile.current_invite_id"))
                .containsEntry("invite_code", "invite-b")
                .containsEntry("current_invite_observed_at", 2_000L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_group_invite invite "
                        + "JOIN wa_group wa_group ON wa_group.id = invite.group_id "
                        + "WHERE wa_group.group_jid = ?", Integer.class, groupJid(13))).isEqualTo(2);

        jdbc.update("UPDATE wa_group_invite SET health_status = 3, banned = 1, deleted_at = 2500, "
                + "last_error_code = 'BANNED', failure_count = 4, last_checked_at = 2500 "
                + "WHERE invite_code = 'invite-b'");
        jdbc.update("UPDATE wa_group SET deleted_at = 2500 WHERE group_jid = ?", groupJid(13));
        writeCurrentInvite(groupJid(13), "invite-b", 3_000L);
        assertThat(jdbc.queryForMap(
                "SELECT health_status, banned, last_error_code, failure_count, last_checked_at, deleted_at "
                        + "FROM wa_group_invite WHERE invite_code = 'invite-b'"))
                .containsEntry("health_status", 3)
                .containsEntry("banned", 1)
                .containsEntry("last_error_code", "BANNED")
                .containsEntry("failure_count", 4)
                .containsEntry("last_checked_at", 3_000L)
                .containsEntry("deleted_at", null);
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM wa_group WHERE group_jid = ?",
                Long.class, groupJid(13))).isNull();
    }

    @Test
    void publicPreviewStaysOnInviteAndResolvedHealthUpdatesGroupProfile() {
        GroupLinkPreview publicPreview = metadataPreview(null, 900L, 900L);
        publicPreview.setGroupLinkId(921L);
        publicPreview.setInviteCode("invite-health");
        publicPreview.setWaSubject("公开群名");
        publicPreview.setAvatarUrl("https://cdn.example/public.jpg");
        publicPreview.setLastPreviewAt(900L);
        jdbc.update("""
                INSERT INTO group_link
                  (id, tenant_id, link_url, created_at, updated_at)
                VALUES (921, 7, 'https://chat.whatsapp.com/invite-health', 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link_preview
                  (tenant_id, group_link_id, invite_code)
                VALUES (7, 921, 'invite-health')
                """);
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction -> {
                currentInvitePersistence.applyPublicPreview(publicPreview, 8L);
                currentInvitePersistence.apply(921L, groupJid(14), "invite-health", 1_000L);
                currentInvitePersistence.applyHealth(groupJid(14), unavailableHealth());
                persistence.applyConfirmedMetadata(confirmedMemberAddMode());
            });
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForMap("SELECT * FROM wa_group_invite WHERE invite_code = 'invite-health'"))
                .containsAllEntriesOf(Map.ofEntries(
                        Map.entry("origin", 1), Map.entry("label_id", 8L),
                        Map.entry("preview_subject", "公开群名"), Map.entry("avatar_url", "https://cdn.example/public.jpg"),
                        Map.entry("preview_observed_at", 900L), Map.entry("health_status", 1),
                        Map.entry("last_checked_at", 1_000L),
                        Map.entry("failure_count", 0)));
        assertThat(jdbc.queryForMap("SELECT banned FROM wa_group_invite"))
                .containsEntry("banned", null);
        assertThat(jdbc.queryForMap("""
                SELECT handle.group_id, handle.group_invite_id
                FROM group_link handle
                WHERE handle.id = 921
                """))
                .doesNotContainEntry("group_id", null)
                .doesNotContainEntry("group_invite_id", null);
        assertThat(jdbc.queryForMap("""
                SELECT member_add_mode, metadata_observed_at, member_count, checked_member_count,
                       health_status, banned, last_checked_at, last_error_code, failure_count
                FROM wa_group_profile
                """))
                .containsEntry("member_add_mode", 1)
                .containsEntry("metadata_observed_at", 2_500L)
                .containsEntry("member_count", null)
                .containsEntry("checked_member_count", 41)
                .containsEntry("health_status", 3)
                .containsEntry("banned", 1)
                .containsEntry("last_checked_at", 2_000L)
                .containsEntry("last_error_code", "CHAT_SUSPENDED")
                .containsEntry("failure_count", 2);
    }

    @Test
    void publicPreviewRestoresSoftDeletedInvite() {
        GroupLinkPreview publicPreview = metadataPreview(null, 900L, 900L);
        publicPreview.setInviteCode("invite-restored");
        publicPreview.setWaSubject("重新导入群");
        publicPreview.setLastPreviewAt(900L);
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    currentInvitePersistence.applyPublicPreview(publicPreview, 8L));
            jdbc.update("UPDATE wa_group_invite SET deleted_at = 950 "
                    + "WHERE invite_code = 'invite-restored'");
            transactionTemplate.executeWithoutResult(transaction ->
                    currentInvitePersistence.applyPublicPreview(publicPreview, 8L));
        } finally {
            TenantContext.clear();
        }

        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM wa_group_invite WHERE invite_code = 'invite-restored'",
                Long.class)).isNull();
    }

    private static void writeSnapshot(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean complete,
            long syncAt,
            String eventId) {
        writeSnapshot(accountId, groups, complete, syncAt, eventId, List.of());
    }

    private static AccountGroupMembershipChangeSet replaceSnapshot(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean complete,
            long syncAt,
            String eventId,
            List<AccountGroupMembershipSnapshot> legacyGroups) {
        TenantContext.set(TENANT_ID);
        try {
            return transactionTemplate.execute(status -> persistence.replaceVisibleGroups(
                    accountId, groups, complete, syncAt, eventId, legacyGroups));
        } finally {
            TenantContext.clear();
        }
    }

    private static void writeSnapshot(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean complete,
            long syncAt,
            String eventId,
            List<AccountGroupMembershipSnapshot> legacyGroups) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(status -> persistence.replaceVisibleGroups(
                    accountId, groups, complete, syncAt, eventId, legacyGroups));
        } finally {
            TenantContext.clear();
        }
    }

    private static void writeSelfMembership(
            Long accountId,
            String groupJid,
            AccountGroupMembershipStatus status,
            long occurredAt,
            String eventId,
            String source) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    persistence.applySelfMembershipChanged(
                            accountId, groupJid, status, occurredAt, eventId, source));
        } finally {
            TenantContext.clear();
        }
    }

    private static void writeControlledParticipantObservation(
            Long accountId,
            String groupJid,
            boolean inGroup,
            boolean admin,
            long observedAt,
            String eventId,
            String source) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    persistence.applyControlledParticipantObservation(
                            accountId, groupJid, inGroup, admin,
                            observedAt, eventId, source));
        } finally {
            TenantContext.clear();
        }
    }

    private static void writeParticipantJoins(List<WhatsappGroupJoinFact> facts) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    persistence.applyParticipantJoins(facts));
        } finally {
            TenantContext.clear();
        }
    }

    private static void writeParticipantDepartures(List<WhatsappGroupDepartureFact> facts) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    persistence.applyParticipantDepartures(facts));
        } finally {
            TenantContext.clear();
        }
    }

    private static void writeParticipantSnapshot(
            String groupJid,
            List<GroupParticipantResult> participants,
            long snapshotAt,
            String snapshotVersion) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    persistence.replaceCompleteParticipantSnapshot(
                            groupJid, participants, snapshotAt, snapshotVersion));
        } finally {
            TenantContext.clear();
        }
    }

    private static void writeCompleteMetadataSnapshot(
            GroupLinkPreview preview,
            List<GroupParticipantResult> participants,
            long snapshotAt,
            String snapshotVersion) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    persistence.replaceCompleteGroupMetadataSnapshot(
                            preview, participants, snapshotAt, snapshotVersion));
        } finally {
            TenantContext.clear();
        }
    }

    private static GroupLinkPreview metadataPreview(
            String groupJid,
            long metadataObservedAt,
            long updatedAt) {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupJid(groupJid);
        preview.setMetadataObservedAt(metadataObservedAt);
        preview.setUpdatedAt(updatedAt);
        return preview;
    }

    private static void writeCurrentInvite(
            String groupJid,
            String inviteCode,
            long observedAt) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    currentInvitePersistence.apply(null, groupJid, inviteCode, observedAt));
        } finally {
            TenantContext.clear();
        }
    }

    private static GroupLinkHealth unavailableHealth() {
        GroupLinkHealth health = new GroupLinkHealth();
        health.setHealthStatus(3);
        health.setBanned(true);
        health.setCurrentCount(41);
        health.setLastCheckAt(2_000L);
        health.setLastHealthError("CHAT_SUSPENDED");
        health.setHealthFailureCount(2);
        return health;
    }

    private static GroupLinkPreview confirmedMemberAddMode() {
        GroupLinkPreview preview = metadataPreview(groupJid(14), 2_500L, 2_500L);
        preview.setMemberAddMode(true);
        preview.setMemberAddModeObserved(true);
        return preview;
    }

    private static void seedCapturedAccount(Long accountId, String phone, List<String> baselineJids)
            throws Exception {
        Map<String, String> subjects = new LinkedHashMap<>();
        for (String groupJid : baselineJids) {
            subjects.put(groupJid, "baseline-" + groupJid);
        }
        jdbc.update("""
                INSERT INTO account (
                  id, tenant_id, ws_phone, protocol_id, protocol_account_id,
                  group_baseline_state, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, 'web', ?, 2, 100, 100, NULL)
                """, accountId, TENANT_ID, phone, "acc_" + accountId);
        jdbc.update("""
                INSERT INTO account_group_baseline (
                  tenant_id, account_id, baseline_group_jids, baseline_group_subjects,
                  group_count, captured_at, last_group_sync_requested_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 900, 100, 100)
                """, TENANT_ID, accountId,
                OBJECT_MAPPER.writeValueAsString(baselineJids),
                OBJECT_MAPPER.writeValueAsString(subjects),
                baselineJids.size(), BASELINE_CAPTURED_AT);
    }

    private static List<AccountGroupsReportedEvent.Group> groups(int start, int count) {
        List<AccountGroupsReportedEvent.Group> groups = new ArrayList<>(count);
        for (int index = start; index < start + count; index++) {
            groups.add(group(index));
        }
        return groups;
    }

    private static AccountGroupsReportedEvent.Group group(int index) {
        return new AccountGroupsReportedEvent.Group(
                groupJid(index),
                "群-" + index,
                100 + index,
                "923300009999@s.whatsapp.net",
                "923300009999",
                (index & 1) == 0,
                (index & 1) == 1,
                "https://cdn.example/group-" + index + ".jpg",
                1_000L + index,
                // 账号群报告的群设置与描述本用例不涉及，保持未观察。
                null,
                null,
                null,
                false,
                null,
                null);
    }

    private static List<String> groupJids(int start, int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = start; index < start + count; index++) {
            values.add(groupJid(index));
        }
        return values;
    }

    private static String groupJid(int index) {
        return "120363-snapshot-%03d@g.us".formatted(index);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Long scalarLong(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static int firstIndexContaining(List<String> statements, String table, String verb) {
        String normalizedTable = table.toUpperCase(java.util.Locale.ROOT);
        for (int index = 0; index < statements.size(); index++) {
            String sql = statements.get(index);
            if (sql.contains(normalizedTable) && sql.startsWith(verb)) {
                return index;
            }
        }
        return -1;
    }

    private static int lastDmlIndexContaining(List<String> statements, String table) {
        String normalizedTable = table.toUpperCase(java.util.Locale.ROOT);
        for (int index = statements.size() - 1; index >= 0; index--) {
            String sql = statements.get(index);
            if (sql.startsWith("INSERT INTO " + normalizedTable)
                    || sql.startsWith("UPDATE " + normalizedTable)) {
                return index;
            }
        }
        return -1;
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
                new ClassPathResource("mapper/group/AccountGroupCurrentSnapshotMapper.xml"),
                new ClassPathResource("mapper/group/GroupCurrentInviteMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建账号群新模型测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }

    /**
     * 锁定群设置字段的三种语义：明确开启、明确关闭、未观察到。
     *
     * <p>未观察到必须保留上次事实而不是清空，否则协议层一次不带设置的快照就会把控端
     * 已知的群设置抹成未知；明确的 false 又必须真正落库，否则控端只看得到设置开启、
     * 永远看不到关闭。</p>
     */
    @Test
    void snapshotPersistsObservedGroupSettingsAndKeepsUnobservedValues() throws Exception {
        seedCapturedAccount(160L, "923300000160", List.of());
        String groupJid = groupJid(60);

        writeSnapshot(160L, List.of(settingsGroup(groupJid, true, true, false)),
                true, 2_000L, "settings-observed");

        assertThat(profileSetting(groupJid, "announce_only")).isEqualTo(1);
        assertThat(profileSetting(groupJid, "admin_only_edit_info")).isEqualTo(1);
        assertThat(profileSetting(groupJid, "member_add_mode")).isEqualTo(0);

        // 协议未观察到设置时字段为 null，COALESCE 必须保留上一次的已知事实。
        writeSnapshot(160L, List.of(settingsGroup(groupJid, null, null, null)),
                true, 3_000L, "settings-unobserved");

        assertThat(profileSetting(groupJid, "announce_only")).isEqualTo(1);
        assertThat(profileSetting(groupJid, "admin_only_edit_info")).isEqualTo(1);
        assertThat(profileSetting(groupJid, "member_add_mode")).isEqualTo(0);

        // 明确关闭与"未观察到"必须区分开：false 要真正写进库，把 1 覆盖回 0。
        writeSnapshot(160L, List.of(settingsGroup(groupJid, false, false, true)),
                true, 4_000L, "settings-disabled");

        assertThat(profileSetting(groupJid, "announce_only")).isEqualTo(0);
        assertThat(profileSetting(groupJid, "admin_only_edit_info")).isEqualTo(0);
        assertThat(profileSetting(groupJid, "member_add_mode")).isEqualTo(1);
    }

    /** 构造只关心群设置三字段的上报群，其余字段取不影响本用例的最小值。 */
    private static AccountGroupsReportedEvent.Group settingsGroup(
            String groupJid,
            Boolean announceOnly,
            Boolean adminOnlyEditInfo,
            Boolean memberAddMode) {
        return new AccountGroupsReportedEvent.Group(
                groupJid, "Settings", 5, null, null, true,
                announceOnly, null, null, adminOnlyEditInfo, memberAddMode,
                null, false, null, null);
    }

    /** 按群 JID 读取 wa_group_profile 的单个群设置列，TINYINT 以 0/1 返回。 */
    private static Integer profileSetting(String groupJid, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM wa_group_profile profile "
                        + "JOIN wa_group current_group ON current_group.id = profile.group_id "
                        + "WHERE current_group.group_jid = ?",
                Integer.class, groupJid);
    }

    /**
     * 锁定群描述、入群审批与限时消息三项的观察语义。
     *
     * <p>描述用独立的 observed 标记而非 null 判断：快照无法区分"群没有描述"与"协议没返回
     * 描述"，靠 null 判断会让空描述永远写不进去，或者让未观察抹掉已知描述。限时消息的 0
     * 是合法值（明确关闭），必须与 null（未观察）区分开真正落库。</p>
     */
    @Test
    void snapshotPersistsDescriptionApprovalAndEphemeralWithObservationSemantics()
            throws Exception {
        seedCapturedAccount(170L, "923300000170", List.of());
        String groupJid = groupJid(70);

        writeSnapshot(170L, List.of(profileGroup(groupJid, "Hello", true, true, 86_400)),
                true, 2_000L, "profile-observed");

        assertThat(profileText(groupJid, "description")).isEqualTo("Hello");
        assertThat(profileSetting(groupJid, "join_approval_mode")).isEqualTo(1);
        assertThat(profileSetting(groupJid, "ephemeral_duration_seconds")).isEqualTo(86_400);

        // 描述未观察必须保留已知值；入群审批明确 false 必须落库覆盖。
        writeSnapshot(170L, List.of(profileGroup(groupJid, null, false, false, null)),
                true, 3_000L, "profile-partially-unobserved");

        assertThat(profileText(groupJid, "description")).isEqualTo("Hello");
        assertThat(profileSetting(groupJid, "join_approval_mode")).isEqualTo(0);
        assertThat(profileSetting(groupJid, "ephemeral_duration_seconds")).isEqualTo(86_400);

        // 明确观察到空描述要写成空串；限时消息明确关闭要落 0，都不能被当成未观察。
        writeSnapshot(170L, List.of(profileGroup(groupJid, "", true, false, 0)),
                true, 4_000L, "profile-cleared");

        assertThat(profileText(groupJid, "description")).isEmpty();
        assertThat(profileSetting(groupJid, "ephemeral_duration_seconds")).isZero();
    }

    /** 构造只关心描述、入群审批与限时消息的上报群。 */
    private static AccountGroupsReportedEvent.Group profileGroup(
            String groupJid,
            String description,
            boolean descriptionObserved,
            Boolean joinApprovalMode,
            Integer ephemeralDurationSeconds) {
        return new AccountGroupsReportedEvent.Group(
                groupJid, "Profile", 5, null, null, true,
                null, null, null, null, null,
                description, descriptionObserved, joinApprovalMode, ephemeralDurationSeconds);
    }

    /** 按群 JID 读取 wa_group_profile 的单个文本列。 */
    private static String profileText(String groupJid, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM wa_group_profile profile "
                        + "JOIN wa_group current_group ON current_group.id = profile.group_id "
                        + "WHERE current_group.group_jid = ?",
                String.class, groupJid);
    }

}
