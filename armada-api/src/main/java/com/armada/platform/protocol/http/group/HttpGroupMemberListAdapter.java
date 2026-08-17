package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.routing.GroupMemberListBackend;

import java.util.Arrays;
import java.util.List;

/**
 * Web/Baileys 群成员列表查询能力的 HTTP adapter。
 *
 * <p>对应协议层 {@code GET /v1/groups/{groupJid}/participants?accountId=...};
 * baseUrl 指向 master 时由协议层 master gateway 按 accountId 路由 owner worker。</p>
 */
public class HttpGroupMemberListAdapter implements GroupMemberListBackend {

    /** 群成员查询接口模板，accountId 位于 query 用于 master owner 路由。 */
    private static final String PARTICIPANTS_URI_TEMPLATE =
            "/v1/groups/%s/participants?accountId=%s";

    /** Baileys 普通管理员角色值。 */
    private static final String ROLE_ADMIN = "admin";
    private static final String PN_JID_SUFFIX = "@s.whatsapp.net";

    /** Baileys 群主角色值。 */
    private static final String ROLE_SUPERADMIN = "superadmin";

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建 Web/Baileys 群成员查询 HTTP adapter。
     *
     * @param httpExecutor 已绑定 Web 协议配置的 HTTP 执行器
     */
    public HttpGroupMemberListAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public List<GroupParticipantResult> list(GroupMemberListQuery query) {
        String accountId = query.account().protocolAccountId();
        ParticipantResponse[] response = httpExecutor.getTyped(
                PARTICIPANTS_URI_TEMPLATE.formatted(query.groupJid(), accountId),
                ParticipantResponse[].class);
        if (response == null) {
            return List.of();
        }
        return Arrays.stream(response)
                .map(HttpGroupMemberListAdapter::toResult)
                .toList();
    }

    private static GroupParticipantResult toResult(ParticipantResponse response) {
        String role = blankToNull(response.admin());
        String jid = blankToNull(response.id());
        return new GroupParticipantResult(
                jid,
                pnJid(response, jid),
                phone(response),
                ROLE_ADMIN.equals(role) || ROLE_SUPERADMIN.equals(role),
                ROLE_SUPERADMIN.equals(role),
                role);
    }

    /**
     * 还原成员的 PN 形式 JID。
     *
     * <p>成员主标识 {@code id} 可能已是 LID,协议另在 {@code phoneNumber} 给出 PN JID;
     * 据此还原可让同一个人的两种身份落在同一行成员记录上。没有可信号码来源时留空,
     * 不得由 LID 数字反推手机号。</p>
     *
     * @param response 协议返回的成员项
     * @param jid      成员主标识,可能是 PN 或 LID 形式
     * @return PN 形式 JID;没有可信来源时返回 null
     */
    private static String pnJid(ParticipantResponse response, String jid) {
        String explicitPhone = blankToNull(response.phoneNumber());
        if (explicitPhone != null) {
            return phone(explicitPhone) + PN_JID_SUFFIX;
        }
        return jid != null && jid.endsWith(PN_JID_SUFFIX) ? phone(jid) + PN_JID_SUFFIX : null;
    }

    private static String phone(ParticipantResponse response) {
        String explicitPhone = blankToNull(response.phoneNumber());
        if (explicitPhone != null) {
            return phone(explicitPhone);
        }
        String jid = blankToNull(response.id());
        return jid != null && jid.toLowerCase(java.util.Locale.ROOT).endsWith("@lid")
                ? null
                : phone(jid);
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

    private record ParticipantResponse(String id, String phoneNumber, String lid, String admin) {
    }
}
