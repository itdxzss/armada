package com.armada.task.scheduler;

import com.armada.account.service.AccountService;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskCreationMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 简化执行模式完成时归档已入群拉手；简化新群同时归档接管管理。 */
@Service
public class PullTaskDirectLinkFinishArchiveService {

    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final AccountService accountService;

    public PullTaskDirectLinkFinishArchiveService(
            PullTaskGroupAccountMapper groupAccountMapper, AccountService accountService) {
        this.groupAccountMapper = groupAccountMapper;
        this.accountService = accountService;
    }

    /**
     * 在收口事务中先迁移账号，再由调用方释放拉手租约。
     *
     * <p>已释放的历史拉手可能正服务其他执行行，不再迁移；仅选号但未确认入群的账号也不算
     * 已使用。跨域转组复用账号服务的租户、软删、占用和并发更新检查，失败向外抛出以回滚收口。
     * 已处于目标分组时账号服务按成功处理。</p>
     *
     * @param executionId 正在正常收口的执行行 ID
     * @param setting 任务冻结配置；未选择完成分组时不迁移
     * @param mode 冻结创建模式，只有简化新群额外处理管理完成分组
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void archive(long executionId, PullTaskStandardSetting setting, PullTaskCreationMode mode) {
        if (setting == null) {
            return;
        }
        List<PullTaskGroupAccountRole> roles = mode.isSimplifiedNewGroup()
                ? List.of(PullTaskGroupAccountRole.MANAGER, PullTaskGroupAccountRole.PULLER)
                : List.of(PullTaskGroupAccountRole.PULLER);
        for (PullTaskGroupAccountRole role : roles) {
            Long targetGroup = role == PullTaskGroupAccountRole.MANAGER
                    ? setting.getManagerFinishGroupId() : setting.getPullerFinishGroupId();
            if (targetGroup == null) {
                continue;
            }
            List<Long> accountIds = groupAccountMapper.selectByExecutionAndRole(executionId, role.code()).stream()
                    .filter(row -> row.getReleasedAt() == null && row.getJoinedAt() != null)
                    .map(PullTaskGroupAccount::getAccountId)
                    .distinct()
                    .sorted()
                    .toList();
            if (!accountIds.isEmpty()) {
                accountService.migrateGroup(accountIds, targetGroup);
            }
        }
    }
}
