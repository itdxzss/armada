package com.armada.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.protocol.controller.ProtocolProcessController;
import com.armada.platform.protocol.process.ProtocolProcessRestartService;
import com.armada.platform.protocol.process.ProtocolRestartVO;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class BusinessMethodSecurityEnforcementTest {

    private ProtocolProcessController controller;
    private TestRestartService restartService;
    private AnnotationConfigApplicationContext applicationContext;

    @BeforeEach
    void createMethodSecurityContext() {
        applicationContext = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = applicationContext.getBean(ProtocolProcessController.class);
        restartService = applicationContext.getBean(TestRestartService.class);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        restartService.reset();
        applicationContext.close();
    }

    @Test
    void rejectsAuthenticatedUserWithoutOwningMenuPermission() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "operator",
                null,
                "tenant:join_task:view"));

        assertThatThrownBy(controller::restart)
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowsAuthenticatedUserWithOwningMenuPermission() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "operator",
                null,
                "tenant:account:view"));

        controller.restart();

        assertThat(restartService.invocationCount()).isEqualTo(1);
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        TestRestartService restartService() {
            return new TestRestartService();
        }

        @Bean
        ProtocolProcessController protocolProcessController(ProtocolProcessRestartService restartService) {
            return new ProtocolProcessController(restartService);
        }
    }

    static class TestRestartService implements ProtocolProcessRestartService {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ProtocolRestartVO restart() {
            invocations.incrementAndGet();
            return null;
        }

        int invocationCount() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
        }
    }
}
