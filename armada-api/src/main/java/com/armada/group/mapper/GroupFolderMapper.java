package com.armada.group.mapper;

import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群组运营分组数据访问。 */
@Mapper
public interface GroupFolderMapper {

    /** 统计符合筛选条件的有效分组。 */
    long countPage(GroupFolderQuery query);

    /** 分页查询分组和可用于拉群的群链接数量。 */
    List<GroupFolderVO> selectPage(GroupFolderQuery query);

    /** 查询全部有效分组下拉选项。 */
    List<GroupFolderOptionVO> selectOptions();

    /** 按 ID 查询有效分组。 */
    GroupFolder selectActiveById(@Param("id") long id);

    /** 按名称查询有效分组。 */
    GroupFolder selectActiveByName(@Param("name") String name);

    /** 按名称查询已删除分组，供复活使用。 */
    GroupFolder selectDeletedByName(@Param("name") String name);

    /** 按名称查询分组，包含软删除行。 */
    GroupFolder selectAnyByName(@Param("name") String name);

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

    /** 使用显式租户执行锁行查询，避免租户插件改写 FOR UPDATE 尾句。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupFolder> selectByTenantAndIdsForUpdate(@Param("tenantId") Long tenantId,
                                                     @Param("ids") List<Long> ids);

    /** 新增分组。 */
    int insert(GroupFolder row);

    /** 复活并重命名已删除分组。 */
    int revive(GroupFolder row);

    /** 更新有效分组名称。 */
    int updateName(GroupFolder row);

    /** 解除群入口与指定分组的运营归属，不删除群入口。 */
    int clearGroupLinks(@Param("ids") List<Long> ids, @Param("updatedAt") long updatedAt);

    /** 批量软删除有效分组。 */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);

    /** 查询分组内当前可用且未封禁的非空链接。 */
    List<String> selectUsableLinks(@Param("folderId") long folderId);
}
