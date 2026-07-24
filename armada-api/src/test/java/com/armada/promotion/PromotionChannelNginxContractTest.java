package com.armada.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 公开渠道运行时接口的受信边缘代理契约。 */
class PromotionChannelNginxContractTest {

    @Test
    void nginxOverwritesForwardedHostInsteadOfTrustingClientHeader() throws IOException {
        String nginx = Files.readString(resolveNginxConfig());

        assertThat(nginx).contains("location /api/ {");
        assertThat(nginx).contains("proxy_set_header X-Forwarded-Host  $host;");
    }

    private Path resolveNginxConfig() {
        List<Path> candidates = List.of(
                Path.of("..", "armada-deploy", "nginx.conf"),
                Path.of("armada-deploy", "nginx.conf"));
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 armada-deploy/nginx.conf"));
    }
}
