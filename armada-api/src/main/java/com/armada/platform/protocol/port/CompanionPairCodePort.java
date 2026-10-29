package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.CompanionPairCodeResult;

/** 安卓主设备确认关联设备（分身）配对码的协议端口。 */
public interface CompanionPairCodePort {

    /**
     * 让在线的安卓主设备输入关联设备的配对码，发送 primary_hello。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param pairingCode 关联设备发起配对时使用的 8 位配对码，不得写入日志
     * @return 受理结果；协议层不可达时抛出协议异常
     */
    CompanionPairCodeResult submit(String wsPhone, String pairingCode);
}
