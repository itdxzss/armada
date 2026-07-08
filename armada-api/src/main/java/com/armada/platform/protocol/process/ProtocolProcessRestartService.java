package com.armada.platform.protocol.process;

/**
 * 协议层进程重启服务。
 *
 * <p>供后台管理接口触发协议层 master/worker 重启,并在返回前探测各进程 ready 状态。</p>
 */
public interface ProtocolProcessRestartService {

    /**
     * 重启协议层进程并等待 ready 探活结果。
     *
     * @return 重启命令、进程探活结果和整体成功状态
     */
    ProtocolRestartVO restart();
}
