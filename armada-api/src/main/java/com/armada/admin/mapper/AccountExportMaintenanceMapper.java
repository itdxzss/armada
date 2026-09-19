package com.armada.admin.mapper;

import com.armada.admin.model.dto.AccountExportExpiryCandidate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理域内部到期扫描；有界跨租户只读标识，实际修改回到账号域并恢复租户隔离。 */
@Mapper
public interface AccountExportMaintenanceMapper {
    /** 特许跨租户清理扫描，不对任何 HTTP 端点暴露。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id AS tenantId FROM account_export_job
            WHERE expires_at <= #{now} AND (status = 'READY' OR archive IS NOT NULL)
            ORDER BY expires_at, id LIMIT 100
            """)
    List<AccountExportExpiryCandidate> expired(@Param("now") long now);
}
