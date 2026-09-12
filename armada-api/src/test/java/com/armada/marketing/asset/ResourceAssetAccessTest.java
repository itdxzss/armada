package com.armada.marketing.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import com.armada.marketing.asset.service.ResourceAssetAccess;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** 业务参数不能把养群权限升级为超链素材权限。 */
class ResourceAssetAccessTest {
    private final ResourceAssetAccess access = new ResourceAssetAccess();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void scriptPermissionsCannotReadOrMutateHyperlinkAssets() {
        authenticate("tenant:script_marketing:view", "tenant:script_marketing:edit");
        assertThat(access.allowed(ResourceAssetScope.SCRIPT, "view")).isTrue();
        assertThat(access.allowed(ResourceAssetScope.SCRIPT, "upload")).isTrue();
        assertThat(access.allowed(ResourceAssetScope.SCRIPT, "edit")).isTrue();
        assertThat(access.allowed(ResourceAssetScope.SCRIPT, "delete")).isFalse();
        for (String action : new String[] {"view", "upload", "edit", "delete"}) {
            assertThat(access.allowed(ResourceAssetScope.HYPERLINK, action)).isFalse();
        }
    }

    @Test
    void hyperlinkPermissionsCannotAccessScriptOrGenericMarketingScope() {
        authenticate("tenant:resource_asset:view", "tenant:resource_asset:delete");
        assertThat(access.allowed(ResourceAssetScope.HYPERLINK, "view")).isTrue();
        assertThat(access.allowed(ResourceAssetScope.HYPERLINK, "delete")).isTrue();
        assertThat(access.allowed(ResourceAssetScope.SCRIPT, "view")).isFalse();
        assertThat(access.allowed(ResourceAssetScope.MARKETING, "view")).isFalse();
        assertThat(access.allowed(null, "view")).isFalse();
    }

    private void authenticate(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", "", Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
