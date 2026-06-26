package com.armada.platform.proxy;

import com.armada.platform.protocol.port.account.command.ProxyDescriptor;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyResolverTest {

    private final ProxyResolver resolver = new ProxyResolver();

    @Test
    void resolveBuildsSocks5DescriptorAndExtractsStickySession() {
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "geo.iproyal.com",
                12321,
                new ProxyCredentials("user1", "pass1_country-in_session-Abc12345_lifetime-1h"),
                "印度");

        ProxyDescriptor descriptor = resolver.resolve(endpoint);

        assertThat(descriptor.protocol()).isEqualTo("socks5");
        assertThat(descriptor.url())
                .isEqualTo("socks5://user1:pass1_country-in_session-Abc12345_lifetime-1h@geo.iproyal.com:12321");
        assertThat(descriptor.sessionId()).isEqualTo("Abc12345");
        assertThat(descriptor.country()).isEqualTo("印度");
    }

    @Test
    void resolveBuildsHttpDescriptor() {
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_HTTP,
                "proxy.example.com",
                8080,
                new ProxyCredentials("u", "p_session-Sess900"),
                "US");

        ProxyDescriptor descriptor = resolver.resolve(endpoint);

        assertThat(descriptor.protocol()).isEqualTo("http");
        assertThat(descriptor.url()).isEqualTo("http://u:p_session-Sess900@proxy.example.com:8080");
        assertThat(descriptor.sessionId()).isEqualTo("Sess900");
    }

    @Test
    void resolveRejectsPasswordWithoutStickySession() {
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "geo.iproyal.com",
                12321,
                new ProxyCredentials("user1", "plain-password"),
                "印度");

        assertThatThrownBy(() -> resolver.resolve(endpoint))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少 sticky session");
    }

    @Test
    void resolveRejectsUnknownProtocolCode() {
        ProxyEndpoint endpoint = new ProxyEndpoint(
                99,
                "geo.iproyal.com",
                12321,
                new ProxyCredentials("user1", "pass_session-Abc12345"),
                "印度");

        assertThatThrownBy(() -> resolver.resolve(endpoint))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的代理协议");
    }
}
