package com.armada.account.service.impl;

import com.armada.account.mapper.AccountProfileMapper;
import com.armada.account.service.AccountProfileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 校验画像事实并委托数据库按各自水位原子写入。 */
@Service
public class AccountProfileServiceImpl implements AccountProfileService {

    private static final Set<Integer> ROTATION_STATUSES = Set.of(0, 1, 2, 3);
    private static final Set<Integer> REGISTRATION_SOURCES = Set.of(1, 2, 3);
    private static final Set<Integer> MARKETING_SOURCES = Set.of(0, 1, 2, 3, 4);

    private final AccountProfileMapper mapper;
    private final Clock clock;

    /**
     * 构造生产画像服务，落库时间使用 UTC 系统时钟。
     *
     * @param mapper 账号画像 Mapper
     */
    @Autowired
    public AccountProfileServiceImpl(AccountProfileMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    AccountProfileServiceImpl(AccountProfileMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void updateFriendCount(long accountId, int friendCount, long syncedAt) {
        long tenantId = tenantId(accountId, syncedAt);
        if (friendCount < 0) {
            throw validation("好友数不能为负数");
        }
        mapper.upsertFriendCount(tenantId, accountId, friendCount, syncedAt, clock.millis());
    }

    @Override
    public void updateGroupInviteAllowed(long accountId, boolean allowed, long syncedAt) {
        long tenantId = tenantId(accountId, syncedAt);
        mapper.upsertGroupInviteAllowed(
                tenantId, accountId, allowed, syncedAt, clock.millis());
    }

    @Override
    public void updateRotationStatus(long accountId, int status, long updatedAt) {
        long tenantId = tenantId(accountId, updatedAt);
        if (!ROTATION_STATUSES.contains(status)) {
            throw validation("轮号状态非法");
        }
        mapper.upsertRotationStatus(tenantId, accountId, status, updatedAt, clock.millis());
    }

    @Override
    public void initializeRegistration(long accountId, long registeredAt, int source) {
        long tenantId = tenantId(accountId, registeredAt);
        if (!REGISTRATION_SOURCES.contains(source)) {
            throw validation("注册时间来源非法");
        }
        mapper.initializeRegistration(tenantId, accountId, registeredAt, source, clock.millis());
    }

    @Override
    public void updateMarketingSource(long accountId, int source, long updatedAt) {
        long tenantId = tenantId(accountId, updatedAt);
        if (!MARKETING_SOURCES.contains(source)) {
            throw validation("营销来源非法");
        }
        mapper.upsertMarketingSource(tenantId, accountId, source, updatedAt, clock.millis());
    }

    private long tenantId(long accountId, long factAt) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw validation("账号画像写入缺少租户上下文");
        }
        if (accountId < 1) {
            throw validation("账号 ID 必须大于 0");
        }
        if (factAt < 0) {
            throw validation("画像事实时间不能为负数");
        }
        return tenantId;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
