package com.armada.group.service;

import com.armada.group.model.dto.GroupLinkLabelDTO;
import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * WS链接分组业务接口。
 */
public interface GroupLinkLabelService {

    /**
     * 分组列表(分页 + 筛选)。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    PageResult<GroupLinkLabelVO> list(GroupLinkLabelQuery query);

    /**
     * 新建分组;同名软删分组自动复活。
     *
     * @param dto 分组信息
     * @return 新建/复活的分组 VO
     */
    GroupLinkLabelVO create(GroupLinkLabelDTO dto);

    /**
     * 修改分组基本信息。
     *
     * @param id  分组 ID
     * @param dto 更新内容
     */
    void update(Long id, GroupLinkLabelDTO dto);

    /**
     * 批量软删分组(级联软删群链接+导入批次)。
     *
     * @param ids 分组 ID 列表(1..100)
     * @return 实际删除的分组数
     */
    int batchDelete(List<Long> ids);
}
