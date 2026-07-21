package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.routing.GroupMemberListBackend;

import java.util.List;

/**
 * Android Zhuan 原生群成员列表查询 backend。
 */
public final class AndroidNativeGroupMemberListAdapter
        implements GroupMemberListBackend {

    private static final String LIST_OPERATION = "group.members.list";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;
    private final AndroidGroupMemberMapper memberMapper;

    /**
     * 创建 Android 原生群成员查询 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param memberMapper Android 群成员响应 mapper
     */
    public AndroidNativeGroupMemberListAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidGroupMemberMapper memberMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 查询当前 Android 账号可见的群成员列表。
     *
     * @param query 统一群成员列表查询
     * @return 归一化后的群成员列表
     * @throws ProtocolException 传输、响应解析或 Android 应用层失败时抛出
     */
    @Override
    public List<GroupParticipantResult> list(GroupMemberListQuery query) {
        try {
            AndroidDecodedResponse response = decoder.decode(
                    client.members(query.account().wsPhone(), query.groupJid()));
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        query.account(),
                        LIST_OPERATION,
                        query.operationId());
            }
            return memberMapper.map(response.data());
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    LIST_OPERATION,
                    query.operationId());
        }
    }
}
