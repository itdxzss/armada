package com.armada.hyperlink.task.port;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 显式能力白名单；默认空集合 fail-closed。测试环境可配置
 * {@code armada.hyperlink.private-capable-backends=WEB,ANDROID}。
 */
@Component
public class ConfiguredHyperlinkPrivateCapabilityPort implements HyperlinkPrivateCapabilityPort {
    private final Set<ProtocolBackend> enabled;

    public ConfiguredHyperlinkPrivateCapabilityPort(
            @Value("${armada.hyperlink.private-capable-backends:}") String configured) {
        this.enabled = configured == null || configured.isBlank() ? Set.of()
                : java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> ProtocolBackend.valueOf(value.toUpperCase(java.util.Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean supports(ProtocolBackend backend, String protocolId) {
        return backend != null && enabled.contains(backend);
    }
}
