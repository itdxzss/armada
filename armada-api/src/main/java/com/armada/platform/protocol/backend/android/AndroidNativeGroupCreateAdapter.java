package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.routing.GroupCreateBackend;
import com.armada.platform.protocol.util.WhatsappJids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Android Zhuan 原生建群 backend。
 *
 * <p>建群成功后按需请求关闭普通成员发言；权限设置采用 best effort，失败不会丢弃已经创建的群。</p>
 */
public final class AndroidNativeGroupCreateAdapter implements GroupCreateBackend {

    private static final Logger log =
            LoggerFactory.getLogger(AndroidNativeGroupCreateAdapter.class);

    private static final String CREATE_OPERATION = "group.create";

    private static final String ANNOUNCEMENT_OPERATION =
            "group.announcement.update";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;
    private final AndroidGroupCreateResponseMapper responseMapper;

    /**
     * 创建 Android 原生建群 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param responseMapper Android 建群响应 mapper
     */
    public AndroidNativeGroupCreateAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidGroupCreateResponseMapper responseMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
        this.responseMapper = responseMapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 创建 Android 群组并按需 best-effort 关闭普通成员发言。
     *
     * @param command 统一建群命令
     * @return 统一建群结果
     * @throws ProtocolException 建群参数、传输、响应解析或应用层失败时抛出
     */
    @Override
    public GroupCreateResult create(GroupCreateCommand command) {
        try {
            List<String> participantJids = command.participants().stream()
                    .map(WhatsappJids::userJid)
                    .toList();
            AndroidDecodedResponse response = decoder.decode(client.createGroup(
                    command.account().wsPhone(),
                    command.subject(),
                    participantJids));
            if (!response.success()) {
                throw errorMapper.toGroupCreateException(
                        response,
                        command.account(),
                        command.operationId());
            }
            GroupCreateResult result = responseMapper.map(
                    response.data(), command.participants());
            requestAnnouncementOnly(command, result.groupJid());
            return result;
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    CREATE_OPERATION,
                    command.operationId());
        }
    }

    private void requestAnnouncementOnly(
            GroupCreateCommand command,
            String groupJid) {
        if (!command.announceOnly()) {
            return;
        }
        try {
            AndroidDecodedResponse response = decoder.decode(
                    client.setGroupAnnouncement(
                            command.account().wsPhone(), groupJid, false));
            if (!response.success()) {
                ProtocolException ex = errorMapper.toException(
                        response,
                        command.account(),
                        ANNOUNCEMENT_OPERATION,
                        command.operationId());
                log.warn("Android 建群关闭发言请求失败 armadaAccountId={} groupJid={} errorCode={}",
                        command.account().armadaAccountId(), groupJid, ex.errorCode());
            }
        } catch (RuntimeException ex) {
            log.warn("Android 建群关闭发言请求异常 armadaAccountId={} groupJid={} reason={}",
                    command.account().armadaAccountId(), groupJid,
                    ex.getClass().getSimpleName());
        }
    }
}
