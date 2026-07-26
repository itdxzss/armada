package com.armada.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.controller.AccountController;
import com.armada.account.controller.AccountGroupController;
import com.armada.account.controller.AccountImportController;
import com.armada.group.controller.GroupController;
import com.armada.group.controller.GroupLinkController;
import com.armada.group.controller.GroupLinkImportController;
import com.armada.group.controller.GroupLinkLabelController;
import com.armada.group.controller.HistoricalGroupController;
import com.armada.group.controller.HistoricalGroupMarketingController;
import com.armada.group.controller.HistoricalGroupPullExecutionController;
import com.armada.marketing.controller.GroupCreationMarketingTaskController;
import com.armada.marketing.controller.MarketingTaskController;
import com.armada.marketing.controller.MarketingTemplateController;
import com.armada.marketing.controller.MarketingTemplateFileController;
import com.armada.marketing.grouppull.controller.GroupPullMarketingTaskController;
import com.armada.platform.protocol.controller.ProtocolProcessController;
import com.armada.promotion.channel.controller.PromotionChannelController;
import com.armada.promotion.template.controller.PromotionTemplateController;
import com.armada.resource.controller.IpProxyController;
import com.armada.resource.controller.IpProxyStatsController;
import com.armada.task.controller.JoinTaskController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class BusinessControllerAuthorizationContractTest {

    private static final String ACCOUNT_GROUP_SHARED_READ = "hasAnyAuthority('tenant:account-group:view', "
            + "'tenant:account:view', 'tenant:account:edit', 'tenant:historical_group:view', "
            + "'tenant:group_creation_marketing:view', 'tenant:marketing_task:view', "
            + "'tenant:group_pull_marketing:view', 'tenant:join_task:view', 'tenant:pull_task:view')";
    private static final String ACCOUNT_SHARED_READ = "hasAnyAuthority('tenant:account:view', "
            + "'tenant:historical_group:view', 'tenant:join_task:view')";
    private static final String GROUP_LINK_SHARED_READ = "hasAnyAuthority('tenant:group_link:view', "
            + "'tenant:group_link_import:view', 'tenant:pull_task:view')";
    private static final String TEMPLATE_SHARED_READ = "hasAnyAuthority('tenant:marketing_template:view', "
            + "'tenant:historical_group:view', 'tenant:marketing_task:view', "
            + "'tenant:group_pull_marketing:view', 'tenant:group_creation_marketing:view')";

    @Test
    void protectsEachBusinessControllerWithItsOwningMenuPermission() {
        assertClassPermission(AccountController.class, "hasAuthority('tenant:account:view')");
        assertClassPermission(AccountGroupController.class, "hasAuthority('tenant:account-group:view')");
        assertClassPermission(AccountImportController.class, "hasAuthority('tenant:account:edit')");
        assertClassPermission(GroupController.class, "hasAuthority('tenant:group_link:view')");
        assertClassPermission(GroupLinkController.class, "hasAuthority('tenant:group_link:view')");
        assertClassPermission(GroupLinkImportController.class, "hasAuthority('tenant:group_link_import:view')");
        assertClassPermission(GroupLinkLabelController.class, "hasAuthority('tenant:group_link_import:view')");
        assertClassPermission(HistoricalGroupController.class, "hasAuthority('tenant:historical_group:view')");
        assertClassPermission(HistoricalGroupMarketingController.class, "hasAuthority('tenant:historical_group:view')");
        assertClassPermission(HistoricalGroupPullExecutionController.class, "hasAuthority('tenant:historical_group:view')");
        assertClassPermission(GroupCreationMarketingTaskController.class,
                "hasAuthority('tenant:group_creation_marketing:view')");
        assertClassPermission(MarketingTaskController.class, "hasAuthority('tenant:marketing_task:view')");
        assertClassPermission(MarketingTemplateController.class, "hasAuthority('tenant:marketing_template:view')");
        assertClassPermission(MarketingTemplateFileController.class, "hasAuthority('tenant:marketing_template:view')");
        assertClassPermission(GroupPullMarketingTaskController.class,
                "hasAuthority('tenant:group_pull_marketing:view')");
        assertClassPermission(ProtocolProcessController.class, "hasAuthority('tenant:account:view')");
        assertClassPermission(PromotionChannelController.class, "hasAuthority('tenant:buyer-channel:view')");
        assertClassPermission(PromotionTemplateController.class, "hasAuthority('tenant:buyer-template:view')");
        assertClassPermission(IpProxyController.class, "hasAuthority('tenant:resource:ips:list')");
        assertClassPermission(IpProxyStatsController.class, "hasAuthority('tenant:resource:ip-stats:list')");
        assertClassPermission(JoinTaskController.class, "hasAuthority('tenant:join_task:view')");
    }

    @Test
    void permitsOnlyBusinessMenusThatActuallyUseSharedReadEndpoints() {
        assertMethodPermission(AccountController.class, "list", ACCOUNT_SHARED_READ);
        assertMethodPermission(AccountGroupController.class, "list", ACCOUNT_GROUP_SHARED_READ);
        assertMethodPermission(AccountGroupController.class, "marketingOccupancy",
                "hasAnyAuthority('tenant:account-group:view', 'tenant:account:view')");
        assertMethodPermission(GroupLinkController.class, "list", GROUP_LINK_SHARED_READ);
        assertMethodPermission(GroupLinkController.class, "importLinks",
                "hasAuthority('tenant:group_link_import:view')");
        assertMethodPermission(GroupLinkController.class, "migrate",
                "hasAuthority('tenant:group_link_import:view')");
        assertMethodPermission(GroupLinkLabelController.class, "list",
                "hasAnyAuthority('tenant:group_link_import:view', 'tenant:pull_task:view')");
        assertMethodPermission(MarketingTemplateController.class, "list", TEMPLATE_SHARED_READ);
        assertMethodPermission(MarketingTemplateFileController.class, "content", TEMPLATE_SHARED_READ);
        assertMethodPermission(IpProxyController.class, "checkProxy",
                "hasAnyAuthority('tenant:resource:ips:list', 'tenant:resource:ip-stats:list')");
        assertMethodPermission(PromotionTemplateController.class, "page",
                "hasAnyAuthority('tenant:buyer-template:view', 'tenant:buyer-channel:view')");
    }

    @Test
    void keepsBuyerChannelButtonPermissionsDistinctFromPageViewPermission() {
        assertMethodPermission(PromotionChannelController.class, "create",
                "hasAuthority('tenant:buyer-channel:create')");
        assertMethodPermission(PromotionChannelController.class, "update",
                "hasAuthority('tenant:buyer-channel:edit')");
        assertMethodPermission(PromotionChannelController.class, "probe",
                "hasAuthority('tenant:buyer-channel:detect')");
        assertMethodPermission(PromotionChannelController.class, "delete",
                "hasAuthority('tenant:buyer-channel:delete')");
        assertMethodPermission(PromotionTemplateController.class, "updateRemark",
                "hasAuthority('tenant:buyer-template:remark')");
    }

    private static void assertClassPermission(Class<?> type, String expectedExpression) {
        PreAuthorize annotation = type.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("%s should declare a class-level business permission", type.getSimpleName())
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(expectedExpression);
    }

    private static void assertMethodPermission(Class<?> type, String methodName, String expectedExpression) {
        Method[] matches = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .toArray(Method[]::new);
        assertThat(matches)
                .as("%s.%s should identify one endpoint method", type.getSimpleName(), methodName)
                .hasSize(1);
        PreAuthorize annotation = matches[0].getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("%s.%s should declare its business permission", type.getSimpleName(), methodName)
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(expectedExpression);
    }
}
