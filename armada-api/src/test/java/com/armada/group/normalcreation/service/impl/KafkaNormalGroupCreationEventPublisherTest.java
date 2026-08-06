package com.armada.group.normalcreation.service.impl;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.platform.dispatch.mapper.NormalGroupCreationDispatchMapper;
import com.armada.platform.dispatch.model.NormalGroupCreationDispatchCandidate;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class KafkaNormalGroupCreationEventPublisherTest {

    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private NormalGroupCreationMapper mapper;
    @Mock private NormalGroupCreationDispatchMapper dispatchMapper;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void expiredCreateLeaseBecomesResultUnknownWhilePrepareCanBeRecovered() {
        NormalGroupCreationDispatchCandidate prepare = candidate(
                7L, 99L, 101L, "PREPARING_CONTACTS", "PREPARE");
        NormalGroupCreationDispatchCandidate create = candidate(
                8L, 100L, 102L, "CREATING_GROUP", "CREATE");
        NormalGroupCreationDispatchCandidate post = candidate(
                9L, 101L, 103L, "POST_PROCESSING", "POST_PROCESS");
        when(dispatchMapper.selectExpiredProcessing(anyLong(), eq(100)))
                .thenReturn(List.of(prepare, create, post));
        when(dispatchMapper.selectPendingDispatches(anyLong(), eq(100)))
                .thenReturn(List.of());
        when(mapper.recoverExpiredProcessing(
                eq(101L), eq("PREPARING_CONTACTS"), anyLong(), eq(3), anyLong()))
                .thenReturn(1);
        when(mapper.recoverExpiredProcessing(
                eq(102L), eq("CREATING_GROUP"), anyLong(), eq(3), anyLong()))
                .thenReturn(1);
        when(mapper.recoverExpiredProcessing(
                eq(103L), eq("POST_PROCESSING"), anyLong(), eq(3), anyLong()))
                .thenReturn(1);

        publisher().recoverPendingDispatches();

        verify(mapper).recoverExpiredProcessing(
                eq(101L), eq("PREPARING_CONTACTS"), anyLong(), eq(3), anyLong());
        verify(mapper).recoverExpiredProcessing(
                eq(102L), eq("CREATING_GROUP"), anyLong(), eq(3), anyLong());
        verify(mapper).recoverExpiredProcessing(
                eq(103L), eq("POST_PROCESSING"), anyLong(), eq(3), anyLong());
        verify(mapper).refreshTaskSummary(eq(99L), anyLong());
        verify(mapper).refreshTaskSummary(eq(100L), anyLong());
        verify(mapper).refreshTaskSummary(eq(101L), anyLong());
    }

    private KafkaNormalGroupCreationEventPublisher publisher() {
        return new KafkaNormalGroupCreationEventPublisher(
                kafkaTemplate,
                mapper,
                dispatchMapper,
                "group.normal-creation.contact-prepare.v1",
                "group.normal-creation.create.v1",
                "group.normal-creation.post-process.v1",
                300_000L,
                3);
    }

    private static NormalGroupCreationDispatchCandidate candidate(
            Long tenantId,
            Long taskId,
            Long itemId,
            String currentStep,
            String dispatchStage) {
        return new NormalGroupCreationDispatchCandidate(
                tenantId, taskId, itemId, 1L, currentStep, dispatchStage, "PROCESSING");
    }
}
