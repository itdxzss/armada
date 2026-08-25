package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.AccountGroupCompatibilitySnapshot;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.service.AccountGroupMembershipReportService;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.shared.tenant.TenantContext;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 锁定兼容写先提交、当前事实失败可由 Kafka 重放恢复的事务边界。 */
class AccountGroupMembershipReportTransactionBoundaryTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void currentSnapshotLockFailureDoesNotRollbackCommittedCompatibilityAndReplayCompletes() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            AccountGroupMembershipSnapshotService snapshotService =
                    context.getBean(AccountGroupMembershipSnapshotService.class);
            AccountGroupCurrentSnapshotPersistenceImpl persistence =
                    context.getBean(AccountGroupCurrentSnapshotPersistenceImpl.class);
            AccountGroupCurrentSnapshotMapper mapper =
                    context.getBean(AccountGroupCurrentSnapshotMapper.class);
            AccountGroupMembershipReportService service =
                    context.getBean(AccountGroupMembershipReportService.class);
            TransactionTemplate outerTransaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            outerTransaction.setIsolationLevel(
                    TransactionDefinition.ISOLATION_REPEATABLE_READ);
            AccountGroupsReportedEvent event = event();
            AccountGroupMembershipSnapshot group = new AccountGroupMembershipSnapshot(
                    20L, "120363001@g.us", "群一", "wa://group/120363001@g.us", true);
            AtomicInteger compatibilityCommits = new AtomicInteger();
            AtomicInteger currentRollbacks = new AtomicInteger();
            AtomicInteger currentCommits = new AtomicInteger();
            AtomicInteger currentAttempts = new AtomicInteger();

            when(mapper.selectContext(10L)).thenReturn(context());
            when(mapper.selectContextForUpdate(10L)).thenReturn(context());
            when(snapshotService.prepareVisibleGroups(
                    any(), any(), anyBoolean(), anyLong(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                        TransactionSynchronizationManager.registerSynchronization(
                                afterCompletion(compatibilityCommits, null));
                        return new AccountGroupCompatibilitySnapshot(
                                List.of(group), GroupClassificationPlan.empty());
                    });
            when(persistence.replaceVisibleGroups(
                    any(), any(), anyBoolean(), anyLong(), any(), any()))
                    .thenAnswer(invocation -> {
                        assertThat(compatibilityCommits.get())
                                .as("当前事实事务开始前兼容阶段必须已经提交")
                                .isEqualTo(currentAttempts.get() + 1);
                        int attempt = currentAttempts.incrementAndGet();
                        TransactionSynchronizationManager.registerSynchronization(
                                afterCompletion(currentCommits, currentRollbacks));
                        if (attempt == 1) {
                            throw new CannotAcquireLockException("simulated lock timeout");
                        }
                        return new AccountGroupMembershipChangeSet(List.of(group), List.of());
                    });

            assertThatThrownBy(() -> outerTransaction.execute(status -> {
                service.applyGroupsReported(event);
                return null;
            }))
                    .isInstanceOf(CannotAcquireLockException.class);
            assertThat(compatibilityCommits).hasValue(1);
            assertThat(currentRollbacks).hasValue(1);

            assertThatCode(() -> outerTransaction.execute(status -> {
                service.applyGroupsReported(event);
                return null;
            })).doesNotThrowAnyException();
            assertThat(compatibilityCommits).hasValue(2);
            assertThat(currentCommits).hasValue(1);
            assertThat(currentAttempts).hasValue(2);
        }
    }

    @Test
    void newerCompleteWatermarkStopsLateIncompleteBeforeAnyWriteDomain() {
        try (AnnotationConfigApplicationContext spring =
                     new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            AccountGroupMembershipSnapshotService snapshotService =
                    spring.getBean(AccountGroupMembershipSnapshotService.class);
            GroupClassificationService classificationService =
                    spring.getBean(GroupClassificationService.class);
            GroupLinkMapper groupLinkMapper = spring.getBean(GroupLinkMapper.class);
            AccountGroupCurrentSnapshotPersistenceImpl persistence =
                    spring.getBean(AccountGroupCurrentSnapshotPersistenceImpl.class);
            GroupMetadataSyncTaskService metadataTaskService =
                    spring.getBean(GroupMetadataSyncTaskService.class);
            MarketingNewGroupImmediateSendService marketingService =
                    spring.getBean(MarketingNewGroupImmediateSendService.class);
            AccountGroupCurrentSnapshotMapper mapper =
                    spring.getBean(AccountGroupCurrentSnapshotMapper.class);
            AccountGroupMembershipReportService service =
                    spring.getBean(AccountGroupMembershipReportService.class);
            AtomicReference<Context> acceptedContext = new AtomicReference<>(context(null));
            AccountGroupMembershipSnapshot group = new AccountGroupMembershipSnapshot(
                    20L, "120363001@g.us", "群一", "wa://group/120363001@g.us", true);
            GroupClassificationPlan plan = new GroupClassificationPlan(
                    Map.of(20L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED),
                    Map.of(20L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED));
            AccountGroupsReportedEvent oldIncomplete = event(1_000L, false, 1, "old-incomplete");
            AccountGroupsReportedEvent newerComplete = event(2_000L, true, 0, "newer-complete");

            when(mapper.selectContext(10L)).thenAnswer(invocation -> acceptedContext.get());
            when(mapper.selectContextForUpdate(10L)).thenAnswer(invocation -> acceptedContext.get());
            when(snapshotService.prepareVisibleGroups(
                    any(), any(), anyBoolean(), anyLong(), any(), any(), any()))
                    .thenReturn(new AccountGroupCompatibilitySnapshot(List.of(group), plan));
            when(persistence.replaceVisibleGroups(
                    any(), any(), anyBoolean(), anyLong(), any(), any()))
                    .thenAnswer(invocation -> {
                        long syncAt = invocation.getArgument(3);
                        if (syncAt == 1_000L) {
                            throw new CannotAcquireLockException("simulated old phase2 timeout");
                        }
                        acceptedContext.set(context(syncAt));
                        return new AccountGroupMembershipChangeSet(List.of(group), List.of(group));
                    });

            assertThatThrownBy(() -> service.applyGroupsReported(oldIncomplete))
                    .isInstanceOf(CannotAcquireLockException.class);
            assertThatCode(() -> service.applyGroupsReported(newerComplete))
                    .doesNotThrowAnyException();
            assertThat(acceptedContext.get().lastCompleteAt()).isEqualTo(2_000L);

            List<Integer> writesBeforeLateReplay = invocationCounts(
                    snapshotService,
                    classificationService,
                    groupLinkMapper,
                    persistence,
                    metadataTaskService,
                    marketingService);

            assertThatCode(() -> service.applyGroupsReported(oldIncomplete))
                    .doesNotThrowAnyException();

            assertThat(invocationCounts(
                    snapshotService,
                    classificationService,
                    groupLinkMapper,
                    persistence,
                    metadataTaskService,
                    marketingService))
                    .as("late incomplete 不得再改兼容分类、任务、六张当前事实表或营销")
                    .isEqualTo(writesBeforeLateReplay);
        }
    }

    private static TransactionSynchronization afterCompletion(
            AtomicInteger commits,
            AtomicInteger rollbacks) {
        return new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    commits.incrementAndGet();
                } else if (rollbacks != null && status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    rollbacks.incrementAndGet();
                }
            }
        };
    }

    private static AccountGroupsReportedEvent event() {
        return event(2_000L, true, 0, "evt");
    }

    private static AccountGroupsReportedEvent event(
            long reportedAt,
            boolean snapshotComplete,
            int skippedGroupCount,
            String eventId) {
        return new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", reportedAt,
                List.of(new AccountGroupsReportedEvent.Group(
                        "120363001@g.us", "群一", 20, null, null, true, false, null)),
                eventId, "test", snapshotComplete, skippedGroupCount);
    }

    private static Context context() {
        return context(null);
    }

    private static Context context(Long lastCompleteAt) {
        return new Context(10L, "15550000001", "WEB", "acc-10",
                2, 1, 0, 1_000L, null, lastCompleteAt);
    }

    private static List<Integer> invocationCounts(Object... mocks) {
        return java.util.Arrays.stream(mocks)
                .map(mock -> org.mockito.Mockito.mockingDetails(mock).getInvocations().size())
                .toList();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:group-report-boundary;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AccountGroupMembershipSnapshotService snapshotService() {
            return mock(AccountGroupMembershipSnapshotService.class);
        }

        @Bean
        GroupClassificationService classificationService() {
            return mock(GroupClassificationService.class);
        }

        @Bean
        GroupLinkMapper groupLinkMapper() {
            return mock(GroupLinkMapper.class);
        }

        @Bean
        AccountGroupCurrentSnapshotMapper currentSnapshotMapper() {
            return mock(AccountGroupCurrentSnapshotMapper.class);
        }

        @Bean
        AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence() {
            return mock(AccountGroupCurrentSnapshotPersistenceImpl.class);
        }

        @Bean
        MarketingNewGroupImmediateSendService immediateSendService() {
            return mock(MarketingNewGroupImmediateSendService.class);
        }

        @Bean
        GroupMetadataSyncTaskService metadataSyncTaskService() {
            return mock(GroupMetadataSyncTaskService.class);
        }

        @Bean
        AccountGroupMembershipReportPhaseService phaseService(
                AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
                GroupLinkMapper groupLinkMapper,
                AccountGroupMembershipSnapshotService snapshotService,
                GroupClassificationService classificationService,
                AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence,
                MarketingNewGroupImmediateSendService immediateSendService,
                GroupMetadataSyncTaskService metadataSyncTaskService) {
            return new AccountGroupMembershipReportPhaseService(
                    currentSnapshotMapper, groupLinkMapper,
                    snapshotService, classificationService,
                    currentSnapshotPersistence, immediateSendService, metadataSyncTaskService);
        }

        @Bean
        AccountGroupMembershipReportServiceImpl reportService(
                AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
                AccountGroupMembershipReportPhaseService phaseService) {
            return new AccountGroupMembershipReportServiceImpl(currentSnapshotMapper, phaseService);
        }
    }
}
