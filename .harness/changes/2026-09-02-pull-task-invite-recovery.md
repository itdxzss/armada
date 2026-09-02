# 变更记录：拉群管理员失效链接双协议恢复

- 日期 / 分支 / worktree: 2026-09-02 / 1.0.3-snapshot / 主仓库检出
- 需求来源: 用户确认 Web 与 Android 协议都兼容；群身份和群内管理员可确定时主动读取当前邀请链接，否则明确提示业务链接失效
- 状态: 已完成（纳入 2026-09-02 交付，未部署）

## 目标（一句话）

统一 Web 与 Android 的失效邀请链接语义，复用现有“读取当前邀请码并仅重试一次”流程，无法恢复时终结为链接失败而非管理员资源不足。

## 缺口拆解 / 任务清单

- [x] Web 进群明确 `400 bad-request` 不再归为 `GROUP_JOIN_UNKNOWN`
- [x] Android 异步与同步进群错误使用同一失效链接语义
- [x] 补齐 Web、Android 新邀请码重试与不可恢复终态测试
- [x] 确认刷新仅选择在线、在群的群主或管理员
- [x] 运行三个仓库的聚焦回归并复核完整 diff

## 关键设计决策

- 自动恢复只读取当前邀请码，不调用 revoke/reset，不使旧链接主动失效。
- 仅当已有 `groupJid` 且存在在线在群管理员时查询；不扫描全租户账号猜测群身份。
- 新邀请码与失败邀请码不同才重试，且最多一次；查不到群身份、管理员或不同邀请码时终结该群执行。
- 协议错误归一限定在邀请码进群操作，保留原始 code/reason 供排障；不把所有未知错误泛化为链接失效。
- 读取账号和待进群账号可使用不同协议，分别由统一 `GroupInvitePort` 与 `GroupJoinPort` 按账号 backend 路由。
- `INVITE_INVALID/INVITE_REVOKED` 恢复优先于已有 `groupJid` 的通用重启成员复核；否则已知群身份反而会绕过当前邀请码查询。

## 验证（evidence-before-done）

- Armada 聚焦链路与双协议 adapter：`mvn -Dtest='...' test`，退出码 0，78 个测试全部通过。
- Web 进群错误归一：`npm run test:unit -- --runTestsByPath src/routes/group-join-error.test.ts`，退出码 0，19 个测试全部通过。
- Android 进群分类：`go test ./internal/armada -run '^TestZhuanGroupJoinSenderClassifiesNativeFailures$' -count=1`，退出码 0；`go test ./internal/armada -count=1` 退出码 0。
- Android 静态与构建：`go vet ./...`、`go build ./...` 均退出码 0。
- 三个仓库 `git diff --check` 均退出码 0。
- TDD 红灯覆盖了：Web/Android 通用 400 的旧分类、明确群不可用优先级、非进群操作兼容性，以及已有 `groupJid` 时恢复分支曾被成员复核绕过。
- 全量基线边界：
  - Armada `mvn test` 会等待本机 MySQL；改跑排除真库测试的广域套件后执行 3611 个，7 个失败、23 个错误，均位于本次未改的 H2/MySQL schema/fixture 用例（例如缺 `creation_mode`、`system_builtin`、MySQL `FORCE INDEX` 不兼容 H2）。
  - Web `npm test` 为 112/114 suites、1322/1324 tests 通过；剩余失败是现有 Baileys app-state export/patch 与 tolerant decode 基线。`npm run build` 仍因未改的 `account-manager.ts` 缺 `resyncAppStateReadOnly` 类型失败。
  - Android `go test ./... -count=1` 中本次涉及的 `internal/armada` 通过；仅现有 `pkg/noise` 固定向量与缺少 `vectors.txt` 失败。

## 交付与部署

- commit / push: 随本变更记录一并交付至 `origin/1.0.3-snapshot`
- 环境 / 部署后验证结果: 未部署

## 遗留 / 跟进

- Android 当前没有邀请链接 revoke/reset 能力；本需求不包含主动重置。
- 未执行线上协议联调；本次证据为本地主仓库代码、聚焦测试、构建与静态检查。
