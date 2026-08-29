package com.armada.marketing.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 素材绑定和删除共用行锁的显式租户 SQL 合同测试。 */
class MarketingTemplateFileLockContractTest {

    @Test
    void lockQueryDisablesTenantRewriteAndUsesExplicitTenantBeforeForUpdate() throws Exception {
        Method method = MarketingTemplateFileMapper.class.getMethod(
                "selectByIdForUpdate", Long.class, Long.class);
        InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
        String xml = Files.readString(Path.of(
                "src/main/resources/mapper/marketing/MarketingTemplateFileMapper.xml"),
                StandardCharsets.UTF_8);

        assertThat(ignore).isNotNull();
        assertThat(ignore.tenantLine()).isEqualTo("true");
        assertThat(xml)
                .contains("WHERE tenant_id = #{tenantId}")
                .contains("AND id = #{id}")
                .contains("AND deleted_at IS NULL")
                .contains("FOR UPDATE")
                .doesNotContain("LIMIT 1");
    }
}
