# 四项目事实对账：群维度首次定类

- `change_id`: `2026-08-26-group-canonical-first-classification`
- 对账基线: Armada `1.0.3-snapshot` / `a57d814e0634b500e3b721ed45a7d5c0cbc5125e`
- 事实状态: `CONFIRMED`、`LIKELY`、`UNKNOWN`、`CONTRADICTED`

## 结论

用户目标与当前实现存在真实模型差距，不是文档或展示问题。当前是“同一群两个可重叠、只升不降的标签”；目标是“同一规范群一个首次写入后不变的分类”。后端数据模型、查询合同和前端筛选都需要改变。账号 baseline 关系仍有独立用途，不在本次删除。

## 后端与数据

| 事实 | 状态 | 证据 / 影响 |
|---|---|---|
| `group_link` 保存 `is_historical`、`is_post_control` 两个独立布尔值。 | `CONFIRMED` | `GroupLink.java`；Flyway `V098__group_list_history_metadata.sql`。 |
| 两个分类只允许 `0 -> 1`，所以同一行可同时为真。 | `CONFIRMED` | `GroupClassificationServiceImpl.pendingClassifications`；`GroupLinkMapper.xml` 的分类更新。 |
| baseline 和 post-control 候选最终都按 `group_link.id` 写入。 | `CONFIRMED` | `GroupClassificationServiceImpl.persistClassifications`。 |
| 查询提供 `HISTORICAL`、`POST_CONTROL`、`BOTH`。 | `CONFIRMED` | `GroupListType.java`；`GroupListCurrentMapper.xml`。 |
| `wa_group` 是租户内规范群身份，唯一键为 `(tenant_id, group_jid)`。 | `CONFIRMED` | Flyway `V120__group_data_model_foundation.sql`。 |
| `group_link` 已通过 `group_id` 关联规范群，新六表是当前群事实主表。 | `CONFIRMED` | Flyway V123 及 `.harness/changes/2026-08-15-group-data-model-rebuild.md`。 |
| 账号 baseline 与 post-control 关系仍保存在 `wa_account_group_binding`。 | `CONFIRMED` | `was_in_initial_baseline`、`first_post_control_observed_at`；它们描述账号与群的关系，不等同于群的唯一分类。 |
| `account_group_sync_state` 仍决定一个账号何时拥有完整可靠 baseline。 | `CONFIRMED` | `baseline_state`、`baseline_completeness`、`baseline_captured_at`。 |
| 群分类已影响群列表之外的营销候选。 | `CONFIRMED` | 当前 Mapper 仍以 `group_link.is_historical` 作为历史来源；迁移必须做兼容或同步切读。 |
| 最终分类事实继续只放在 `group_link` 最合适。 | `CONTRADICTED` | `group_link` 是兼容句柄；规范群身份已是 `wa_group`，群维度事实应优先归属 `wa_group`。 |

## 前端

| 事实 | 状态 | 证据 / 影响 |
|---|---|---|
| API 类型仍是两个布尔字段。 | `CONFIRMED` | `wheel-saas-pure-web/src/api/group.ts`。 |
| 双 true 时表格会渲染两枚标签。 | `CONFIRMED` | `GroupListTable.vue`。 |
| 筛选包含“同时属于两类”。 | `CONFIRMED` | `src/views/group/list/constants.ts`。 |
| 独立“历史群管理”页面展示账号 baseline/当前成员关系。 | `CONFIRMED` | `src/api/historical-group.ts` 及相应页面测试；不应和群池永久分类合并。 |
| 现有定向合同测试在当前旧口径下通过。 | `CONFIRMED` | API、列表、抽屉和筛选共 11/11；这只证明旧合同稳定，不证明新需求已实现。 |
| staging E2E 已覆盖唯一分类。 | `CONTRADICTED` | 当前 smoke 没有群分类场景。 |

## Web / Android 协议

| 事实 | 状态 | 证据 / 影响 |
|---|---|---|
| 最终分类仍应只由 Java 后端归约，不下沉协议层。 | `CONFIRMED` | 两端只提供事实与边界，不保存租户级最终分类。 |
| Web `account.groups_reported` 当前缺 `snapshotComplete/skippedGroupCount` 和本次查询边界。 | `CONFIRMED` | Web 发布链没有透传完整性、commandId/queryStartedAt/cutoff/snapshotId；Java 兼容逻辑会把缺字段且 skipped=0 当完整。 |
| Web self-membership 的 `occurredAt/eventId` 不是稳定 WhatsApp 事实。 | `CONFIRMED` | 当前发布时刻 + random eventId 无法对重放和查询期间 add 做稳定排序。 |
| Android 全量快照已有 `snapshotComplete/skippedGroupCount`。 | `CONFIRMED` | `event.go` 的 groups reported 构造和测试覆盖完整、跳过及空快照。 |
| Android coordinator 会丢失 self-membership 原始 `SourceEventID/OccurredAt`。 | `CONFIRMED` | WGP2 notification 原本携带字段，但 `group_snapshot_coordinator` 转换时改用 `Now()`，输出结构无稳定 source event key。 |
| 两协议仓都可直接判为 `VERIFIED_NOT_CHANGED`。 | `CONTRADICTED` | Java 以 `occurredAt > baselineCapturedAt` 判 post-control；缺少同步边界和稳定事件证据会永久误分类。两仓需补最小事件契约。 |

## 文档与测试环境

| 事实 | 状态 | 证据 / 影响 |
|---|---|---|
| AI 交付规范要求 D2 决策确认后才能冻结 `scope_hash`。 | `CONFIRMED` | `docs/ai-delivery-system/requirements-governance.md`。 |
| test1 基础 `staging-acceptd` 与最近 quick 可运行。 | `CONFIRMED` | 前次实例只读检查。 |
| test1 已安装 soak 和 CloudWatch observer wrapper。 | `CONTRADICTED` | 远端缺少 `test1-soak`、`cloudwatch-observer-client` 和对应 CloudWatch 证据。 |
| Runner 当前可读取 CloudWatch 指标。 | `CONTRADICTED` | Runner 身份查询 CloudWatch 返回 `AccessDenied`。 |

## 反例登记

1. A 的 post-control 事件先写，B 的 baseline 后写：必须保持 `POST_CONTROL`。
2. B 的 baseline 先写，A 的 post-control 事件后写：必须保持 `HISTORICAL`。
3. 同一个候选重复、Kafka 重放或完整快照重复：结果不变且不重复创建同步任务。
4. 两账号并发用相反类型给同一群定类：只能成功一个，不能双写。
5. 链接导入先创建了 `wa_group`，但没有可靠账号控制事实：不能因 `created_at` 自动判类。
6. 账号退出、重新上线或 baseline 刷新：群分类不变；账号 membership 事实仍可变。
7. 同一 JID 位于不同租户：是否共享分类属于待确认业务作用域。
8. 旧数据双 true、双 false、证据时间缺失：必须先报告，不得静默猜测并批量覆盖。
9. baseline 查询进行中收到 self-add，随后快照到达：必须凭同一查询 cutoff/稳定事件事实归约，不能按发布顺序猜测。
10. 同一 self-add 重放：Web/Android 必须保持稳定 source event key 与事实时间，不能生成新的“首次”候选。
