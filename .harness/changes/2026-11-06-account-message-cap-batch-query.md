# 变更记录：账号“新聊天额度”批量查询

- 日期 / 分支 / worktree: 2026-11-06 / `1.0.3-snapshot` / 主仓库（Armada、前端、Android 协议层三仓同分支）
- 需求来源: 用户要求把 Claude/Codex 昨天的额度查询探针做成页面功能：在账号列表勾选多个账号，批量查询各号的“新聊天额度”；
  WhatsApp 返回的所有字段都要解析并能看到含义，供后续做实验。页面文案用“新聊天额度”。
  探针提交：`f32c1d6`、`759c4f4`、`2d8cb72`（分支 `codex/message-cap-argo-20261105`）。
- 状态: 已完成（未部署）

## 目标（一句话）
账号列表“批量操作 → 查询新聊天额度”，对勾选账号各自的在线会话各查一次，抽屉展示全部字段与原始响应。

## 缺口拆解 / 任务清单
- [x] Go：并入探针三个提交（Argo 解码 + 接口 `GET /ws/v1/account/message-cap/:key`）
- [x] Go：补 `subscriptionStatus`、`extensions`、GraphQL `errors` 明细、`?debug=1` 原始响应
- [x] Armada 协议层：`AccountMessageCapPort` + Routing + Android backend，Web 明确返回不支持
- [x] Armada 账号域：`POST /api/accounts/message-cap/query`（≤100 账号，8 并发，逐账号隔离）
- [x] 前端：抽屉 + composable + 展示函数，批量操作菜单新增“查询新聊天额度”
- [ ] 部署 perf2 联调（需确认目标环境）
- [ ] 修 Web 协议层同名接口 `armada-protocol/protocol-layer/src/routes/restriction.ts` 的 null→0 / -1 算成剩余 0 的问题后，再放开 Web 账号

## 关键设计决策
- **实时查询、不落库。** 额度随发送实时变化，只在抽屉里展示；做实验需要留存时再加表，避免现在引入 Flyway 与保留策略。
- **-1 原样保留，不推断。** 官方 Android 客户端（2.26.36.73）对 `total_quota < 0` 判为无效并丢弃，所以 `-1` 不是正常额度；
  七个测试号均返回 `total=-1, used=0, capping=NONE, ote/mv=NOT_ELIGIBLE`。页面标“平台特殊值”，`remaining` 为空显示“未知”，
  不显示“无限”。推测与 AB 开关 `wa_individual_new_chat_msg_capping_enabled`（默认 false）有关，**尚未验证**。
- **数值全程字符串。** 避免 JSON number 精度丢失（>2^53）并区分真实 0 与未知。
- **失败不丢诊断。** Go 在 400/408/409/502 里放结构化诊断；`ProtocolHttpExecutor` 新增 `getTypedAcceptingStatuses` 读取这些响应体，
  平台明确拒绝映射为 `REJECTED` 结果（不是异常）。离线（Go `Code=1010`）映射 `ACCOUNT_NOT_ONLINE`。
- **GraphQL errors 明细对运营可见。** 与探针原设计（不回显上游文本）不同：message 截断 200 字符并清除控制字符，最多 8 条，
  `path`/`extensions` 超 512 字节丢弃；`Error()` 字符串仍不含上游文本。数值/状态校验失败的原值仍不回显。
- **单次 ≤100 账号、共享 8 线程池。** 避免一次大批量压垮协议层；前端超过 100 自动分批顺序请求。
- **权限沿用 `tenant:account:view`**（与 probe/refresh-status 一致）；只读，不登录不发送不重试。
- 被否决：沿用 tier-check 的“任务 + 明细表 + 调度器”异步模型——额度查询单账号约 1 秒，同步足够，异步会引入表和状态机。

## 验证（evidence-before-done）
- Go：`go build ./...`、`go vet ./...` 通过；`go test ./...` 仅 `pkg/noise` 失败，在干净 HEAD 同样失败（与本次无关）。
- Armada 聚焦：`mvn -o test -Dtest='AccountMessageCap*Test,AndroidAccountMessageCapAdapterTest,RoutingAccountMessageCapPortTest,ProtocolHttpExecutorTest,ProtocolConfigurationTest,AccountDomainDependencyGuardTest'` → 28 通过。
- 前端：`vue-tsc --noEmit --skipLibCheck`、`eslint --max-warnings 0`（改动文件）通过；展示函数 8 个与 API 2 个测试通过；
  composable 5 个测试需带 css 桩与 armada 替身的临时 loader 才能在本机跑通（本机 Node 23/24 下仓库既有 composable 测试同样无法直接运行）。

## 部署
- 未部署。联调前需确认目标环境（perf2 / test1），Go 节点与 Armada 需同时升级。

## 遗留 / 跟进
- 验证 `-1` 的含义：拉取这些号的 AB 开关（ID 20865、17845、18967、20649、19942、20667、19781），与一个非 `-1` 的号对比。
- Web 协议层同名接口的 bug（见上）。
- `MessageCappingInfoNotification` 推送目前只打日志，可用于实时刷新。
- 测试样本 `subscription` 的 name 用了 `TEST_PLAN`，不在客户端枚举（仅 `MP4B`）；是合成数据，仅用于验证未知枚举值原样保留。
