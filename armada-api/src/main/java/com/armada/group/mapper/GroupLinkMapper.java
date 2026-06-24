package com.armada.group.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群链接数据访问。
 *
 * <p>本阶段(Phase 2)仅放 {@code softDeleteByLabelIds},用于分组批量删除时级联软删群链接。
 * Phase 3 会追加 upsert / 列表 / 迁移等方法。</p>
 */
@Mapper
public interface GroupLinkMapper {

    /**
     * 按所属分组 ID 批量软删除群链接(分组被删时级联调用)。
     *
     * @param labelIds 分组 ID 列表
     * @return 更新行数
     */
    int softDeleteByLabelIds(@Param("ids") List<Long> labelIds);
}
