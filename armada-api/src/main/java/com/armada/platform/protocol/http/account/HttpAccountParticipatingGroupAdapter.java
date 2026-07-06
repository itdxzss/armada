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

    /**
     * 批量查询账号当前参与的 WhatsApp 群。
     *
     * <p>调用方可以传入整个营销账号分组的协议账号 ID,本方法内部会先做空值清理和去重。
     * 协议层 {@code /v1/accounts/groups/batch} 单次最多接收 200 个账号,因此这里必须按
     * {@link #PROTOCOL_BATCH_LIMIT} 切片发送。这个限制不能交给前端或业务服务记忆,否则大分组
     * 一旦直接透传 300+ 账号,协议层会返回参数校验错误,前端也会因为等待过久而超时。</p>
     *
     * <p>协议层按账号逐条返回成功或失败;这里不因为单个账号失败中断后续账号,只把协议返回的
     * per-account 结果转换成 {@link AccountParticipatingGroupResult} 后按批次顺序合并。
     * 上层再根据每个账号的 {@code success/error} 决定是否置灰账号或展示群列表。</p>
     *
     * @param protocolAccountIds 协议层账号 ID 列表,允许包含空值和重复值
     * @param concurrency        单个协议批次内部的并发度,必须大于 0
     * @return 按切片响应顺序合并后的账号查群结果
     */
    @Override
    public List<AccountParticipatingGroupResult> listBatch(List<String> protocolAccountIds, int concurrency) {
        // 账号 ID 是协议层寻址字段。这里统一 trim、丢弃空值、去重,避免把无效 ID 打到协议层。
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
            // 每片最多 200 个账号,匹配协议层 route 的 zod 上限;最后一片可以少于 200。
            List<String> batchAccountIds = accountIds.subList(start, end);
            if (accountIds.size() > PROTOCOL_BATCH_LIMIT) {
                log.info("协议层账号群查询切片发送 total={} batchStart={} batchSize={} concurrency={}",
                        accountIds.size(), start, batchAccountIds.size(), concurrency);
            }
            // postTyped 会统一处理非 2xx、网络异常和反序列化异常;这里保持 adapter 只关心业务响应体。
            BatchGroupsResponse response = httpExecutor.postTyped(
                    BATCH_GROUPS_URI,
                    new BatchGroupsRequest(batchAccountIds, concurrency),
                    BatchGroupsResponse.class);
            if (response == null || response.results() == null) {
                // 空响应无法还原到具体账号,只能记录批次信息并跳过;上层会把缺失结果视为该账号查群失败。
                log.warn("协议层账号群查询返回空结果 batchStart={} batchSize={}", start, batchAccountIds.size());
                continue;
            }
            // 保留协议层 per-account 成功/失败语义,不在这里吞掉失败账号。
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
