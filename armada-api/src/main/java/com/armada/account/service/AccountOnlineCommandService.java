package com.armada.account.service;

import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.shared.exception.BusinessException;

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
}
