# 实施影响与依赖

- `change_id`: `2026-08-26-group-canonical-first-classification`
- 状态: `LOCAL_VERIFIED`
- `scope_hash`: `316839dc898e558b494ff6835abf15cd55d942e8a730c927ba76a6c25be61825`

## 推荐技术纵切

### 1. 数据模型

- 在规范群表 `wa_group` 增加唯一分类及首次定类证据：
  - `group_classification`: `0/1/2`（未分类/历史群/上控后群）；
  - `group_classified_at`: 首次成功定类事实时间；
  - `group_classification_source`: baseline、上控后事实或迁移来源；
  - 迁移输入证据和归约规则写入永久审计表；运行期不额外复制账号或协议事件正文。
- 用 CHECK、索引、锁定 current read 和 `... WHERE group_classification = 0` 的条件更新保证一个最终事实。
- `group_link.is_historical/is_post_control` 在兼容窗口内停止独立写入；列表 API 从 `wa_group` 单值互斥派生，窗口结束后再删除旧列。

### 2. 后端

- 将 `GroupClassificationServiceImpl` 从“分别提升两个布尔”改为“提交候选、原子首次定类”。
- Mapper 以显式 `tenant_id + group_jid` 锁定并写最终分类；缺少 canonical 行时先由 registry/批量 ensure 正常登记，不回退形成第二事实源。
- 群列表、群详情和依赖历史分类的营销 Mapper 切读唯一分类。
- `GroupListType` 移除 `BOTH`，必要时增加 `UNCLASSIFIED` 仅供明确筛选。
- 元数据同步任务只对首次成功定类入队；失败候选、重放和后续相反候选不重复入队。

### 3. 前端

- API model 增加 `groupClassification` 单值，过渡期兼容旧互斥布尔。
- 群列表每行只渲染一枚分类标签，移除“同时属于两类”。
- 更新筛选转换、群列表表格和定向测试；独立历史群页面及未展示分类的详情位置保持不变。

### 4. Web 协议 / Android 协议

- 两仓实施状态更新为 `CHANGED`，但仍不保存最终分类。
- Web 最小补齐：本次 sync `commandId`、查询起点/cutoff（或等价单调 snapshotVersion）、`snapshotComplete/skippedGroupCount`、稳定 source event key，并保留 self-membership 原始事实时间。
- Android 最小补齐：sync command 上下文/查询边界；coordinator 透传 WGP2 self-membership 原始 `SourceEventID/OccurredAt`，不得重建为 `Now()`。
- Java 后端按明确的 baseline cutoff 归约候选；Kafka 同 key 顺序仅作传输保障，不能替代事实边界。

## 测试先行清单

1. 后端 Service/Mapper：历史先写、post 先写、相反候选忽略、同类重复、批量混合、事务回滚。
2. 并发：两个事务对同一 `wa_group` 提交相反候选，最终只有一个分类与一个副作用任务。
3. 租户：相同 JID 在两个租户独立定类。
4. Mapper 查询：历史、上控后、未分类及营销候选均只读一个事实源。
5. Flyway：新列/约束/索引、旧数据 dry-run 分桶、回填后零双分类、重复执行安全性。
6. 前端：单标签真实渲染、筛选请求、旧字段兼容、无 `BOTH`。
7. 跨仓：Web/Android 完整快照、查询 cutoff、稳定 self-add source key 与原始事实时间合同。

## 依赖顺序

1. D2 签认并生成 `scope_hash`。
2. 只读迁移 dry-run SQL 与本地夹具。
3. 后端失败测试。
4. Flyway + Mapper + Service 实现。
5. API 查询/营销切读及回归。
6. 前端合同与 UI。
7. Web/Android 补齐同步边界与稳定 self-membership 事实，并做重放测试。
8. 四仓本地门禁和专家评审。
9. 请求 test1 D3 授权；冻结四仓版本。
10. 部署、quick、release-canary、soak-60m、机器报告收口。

## 风险与控制

| 风险 | 控制 |
|---|---|
| 双 true 迁移误判 | 先 dry-run 分桶；业务确认兜底；保留审计证据和补偿映射。 |
| 两套事实源漂移 | 新字段为唯一写源；旧布尔只派生，设一致性门禁。 |
| 并发首次写不唯一 | 条件更新/行锁 + 真实 Mapper 并发测试。 |
| 列表正确但营销语义遗漏 | 全仓查 `is_historical/is_post_control/GroupListType` 并做营销 Mapper 回归。 |
| 未分类群被误当上控后群 | `UNCLASSIFIED` 显式存在，非可靠来源不得定类。 |
| test1 长稳报告缺 CloudWatch | 部署前单独补 Runner wrapper 和最小 IAM；未补齐只能报 `STAGING_BLOCKED`。 |
| 工作树有其他会话改动 | 只改本 change 相关文件，不清理或覆盖 `staging-accept` 在途修改。 |

## 回滚原则

- 应用灰度期间保留旧只读字段兼容，可将读路径切回旧投影，但禁止重新启用双独立写。
- Flyway 新列不做自动 DROP；回滚应用前先确认旧版本能容忍新增列。
- 正式迁移前保存分类映射审计文件；需要补偿时按映射做新的前向迁移，不手工改共享库。
- test1/生产执行方案分别审批，生产不沿用 test1 授权。
