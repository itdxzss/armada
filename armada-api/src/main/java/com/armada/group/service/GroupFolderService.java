package com.armada.group.service;

import com.armada.group.model.dto.GroupFolderDTO;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.vo.GroupFolderDeleteResultVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * 群组列表运营分组业务服务。
 */
public interface GroupFolderService {

    /** 分页查询当前租户的运营分组。 */
    PageResult<GroupFolderVO> list(GroupFolderQuery query);

    /** 查询当前租户全部活跃运营分组选择项。 */
    List<GroupFolderOptionVO> options();

    /** 新建运营分组；同名软删除记录会被复活。 */
    GroupFolderVO create(GroupFolderDTO dto);

    /** 修改运营分组名称。 */
    void update(Long id, GroupFolderDTO dto);

    /** 批量删除运营分组并将关联群组移入未分组。 */
    GroupFolderDeleteResultVO batchDelete(List<Long> ids);
}
