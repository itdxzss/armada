package com.armada.task.model.dto;

import java.util.List;

/**
 * 建进群任务入参(camelCase wire)。
 *
 * <p>选中账号由前端 id+号码双填(account_id 权威、号码串展示),后端不回查账号表。分配方式相关数值按
 * {@code distributionMode} 取用:方式一用 accountsPerLink + fixedInterval*,方式二用 executorAccountCount
 * + linksPerAccount + multiInterval*;未用到的数值字段允许为 null,服务层归一为 0。</p>
 *
 * @param name                 任务名称(必填,空白抛 VALIDATION)
 * @param accountGroupIds      选中账号分组 id(快照,落 account_group_ids JSON)
 * @param accountGroupNames    账号分组名(快照,以 "/" 连接落 account_group_names,免 JOIN 展示)
 * @param selectedAccounts     选中执行账号(accountId+phone 双填)
 * @param linksText            进群链接输入框原始文本(按行拆分分类,原文回填编辑)
 * @param distributionMode     分配方式:FIXED_ACCOUNTS_PER_LINK 每链接固定账号数 / FIXED_ACCOUNT_MULTI_LINK 固定账号多链接
 * @param accountsPerLink      方式一:每条群链接分配账号数
 * @param executorAccountCount 方式二:参与执行账号数
 * @param linksPerAccount      方式二:每账号进群链接数
 * @param fixedIntervalMinSec  方式一进群间隔下限(秒)
 * @param fixedIntervalMaxSec  方式一进群间隔上限(秒)
 * @param multiIntervalMinSec  方式二进群间隔下限(秒)
 * @param multiIntervalMaxSec  方式二进群间隔上限(秒)
 * @param retryEnabled         失败是否自动重试
 * @param retryLimit           重试次数上限
 * @param failurePolicy        失败处理策略快照(标签/JSON,编辑回填)
 */
public record CreateJoinTaskRequest(
        String name,
        List<Long> accountGroupIds,
        List<String> accountGroupNames,
        List<SelectedAccount> selectedAccounts,
        String linksText,
        String distributionMode,
        Integer accountsPerLink,
        Integer executorAccountCount,
        Integer linksPerAccount,
        Integer fixedIntervalMinSec,
        Integer fixedIntervalMaxSec,
        Integer multiIntervalMinSec,
        Integer multiIntervalMaxSec,
        Boolean retryEnabled,
        Integer retryLimit,
        String failurePolicy) {
}
