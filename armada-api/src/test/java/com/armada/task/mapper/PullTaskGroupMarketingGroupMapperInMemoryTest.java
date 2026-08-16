package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolAddDTO;
import com.armada.task.model.entity.PullTaskGroupMarketingGroupOccupancy;
import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateAccountRow;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateRow;
import com.armada.task.model.vo.PullTaskGroupMarketingWaitingPoolVO;
import com.armada.task.service.PullTaskGroupMarketingGroupService;
import com.armada.task.service.impl.PullTaskGroupMarketingGroupServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 使用 H2 MySQL 模式验证拉群营销候选群和等待池真实 Mapper XML。 */
@SpringJUnitConfig(PullTaskGroupMarketingGroupMapperInMemoryTest.TestMyBatisPlusConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
public class PullTaskGroupMarketingGroupMapperInMemoryTest {

    private static final String SERVICE_TOKEN =
            "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_TOKEN =
            "22222222-2222-4222-8222-222222222222";
    private static final String ROLLBACK_TOKEN =
            "33333333-3333-4333-8333-333333333333";
    private static final String CONCURRENT_TOKEN_A =
            "44444444-4444-4444-8444-444444444444";
    private static final String CONCURRENT_TOKEN_B =
            "55555555-5555-4555-8555-555555555555";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupMarketingCandidateMapper candidateMapper;

    @Autowired
    private PullTaskGroupMarketingGroupOccupancyMapper occupancyMapper;

    @Autowired
    private PullTaskGroupMarketingGroupService groupService;

    @Autowired
    private CountryService countryService;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS");
        executeSql(schema(), fixtures());
        TenantContext.set(7L);
        reset(countryService);
        when(countryService.resolveActiveCountriesByPhoneNumbers(anyCollection()))
                .thenReturn(Map.of("919900000001",
                        new CountryReferenceVO(91L, "IN", "印度", "+91", "🇮🇳", "ASIA")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void deduplicatesGroupAndAggregatesEligibleManagersAcrossAccounts() {
        PullTaskGroupMarketingCandidateQuery query = query(false, null);

        assertThat(candidateMapper.countPage(query)).isEqualTo(2);
        assertThat(candidateMapper.selectPage(query, 0, 10))
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .containsExactly("120363001@g.us", "120363003@g.us");

        PullTaskGroupMarketingCandidateRow mixed = candidateMapper.selectPage(query, 0, 10).get(0);
        assertThat(mixed.getHistorical()).isTrue();
        assertThat(mixed.getSelfCollected()).isTrue();
        assertThat(mixed.getEligibleAccountCount()).isEqualTo(2);
        assertThat(mixed.getOnlineAccountCount()).isEqualTo(1);
        assertThat(mixed.getOwnerPhone()).isEqualTo("919900000001");

        assertThat(candidateMapper.selectAccountsByGroupJids(List.of(mixed.getGroupJid())))
                .extracting(PullTaskGroupMarketingCandidateAccountRow::getAccountId)
                .containsExactly(101L, 102L);

        assertThat(candidateMapper.selectByGroupJids(List.of(mixed.getGroupJid())))
                .singleElement()
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .isEqualTo("120363001@g.us");

        PullTaskGroupMarketingCandidateRow selfCollected = candidateMapper.selectPage(query, 0, 10).get(1);
        assertThat(selfCollected.getSourceJoinTaskId()).isEqualTo(501L);
        assertThat(selfCollected.getSourceJoinTaskName()).isEqualTo("印度自收任务");
        assertThat(selfCollected.getSourceJoinedAt()).isEqualTo(3_300L);
        assertThat(selfCollected.getSourcePromotedAt()).isEqualTo(3_400L);
    }

    @Test
    void currentBindingsOverrideStaleLegacyMembershipForCandidateEligibility()
            throws SQLException {
        executeSql("""
                UPDATE account_group_membership
                SET membership_status = 3, is_admin = 0
                WHERE id IN (1, 2)
                """);

        PullTaskGroupMarketingCandidateQuery query = query(false, null);

        assertThat(candidateMapper.selectPage(query, 0, 10))
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .contains("120363001@g.us");
        assertThat(candidateMapper.selectAccountsByGroupJids(List.of("120363001@g.us")))
                .extracting(PullTaskGroupMarketingCandidateAccountRow::getAccountId)
                .containsExactly(101L, 102L);
    }

    @Test
    void showsMemberOnlyGroupOnlyWhenExplicitlyRequestedAndNeverMakesItEligible() {
        PullTaskGroupMarketingCandidateQuery hidden = query(false, null);
        assertThat(candidateMapper.selectPage(hidden, 0, 10))
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .doesNotContain("120363002@g.us");

        PullTaskGroupMarketingCandidateQuery visible = query(true, null);
        assertThat(candidateMapper.selectPage(visible, 0, 10))
                .filteredOn(row -> "120363002@g.us".equals(row.getGroupJid()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getEligibleAccountCount()).isZero();
                    assertThat(row.getOnlineAccountCount()).isZero();
                });
    }

    @Test
    void filtersBySourceAndKeepsOtherTenantsOut() {
        PullTaskGroupMarketingCandidateQuery historical = query(false, PullTaskGroupSource.HISTORICAL);
        assertThat(candidateMapper.selectPage(historical, 0, 10))
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .containsExactly("120363001@g.us");

        PullTaskGroupMarketingCandidateQuery selfCollected = query(false, PullTaskGroupSource.SELF_COLLECTED);
        assertThat(candidateMapper.selectPage(selfCollected, 0, 10))
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .containsExactly("120363003@g.us");

        TenantContext.set(8L);
        assertThat(candidateMapper.selectPage(query(false, null), 0, 10))
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .containsExactly("120363901@g.us");
    }

    @Test
    void managerPhoneFilterDoesNotMatchOrdinaryMemberRelations() {
        PullTaskGroupMarketingCandidateQuery ordinaryMember = query(true, null);
        ordinaryMember.setManagerPhone("000003");
        assertThat(candidateMapper.selectPage(ordinaryMember, 0, 10)).isEmpty();

        PullTaskGroupMarketingCandidateQuery manager = query(false, null);
        manager.setManagerPhone("000001");
        assertThat(candidateMapper.selectPage(manager, 0, 10))
                .extracting(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .containsExactly("120363001@g.us");
    }

    @Test
    void softOccupancyIsIdempotentForOwnerAndExclusiveAcrossPools() {
        PullTaskGroupMarketingGroupOccupancy first = occupancy("pool-a", 88L, 5_000L);
        PullTaskGroupMarketingGroupOccupancy conflict = occupancy("pool-b", 99L, 6_000L);

        assertThat(occupancyMapper.insertWaiting(first)).isEqualTo(1);
        assertThatThrownBy(() -> occupancyMapper.insertWaiting(first))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> occupancyMapper.insertWaiting(conflict))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(occupancyMapper.selectActiveByGroupJids(List.of(first.getGroupJid())))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getReservationToken()).isEqualTo("pool-a");
                    assertThat(row.getCreatedBy()).isEqualTo(88L);
                    assertThat(row.getGroupSource()).isEqualTo("HISTORICAL");
                });

        assertThat(occupancyMapper.releaseWaiting(
                "pool-b", first.getGroupJid(), 99L, 7_000L)).isZero();
        assertThat(occupancyMapper.releaseWaiting(
                "pool-a", first.getGroupJid(), 88L, 7_000L)).isEqualTo(1);
        assertThat(occupancyMapper.insertWaiting(conflict)).isEqualTo(1);
    }

    @Test
    void waitingPoolReadsAreBoundToTokenAndCreator() {
        assertThat(occupancyMapper.insertWaiting(occupancy("pool-a", 88L, 5_000L))).isEqualTo(1);
        assertThat(occupancyMapper.updateWaitingSnapshot(
                "pool-a", 88L, "重命名任务", 9_000L, 5_500L)).isEqualTo(1);

        assertThat(occupancyMapper.selectWaitingByToken("pool-a", 88L))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getTaskNameSnapshot()).isEqualTo("重命名任务");
                    assertThat(row.getPlannedStartAt()).isEqualTo(9_000L);
                });
        assertThat(occupancyMapper.selectWaitingByToken("pool-a", 99L)).isEmpty();

        TenantContext.set(8L);
        assertThat(occupancyMapper.selectWaitingByToken("pool-a", 88L)).isEmpty();
    }

    @Test
    void expiresAbandonedWaitingPoolsAndRenewsOwnedPools() {
        TenantContext.set(8L);
        PullTaskGroupMarketingGroupOccupancy otherTenant = occupancy("pool-z", 99L, 5_000L);
        otherTenant.setExpiresAt(6_000L);
        assertThat(occupancyMapper.insertWaiting(otherTenant)).isEqualTo(1);

        TenantContext.set(7L);
        PullTaskGroupMarketingGroupOccupancy abandoned = occupancy("pool-a", 88L, 5_000L);
        abandoned.setExpiresAt(6_000L);
        assertThat(occupancyMapper.insertWaiting(abandoned)).isEqualTo(1);

        assertThat(occupancyMapper.releaseExpiredWaiting(7_000L)).isEqualTo(1);
        assertThat(occupancyMapper.selectWaitingByToken("pool-a", 88L)).isEmpty();
        TenantContext.set(8L);
        assertThat(occupancyMapper.selectWaitingByToken("pool-z", 99L)).hasSize(1);

        TenantContext.set(7L);
        PullTaskGroupMarketingGroupOccupancy active = occupancy("pool-b", 88L, 8_000L);
        active.setExpiresAt(9_000L);
        assertThat(occupancyMapper.insertWaiting(active)).isEqualTo(1);
        assertThat(occupancyMapper.renewWaiting("pool-b", 88L, 12_000L, 8_500L)).isEqualTo(1);
        assertThat(occupancyMapper.selectWaitingByToken("pool-b", 88L))
                .singleElement()
                .extracting(PullTaskGroupMarketingGroupOccupancy::getExpiresAt)
                .isEqualTo(12_000L);
    }

    @Test
    void serviceRoundTripUsesRealTransactionsAndMockedCountryReference() {
        PullTaskGroupMarketingCandidateQuery query = query(false, null);
        var candidates = groupService.listCandidates(query, 88L);

        assertThat(candidates.total()).isEqualTo(2);
        assertThat(candidates.list())
                .filteredOn(row -> "120363001@g.us".equals(row.groupJid()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.countryIso2()).isEqualTo("IN");
                    assertThat(row.countryName()).isEqualTo("印度");
                    assertThat(row.countryFlag()).isEqualTo("🇮🇳");
                    assertThat(row.operableAccounts()).hasSize(2);
                });

        PullTaskGroupMarketingWaitingPoolVO waiting = groupService.addWaiting(
                new PullTaskGroupMarketingWaitingPoolAddDTO(
                        SERVICE_TOKEN, "H2端到端", null, List.of("120363001@g.us")),
                88L);
        assertThat(waiting.groups())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.inCurrentWaitingPool()).isTrue();
                    assertThat(row.selectable()).isFalse();
                });

        PullTaskGroupMarketingWaitingPoolVO conflict = groupService.addWaiting(
                new PullTaskGroupMarketingWaitingPoolAddDTO(
                        OTHER_TOKEN, "竞争等待池", null, List.of("120363001@g.us")),
                99L);
        assertThat(conflict.groups()).isEmpty();
        assertThat(conflict.rejected()).singleElement();

        groupService.releaseWaiting(SERVICE_TOKEN, 88L);
        assertThat(occupancyMapper.selectActiveByGroupJids(List.of("120363001@g.us")))
                .isEmpty();
    }

    @Test
    void rollsBackInsertedWaitingWhenExternalCountryAssemblyFails() {
        when(countryService.resolveActiveCountriesByPhoneNumbers(anyCollection()))
                .thenThrow(new IllegalStateException("country mock unavailable"));

        assertThatThrownBy(() -> groupService.addWaiting(
                new PullTaskGroupMarketingWaitingPoolAddDTO(
                        ROLLBACK_TOKEN, "事务回滚", null, List.of("120363001@g.us")),
                88L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("country mock unavailable");

        assertThat(occupancyMapper.selectActiveByGroupJids(List.of("120363001@g.us")))
                .isEmpty();
    }

    @Test
    void concurrentServicesAllowExactlyOnePoolToOccupyTheSameGroup() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PullTaskGroupMarketingWaitingPoolVO> first = executor.submit(
                    () -> addConcurrently(start, CONCURRENT_TOKEN_A, 88L));
            Future<PullTaskGroupMarketingWaitingPoolVO> second = executor.submit(
                    () -> addConcurrently(start, CONCURRENT_TOKEN_B, 99L));
            start.countDown();

            List<PullTaskGroupMarketingWaitingPoolVO> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().mapToInt(result -> result.groups().size()).sum())
                    .isEqualTo(1);
            assertThat(results.stream().mapToInt(result -> result.rejected().size()).sum())
                    .isEqualTo(1);
            assertThat(occupancyMapper.selectActiveByGroupJids(List.of("120363001@g.us")))
                    .singleElement();
        } finally {
            executor.shutdownNow();
        }
    }

    private PullTaskGroupMarketingWaitingPoolVO addConcurrently(
            CountDownLatch start,
            String token,
            long operatorId) throws Exception {
        TenantContext.set(7L);
        try {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return groupService.addWaiting(new PullTaskGroupMarketingWaitingPoolAddDTO(
                    token, "并发抢占", null, List.of("120363001@g.us")), operatorId);
        } finally {
            TenantContext.clear();
        }
    }

    private static PullTaskGroupMarketingCandidateQuery query(
            boolean showRegularGroups,
            PullTaskGroupSource source) {
        PullTaskGroupMarketingCandidateQuery query = new PullTaskGroupMarketingCandidateQuery();
        query.setShowRegularGroups(showRegularGroups);
        query.setSource(source);
        return query;
    }

    private static PullTaskGroupMarketingGroupOccupancy occupancy(
            String token,
            long createdBy,
            long now) {
        PullTaskGroupMarketingGroupOccupancy row = new PullTaskGroupMarketingGroupOccupancy();
        row.setGroupLinkId(1001L);
        row.setGroupJid("120363001@g.us");
        row.setGroupSource("HISTORICAL");
        row.setOccupancyType("WAITING");
        row.setReservationToken(token);
        row.setTaskNameSnapshot("印度营销任务");
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** H2 测试别名：判断简单 JSON 字符串数组是否包含指定 JSON 字符串值。 */
    public static boolean jsonContains(String json, String quotedValue) {
        return json != null && quotedValue != null && json.contains(quotedValue);
    }

    /** H2 测试别名：把普通字符串编码为本测试数据可用的 JSON 字符串。 */
    public static String jsonQuote(String value) {
        return value == null ? null : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    /** H2 测试别名：覆盖生产 MySQL 中提取 JID 手机号部分的 SUBSTRING_INDEX。 */
    public static String substringIndex(String value, String delimiter, int count) {
        if (value == null || delimiter == null || delimiter.isEmpty() || count != 1) {
            return value;
        }
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ALIAS IF NOT EXISTS JSON_CONTAINS FOR '"
                    + PullTaskGroupMarketingGroupMapperInMemoryTest.class.getName()
                    + ".jsonContains'");
            statement.execute("CREATE ALIAS IF NOT EXISTS JSON_QUOTE FOR '"
                    + PullTaskGroupMarketingGroupMapperInMemoryTest.class.getName()
                    + ".jsonQuote'");
            statement.execute("CREATE ALIAS IF NOT EXISTS SUBSTRING_INDEX FOR '"
                    + PullTaskGroupMarketingGroupMapperInMemoryTest.class.getName()
                    + ".substringIndex'");
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String schema() {
        return """
                CREATE TABLE account (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32),
                    account_group_id BIGINT, deleted_at BIGINT
                );
                CREATE TABLE account_state (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                    account_state INT, login_state INT, state_source VARCHAR(64)
                );
                CREATE TABLE account_group_baseline (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                    baseline_group_jids VARCHAR(2000) NOT NULL
                );
                CREATE TABLE account_group_membership (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                    group_link_id BIGINT, group_jid VARCHAR(128) NOT NULL, is_admin TINYINT,
                    membership_status INT NOT NULL, joined_at BIGINT, last_seen_at BIGINT,
                    deleted_at BIGINT
                );
                CREATE TABLE group_link (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT,
                    group_name VARCHAR(128), is_historical TINYINT DEFAULT 0,
                    deleted_at BIGINT
                );
                CREATE TABLE group_link_preview (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT,
                    group_jid VARCHAR(128), wa_subject VARCHAR(255), member_size INT,
                    owner_phone VARCHAR(32), announce_only TINYINT, group_created_at BIGINT,
                    avatar_url VARCHAR(512), last_preview_at BIGINT
                );
                CREATE TABLE group_link_health (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT,
                    health_status INT, is_banned TINYINT, current_count INT,
                    last_check_at BIGINT, last_health_error VARCHAR(64)
                );
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL, display_name VARCHAR(128),
                    avatar_url VARCHAR(512), deleted_at BIGINT
                );
                CREATE TABLE wa_group_profile (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                    subject VARCHAR(255), member_count INT, checked_member_count INT,
                    announce_only TINYINT, wa_created_at BIGINT,
                    metadata_observed_at BIGINT, health_status INT, banned TINYINT,
                    last_checked_at BIGINT, last_error_code VARCHAR(64)
                );
                CREATE TABLE wa_group_participant (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                    phone VARCHAR(32), presence_status TINYINT NOT NULL, role TINYINT NOT NULL
                );
                CREATE TABLE wa_account_group_binding (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL, participant_id BIGINT NOT NULL,
                    was_in_initial_baseline TINYINT, last_observed_at BIGINT
                );
                CREATE TABLE join_task (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(128),
                    deleted_at BIGINT
                );
                CREATE TABLE join_task_result (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, join_task_id BIGINT NOT NULL,
                    account_id BIGINT, status VARCHAR(16), group_jid VARCHAR(128), is_admin TINYINT,
                    joined_at BIGINT, promoted_at BIGINT
                );
                CREATE TABLE pull_task_group_marketing_group_occupancy (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT, group_jid VARCHAR(128) NOT NULL,
                    group_source VARCHAR(16) NOT NULL, occupancy_type VARCHAR(16) NOT NULL,
                    reservation_token VARCHAR(64),
                    task_id BIGINT, task_name_snapshot VARCHAR(128), planned_start_at BIGINT,
                    last_validation_reason VARCHAR(255), last_validated_at BIGINT,
                    created_by BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                    expires_at BIGINT, released_at BIGINT,
                    active_key TINYINT GENERATED ALWAYS AS (
                        CASE WHEN released_at IS NULL THEN 1 ELSE NULL END
                    ),
                    CONSTRAINT uq_group_occupancy UNIQUE (tenant_id, group_jid, active_key)
                )
                """;
    }

    private static String fixtures() {
        return """
                INSERT INTO account VALUES
                  (101, 7, '919900000001', 1, NULL),
                  (102, 7, '919900000002', 2, NULL),
                  (103, 7, '919900000003', 3, NULL),
                  (104, 7, '919900000004', 4, NULL),
                  (901, 8, '551100000001', 9, NULL);
                INSERT INTO account_state VALUES
                  (1, 7, 101, 2, 1, 'ONLINE'),
                  (2, 7, 102, 2, 2, 'OFFLINE'),
                  (3, 7, 103, 2, 1, 'ONLINE'),
                  (4, 7, 104, 3, 2, 'BANNED'),
                  (9, 8, 901, 2, 1, 'ONLINE');
                INSERT INTO account_group_baseline VALUES
                  (1, 7, 101, '[\"120363001@g.us\",\"120363002@g.us\"]'),
                  (2, 7, 103, '[\"120363002@g.us\"]'),
                  (9, 8, 901, '[\"120363901@g.us\"]');
                INSERT INTO group_link VALUES
                  (1001, 7, 2001, '运营群一', 1, NULL),
                  (1002, 7, 2002, '普通成员群', 1, NULL),
                  (1003, 7, 2003, '自收群三', 0, NULL),
                  (9001, 8, 2901, '巴西群', 1, NULL);
                INSERT INTO group_link_preview VALUES
                  (1, 7, 1001, '120363001@g.us', '印度群一', 120, '919900000001', 0, 1700000000, NULL, 4000),
                  (2, 7, 1002, '120363002@g.us', '印度群二', 80, '919900000099', 0, 1700000100, NULL, 4000),
                  (3, 7, 1003, '120363003@g.us', '印度群三', 90, '919900000099', 1, 1700000200, NULL, 4000),
                  (9, 8, 9001, '120363901@g.us', '巴西群', 60, '551100000001', 0, 1700000300, NULL, 4000);
                INSERT INTO group_link_health VALUES
                  (1, 7, 1001, 1, 0, 121, 4100, NULL),
                  (2, 7, 1002, 1, 0, 80, 4100, NULL),
                  (3, 7, 1003, 1, 0, 90, 4100, NULL),
                  (9, 8, 9001, 1, 0, 60, 4100, NULL);
                INSERT INTO wa_group VALUES
                  (2001, 7, '120363001@g.us', '运营群一', NULL, NULL),
                  (2002, 7, '120363002@g.us', '普通成员群', NULL, NULL),
                  (2003, 7, '120363003@g.us', '自收群三', NULL, NULL),
                  (2901, 8, '120363901@g.us', '巴西群', NULL, NULL);
                INSERT INTO wa_group_profile VALUES
                  (3001, 7, 2001, '印度群一', 120, 121, 0, 1700000000000,
                   4000, 1, 0, 4100, NULL),
                  (3002, 7, 2002, '印度群二', 80, 80, 0, 1700000100000,
                   4000, 1, 0, 4100, NULL),
                  (3003, 7, 2003, '印度群三', 90, 90, 1, 1700000200000,
                   4000, 1, 0, 4100, NULL),
                  (3901, 8, 2901, '巴西群', 60, 60, 0, 1700000300000,
                   4000, 1, 0, 4100, NULL);
                INSERT INTO wa_group_participant VALUES
                  (4001, 7, 2001, '919900000001', 1, 2),
                  (4002, 7, 2001, '919900000002', 1, 2),
                  (4003, 7, 2002, '919900000003', 1, 1),
                  (4004, 7, 2003, '919900000004', 1, 2),
                  (4005, 7, 2001, '919900000003', 1, 1),
                  (4901, 8, 2901, '551100000001', 1, 2);
                INSERT INTO wa_account_group_binding VALUES
                  (5001, 7, 101, 2001, 4001, 1, 4100),
                  (5002, 7, 102, 2001, 4002, 1, 4050),
                  (5003, 7, 103, 2002, 4003, 1, 4000),
                  (5004, 7, 104, 2003, 4004, 0, 4000),
                  (5005, 7, 103, 2001, 4005, NULL, 4000),
                  (5901, 8, 901, 2901, 4901, 1, 4000);
                INSERT INTO account_group_membership VALUES
                  (1, 7, 101, 1001, '120363001@g.us', 1, 1, 3000, 4100, NULL),
                  (2, 7, 102, 1001, '120363001@g.us', 1, 1, 3100, 4050, NULL),
                  (3, 7, 103, 1002, '120363002@g.us', 0, 1, 3200, 4000, NULL),
                  (4, 7, 104, 1003, '120363003@g.us', 1, 1, 3300, 4000, NULL),
                  (5, 7, 103, 1001, '120363001@g.us', 0, 1, 3350, 4000, NULL),
                  (9, 8, 901, 9001, '120363901@g.us', 1, 1, 3400, 4000, NULL);
                INSERT INTO join_task VALUES
                  (501, 7, '印度自收任务', NULL), (502, 7, 'A干扰任务', NULL),
                  (901, 8, '巴西自收任务', NULL);
                INSERT INTO join_task_result VALUES
                  (1, 7, 501, 102, 'SUCCESS', '120363001@g.us', 1, 3100, 3200),
                  (2, 7, 501, 104, 'SUCCESS', '120363003@g.us', 1, 3300, 3400),
                  (3, 7, 502, 104, 'SUCCESS', '120363003@g.us', 1, 3500, 3600),
                  (9, 8, 901, 901, 'SUCCESS', '120363901@g.us', 1, 3400, 3500)
                """;
    }

    /** 测试专用 MyBatis-Plus 配置，复用生产租户插件并加载真实 Mapper XML。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    @EnableTransactionManagement
    static class TestMyBatisPlusConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:pull_task_group_marketing_group_test"
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDatabaseId("h2");

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(
                    new ClassPathResource("mapper/task/PullTaskGroupMarketingCandidateMapper.xml"),
                    new ClassPathResource("mapper/task/PullTaskGroupMarketingGroupOccupancyMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupMarketingCandidateMapper candidateMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupMarketingCandidateMapper.class);
        }

        @Bean
        PullTaskGroupMarketingGroupOccupancyMapper occupancyMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupMarketingGroupOccupancyMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        CountryService countryService() {
            return Mockito.mock(CountryService.class);
        }

        @Bean
        PullTaskGroupMarketingGroupService groupService(
                PullTaskGroupMarketingCandidateMapper candidateMapper,
                PullTaskGroupMarketingGroupOccupancyMapper occupancyMapper,
                CountryService countryService) {
            return new PullTaskGroupMarketingGroupServiceImpl(
                    candidateMapper, occupancyMapper, countryService);
        }
    }
}
