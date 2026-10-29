package com.armada.account.wbcompanion.model.dto;

import java.util.List;

/**
 * 发起"登录到 WB 分身"。
 *
 * @param accountIds 安卓协议账号 ID，单个或批量，最多 200 个；每个号各自串行，号与号之间并行
 * @param wbEnvId 目标 WB 环境标识，必须是本环境配置过的目标
 * @param wbMemberId WB 会员 ID，来自会员查询
 * @param wbUsername WB 会员用户名，只作展示快照
 * @param targetCount 目标连接数 1-4（含 WB 上已有的连接），第 1 条是主设备，其余是影子
 */
public record WbCompanionCreateDTO(
        List<Long> accountIds,
        String wbEnvId,
        Long wbMemberId,
        String wbUsername,
        Integer targetCount) {
}
