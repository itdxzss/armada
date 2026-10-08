package com.armada.account.takeover;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.AccountAutoTakeoverCandidate;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 跨租户补偿被抢登和抢登中离线账号，每个账号由上线服务独立事务复核。 */
@Service
public class AccountAutoTakeoverDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AccountAutoTakeoverDispatcher.class);
    private static final String SOURCE_LOGIN_REPLACED_TAKEOVER = "login_replaced_takeover";

    private final AccountStateMapper stateMapper;
    private final AccountOnlineCommandService onlineCommands;
    private final AccountAutoTakeoverProperties properties;

    /**
     * @param stateMapper 跨租户离线候选查询
     * @param onlineCommands 按账号复核并入队的上线服务
     * @param properties 自动抢登开关与扫描上限
     */
    public AccountAutoTakeoverDispatcher(AccountStateMapper stateMapper,
            AccountOnlineCommandService onlineCommands, AccountAutoTakeoverProperties properties) {
        this.stateMapper = stateMapper;
        this.onlineCommands = onlineCommands;
        this.properties = properties;
    }

    /**
     * 扫描并尝试恢复一批离线账号，单账号失败不影响其余账号。
     *
     * @param now 本轮扫描时间(epoch 毫秒)
     * @return 实际尝试的候选数，包含被上线服务再次复核跳过或发生异常的候选
     */
    public int dispatchOnce(long now) {
        if (!properties.isEnabled()) {
            return 0;
        }
        List<AccountAutoTakeoverCandidate> candidates = stateMapper.selectAutoTakeoverCandidates(
                now - properties.getScan().getStuckTakingOverMs(), properties.getScan().getBatchSize());
        Long previousTenant = TenantContext.get();
        int attempted = 0;
        try {
            for (AccountAutoTakeoverCandidate candidate : candidates) {
                TenantContext.set(candidate.tenantId());
                attempted++;
                try {
                    if (candidate.accountState() == AccountStateCode.LOGIN_REPLACED) {
                        onlineCommands.autoTakeover(candidate.accountId());
                    } else {
                        onlineCommands.reonlineForTakeover(
                                candidate.accountId(), null, SOURCE_LOGIN_REPLACED_TAKEOVER);
                    }
                } catch (RuntimeException error) {
                    log.warn("自动抢登单账号补偿异常，等待后续扫描 tenantId={} accountId={}",
                            candidate.tenantId(), candidate.accountId(), error);
                }
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
        return attempted;
    }
}
