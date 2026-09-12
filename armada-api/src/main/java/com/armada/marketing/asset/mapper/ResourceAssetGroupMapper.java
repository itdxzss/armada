package com.armada.marketing.asset.mapper;

import com.armada.marketing.asset.model.entity.ResourceAssetGroup;
import com.armada.marketing.asset.model.vo.ResourceAssetGroupVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 素材分组及归属 SQL，所有操作由租户插件隔离。 */
@Mapper
public interface ResourceAssetGroupMapper {
    /** @return 当前租户按名称排序的分组，包含空分组 */
    List<ResourceAssetGroupVO> selectAll(@Param("scope") int scope);
    /** @param group 新分组 @return 插入行数 */
    int insert(ResourceAssetGroup group);
    /** @param id 分组 ID @return 加锁后的 ID；不可访问时为空 */
    Long lockById(@Param("id") Long id, @Param("scope") int scope);
    /** @param id 分组 ID @return 删除行数 */
    int delete(@Param("id") Long id);
    /** @param groupId 已加锁的分组 @param scope 当前业务 @return 解除归属的数量 */
    int ungroup(@Param("groupId") Long groupId, @Param("scope") int scope);
    /** @param ids 已加锁素材 @param groupId 已验证的目标分组 @param now 修改时间 @return 更新数量 */
    int move(@Param("ids") List<Long> ids, @Param("groupId") Long groupId,
             @Param("now") long now, @Param("scope") int scope);
    /** @param ids 素材 ID @param scope 当前业务 @return 解除本业务归属数 */
    int deleteRefs(@Param("ids") List<Long> ids, @Param("scope") int scope);
}
