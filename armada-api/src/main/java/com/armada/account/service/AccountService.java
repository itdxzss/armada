package com.armada.account.service;

import java.util.List;

/**
 * 账号业务接口(账号列表菜单)。
 *
 * <p>step1 仅暴露迁移分组与批量删除两个变更方法;list/stats 方法由 1.3.4 继续补充。</p>
 */
public interface AccountService {

    /**
     * 批量迁移分组:将指定账号迁移到目标分组。
     *
     * <p>实现要点:① ids 非空;② 目标分组存在(软删的不算);③ UPDATE account SET account_group_id。
     * 新建分组由 Controller 先调 AccountGroupService.create 获取 id 再传入,本方法只接受已存在的 groupId。</p>
     *
     * @param ids            账号 ID 列表(1..N)
     * @param accountGroupId 目标分组 ID(必须已存在且未软删)
     * @throws com.armada.shared.exception.BusinessException 当 ids 为空或目标分组不存在时
     */
    void migrateGroup(List<Long> ids, Long accountGroupId);

    /**
     * 严格口径批量软删除账号(全或无)。
     *
     * <p>口径:账号 account_state 必须 ∈ {封禁=3, 导出=4, 解绑=5} 且 dispatched_at IS NULL(不在任务中)。
     * 任一账号不满足 → 整批抛 VALIDATION 并回报违规账号 id,不调 batchSoftDelete。</p>
     *
     * <p>step1 导入态 account_state=NULL → 拒删;memory 中 test_data_cleanup 走 DB 硬删,不走本方法。</p>
     *
     * @param ids 账号 ID 列表(1..N)
     * @throws com.armada.shared.exception.BusinessException 当任意账号不符合删除条件时
     */
    void batchDelete(List<Long> ids);
}
