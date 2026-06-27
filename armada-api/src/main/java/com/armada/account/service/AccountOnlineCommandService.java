package com.armada.account.service;

import com.armada.account.model.dto.AccountOnlineDTO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.shared.exception.BusinessException;

/**
 * 账号手动上线应用服务。
 *
 * <p>负责从账号 ID 和手动选择的 proxyId 出发,加载账号凭据与代理端点,
 * 再委托底层 {@link AccountOnlineService} 调协议层 online。</p>
 */
public interface AccountOnlineCommandService {

    /**
     * 发起单账号上线。
     *
     * @param accountId armada 账号主键
     * @param request   单账号上线请求
     * @return 协议层上线受理回执
     * @throws BusinessException 当账号、凭据或 proxyId 不满足上线前置条件时抛出
     */
    AccountOnlineVO online(Long accountId, AccountOnlineDTO request);
}
