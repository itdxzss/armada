package com.armada.group.service;

import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 群组运营分组业务。 */
public interface GroupFolderService {

    /** 分页查询有效分组。 */
    PageResult<GroupFolderVO> list(GroupFolderQuery query);

    /** 查询有效分组下拉选项。 */
    List<GroupFolderOptionVO> options();

    /** 新建分组；同名软删数据存在时复活原记录。 */
    GroupFolderVO create(GroupFolderWriteDTO request, long userId);

    /** 修改分组名称。 */
    void update(long id, GroupFolderWriteDTO request);

    /** 解除群入口归属并批量软删除分组。 */
    GroupFolderDeleteVO batchDelete(List<Long> ids);

    /** 校验分组存在，并返回只读快照供其他业务域使用。 */
    GroupFolderOptionVO requireExisting(long id);

    /** 查询分组内当前可用且未封禁的邀请链接；不返回内部群入口。 */
    List<String> usableLinks(long id);
}
