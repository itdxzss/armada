-- 租户注册表(平台级)。本身无 tenant_id 列,不参与租户行隔离 —— 必须登记进 MyBatisConfig.IGNORED_TABLES,
-- 否则解析租户时该查询会被注入 AND tenant_id=? 抛 Unknown column。
-- 先测阶段:登录按 tenant_code + 配置统一密码校验;拦截器据 tenant_code 解析 tenant_id 注入上下文;JWT 后置。
CREATE TABLE tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT                    COMMENT '主键,即业务表里用的 tenant_id',
    tenant_code VARCHAR(64)  NOT NULL                                   COMMENT '租户码(前端 X-Tenant-Code 头 / 登录入参)',
    name        VARCHAR(128) NOT NULL                                   COMMENT '租户名',
    status      TINYINT      NOT NULL DEFAULT 1                         COMMENT '状态:1=启用 0=停用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                            COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_code (tenant_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租户注册表';

-- 先测种子租户(id 固定;业务数据按此 tenant_id 隔离)。
INSERT INTO tenant (id, tenant_code, name, status) VALUES
    (1, 'demo',  '演示租户A', 1),
    (2, 'demo2', '演示租户B', 1);
