package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Web/Baileys 群成员变更能力的 HTTP adapter。
 */
public class HttpGroupParticipantAdapter implements GroupParticipantPort {

    /** 当前适配器的低层协议调用日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(HttpGroupParticipantAdapter.class);

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
     * 调用协议层成员变更接口批量添加、升管理员、降管理员或移除成员。
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

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group participants 参数缺失 " + fieldName);
        }
        return value;
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
