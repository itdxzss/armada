package com.armada.account.service;

import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.shared.exception.BusinessException;
import java.util.List;

/**
 * 账号上线应用服务。
 *
 * <p>负责从账号 ID 出发,加载账号凭据、向 resource 域申请空闲代理,
 * 再委托底层 {@link AccountOnlineService} 投递协议层 online 命令。</p>
 */
public interface AccountOnlineCommandService {

    /**
     * 发起单账号上线。
     *
     * @param accountId armada 账号主键
     * @return 协议层上线受理回执
     * @throws BusinessException 当账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountOnlineVO online(Long accountId);

    /**
     * 批量发起账号上线。
     *
     * <p>一次最多 500 个账号。实现会批量加载账号与凭据,逐账号分配代理,
     * 但对协议层只发一次 batch online HTTP 命令。</p>
     *
     * @param accountIds armada 账号主键列表
     * @return 协议层批量上线受理回执
     * @throws BusinessException 当账号列表、账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountBatchOnlineVO onlineBatch(List<Long> accountIds);
}
