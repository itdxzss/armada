# 账号上线 Kafka 凭据水合

- 日期 / 分支 / worktree: 2026-06-29 / main / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 账号上线 outbox 重构后 Kafka 只发轻量凭据引用,协议层无法直接上线
- 状态: 已完成 Armada 侧发送前批量水合

## 目标

账号上线命令写 outbox 时继续只保存轻量引用;dispatcher 发送 Kafka 前,由 Armada publisher 按本批 outbox row 的 tenant/account/proxy 一次性批量查出完整凭据和代理,再把协议层可直接消费的 `format + credential + proxy` 放入 Kafka envelope。

## 影响模块

- `ProtocolCommandOutboxServiceImpl`:afterCommit 主路径使用内存 row 发送,因此创建 row 时拷贝 `TenantContext` 到 `tenantId`。
- `ProtocolCommandDispatcher`:从逐条 `publisher.publish(row)` 改为一批 locked rows 调用一次 `publisher.publishBatch(rows)`,再按 outcome 逐行回写 SENT/RETRY/DEAD。
- `ProtocolCommandPublisher`:online 命令按 tenant 分组,批量读取 `account_credential` 和 `ip_proxy`,发送前构造完整 online payload。
- `AccountCredentialMapper` / `IpProxyMapper`:新增显式 tenant_id 的后台批量查询,不依赖 dispatcher 线程上的 `TenantContext`。

## 关键设计决策

- 协议层不查 Armada 数据库;完整凭据由 Armada 在 Kafka 发布前补齐。
- outbox 表不保存完整 `creds_json`、代理用户名密码,避免敏感信息长期落入命令表。
- 批量上线同一 tenant 内只查一次凭据集合、一次代理集合,不按账号逐条查库。
- 代理发送前校验仍是 `IN_USE + bound_account_id=accountId`,避免发送已失效绑定。
- 每条 row 的发送结果仍独立处理;单条凭据/代理缺失只影响该 row 的 DEAD/RETRY 结果。

## 验证

- `mvn -Dtest=ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest,ProtocolCommandOutboxServiceImplTest test`
  - 结果: `Tests run: 24, Failures: 0, Errors: 0, Skipped: 0`
- `xmllint --noout armada-api/src/main/resources/mapper/account/AccountCredentialMapper.xml armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml`
  - 结果: 退出码 0
- `mvn -Dtest=TenantInterceptorIntegrationTest test`
  - 结果: 未通过;SpringBootTest 在 Flyway 初始化时连接本地 MySQL `root@localhost` 无密码被拒绝,未进入本次变更相关 bean 校验。

## 遗留 / 跟进

- 本次只完成 Armada 侧 Kafka envelope 补齐;协议层 consumer 是否已经按该 online payload 执行上线,需要在协议层切片中继续对齐。
