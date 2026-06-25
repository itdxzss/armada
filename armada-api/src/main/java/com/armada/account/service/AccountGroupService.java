package com.armada.account.service;

import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.vo.AccountGroupVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * 账号分组业务接口。
 */
public interface AccountGroupService {

    /**
     * 分组列表(分页 + 筛选)。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    PageResult<AccountGroupVO> list(AccountGroupQuery query);

    /**
     * 新建分组;同名软删分组自动复活。
     *
     * @param dto 分组信息
     * @return 新建/复活的分组 VO
     */
    AccountGroupVO create(AccountGroupDTO dto);

    /**
     * 修改分组基本信息(系统内置分组不可改名)。
     *
     * @param id  分组 ID
     * @param dto 更新内容
     */
    void update(Long id, AccountGroupDTO dto);

    /**
     * 批量软删除分组(全或无闸门:组内有账号或命中系统组则整批拒删)。
     *
     * @param ids 分组 ID 列表(1..100)
     * @return 实际删除的分组数
     */
    int batchDelete(List<Long> ids);

    /**
     * 获取(或懒创建)系统默认分组,供导入/列表模块复用。
     *
     * @return 系统内置分组实体
     */
    AccountGroup ensureSystemGroup();
}
