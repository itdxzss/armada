package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkImportBatch;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群链接导入批次数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface GroupLinkImportBatchMapper {

    /**
     * 插入新导入批次(id/tenant_id 由库或拦截器注入,时间由调用方传入)。
     *
     * @param row 批次实体
     * @return 影响行数
     */
    int insert(GroupLinkImportBatch row);

    /**
     * 回写批次统计计数(total/inserted/adopted/skipped/failed)。
     *
     * @param row 含 id 及各计数字段
     * @return 影响行数
     */
    int updateCounts(GroupLinkImportBatch row);

    /**
     * 按所属分组 ID 批量软删除导入批次(分组被删时级联调用)。
     *
     * @param labelIds 分组 ID 列表
     * @return 更新行数
     */
    int softDeleteByLabelIds(@Param("ids") List<Long> labelIds, @Param("deletedAt") long deletedAt);
}
