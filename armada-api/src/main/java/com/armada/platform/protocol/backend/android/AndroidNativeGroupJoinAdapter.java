package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.routing.GroupJoinBackend;

/**
 * Android Zhuan 原生进群 backend。
 *
 * <p>原生进群成功后继续查询目标群成员，只有找到当前账号才返回 JOINED；未找到返回
 * PENDING_APPROVAL，确认调用失败则抛 JOIN_RESULT_UNCONFIRMED。</p>
 */
public final class AndroidNativeGroupJoinAdapter implements GroupJoinBackend {

    private static final String JOIN_OPERATION = "group.join";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupJoinErrorMapper errorMapper;
    private final AndroidGroupJoinResponseMapper responseMapper;
    private final AndroidGroupMembershipVerifier verifier;

    /**
     * 创建 Android 原生进群 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 原生业务错误 mapper
     * @param responseMapper Android 邀请与成功响应 mapper
     * @param verifier Android 群成员确认器
     */
    public AndroidNativeGroupJoinAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupJoinErrorMapper errorMapper,
            AndroidGroupJoinResponseMapper responseMapper,
            AndroidGroupMembershipVerifier verifier) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
        this.responseMapper = responseMapper;
        this.verifier = verifier;
    }

    /**
     * 返回当前实现支持的 Android 协议后端。
     *
     * @return Android 协议后端
     */
    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 执行 Android 原生进群并二次确认真实入群结果。
     *
     * @param command 统一进群命令
     * @return 包含群 JID 和 JOINED/PENDING_APPROVAL 的统一结果
     * @throws ProtocolException 输入、原生进群或成员确认失败时抛出
     */
    @Override
    public GroupJoinResult join(GroupJoinCommand command) {
        try {
            String inviteCode = responseMapper.inviteCode(command.inviteLinkOrCode());
            AndroidDecodedResponse response = decoder.decode(
                    client.join(command.account().wsPhone(), inviteCode));
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        command.account(),
                        JOIN_OPERATION,
                        command.operationId());
            }
            String groupJid = responseMapper.groupJid(response.data());
            GroupJoinOutcome outcome = verifier.verify(
                    command.account(),
                    groupJid,
                    command.operationId());
            return new GroupJoinResult(groupJid, outcome);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    JOIN_OPERATION,
                    command.operationId());
        }
    }
}
