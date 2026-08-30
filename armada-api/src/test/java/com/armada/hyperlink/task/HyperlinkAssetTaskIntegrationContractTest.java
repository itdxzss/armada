package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 素材任务引用保护、菜单排序和动态组件白名单合同。 */
class HyperlinkAssetTaskIntegrationContractTest {

    @Test
    void resourceAssetReferenceQueriesIncludeBothTaskAssetSlots() throws Exception {
        String xml = Files.readString(Path.of(
                "src/main/resources/mapper/marketing/MarketingTemplateFileMapper.xml"),
                StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("FROM hyperlink_task_content")
                .contains("SELECT link_preview_asset_id, 'hyperlink_task', hyperlink_task_id")
                .contains("SELECT body_main_asset_id, 'hyperlink_task', hyperlink_task_id")
                .contains("GROUP BY asset_id, source_type, source_id");
    }

    @Test
    void integrationMigrationRestoresMenuOrderAndSensitivePermission() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V169__hyperlink_asset_task_integration.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("WHEN 'HyperlinkTaskList' THEN 10")
                .contains("WHEN 'HyperlinkDataPackage' THEN 20")
                .contains("WHEN 'HyperlinkTemplate' THEN 30")
                .contains("WHEN 'HyperlinkResourceAsset' THEN 50")
                .contains("tenant:hyperlink_task:attribution_sensitive");
    }

    @Test
    void menuManagementAllowsTheShippedTaskComponent() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/armada/admin/service/impl/MenuManagementServiceImpl.java"),
                StandardCharsets.UTF_8);

        assertThat(source).contains("\"hyperlink/task/index\"");
    }
}
