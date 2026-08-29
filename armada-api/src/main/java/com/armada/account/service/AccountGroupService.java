package com.armada.account.service;

import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.model.vo.AccountGroupMarketingOccupancyVO;
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
     * 查询分组当前营销整组占用详情。
     *
     * @param groupId 分组 ID
     * @return 当前占用详情；空闲分组返回 FREE
     */
    AccountGroupMarketingOccupancyVO marketingOccupancy(Long groupId);

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
     * 将一个非系统分组的全部账号平均拆入指定数量的新分组，并删除原分组。
     *
     * @param groupId 原分组 ID
     * @param groupCount 新分组数量
     */
    void split(Long groupId, Integer groupCount);

    /**
     * 按传入顺序合并非系统分组，首个分组保留为主分组。
     *
     * @param groupIds 按用户勾选顺序排列的分组 ID
     */
    void merge(List<Long> groupIds);

    /**
     * 获取(或懒创建)系统默认分组,供导入/列表模块复用。
     *
     * @return 系统内置分组实体
     */
    AccountGroup ensureSystemGroup();

    /** 当前租户活跃账号分组选项；只读，不触发默认分组懒创建。 */
    List<AccountGroupOptionVO> options();

    /**
     * 断言分组存在并返回分组实体;不存在则抛 NOT_FOUND 业务异常。
     *
     * <p>用于导入前置校验:调用方传入了 accountGroupId 时须确认该组确实存在,
     * 防止账号入库后出现悬空引用(无审计锚点的孤儿账号)。</p>
     *
     * @param id 分组主键(调用方保证非 null)
     * @return 对应的活跃分组实体
     * @throws com.armada.shared.exception.BusinessException 当分组不存在时抛 NOT_FOUND
     */
    AccountGroup requireExisting(Long id);
}
