package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.Arrays;
import java.util.List;

/**
 * {@link GroupParticipantPort} 的 HTTP adapter。
 *
 * <p>对应协议层 {@code GET /v1/groups/{groupJid}/participants?accountId=...};
 * baseUrl 指向 master 时由协议层 master gateway 按 accountId 路由 owner worker。</p>
 */
public class HttpGroupParticipantAdapter implements GroupParticipantPort {

    private static final String PARTICIPANTS_URI_TEMPLATE = "/v1/groups/%s/participants?accountId=%s";
    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_SUPERADMIN = "superadmin";

    private final ProtocolHttpExecutor httpExecutor;

    public HttpGroupParticipantAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public List<GroupParticipantResult> listParticipants(String protocolAccountId, String groupJid) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String jid = requireText(groupJid, "groupJid");
        // 协议 worker 已有契约是 accountId 放 query string。
        // 当 baseUrl 指向 master 时,master gateway 也靠这个 accountId 做 owner worker 路由。
        //
        // 这里故意不提前 URLEncoder.encode: RestClient 接收 URI 模板字符串后会按实际请求处理。
        // 如果这里先手动编码,包含 @ 的 groupJid 会被二次编码成 %2540,worker 路由还能到,
        // 但实际 path 已经不是原始群 JID 形态。
        ParticipantResponse[] response = httpExecutor.getTyped(
                PARTICIPANTS_URI_TEMPLATE.formatted(jid, accountId),
                ParticipantResponse[].class);
        if (response == null) {
            return List.of();
        }
        return Arrays.stream(response).map(HttpGroupParticipantAdapter::toResult).toList();
    }

    private static GroupParticipantResult toResult(ParticipantResponse response) {
        String role = blankToNull(response.admin());
        String jid = blankToNull(response.id());
        // Baileys 返回的 admin 字段普通成员为空;admin 表示管理员,superadmin 表示群主。
        // 对外同时保留原始 role,避免后续页面需要展示协议层原值时再改契约。
        return new GroupParticipantResult(
                jid,
                phone(jid),
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
        // 多设备 JID 可能形如 8613...:12@s.whatsapp.net,页面展示号码时只保留主号码。
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
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group participants 参数缺失 " + fieldName);
        }
        return value;
    }

    private record ParticipantResponse(String id, String admin) {
    }
}
