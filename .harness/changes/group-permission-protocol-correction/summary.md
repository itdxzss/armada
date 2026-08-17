# 变更记录：群权限协议回读与能力纠正

- 日期 / 分支 / worktree: 2026-08-17 / Armada `1.0.3-group`；Web、Android 协议基于 `1.0.3-snapshot` 的隔离 worktree
- 需求来源: 群详情权限按钮设置后 Android metadata 漏传 `GroupJoinState`；“通过链接邀请”需改用 WhatsApp 的正式 MEX mutation
- 状态: 已实现并完成定向验证，待真实群联调

## 目标（一句话）

让群权限设置遵循“本地选择管理员 → 协议设置 → 协议成功立即返回”，并保证只调用经过协议适配的可写能力。

## 缺口拆解 / 任务清单

- [x] Android `/groups/members` 响应返回底层已解析的 `GroupJoinState`。
- [x] Armada Android metadata adapter 回归验证 `GroupJoinState=on/off`。
- [x] Web/Android 使用 `WAWebMexUpdateGroupPropertyJob` 修改独立 `member_link_mode`，不再发送猜测的 w:g2 子节点。
- [x] 更新协议契约、测试和编译验证。

## 关键设计决策

> 环境一已证明旧实现发送 `w:g2 <member_link_mode>` 后连续 10 秒无任何 WhatsApp result/error。进一步核对 WhatsApp Web 的协议实现后，确认正式路径是 persisted GraphQL MEX mutation `WAWebMexUpdateGroupPropertyJob`（query id `9418211574894172`），`update` 传预序列化的 `member_link_mode=ADMIN_LINK/ALL_MEMBER_LINK`。Web 与 Native 共用 `w:mex` envelope，因此两端统一改走该路径，不通过延长超时掩盖错误写包。

## 验证（evidence-before-done）

- Web 协议定向 Jest：3 suites、38 tests 全部通过。
- Web 协议 `npm run build`：通过。
- Android 定向 Go test：`api/service`、`internal/service/app`、`internal/service/node/processor` 全部通过。
- Android `go build ./...`、`go vet ./...`：通过（仅 sqlite3 上游 C 编译器 const warning）。
- Android `go test ./...`：本次相关包通过；全量仍有既有的 CRLF 部署脚本用例和 `pkg/noise` 向量用例失败，与本次改动无关。
- Armada Java 定向测试：22 tests 全部通过（Android metadata/settings 与 Web settings adapter）。
- Armada `mvn -DskipTests verify`：构建成功。

## 部署

- commit / 环境 / 部署后验证结果: 未部署

## 遗留 / 跟进

- 上线后需用真实群分别验证 `ADMIN_LINK` 与 `ALL_MEMBER_LINK` 的 WhatsApp 客户端表现和 metadata 回读。

## 本次提交流程（2026-08-17）

- 群权限设置不读取 metadata，也不创建 `METADATA_CHANGED` 刷新任务，避免每次切换开关都产生协议查询和后台资源消耗。
- 仍由本地快照选择在线、正常且在群内的管理员/群主，调用 Web 或 Android 对应设置接口；协议设置成功后立即返回“设置已提交”。
- 用户需要最新群状态时，仍可通过“刷新群信息”显式触发已有的 metadata 同步流程；本次仅移除权限开关的自动刷新。
