package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GroupParticipantPort} 的 HTTP adapter。
 *
 * <p>对应协议层 {@code GET /v1/groups/{groupJid}/participants?accountId=...};
 * baseUrl 指向 master 时由协议层 master gateway 按 accountId 路由 owner worker。</p>
 */
public class HttpGroupParticipantAdapter implements GroupParticipantPort {

    /** 当前适配器的低层协议调用日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(HttpGroupParticipantAdapter.class);

    /** 群成员查询接口模板，accountId 位于 query 用于 master owner 路由。 */
    private static final String PARTICIPANTS_URI_TEMPLATE = "/v1/groups/%s/participants?accountId=%s";

    /** Baileys 普通管理员角色值。 */
    private static final String ROLE_ADMIN = "admin";

    /** Baileys 群主角色值。 */
    private static final String ROLE_SUPERADMIN = "superadmin";

    /** 协议层等待 WhatsApp 成员动作完成的最大毫秒数。 */
    private static final int PARTICIPANT_MUTATION_TIMEOUT_MS = 30_000;

    /** 统一协议层 HTTP 执行器。 */
    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建群成员 HTTP 适配器。
     *
     * @param httpExecutor 已配置协议层 baseUrl、鉴权和超时的统一 HTTP 执行器
     */
    public HttpGroupParticipantAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    /**
     * 通过一个在群账号读取当前 WhatsApp 群成员快照。
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @return 归一化角色和号码后的成员列表；协议返回空 body 时返回空列表
     * @throws ProtocolException 当参数缺失或协议调用失败时抛出
     */
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

    /**
     * 调用协议层成员变更接口批量升管理员、降管理员或移除成员。
     *
     * <p>请求路径由 {@link GroupParticipantAction#wireValue()} 决定，body 中同时携带
     * {@code accountId}、目标成员列表和 30 秒协议等待上限。baseUrl 指向 master 时，
     * master gateway 使用 body 中的 accountId 把请求转发到持有该账号 socket 的 owner worker。</p>
     *
     * <p>HTTP 2xx 只表示协议请求已完成处理；真实结果必须读取 {@code partial} 和逐 JID
     * {@code status/rawStatus}。协议返回空 body 时保守标记为 partial，避免上层把缺失回执误判为成功。
     * 参数错误、权限不足、超时和网络异常由 {@link ProtocolHttpExecutor} 统一映射为
     * {@link ProtocolException}。</p>
     *
     * @param protocolAccountId 协议层账号句柄，仅用于路由 owner worker
     * @param groupJid          WhatsApp 群 JID
     * @param participants      目标成员 JID 列表，不能为空
     * @param action            成员变更动作，不能为空
     * @return 协议层 partial 标记和逐成员结果
     * @throws ProtocolException 当参数无效或协议请求失败时抛出
     */
    @Override
    public GroupParticipantBatchResult updateParticipants(
            String protocolAccountId,
            String groupJid,
            List<String> participants,
            GroupParticipantAction action) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String jid = requireText(groupJid, "groupJid");
        if (participants == null || participants.isEmpty() || action == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "协议层 group participant mutation 参数缺失");
        }
        log.debug("调用协议层批量修改群成员 action={} targetCount={}",
                action, participants.size());
        BatchResponse response = httpExecutor.postTyped(
                "/v1/groups/%s/participants/%s".formatted(jid, action.wireValue()),
                new BatchRequest(accountId, participants, PARTICIPANT_MUTATION_TIMEOUT_MS),
                BatchResponse.class);
        if (response == null) {
            return new GroupParticipantBatchResult(true, List.of());
        }
        List<GroupParticipantBatchResult.Item> results = response.results() == null
                ? List.of()
                : response.results().stream()
                        .map(item -> new GroupParticipantBatchResult.Item(
                                item.jid(), item.status(), item.rawStatus()))
                        .toList();
        log.debug("协议层批量修改群成员返回 action={} partial={} resultCount={}",
                action, response.partial(), results.size());
        return new GroupParticipantBatchResult(response.partial(), results);
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

    private record BatchRequest(
            String accountId,
            List<String> participants,
            int timeoutMs) {
    }

    private record BatchResponse(boolean partial, List<BatchItemResponse> results) {
    }

    private record BatchItemResponse(String jid, String status, String rawStatus) {
    }
}
