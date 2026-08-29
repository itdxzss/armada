package com.armada.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkWalletPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
