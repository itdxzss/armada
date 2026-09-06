package com.armada.promotion.pairing.service;

import com.armada.promotion.pairing.model.command.ControlPairingCreateCommand;
import com.armada.promotion.pairing.model.vo.ControlPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.ControlPairingStatusVO;

/** 认证后控台使用的 WhatsApp 固定认证码导号服务。 */
public interface ControlPairingService {

    ControlPairingCreatedVO create(ControlPairingCreateCommand command);

    ControlPairingStatusVO status(Long sessionId, Long tenantId);
}
