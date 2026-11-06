package com.armada.account.service;

import com.armada.account.model.dto.AccountMessageCapQueryDTO;
import com.armada.account.model.vo.AccountMessageCapBatchVO;
import com.armada.shared.exception.BusinessException;

/**
 * 账号“新聊天额度”批量查询服务。
 *
 * <p>只读：对选中账号各自的当前在线会话各发一次固定查询，不登录、不发送、不重试，也不落库。</p>
 */
public interface AccountMessageCapService {

    /**
     * 批量查询当前租户选中账号的新聊天额度。
     *
     * @param request 账号 ID 与是否返回原始响应
     * @return 逐账号结果；单个账号失败不影响其它账号
     * @throws BusinessException 当 ID 为空或去重后超过上限时抛出
     */
    AccountMessageCapBatchVO query(AccountMessageCapQueryDTO request);
}
