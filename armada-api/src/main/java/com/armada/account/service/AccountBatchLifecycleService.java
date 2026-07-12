package com.armada.account.service;

import com.armada.account.model.dto.AccountBatchPreviewDTO;
import com.armada.account.model.dto.AccountBatchQueryDTO;
import com.armada.account.model.vo.AccountBatchCommandResultVO;
import com.armada.account.model.vo.AccountBatchPreviewVO;
import com.armada.shared.exception.BusinessException;
import java.util.List;

/**
 * 账号批量生命周期编排服务。
 *
 * <p>负责把明确 ID 或账号列表筛选条件转换为现有上线、下线命令服务可安全处理的小批次。
 * 本接口只返回 outbox 命令受理汇总，不代表账号已经完成最终登录或离线。</p>
 */
public interface AccountBatchLifecycleService {

    /**
     * 预估明确 ID 或后端查询范围的批量操作数量。
     *
     * @param request 显式操作类型和范围
     * @return 匹配、预计执行和跳过数量
     * @throws BusinessException 当操作类型、范围或 ID 参数不合法时抛出
     */
    AccountBatchPreviewVO preview(AccountBatchPreviewDTO request);

    /**
     * 对明确选择的账号发起批量登录。
     *
     * @param ids 当前租户账号 ID，最多 2,000 个
     * @return 所有内部批次的聚合结果
     * @throws BusinessException 当 ID 为空、重复、超过上限或不属于当前租户时抛出
     */
    AccountBatchCommandResultVO onlineByIds(List<Long> ids);

    /**
     * 对明确选择的账号发起批量离线。
     *
     * @param ids 当前租户账号 ID，最多 2,000 个
     * @return 所有内部批次的聚合结果
     * @throws BusinessException 当 ID 为空、重复、超过上限或不属于当前租户时抛出
     */
    AccountBatchCommandResultVO offlineByIds(List<Long> ids);

    /**
     * 对符合已生效筛选条件的全部账号发起批量登录。
     *
     * @param query 不含分页语义的筛选条件；null 等价于空条件
     * @return 所有游标批次的聚合结果
     * @throws BusinessException 当稳定 ID 游标无法继续向前推进时抛出
     */
    AccountBatchCommandResultVO onlineByQuery(AccountBatchQueryDTO query);

    /**
     * 对符合已生效筛选条件的全部账号发起批量离线。
     *
     * @param query 不含分页语义的筛选条件；null 等价于空条件
     * @return 所有游标批次的聚合结果
     * @throws BusinessException 当稳定 ID 游标无法继续向前推进时抛出
     */
    AccountBatchCommandResultVO offlineByQuery(AccountBatchQueryDTO query);
}
