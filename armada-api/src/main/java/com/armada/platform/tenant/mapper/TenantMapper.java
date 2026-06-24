package com.armada.platform.tenant.mapper;

import com.armada.platform.tenant.model.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户注册表数据访问。tenant 表无 tenant_id 列,已登记进 MyBatisConfig.IGNORED_TABLES,
 * 故此处查询不会被注入 tenant_id 过滤,登录/解析阶段(无租户上下文)可用。
 */
@Mapper
public interface TenantMapper {

    /** 按租户码查启用(status=1)的租户;无则返回 null。 */
    Tenant selectActiveByCode(@Param("code") String code);
}
