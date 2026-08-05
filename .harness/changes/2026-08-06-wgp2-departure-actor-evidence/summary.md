# 变更记录：WGP2 退群操作人证据识别

- 日期 / 分支：2026-08-06 / `codex/simple-whatsapp-group-member-export`
- 涉及仓库：Android 协议、Armada 后端
- 状态：开发与本地验证完成

## 目标

使用 WGP2 通知操作人与唯一目标成员的身份关系，区分成员主动退群、被其他成员移出和原因未识别。

## 影响

- Android 从通知外层 `participant` / `participant_pn` 提取操作人证据。
- `remove` 且操作人与目标成员相同上报 `LEFT`；明确不同时上报 `REMOVED`；无法比较时上报 `UNKNOWN`。
- Armada 只信任携带 `WGP2_ACTOR_DIFFERENT` 证据的实时 `REMOVED`，旧节点事件继续降级为 `UNKNOWN`。
- 导出对清理后的 `REMOVED` 统一展示“被移出群组”。

## 数据、API 与 Redis

- 不变更表结构。
- Flyway `V098_1__normalize_legacy_wgp2_removed.sql` 将历史无操作人证据的
  `WGP2_NOTIFICATION + REMOVED` 改为 `UNKNOWN`，避免放开新结果后恢复旧误判。
- Android 退群 participant 事件新增可选字段 `exitEvidence`；旧事件缺少该字段时保持兼容。
- Redis 无变更。

## 关键约束

- 仅单目标 remove 且 PN/LID 身份可比较时生成证据；批量、缺失身份和跨命名空间无法对应均保持未知。
- WGP2 原始 `Action` 仍保留为 `remove`，不影响群快照和成员关系刷新。
- 发布顺序为先 Armada、后 Android；滚动期间旧 Android 的 REMOVED 不会被后端信任。

## 回滚

- 应用代码可回滚到前一提交，旧事件仍按 UNKNOWN 处理。
- 历史 REMOVED 到 UNKNOWN 的迁移不可安全逆推；回滚不恢复缺少证据的退出原因。

## 验证

- Armada 扩展回归：12 个测试类、55 个测试，0 失败、0 错误、0 跳过；覆盖事件消费、
  事实落库、缓存、导出、Flyway 版本及 H2 数据迁移。
- Armada `mvn -q -DskipTests verify`：通过。
- Android `go test ./internal/service/node/processor ./internal/armada`：通过。
- Android `go vet ./...`、`go build ./...`：通过。
- Android `go test ./...`：本次相关包通过；全仓仍被既有部署脚本 CRLF 用例及
  `pkg/noise` fixture/向量用例阻断，与本次改动文件无关。
- 按专家评审维度检查事件兼容、证据传递、Flyway 版本、历史数据口径及导出映射，
  未发现阻断项。
