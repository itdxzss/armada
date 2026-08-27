package com.armada.hyperlink.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.controller.MarketingTemplateFileController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** 图片接口兼容权限合同测试，防止扩展超链权限时删掉存量营销权限。 */
class MarketingTemplateFilePermissionCompatibilityTest {

    @Test
    void uploadKeepsMarketingViewAndAddsHyperlinkCreateEdit() throws NoSuchMethodException {
        Method method = MarketingTemplateFileController.class.getMethod(
                "upload", org.springframework.web.multipart.MultipartFile.class);
        String expression = method.getAnnotation(PreAuthorize.class).value();

        assertThat(expression)
                .contains("tenant:marketing_template:view")
                .contains("tenant:hyperlink_template:create")
                .contains("tenant:hyperlink_template:edit");
    }

    @Test
    void contentKeepsEveryExistingPermissionAndAddsHyperlinkReadWrite() throws NoSuchMethodException {
        Method method = MarketingTemplateFileController.class.getMethod("content", Long.class);
        String expression = method.getAnnotation(PreAuthorize.class).value();

        assertThat(expression)
                .contains("tenant:marketing_template:view")
                .contains("tenant:historical_group:view")
                .contains("tenant:marketing_task:view")
                .contains("tenant:group_pull_marketing:view")
                .contains("tenant:group_creation_marketing:view")
                .contains("tenant:hyperlink_template:view")
                .contains("tenant:hyperlink_template:create")
                .contains("tenant:hyperlink_template:edit");
    }
}
