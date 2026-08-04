package com.armada.group.service;

import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 群组运营分组业务服务。 */
public interface GroupFolderService {

    /** 分页查询当前租户的有效运营分组。 */
    PageResult<GroupFolderVO> list(GroupFolderQuery query);

    /** 查询当前租户的有效运营分组选项。 */
    List<GroupFolderOptionVO> options();

    /** 新建运营分组；同名软删除记录存在时复活原记录。 */
    GroupFolderVO create(GroupFolderWriteDTO request, long userId);

    /** 修改运营分组名称。 */
    void update(long id, GroupFolderWriteDTO request);

    /** 批量删除运营分组并解除群入口归属。 */
    GroupFolderDeleteVO batchDelete(List<Long> ids);

    /** 校验分组存在，并返回跨业务域使用的只读快照。 */
    GroupFolderOptionVO requireExisting(long id);

    /** 查询分组内当前健康、未封禁且链接非空的群链接。 */
    List<String> usableLinks(long id);
}
