package com.armada.group.mapper;

import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * WS链接分组数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface GroupLinkLabelMapper {

    /** 分页总数(与 selectPage 共用 filter,口径一致)。 */
    long countPage(GroupLinkLabelQuery query);

    /** 分页列表,含聚合活跃链接数。 */
    List<GroupLinkLabelVoRow> selectPage(GroupLinkLabelQuery query);

    /** 按名称查活跃分组(校验重名/复活判断)。 */
    GroupLinkLabel selectActiveByName(@Param("name") String name);

    /** 按名称查软删分组(复活场景)。 */
    GroupLinkLabel selectDeletedByName(@Param("name") String name);

    /** 按 ID 查活跃分组。 */
    GroupLinkLabel selectById(@Param("id") Long id);

    /** 插入新分组(id/tenant_id 由库或拦截器注入,时间由调用方传入)。 */
    int insert(GroupLinkLabel row);

    /** 复活软删分组(deleted_at=NULL)。 */
    int reviveById(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /** 更新分组基本信息(name/region/remark)。 */
    int updateProfile(GroupLinkLabel row);

    /** 批量软删除。 */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
