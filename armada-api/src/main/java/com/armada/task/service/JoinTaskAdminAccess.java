package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;

/** 为异步管理员操作保存可信发起人；不能由请求自报用户 ID。 */
public final class JoinTaskAdminAccess {
    private JoinTaskAdminAccess() {}

    /** 新任务及历史草稿首次开启时记录身份，已归属任务不能借编辑改换执行范围。 */
    public static Long ownerForSave(Long existingOwner) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !Objects.equals(TenantContext.get(), principal.tenantId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "进群任务需要有效登录身份");
        }
        if (existingOwner != null && existingOwner != principal.userId()
                && !principal.roleCodes().contains("TENANT_ADMIN")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "不能修改其他用户的管理员设置任务");
        }
        return existingOwner == null ? principal.userId() : existingOwner;
    }
}
