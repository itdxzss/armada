package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.task.controller.HyperlinkMarketingAnalysisController;
import com.armada.hyperlink.task.model.dto.HyperlinkMarketingStatsQuery;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 市场页只暴露主查询和国家清单，字段命名不向旧设计漂移。 */
class HyperlinkMarketingEndpointContractTest {

    @Test
    void controllerExposesTwoReadEndpointsWithDedicatedPermission() throws Exception {
        RequestMapping root = HyperlinkMarketingAnalysisController.class
                .getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/hyperlink-tasks/marketing-stats");

        Method stats = HyperlinkMarketingAnalysisController.class
                .getMethod("stats", HyperlinkMarketingStatsQuery.class);
        Method countries = HyperlinkMarketingAnalysisController.class
                .getMethod("countries", HyperlinkMarketingStatsQuery.class);
        assertThat(stats.getAnnotation(GetMapping.class).value()).isEmpty();
        assertThat(countries.getAnnotation(GetMapping.class).value()).containsExactly("/countries");
        assertThat(countries.getParameterCount()).isOne();
        assertThat(stats.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('tenant:hyperlink_analysis:view')");
        assertThat(countries.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('tenant:hyperlink_analysis:view')");
    }

    @Test
    void queryUsesDeviceOsAndShortLinkEnabledNames() throws Exception {
        assertThat(HyperlinkMarketingStatsQuery.class.getMethod("getDeviceOs")).isNotNull();
        assertThat(HyperlinkMarketingStatsQuery.class.getMethod("getShortLinkEnabled")).isNotNull();
        assertThatThrownByMethod("getPlatform");
        assertThatThrownByMethod("getProtocolBackend");
        assertThatThrownByMethod("getIsShortLinkEnabled");
    }

    private static void assertThatThrownByMethod(String name) {
        assertThat(java.util.Arrays.stream(HyperlinkMarketingStatsQuery.class.getMethods())
                .map(Method::getName)).doesNotContain(name);
    }
}
