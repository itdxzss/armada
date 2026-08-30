package com.armada.account.service;

/** 账号当前有效类型的协议校验服务。 */
public interface AccountTypeVerificationService {

    /**
     * 应用协议类型检测事实；凭据过期、账号不存在或旧事件返回 false。
     *
     * @param event 类型检测事实
     * @return 本次结果是否更新当前账号
     */
    boolean applyDetected(AccountTypeDetectedEvent event);
}
