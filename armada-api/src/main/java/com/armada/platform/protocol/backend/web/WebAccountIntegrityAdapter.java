package com.armada.platform.protocol.backend.web;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.routing.AccountIntegrityBackend;
import java.net.URI;
import java.util.Map;
import java.util.Set;
/** Web 只读 IQ 适配；错误及非成功响应不透传敏感返回字段。 */
public final class WebAccountIntegrityAdapter implements AccountIntegrityBackend {
    private static final String PATH = "/v1/accounts/{accountId}/integrity-query";
    private static final Set<String> REASONS = Set.of("TIMEOUT", "IQ_ERROR", "GRAPHQL_ERROR", "INVALID_RESPONSE");
    private final ProtocolHttpExecutor http;
    /** 复用有鉴权和连接/读取超时的执行器。 */
    public WebAccountIntegrityAdapter(ProtocolHttpExecutor http) { this.http = http; }
    @Override public ProtocolBackend backend() { return ProtocolBackend.WEB; }
    @Override public ProtocolAccountIntegrity query(ProtocolAccountRef executor, String targetPhone) {
        try {
            return normalize(request(executor, targetPhone));
        } catch (ProtocolException ex) {
            // 不传播 HTTP 执行器可能携带的错误原文或响应预览。
            throw new ProtocolException(ex.errorCode(), "档位协议查询失败")
                    .withContext(ProtocolBackend.WEB, "account.integrity", "account:" + executor.armadaAccountId());
        }
    }
    private Response request(ProtocolAccountRef executor, String phone) {
        try {
            return http.postTyped(PATH, Map.of("phone", phone), Response.class, executor.protocolAccountId());
        } catch (ProtocolException ex) {
            if (ex.errorCode() != ProtocolErrorCode.NOT_OWNER || ex.ownerEndpoint().isEmpty()) throw ex;
            // owner 信息由已鉴权协议服务返回；只在明确未执行时重定向一次，超时不自动重试。
            URI owner = URI.create(ex.ownerEndpoint().get());
            if (!("http".equals(owner.getScheme()) || "https".equals(owner.getScheme()))
                    || owner.getHost() == null || owner.getUserInfo() != null || owner.getQuery() != null
                    || owner.getFragment() != null) throw new ProtocolException(ProtocolErrorCode.NOT_OWNER, "无效 owner 地址");
            String endpoint = owner.toString().replaceAll("/+$", "");
            return http.postTyped(endpoint + PATH, Map.of("phone", phone), Response.class, executor.protocolAccountId());
        }
    }
    private ProtocolAccountIntegrity normalize(Response r) {
        if (r == null || r.status() == null) return ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        return switch (r.status()) {
            case "EMPTY" -> new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.EMPTY,
                    null, null, null, null, null, null, null, "EMPTY_RESPONSE");
            case "NOT_ALLOWED" -> new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.NOT_ALLOWED,
                    null, null, null, null, null, null, null, "NOT_ALLOWED");
            case "ERROR" -> ProtocolAccountIntegrity.failure(REASONS.contains(r.reasonCode() == null ? "" : r.reasonCode())
                    ? r.reasonCode() : "PROTOCOL_ERROR");
            case "OK" -> success(r);
            default -> ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        };
    }
    private ProtocolAccountIntegrity success(Response r) {
        String tier = r.trustTier();
        if (tier != null && !tier.matches("[A-Z0-9_]{1,32}")) return ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        if (tier == null && r.isSuspicious() == null && r.isBanned() == null && r.isMvSubscribed() == null
                && r.joinDateMs() == null && r.phoneCountryCode() == null) return ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        String type = "BUSINESS".equals(r.userType()) || "PERSONAL".equals(r.userType()) ? r.userType() : null;
        String country = r.phoneCountryCode();
        if (country != null && !country.matches("[A-Za-z0-9+_-]{1,32}")) country = null;
        Long joined = r.joinDateMs() != null && r.joinDateMs() >= 0 ? r.joinDateMs() : null;
        return new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.OK, type, tier,
                r.isSuspicious(), r.isBanned(), r.isMvSubscribed(), joined, country, null);
    }
    private record Response(String status, String userType, String trustTier, Boolean isSuspicious,
            Boolean isBanned, Boolean isMvSubscribed, Long joinDateMs, String phoneCountryCode, String reasonCode) {}
}
