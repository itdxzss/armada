package com.armada.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAuditEventMapper;
import com.armada.hyperlink.task.port.DatabaseHyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkWalletPort;
import com.armada.hyperlink.task.port.ZeroBillingHyperlinkWalletPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** 超链外部端口失败关闭装配测试。 */
class HyperlinkFallbackPortConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HyperlinkFallbackPortConfiguration.class);

    @Test
    void suppliesFailClosedPortsWhenNoRealAdapterExists() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(HyperlinkWalletPort.class);
            assertThat(context.getBean(HyperlinkWalletPort.class))
                    .isInstanceOf(UnavailableHyperlinkWalletPort.class);
            assertThat(context).hasSingleBean(HyperlinkTaskAuditPort.class);
            assertThat(context.getBean(HyperlinkTaskAuditPort.class))
                    .isInstanceOf(UnavailableHyperlinkTaskAuditPort.class);
        });
    }

    @Test
    void suppliesZeroBillingOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("armada.hyperlink.billing-mode=ZERO_TEST")
                .run(context -> {
                    assertThat(context).hasSingleBean(HyperlinkWalletPort.class);
                    assertThat(context.getBean(HyperlinkWalletPort.class))
                            .isInstanceOf(ZeroBillingHyperlinkWalletPort.class);
                });
    }

    @Test
    void preservesRealAdaptersWhenTheyAreProvided() {
        HyperlinkWalletPort walletPort = mock(HyperlinkWalletPort.class);
        HyperlinkTaskAuditPort auditPort = mock(HyperlinkTaskAuditPort.class);

        contextRunner
                .withBean(HyperlinkWalletPort.class, () -> walletPort)
                .withBean(HyperlinkTaskAuditPort.class, () -> auditPort)
                .run(context -> {
                    assertThat(context.getBean(HyperlinkWalletPort.class)).isSameAs(walletPort);
                    assertThat(context.getBean(HyperlinkTaskAuditPort.class)).isSameAs(auditPort);
                });
    }

    @Test
    void scannedDatabaseAuditAdapterReplacesFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(HyperlinkFallbackPortConfiguration.class,
                        DatabaseAuditScanConfiguration.class)
                .withBean(HyperlinkTaskAuditEventMapper.class,
                        () -> mock(HyperlinkTaskAuditEventMapper.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(HyperlinkTaskAuditPort.class);
                    assertThat(context.getBean(HyperlinkTaskAuditPort.class))
                            .isInstanceOf(DatabaseHyperlinkTaskAuditPort.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = DatabaseHyperlinkTaskAuditPort.class)
    static class DatabaseAuditScanConfiguration { }
}
