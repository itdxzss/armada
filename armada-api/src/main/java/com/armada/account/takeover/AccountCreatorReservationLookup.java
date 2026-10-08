package com.armada.account.takeover;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.armada.account.model.AccountCreatorReservation;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 从当前租户账号查找全局身份的预留/注销归属，保留原租户供恢复命令复核。 */
@Service
public class AccountCreatorReservationLookup {

    private final AccountCreatorDeletionMapper mapper;

    /** @param mapper 带显式当前账号租户约束的注销事实访问器 */
    public AccountCreatorReservationLookup(AccountCreatorDeletionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询账号本身或同一规范化身份的冻结归属，不向调用者返回号码。
     * @param accountId 当前租户的账号主键
     * @return 保留原始租户的预留/注销快照；账号不属于当前租户或无记录时为空
     * @throws BusinessException 缺少当前租户上下文
     */
    public Optional<AccountCreatorReservation> find(Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "缺少建群人归属查询租户上下文");
        }
        return Optional.ofNullable(mapper.selectReservationByAccount(tenantId, accountId));
    }
}
