package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupMetadataPort;
import com.armada.platform.protocol.routing.FixedAccountGroupMetadataBackend;
import java.util.List;

/**
 * {@link GroupMetadataPort} 的 HTTP adapter。
 *
 * <p>本类只将协议层 wire 结构转换为 Armada 稳定结果,不承担页面权限判断。</p>
 */
public class HttpGroupMetadataAdapter
        implements GroupMetadataPort, FixedAccountGroupMetadataBackend {

    private static final String METADATA_URI_TEMPLATE = "/v1/groups/%s/metadata?accountId=%s";
    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_SUPERADMIN = "superadmin";
    private static final String CAPABILITY_NOT_DECLARED = "协议未返回能力声明";

    private final ProtocolHttpExecutor httpExecutor;

    public HttpGroupMetadataAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    /**
     * 使用固定 Web 账号引用查询群详情。
     *
     * @param account 固定操作账号引用
     * @param groupJid 群 JID
     * @return 稳定群详情
     */
    @Override
    public GroupMetadataResult getMetadata(
            ProtocolAccountRef account,
            String groupJid) {
        if (account == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "协议层操作账号不能为空");
        }
        return getMetadata(account.protocolAccountId(), groupJid);
    }

    /**
     * 调用协议层 {@code GET /v1/groups/:groupJid/metadata}。
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          群 JID
     * @return 稳定群详情
     */
    @Override
    public GroupMetadataResult getMetadata(String protocolAccountId, String groupJid) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String jid = requireText(groupJid, "groupJid");
        MetadataResponse response = httpExecutor.getTyped(
                METADATA_URI_TEMPLATE.formatted(jid, accountId),
                MetadataResponse.class);
        List<GroupParticipantResult> participants = response.participants() == null
                ? List.of()
                : response.participants().stream().map(HttpGroupMetadataAdapter::participant).toList();
        CapabilityResponse invite = response.capabilities() == null
                ? null
                : response.capabilities().inviteViaLink();
        return new GroupMetadataResult(
                response.id(),
                response.subject(),
                response.announce(),
                response.restrict(),
                response.memberAddMode(),
                response.joinApprovalMode(),
                response.ephemeralDuration(),
                response.inviteViaLink(),
                invite != null && invite.supported(),
                invite == null ? CAPABILITY_NOT_DECLARED : invite.reason(),
                Boolean.TRUE.equals(response.isBanned()),
                true,
                participants);
    }

    private static GroupParticipantResult participant(ParticipantResponse response) {
        String role = blankToNull(response.admin());
        String jid = blankToNull(response.id());
        String phoneSource = blankToNull(response.phoneNumber());
        return new GroupParticipantResult(
                jid,
                phone(phoneSource == null ? jid : phoneSource),
                ROLE_ADMIN.equals(role) || ROLE_SUPERADMIN.equals(role),
                ROLE_SUPERADMIN.equals(role),
                role);
    }

    private static String phone(String jid) {
        if (jid == null || jid.isBlank()) {
            return null;
        }
        String normalized = jid.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group metadata 参数缺失 " + fieldName);
        }
        return value.trim();
    }

    private record MetadataResponse(
            String id,
            String subject,
            Boolean announce,
            Boolean restrict,
            Boolean memberAddMode,
            Boolean joinApprovalMode,
            Integer ephemeralDuration,
            Boolean inviteViaLink,
            Boolean isBanned,
            CapabilitiesResponse capabilities,
            List<ParticipantResponse> participants) {
    }

    private record CapabilitiesResponse(CapabilityResponse inviteViaLink) {
    }

    private record CapabilityResponse(boolean supported, String reason) {
    }

    private record ParticipantResponse(String id, String phoneNumber, String lid, String admin) {
    }
}
