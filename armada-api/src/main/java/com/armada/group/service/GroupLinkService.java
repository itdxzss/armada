package com.armada.group.service;

import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * 群链接业务接口(分页列表、迁移分组、批量删除)。
 */
public interface GroupLinkService {

    /**
     * 按WS链接分组分页查询群链接。
     *
     * @param query 查询条件(含 labelId/keyword/page/pageSize)
     * @return 分页结果
     */
    PageResult<GroupLinkVO> listByLabel(GroupLinkQuery query);

    /**
     * 批量迁移群链接到目标分组。
     *
     * <p>校验目标分组存在、linkIds 全部活跃,二者均通过后执行迁移。</p>
     *
     * @param linkIds       待迁移的群链接 ID 列表
     * @param targetLabelId 目标WS链接分组 ID
     * @return 实际迁移行数
     */
    int migrate(List<Long> linkIds, Long targetLabelId);

    /**
     * 批量软删除群链接。
     *
     * @param ids 群链接 ID 列表(1..100)
     * @return 实际删除行数
     */
    int batchDelete(List<Long> ids);
}
