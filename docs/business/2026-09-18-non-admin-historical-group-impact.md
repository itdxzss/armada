# 普通成员历史群不导入控端：影响分析

日期：2026-09-18。范围：本地 armada `1.0.3-snapshot`（HEAD `41f5c464`，包含已有未提交修改）及同级前端当前源码。只做静态调用链分析，未访问远程环境、未统计真实群、未修改业务代码、未运行测试或部署。线上版本是否一致未验证。

## 结论

不能按列表“可用管理员＝不可用”直接丢弃群同步记录。若目标是减少列表噪声，可默认只展示存在我方管理员角色的群，并保留切换查看全部；若目标是减少存储和同步成本，需要独立设计轻量事实保留与详情按需加载，不能在现有完整快照入口直接过滤普通成员群。

“我方成员只是普通成员”“我方管理员当前离线”“角色尚未确认”“群封禁”是不同事实。同群也可能由多个受控账号共同参与，不能按单账号的 admin=false 删除租户级群资源。

## 当前证据

以下路径均相对 armada 仓库根目录。

| 事实 | 源码证据 |
|---|---|
| 页面“不可用”来自 availableAdmin，不是群健康状态 | 同级前端 `src/views/group/list/components/GroupListTable.vue:257` |
| 可用管理员要求群内角色为管理员/群主、账号未删、有协议句柄、在线且生命周期可执行 | `armada-api/src/main/resources/mapper/group/GroupListCurrentMapper.xml:10` 的 availableAdminProbe |
| 当前同步为所有可见群注册兼容句柄，未按 admin 过滤 | `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java:98`、resolveGroups |
| 完整快照缺失群会更新已有成员的在群状态并清理 activeSince | `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupCurrentSnapshotPersistenceImpl.java:246` |
| 首次基线按可见群记录；后续首次出现且晚于基线的关系会获得上控后分类 | 同文件 syncState、snapshotClassification（约 1322、1372 行） |
| 非首次基线的新发现群会送入新群即时营销逻辑 | `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportPhaseService.java:195` |
| 营销候选依赖群、账号群绑定、成员在群事实，且过滤首次基线历史群 | `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml:1408` |
| 剧本营销允许开放发言群的普通成员发送，禁言群则要求管理员 | `armada-api/src/main/resources/mapper/group/GroupScriptCandidateMapper.xml:4` |
| 历史群操作入口要求同账号组存在在线在群管理员 | `armada-api/src/main/java/com/armada/group/service/HistoricalGroupExecutionAccountSelector.java:31` |
| 群详情读取候选和管理员发现候选允许从在群账号中选择，不仅限已确认管理员 | `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml:325`、selectGroupExecutionAccounts |

当前新模型事实主体是 `wa_group`、`wa_group_profile`、`wa_group_participant`、`wa_account_group_binding` 和同步状态；不能只依据旧表名或旧注释评估影响。

## 业务影响

| 业务 | 直接不落库的影响 |
|---|---|
| 群列表、历史群列表、群数量 | 列表和统计缩小；历史群范围与真实参与范围不再一致。属于产品口径改变。 |
| 历史群拉人、管理成员、刷新链接等 | 对确实没有任何我方管理员的群，目前相关管理员操作本就无法执行；但离线管理员群不能一起剔除，后续上线应可恢复。 |
| 剧本营销、普通发言 | 有影响。开放发言群中的普通成员具有候选价值；删除关系会丢失候选或权限判断依据。此处是代码能力，不代表已证实用户线上任务正在使用这些群。 |
| 常规营销的历史群排除 | 不能漏记首次基线；否则未来重新发现或提权后可能被误认成上控后新群，进入营销候选或新群即时发送链路。能否实际发送仍受任务与权限等条件限制。 |
| 当前在群状态、已有任务 | 若过滤后仍标记完整快照，会把已有群关系错误归约为缺失；营销轮次读取当前关系，存在错误跳过目标的风险。 |
| 群详情、管理员发现与补权 | 普通成员关系也能提供查询入口；完全丢弃会损失当前实现的本地候选依据，不能假设后续一定自动恢复。 |
| 多账号共享同一群 | 单账号普通成员不代表租户没有其他管理员；必须保留其他账号、分组、任务已有引用。 |

## 两种目标分别处理

### 目标一：页面只看可管理的群

优先保留全量轻量事实，调整列表查询及默认筛选。当前已存在 availableAdmin 筛选，能隐藏当前无可用管理员群，但同时隐藏离线管理员群；若要求“只排除纯普通成员群”，应按租户内我方在群角色聚合，另外显示在线可用状态，不能复用 availableAdmin 作为身份判断。

默认筛选和分页总数需同口径；任务候选、既有任务执行不应顺带套用页面默认筛选。该方案减少展示噪声，不减少当前同步存储成本。

### 目标二：减少群导入、存储和同步成本

保留全量群 JID、账号绑定、初始基线、真实在群状态与角色观察依据。只对确定无管理员且没有业务引用的群考虑延迟详情、成员全量与其他重任务；现有主群/兼容句柄被多处 JOIN，进一步不创建这些记录属于跨模块改造。

要求：

1. 管理员身份未知与确认普通成员分开，不能把空角色当成无价值。
2. 完整快照以真实参与全集为准；不能简单把筛选后快照当全集，也不能一律改成不完整（会损害基线完成和真实退群识别）。
3. 后续提权、其他管理员账号导入或上线、显式刷新时能补齐详情与可管理入口。
4. 降权只改变角色和展示，不删除在群事实或现有任务引用。
5. 按租户隔离聚合，保留现有账号权限范围，不跨租户借用管理员。
6. 不清理存量数据，除非另行统计引用、定义迁移与恢复方案。

## 后续验证范围

若实施，应覆盖普通成员开放发言、管理员离线再上线、角色未知后确认、同群多账号、管理员降权、真实退群、旧群后续提权仍保留历史分类、已有营销任务以及不同租户同 JID。

当前只能确认代码依赖，不能给出“不导入可减少多少数据/查询”或“线上多少任务受影响”。量化需明确测试一、测试二或生产目标，再按群角色、账号在线状态、业务引用和元数据任务量做只读统计。无需为本次静态结论访问真库。
