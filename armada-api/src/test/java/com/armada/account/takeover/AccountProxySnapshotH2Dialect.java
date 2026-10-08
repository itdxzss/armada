package com.armada.account.takeover;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.sql.Connection;
import java.util.regex.Pattern;
import org.apache.ibatis.executor.statement.StatementHandler;

/**
 * 仅将存量代理快照的 MySQL UPDATE JOIN 语法转换为 H2 MERGE，不替换 Mapper 或执行结果。
 *
 * <p>此测试拦截器必须放在生产租户插件之后；保留已注入的租户条件、JOIN 条件、SET 和参数顺序。
 * 新抢登 SQL 不经过转换；本适配不声称覆盖 MySQL InnoDB 的锁和优化器行为。</p>
 */
final class AccountProxySnapshotH2Dialect implements InnerInterceptor {

    /** 唯一允许转换的存量 MySQL 方言 statement。 */
    private static final String SNAPSHOT_STATEMENT =
            "com.armada.account.mapper.AccountStateMapper.updateProxySnapshotsInternal";

    /** 精确限定已确认的代理快照结构；生产 SQL 改变时显式失败而不是放宽条件。 */
    private static final Pattern SNAPSHOT_SQL = Pattern.compile(
            "^UPDATE account_state state_row JOIN \\((.+)\\) snapshot "
                    + "ON snapshot\\.account_id = state_row\\.account_id SET (.+) WHERE (.+)$");

    /** {@inheritDoc} */
    @Override
    public void beforePrepare(StatementHandler statementHandler, Connection connection, Integer transactionTimeout) {
        var handler = PluginUtils.mpStatementHandler(statementHandler);
        if (!SNAPSHOT_STATEMENT.equals(handler.mappedStatement().getId())) {
            return;
        }
        var boundSql = handler.mPBoundSql();
        var matcher = SNAPSHOT_SQL.matcher(boundSql.sql().replaceAll("\\s+", " ").trim());
        if (!matcher.matches()) {
            throw new IllegalStateException("代理快照 SQL 结构已变化，必须重新核对 H2 方言适配");
        }
        // H2 派生表需要明确参数列类型，CAST 不改变原 JDBC 参数个数与绑定顺序。
        String snapshots = matcher.group(1)
                .replace("? AS account_id", "CAST(? AS BIGINT) AS account_id")
                .replace("? AS truth_ip", "CAST(? AS VARCHAR(45)) AS truth_ip")
                .replace("? AS proxy_country", "CAST(? AS VARCHAR(64)) AS proxy_country")
                .replace("? AS proxy_source", "CAST(? AS VARCHAR(64)) AS proxy_source")
                .replace("? AS updated_at", "CAST(? AS BIGINT) AS updated_at");
        boundSql.sql("MERGE INTO account_state state_row USING (" + snapshots + ") snapshot "
                + "ON snapshot.account_id = state_row.account_id AND (" + matcher.group(3) + ") "
                + "WHEN MATCHED THEN UPDATE SET " + matcher.group(2));
    }
}
