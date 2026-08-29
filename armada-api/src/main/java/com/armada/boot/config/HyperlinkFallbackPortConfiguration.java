package com.armada.boot.config;

import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.UnavailableHyperlinkWalletPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 超链任务外部端口未接入时的失败关闭装配。 */
@Configuration(proxyBeanMethods = false)
public class HyperlinkFallbackPortConfiguration {

    @Bean
    @ConditionalOnMissingBean(HyperlinkWalletPort.class)
    public HyperlinkWalletPort hyperlinkWalletPort() {
        return new UnavailableHyperlinkWalletPort();
    }

    @Bean
    @ConditionalOnMissingBean(HyperlinkTaskAuditPort.class)
    public HyperlinkTaskAuditPort hyperlinkTaskAuditPort() {
        return new UnavailableHyperlinkTaskAuditPort();
    }
}
