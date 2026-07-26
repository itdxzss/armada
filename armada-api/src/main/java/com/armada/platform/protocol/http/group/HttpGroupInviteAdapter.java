package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.routing.GroupInviteBackend;

/**
 * {@link GroupInvitePort} 的 HTTP 适配器。
 *
 * <p>固定操作账号通过 query 参数交给协议层 master 路由到持有该账号 socket 的 worker。</p>
 */
public class HttpGroupInviteAdapter implements GroupInviteBackend {

    private static final String INVITE_CODE_URI_TEMPLATE =
            "/v1/groups/{groupJid}/invite-code?accountId={accountId}";

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建群邀请链接 HTTP 适配器。
     *
     * @param httpExecutor 已配置协议层 baseUrl、鉴权和超时的统一 HTTP 执行器
     */
    public HttpGroupInviteAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    /**
     * 查询固定操作账号所在群的邀请链接。
     *
     * @param account  固定操作账号引用
     * @param groupJid WhatsApp 群 JID
     * @return 群邀请码与完整邀请链接
     * @throws ProtocolException 当参数缺失、协议未返回邀请链接或协议调用失败时抛出
     */
    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public GroupInviteResult getInvite(ProtocolAccountRef account, String groupJid) {
        String accountId = requireAccountId(account);
        String jid = requireText(groupJid, "groupJid");
        InviteResponse response = httpExecutor.getTyped(
                INVITE_CODE_URI_TEMPLATE,
                InviteResponse.class,
                jid,
                accountId);
        if (response == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group invite 响应为空");
        }
        String inviteUrl = blankToNull(response.inviteUrl());
        if (inviteUrl == null) {
            // HTTP 2xx 不代表业务可用；后续进群必须使用真实完整链接，空链接不能伪装成成功结果。
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group invite inviteUrl 为空");
        }
        return new GroupInviteResult(
                blankToNull(response.groupJid()),
                blankToNull(response.inviteCode()),
                inviteUrl);
    }

    private static String requireAccountId(ProtocolAccountRef account) {
        if (account == null) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "协议层操作账号不能为空");
        }
        return account.protocolAccountId();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "协议层 group invite 参数缺失 " + fieldName);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record InviteResponse(String groupJid, String inviteCode, String inviteUrl) {
    }
}
