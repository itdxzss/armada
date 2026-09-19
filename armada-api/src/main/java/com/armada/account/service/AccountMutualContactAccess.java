package com.armada.account.service;
import com.armada.account.model.dto.AccountMutualContactCandidate;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import java.util.Objects;
/** 互存任务身份和执行账号访问边界。 */
public final class AccountMutualContactAccess {
    private static final int RISK_NORMAL = 1;
    private AccountMutualContactAccess() {}
    public static boolean admin(AuthPrincipal p) {
        return p.roleCodes().contains("TENANT_ADMIN");
    }
    public static void require(AuthPrincipal p) {
        if (p == null || !Objects.equals(TenantContext.get(), p.tenantId()))
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }
    public static boolean owns(AuthPrincipal p, AccountMutualContactCandidate a) {
        // 既有未分配账号遵循账号列表的租户共享语义；有明确归属的账号不得跨用户写入。
        return a != null && (admin(p) || a.ownerUserId() == null || Objects.equals(a.ownerUserId(), p.userId()));
    }
    public static boolean eligible(AccountMutualContactCandidate a) {
        return a != null && Integer.valueOf(AccountStateCode.NORMAL).equals(a.accountState())
                && Integer.valueOf(AccountLoginStateCode.ONLINE).equals(a.loginState())
                && (a.riskStatus() == null || a.riskStatus() == RISK_NORMAL) && a.muteStatus() == null
                && a.wsPhone() != null && a.wsPhone().matches("[1-9][0-9]{5,19}") && a.protocolAccountId() != null
                && !a.protocolAccountId().isBlank()
                && ("WEB".equals(a.protocolBackend()) || "ANDROID".equals(a.protocolBackend()));
    }
}
