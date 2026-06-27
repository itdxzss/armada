package com.armada.platform.protocol.model.result;

/**
 * 批量上线单账号命令投递结果。
 */
public enum BatchOnlineResultStatus {

    /**
     * 协议层已受理该账号上线命令。
     */
    ACCEPTED,

    /**
     * 该账号在本批等待上线令牌超时,调用方可后续重试。
     */
    TIMEOUT,

    /**
     * 协议层认为该账号缺少代理信息。
     */
    PROXY_REQUIRED,

    /**
     * 该账号归属其它 worker,当前切片不做远端重投。
     */
    REMOTE,

    /**
     * 其它协议层投递错误。
     */
    ERROR
}
