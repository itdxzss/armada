package com.armada.boot.config;

import com.armada.hyperlink.task.model.enums.HyperlinkBillingMode;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkWalletPort;
import com.armada.hyperlink.task.port.ZeroBillingHyperlinkWalletPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 超链任务外部端口未接入时的失败关闭装配。 */
@Configuration(proxyBeanMethods = false)
public class HyperlinkFallbackPortConfiguration {

    @Bean
    @ConditionalOnMissingBean(HyperlinkWalletPort.class)
    public HyperlinkWalletPort hyperlinkWalletPort(
            @Value("${armada.hyperlink.billing-mode:UNAVAILABLE}") String configuredMode) {
        HyperlinkBillingMode mode = HyperlinkBillingMode.fromProperty(configuredMode);
        return mode == HyperlinkBillingMode.ZERO_TEST
                ? new ZeroBillingHyperlinkWalletPort()
                : new UnavailableHyperlinkWalletPort();
    }

    @Bean
    @ConditionalOnMissingBean(HyperlinkTaskAuditPort.class)
    public HyperlinkTaskAuditPort hyperlinkTaskAuditPort() {
        return new UnavailableHyperlinkTaskAuditPort();
    }
}
