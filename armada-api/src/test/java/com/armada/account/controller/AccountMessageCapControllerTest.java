package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.dto.AccountMessageCapQueryDTO;
import com.armada.account.model.vo.AccountMessageCapBatchVO;
import com.armada.account.service.AccountMessageCapService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class AccountMessageCapControllerTest {

    @Mock
    private AccountMessageCapService service;

    @Test
    void delegatesToServiceAndWrapsResult() {
        AccountMessageCapQueryDTO request = new AccountMessageCapQueryDTO(List.of(1L), true);
        AccountMessageCapBatchVO batch = new AccountMessageCapBatchVO(1, 0, 1, List.of());
        when(service.query(request)).thenReturn(batch);

        assertThat(new AccountMessageCapController(service).query(request).data()).isSameAs(batch);
        verify(service).query(request);
    }

    @Test
    void requiresAccountViewAuthority() {
        PreAuthorize annotation = AccountMessageCapController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasAuthority('tenant:account:view')");
    }
}
