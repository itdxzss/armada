# 第二套上线状态回写耗时取证

目标：perf2 后端 3.110.124.52，数据库 armada_perf。只读排查；没有改配置、重启或主动操作账号。

## 已核实

- 09:24 UTC 批次：1172 条 batch_online 命令，受理约 3.6 秒，outbox 发送等待平均 398ms，最大 580ms。
- 配对的 1153 条 ONLINE 事件在 09:24:54.157–09:25:06.220 产生；后端状态处理日志持续至 09:27:35.446。事件到消费接收 P50 41033ms、P95 140694ms、最大 152627ms；消费接收到状态处理日志 P50 214ms、P95 507ms。此指标不含日志之后的事务提交。
- 同期无 account.groups_sync.requested；Android 保留的 44568 行日志无主动查群/快照查询记录。一次 group.members.query.requested 属于任务查询。

## JVM 证据

09:55 UTC 附近执行一次 45 秒 JFR profile；到时自动停止。使用宿主已有 jdk.jcmd 模块 attach，没有安装 agent。

线程快照：四个 account-state 消费线程中三个阻塞于 FutureTask.get → CCJSqlParserUtil.parseStatements → TenantLineInnerInterceptor.beforePrepare。其中一个调用 AccountStateMapper.markOnlineNormalStateInternal，即 ONLINE 写回，SQL 尚未到 JDBC 执行阶段。唯一 mybatis-plus-jsqlParser-26 线程正在解析 SQL。

完整 45 秒记录，对 KafkaListenerEndpointContainer#5-* 的已记录等待汇总：

|类型|事件数|累计线程等待 ms|P50 ms|P95 ms|最大 ms|
|---|---:|---:|---:|---:|---:|
|SQL 解析 Future 等待|1243|20601.6|13.5|28.1|249.0|
|SocketRead|194|3264.9|12.6|39.6|78.1|
|其他 ThreadPark|6|70.9|12.5|13.2|13.2|

注意：累计线程等待不是墙钟时间；JFR 事件有阈值，SocketRead 未全部分类为数据库，本表也不是整个请求各阶段的完整占比。新采样证实当前共享解析器拥塞，不能逐条还原之前 09:24 批次。

MyBatis-Plus 3.5.7 本地对应版本源码确认：JsqlParserGlobal 默认解析线程数为 (availableProcessors + 1) / 2，共享固定线程池，默认缓存 null。项目 MybatisConfig 未设置解析缓存或替换该线程池。该共享队列同时服务状态、风控和其它 SQL 租户解析。

容器 cpu.stat：nr_throttled=0，无 CPU 配额节流。

## 次要问题和边界

account_import_detail 没有 (tenant_id,account_id) 索引，普通上线仍调用结算 UPDATE；EXPLAIN 使用 idx_import_detail_online_phase，估计扫描 50 行。它是优化候选，不应单独宣称为本次主因。

数据库 performance_schema.events_statements_summary_by_digest 对当前 wheel 用户拒绝 SELECT；未绕过权限。

建议优先为高频 SQL 配置有界解析缓存并验证跨租户隔离；按 CPU 调整解析器并行度作为补充。不能为提速直接关闭租户拦截器，也不能只加 Kafka 消费线程。

原始诊断文件：本机 /private/tmp/perf2-threads.txt；第二套容器 /tmp/online-diag.jfr。后者是已停止录制的性能文件。
