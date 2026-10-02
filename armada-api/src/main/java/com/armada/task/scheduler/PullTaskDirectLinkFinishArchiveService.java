package com.armada.task.scheduler;

import com.armada.account.service.AccountService;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 新群链接模式完成时，将本执行行仍占用的已入群拉手归入完成分组。 */
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
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void archive(long executionId, PullTaskStandardSetting setting) {
        if (setting == null || setting.getPullerFinishGroupId() == null) {
            return;
        }
        List<Long> accountIds = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code()).stream()
                .filter(row -> row.getReleasedAt() == null && row.getJoinedAt() != null)
                .map(PullTaskGroupAccount::getAccountId)
                .distinct()
                .sorted()
                .toList();
        if (!accountIds.isEmpty()) {
            accountService.migrateGroup(accountIds, setting.getPullerFinishGroupId());
        }
    }
}
