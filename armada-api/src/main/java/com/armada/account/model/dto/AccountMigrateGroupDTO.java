package com.armada.account.model.dto;

import java.util.List;

/**
 * 批量迁移分组请求体。
 *
 * <p>两种用法二选一:
 * <ul>
 *   <li>迁移到已有分组:提供 {@code accountGroupId}(非 null),忽略 newGroupName/newGroupRemark。</li>
 *   <li>先建新分组再迁移:accountGroupId=null + newGroupName 非空,Controller 先调
 *       AccountGroupService.create 取 id,再迁移。</li>
 * </ul>
 * </p>
 *
 * @param ids            账号 ID 列表(1..N)
 * @param accountGroupId 目标分组 ID(与 newGroupName 二选一)
 * @param newGroupName   新建分组名称(accountGroupId=null 时生效)
 * @param newGroupRemark 新建分组备注(可选)
 */
public record AccountMigrateGroupDTO(
        List<Long> ids,
        Long accountGroupId,
        String newGroupName,
        String newGroupRemark
) {
}
