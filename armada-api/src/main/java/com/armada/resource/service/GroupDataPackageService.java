package com.armada.resource.service;

import com.armada.resource.model.dto.GroupDataPackageCreateDTO;
import com.armada.resource.model.dto.GroupDataPackageUpdateDTO;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import com.armada.resource.model.dto.GroupDataPackagePhoneQuery;
import com.armada.resource.model.vo.GroupDataPackageVO;
import com.armada.resource.model.vo.GroupDataPackagePhoneVO;
import com.armada.shared.response.PageResult;

/** 独立拉群数据包管理。 */
public interface GroupDataPackageService {
    /** 数据库筛选与分页，读取真实状态投影。 */ PageResult<GroupDataPackageVO> list(GroupDataPackageQuery query);
    /** 读取当前租户资源详情。 */ GroupDataPackageVO detail(long id);
    /** 创建空数据包。 */ GroupDataPackageVO create(GroupDataPackageCreateDTO request, long userId);
    /** 乐观锁编辑名称备注。 */ GroupDataPackageVO update(long id, GroupDataPackageUpdateDTO request);
    /** 当前代号码明细。 */ PageResult<GroupDataPackagePhoneVO> phones(long id, GroupDataPackagePhoneQuery query);
    /** 只回收已终结任务的明确可重试失败。 */ int resetFailed(long id);
    /** 无活动引用后软删，保留号码和任务历史。 */ void delete(long id);
}
