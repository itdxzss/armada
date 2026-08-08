# 变更记录：营销导出复用耐久群成员快照

- 日期 / 分支 / worktree: 2026-08-08 / `1.0.2-snapshot` / 主工作树
- 需求来源: 用户反馈“账号曾在线且群成员已入库，后续离线或封禁仍应直接从库导出”
- 状态: 已完成，待部署验证

## 目标（一句话）

营销任务导出在专用成员缓存缺失时复用群详情模块保存的最后一次完整成员快照，不因观察账号离线或封禁而失败。

## 缺口拆解 / 任务清单

- [x] 为耐久快照增加按租户、群 JID 批量查询能力。
- [x] 专用营销缓存优先；仅对缺失群回退最后一次耐久完整快照。
- [x] 保留导出主查询已有的群名称和发言权限元数据。
- [x] 增加缓存服务单测和真实 Mapper XML 的 H2 租户隔离测试。
- [x] 运行专项回归并记录真实结果。

## 关键设计决策

- 不放宽在线账号查询条件；在线账号只用于两个数据库来源均无快照时的实时补采。
- 不新增表、不迁移历史数据，直接复用 `whatsapp_group_member_snapshot` 的最后一次完整快照。
- `whatsapp_group_member_cache` 包含成员增量状态，存在时优先级高于耐久快照；耐久快照只补缺失群。
- 同一群 JID 存在多个群入口时，按 `snapshot_at`、`group_link_id` 选择最新的一份完整快照，避免混合不同时点的成员。
- 目标数据库按仓库现状假设为 MySQL 8.x；新增 V105 复合索引支撑 `(tenant_id, group_jid)` 批量读取，查询不做无业务价值排序。

## 影响

- 影响模块: 群成员缓存读取、营销任务导出、群成员耐久快照 Mapper。
- 数据库变更: V105 为 `whatsapp_group_member_snapshot` 增加
  `idx_whatsapp_group_jid_snapshot (tenant_id, group_jid, snapshot_at, group_link_id)`。
- API 变更: 无。
- Redis 变更: 无。
- 错误提示: 两个数据库来源均无快照且无可用账号时，明确提示“数据库中没有群成员快照且没有可用的实际发送账号”。
- 回滚: 应用代码可回退；已执行的 V105 不修改或删除原迁移，通过后续 Flyway 迁移删除索引（如确有必要）。

## 验证（evidence-before-done）

- 修复前红灯：`mvn -Dtest=WhatsappGroupMemberCacheServiceImplTest test`，测试编译因缺少耐久快照查询和新构造依赖失败，符合预期。
- 首轮专项：缓存服务、H2 快照 Mapper、导出提供器共 17 个测试，0 失败、0 错误、0 跳过。
- 最终扩大专项：营销导出、耐久快照、成员进退事实、Flyway 合同共 69 个测试，0 失败、0 错误、4 跳过，Maven 退出码 0。
- 4 个跳过均为本机无 Docker 导致的既有 MySQL Testcontainers 用例；H2 MySQL 模式已加载真实 Mapper XML 验证按显式租户和群 JID 查询。
- 未连接第二套环境真实 MySQL，因此未执行目标库 `EXPLAIN` 或迁移 dry run；部署前应在 test2 确认 V105 执行时长及索引命中。

## 部署

- 未提交、未部署。

## 遗留 / 跟进

- 部署 test2 后，以“账号曾在线且已有 `whatsapp_group_member_snapshot`、当前已离线/封禁”的真实任务执行全量和按国家导出。
- 在 test2 执行等价查询的 `EXPLAIN`，确认使用 `idx_whatsapp_group_jid_snapshot`，并观察 V105 建索引期间数据库负载。
