package com.armada.platform.tenant.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantMapper 真库测试:验种子租户可按码查到、未知码返回 null、tenant 表不被租户隔离误伤。 */
class TenantMapperDbTest extends DbTestBase {

    @Autowired private TenantMapper tenantMapper;

    @Test
    void selectActiveByCode_returnsSeededTenant() {
        Tenant t = tenantMapper.selectActiveByCode("demo");
        assertThat(t).isNotNull();
        assertThat(t.getId()).isEqualTo(1L);
        assertThat(t.getName()).isEqualTo("演示租户A");
        assertThat(t.getStatus()).isEqualTo(1);
    }

    @Test
    void selectActiveByCode_unknownCode_returnsNull() {
        assertThat(tenantMapper.selectActiveByCode("nope")).isNull();
    }
}
