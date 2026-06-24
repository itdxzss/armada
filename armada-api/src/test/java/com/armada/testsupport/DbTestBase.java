package com.armada.testsupport;

import com.armada.boot.Application;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 真库 DbTest 基类:启动完整 Spring 上下文(连测试库 armada schema,Flyway 自动迁移),
 * 每个测试方法在事务内执行并回滚以隔离数据,并预置租户上下文(供 MyBatis 租户拦截器注入 tenant_id)。
 *
 * <p>DB 凭据由 {@code armada-api/.env}(gitignored)注入(见 {@code dbtest.sh}),不在代码/仓库出现。
 * 子类直接 {@code @Autowired} 目标 Mapper,在 {@link #TEST_TENANT_ID} 租户下断言真库行为。</p>
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
public abstract class DbTestBase {

    /** 测试租户 ID;租户拦截器据此给每条 SQL 注入 {@code tenant_id}。 */
    protected static final long TEST_TENANT_ID = 1L;

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(TEST_TENANT_ID);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }
}
