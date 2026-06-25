package com.armada.account.service.impl;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountDeleteGateRow;
import com.armada.account.service.AccountService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号业务实现(账号列表菜单)。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成,本类不手写 tenant_id。</p>
 * <p>时间字段为 BIGINT epoch 毫秒,update 时由调用方(本类)显式传入。</p>
 */
@Service
public class AccountServiceImpl implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);

    /**
     * 可删除账号状态集合:封禁(3)/ 导出(4)/ 解绑(5)。
     * 对应 account_state 表 account_state 列口径,禁止魔法值直接散落。
     */
    private static final Set<Integer> DELETABLE_STATES = Set.of(3, 4, 5);

    private final AccountMapper accountMapper;
    private final AccountGroupMapper accountGroupMapper;

    public AccountServiceImpl(AccountMapper accountMapper, AccountGroupMapper accountGroupMapper) {
        this.accountMapper = accountMapper;
        this.accountGroupMapper = accountGroupMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:① ids 非空校验;② 目标分组存在(selectById 非空,否则 NOT_FOUND);
     * ③ migrateGroup(ids, accountGroupId, now) 批量 UPDATE。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void migrateGroup(List<Long> ids, Long accountGroupId) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (accountGroupMapper.selectById(accountGroupId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标分组不存在: " + accountGroupId);
        }
        long now = System.currentTimeMillis();
        int updated = accountMapper.migrateGroup(ids, accountGroupId, now);
        log.info("账号批量迁移分组 groupId={} count={} ids={}", accountGroupId, updated, ids);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:① ids 非空;② selectStatesByIds 取每号状态;
     * ③ 逐个校验 accountState ∈ DELETABLE_STATES 且 dispatchedAt IS NULL;
     * ④ 任一不满足 → 整批抛 VALIDATION(全或无,不调 batchSoftDelete);
     * ⑤ 全通过才 batchSoftDelete(ids, now)。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        List<AccountDeleteGateRow> rows = accountMapper.selectStatesByIds(ids);
        // 全或无:先全量校验,任一不满足整批拒删
        for (AccountDeleteGateRow row : rows) {
            if (!isDeletable(row)) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "仅导出/封禁/解绑状态且不在任务的账号可删除(账号 " + row.getId() + " 不满足条件)");
            }
        }
        long now = System.currentTimeMillis();
        int deleted = accountMapper.batchSoftDelete(ids, now);
        log.info("账号批量软删除 count={} ids={}", deleted, ids);
    }

    /**
     * 判断单个账号是否满足严格删除口径:
     * accountState ∈ {3,4,5} 且 dispatchedAt IS NULL。
     */
    private boolean isDeletable(AccountDeleteGateRow row) {
        return row.getAccountState() != null
                && DELETABLE_STATES.contains(row.getAccountState())
                && row.getDispatchedAt() == null;
    }
}
