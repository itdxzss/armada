package com.armada.platform.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantCodeResolver 真库测试:种子码可解析、空白/未知码返回空。 */
class TenantCodeResolverImplDbTest extends DbTestBase {

    @Autowired private TenantCodeResolver resolver;

    @Test
    void resolvesSeededCode() {
        assertThat(resolver.resolveTenantId("demo")).contains(1L);
        assertThat(resolver.resolveTenantId("demo2")).contains(2L);
    }

    @Test
    void unknownCode_empty() {
        assertThat(resolver.resolveTenantId("nope")).isEmpty();
    }

    @Test
    void blankCode_empty() {
        assertThat(resolver.resolveTenantId("  ")).isEmpty();
        assertThat(resolver.resolveTenantId(null)).isEmpty();
    }
}
