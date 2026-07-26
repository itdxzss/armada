package com.armada.promotion.pairing.service.impl;

import com.armada.account.service.PromotionAccountProvisionCommand;
import com.armada.account.service.PromotionAccountProvisionService;
import com.armada.platform.kafka.consumer.pairing.ProtocolPairingEvent;
import com.armada.platform.protocol.model.result.PairingCredentialExport;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 配对完成后账号落库、IP 转绑和会话成功状态的同库事务边界。 */
@Service
public class PromotionPairingCompletionService {

    private static final String ERROR_EXPIRED = "PAIRING_EXPIRED";
    private static final String MESSAGE_EXPIRED = "配对码已失效，请重试";
    private static final String TYPE_PERSONAL = "PERSONAL";
    private static final String TYPE_BUSINESS_STANDARD = "BUSINESS_STANDARD";
    private static final String TYPE_BUSINESS_VERIFIED = "BUSINESS_VERIFIED";
    private static final int ACCOUNT_TYPE_PERSONAL = 1;
    private static final int ACCOUNT_TYPE_BUSINESS = 2;

    private final PromotionPairingSessionMapper sessionMapper;
    private final PromotionAccountProvisionService accountProvisionService;
    private final IpProxyService ipProxyService;

    public PromotionPairingCompletionService(PromotionPairingSessionMapper sessionMapper,
                                             PromotionAccountProvisionService accountProvisionService,
                                             IpProxyService ipProxyService) {
        this.sessionMapper = sessionMapper;
        this.accountProvisionService = accountProvisionService;
        this.ipProxyService = ipProxyService;
    }

    /**
     * 完成配对落库。
     *
     * <p>完整凭据已在事务外从协议层导出；本方法只做本地 MySQL 原子写，
     * 任一步失败会同时回滚账号、IP 正式绑定和会话状态。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Long complete(Long sessionId,
                         Long tenantId,
                         ProtocolPairingEvent event,
                         PairingCredentialExport credential) {
        PromotionPairingSession session = sessionMapper.selectByIdForUpdate(sessionId, tenantId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "配对会话不存在");
        }
        validateEventCorrelation(session, event);
        PromotionPairingStatus status = PromotionPairingStatus.fromCode(session.getStatus());
        if (status == PromotionPairingStatus.SUCCEEDED) {
            return session.getAccountId();
        }
        if (event.occurredAt() > session.getExpiresAt()) {
            int changed = sessionMapper.markTerminal(
                    sessionId, tenantId, PromotionPairingStatus.EXPIRED.code(),
                    ERROR_EXPIRED, MESSAGE_EXPIRED, event.occurredAt());
            if (changed == 1 && session.getProxyId() != null) {
                ipProxyService.releasePairingAllocation(sessionId, session.getProxyId());
            }
            return null;
        }
        // 创建会话与完成事件之间可能发生并发落库，完成前必须再次做跨租户归属校验。
        if (accountProvisionService.existsActiveByPhoneGlobally(session.getPhone())) {
            throw new BusinessException(ErrorCode.CONFLICT, "配对暂不可用，请更换账号或稍后重试");
        }
        if (status != PromotionPairingStatus.FINALIZING) {
            if (status != PromotionPairingStatus.REQUESTING
                    && status != PromotionPairingStatus.WAITING_CONFIRMATION) {
                throw new BusinessException(ErrorCode.CONFLICT, "配对会话已结束");
            }
            requireOne(sessionMapper.claimFinalizing(
                    sessionId, tenantId, event.occurredAt()), "配对会话状态已变化");
        }
        if (session.getProxyId() == null || session.getProxySessionId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "配对会话缺少代理绑定");
        }

        Long accountId = accountProvisionService.provision(new PromotionAccountProvisionCommand(
                session.getPhone(),
                session.getPromotionChannelId(),
                session.getChannelName(),
                session.getOwnerUserId(),
                session.getProtocolAccountId(),
                event.ownerEndpoint(),
                credential.credentialJson(),
                session.getProxySessionId(),
                session.getProxyRegion(),
                session.getProxySource(),
                resolveAccountType(event.detectedAccountType()),
                event.occurredAt()));
        ipProxyService.confirmPairingAllocation(sessionId, accountId, session.getProxyId());
        requireOne(sessionMapper.markSucceeded(
                sessionId, tenantId, accountId, event.occurredAt()), "配对成功状态写入失败");
        return accountId;
    }

    /**
     * 在行锁内复核会话的实时到期时间，并回收当前代理绑定。
     *
     * <p>定时扫描和公开状态查询拿到的都只是快照，协议层可能在扫描后回填新的过期时间或代理。
     * 因此不能直接使用调用方快照结束会话，必须重新锁行判断。</p>
     *
     * @param sessionId 配对会话 ID
     * @param tenantId 会话所属租户 ID
     * @param cutoff 本次判断使用的当前时间，epoch 毫秒
     * @return true 表示本次成功将到期会话转为过期状态
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean expireIfDue(Long sessionId, Long tenantId, long cutoff) {
        PromotionPairingSession current = sessionMapper.selectByIdForUpdate(sessionId, tenantId);
        if (current == null || current.getExpiresAt() > cutoff) {
            return false;
        }
        PromotionPairingStatus status = PromotionPairingStatus.fromCode(current.getStatus());
        if (status != PromotionPairingStatus.REQUESTING
                && status != PromotionPairingStatus.WAITING_CONFIRMATION
                && status != PromotionPairingStatus.FINALIZING) {
            return false;
        }
        int changed = sessionMapper.markTerminal(
                sessionId, tenantId, PromotionPairingStatus.EXPIRED.code(),
                ERROR_EXPIRED, MESSAGE_EXPIRED, cutoff);
        if (changed == 1 && current.getProxyId() != null) {
            ipProxyService.releasePairingAllocation(sessionId, current.getProxyId());
        } else if (changed == 1) {
            // 进程可能在代理分配提交后、会话回填 proxy_id 前中断，按专用会话归属字段兜底回收。
            ipProxyService.releasePairingAllocationBySession(sessionId);
        }
        return changed == 1;
    }

    /**
     * 失败时在同一事务内结束会话并释放临时代理。
     *
     * @param session 当前调用链内持有的配对会话
     * @param terminalStatus 失败终态
     * @param errorCode 脱敏失败码
     * @param errorMessage 可公开展示的失败摘要
     * @param occurredAt 失败发生时间，epoch 毫秒
     * @return true 表示本次成功将活动会话转为终态
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean terminate(PromotionPairingSession session,
                             PromotionPairingStatus terminalStatus,
                             String errorCode,
                             String errorMessage,
                             long occurredAt) {
        int changed = sessionMapper.markTerminal(
                session.getId(), session.getTenantId(), terminalStatus.code(),
                errorCode, errorMessage, occurredAt);
        if (changed == 1 && session.getProxyId() != null) {
            ipProxyService.releasePairingAllocation(session.getId(), session.getProxyId());
        } else if (changed == 1) {
            ipProxyService.releasePairingAllocationBySession(session.getId());
        }
        return changed == 1;
    }

    private static void requireOne(int affected, String message) {
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private static void validateEventCorrelation(PromotionPairingSession session, ProtocolPairingEvent event) {
        if (event == null
                || !session.getProtocolAccountId().equals(event.protocolAccountId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议配对完成事件与会话不一致");
        }
    }

    /**
     * 将协议层明确识别的类型映射到既有账号类型。
     *
     * <p>账号类型入库后不可修改，因此 UNKNOWN、空值或未来新增值必须拒绝落库，
     * 不能静默按个人号处理。</p>
     */
    private static int resolveAccountType(String detectedAccountType) {
        if (TYPE_PERSONAL.equalsIgnoreCase(detectedAccountType)) {
            return ACCOUNT_TYPE_PERSONAL;
        }
        if (TYPE_BUSINESS_STANDARD.equalsIgnoreCase(detectedAccountType)
                || TYPE_BUSINESS_VERIFIED.equalsIgnoreCase(detectedAccountType)) {
            return ACCOUNT_TYPE_BUSINESS;
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议层未明确识别账号类型");
    }
}
