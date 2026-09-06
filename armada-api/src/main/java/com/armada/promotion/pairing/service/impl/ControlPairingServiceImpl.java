package com.armada.promotion.pairing.service.impl;

import com.armada.account.service.PromotionAccountProvisionService;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.command.PairingCodeCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.PairingAccepted;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.command.ControlPairingCreateCommand;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingScene;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import com.armada.promotion.pairing.model.vo.ControlPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.ControlPairingStatusVO;
import com.armada.promotion.pairing.service.ControlPairingService;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 登录控台后的固定认证码导号编排。 */
@Service
public class ControlPairingServiceImpl implements ControlPairingService {

    static final String CONTROL_PAIRING_CODE = "88888888";
    private static final Logger log = LoggerFactory.getLogger(ControlPairingServiceImpl.class);
    private static final Pattern PHONE = Pattern.compile("[1-9][0-9]{9,14}");
    private static final long INITIAL_TTL_MILLIS = 180_000L;
    private static final long EVENT_DELIVERY_GRACE_MILLIS = 30_000L;
    private static final String ERROR_REQUEST_FAILED = "PAIRING_REQUEST_FAILED";
    private static final String ERROR_EXPIRED = "PAIRING_EXPIRED";

    private final PromotionPairingSessionMapper sessionMapper;
    private final PromotionAccountProvisionService accountProvisionService;
    private final IpProxyService ipProxyService;
    private final ProxyResolver proxyResolver;
    private final PairingLoginPort pairingLoginPort;
    private final PromotionPairingTokenService tokenService;
    private final PromotionPairingTransitionService transitionService;
    private final PromotionPairingCompletionService completionService;
    private final CountryService countryService;
    private final LongSupplier clock;

    @Autowired
    public ControlPairingServiceImpl(PromotionPairingSessionMapper sessionMapper,
                                     PromotionAccountProvisionService accountProvisionService,
                                     IpProxyService ipProxyService,
                                     ProxyResolver proxyResolver,
                                     PairingLoginPort pairingLoginPort,
                                     PromotionPairingTokenService tokenService,
                                     PromotionPairingTransitionService transitionService,
                                     PromotionPairingCompletionService completionService,
                                     CountryService countryService) {
        this(sessionMapper, accountProvisionService, ipProxyService, proxyResolver, pairingLoginPort,
                tokenService, transitionService, completionService, countryService,
                System::currentTimeMillis);
    }

    ControlPairingServiceImpl(PromotionPairingSessionMapper sessionMapper,
                              PromotionAccountProvisionService accountProvisionService,
                              IpProxyService ipProxyService,
                              ProxyResolver proxyResolver,
                              PairingLoginPort pairingLoginPort,
                              PromotionPairingTokenService tokenService,
                              PromotionPairingTransitionService transitionService,
                              PromotionPairingCompletionService completionService,
                              CountryService countryService,
                              LongSupplier clock) {
        this.sessionMapper = sessionMapper;
        this.accountProvisionService = accountProvisionService;
        this.ipProxyService = ipProxyService;
        this.proxyResolver = proxyResolver;
        this.pairingLoginPort = pairingLoginPort;
        this.tokenService = tokenService;
        this.transitionService = transitionService;
        this.completionService = completionService;
        this.countryService = countryService;
        this.clock = clock;
    }

    @Override
    public ControlPairingCreatedVO create(ControlPairingCreateCommand command) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        if (command == null || command.ownerUserId() == null || command.ownerUserId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "配对参数不能为空");
        }
        String phone = normalizePhone(command.phone());
        accountProvisionService.validateControlTarget(command.accountGroupId());
        if (accountProvisionService.existsActiveByPhoneGlobally(phone)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该 WhatsApp 账号已存在");
        }

        PromotionPairingSession session = null;
        try {
            long now = clock.getAsLong();
            PromotionPairingTokenService.GeneratedToken token = tokenService.generate();
            session = buildSession(command, tenantId, phone, token.tokenHash(), now);
            transitionService.createControlSession(session);

            String preferredRegion = countryService.resolveIpRegionByPhonePrefix(phone);
            IpProxyAllocation allocation = ipProxyService.allocatePairingEndpoint(
                    session.getId(), preferredRegion, true);
            session.setProxyId(allocation.proxyId());
            ProxyDescriptor proxy = proxyResolver.resolve(allocation.endpoint());
            requireOne(sessionMapper.attachProxy(
                    session.getId(), tenantId, allocation.proxyId(), proxy.sessionId(),
                    proxy.country(), allocation.proxySource(), now), "配对代理绑定失败");

            PairingAccepted accepted = pairingLoginPort.requestCode(new PairingCodeCommand(
                    session.getProtocolAccountId(), phone, proxy, CONTROL_PAIRING_CODE));
            validateAccepted(session, accepted, now);
            long expiresAt = accepted.expiresAt().toEpochMilli();
            transitionService.markControlAccepted(
                    session.getId(), tenantId, accepted.pairingId(), expiresAt, clock.getAsLong());
            log.info("控台认证码配对请求已受理 sessionId={} proxyId={} expiresAt={}",
                    session.getId(), allocation.proxyId(), expiresAt);
            return new ControlPairingCreatedVO(
                    session.getId(), PromotionPairingStatus.REQUESTING.name(), expiresAt);
        } catch (BusinessException ex) {
            compensateFailedSession(session);
            throw ex;
        } catch (RuntimeException ex) {
            compensateFailedSession(session);
            log.warn("控台认证码配对请求失败 sessionId={} errorType={}",
                    session == null ? null : session.getId(), ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.CONFLICT, "配对请求失败，请重试");
        }
    }

    @Override
    public ControlPairingStatusVO status(Long sessionId, Long tenantId) {
        if (sessionId == null || sessionId <= 0 || tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "配对会话不存在");
        }
        PromotionPairingSession session = sessionMapper.selectByIdAndTenant(sessionId, tenantId);
        requireControlSession(session);
        PromotionPairingStatus status = PromotionPairingStatus.fromCode(session.getStatus());
        long now = clock.getAsLong();
        if (isActive(status) && session.getExpiresAt() <= now) {
            if (session.getExpiresAt() + EVENT_DELIVERY_GRACE_MILLIS > now) {
                status = PromotionPairingStatus.EXPIRED;
                session.setPairingCode(null);
                session.setErrorCode(ERROR_EXPIRED);
                session.setErrorMessage("配对码已失效，请重试");
            } else {
                completionService.expireIfDue(sessionId, tenantId, now);
                session = sessionMapper.selectByIdAndTenant(sessionId, tenantId);
                requireControlSession(session);
                status = PromotionPairingStatus.fromCode(session.getStatus());
            }
        }
        return new ControlPairingStatusVO(
                status.name(),
                status == PromotionPairingStatus.WAITING_CONFIRMATION ? session.getPairingCode() : null,
                session.getExpiresAt(),
                status == PromotionPairingStatus.SUCCEEDED ? session.getAccountId() : null,
                session.getErrorCode(),
                session.getErrorMessage());
    }

    private PromotionPairingSession buildSession(ControlPairingCreateCommand command,
                                                 Long tenantId,
                                                 String phone,
                                                 String tokenHash,
                                                 long now) {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setPairingScene(PromotionPairingScene.CONTROL_ACCOUNT_IMPORT.code());
        session.setTenantId(tenantId);
        session.setOwnerUserId(command.ownerUserId());
        session.setAccountGroupId(command.accountGroupId());
        session.setRemark(optionalRemark(command.remark()));
        session.setSessionTokenHash(tokenHash);
        session.setPhone(phone);
        session.setProtocolAccountId("acc_pair_" + UUID.randomUUID().toString().replace("-", ""));
        session.setStatus(PromotionPairingStatus.REQUESTING.code());
        session.setExpiresAt(now + INITIAL_TTL_MILLIS);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }

    private void compensateFailedSession(PromotionPairingSession session) {
        if (session != null && session.getId() != null) {
            completionService.terminate(
                    session, PromotionPairingStatus.FAILED, ERROR_REQUEST_FAILED,
                    "配对请求失败，请重试", clock.getAsLong());
        }
    }

    private static void requireControlSession(PromotionPairingSession session) {
        if (session == null
                || PromotionPairingScene.fromCode(session.getPairingScene())
                != PromotionPairingScene.CONTROL_ACCOUNT_IMPORT) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "配对会话不存在");
        }
    }

    private static void validateAccepted(PromotionPairingSession session,
                                         PairingAccepted accepted,
                                         long now) {
        if (accepted == null || !session.getProtocolAccountId().equals(accepted.protocolAccountId())
                || !StringUtils.hasText(accepted.pairingId()) || accepted.expiresAt() == null
                || !accepted.expiresAt().isAfter(Instant.ofEpochMilli(now))) {
            throw new BusinessException(ErrorCode.CONFLICT, "协议层未正确受理配对请求");
        }
    }

    private static String normalizePhone(String value) {
        String phone = value == null ? "" : value.trim();
        if (!PHONE.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "手机号需为 10-15 位国际号码数字");
        }
        return phone;
    }

    private static String optionalRemark(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String remark = value.trim();
        if (remark.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION, "备注不能超过 255 个字符");
        }
        return remark;
    }

    private static boolean isActive(PromotionPairingStatus status) {
        return status == PromotionPairingStatus.REQUESTING
                || status == PromotionPairingStatus.WAITING_CONFIRMATION
                || status == PromotionPairingStatus.FINALIZING;
    }

    private static void requireOne(int affected, String message) {
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }
}
