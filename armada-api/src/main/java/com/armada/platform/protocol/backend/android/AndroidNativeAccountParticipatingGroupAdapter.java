package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.routing.AccountParticipatingGroupBackend;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 复用 Zhuan 当前群列表接口的 Android 固定账号参与群读取 backend。
 */
public final class AndroidNativeAccountParticipatingGroupAdapter
        implements AccountParticipatingGroupBackend {

    private static final int MAX_CONCURRENCY = 16;
    private static final String CURRENT_OPERATION = "account.groups.current";
    private static final String SUMMARY_OPERATION = "account.groups.metadata-summaries";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;
    private final AndroidAccountParticipatingGroupMapper mapper;

    /**
     * 创建 Android 固定账号参与群读取 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param mapper Android 当前群响应 mapper
     */
    public AndroidNativeAccountParticipatingGroupAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidAccountParticipatingGroupMapper mapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
        this.mapper = mapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 查询 Android 账号当前参与群轻量列表。
     *
     * @param account 固定操作账号引用
     * @return 当前群轻量列表
     */
    @Override
    public List<AccountParticipatingGroupResult.Group> listCurrent(
            ProtocolAccountRef account) {
        return execute(
                account,
                CURRENT_OPERATION,
                () -> client.groups(account.wsPhone()),
                mapper::mapGroups);
    }

    /**
     * 从 Zhuan 当前群列表快照中按请求顺序生成摘要。
     *
     * <p>Zhuan 列表接口一次返回全部当前群，因此 {@code concurrency} 只做统一契约校验，
     * 不触发逐群并发请求。</p>
     *
     * @param account 固定操作账号引用
     * @param groupJids 待查询群 JID
     * @param concurrency 统一端口并发提示，范围 1 至 16
     * @return 逐群摘要
     */
    @Override
    public List<AccountGroupMetadataSummaryResult> summarize(
            ProtocolAccountRef account,
            List<String> groupJids,
            int concurrency) {
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw contextual(
                    new ProtocolException(
                            ProtocolErrorCode.BAD_REQUEST,
                            "Android 当前群摘要 concurrency 必须在 1 到 16 之间"),
                    account,
                    SUMMARY_OPERATION);
        }
        return execute(
                account,
                SUMMARY_OPERATION,
                () -> client.groups(account.wsPhone()),
                data -> mapper.mapSummaries(data, groupJids, account.wsPhone()));
    }

    private <T> T execute(
            ProtocolAccountRef account,
            String operation,
            Supplier<AndroidResponseEnvelope> request,
            Function<JsonNode, T> mapping) {
        String operationId = operationId(account);
        try {
            AndroidDecodedResponse response = decoder.decode(request.get());
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        account,
                        operation,
                        operationId);
            }
            return mapping.apply(response.data());
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    operation,
                    operationId);
        }
    }

    private static ProtocolException contextual(
            ProtocolException exception,
            ProtocolAccountRef account,
            String operation) {
        return exception.withContext(
                ProtocolBackend.ANDROID,
                operation,
                operationId(account));
    }

    private static String operationId(ProtocolAccountRef account) {
        return "armada-account:" + account.armadaAccountId();
    }
}
