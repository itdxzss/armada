package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkBillingReservationMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.task.service.HyperlinkBillingConsumptionService;
import com.armada.hyperlink.task.service.HyperlinkBillingSagaService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用真实 Mapper XML 和 H2 MySQL 模式验证计费 Saga 的故障窗口与幂等恢复。 */
@SpringJUnitConfig(HyperlinkBillingSagaH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkBillingSagaH2Test {
    private static final long TENANT_ID = 7L;
    private static final long TASK_ID = 11L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private HyperlinkBillingReservationMapper billingMapper;
    @Autowired
    private HyperlinkTaskRecipientMapper recipientMapper;
    @Autowired
    private HyperlinkTaskMapper taskMapper;

    private RecordingWallet wallet;
    private RecordingAudit audit;
    private HyperlinkBillingSagaService service;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", taskSchema(), billingSchema(), recipientSchema());
        TenantContext.set(TENANT_ID);
        wallet = new RecordingWallet();
        audit = new RecordingAudit();
        service = new HyperlinkBillingSagaService(billingMapper, taskMapper,
                new HyperlinkBillingConsumptionService(recipientMapper, new ObjectMapper()), wallet,
                audit);
        insertTask(9);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void stopFinalizationSettlesUniqueSentRecipientsThenReleasesRemainingAmount() throws SQLException {
        insertReservedBilling(2, 0, null);
        insertRecipient(101, "BR", 3, 1000L);
        insertRecipient(102, "BR", 4, 1001L);
        insertRecipient(103, "BR", 5, 1002L);
        insertRecipient(104, "US", 3, 1003L);
        insertRecipient(105, "BR", 6, null);

        service.finalizeBilling(TASK_ID);
        service.finalizeBilling(TASK_ID);

        BillingRow row = billingRow();
        assertThat(row.status()).isEqualTo(5);
        assertThat(row.pendingOperation()).isZero();
        assertThat(row.settledAmount()).isEqualByComparingTo("9.00000000");
        assertThat(row.settledSendCount()).isEqualTo(4);
        assertThat(row.releasedAmount()).isEqualByComparingTo("13.00000000");
        assertThat(wallet.actions).containsExactly("settle", "release");
        assertThat(wallet.operationKeys).allSatisfy(key -> {
            assertThat(key).hasSizeLessThan(128);
            assertThat(key).startsWith("hl:");
        });
        assertThat(wallet.operationKeys.get(0)).isNotEqualTo(wallet.operationKeys.get(1));
        assertThat(audit.actions()).containsExactly(
                HyperlinkTaskAuditPort.Action.BILLING_SETTLE,
                HyperlinkTaskAuditPort.Action.BILLING_RELEASE);
    }

    @Test
    void releaseFailureKeepsSamePendingOperationKeyAndRetryDoesNotSettleTwice() throws SQLException {
        insertReservedBilling(2, 0, null);
        insertRecipient(101, "BR", 3, 1000L);
        wallet.failNextRelease = true;

        assertThatThrownBy(() -> service.finalizeBilling(TASK_ID))
                .isInstanceOf(BusinessException.class);
        BillingRow failed = billingRow();
        assertThat(failed.status()).isEqualTo(6);
        assertThat(failed.pendingOperation()).isEqualTo(4);
        String failedKey = failed.operationKey();

        service.finalizeBilling(TASK_ID);

        assertThat(billingRow().status()).isEqualTo(5);
        assertThat(wallet.actions).containsExactly("settle", "release", "release");
        assertThat(wallet.operationKeys.get(1)).isEqualTo(failedKey);
        assertThat(wallet.operationKeys.get(2)).isEqualTo(failedKey);
    }

    @Test
    void walletReleaseSuccessBeforeLocalWriteFailureReplaysSameOperationKey() throws SQLException {
        insertReservedBilling(2, 0, null);
        insertRecipient(101, "BR", 3, 1000L);
        HyperlinkBillingReservationMapper flakyMapper = mock(
                HyperlinkBillingReservationMapper.class, delegatesTo(billingMapper));
        AtomicInteger releaseWrites = new AtomicInteger();
        doAnswer(invocation -> {
            if (releaseWrites.getAndIncrement() == 0) { return 0; }
            return billingMapper.markReleased(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
        }).when(flakyMapper).markReleased(anyLong(), anyString(), any(BigDecimal.class), anyLong());
        HyperlinkBillingSagaService recoveringService = new HyperlinkBillingSagaService(
                flakyMapper, taskMapper,
                new HyperlinkBillingConsumptionService(recipientMapper, new ObjectMapper()), wallet,
                audit);

        assertThatThrownBy(() -> recoveringService.finalizeBilling(TASK_ID))
                .isInstanceOf(BusinessException.class);
        String pendingKey = billingRow().operationKey();
        recoveringService.finalizeBilling(TASK_ID);

        assertThat(billingRow().status()).isEqualTo(5);
        assertThat(wallet.actions).containsExactly("settle", "release", "release");
        assertThat(wallet.operationKeys.get(1)).isEqualTo(pendingKey);
        assertThat(wallet.operationKeys.get(2)).isEqualTo(pendingKey);
        assertThat(audit.actions()).containsExactly(
                HyperlinkTaskAuditPort.Action.BILLING_SETTLE,
                HyperlinkTaskAuditPort.Action.BILLING_RELEASE);
    }

    @Test
    void pendingReserveReplaysOriginalKeyAndConvergesToReserved() throws SQLException {
        insertReservedBilling(6, 1, "hl:reserve:fixed-key");
        executeSql("UPDATE hyperlink_billing_reservation SET reserved_amount=0, "
                + "external_reservation_no=NULL WHERE hyperlink_task_id=" + TASK_ID);
        for (int index = 0; index < 10; index++) {
            insertRecipient(200 + index, index < 8 ? "BR" : "US", 1, null);
        }

        assertThat(service.abandonFailedStaleUncalledReservation(TASK_ID)).isFalse();
        service.ensureProvisionReservation(TASK_ID);
        service.ensureProvisionReservation(TASK_ID);

        BillingRow row = billingRow();
        assertThat(row.status()).isEqualTo(2);
        assertThat(row.pendingOperation()).isZero();
        assertThat(row.reservedAmount()).isEqualByComparingTo("22.00000000");
        assertThat(wallet.actions).containsExactly("reserve");
        assertThat(wallet.operationKeys).containsExactly("hl:reserve:fixed-key");
    }

    @Test
    void quoteStaleUncalledReserveCanBeAbandonedWithoutCallingWallet() throws SQLException {
        insertReservedBilling(6, 1, "hl:reserve:stale-key");
        executeSql("UPDATE hyperlink_billing_reservation SET reserved_amount=0, "
                + "settled_amount=0, released_amount=0, settled_send_count=0, "
                + "external_reservation_no=NULL, reserved_at=NULL, settled_at=NULL, "
                + "released_at=NULL, failure_code='40911' "
                + "WHERE hyperlink_task_id=" + TASK_ID);

        assertThat(service.abandonFailedStaleUncalledReservation(TASK_ID)).isTrue();

        BillingRow row = billingRow();
        assertThat(row.status()).isEqualTo(5);
        assertThat(row.pendingOperation()).isZero();
        assertThat(row.reservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.settledAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.releasedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.settledSendCount()).isZero();
        assertThat(wallet.actions).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "reservation_status=2",
            "pending_operation=2",
            "external_reservation_no='wallet-called'",
            "reserved_amount=1",
            "settled_amount=1",
            "released_amount=1",
            "settled_send_count=1",
            "reserved_at=1000",
            "settled_at=1000",
            "released_at=1000"
    })
    void quoteStaleReserveWithAnyRequiredFactMismatchFailsClosed(String mismatchedFact)
            throws SQLException {
        insertReservedBilling(6, 1, "hl:reserve:stale-key");
        executeSql("UPDATE hyperlink_billing_reservation SET reserved_amount=0, "
                + "settled_amount=0, released_amount=0, settled_send_count=0, "
                + "external_reservation_no=NULL, reserved_at=NULL, settled_at=NULL, "
                + "released_at=NULL, failure_code='40911' "
                + "WHERE hyperlink_task_id=" + TASK_ID,
                "UPDATE hyperlink_billing_reservation SET " + mismatchedFact
                + " WHERE hyperlink_task_id=" + TASK_ID);

        assertThatThrownBy(() -> service.abandonFailedStaleUncalledReservation(TASK_ID))
                .isInstanceOf(BusinessException.class);

        BillingRow row = billingRow();
        assertThat(row.status()).isNotEqualTo(5);
        assertThat(row.pendingOperation()).isNotZero();
        assertThat(wallet.actions).isEmpty();
    }

    @Test
    void editRebuildAdjustsExistingReservationWithPersistedNewOperationKey() throws SQLException {
        insertReservedBilling(2, 0, null);
        for (int index = 0; index < 10; index++) {
            insertRecipient(300 + index, index < 8 ? "BR" : "US", 1, null);
        }
        HyperlinkBillingReservation replacement = replacementBilling("hl:adjust:rebuild-key");

        assertThat(billingMapper.resetForAdjustment(replacement, 5)).isEqualTo(1);
        service.ensureProvisionReservation(TASK_ID);

        BillingRow row = billingRow();
        assertThat(row.status()).isEqualTo(2);
        assertThat(row.pendingOperation()).isZero();
        assertThat(row.reservedAmount()).isEqualByComparingTo("24.00000000");
        assertThat(wallet.actions).containsExactly("adjust");
        assertThat(wallet.operationKeys).containsExactly("hl:adjust:rebuild-key");
    }

    @Test
    void unavailableAuditStopsPendingReserveBeforeCallingWallet() throws SQLException {
        insertReservedBilling(6, 1, "hl:reserve:audit-gate");
        executeSql("UPDATE hyperlink_billing_reservation SET reserved_amount=0, "
                + "external_reservation_no=NULL WHERE hyperlink_task_id=" + TASK_ID);
        for (int index = 0; index < 10; index++) {
            insertRecipient(500 + index, index < 8 ? "BR" : "US", 1, null);
        }
        HyperlinkBillingSagaService gatedService = new HyperlinkBillingSagaService(
                billingMapper, taskMapper,
                new HyperlinkBillingConsumptionService(recipientMapper, new ObjectMapper()), wallet,
                new UnavailableHyperlinkTaskAuditPort());

        assertThatThrownBy(() -> gatedService.ensureProvisionReservation(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(com.armada.shared.exception.ErrorCode.HYPERLINK_AUDIT_UNAVAILABLE.code());
        assertThat(wallet.actions).isEmpty();
    }

    @Test
    void recipientBillingQueriesUseUniqueRowsAndExcludeUnsentFailures() throws SQLException {
        insertRecipient(101, "BR", 3, 1000L);
        insertRecipient(102, "BR", 4, 1001L);
        insertRecipient(103, "US", 6, null);

        List<HyperlinkRecipientCountryCount> counts = recipientMapper.selectSentCountryCounts(TASK_ID);

        assertThat(counts).containsExactly(new HyperlinkRecipientCountryCount("BR", 2));
        assertThat(recipientMapper.countSendingByTaskId(TASK_ID)).isZero();
    }

    private void insertTask(int version) throws SQLException {
        executeSql("INSERT INTO hyperlink_task (id, tenant_id, task_name, task_type, start_mode, "
                + "task_delay_minutes, task_interval_minutes, data_package_id, data_package_generation, "
                + "data_package_name_snapshot, target_country_iso2s_snapshot, account_filter, "
                + "max_use_account, concurrent_num, account_max_send_num, account_send_concurrency, "
                + "msg_interval_min_ms, msg_interval_max_ms, is_short_link_enabled, version, created_at, updated_at) "
                + "VALUES (11, 7, 'task', 1, 1, 0, 0, 21, 1, 'pack', '[\"BR\",\"US\"]', '{}', "
                + "1, 1, 0, 1, 500, 700, 0, " + version + ", 1000, 1000)");
    }

    private void insertReservedBilling(int status, int pendingOperation, String operationKey)
            throws SQLException {
        String key = operationKey == null ? "NULL" : "'" + operationKey + "'";
        executeSql("INSERT INTO hyperlink_billing_reservation (id, tenant_id, hyperlink_task_id, "
                + "billing_provider, quote_id, quote_expires_at, price_code, pricing_mode, currency_code, "
                + "unit_price, pricing_breakdown, quoted_recipient_count, quoted_amount, reserved_amount, "
                + "settled_amount, released_amount, settled_send_count, reservation_status, pending_operation, "
                + "operation_idempotency_key, external_reservation_no, version, created_at, updated_at) VALUES "
                + "(1, 7, 11, 'wallet', 'quote', 9999999999999, 'NORMAL', 1, 'USDT', NULL, "
                + "'[{\"recipientCountryIso2\":\"BR\",\"recipientCount\":8,\"unitPrice\":2,\"amount\":16},"
                + "{\"recipientCountryIso2\":\"US\",\"recipientCount\":2,\"unitPrice\":3,\"amount\":6}]', "
                + "10, 22, 22, 0, 0, 0, " + status + ", " + pendingOperation + ", " + key
                + ", 'ext-001', 5, 1000, 1000)");
    }

    private void insertRecipient(long id, String country, int status, Long sentAt) throws SQLException {
        String sent = sentAt == null ? "NULL" : String.valueOf(sentAt);
        executeSql("INSERT INTO hyperlink_task_recipient (id, tenant_id, hyperlink_task_id, "
                + "recipient_country_iso2_snapshot, command_id, send_status, sent_at, created_at, updated_at) "
                + "VALUES (" + id + ", 7, 11, '" + country + "', 'cmd-" + id + "', "
                + status + ", " + sent + ", 1000, 1000)");
    }

    private HyperlinkBillingReservation replacementBilling(String operationKey) {
        HyperlinkBillingReservation billing = new HyperlinkBillingReservation();
        billing.setHyperlinkTaskId(TASK_ID);
        billing.setBillingProvider("wallet");
        billing.setQuoteId("quote-v2");
        billing.setQuoteExpiresAt(9_999_999_999_999L);
        billing.setPriceCode("NORMAL");
        billing.setPricingMode(1);
        billing.setCurrencyCode("USDT");
        billing.setPricingBreakdown("[]");
        billing.setQuotedRecipientCount(10);
        billing.setQuotedAmount(new BigDecimal("24"));
        billing.setOperationIdempotencyKey(operationKey);
        billing.setUpdatedAt(2000L);
        return billing;
    }

    private BillingRow billingRow() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT reservation_status, pending_operation, "
                     + "operation_idempotency_key, reserved_amount, settled_amount, released_amount, "
                     + "settled_send_count FROM hyperlink_billing_reservation WHERE id=1")) {
            assertThat(result.next()).isTrue();
            return new BillingRow(result.getInt(1), result.getInt(2), result.getString(3),
                    result.getBigDecimal(4), result.getBigDecimal(5), result.getBigDecimal(6),
                    result.getLong(7));
        }
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private String taskSchema() {
        return """
                CREATE TABLE hyperlink_task (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, task_name VARCHAR(1024),
                    task_type INT, start_mode INT, task_delay_minutes INT, task_planned_end_at BIGINT,
                    task_interval_minutes INT, data_package_id BIGINT, data_package_generation INT,
                    data_package_name_snapshot VARCHAR(255), target_country_iso2s_snapshot VARCHAR(255),
                    source_template_id BIGINT, source_template_version INT, hyperlink_strategy_id BIGINT,
                    account_filter VARCHAR(2000), max_use_account INT, concurrent_num INT,
                    account_max_send_num INT, account_send_concurrency INT, msg_interval_min_ms INT,
                    msg_interval_max_ms INT, is_short_link_enabled BOOLEAN, version INT,
                    created_by BIGINT, created_at BIGINT, updated_at BIGINT)
                """;
    }

    private String billingSchema() {
        return """
                CREATE TABLE hyperlink_billing_reservation (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL,
                    billing_provider VARCHAR(64) NOT NULL, quote_id VARCHAR(128) NOT NULL,
                    quote_expires_at BIGINT NOT NULL, price_code VARCHAR(64) NOT NULL,
                    pricing_mode INT NOT NULL, currency_code VARCHAR(16) NOT NULL,
                    unit_price DECIMAL(20,8), pricing_breakdown VARCHAR(4000) NOT NULL,
                    quoted_recipient_count INT NOT NULL, quoted_amount DECIMAL(20,8) NOT NULL,
                    reserved_amount DECIMAL(20,8) DEFAULT 0, settled_amount DECIMAL(20,8) DEFAULT 0,
                    released_amount DECIMAL(20,8) DEFAULT 0, settled_send_count BIGINT DEFAULT 0,
                    reservation_status INT NOT NULL, pending_operation INT NOT NULL,
                    operation_idempotency_key VARCHAR(128), next_retry_at BIGINT,
                    external_reservation_no VARCHAR(128), failure_code VARCHAR(64),
                    failure_reason VARCHAR(255), reserved_at BIGINT, settled_at BIGINT, released_at BIGINT,
                    version INT NOT NULL, created_at BIGINT, updated_at BIGINT)
                """;
    }

    private String recipientSchema() {
        return """
                CREATE TABLE hyperlink_task_recipient (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL,
                    recipient_country_iso2_snapshot VARCHAR(2), command_id VARCHAR(128),
                    send_status INT NOT NULL, sent_at BIGINT, created_at BIGINT, updated_at BIGINT)
                """;
    }

    private record BillingRow(int status, int pendingOperation, String operationKey,
                              BigDecimal reservedAmount, BigDecimal settledAmount,
                              BigDecimal releasedAmount, long settledSendCount) { }

    private static final class RecordingWallet implements HyperlinkWalletPort {
        private final List<String> actions = new ArrayList<>();
        private final List<String> operationKeys = new ArrayList<>();
        private boolean failNextRelease;

        @Override
        public PricingSnapshot quote(long tenantId, int maxExecutingAccounts,
                List<HyperlinkRecipientCountryCount> recipientCounts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReserveResult reserve(long tenantId, long taskId, String operationKey,
                String currencyCode, BigDecimal amount) {
            record("reserve", operationKey);
            return new ReserveResult("ext-001", amount);
        }

        @Override
        public AdjustmentResult adjust(long tenantId, long taskId, String operationKey,
                String externalReservationNo, String currencyCode, BigDecimal targetReservedAmount) {
            record("adjust", operationKey);
            return new AdjustmentResult(targetReservedAmount);
        }

        @Override
        public SettlementResult settle(long tenantId, long taskId, String operationKey,
                String externalReservationNo, String currencyCode, BigDecimal targetSettledAmount,
                long targetSettledSendCount) {
            record("settle", operationKey);
            return new SettlementResult(targetSettledAmount, targetSettledSendCount);
        }

        @Override
        public ReleaseResult release(long tenantId, long taskId, String operationKey,
                String externalReservationNo, String currencyCode, BigDecimal targetReleasedAmount) {
            record("release", operationKey);
            if (failNextRelease) {
                failNextRelease = false;
                throw new IllegalStateException("simulated timeout");
            }
            return new ReleaseResult(targetReleasedAmount);
        }

        private void record(String action, String operationKey) {
            actions.add(action);
            operationKeys.add(operationKey);
        }
    }

    private static final class RecordingAudit implements HyperlinkTaskAuditPort {
        private final Map<String, AuditEvent> events = new LinkedHashMap<>();

        @Override
        public void requireAvailable() { }

        @Override
        public void record(AuditEvent event) {
            events.putIfAbsent(event.eventId(), event);
        }

        private List<Action> actions() {
            return events.values().stream().map(AuditEvent::action).toList();
        }
    }

    /** 测试专用 MyBatis-Plus 配置，加载生产租户插件与真实 Mapper XML。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:hyperlink_billing_saga;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(mybatisPlusInterceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkBillingReservationMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkBillingReservationMapper billingMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkBillingReservationMapper.class);
        }

        @Bean
        HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRecipientMapper.class);
        }

        @Bean
        HyperlinkTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskMapper.class);
        }
    }
}
