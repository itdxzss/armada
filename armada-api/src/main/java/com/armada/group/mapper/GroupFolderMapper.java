package com.armada.group.mapper;

import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderVoRow;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群组列表运营分组数据访问。
 */
@Mapper
public interface GroupFolderMapper {

    /** 按筛选条件统计当前租户活跃分组。 */
    long countPage(GroupFolderQuery query);

    /** 分页查询当前租户活跃分组及关联群数。 */
    List<GroupFolderVoRow> selectPage(GroupFolderQuery query);

    /** 查询当前租户全部活跃分组选择项。 */
    List<GroupFolder> selectOptions();

    /** 按名称查询活跃分组。 */
    GroupFolder selectActiveByName(@Param("name") String name);

    /** 按名称查询软删除分组。 */
    GroupFolder selectDeletedByName(@Param("name") String name);

    /** 按名称查询分组，包含软删除行。 */
    GroupFolder selectAnyByName(@Param("name") String name);

    /** 按 ID 查询活跃分组。 */
    GroupFolder selectById(@Param("id") Long id);

    /**
     * 按 ID 升序锁定当前租户的活跃分组。
     *
     * @param ids 已去重并排序的分组 ID
     * @return 当前租户内存在的活跃分组
     * @throws BusinessException 当前线程缺少租户上下文时抛出
     */
    default List<GroupFolder> selectActiveByIdsForUpdate(List<Long> ids) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectByTenantAndIdsForUpdate(tenantId, ids);
    }

    /** 使用显式租户执行锁行查询，避免租户插件改写 FOR UPDATE 尾句。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupFolder> selectByTenantAndIdsForUpdate(@Param("tenantId") Long tenantId,
                                                     @Param("ids") List<Long> ids);

    /** 插入分组并回填主键。 */
    int insert(GroupFolder row);

    /** 复活指定软删除分组并更新时间。 */
    int reviveById(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /** 修改活跃分组名称。 */
    int updateName(@Param("id") Long id,
                   @Param("name") String name,
                   @Param("updatedAt") long updatedAt);

    /** 批量软删除活跃分组。 */
    int softDeleteByIds(@Param("ids") List<Long> ids,
                        @Param("deletedAt") long deletedAt);
}
