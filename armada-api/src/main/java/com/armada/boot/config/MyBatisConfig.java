package com.armada.boot.config;

import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置:租户行隔离拦截器。给每条经 MyBatis 的 SQL 自动注入
 * {@code AND tenant_id = 当前租户},实现 SaaS 行级数据隔离。
 *
 * <p>租户从 {@link TenantContext} 取,无上下文回退哨兵 {@code -1}(fail-closed)。</p>
 */
@Configuration
public class MyBatisConfig {

    /** 无上下文时注入的哨兵租户值,永不匹配任何真实租户(fail-closed)。 */
    private static final long NO_TENANT_SENTINEL = -1L;

    /**
     * 永远不附加 tenant_id 过滤的表:凡<b>没有 tenant_id 列</b>的表都必须在此,
     * 否则租户上下文下查询会被注入非法的 {@code AND tenant_id = ?} 而抛 Unknown column。
     * 新增此类表时记得同步登记。
     */
    private static final Set<String> IGNORED_TABLES = Set.of("tenant");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        return interceptor;
    }

    @Bean
    public TenantLineHandler tenantLineHandler() {
        return new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.get();
                return new LongValue(tenantId == null ? NO_TENANT_SENTINEL : tenantId);
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return IGNORED_TABLES.contains(normalize(tableName));
            }
        };
    }

    /** 归一化表名:剥 schema 前缀、去反引号、trim、转小写,再比对忽略名单。 */
    private static String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }
        String name = tableName.trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.replace("`", "").trim().toLowerCase();
    }
}
