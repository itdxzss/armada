# 变更记录：手动下线意图闸门

- 日期 / 分支 / worktree: 2026-07-25 / `1.0.1-snapshot` / 当前工作区
- 需求来源: 用户确认手动/批量下线必须阻断旧 PROXY_FAILED 自动恢复；后续显式上线必须解除闸门
- 状态: 进行中

## 目标（一句话）

持久化账号期望登录状态，让显式下线可靠终止旧自动重试，同时保留后续显式上线与正常代理失败恢复。

## 缺口拆解 / 任务清单

- [x] 复核 perf2 下线、上线 outbox 和 Kafka 两向 lag。
- [x] 确认实际登录态不能作为自动恢复开关。
- [x] 完成设计并由用户明确选择 `desired_login_state` 方案。
- [x] TDD 覆盖下线阻断、再次上线、历史兼容和调度过滤。
- [x] 实现 Flyway、Mapper、Service 事务和 outbox 取消。
- [x] 完成专家评审与 perf2 部署验证。
- [ ] 本机 MySQL 恢复后补跑真库 DbTest。

## 关键设计决策

- `desired_login_state=1/2/NULL` 分别表示期望在线、期望离线、历史未知；历史未知继续允许恢复。
- 自动重试要求实际 `OFFLINE/PROXY_FAILED` 且期望不是 `OFFLINE`，不误用实际 `ONLINE` 条件。
- 显式下线更新期望、取消 PENDING 上线 outbox、写下线 outbox处在同一默认隔离级别事务。
- 显式上线负责把期望改回 ONLINE；协议状态事件和自动重试不得改变期望。
- 不改变既有 A/B/C 分事务设计，不添加 `READ_COMMITTED`。
- 不重置当前几乎无 lag 的生命周期命令 topic；状态 topic 保留正常事件并由新闸门跳过旧失败恢复。

## 验证（evidence-before-done）

- `mvn -Dtest=ProtocolCommandOutboxServiceImplTest,AccountOnlineCommandServiceImplTest,ProxyFailedRecoveryDispatcherTest test`
  通过：59 tests，0 failures，0 errors。
- `xmllint --noout` 校验 `AccountStateMapper.xml`、`ProtocolCommandOutboxMapper.xml` 通过。
- `git diff --check` 通过；生产代码与测试未发现 `READ_COMMITTED` 事务设置。
- JDK 17 `mvn -q -DskipTests clean package` 通过；同一 JDK 在当前 macOS 上运行 Mockito 时因
  Byte Buddy 无法 self-attach 而在 mock 初始化前失败，非业务断言失败。
- `./dbtest.sh 'AccountOnlineCommandServiceImplDbTest,ProtocolCommandOutboxMapperDbTest'`
  未完成：本地 `.env` 指向的本机 MySQL 当前不可连接，Hikari 连续重试后人工中止；不是测试断言失败。

## 部署

- 不 commit；目标为第二套测试环境 perf2。
- 初次部署因安全组未放行当前出口 IP，在制品同步前停止；放行后使用仓库部署脚本完成 perf2 后端部署。
- Flyway 日志确认 `armada_perf` 从 V061 成功迁移至 V062；`desired_login_state` 列存在。
- 后端与 Nginx 均为 `running`、`RestartCount=0`；API 返回预期 `40101` 鉴权响应。
- 后端重建后 Nginx 暂时缓存旧容器地址并返回 502；仅重启 Nginx 刷新解析，未回退后端。
- 状态事件与安卓生命周期命令两个 consumer group 的 12 个分区 lag 均为 0，未修改 offset。
- 部署后批量下线写入 `desired_offline=1128`，PENDING 上线 outbox 为 0；最新下线之后的
  `proxy_failed_reonline` 上线命令为 0。日志确认闸门已跳过一条延迟 PROXY_FAILED 自动上线。
- 最近十分钟启动错误 0、MySQL deadlock 日志 0。

## 遗留 / 跟进

- 当前状态事件 consumer group 同时出现两个内网 consumer host，部署前需确认是否为预期实例拓扑。
