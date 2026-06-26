package com.armada.task.model.vo;

/**
 * 进群任务列表行出参 VO(camelCase wire)。
 *
 * <p>status / distributionMode 存英文码,中文展示由前端映射。计数列中 executed/success/failed
 * 由引擎回写,建任务时恒 0;total = 实际生成的 PENDING 计划行数,pending 建时 = total。</p>
 *
 * @param id                任务 ID
 * @param name              任务名称
 * @param accountGroupNames 账号分组名快照(以 "/" 连接)
 * @param total             计划进群次数(= 实际生成的 PENDING 计划行数)
 * @param executed          已执行次数(引擎回写,建时 0)
 * @param success           成功进群数(引擎回写,建时 0)
 * @param failed            失败数(引擎回写,建时 0)
 * @param pending           待执行数(建时 = total)
 * @param intervalLabel     进群间隔展示标签(如 "10-20s")
 * @param distributionMode  分配方式英文码(FIXED_ACCOUNTS_PER_LINK / FIXED_ACCOUNT_MULTI_LINK)
 * @param failurePolicy     失败处理策略快照
 * @param retryEnabled      是否失败自动重试
 * @param retryLimit        重试次数上限
 * @param status            任务状态英文码(DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED)
 * @param createdBy         创建人 user_id(暂无鉴权上下文,恒 null)
 * @param createdAt         创建时间(epoch 毫秒,UTC)
 */
public record JoinTaskVO(
        Long id,
        String name,
        String accountGroupNames,
        int total,
        int executed,
        int success,
        int failed,
        int pending,
        String intervalLabel,
        String distributionMode,
        String failurePolicy,
        boolean retryEnabled,
        int retryLimit,
        String status,
        Long createdBy,
        Long createdAt) {
}
