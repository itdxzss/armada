# 代理 sticky session 用户名兼容

## 问题

perf2 批量上线时，988 条 `account.online.requested` outbox 在发布前进入 DEAD，错误为
“代理密码缺少 sticky session”。Grassdata 代理把 `-session-<id>` 放在 username，原
`ProxyResolver` 只识别 password 中的 `_session-<id>`。

## 调整

- `ProxyResolver` 同时支持 `_session-<id>` 与 `-session-<id>`。
- 优先从 password 提取，找不到时再从 username 提取。
- 代理 URL 继续原样透传，不修改代理分配、绑定和状态逻辑。
- 增加 username session 回归测试，并保留原 password 场景测试。

## 验证与 perf2 处理

- 新回归测试先复现 `BusinessException: 代理密码缺少 sticky session`，实现后转绿。
- `ProxyResolverTest,ProtocolCommandPublisherTest` 聚焦测试通过。
- 只部署 perf2 Armada 后端，容器 `restart=0`，API 返回 200。
- 旧 988 条 DEAD 命令引用已软删除代理，未批量原样重放；金丝雀确认后改为按状态重新触发，
  从当前代理池重新分配并生成新命令。
- outbox 快照：`/home/app/armada-deploy/rollback/outbox-sticky-session-dead-20260725.tsv`。
- 状态快照：
  `/home/app/armada-deploy/rollback/account-state.pre-sticky-session-retrigger-20260725.tsv`。
- 新生成的上线命令未再出现 sticky session DEAD；目标在线账号最终无 PENDING。
- 未提交代码，未自动回退。

## 独立观察项

恢复期间出现的 MySQL deadlock 位于群同步的 `GroupLinkHealthMapper` 与
`AccountGroupMembershipMapper`，不属于账号上线/代理分配事务，本次不扩大范围处理。
