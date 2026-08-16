# 变更记录：群链接邀请与 Android 编辑群设置权限

- 日期 / 分支 / worktree: 2026-08-16 / `codex/group-link-permissions-armada` / `.codex-worktrees/group-link-permissions-armada`
- 需求来源: 群组详情中的“通过链接邀请”与“编辑群组设置”均需同时支持 Web 与 Android 协议；设置必须由对应群主账号执行。
- 状态: 已实现，待真实 WhatsApp 群端到端验收

## 目标（一句话）

让 Armada 群详情中的两个开关在 Web、Android 两种协议账号上都可读、可写、可确认，并由群主账号执行。

## 缺口拆解 / 任务清单

- [x] Web 协议读写 `member_link_mode`，`true -> all_member_link`、`false -> admin_link`。
- [x] Android 协议读写 `member_link_mode`，并在 metadata 中返回实时值。
- [x] Android 协议在 metadata 中返回 locked 状态，Armada 接入既有 locked/unlocked 写接口。
- [x] Armada Web/Android adapter 声明真实能力，写后使用同一群主账号回读确认。
- [x] legacy `group_link_preview` 与 current `wa_group_profile` 同步保存链接邀请实时快照。
- [x] 前端根据实时 capability 解禁两个开关，失败回滚并展示原始业务错误。
- [x] 覆盖两种协议、两个布尔方向、群主选择、双写和点击链路测试。

## 关键设计决策

- “通过链接邀请”是独立权限，使用 WhatsApp 的 `member_link_mode`；不得映射为 `member_add_mode`（“添加其他成员”）。
- 开启使用 `all_member_link`，关闭使用 `admin_link`。关闭后旧邀请链接失效由 WhatsApp 服务端执行该设置时自动重置链接；验收环境需用关闭前的旧链接做端到端验证。
- “编辑群组设置”继续使用 `unlocked/locked`；开启表示普通成员可编辑，关闭表示仅群主和管理员可编辑。
- 所有写操作继续严格选择群主账号；群主不可用时不回退到管理员或普通成员。
- `1.0.3-group` 当前仍是 legacy 读 + current 影子双写阶段，因此两个快照模型均写入，业务回读仍以 legacy 为准。

## 验证（evidence-before-done）

- Web 协议：3 个定向 Jest suite、36 个用例通过；`tsc --noEmit` 通过；OpenAPI 类型重新生成并通过契约检查。
- Android 协议：`gofmt`、Linux 目标 `go vet ./...`、`go build -buildvcs=false ./...` 通过；全部包测试目标可编译；Windows 可执行的 metadata parser 测试通过。仓库既有 `syscall.Statfs` 为 Linux-only，因此 Windows 无法执行依赖 `internal/traffic` 的测试二进制。
- Armada：Web/Android adapter、群详情写后确认、迁移、legacy H2 mapper、current/backfill SQL 契约定向测试全部通过；`mvn -DskipTests compile` 通过。
- 前端：`tsc --noEmit` 与群详情抽屉 3 个静态点击链路用例通过；`useGroupPermissions` 新增独立 `INVITE_VIA_LINK` 请求断言。Node 24 直接执行该 composable 用例时被既有 CSS loader/import-meta 环境阻断。
- Docker/MySQL 集成测试未执行：当前机器无可用 Docker environment；已用 H2 与 SQL shape 测试覆盖本次 legacy/current 双写字段。

## 部署

- commit / 环境 / 部署后验证结果: 待完成。

## 遗留 / 跟进

- 需要在接入真实 WhatsApp 测试群后验证：关闭开关后，关闭前生成的邀请链接不能再加入群组。
