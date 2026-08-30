package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** V170 的设备快照、聚合保留期和菜单契约门禁。 */
class HyperlinkMarketAnalysisMigrationSqlTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V170__hyperlink_market_analysis.sql");

    @Test
    void migrationCreatesOnlyFrozenMarketSurface() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "sender_device_os_snapshot",
                "CREATE TABLE IF NOT EXISTS hyperlink_stat_daily (",
                "CREATE TABLE IF NOT EXISTS hyperlink_stat_hourly (",
                "sender_device_os TINYINT NOT NULL",
                "idx_hyperlink_stat_daily_retention",
                "idx_hyperlink_stat_hourly_retention",
                "'超链市场分析', 'HyperlinkAnalysis', 'M'",
                "'/hyperlink/analysis', 'hyperlink/analysis/index'",
                "'tenant:hyperlink_analysis:view', 'solar:chart-2-bold-duotone', 60")
                .doesNotContain(
                        "protocol_backend TINYINT NOT NULL",
                        "marketing-stats/accounts",
                        "accounts/export",
                        "INSERT INTO sys_role_menu",
                        "INSERT IGNORE INTO sys_role_menu");
        assertThat(sql).contains("顶部overview另行全局去重", "滚动保留90天", "滚动保留8天");
    }
}
