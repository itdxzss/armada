package com.armada.promotion.pairing.service.impl;

import com.armada.account.service.PromotionAccountProvisionService;
import com.armada.platform.protocol.model.command.PairingCodeCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.PairingAccepted;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.promotion.channel.support.PromotionDomainNormalizer;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.command.PromotionPairingAttribution;
import com.armada.promotion.pairing.model.command.PromotionPairingCreateCommand;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import com.armada.promotion.pairing.model.vo.PromotionPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.PromotionPairingStatusVO;
import com.armada.promotion.pairing.service.PromotionPairingService;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 推广落地页 WhatsApp 配对会话编排。 */
@Service
public class PromotionPairingServiceImpl implements PromotionPairingService {

    private static final Logger log = LoggerFactory.getLogger(PromotionPairingServiceImpl.class);
    private static final Pattern PHONE = Pattern.compile("[1-9][0-9]{9,14}");
    private static final Pattern META_BROWSER_ID = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final long INITIAL_TTL_MILLIS = 180_000L;
    private static final long EVENT_DELIVERY_GRACE_MILLIS = 30_000L;
    private static final String ERROR_REQUEST_FAILED = "PAIRING_REQUEST_FAILED";
    private static final String ERROR_EXPIRED = "PAIRING_EXPIRED";

    private final PromotionChannelService channelService;
    private final PromotionPairingSessionMapper sessionMapper;
    private final PromotionAccountProvisionService accountProvisionService;
    private final IpProxyService ipProxyService;
    private final ProxyResolver proxyResolver;
    private final PairingLoginPort pairingLoginPort;
    private final PromotionPairingTokenService tokenService;
    private final PromotionPairingTransitionService transitionService;
    private final PromotionPairingCompletionService completionService;

    public PromotionPairingServiceImpl(PromotionChannelService channelService,
                                       PromotionPairingSessionMapper sessionMapper,
                                       PromotionAccountProvisionService accountProvisionService,
                                       IpProxyService ipProxyService,
                                       ProxyResolver proxyResolver,
                                       PairingLoginPort pairingLoginPort,
                                       PromotionPairingTokenService tokenService,
                                       PromotionPairingTransitionService transitionService,
                                       PromotionPairingCompletionService completionService) {
        this.channelService = channelService;
        this.sessionMapper = sessionMapper;
        this.accountProvisionService = accountProvisionService;
        this.ipProxyService = ipProxyService;
        this.proxyResolver = proxyResolver;
        this.pairingLoginPort = pairingLoginPort;
        this.tokenService = tokenService;
        this.transitionService = transitionService;
        this.completionService = completionService;
    }

    @Override
    public PromotionPairingCreatedVO create(PromotionPairingCreateCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "配对参数不能为空");
        }
        String normalizedPhone = normalizePhone(command.phone());
        PromotionChannelPairingContextRow context =
                channelService.resolvePairingContext(command.channelCode(), command.forwardedHost());
        PromotionPairingAttribution attribution = normalizeAttribution(
                command, PromotionDomainNormalizer.normalize(command.forwardedHost()));

        Long previousTenant = TenantContext.get();
        PromotionPairingSession session = null;
        DataScopeContext.Scope ownerScope = null;
        try {
            TenantContext.set(context.tenantId());
            ownerScope = DataScopeContext.open(DataScope.self(requireOwner(context.ownerUserId())));
            if (accountProvisionService.existsActiveByPhoneGlobally(normalizedPhone)) {
                // 公开入口不泄露手机号是否属于其他租户，只返回统一的可重试提示。
                throw new BusinessException(ErrorCode.CONFLICT, "配对暂不可用，请更换账号或稍后重试");
            }

            long now = System.currentTimeMillis();
            PromotionPairingTokenService.GeneratedToken token = tokenService.generate();
            session = buildSession(context, normalizedPhone, token.tokenHash(), now);
            transitionService.createSession(session, context, attribution, now);

            // 代理使用专用配对状态和会话归属占用；成功落库后才转成正式 account.id。
            IpProxyAllocation allocation = ipProxyService.allocatePairingEndpoint(
                    session.getId(), context.preferredProxyRegion(), true);
            // 分配一成功就记录到内存会话，后续解析或数据库绑定失败时补偿逻辑才能释放该代理。
            session.setProxyId(allocation.proxyId());
            ProxyDescriptor proxy = proxyResolver.resolve(allocation.endpoint());
            requireOne(sessionMapper.attachProxy(
                    session.getId(), context.tenantId(), allocation.proxyId(), proxy.sessionId(),
                    allocation.endpoint().country(), allocation.proxySource(), now), "配对代理绑定失败");

            // 不传固定 customPairingCode，随机码由协议层生成并通过 Kafka 回填。
            PairingAccepted accepted = pairingLoginPort.requestCode(new PairingCodeCommand(
                    session.getProtocolAccountId(), normalizedPhone, proxy));
            validateAccepted(session, accepted);
            long expiresAt = accepted.expiresAt().toEpochMilli();
            transitionService.markAccepted(
                    session.getId(), context.tenantId(), accepted.pairingId(),
                    expiresAt, System.currentTimeMillis());
            log.info("推广配对请求已受理 sessionId={} channelId={} proxyId={} expiresAt={}",
                    session.getId(), context.channelId(), allocation.proxyId(), expiresAt);
            return new PromotionPairingCreatedVO(
                    token.rawToken(), PromotionPairingStatus.REQUESTING.name(), expiresAt);
        } catch (BusinessException ex) {
            compensateFailedSession(session);
            throw ex;
        } catch (RuntimeException ex) {
            compensateFailedSession(session);
            log.warn("推广配对请求失败 sessionId={} channelId={} errorType={}",
                    session == null ? null : session.getId(), context.channelId(), ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.CONFLICT, "配对请求失败，请重试");
        } finally {
            if (ownerScope != null) {
                ownerScope.close();
            }
            restoreTenant(previousTenant);
        }
    }

    @Override
    public PromotionPairingStatusVO status(String sessionToken) {
        String tokenHash = tokenService.hash(sessionToken);
        PromotionPairingSession session = sessionMapper.selectByTokenHash(tokenHash);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "配对会话不存在");
        }
        PromotionPairingStatus status = PromotionPairingStatus.fromCode(session.getStatus());
        long now = System.currentTimeMillis();
        if (isActive(status) && session.getExpiresAt() <= now) {
            // 对用户按真实到期时间展示 EXPIRED；数据库延迟 30 秒终态化，以接纳到期前已发生但 Kafka 稍晚送达的成功事件。
            if (session.getExpiresAt() + EVENT_DELIVERY_GRACE_MILLIS > now) {
                status = PromotionPairingStatus.EXPIRED;
                session.setPairingCode(null);
                session.setErrorCode(ERROR_EXPIRED);
                session.setErrorMessage("配对码已失效，请重试");
            } else if (expireIfDue(session)) {
                status = PromotionPairingStatus.EXPIRED;
                session.setStatus(status.code());
                session.setPairingCode(null);
                session.setErrorCode(ERROR_EXPIRED);
                session.setErrorMessage("配对码已失效，请重试");
            } else {
                // Kafka 完成事件可能刚好抢先落库；终态竞争失败时必须返回数据库真实状态。
                session = sessionMapper.selectByTokenHash(tokenHash);
                if (session == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "配对会话不存在");
                }
                status = PromotionPairingStatus.fromCode(session.getStatus());
            }
        }
        return new PromotionPairingStatusVO(
                status.name(),
                status == PromotionPairingStatus.WAITING_CONFIRMATION ? session.getPairingCode() : null,
                session.getExpiresAt(),
                status == PromotionPairingStatus.SUCCEEDED ? session.getAccountId() : null,
                session.getErrorCode(),
                session.getErrorMessage());
    }

    private PromotionPairingSession buildSession(PromotionChannelPairingContextRow context,
                                                 String phone,
                                                 String tokenHash,
                                                 long now) {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setTenantId(context.tenantId());
        session.setPromotionChannelId(context.channelId());
        session.setChannelName(context.channelName());
        session.setOwnerUserId(context.ownerUserId());
        session.setSessionTokenHash(tokenHash);
        session.setPhone(phone);
        // 每次尝试使用独立协议账号句柄；协议层原始事件中的 accountId 可直接且唯一地关联本会话。
        session.setProtocolAccountId("acc_pair_" + UUID.randomUUID().toString().replace("-", ""));
        session.setStatus(PromotionPairingStatus.REQUESTING.code());
        session.setExpiresAt(now + INITIAL_TTL_MILLIS);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }

    private void compensateFailedSession(PromotionPairingSession session) {
        if (session == null || session.getId() == null) {
            return;
        }
        completionService.terminate(
                session, PromotionPairingStatus.FAILED, ERROR_REQUEST_FAILED,
                "配对请求失败，请重试", System.currentTimeMillis());
    }

    private boolean expireIfDue(PromotionPairingSession session) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(session.getTenantId());
            try (DataScopeContext.Scope ignored = DataScopeContext.open(
                    DataScope.self(requireOwner(session.getOwnerUserId())))) {
                return completionService.expireIfDue(
                        session.getId(), session.getTenantId(), System.currentTimeMillis());
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static long requireOwner(Long ownerUserId) {
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "推广渠道或配对会话缺少可信用户归属");
        }
        return ownerUserId;
    }

    private static void validateAccepted(PromotionPairingSession session, PairingAccepted accepted) {
        if (accepted == null || !session.getProtocolAccountId().equals(accepted.protocolAccountId())
                || accepted.pairingId() == null || accepted.pairingId().isBlank()
                || accepted.expiresAt() == null || !accepted.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "协议层未正确受理配对请求");
        }
    }

    private static String normalizePhone(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!PHONE.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "手机号需为 10-15 位国际号码数字");
        }
        return normalized;
    }

    private static PromotionPairingAttribution normalizeAttribution(
            PromotionPairingCreateCommand command, String expectedHost) {
        return new PromotionPairingAttribution(
                optionalMetaId(command.fbp()),
                optionalMetaId(command.fbc()),
                optionalSourceUrl(command.sourceUrl(), expectedHost),
                optionalClientIp(command.clientIp()),
                optionalUserAgent(command.clientUserAgent()));
    }

    private static String optionalMetaId(String value) {
        String normalized = optionalTrim(value);
        if (normalized != null && !META_BROWSER_ID.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private static String optionalClientIp(String value) {
        String normalized = optionalTrim(value);
        return isIpv4Literal(normalized) || isIpv6Literal(normalized) ? normalized : null;
    }

    private static String optionalUserAgent(String value) {
        String normalized = optionalTrim(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > 512 || normalized.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }

    private static String optionalSourceUrl(String value, String expectedHost) {
        String normalized = optionalTrim(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > 2048) {
            return null;
        }
        try {
            URI uri = new URI(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || !expectedHost.equalsIgnoreCase(uri.getHost())) {
                return null;
            }
            String ascii = new URI(
                    uri.getScheme().toLowerCase(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toASCIIString();
            return ascii.length() <= 2048 ? ascii : null;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static boolean isIpv4Literal(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            int number = Integer.parseInt(part);
            if (number > 255 || (part.length() > 1 && part.startsWith("0"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6Literal(String value) {
        if (value == null || !value.contains(":") || !IPV6_LITERAL.matcher(value).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static String optionalTrim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
