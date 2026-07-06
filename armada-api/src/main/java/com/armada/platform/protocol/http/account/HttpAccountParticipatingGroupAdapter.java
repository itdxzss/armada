package com.armada.platform.protocol.http.account;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 协议层账号参与群查询 HTTP 适配器。
 *
 * <p>协议层沿用 Baileys 字段名,例如 {@code accountId}、{@code size}、{@code owner}、{@code announce}。
 * 本适配器把这些 wire 字段收敛到 Armada 内部稳定结果模型,避免营销模块直接依赖协议层响应细节。</p>
 */
public class HttpAccountParticipatingGroupAdapter implements AccountParticipatingGroupPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAccountParticipatingGroupAdapter.class);

    private static final String BATCH_GROUPS_URI = "/v1/accounts/groups/batch";

    /**
     * 协议层 route 目前限制单次最多 200 个账号;这里切片，避免营销分组里账号数超过 200 时整批 400。
     */
    private static final int PROTOCOL_BATCH_LIMIT = 200;

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建协议层账号参与群 HTTP 适配器。
     *
     * @param httpExecutor 协议层 HTTP 执行器
     */
    public HttpAccountParticipatingGroupAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public List<AccountParticipatingGroupResult> listBatch(List<String> protocolAccountIds, int concurrency) {
        List<String> accountIds = normalizeAccountIds(protocolAccountIds);
        if (accountIds.isEmpty()) {
            return List.of();
        }
        if (concurrency < 1) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层账号群查询 concurrency 必须大于 0");
        }
        List<AccountParticipatingGroupResult> merged = new ArrayList<>(accountIds.size());
        for (int start = 0; start < accountIds.size(); start += PROTOCOL_BATCH_LIMIT) {
            int end = Math.min(start + PROTOCOL_BATCH_LIMIT, accountIds.size());
            List<String> batchAccountIds = accountIds.subList(start, end);
            if (accountIds.size() > PROTOCOL_BATCH_LIMIT) {
                log.info("协议层账号群查询切片发送 total={} batchStart={} batchSize={} concurrency={}",
                        accountIds.size(), start, batchAccountIds.size(), concurrency);
            }
            BatchGroupsResponse response = httpExecutor.postTyped(
                    BATCH_GROUPS_URI,
                    new BatchGroupsRequest(batchAccountIds, concurrency),
                    BatchGroupsResponse.class);
            if (response == null || response.results() == null) {
                log.warn("协议层账号群查询返回空结果 batchStart={} batchSize={}", start, batchAccountIds.size());
                continue;
            }
            response.results().stream()
                    .map(HttpAccountParticipatingGroupAdapter::toResult)
                    .forEach(merged::add);
        }
        return merged;
    }

    private static AccountParticipatingGroupResult toResult(AccountGroupsResponse response) {
        boolean success = Boolean.TRUE.equals(response.success());
        List<AccountParticipatingGroupResult.Group> groups = response.groups() == null
                ? List.of()
                : response.groups().stream().map(HttpAccountParticipatingGroupAdapter::toGroup).toList();
        return new AccountParticipatingGroupResult(
                blankToNull(response.accountId()),
                success,
                success ? groups : List.of(),
                success ? null : blankToNull(response.error()));
    }

    private static AccountParticipatingGroupResult.Group toGroup(GroupResponse response) {
        return new AccountParticipatingGroupResult.Group(
                blankToNull(response.groupJid()),
                blankToNull(response.subject()),
                response.size(),
                blankToNull(response.owner()),
                response.isAdmin(),
                response.announce());
    }

    private static List<String> normalizeAccountIds(List<String> protocolAccountIds) {
        if (protocolAccountIds == null) {
            return List.of();
        }
        return protocolAccountIds.stream()
                .map(HttpAccountParticipatingGroupAdapter::blankToNull)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record BatchGroupsRequest(List<String> accountIds, int concurrency) {
    }

    private record BatchGroupsResponse(Integer total, Integer succeeded, Integer failed,
                                       List<AccountGroupsResponse> results) {
    }

    private record AccountGroupsResponse(String accountId, Boolean success,
                                         List<GroupResponse> groups, String error) {
    }

    private record GroupResponse(String groupJid, String subject, Integer size,
                                 String owner, Boolean isAdmin, Boolean announce, Long creation) {
    }
}
