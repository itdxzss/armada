package com.armada.resource.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.account.service.AccountOnlineCommandService;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.service.impl.IpProxyDeletionServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IP 删除编排服务单测。
 */
@ExtendWith(MockitoExtension.class)
class IpProxyDeletionServiceImplTest {

    @Mock
    private IpProxyMapper ipProxyMapper;

    @Mock
    private AccountOnlineCommandService accountOnlineCommandService;

    @InjectMocks
    private IpProxyDeletionServiceImpl service;

    @Test
    void batchDelete_reloginsOnlineBoundAccountsBeforeSoftDeletingIps() {
        List<Long> ids = List.of(10L, 11L);

        service.batchDelete(ids);

        InOrder inOrder = org.mockito.Mockito.inOrder(accountOnlineCommandService, ipProxyMapper);
        inOrder.verify(accountOnlineCommandService).reloginOnlineAccountsByProxyIds(ids);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        inOrder.verify(ipProxyMapper).softDeleteByIds(idsCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
        org.assertj.core.api.Assertions.assertThat(idsCaptor.getValue()).containsExactly(10L, 11L);
    }

    @Test
    void batchDelete_emptyIdsDoesNothing() {
        service.batchDelete(List.of());

        verifyNoInteractions(accountOnlineCommandService, ipProxyMapper);
    }

    @Test
    void batchDelete_reloginFailureDoesNotSoftDeleteIps() {
        List<Long> ids = List.of(10L);
        RuntimeException failure = new RuntimeException("no spare proxy");
        org.mockito.Mockito.doThrow(failure)
                .when(accountOnlineCommandService).reloginOnlineAccountsByProxyIds(ids);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.batchDelete(ids))
                .isSameAs(failure);

        verify(ipProxyMapper, never()).softDeleteByIds(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
