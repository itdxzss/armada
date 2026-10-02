package com.armada.promotion.pairing.service;

import com.armada.promotion.pairing.model.command.ControlPairingCreateCommand;
import com.armada.promotion.pairing.model.vo.ControlPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.ControlPairingStatusVO;
import java.util.Optional;

/** 认证后控台使用的 WhatsApp 固定认证码导号服务。 */
public interface ControlPairingService {

    /** 持久化并异步启动配对；同一用户的进行中会话直接恢复。 */
    ControlPairingCreatedVO create(ControlPairingCreateCommand command);

    /** 创建响应丢失时查询原用户最近一次会话，不重新发起配对。 */
    Optional<ControlPairingCreatedVO> recover(String phone, Long ownerUserId);

    ControlPairingStatusVO status(Long sessionId, Long tenantId);
}
