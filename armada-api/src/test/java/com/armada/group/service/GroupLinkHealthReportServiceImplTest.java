package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.service.impl.GroupLinkHealthReportServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 群链接健康检测回报服务单测。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkHealthReportServiceImplTest {

    @Mock
    private GroupLinkHealthMapper healthMapper;

    private GroupLinkHealthReportServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        service = new GroupLinkHealthReportServiceImpl(healthMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void applyHealthReported_healthyUpdatesAvailableAndRestoresTenantContext() {
        GroupLinkHealth current = currentHealth(44, 3);
        List<Long> tenantContextDuringMapperCalls = captureTenantContext(current);

        service.applyHealthReported(new GroupLinkHealthReportedEvent(
                9L,
                200L,
                "1203630health@g.us",
                "HEALTHY",
                55,
                1_782_712_801_000L,
                null,
                "acc_100",
                "evt-1"));

        ArgumentCaptor<GroupLinkHealth> captor = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(healthMapper).upsert(captor.capture());
        GroupLinkHealth row = captor.getValue();
        assertThat(row.getGroupLinkId()).isEqualTo(200L);
        assertThat(row.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.AVAILABLE.code());
        assertThat(row.getBanned()).isFalse();
        assertThat(row.getCurrentCount()).isEqualTo(55);
        assertThat(row.getLastCheckAt()).isEqualTo(1_782_712_801_000L);
        assertThat(row.getLastHealthError()).isNull();
        assertThat(row.getHealthFailureCount()).isZero();
        assertThat(tenantContextDuringMapperCalls).containsExactly(9L, 9L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void applyHealthReported_errorPreservesMemberCountAndIncrementsFailureCount() {
        TenantContext.set(3L);
        GroupLinkHealth current = currentHealth(66, 2);
        when(healthMapper.selectByGroupLinkId(201L)).thenReturn(current);

        service.applyHealthReported(new GroupLinkHealthReportedEvent(
                10L,
                201L,
                "1203630error@g.us",
                "ERROR",
                null,
                1_782_712_802_000L,
                "GROUP_METADATA_FAILED",
                "acc_101",
                "evt-2"));

        ArgumentCaptor<GroupLinkHealth> captor = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(healthMapper).upsert(captor.capture());
        GroupLinkHealth row = captor.getValue();
        assertThat(row.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.UNAVAILABLE.code());
        assertThat(row.getBanned()).isFalse();
        assertThat(row.getCurrentCount()).isEqualTo(66);
        assertThat(row.getLastHealthError()).isEqualTo("GROUP_METADATA_FAILED");
        assertThat(row.getHealthFailureCount()).isEqualTo(3);
        assertThat(TenantContext.get()).isEqualTo(3L);
    }

    @Test
    void applyHealthReported_revokedInviteMapsToLinkInvalid() {
        when(healthMapper.selectByGroupLinkId(202L)).thenReturn(null);

        service.applyHealthReported(new GroupLinkHealthReportedEvent(
                11L,
                202L,
                "1203630revoked@g.us",
                "ERROR",
                null,
                1_782_712_803_000L,
                "INVITE_REVOKED",
                "acc_102",
                "evt-3"));

        ArgumentCaptor<GroupLinkHealth> captor = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(healthMapper).upsert(captor.capture());
        GroupLinkHealth row = captor.getValue();
        assertThat(row.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.LINK_INVALID.code());
        assertThat(row.getBanned()).isFalse();
        assertThat(row.getHealthFailureCount()).isEqualTo(1);
        assertThat(row.getLastHealthError()).isEqualTo("INVITE_REVOKED");
    }

    @Test
    void applyHealthReported_missingHealthThrowsBusinessException() {
        assertThatThrownBy(() -> service.applyHealthReported(new GroupLinkHealthReportedEvent(
                1L, 200L, "1203630health@g.us", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("群链接健康事件缺少 health");
    }

    private List<Long> captureTenantContext(GroupLinkHealth current) {
        List<Long> tenantContexts = new ArrayList<>();
        doAnswer(invocation -> {
            tenantContexts.add(TenantContext.get());
            return null;
        }).when(healthMapper).upsert(any());
        doAnswer(invocation -> {
            tenantContexts.add(TenantContext.get());
            return current;
        }).when(healthMapper).selectByGroupLinkId(200L);
        return tenantContexts;
    }

    private static GroupLinkHealth currentHealth(Integer currentCount, Integer failureCount) {
        GroupLinkHealth current = new GroupLinkHealth();
        current.setCurrentCount(currentCount);
        current.setHealthFailureCount(failureCount);
        return current;
    }
}
