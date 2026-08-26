# 验收合同（冻结版）

- `change_id`: `2026-08-26-group-canonical-first-classification`
- 状态: `READY`
- `scope_hash`: `316839dc898e558b494ff6835abf15cd55d942e8a730c927ba76a6c25be61825`
- hash 输入: `change_id`、D2-01～D2-06 选择、业务范围、兼容窗口、非目标与 owner 的规范化 v1 字符串。
- 冻结时间: 2026-08-26 15:01:51 CST

## 一句话目标

让每个业务作用域内的规范 WhatsApp 群在第一次可靠上控事实出现时获得唯一分类，并在后续任何账号观察、重放和成员状态变化中保持不变。

## 已冻结范围

1. 最终事实归属 `wa_group`，分类值为 `UNCLASSIFIED / HISTORICAL / POST_CONTROL`。
2. 账号首次完整 baseline 产生历史候选；baseline 后可靠 self-add 或完整快照新增产生上控后候选。
3. 使用原子首次写，已定分类不再更新。
4. `wa_account_group_binding` 与账号 baseline 状态保留，继续服务账号关系、营销树、加入时间等既有业务。
5. 群列表 API 和前端改为单分类；旧双布尔只作一个发布窗口的互斥只读兼容。
6. 旧数据先 dry-run，按确认的证据和兜底规则回填。

## 明确非目标

- 不改变独立“历史群管理”页面的账号 baseline 与实时 membership 含义。
- 不改变 Web/Android 协议如何连接 WhatsApp、拉取群或产生原始事件。
- 不以邀请链接导入、公开预览或普通 metadata 查询直接定类。
- 不在本次删除账号 baseline、binding 或 `first_post_control_observed_at`。
- 不在未授权情况下连接真库、部署 test1、操作真实账号或修改 IAM。

## Given / When / Then

### AC-01 历史先到

- Given: 群 G 尚未定类。
- When: 账号 A 的首次完整 baseline 包含 G。
- Then: G 定为 `HISTORICAL`。
- And: 账号 B 后来以上控后事件观察到 G，分类仍为 `HISTORICAL`。

### AC-02 上控后先到

- Given: A 已完成 baseline，群 G 尚未定类且不在该 baseline。
- When: A 的可靠 self-add 或后续完整快照首次新增 G。
- Then: G 定为 `POST_CONTROL`。
- And: 后导入账号 B 的 baseline 包含 G，分类仍为 `POST_CONTROL`。

### AC-03 未分类

- Given: G 只因邀请链接导入或预览而存在。
- When: 尚无合格账号控制事实。
- Then: G 为 `UNCLASSIFIED`，不进入历史群或上控后群筛选。

### AC-04 幂等与重放

- Given: G 已定为任一分类。
- When: 相同消息重复、Kafka 重放、完整快照重复或账号重连。
- Then: 分类和首次定类证据不变，不重复产生分类副作用任务。

### AC-05 并发

- Given: G 尚未定类。
- When: 历史候选和上控后候选并发写入。
- Then: 只有一个原子首次写成功，数据库中不存在双分类；结果符合 D2-03。

### AC-06 账号状态与群分类分离

- Given: G 已定类且账号 A 当前在群。
- When: A 退群、重新上线或 membership 刷新。
- Then: A 的账号群关系可变化，但 G 的分类不变。

### AC-07 旧数据迁移

- Given: 旧数据存在双 true、双 false和单 true。
- When: 运行 dry-run 与正式 Flyway 回填。
- Then: 报告各桶数量、采用的证据和歧义清单；正式表每个群最多一个分类，结果符合 D2-04。

### AC-08 API/UI

- Given: 群列表包含三种分类状态。
- When: 用户查看和筛选群列表。
- Then: 已分类群每行最多一枚标签；无 `BOTH` 筛选；未分类群行为符合 D2-02/D2-05。

## 技术约束

- 租户隔离必须由 MyBatis 租户插件和数据库唯一约束共同验证。
- 原子首次写必须有并发 Mapper/H2 或 MySQL 语义测试，不能只做 Service mock。
- Flyway 只能前向迁移；回滚以应用兼容、停止新写和明确的补偿脚本/备份策略为准。
- 旧字段兼容期间必须由新唯一事实派生，不允许形成第二事实源。
- Kafka/Redis 不新增最终分类状态；其重放只能提交候选。
- Web/Android 必须携带可验证的完整快照边界，并为 self-membership 保留稳定 source event key 与原始事实时间；发布时刻不能冒充 WhatsApp 事实时间。

## 四项目实施状态

| 项目 | 状态 | 本期边界 |
|---|---|---|
| Armada 后端 | `CHANGED` | canonical 唯一分类、迁移、API/查询与事实归约。 |
| wheel 前端 | `CHANGED` | 单分类标签、筛选和兼容边界。 |
| Web 协议 | `CHANGED` | 快照完整性/查询 cutoff/稳定 self-membership 证据；不保存最终分类。 |
| Android 协议 | `CHANGED` | sync 边界与原始 self-membership 证据透传；不保存最终分类。 |

## 必须验证的 profiles

| 阶段 | profile | 目标 |
|---|---|---|
| 本地 | backend focused + H2/MySQL-shape | 分类原子性、租户、迁移 SQL、查询和营销回归。 |
| 本地 | frontend focused + typecheck + build | 单标签、筛选、API 兼容。 |
| test1 | `quick` | 四项目版本、服务/API、页面主路径与基础观测。 |
| test1 | `release-canary` | 用受控账号验证 AC-01/02 或等价安全夹具。 |
| test1 | `soak-60m` | 重放/重连期间分类不漂移，Runner 采集实例、Kafka、Redis 证据。 |

## 签认

- 业务 owner: 当前提出人（“全部按 A”）
- 技术 owner: Armada 交付主控
- D2 确认时间: 2026-08-26 15:01:51 CST
- D3 test1 授权: `NOT_REQUESTED`
