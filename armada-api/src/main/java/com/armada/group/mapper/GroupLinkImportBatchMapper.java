package com.armada.group.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群链接导入批次数据访问。
 *
 * <p>本阶段(Phase 2)仅放 {@code softDeleteByLabelIds},用于分组批量删除时级联软删批次。
 * Phase 3 会追加 insert / updateCounts 等方法。</p>
 */
@Mapper
public interface GroupLinkImportBatchMapper {

    /**
     * 按所属分组 ID 批量软删除导入批次(分组被删时级联调用)。
     *
     * @param labelIds 分组 ID 列表
     * @return 更新行数
     */
    int softDeleteByLabelIds(@Param("ids") List<Long> labelIds);
}
