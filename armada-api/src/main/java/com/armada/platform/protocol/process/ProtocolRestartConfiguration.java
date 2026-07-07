package com.armada.platform.protocol.process;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProtocolRestartProperties.class)
public class ProtocolRestartConfiguration {
}
