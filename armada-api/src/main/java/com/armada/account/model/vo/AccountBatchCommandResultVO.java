package com.armada.account.model.vo;

import java.util.List;
import java.util.Map;

/**
 * 跨内部批次聚合后的账号生命周期命令结果。
 *
 * <p>保留原批量接口字段，新增跳过、失败和批次错误摘要。accepted 仍只表示命令已可靠写入
 * 本地 outbox，不代表账号最终 ONLINE/OFFLINE。</p>
 *
 * @param requested     本次请求或查询匹配账号数
 * @param submitted     进入内部命令批次的账号数
 * @param accepted      成功写入 outbox 的账号数
 * @param timeout       下层批量结果中的超时数
 * @param proxyRequired 下层批量结果中的代理不足数
 * @param error         兼容旧字段的失败账号数
 * @param remote        下层批量结果中的远端路由数
 * @param elapsedMs     下层批量结果累计耗时
 * @param skipped       因业务规则跳过的账号数
 * @param failed        未被 outbox 受理的账号数
 * @param skipReasons   按稳定原因 key 聚合的跳过数量
 * @param batchErrors   已清理换行并限制长度、数量的内部批次错误摘要
 * @param results       ID 接口的有界单账号结果；查询接口不返回无界明细
 * @param remoteRoutes  ID 接口的有界远端路由明细
 */
public record AccountBatchCommandResultVO(
        int requested,
        int submitted,
        int accepted,
        int timeout,
        int proxyRequired,
        int error,
        int remote,
        long elapsedMs,
        int skipped,
        int failed,
        Map<String, Integer> skipReasons,
        List<String> batchErrors,
        List<AccountBatchOnlineItemVO> results,
        List<AccountBatchOnlineRemoteRouteVO> remoteRoutes
) {

    /**
     * 将旧的单批结果转换为新聚合结构。
     *
     * <p>仅用于保留携带 protocolBackend 的旧 accounts 请求分支；该分支没有 Armada
     * 编排层跳过统计。</p>
     *
     * @param value 旧批量命令结果
     * @return 字段兼容的新结果
     */
    public static AccountBatchCommandResultVO from(AccountBatchOnlineVO value) {
        return new AccountBatchCommandResultVO(
                value.requested(),
                value.submitted(),
                value.accepted(),
                value.timeout(),
                value.proxyRequired(),
                value.error(),
                value.remote(),
                value.elapsedMs(),
                0,
                value.error(),
                Map.of(),
                List.of(),
                value.results(),
                value.remoteRoutes());
    }
}
