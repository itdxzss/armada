package com.armada.group.mapper;

import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.model.vo.GroupPoolResourceVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群组列表运营分组数据访问。 */
@Mapper
public interface GroupFolderMapper {

    /** 按筛选条件统计当前租户的有效分组。 */
    long countPage(GroupFolderQuery query);

    /** 分页查询分组及当前可用于普通拉群的群链接数量。 */
    List<GroupFolderVO> selectPage(GroupFolderQuery query);

    /** 查询当前租户全部有效分组选项。 */
    List<GroupFolderOptionVO> selectOptions();

    /** 按名称查询有效分组。 */
    GroupFolder selectActiveByName(@Param("name") String name);

    /** 按名称查询软删除分组，供复活使用。 */
    GroupFolder selectDeletedByName(@Param("name") String name);

    /** 按名称查询分组，包含软删除行。 */
    GroupFolder selectAnyByName(@Param("name") String name);

    /** 按 ID 查询有效分组。 */
    GroupFolder selectById(@Param("id") long id);

    /**
     * 按 ID 升序锁定当前租户的有效分组。
     *
     * @param ids 已去重并排序的分组 ID
     * @return 当前租户内存在的有效分组
     * @throws BusinessException 当前线程缺少租户上下文时抛出
     */
    default List<GroupFolder> selectActiveByIdsForUpdate(List<Long> ids) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectByTenantAndIdsForUpdate(tenantId, ids);
    }

    /** 使用显式租户执行锁行查询，避免租户插件改写 {@code FOR UPDATE} 尾句。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupFolder> selectByTenantAndIdsForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("ids") List<Long> ids);

    /** 新增分组。 */
    int insert(GroupFolder row);

    /** 幂等创建或复活当前租户的系统“已使用群组”。 */
    int upsertUsedSystemFolder(
            @Param("name") String name,
            @Param("now") long now);

    /** 复活并更新指定软删除分组。 */
    int revive(GroupFolder row);

    /** 修改有效分组名称。 */
    int updateName(
            @Param("id") long id,
            @Param("name") String name,
            @Param("updatedAt") long updatedAt);

    /** 批量软删除有效分组。 */
    int softDeleteByIds(
            @Param("ids") List<Long> ids,
            @Param("deletedAt") long deletedAt);

    /** 查询分组内当前健康、未封禁的邀请链接；内部群入口按预览邀请码转换。 */
    List<String> selectUsableLinks(@Param("folderId") long folderId);

    /** 查询资源池内当前可领取的群组，按群组 ID 升序。 */
    List<GroupPoolResourceVO> selectUsableResources(@Param("folderId") long folderId);

    /** 锁定并复核指定群组仍属于资源池且当前可用。 */
    default GroupPoolResourceVO selectUsableResourceForUpdate(long folderId, long groupLinkId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectByTenantFolderAndResourceForUpdate(tenantId, folderId, groupLinkId);
    }

    /** 显式租户锁行查询，避免租户插件改写 {@code FOR UPDATE} 尾句。 */
    @InterceptorIgnore(tenantLine = "true")
    GroupPoolResourceVO selectByTenantFolderAndResourceForUpdate(
            @Param("tenantId") long tenantId,
            @Param("folderId") long folderId,
            @Param("groupLinkId") long groupLinkId);
}
