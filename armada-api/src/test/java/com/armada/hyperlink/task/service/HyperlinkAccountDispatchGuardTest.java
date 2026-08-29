package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.shared.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 账号级 holder 的跨任务容量、幂等、TTL 与失败关闭合同。 */
class HyperlinkAccountDispatchGuardTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void differentTaskCommandsShareTheSameAccountCapacityKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L, 0L);
        HyperlinkAccountDispatchGuard guard = new HyperlinkAccountDispatchGuard(redis,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

        assertThat(guard.tryAcquire(51L, "hl:7:11:13")).isTrue();
        assertThat(guard.tryAcquire(51L, "hl:7:12:14")).isFalse();

        verify(redis).execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT),
                eq(List.of("armada:hyperlink:account-guard:{account:51}:holders")),
                eq("1000"), eq("601000"), eq("hl:7:11:13"), eq("20"), eq("600000"));
        verify(redis).execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT),
                eq(List.of("armada:hyperlink:account-guard:{account:51}:holders")),
                eq("1000"), eq("601000"), eq("hl:7:12:14"), eq("20"), eq("600000"));
    }

    @Test
    void sameCommandRenewalUsesTheSameHolderAndRefreshesTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(2_000L, 3_000L);
        HyperlinkAccountDispatchGuard guard = new HyperlinkAccountDispatchGuard(redis, clock);

        guard.renew(51L, "hl:7:11:13");
        guard.renew(51L, "hl:7:11:13");

        verify(redis, times(2)).execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT),
                eq(List.of("armada:hyperlink:account-guard:{account:51}:holders")),
                any(), any(), eq("hl:7:11:13"), eq("20"), eq("600000"));
        assertThat(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT.getScriptAsString())
                .contains("ZREMRANGEBYSCORE", "ZSCORE", "ZCARD", "ZADD", "PEXPIRE")
                .contains("return 0");
    }

    @Test
    void delayedUnknownUsesConsumerClockInsteadOfBackdatingFromEventTime() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        HyperlinkAccountDispatchGuard guard = new HyperlinkAccountDispatchGuard(redis,
                Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC));
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
        recipient.setId(13L);
        recipient.setHyperlinkTaskId(11L);
        recipient.setAccountId(51L);
        recipient.setCommandId("hl:7:11:13");
        recipient.setSendStatus(2);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(recipient);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        when(usages.selectByTaskAndAccountForUpdate(11L, 51L))
                .thenReturn(new com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage());
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13"))
                .thenReturn(recipient);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages,
                new HyperlinkRecipientStateMachine(),
                mock(DataPackageRecipientClaimService.class), guard);

        service.handleSendResultReported(new ProtocolMessageSendResultReportedEvent(
                "late-unknown", 7L, null, null, null, null, "acc51", null,
                "hl:7:11:13", false, null, "MESSAGE_SEND_RESULT_UNKNOWN",
                "result delayed", 1_000L, "worker", null, null,
                "hyperlink_task", null, null, null, null, null,
                "8613800000000@s.whatsapp.net", "PRIVATE", 11L, 13L,
                "UNKNOWN", false));

        verify(redis).execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT),
                eq(List.of("armada:hyperlink:account-guard:{account:51}:holders")),
                eq("1000000"), eq("1600000"), eq("hl:7:11:13"), eq("20"), eq("600000"));
        verify(recipients).scheduleReconciliation("hl:7:11:13", 31_000L, 1_000L);
    }

    @Test
    void holderTtlIsOnlyAnOperationalRenewalWindow() {
        assertThat(HyperlinkAccountDispatchGuard.HOLDER_TTL_MS)
                .isEqualTo(600_000L);
    }

    @Test
    void releaseOnlyRemovesItsOwnHolder() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(HyperlinkAccountDispatchGuard.RELEASE_SCRIPT), anyList(), any()))
                .thenReturn(1L);
        HyperlinkAccountDispatchGuard guard = new HyperlinkAccountDispatchGuard(redis);

        guard.release(51L, "hl:7:11:13");

        verify(redis).execute(eq(HyperlinkAccountDispatchGuard.RELEASE_SCRIPT),
                eq(List.of("armada:hyperlink:account-guard:{account:51}:holders")),
                eq("hl:7:11:13"));
        assertThat(HyperlinkAccountDispatchGuard.RELEASE_SCRIPT.getScriptAsString())
                .contains("ZREM', KEYS[1], ARGV[1]")
                .doesNotContain("ZREMRANGEBYRANK");
    }

    @Test
    void redisExceptionAndMissingResultFailClosedWith50311() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(HyperlinkAccountDispatchGuard.ACQUIRE_SCRIPT), anyList(),
                any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        HyperlinkAccountDispatchGuard guard = new HyperlinkAccountDispatchGuard(redis);

        BusinessException unavailable = catchThrowableOfType(
                () -> guard.tryAcquire(51L, "hl:7:11:13"),
                BusinessException.class);

        assertThat(unavailable.getCode()).isEqualTo(50311);

        StringRedisTemplate missing = mock(StringRedisTemplate.class);
        BusinessException missingResult = catchThrowableOfType(
                () -> new HyperlinkAccountDispatchGuard(missing)
                        .tryAcquire(51L, "hl:7:11:13"),
                BusinessException.class);
        assertThat(missingResult.getCode()).isEqualTo(50311);
    }

    @Test
    void terminalHolderIsReleasedOnlyAfterDatabaseCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(HyperlinkAccountDispatchGuard.RELEASE_SCRIPT), anyList(), any()))
                .thenReturn(1L);
        HyperlinkAccountDispatchGuard guard = new HyperlinkAccountDispatchGuard(redis);
        TransactionSynchronizationManager.initSynchronization();

        guard.releaseAfterCommit(51L, "hl:7:11:13", 11L, 13L);
        verify(redis, times(0)).execute(eq(HyperlinkAccountDispatchGuard.RELEASE_SCRIPT),
                anyList(), any());
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(redis).execute(eq(HyperlinkAccountDispatchGuard.RELEASE_SCRIPT),
                eq(List.of("armada:hyperlink:account-guard:{account:51}:holders")),
                eq("hl:7:11:13"));
    }

    @Test
    void terminalHolderStaysWhenDatabaseRollsBack() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HyperlinkAccountDispatchGuard guard = new HyperlinkAccountDispatchGuard(redis);
        TransactionSynchronizationManager.initSynchronization();

        guard.releaseAfterCommit(51L, "hl:7:11:13", 11L, 13L);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(redis, times(0)).execute(eq(HyperlinkAccountDispatchGuard.RELEASE_SCRIPT),
                anyList(), any());
    }
}
