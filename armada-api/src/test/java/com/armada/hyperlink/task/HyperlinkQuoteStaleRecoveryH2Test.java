package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.data.mapper.DataPackageMapper;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.mapper.DataPackageStatMapper;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.data.service.impl.DataPackageRecipientClaimServiceImpl;
import com.armada.hyperlink.task.mapper.HyperlinkBillingReservationMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskActionDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskQuoteDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.task.service.HyperlinkBillingConsumptionService;
import com.armada.hyperlink.task.service.HyperlinkBillingSagaService;
import com.armada.hyperlink.task.service.HyperlinkCleanupStartService;
import com.armada.hyperlink.task.service.HyperlinkFirstRoundService;
import com.armada.hyperlink.task.service.HyperlinkOwnedRecipientQuoteService;
import com.armada.hyperlink.task.service.HyperlinkProvisionFactService;
import com.armada.hyperlink.task.service.HyperlinkProvisioningService;
import com.armada.hyperlink.task.service.HyperlinkQuoteTokenService;
import com.armada.hyperlink.task.service.HyperlinkRecipientClaimService;
import com.armada.hyperlink.task.service.HyperlinkRoundAccountSelectionService;
import com.armada.hyperlink.task.service.HyperlinkShortLinkGuard;
import com.armada.hyperlink.task.service.HyperlinkTaskActionService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteGuardService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteService;
import com.armada.hyperlink.task.service.HyperlinkTaskStoreService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实 H2/MyBatis SQL 验证领取人数变小后的重新报价恢复不释放、不重领。 */
@SpringJUnitConfig(HyperlinkQuoteStaleRecoveryH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkQuoteStaleRecoveryH2Test {
    private static final long TENANT_ID = 7L;
    private static final long TASK_ID = 11L;
    private static final long COMPETING_TASK_ID = 12L;
    private static final long DATA_PACKAGE_ID = 21L;

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataPackageRecipientClaimService dataPackageService;
    @Autowired private HyperlinkTaskMapper taskMapper;
    @Autowired private HyperlinkTaskRuntimeMapper runtimeMapper;
    @Autowired private HyperlinkTaskRecipientClaimMapper claimMapper;
    @Autowired private HyperlinkBillingReservationMapper billingMapper;
    @Autowired private HyperlinkTaskRecipientMapper recipientMapper;
    @Autowired private HyperlinkTaskRoundMapper roundMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecordingWallet wallet;
    private HyperlinkTaskQuoteService quoteService;
    private HyperlinkOwnedRecipientQuoteService ownedQuoteService;
    private HyperlinkProvisioningService provisioningService;
    private HyperlinkCleanupStartService cleanupStartService;
    private HyperlinkTaskQuoteGuardService quoteGuard;
    private HyperlinkProvisionFactService provisionFacts;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
        wallet = new RecordingWallet();
        HyperlinkQuoteTokenService tokenService = new HyperlinkQuoteTokenService(objectMapper,
                "quote-recovery-test-signing-key-1234567890");
        ownedQuoteService = new HyperlinkOwnedRecipientQuoteService(runtimeMapper, claimMapper,
                billingMapper, recipientMapper);
        quoteService = new HyperlinkTaskQuoteService(dataPackageService, taskMapper,
                wallet, tokenService, ownedQuoteService);
        quoteGuard = new HyperlinkTaskQuoteGuardService(
                quoteService, tokenService);
        provisionFacts = new HyperlinkProvisionFactService(
                claimMapper, billingMapper, recipientMapper, dataPackageService, objectMapper);
        HyperlinkTaskAuditPort audit = new RecordingAudit();
        cleanupStartService = mock(HyperlinkCleanupStartService.class);
        HyperlinkRecipientClaimService recipientClaims = new HyperlinkRecipientClaimService(
                claimMapper, recipientMapper, dataPackageService);
        HyperlinkBillingSagaService billingSaga = new HyperlinkBillingSagaService(
                billingMapper, taskMapper,
                new HyperlinkBillingConsumptionService(recipientMapper, objectMapper),
                wallet, audit);
        HyperlinkRoundAccountSelectionService accountSelection =
                mock(HyperlinkRoundAccountSelectionService.class);
        when(accountSelection.select(any(), any(), anyLong())).thenReturn(1);
        HyperlinkFirstRoundService firstRound = new HyperlinkFirstRoundService(taskMapper,
                runtimeMapper, recipientMapper, billingMapper, roundMapper, accountSelection);
        provisioningService = new HyperlinkProvisioningService(
                recipientClaims, billingSaga, firstRound, runtimeMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void competingClaimRequotesOwnedNinetyAndResumesWithoutReleaseOrSecondClaim() throws Exception {
        prepareQuoteStaleFailure();

        HyperlinkTaskRuntime failed = runtimeMapper.selectByTaskId(TASK_ID);
        HyperlinkTaskRecipientClaim owned = claimMapper.selectSnapshotByTaskId(TENANT_ID, TASK_ID);
        HyperlinkBillingReservation staleBilling = billingMapper.selectByTaskId(TASK_ID);
        assertThat(failed.getEnabled()).isTrue();
        assertThat(failed.getRunStatus()).isEqualTo(0);
        assertThat(failed.getProvisionStatus()).isEqualTo(HyperlinkProvisionStatus.FAILED.code());
        assertThat(failed.getFailureCode()).isEqualTo(ErrorCode.HYPERLINK_QUOTE_STALE.code());
        assertThat(owned.getClaimStatus()).isEqualTo(3);
        assertThat(owned.getClaimedPhoneCount()).isEqualTo(90);
        assertThat(staleBilling.getQuotedRecipientCount()).isEqualTo(100);
        assertThat(staleBilling.getFailureCode())
                .isEqualTo(Integer.toString(ErrorCode.HYPERLINK_QUOTE_STALE.code()));
        assertThat(staleBilling.getExternalReservationNo()).isNull();
        assertThat(staleBilling.getReservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        int claimVersionBeforeRecovery = owned.getVersion();
        String staleOperationKey = staleBilling.getOperationIdempotencyKey();
        assertThat(ownedQuoteService.snapshot(taskMapper.selectById(TASK_ID)))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.recipientCount()).isEqualTo(90));

        HyperlinkTaskQuoteVO recoveryQuote = quoteService.quote(
                new HyperlinkTaskQuoteDTO("START", TASK_ID, null, null, null), principal());
        assertThat(recoveryQuote.recipientCount()).isEqualTo(90);
        assertThat(recoveryQuote.estimatedAmount()).isEqualByComparingTo("90");
        assertThat(recoveryQuote.pricingBreakdown())
                .extracting(row -> row.recipientCountryIso2() + ":" + row.recipientCount())
                .containsExactly("BR:40", "US:50");
        assertCompetingStartsOnlyOneWins(recoveryQuote.quoteToken());

        HyperlinkBillingReservation replaced = billingMapper.selectByTaskId(TASK_ID);
        assertThat(replaced.getQuotedRecipientCount()).isEqualTo(90);
        assertThat(replaced.getQuotedAmount()).isEqualByComparingTo("90");
        assertThat(replaced.getPendingOperation()).isEqualTo(1);
        assertThat(replaced.getExternalReservationNo()).isNull();
        assertThat(replaced.getOperationIdempotencyKey())
                .startsWith("hl:reserve:")
                .isNotEqualTo(staleOperationKey);
        assertThat(replaced.getVersion()).isEqualTo(staleBilling.getVersion() + 1);
        assertThat(taskMapper.selectById(TASK_ID).getVersion()).isEqualTo(2);
        assertThat(claimMapper.selectSnapshotByTaskId(TENANT_ID, TASK_ID).getVersion())
                .isEqualTo(claimVersionBeforeRecovery);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM hyperlink_task_recipient_claim "
                + "WHERE hyperlink_task_id=?", Integer.class, TASK_ID)).isEqualTo(1);
        verify(cleanupStartService, never()).begin(anyLong(), anyBoolean(), anyLong());

        inTransaction(() -> provisioningService.advance(TASK_ID));

        HyperlinkTaskRuntime ready = runtimeMapper.selectByTaskId(TASK_ID);
        HyperlinkBillingReservation reserved = billingMapper.selectByTaskId(TASK_ID);
        assertThat(ready.getProvisionStatus()).isEqualTo(HyperlinkProvisionStatus.READY.code());
        assertThat(ready.getRecipientTotal()).isEqualTo(90);
        assertThat(reserved.getReservationStatus()).isEqualTo(2);
        assertThat(reserved.getReservedAmount()).isEqualByComparingTo("90");
        assertThat(reserved.getExternalReservationNo()).isEqualTo("wallet-reservation-1");
        assertThat(wallet.reserveAmounts).hasSize(1);
        assertThat(wallet.reserveAmounts.get(0)).isEqualByComparingTo("90");
        assertThat(recipientMapper.countByTaskId(TASK_ID)).isEqualTo(90);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM data_package_phone "
                + "WHERE claimed_by_hyperlink_task_id=?", Integer.class, TASK_ID)).isEqualTo(90);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM data_package_phone "
                + "WHERE claimed_by_hyperlink_task_id=?", Integer.class, COMPETING_TASK_ID)).isEqualTo(10);
        assertThat(jdbc().queryForObject("SELECT claimed_count FROM data_package_stat "
                + "WHERE data_package_id=?", Integer.class, DATA_PACKAGE_ID)).isEqualTo(100);
    }

    @Test
    void quoteStaleRecoveryStartFailsClosedWhenWalletWasCalledAfterQuote() {
        prepareQuoteStaleFailure();
        HyperlinkTaskQuoteVO recoveryQuote = quoteService.quote(
                new HyperlinkTaskQuoteDTO("START", TASK_ID, null, null, null), principal());
        HyperlinkTaskStoreService store = new HyperlinkTaskStoreService(taskMapper,
                mock(HyperlinkTaskContentMapper.class), runtimeMapper);
        HyperlinkTaskActionService service = new HyperlinkTaskActionService(store,
                quoteGuard, provisionFacts, roundMapper, cleanupStartService,
                new RecordingAudit(), new HyperlinkShortLinkGuard(""),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));
        assertThat(jdbc().update("UPDATE hyperlink_billing_reservation "
                + "SET external_reservation_no='wallet-existing',reserved_amount=90 "
                + "WHERE hyperlink_task_id=?", TASK_ID)).isEqualTo(1);

        assertThatThrownBy(() -> inTransaction(() -> service.action(TASK_ID,
                new HyperlinkTaskActionDTO(HyperlinkTaskAction.START, 1,
                        recoveryQuote.quoteToken()), principal())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT.code()));
        assertThat(taskMapper.selectById(TASK_ID).getVersion()).isEqualTo(1);
        assertThat(runtimeMapper.selectByTaskId(TASK_ID).getProvisionStatus())
                .isEqualTo(HyperlinkProvisionStatus.FAILED.code());
        assertThat(wallet.reserveAmounts).isEmpty();
    }

    private void prepareQuoteStaleFailure() {
        HyperlinkTaskQuoteVO initialQuote = quoteService.quote(
                new HyperlinkTaskQuoteDTO("START", TASK_ID, null, null, null), principal());
        assertThat(initialQuote.recipientCount()).isEqualTo(100);
        HyperlinkQuoteTokenService.QuoteClaims initialClaims = claims(initialQuote.quoteToken(), 1);
        inTransaction(() -> provisionFacts.prepare(
                taskMapper.selectById(TASK_ID), initialClaims, 1_000L));
        assertThat(dataPackageService.claimBatch(COMPETING_TASK_ID, DATA_PACKAGE_ID,
                1, 0, 100, 10, 1_100L)).hasSize(10);
        inTransaction(() -> provisioningService.advance(TASK_ID));
        inTransaction(() -> provisioningService.advance(TASK_ID));
    }

    private void assertCompetingStartsOnlyOneWins(String quoteToken) throws Exception {
        CountDownLatch atVersionCas = new CountDownLatch(2);
        CountDownLatch releaseVersionCas = new CountDownLatch(1);
        HyperlinkTaskStoreService barrierStore = new HyperlinkTaskStoreService(taskMapper,
                mock(HyperlinkTaskContentMapper.class), runtimeMapper) {
            @Override
            public HyperlinkTaskContent requireContent(long taskId) {
                HyperlinkTaskContent content = new HyperlinkTaskContent();
                content.setMessageType(1);
                return content;
            }

            @Override
            public void incrementVersion(long taskId, int expectedVersion, long now) {
                atVersionCas.countDown();
                try {
                    if (!releaseVersionCas.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("两个 START 未同时到达版本 CAS");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 START 版本 CAS 被中断", exception);
                }
                super.incrementVersion(taskId, expectedVersion, now);
            }
        };
        HyperlinkTaskActionService competingAction = new HyperlinkTaskActionService(barrierStore,
                quoteGuard, provisionFacts, roundMapper, cleanupStartService,
                new RecordingAudit(), new HyperlinkShortLinkGuard(""),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> startOnce(competingAction, quoteToken));
            Future<Boolean> second = executor.submit(() -> startOnce(competingAction, quoteToken));
            assertThat(atVersionCas.await(5, TimeUnit.SECONDS)).isTrue();
            releaseVersionCas.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            releaseVersionCas.countDown();
            executor.shutdownNow();
        }
    }

    private boolean startOnce(HyperlinkTaskActionService service, String quoteToken) {
        TenantContext.set(TENANT_ID);
        try {
            Boolean success = new TransactionTemplate(transactionManager).execute(status -> {
                service.action(TASK_ID, new HyperlinkTaskActionDTO(HyperlinkTaskAction.START,
                        1, quoteToken), principal());
                return true;
            });
            return Boolean.TRUE.equals(success);
        } catch (BusinessException exception) {
            assertThat(exception.getCode()).isEqualTo(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT.code());
            return false;
        } finally {
            TenantContext.clear();
        }
    }

    private HyperlinkQuoteTokenService.QuoteClaims claims(String token, int version) {
        return new HyperlinkQuoteTokenService(objectMapper,
                "quote-recovery-test-signing-key-1234567890")
                .verify(token, TENANT_ID, 8L, "START", TASK_ID, version,
                        System.currentTimeMillis());
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(8L, TENANT_ID, "u", "U", "t", "T", List.of(), List.of());
    }

    private void insertFixtures() throws SQLException {
        execute("INSERT INTO data_package "
                + "(id,tenant_id,package_name,current_generation,phone_count,version,created_at,updated_at) "
                + "VALUES (21,7,'pool',1,100,1,1000,1000)");
        execute("INSERT INTO data_package_stat VALUES (21,7,1,100,0,0,0,0,0,1000,NULL)");
        insertPhones();
        execute(taskInsert(TASK_ID, "recover"), taskInsert(COMPETING_TASK_ID, "competitor"));
        execute("INSERT INTO hyperlink_task_runtime "
                + "(hyperlink_task_id,tenant_id,is_enabled,run_status,provision_status,created_at,updated_at) "
                + "VALUES (11,7,1,0,1,1000,1000)");
    }

    private String taskInsert(long taskId, String name) {
        return "INSERT INTO hyperlink_task (id,tenant_id,task_name,task_type,start_mode,"
                + "task_delay_minutes,task_interval_minutes,data_package_id,data_package_generation,"
                + "data_package_name_snapshot,target_country_iso2s_snapshot,account_filter,"
                + "max_use_account,concurrent_num,account_max_send_num,account_send_concurrency,"
                + "msg_interval_min_ms,msg_interval_max_ms,is_short_link_enabled,version,created_by,"
                + "created_at,updated_at) VALUES (" + taskId + ",7,'" + name
                + "',1,1,0,0,21,1,'pool','[\"BR\",\"US\"]','{}',1,1,0,20,500,700,0,1,8,1000,1000)";
    }

    private void insertPhones() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO data_package_phone "
                     + "(id,tenant_id,data_package_id,generation,source_import_id,phone,country_iso2,"
                     + "pool_status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (int index = 1; index <= 100; index++) {
                statement.setLong(1, index);
                statement.setLong(2, TENANT_ID);
                statement.setLong(3, DATA_PACKAGE_ID);
                statement.setInt(4, 1);
                statement.setLong(5, 31L);
                statement.setString(6, "550000" + index);
                statement.setString(7, index <= 50 ? "BR" : "US");
                statement.setInt(8, 1);
                statement.setLong(9, 1000L);
                statement.setLong(10, 1000L);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void createSchema() throws SQLException {
        execute(dataPackageSchema(), dataPackagePhoneSchema(), dataPackageStatSchema(),
                taskSchema(), runtimeSchema(), claimSchema(), billingSchema(), recipientSchema(),
                roundSchema());
    }

    private String dataPackageSchema() {
        return "CREATE TABLE data_package (id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,"
                + "package_name VARCHAR(128),remark VARCHAR(500),current_generation INT,phone_count INT,"
                + "version INT,created_by BIGINT,created_at BIGINT,updated_at BIGINT,deleted_by BIGINT,"
                + "deleted_at BIGINT)";
    }

    private String dataPackagePhoneSchema() {
        return "CREATE TABLE data_package_phone (id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,"
                + "data_package_id BIGINT,generation INT,source_import_id BIGINT,phone VARCHAR(32),"
                + "country_iso2 CHAR(2),pool_status INT,claimed_by_hyperlink_task_id BIGINT,"
                + "claimed_at BIGINT,created_at BIGINT,updated_at BIGINT,"
                + "UNIQUE(tenant_id,data_package_id,generation,phone))";
    }

    private String dataPackageStatSchema() {
        return "CREATE TABLE data_package_stat (data_package_id BIGINT PRIMARY KEY,"
                + "tenant_id BIGINT NOT NULL,generation INT,unused_count INT,claimed_count INT,"
                + "sent_count INT,delivered_count INT,retryable_failed_count INT,"
                + "unregistered_count INT,updated_at BIGINT,reconciled_at BIGINT)";
    }

    private String taskSchema() {
        return "CREATE TABLE hyperlink_task (id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,"
                + "task_name VARCHAR(1024),task_type INT,start_mode INT,task_delay_minutes INT,"
                + "task_planned_end_at BIGINT,task_interval_minutes INT,data_package_id BIGINT,"
                + "data_package_generation INT,data_package_name_snapshot VARCHAR(255),"
                + "target_country_iso2s_snapshot VARCHAR(255),source_template_id BIGINT,"
                + "source_template_version INT,hyperlink_strategy_id BIGINT,account_filter VARCHAR(2000),"
                + "max_use_account INT,concurrent_num INT,account_max_send_num INT,"
                + "account_send_concurrency INT,msg_interval_min_ms INT,msg_interval_max_ms INT,"
                + "is_short_link_enabled BOOLEAN,version INT,created_by BIGINT,created_at BIGINT,updated_at BIGINT)";
    }

    private String runtimeSchema() {
        return "CREATE TABLE hyperlink_task_runtime (hyperlink_task_id BIGINT PRIMARY KEY,"
                + "tenant_id BIGINT NOT NULL,is_enabled BOOLEAN,run_status INT,provision_status INT,"
                + "current_round_id BIGINT,current_round_no BIGINT,started_at BIGINT,last_send_at BIGINT,"
                + "finished_at BIGINT,recipient_total INT DEFAULT 0,send_total BIGINT DEFAULT 0,"
                + "success_num BIGINT DEFAULT 0,delivered_num BIGINT DEFAULT 0,read_num BIGINT DEFAULT 0,"
                + "fail_num BIGINT DEFAULT 0,fail_404_num BIGINT DEFAULT 0,invalid_account_count INT DEFAULT 0,"
                + "used_account_count INT DEFAULT 0,actual_concurrency INT DEFAULT 0,"
                + "execution_duration_sec BIGINT DEFAULT 0,active_since_at BIGINT,metrics_updated_at BIGINT,"
                + "failure_code INT,failure_reason VARCHAR(255),created_at BIGINT,updated_at BIGINT)";
    }

    private String claimSchema() {
        return "CREATE TABLE hyperlink_task_recipient_claim (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + "tenant_id BIGINT NOT NULL,hyperlink_task_id BIGINT,data_package_id BIGINT,"
                + "data_package_generation INT,claim_upper_phone_id BIGINT,scan_cursor_phone_id BIGINT,"
                + "quoted_phone_count INT,claimed_phone_count INT,claim_status INT,lease_owner VARCHAR(64),"
                + "lease_expires_at BIGINT,failure_code VARCHAR(64),failure_reason VARCHAR(255),version INT,"
                + "started_at BIGINT,finished_at BIGINT,created_at BIGINT,updated_at BIGINT)";
    }

    private String billingSchema() {
        return "CREATE TABLE hyperlink_billing_reservation (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + "tenant_id BIGINT NOT NULL,hyperlink_task_id BIGINT,billing_provider VARCHAR(64),"
                + "quote_id VARCHAR(128),quote_expires_at BIGINT,price_code VARCHAR(64),pricing_mode INT,"
                + "currency_code VARCHAR(16),unit_price DECIMAL(20,8),pricing_breakdown VARCHAR(4000),"
                + "quoted_recipient_count INT,quoted_amount DECIMAL(20,8),reserved_amount DECIMAL(20,8) DEFAULT 0,"
                + "settled_amount DECIMAL(20,8) DEFAULT 0,released_amount DECIMAL(20,8) DEFAULT 0,"
                + "settled_send_count BIGINT DEFAULT 0,reservation_status INT,pending_operation INT,"
                + "operation_idempotency_key VARCHAR(128),next_retry_at BIGINT,external_reservation_no VARCHAR(128),"
                + "failure_code VARCHAR(64),failure_reason VARCHAR(255),reserved_at BIGINT,settled_at BIGINT,"
                + "released_at BIGINT,version INT,created_at BIGINT,updated_at BIGINT)";
    }

    private String recipientSchema() {
        return "CREATE TABLE hyperlink_task_recipient (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + "tenant_id BIGINT NOT NULL,hyperlink_task_id BIGINT,data_package_id BIGINT,"
                + "data_package_generation INT,source_import_id BIGINT,recipient_phone_snapshot VARCHAR(32),"
                + "recipient_country_iso2_snapshot CHAR(2),hyperlink_task_round_id BIGINT,round_no BIGINT,"
                + "account_id BIGINT,sender_phone_snapshot VARCHAR(32),sender_country_iso2_snapshot CHAR(2),"
                + "sender_account_type_snapshot INT,protocol_id VARCHAR(64),protocol_backend INT,"
                + "protocol_message_id VARCHAR(128),command_id VARCHAR(128),short_code VARCHAR(32),"
                + "send_status INT DEFAULT 1,next_dispatch_at BIGINT DEFAULT 0,submitted_at BIGINT,sent_at BIGINT,"
                + "delivered_at BIGINT,read_at BIGINT,failed_at BIGINT,fail_code VARCHAR(64),fail_reason VARCHAR(255),"
                + "metrics_projected_status INT DEFAULT 1,needs_metrics_projection INT DEFAULT 0,"
                + "click_count INT DEFAULT 0,created_at BIGINT,updated_at BIGINT,"
                + "UNIQUE(tenant_id,hyperlink_task_id,recipient_phone_snapshot))";
    }

    private String roundSchema() {
        return "CREATE TABLE hyperlink_task_round (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + "tenant_id BIGINT NOT NULL,hyperlink_task_id BIGINT,round_no BIGINT,round_status INT,"
                + "scheduled_at BIGINT,next_dispatch_at BIGINT,assigned_recipient_count INT,"
                + "selected_account_count INT,actual_concurrency INT,send_total BIGINT DEFAULT 0,"
                + "success_num BIGINT DEFAULT 0,delivered_num BIGINT DEFAULT 0,read_num BIGINT DEFAULT 0,"
                + "fail_num BIGINT DEFAULT 0,fail_404_num BIGINT DEFAULT 0,last_send_at BIGINT,"
                + "started_at BIGINT,dispatch_completed_at BIGINT,finished_at BIGINT,version INT,"
                + "created_at BIGINT,updated_at BIGINT)";
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static final class RecordingWallet implements HyperlinkWalletPort {
        private final List<BigDecimal> reserveAmounts = new ArrayList<>();

        @Override
        public PricingSnapshot quote(long tenantId, int maxExecutingAccounts,
                List<HyperlinkRecipientCountryCount> counts) {
            List<CountryPrice> breakdown = counts.stream()
                    .map(row -> new CountryPrice(row.countryIso2(), row.recipientCount(),
                            BigDecimal.ONE, BigDecimal.valueOf(row.recipientCount())))
                    .toList();
            BigDecimal amount = BigDecimal.valueOf(counts.stream()
                    .mapToInt(HyperlinkRecipientCountryCount::recipientCount).sum());
            return new PricingSnapshot("wallet", "NORMAL", "unit", "USDT", BigDecimal.ONE,
                    breakdown, amount, new BigDecimal("1000"), BigDecimal.ZERO);
        }

        @Override
        public ReserveResult reserve(long tenantId, long taskId, String operationKey,
                String currencyCode, BigDecimal amount) {
            reserveAmounts.add(amount);
            return new ReserveResult("wallet-reservation-1", amount);
        }

        @Override public AdjustmentResult adjust(long tenantId, long taskId, String operationKey,
                String externalReservationNo, String currencyCode, BigDecimal amount) {
            throw new AssertionError("恢复不得调整旧钱包预约");
        }
        @Override public SettlementResult settle(long tenantId, long taskId, String operationKey,
                String externalReservationNo, String currencyCode, BigDecimal amount, long count) {
            throw new AssertionError("恢复不得结算");
        }
        @Override public ReleaseResult release(long tenantId, long taskId, String operationKey,
                String externalReservationNo, String currencyCode, BigDecimal amount) {
            throw new AssertionError("恢复不得释放");
        }
    }

    private static final class RecordingAudit implements HyperlinkTaskAuditPort {
        @Override public void requireAvailable() { }
        @Override public void record(AuditEvent event) { }
    }

    /** 只加载本恢复链实际执行的生产 Mapper XML 与租户插件。 */
    @Configuration
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_quote_recovery;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/hyperlink/data/DataPackageMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/data/DataPackagePhoneMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/data/DataPackageStatMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRuntimeMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientClaimMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkBillingReservationMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRoundMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean DataPackageMapper dataPackageMapper(SqlSessionTemplate value) {
            return value.getMapper(DataPackageMapper.class);
        }
        @Bean DataPackagePhoneMapper dataPackagePhoneMapper(SqlSessionTemplate value) {
            return value.getMapper(DataPackagePhoneMapper.class);
        }
        @Bean DataPackageStatMapper dataPackageStatMapper(SqlSessionTemplate value) {
            return value.getMapper(DataPackageStatMapper.class);
        }
        @Bean HyperlinkTaskMapper taskMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskMapper.class);
        }
        @Bean HyperlinkTaskRuntimeMapper runtimeMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRuntimeMapper.class);
        }
        @Bean HyperlinkTaskRecipientClaimMapper claimMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRecipientClaimMapper.class);
        }
        @Bean HyperlinkBillingReservationMapper billingMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkBillingReservationMapper.class);
        }
        @Bean HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRecipientMapper.class);
        }
        @Bean HyperlinkTaskRoundMapper roundMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRoundMapper.class);
        }

        @Bean
        DataPackageRecipientClaimService dataPackageService(DataPackageMapper packages,
                DataPackagePhoneMapper phones, DataPackageStatMapper stats) {
            return new DataPackageRecipientClaimServiceImpl(packages, phones, stats);
        }
    }
}
