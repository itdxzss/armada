package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.port.MessageCommandRecoveryPort;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 Mapper、租户插件和事务下验证超时、迟到回执、防重发和并发计数。 */
@SpringJUnitConfig(HyperlinkUnknownTimeoutH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class HyperlinkUnknownTimeoutH2Test {
    private static final long NOW = 1_000_000L;
    @Autowired private DataSource dataSource;
    @Autowired private SqlSessionTemplate template;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private HyperlinkTaskRecipientMapper recipients;
    private HyperlinkTaskAccountUsageMapper usages;
    private HyperlinkTaskRoundAccountMapper roundAccounts;
    private DataPackagePhoneMapper phones;
    private HyperlinkUnknownResultRecoveryService recovery;
    private HyperlinkProtocolResultService results;
    private HyperlinkAccountDispatchGuard guard;
    private MessageCommandRecoveryPort port;
    private DataPackageRecipientClaimService data;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        recipients = template.getMapper(HyperlinkTaskRecipientMapper.class);
        usages = template.getMapper(HyperlinkTaskAccountUsageMapper.class);
        roundAccounts = template.getMapper(HyperlinkTaskRoundAccountMapper.class);
        phones = template.getMapper(DataPackagePhoneMapper.class);
        guard = mock(HyperlinkAccountDispatchGuard.class);
        port = mock(MessageCommandRecoveryPort.class);
        data = mock(DataPackageRecipientClaimService.class);
        var runtimes = mock(com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper.class);
        var stopped = new com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime();
        stopped.setRunStatus(4);
        org.mockito.Mockito.when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(stopped);
        recovery = new HyperlinkUnknownResultRecoveryService(recipients, port, guard, usages,
                mock(HyperlinkMetricsProjectionService.class), runtimes,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        results = new HyperlinkProtocolResultService(recipients, usages, new HyperlinkRecipientStateMachine(),
                data, guard, mock(AccountOperationRestrictionService.class), mock(HyperlinkMetricsProjectionService.class));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
            CREATE TABLE hyperlink_task_recipient (
              id BIGINT PRIMARY KEY, tenant_id BIGINT, hyperlink_task_id BIGINT,
              hyperlink_task_round_id BIGINT, account_id BIGINT, command_id VARCHAR(128),
              protocol_backend INT, protocol_id VARCHAR(32), protocol_message_id VARCHAR(128),
              data_package_id BIGINT, data_package_generation INT, recipient_phone_snapshot VARCHAR(32),
              send_status INT, fail_code VARCHAR(64), fail_reason VARCHAR(255),
              submitted_at BIGINT, sent_at BIGINT, delivered_at BIGINT, read_at BIGINT, failed_at BIGINT,
              next_dispatch_at BIGINT, updated_at BIGINT, created_at BIGINT)
            """);
        jdbc.execute("""
            CREATE TABLE hyperlink_task_account_usage (
              id BIGINT PRIMARY KEY, tenant_id BIGINT, hyperlink_task_id BIGINT, account_id BIGINT,
              usage_status INT, invalid_at BIGINT, success_limit INT, successful_send_count INT,
              reserved_success_slot_count INT, in_flight_count INT, version INT, updated_at BIGINT)
            """);
        jdbc.execute("CREATE TABLE protocol_command_outbox (tenant_id BIGINT, command_id VARCHAR(128), created_at BIGINT)");
        jdbc.execute("""
            CREATE TABLE hyperlink_task_round_account (id BIGINT, tenant_id BIGINT,
              hyperlink_task_round_id BIGINT, task_account_usage_id BIGINT, assignment_status INT)
            """);
        jdbc.execute("""
            CREATE TABLE data_package_phone (id BIGINT, tenant_id BIGINT, data_package_id BIGINT,
              generation INT, phone VARCHAR(32), pool_status INT, claimed_by_hyperlink_task_id BIGINT,
              claimed_at BIGINT, updated_at BIGINT)
            """);
        jdbc.update("""
            INSERT INTO hyperlink_task_recipient VALUES
              (13,7,11,21,17,'hl:7:11:13',2,'android','message-13',23,1,'recipient',2,
               'SEND_RESULT_UNKNOWN','awaiting ack',100, NULL,NULL,NULL,NULL,100,NOW(),100)
            """.replace("NOW()", Long.toString(NOW)));
        jdbc.update("INSERT INTO protocol_command_outbox VALUES (7,'hl:7:11:13',?)", NOW - 120_000L);
        jdbc.update("INSERT INTO hyperlink_task_account_usage VALUES (19,7,11,17,3,?,0,27,2,2,1,100)", NOW - 120_000L);
        jdbc.update("INSERT INTO hyperlink_task_round_account VALUES (31,7,21,19,3)");
        jdbc.update("INSERT INTO data_package_phone VALUES (41,7,23,1,'recipient',2,11,100,100)");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void timeoutReleasesExactlyOneSlotAndLeavesPoolExcludedFromFailedReset() {
        HyperlinkReconciliationCandidate candidate = candidate();
        tx.executeWithoutResult(status -> recovery.recover(candidate));
        tx.executeWithoutResult(status -> recovery.recover(candidate));
        assertThat(recipients.countSendingByTaskId(11L)).isZero();
        assertThat(recipients.selectReconciliationCandidates(NOW, 10)).isEmpty();
        assertThat(recipients.selectByCommandId(candidate.commandId()).getFailCode())
                .isEqualTo(HyperlinkRecipientStatus.BANNED_RESULT_TIMEOUT);
        assertThat(jdbc.queryForMap("SELECT in_flight_count,reserved_success_slot_count,successful_send_count FROM hyperlink_task_account_usage"))
                .containsEntry("in_flight_count", 1).containsEntry("reserved_success_slot_count", 1)
                .containsEntry("successful_send_count", 27);
        Integer resetCount = tx.execute(status -> phones.resetPoolStatus(23L, 1, 5, 1, NOW));
        assertThat(resetCount).isZero();
        assertThat(jdbc.queryForObject("SELECT pool_status FROM data_package_phone", Integer.class)).isEqualTo(2);
        verify(guard, times(1)).releaseAfterCommit(17L, "hl:7:11:13", 11L, 13L);
        verify(port, never()).replay(anyLong(), any(), anyLong());
        verify(data, never()).advanceDeliveryFact(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), anyLong());
    }

    @Test
    void delayedSendSuccessThenDeliveryCorrectsFailureWithoutTouchingOtherInFlightSlot() {
        tx.executeWithoutResult(status -> recovery.recover(candidate()));
        tx.executeWithoutResult(status -> results.handleSendResultReported(success()));
        tx.executeWithoutResult(status -> results.handleSendResultReported(success()));
        tx.executeWithoutResult(status -> results.handleAck(ack("DELIVERED")));
        tx.executeWithoutResult(status -> results.handleAck(ack("DELIVERED")));
        HyperlinkTaskRecipient fact = recipients.selectByCommandId("hl:7:11:13");
        assertThat(fact.getSendStatus()).isEqualTo(4);
        assertThat(fact.getFailCode()).isNull();
        assertThat(fact.getFailedAt()).isNull();
        assertThat(jdbc.queryForMap("SELECT usage_status,in_flight_count,reserved_success_slot_count,successful_send_count FROM hyperlink_task_account_usage"))
                .containsEntry("usage_status", 3).containsEntry("in_flight_count", 1)
                .containsEntry("reserved_success_slot_count", 1).containsEntry("successful_send_count", 28);
        verify(data).advanceDeliveryFact(11L, 23L, 1, "recipient", DataPackagePoolStatus.SENT, NOW + 1);
        verify(data).advanceDeliveryFact(11L, 23L, 1, "recipient", DataPackagePoolStatus.DELIVERED, NOW + 2);
    }

    @Test
    void delayedAckCanCorrectTimeoutButNeverAnOrdinaryFailure() {
        tx.executeWithoutResult(status -> recovery.recover(candidate()));
        tx.executeWithoutResult(status -> results.handleAck(ack("READ")));
        tx.executeWithoutResult(status -> results.handleAck(ack("READ")));
        assertThat(recipients.selectByCommandId("hl:7:11:13").getSendStatus()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT successful_send_count FROM hyperlink_task_account_usage", Integer.class)).isEqualTo(28);
        jdbc.update("UPDATE hyperlink_task_recipient SET send_status=6,fail_code='INVALID_TARGET_JID'");
        tx.executeWithoutResult(status -> results.handleAck(ack("READ")));
        assertThat(recipients.selectByCommandId("hl:7:11:13").getSendStatus()).isEqualTo(6);
    }

    @Test
    void currentCommandTimeDoesNotResetOnPollingOrReuseOldAttemptSubmissionTime() {
        jdbc.update("UPDATE hyperlink_task_account_usage SET usage_status=1,invalid_at=NULL");
        jdbc.update("UPDATE protocol_command_outbox SET created_at=?", NOW - 119_999L);
        // Same command string from another tenant must never affect deadline or duplicate candidates.
        jdbc.update("INSERT INTO protocol_command_outbox VALUES (8,'hl:7:11:13',1)");
        HyperlinkReconciliationCandidate candidate = candidate();
        assertThat(candidate.commandCreatedAt()).isEqualTo(NOW - 119_999L);
        tx.executeWithoutResult(status -> recovery.recover(candidate));
        assertThat(recipients.selectByCommandId("hl:7:11:13").getSendStatus()).isEqualTo(2);
        verify(port).replay(7L, "hl:7:11:13", NOW);
        jdbc.update("UPDATE protocol_command_outbox SET created_at=? WHERE tenant_id=7", NOW - 120_000L);
        jdbc.update("UPDATE hyperlink_task_recipient SET next_dispatch_at=0");
        tx.executeWithoutResult(status -> recovery.recover(candidate()));
        assertThat(recipients.selectByCommandId("hl:7:11:13").getFailCode()).isEqualTo(HyperlinkRecipientStatus.RESULT_TIMEOUT);
    }

    @Test
    void quotaReservedByHealthyInFlightCountsAsExecutingButBannedDoesNot() {
        assertThat(roundAccounts.countExecutingByRoundId(21L)).isZero();
        jdbc.update("UPDATE hyperlink_task_round_account SET assignment_status=1");
        jdbc.update("UPDATE hyperlink_task_account_usage SET usage_status=1,success_limit=29");
        assertThat(roundAccounts.countAvailableByRoundId(21L)).isZero();
        assertThat(roundAccounts.countExecutingByRoundId(21L)).isEqualTo(1);
        TenantContext.set(8L);
        assertThat(roundAccounts.countExecutingByRoundId(21L)).isZero();
    }

    @Test
    void timeoutTransactionRollbackRestoresRecipientAndUsageFacts() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            recovery.recover(candidate());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(recipients.selectByCommandId("hl:7:11:13").getSendStatus()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT in_flight_count FROM hyperlink_task_account_usage", Integer.class)).isEqualTo(2);
    }

    @Test
    void timeoutCorrectionCasRejectsCrossTenantWrites() {
        tx.executeWithoutResult(status -> recovery.recover(candidate()));
        HyperlinkTaskRecipient correction = recipients.selectByCommandId("hl:7:11:13");
        correction.setSendStatus(3);
        correction.setUpdatedAt(NOW + 1);
        TenantContext.set(8L);
        assertThat(recipients.correctTimedOutResult(correction)).isZero();
        assertThat(usages.recordLateSuccess(19L, NOW)).isZero();
    }

    private HyperlinkReconciliationCandidate candidate() {
        var candidates = recipients.selectReconciliationCandidates(NOW, 10);
        assertThat(candidates).hasSize(1);
        return candidates.get(0);
    }

    private ProtocolMessageAckEvent ack(String state) {
        return new ProtocolMessageAckEvent("ack", 7L, "hyperlink_task", 11L, 13L, "hl:7:11:13",
                17L, "android", "account", "recipient", "PRIVATE", "message-13", state,
                true, null, null, NOW + 2, "worker");
    }

    private ProtocolMessageSendResultReportedEvent success() {
        return new ProtocolMessageSendResultReportedEvent("result",7L,null,null,null,null,"account",null,
                "hl:7:11:13",true,"message-13",null,null,NOW+1,"worker",null,null,"hyperlink_task",
                null,null,null,null,null,"recipient","PRIVATE",11L,13L,"SUCCESS",true);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_unknown_timeout;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountUsageMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRoundAccountMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/data/DataPackagePhoneMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
    }
}
