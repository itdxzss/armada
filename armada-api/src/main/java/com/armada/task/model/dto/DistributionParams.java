package com.armada.task.model.dto;

/**
 * 计划行生成的分配参数(把 PlanRowGenerator.generate 的散参收拢,满足参数≤5)。
 *
 * @param mode                 分配方式英文码(FIXED_ACCOUNTS_PER_LINK / FIXED_ACCOUNT_MULTI_LINK)
 * @param accountsPerLink      方式一:每链接账号数
 * @param executorAccountCount 方式二:参与执行账号数
 * @param linksPerAccount      方式二:每账号链接数
 */
public record DistributionParams(
        String mode,
        int accountsPerLink,
        int executorAccountCount,
        int linksPerAccount) {
}
