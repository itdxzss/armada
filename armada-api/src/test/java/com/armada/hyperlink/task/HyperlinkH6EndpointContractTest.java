package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** H3 只生成短链，H6 的公网与分析端点继续由冻结设计拥有。 */
class HyperlinkH6EndpointContractTest {

    @Test
    void sharedAndH6DesignKeepTheSameSixEndpoints() throws IOException {
        String shared = read("2026-08-28-hyperlink-task-shared-contract.md");
        String h6 = read("2026-08-28-hyperlink-task-attribution-analysis-design.md");

        for (String endpoint : new String[]{
                "/api/public/hl/{shortCode}",
                "/api/hyperlink-tasks/{id}/clicks",
                "/api/hyperlink-tasks/{id}/click-attribution/export",
                "/api/hyperlink-tasks/{id}/visit-trend",
                "/api/hyperlink-tasks/{id}/visit-trend/export",
                "/api/hyperlink-tasks/{id}/ban-stats"}) {
            assertThat(shared).contains(endpoint);
            assertThat(h6).contains(endpoint);
        }
    }

    private String read(String fileName) throws IOException {
        Path path = Path.of(System.getProperty("basedir"), "../docs/superpowers/specs", fileName);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
