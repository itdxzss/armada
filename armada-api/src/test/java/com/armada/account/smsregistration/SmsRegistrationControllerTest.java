package com.armada.account.smsregistration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.armada.account.controller.SmsActivationController;
import com.armada.account.model.dto.SmsRegistrationStartDTO;
import com.armada.account.service.SmsActivationService;
import com.armada.account.service.SmsRegistrationService;
import com.armada.shared.security.AuthPrincipal;
import org.junit.jupiter.api.Test;

/** 入口只负责缺省个人类型及透传显式选择，类型合法性由真实服务测试覆盖。 */
class SmsRegistrationControllerTest {
    @Test
    void omittedBodyKeepsPersonalAndExplicitBusinessIsForwarded() {
        var registrations = mock(SmsRegistrationService.class);
        var controller = new SmsActivationController(mock(SmsActivationService.class), registrations);
        var principal = mock(AuthPrincipal.class);
        controller.startRegistration(1L, null, principal);
        controller.startRegistration(2L, new SmsRegistrationStartDTO(2), principal);
        controller.startRegistration(3L, new SmsRegistrationStartDTO(null), principal);
        verify(registrations).start(1L, principal.userId(), 1);
        verify(registrations).start(2L, principal.userId(), 2);
        verify(registrations).start(3L, principal.userId(), 1);
    }
}
