package com.armada.marketing.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 素材绑定、编辑和删除行锁的全局租户 SQL 合同测试。 */
class MarketingTemplateFileLockContractTest {

    @Test
    void lockQueriesKeepGlobalTenantRewriteAndUseTheSmallestRequiredProjection() throws Exception {
        Method contentLock = MarketingTemplateFileMapper.class.getMethod(
                "selectByIdForUpdate", Long.class);
        Method idLock = MarketingTemplateFileMapper.class.getMethod(
                "selectIdByIdForUpdate", Long.class);
        String xml = Files.readString(Path.of(
                "src/main/resources/mapper/marketing/MarketingTemplateFileMapper.xml"),
                StandardCharsets.UTF_8);

        assertThat(contentLock.getAnnotation(InterceptorIgnore.class)).isNull();
        assertThat(idLock.getAnnotation(InterceptorIgnore.class)).isNull();
        assertThat(selectBlock(xml, "selectByIdForUpdate"))
                .doesNotContain("tenant_id = #{tenantId}")
                .contains("WHERE id = #{id}")
                .contains("AND deleted_at IS NULL")
                .contains("FOR UPDATE")
                .doesNotContain("LIMIT 1");
        assertThat(selectBlock(xml, "selectIdByIdForUpdate"))
                .contains("SELECT id")
                .doesNotContain("content")
                .doesNotContain("tenant_id = #{tenantId}")
                .contains("WHERE id = #{id}")
                .contains("AND deleted_at IS NULL")
                .contains("FOR UPDATE")
                .doesNotContain("LIMIT 1");
    }

    private static String selectBlock(String xml, String id) {
        String startMarker = "<select id=\"" + id + "\"";
        int start = xml.indexOf(startMarker);
        int end = xml.indexOf("</select>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
