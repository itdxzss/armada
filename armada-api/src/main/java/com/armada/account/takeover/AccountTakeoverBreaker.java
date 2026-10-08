package com.armada.account.takeover;

import com.armada.account.mapper.AccountTakeoverBreakerMapper;
import com.armada.account.model.entity.Account;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以账号行锁串行化固定窗口计数和人工清零，熔断后不随时间自动恢复。 */
@Service
public class AccountTakeoverBreaker {

    /** 一次被挤事实更新后的自动恢复决策。 */
    public enum KickResult {
        /** 尚未达到熔断阈值，允许继续恢复。 */
        CONTINUE,
        /** 已熔断，必须人工清零后才允许恢复。 */
        TRIPPED
    }

    private final AccountTakeoverBreakerMapper mapper;
    private final AccountAutoTakeoverProperties properties;

    /**
     * @param mapper 熔断事实访问器
     * @param properties 固定窗口与阈值配置
     */
    public AccountTakeoverBreaker(AccountTakeoverBreakerMapper mapper, AccountAutoTakeoverProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * 在当前账号状态事务内累计一次被挤；首个计数行尚不存在时也用账号行实现互斥。
     *
     * @param account 当前租户的存续账号
     * @param kickedAt 被挤事实发生时间，毫秒
     * @return 计数后是否熔断
     * @throws BusinessException 账号不属当前租户、已删除或事实写入未命中
     */
    @Transactional(rollbackFor = Exception.class)
    public KickResult recordKick(Account account, long kickedAt) {
        Long tenantId = requireAccount(account);
        if (mapper.lockAccountIds(tenantId, List.of(account.getId())).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "抢登熔断账号不存在或已删除");
        }
        var row = mapper.selectForUpdateByAccountId(account.getId());
        if (row != null && row.getTrippedAt() != null) {
            return KickResult.TRIPPED;
        }
        long now = System.currentTimeMillis();
        boolean insert = row == null;
        if (insert) {
            row = new com.armada.account.model.entity.AccountTakeoverBreaker();
            row.setTenantId(tenantId);
            row.setAccountId(account.getId());
            row.setCreatedAt(now);
        }
        if (row.getWindowStartedAt() == null || kickedAt - row.getWindowStartedAt() >= properties.getBreakerWindowMs()) {
            row.setWindowStartedAt(kickedAt);
            row.setKickCount(1);
        } else {
            row.setKickCount(row.getKickCount() + 1);
        }
        if (row.getKickCount() >= properties.getBreakerMaxKicks()) {
            row.setTrippedAt(kickedAt);
        }
        row.setUpdatedAt(now);
        int changed = insert ? mapper.insert(row) : mapper.update(row);
        if (changed != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "抢登熔断计数写入未命中");
        }
        return row.getTrippedAt() == null ? KickResult.CONTINUE : KickResult.TRIPPED;
    }

    /**
     * @param accountId 当前租户账号
     * @return 是否持久熔断中；无记录时为 false
     */
    public boolean isTripped(Long accountId) {
        if (accountId == null) {
            return false;
        }
        var row = mapper.selectByAccountId(accountId);
        return row != null && row.getTrippedAt() != null;
    }

    /**
     * 清零人工选择账号的窗口和熔断；与正在处理的被挤事件用相同账号锁串行。
     * @param accountIds 当前租户选择的账号
     * @param now 清零时间，毫秒
     */
    @Transactional(rollbackFor = Exception.class)
    public void reset(List<Long> accountIds, long now) {
        if (accountIds == null || accountIds.isEmpty()) {
            return;
        }
        List<Long> ids = accountIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            return;
        }
        List<Long> locked = mapper.lockAccountIds(requireTenantId(), ids);
        if (!locked.isEmpty()) {
            mapper.reset(locked, now);
        }
    }

    private static Long requireAccount(Account account) {
        Long tenantId = requireTenantId();
        if (account == null || account.getId() == null
                || (account.getTenantId() != null && !tenantId.equals(account.getTenantId()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "抢登熔断账号与当前租户不一致");
        }
        return tenantId;
    }

    private static Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "缺少抢登熔断租户上下文");
        }
        return tenantId;
    }
}
