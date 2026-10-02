# 群组列表群主控制关系筛选

日期：2026-09-18。状态：已完成本地开发，未提交、未推送、未部署。

## 最终业务口径

本需求最初使用“没有群主”的表述，用户随后明确：第二项判断的是 **creator（建群人）不在群**，不是没有 superadmin。本文以该澄清为准，替代分析阶段的草案。

creator 是群级创建者身份；superadmin 和 admin 是当前成员角色。创建者即使仍在群，也不能仅凭创建者身份提升为 superadmin；创建者离开后，即使其他 superadmin 留在群内，也不妨碍第二项命中。

| 多选选项 | API 枚举 | 当前判定 |
|---|---|---|
| 我方是群主 | CONTROLLED_OWNER | 同租户未删除账号对应的在群成员具有 superadmin / role=3 |
| 我方是管理员且建群人不在群 | CONTROLLED_ADMIN_CREATOR_ABSENT | 我方有在群管理员（role=2 或 3），且已知 creator 确认不在完整当前成员列表 |
| 建群人在群但未上控 | EXTERNAL_CREATOR_PRESENT | 已知 creator 匹配当前在群成员，同租户没有该号码的未删除账号 |
| 待确认 | UNKNOWN | creator 未解析，或缺少支持其缺席判定的完整快照/成员身份事实 |

选择多个选项取并集（OR），与已有分组、状态、可用管理员等条件取交集（AND）。不选表示不施加此项限制，重置清空选择。四项是查询条件，可能重叠，也不承诺覆盖所有已知群；例如 creator 已退出且我方没有管理员，不属于前两项或第三项。

“我方”沿用当前租户未删除账号口径，包含离线账号；在线执行能力由原有“可用管理员”条件独立筛选。本次没有自动踢人、退群、改权限或修改任务准入。

## 后端判定与数据边界

- GET `/api/group-links` 增加 `controlRelations`，前端发送去重后的逗号分隔枚举；后端绑定枚举集合、去重，拒绝非法枚举及集合内空项。
- `GroupListCurrentMapper.xml` 的 count 与分页 ID 查询共用筛选片段；使用 EXISTS，避免多个受控账号导致重复行，并保留租户条件。
- 当前角色/在群态取 `wa_group_participant`；creator 使用 `group_link_preview.owner_phone` 兼容投影。creatorPhone 非空本身不代表此人仍在群。
- creator 能匹配在群成员即为 PRESENT，无需通过 superadmin 反推。
- 未匹配 creator 时，必须有 `wa_group_profile.member_snapshot_at/version`，且无未知在群态或无法解析 PN 的在群成员，才能得到 ABSENT；否则 UNKNOWN。LID 无法可靠还原时不猜号码。
- 当前规则没有自行设定过期时间阈值；搜索查询不会触发协议全量刷新。
- 复用现有表，无 Flyway/schema 变更，也未批量修复历史数据。

注意：旧数据可能由旧完整性/角色映射规则写入。现有快照时间和版本不能证明已通过新规则；本次不会自动使历史快照可信。目标环境上线时须通过既有刷新入口获取新的完整成员事实，并核对最终在群态及角色；角色来源优先级可能影响旧 role=3 的纠正，需要对照实际结果。这是数据验收边界，不应把部署成功或请求刷新成功等同于数据已校准。

## 双协议改动

| 链路 | 本次改动 |
|---|---|
| Android Kafka 群资料 | Owner 只来自 superadmin，creator 独立保留；不再因为成员匹配创建者而提升角色 |
| Android IQ 单群/批量解析 | 保留无身份 participant 条目，避免解析前静默丢失而误报完整；映射前后成员数量不同则不声明 MembersComplete |
| Web Kafka 群资料 | creator 只从 owner/ownerPn 或同一身份的明确 PN/LID 对应取得，不用任意 superadmin 替代；丢失成员或 metadata.size 不符则不声明完整 |
| Web HTTP | 透传 ownerPn，保留原始 size；Java 适配器核对 size 与成员数量 |
| Android HTTP | 沿用 superadmin 角色映射，回归测试验证与 Kafka 口径一致 |

普通成员缺省 role/type 是协议正常情况，不直接当作资料损坏。未知身份和截断列表则不能用于确认 creator 缺席。本次没有改造全部成员增量事件排序策略。

## 实现入口

- 前端：`src/views/group/list/index.vue`、`constants.ts`、`group-list-filters.ts`、`composables/useGroupListPage.ts`，以及 `src/api/group.ts`。
- 后端：`GroupControlRelation.java`、`GroupLinkQuery.java`、`GroupListCurrentMapper.xml`、`HttpGroupMetadataAdapter.java`。
- Web：`protocol-layer/src/worker/group-profile-event.ts`、`protocol-layer/src/routes/groups.ts`。
- Android：`internal/armada/groups_fetcher.go`、`internal/service/node/nodes/iq.go`。

四个仓库均在主工作区 `1.0.3-snapshot` 开发。后端、前端和 Android 开工时已有其他任务修改，本次未提交、清理或覆盖这些修改。

## 本地验证

- 后端聚焦回归：7 个测试类、92 项通过，覆盖真实 H2 Mapper、生产 MyBatis 插件、事务回滚、多选 OR/其他条件 AND、租户/删除边界、分页 ID、creator 进退群、HTTP 参数校验、双协议映射。使用 Java 17 和显式 Byte Buddy agent。
- H2 不支持完整 MySQL 查询的 STRAIGHT_JOIN，并存在带参数 CTE 重复引用限制；count 使用真实 Mapper，分页测试执行从生产 BoundSql 提取的原始 page_ids 查询。完整 MySQL SQL 另外通过 JSQLParser 解析及 XML 校验。尚未在目标 MySQL 执行 EXPLAIN 或线上查询。
- 前端：20 项针对性测试、TypeScript 检查、ESLint、生产构建通过。修正一条既有头像上传测试的 timeout 参数位置断言，未修改头像上传实现。
- Web：6 套件、157 项测试及 TypeScript 构建通过。
- Android：gofmt、go vet、go build 通过；解除本机测试回环端口限制后，完整 go test 中本次相关包及其他包通过，仅 pkg/noise 有 8 条既有加密测试失败。从干净 HEAD 提取 go.mod/go.sum/pkg/noise 后，复现相同 8 条失败，未修改加密实现或其测试。
- 新增核心测试在实现前已执行并观察到失败，随后实现转绿。未访问真实数据库、未启动线上刷新或验证真实手机群权限。

## 上线与后续验收

本地开发不等于部署完成。后续确认目标环境后，分别发布前端、后端、Web、Android，并选取真实群对照 creator、完整成员列表、当前角色、租户账号和 API/UI 结果；特别验收“creator 不在但另有 superadmin”和“creator 在群但仅为普通成员/管理员”。

旧快照刷新、MySQL 执行计划及真实群业务验收尚未执行。本次无远程写入、无数据库结构变更。回滚应仅撤销本功能代码；若协议文件已有其他任务修改，必须按具体差异回退，不能整文件恢复。
