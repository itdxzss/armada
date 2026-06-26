package com.armada.task.model.vo;

import java.util.List;

/**
 * 进群任务详情出参 VO(详情页 + 编辑回填用,camelCase wire)。
 *
 * <p>accountGroupIds / selectedAccountIds 由库内 JSON 快照列解析回 List,供前端编辑回填;
 * status / distributionMode 存英文码,中文展示前端映射;时间列 epoch 毫秒 UTC。
 * 字段较多,逐属性内联注释业务含义。</p>
 */
public record JoinTaskDetailVO(

        /** 任务 ID。 */
        Long id,

        /** 任务名称。 */
        String name,

        /** 选中账号分组 id(由 JSON 快照解析)。 */
        List<Long> accountGroupIds,

        /** 账号分组名快照(以 "/" 连接)。 */
        String accountGroupNames,

        /** 选中账号 id(由 JSON 快照解析,编辑回填)。 */
        List<Long> selectedAccountIds,

        /** 进群链接输入框原始文本(编辑回填)。 */
        String linksText,

        /** 分配方式英文码(FIXED_ACCOUNTS_PER_LINK / FIXED_ACCOUNT_MULTI_LINK)。 */
        String distributionMode,

        /** 方式一:每链接账号数。 */
        int accountsPerLink,

        /** 方式二:参与执行账号数。 */
        int executorAccountCount,

        /** 方式二:每账号链接数。 */
        int linksPerAccount,

        /** 方式一进群间隔下限(秒)。 */
        int fixedIntervalMinSec,

        /** 方式一进群间隔上限(秒)。 */
        int fixedIntervalMaxSec,

        /** 方式二进群间隔下限(秒)。 */
        int multiIntervalMinSec,

        /** 方式二进群间隔上限(秒)。 */
        int multiIntervalMaxSec,

        /** 进群间隔展示标签(如 "10-20s")。 */
        String intervalLabel,

        /** 是否失败自动重试。 */
        boolean retryEnabled,

        /** 重试次数上限。 */
        int retryLimit,

        /** 失败处理策略快照。 */
        String failurePolicy,

        /** 计划进群次数(= PENDING 计划行数)。 */
        int total,

        /** 已执行次数(引擎回写)。 */
        int executed,

        /** 成功进群数(引擎回写)。 */
        int success,

        /** 失败数(引擎回写)。 */
        int failed,

        /** 待执行数。 */
        int pending,

        /** 任务状态英文码(DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED)。 */
        String status,

        /** 创建人 user_id(暂无鉴权上下文,恒 null)。 */
        Long createdBy,

        /** 创建时间(epoch 毫秒,UTC)。 */
        Long createdAt,

        /** 更新时间(epoch 毫秒,UTC)。 */
        Long updatedAt) {
}
