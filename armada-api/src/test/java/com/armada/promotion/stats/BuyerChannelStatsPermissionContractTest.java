package com.armada.promotion.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** 渠道统计敏感操作权限契约。 */
class BuyerChannelStatsPermissionContractTest {

    @Test
    void editAndExportUseIndependentPermissions() throws Exception {
        Method update = BuyerChannelStatsController.class.getDeclaredMethod("update", long.class,
                String.class, BuyerChannelStatsModels.DailyInput.class,
                com.armada.shared.security.AuthPrincipal.class);
        Method export = BuyerChannelStatsController.class.getDeclaredMethod("export",
                BuyerChannelStatsModels.Query.class, com.armada.shared.security.AuthPrincipal.class,
                jakarta.servlet.http.HttpServletResponse.class);
        assertThat(update.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:buyer-channel-stats:edit");
        assertThat(export.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:buyer-channel-stats:export");
    }
}
