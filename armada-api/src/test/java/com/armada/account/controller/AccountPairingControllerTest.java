package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.dto.AccountPairingCreateDTO;
import com.armada.promotion.pairing.model.command.ControlPairingCreateCommand;
import com.armada.promotion.pairing.model.vo.ControlPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.ControlPairingStatusVO;
import com.armada.promotion.pairing.service.ControlPairingService;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class AccountPairingControllerTest {

    @Mock private ControlPairingService service;

    @Test
    void createUsesAuthenticatedOwnerAndNeverAcceptsCodeFromBrowser() {
        when(service.create(org.mockito.ArgumentMatchers.any(ControlPairingCreateCommand.class)))
                .thenReturn(new ControlPairingCreatedVO(7002L, "REQUESTING", 92_001L));
        AccountPairingController controller = new AccountPairingController(service);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthPrincipal principal = principal();

        var result = controller.create(
                new AccountPairingCreateDTO("919876543211", 301L, "异地主设备"),
                principal,
                response);

        assertThat(result.data().sessionId()).isEqualTo(7002L);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        ArgumentCaptor<ControlPairingCreateCommand> command =
                ArgumentCaptor.forClass(ControlPairingCreateCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().ownerUserId()).isEqualTo(81L);
        assertThat(command.getValue().accountGroupId()).isEqualTo(301L);
    }

    @Test
    void statusIsAlwaysScopedToAuthenticatedTenant() {
        when(service.status(7002L, 7L)).thenReturn(new ControlPairingStatusVO(
                "WAITING_CONFIRMATION", "88888888", 92_001L,
                null, null, null));
        AccountPairingController controller = new AccountPairingController(service);

        var result = controller.status(7002L, principal(), new MockHttpServletResponse());

        assertThat(result.data().pairingCode()).isEqualTo("88888888");
        verify(service).status(7002L, 7L);
    }

    @Test
    void endpointRequiresAccountEditAuthority() {
        PreAuthorize annotation = AccountPairingController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasAuthority('tenant:account:edit')");
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(
                81L, 7L, "operator", "操作员", "tenant", "租户",
                List.of("TENANT_ADMIN"), List.of("tenant:account:edit"));
    }
}
