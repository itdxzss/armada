package com.armada.account.model.vo;
/** SQL 统计，待查包含查询中；取消项只计入总数。 */
public record AccountTierCheckStatsVO(int total, int success, int failed, int unqueryable, int pending, int first, int same, int up, int down, int unknown, int lost) {}
