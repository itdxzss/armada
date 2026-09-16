# 群封禁事实与营销状态一致性修复

日期：2026-09-17。来源：第二套 perf2 两个群明确 CHAT_SUSPENDED 后，任务 276 仍显示正常并继续发送。诊断证据位于 docs/operations/evidence/group-ban-perf2-20260916/。

## 目标与设计

- 群健康、账号在群关系、单次消息 ACK、历史发送统计是独立事实。当前明确封禁优先展示，成功 ACK 不代表解除封禁。
- 复用 wa_group_profile.banned，不新增表、列或重复封禁状态存储。
- MarketingTaskMapper 详情投影新增内部 currentGroupBanned 字段；外部 groupStatus 使用已有 GROUP_BANNED 枚举值。历史计数、最后执行结果和成员关系保持原义。
- MarketingMessageSendService 为营销业务的统一发送入口，批量查询当前租户的明确封禁群后再调用 MessageSendPort。普通轮次、新群即时/延迟发送、即时重试、建群营销均经过此入口。
- 明确封禁为硬性发送限制，不允许通过关闭异常群选项绕过；未知健康/一般查询失败不升级为封禁。普通营销在 outbox 接受前拦截记 SKIPPED，不增加成功/失败计数；已接受或已完成记录不能被覆盖。
- Android 在准备前、派发前分别读取既有封禁事件缓存；等待 ACK 时新收到封禁，保留 ACK 成功但回报 BANNED。原失败诊断仍可查询最新 metadata，原测试改为直接调用失败诊断入口，避免为了触发诊断而向已知封禁群发消息。
- 前端详情列从“最后协议状态”改为“群状态”，现有 GROUP_BANNED 标签已支持，无新增 UI 枚举。

## 影响与边界

- 后端、Android 协议、前端三个主仓库本地修改，保留各仓原有账号注册等在途改动。
- 没有 DB migration、Redis key、协议 topic 或外部 API 字段变化。
- 没有修改第二套历史数据；未启动任务或发送真实测试消息。
- 已物理提交的消息无法撤回；修复保留其 ACK 结果并独立展示封禁。
- 本地修复不等于上线。尚未 commit/push 或部署第二套。

## 验证

- Android 新回归在原实现下三种时点均失败（before_prepare / after_prepare / during_ack）；修复后通过，race 检测通过。
- Go gofmt、go vet ./...、go build ./... 通过。go test ./... 在本机 Go 1.26.5 及指定 Go 1.25.1 下均仅 pkg/noise 既有 8 个握手测试失败；internal/armada 及其他包通过。从 HEAD 单独导出未修改 Noise 包复跑，复现同样失败，证据存于本次验证目录。
- Java 使用 JDK 17，聚焦普通轮次、新群、重试、详情、归一化、发送门禁和 Mapper 测试。最终结果见 validation.md。
- H2 真实加载 MarketingTaskMapper.xml、生产 MyBatis 租户插件和事务管理器验证封禁查询/业务跳过幂等性。详情多层 CTE 在 H2 2.2.224 的 PreparedStatement 参数绑定下返回空，而固定 taskId 执行同 SQL 正常；测试提取生产 SQL，固定测试 taskId 后通过生产租户插件再执行，覆盖新投影及跨租户隔离。SUBSTRING_INDEX 复用既有测试适配，不改生产 SQL。此边界不能冒称完整 MySQL 行为等价。
- 前端详情 6 项测试、针对性 ESLint、typecheck、生产 build 通过。

## 回滚

只撤回本次营销相关文件、Android message_sender 相关 diff 与前端详情列名改动。无需回滚 schema 或业务数据。若后续部署，必须记录各制品哈希并保留原版本；不把本地验证当作线上验收。
