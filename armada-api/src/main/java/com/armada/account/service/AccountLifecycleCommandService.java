package com.armada.account.service;

import com.armada.account.model.vo.AccountProbeVO;
import com.armada.account.model.vo.AccountStatusVO;
import com.armada.shared.exception.BusinessException;

/**
 * 账号生命周期诊断应用服务。
 *
 * <p>负责从 armada 账号 ID 找到协议账号句柄,再委托协议防腐层查询状态或主动探活。
 * 本服务不修改账号登录状态;本地状态收敛仍以后续 Kafka 事件为准。</p>
 */
public interface AccountLifecycleCommandService {

    /**
     * 主动从协议层读取账号状态快照。
     *
     * @param accountId armada 账号 ID
     * @return 协议层状态快照
     * @throws BusinessException 账号不存在或协议账号句柄缺失时抛出
     */
    AccountStatusVO refreshStatus(Long accountId);

    /**
     * 主动探活账号。
     *
     * @param accountId armada 账号 ID
     * @return 探活结果
     * @throws BusinessException 账号不存在或协议账号句柄缺失时抛出
     */
    AccountProbeVO probe(Long accountId);
}
