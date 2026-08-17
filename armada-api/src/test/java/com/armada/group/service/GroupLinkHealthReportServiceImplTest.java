package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupLinkHealthReportedEvent;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.service.impl.GroupCurrentInvitePersistence;
import com.armada.group.service.impl.GroupLinkHealthReportServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群健康事件只写当前群资料的单测。 */
@ExtendWith(MockitoExtension.class)
class GroupLinkHealthReportServiceImplTest {

    @Mock private GroupLinkMapper groupLinkMapper;
    @Mock private GroupCurrentInvitePersistence currentInvitePersistence;

    private GroupLinkHealthReportServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        service = new GroupLinkHealthReportServiceImpl(
                groupLinkMapper, currentInvitePersistence);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void healthyEventWritesAvailableCurrentProfileAndRestoresTenant() {
        when(groupLinkMapper.selectActiveIdByGroupJid("1203630health@g.us"))
                .thenReturn(200L);
        when(currentInvitePersistence.findHealth("1203630health@g.us"))
                .thenReturn(currentHealth(44, 3));

        Optional<Long> result = service.applyHealthReported(event(
                9L, 200L, "1203630health@g.us", "HEALTHY", 55, null));

        assertThat(result).contains(200L);
        ArgumentCaptor<GroupLinkHealth> row = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(currentInvitePersistence).applyHealth(
                org.mockito.ArgumentMatchers.eq("1203630health@g.us"), row.capture());
        assertThat(row.getValue().getHealthStatus())
                .isEqualTo(GroupLinkHealthStatus.AVAILABLE.code());
        assertThat(row.getValue().getCurrentCount()).isEqualTo(55);
        assertThat(row.getValue().getHealthFailureCount()).isZero();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void errorPreservesCurrentCountAndIncrementsFailureCount() {
        when(groupLinkMapper.selectActiveIdByGroupJid("1203630error@g.us"))
                .thenReturn(201L);
        when(currentInvitePersistence.findHealth("1203630error@g.us"))
                .thenReturn(currentHealth(66, 2));

        service.applyHealthReported(event(
                10L, 201L, "1203630error@g.us", "ERROR", null,
                "GROUP_METADATA_FAILED"));

        ArgumentCaptor<GroupLinkHealth> row = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(currentInvitePersistence).applyHealth(
                org.mockito.ArgumentMatchers.eq("1203630error@g.us"), row.capture());
        assertThat(row.getValue().getHealthStatus())
                .isEqualTo(GroupLinkHealthStatus.UNAVAILABLE.code());
        assertThat(row.getValue().getCurrentCount()).isEqualTo(66);
        assertThat(row.getValue().getHealthFailureCount()).isEqualTo(3);
    }

    @Test
    void unknownGroupSkipsCurrentHealthWrite() {
        when(groupLinkMapper.selectActiveIdByGroupJid("1203630missing@g.us"))
                .thenReturn(null);

        assertThat(service.applyHealthReported(event(
                12L, null, "1203630missing@g.us", "BANNED", null,
                "CHAT_TERMINATED"))).isEmpty();

        verifyNoInteractions(currentInvitePersistence);
    }

    @Test
    void conflictingHandleAndGroupJidAreRejected() {
        when(groupLinkMapper.selectActiveIdByGroupJid("1203630mismatch@g.us"))
                .thenReturn(999L);

        assertThatThrownBy(() -> service.applyHealthReported(event(
                12L, 204L, "1203630mismatch@g.us", "BANNED", null,
                "CHAT_SUSPENDED")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("群链接健康事件 groupLinkId 与 groupJid 不一致");
    }

    private static GroupLinkHealthReportedEvent event(
            Long tenantId,
            Long groupLinkId,
            String groupJid,
            String health,
            Integer memberCount,
            String errorCode) {
        return new GroupLinkHealthReportedEvent(
                tenantId, groupLinkId, groupJid, health, memberCount,
                1_782_712_801_000L, errorCode, "acc", "evt");
    }

    private static GroupLinkHealth currentHealth(Integer count, Integer failures) {
        GroupLinkHealth current = new GroupLinkHealth();
        current.setCurrentCount(count);
        current.setHealthFailureCount(failures);
        return current;
    }
}
