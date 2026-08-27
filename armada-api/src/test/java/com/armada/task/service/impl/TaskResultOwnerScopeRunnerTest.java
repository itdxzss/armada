package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.PullTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskResultOwnerScopeRunnerTest {

    private final PullTaskMapper pullTaskMapper = mock(PullTaskMapper.class);
    private final JoinTaskMapper joinTaskMapper = mock(JoinTaskMapper.class);
    private final TaskResultOwnerScopeRunner runner =
            new TaskResultOwnerScopeRunner(pullTaskMapper, joinTaskMapper);

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void runsPullTaskCallbackInTrustedOwnerScopeAndRestoresPreviousContexts() {
        PullTask task = new PullTask();
        task.setId(42L);
        task.setOwnerUserId(7L);
        when(pullTaskMapper.selectLifecycle(42L)).thenReturn(task);
        TenantContext.set(99L);

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(88L))) {
            assertThat(runner.runForPullTask(1L, 42L, () -> {
                assertThat(TenantContext.get()).isEqualTo(1L);
                assertThat(DataScopeContext.requireCurrent()).isEqualTo(DataScope.self(7L));
            })).isTrue();

            assertThat(TenantContext.get()).isEqualTo(99L);
            assertThat(DataScopeContext.requireCurrent()).isEqualTo(DataScope.all(88L));
        }
    }

    @Test
    void skipsHistoricalOwnerlessPullTaskWithoutRunningCallback() {
        PullTask task = new PullTask();
        task.setId(42L);
        when(pullTaskMapper.selectLifecycle(42L)).thenReturn(task);
        AtomicBoolean invoked = new AtomicBoolean();

        assertThat(runner.runForPullTask(1L, 42L, () -> invoked.set(true))).isFalse();

        assertThat(invoked).isFalse();
        verify(joinTaskMapper, never()).selectByTenantAndId(42L);
    }

    @Test
    void runsJoinTaskCallbackInTrustedOwnerScope() {
        JoinTask task = new JoinTask();
        task.setId(9L);
        task.setOwnerUserId(17L);
        when(joinTaskMapper.selectByTenantAndId(9L)).thenReturn(task);

        assertThat(runner.runForJoinTask(1L, 9L, () ->
                assertThat(DataScopeContext.requireCurrent()).isEqualTo(DataScope.self(17L))))
                .isTrue();

        assertThat(DataScopeContext.current()).isEmpty();
        assertThat(TenantContext.get()).isNull();
        verify(pullTaskMapper, never()).selectLifecycle(9L);
    }
}
