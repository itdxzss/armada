# 营销账号树可营销群展开展示修复设计

## 目标

修复“新增营销任务”账号树中父节点显示 `0个群`，展开后却展示多条群记录的问题。

父节点计数口径保持不变：统计 Armada 本地事件同步结果中当前可营销的群。展开列表只展示这批可营销群，不展示被 baseline 排除、群链接状态不可用、健康异常或已封禁的群。

## 已确认口径

- 计数继续读取 `account_group_membership` 等本地表，不改成协议实时统计。
- 事件尚未同步到本地时，计数允许暂时落后于协议实时状态。
- 正常但属于 `account_group_baseline.baseline_group_jids` 的历史群也不在账号树中展示。
- 本次只修账号树展开接口，不改变动态营销实际发送的当前群口径。
- 不修改 Web 协议接口、账号列表群统计、群同步事件消费或数据库结构。

## 根因

账号树首屏的 `groupCount` 使用 `selectAccountTreeAccounts` 统计可营销群，会应用 baseline、群链接关系态和群健康过滤。

单账号展开当前复用了 `selectDynamicTargetGroups`。该查询服务于实际动态发送，按已确认的发送规则会保留当前 membership 中的 baseline 群和异常群，让协议发送结果链路记录最终失败原因。它不适合作为账号树的展示查询。

因此计数和展开列表承担了不同业务目的，却复用了同一条发送查询，导致展示范围大于计数范围。

## 方案

### 采用：新增账号树专用群查询

在 `MarketingTaskMapper` 新增账号树专用查询 `selectAccountTreeVisibleGroups(accountId)`：

- 只查询指定账号未软删的 membership；
- 要求 membership 的 `group_jid` 非空；
- 要求关联群链接未删除且 `membership_state IN (2, 3)`；
- 排除 `group_link_health.is_banned = 1`；
- 排除明确异常的群健康状态，仅保留未检查或正常状态；
- 当账号 baseline 状态为已拍时，排除 baseline JSON 中的历史群；
- 返回群 ID、JID、链接和名称，保持现有 API 响应结构不变。

`MarketingAccountTreeRealtimeService.accountGroups` 改为调用该专用查询。`selectDynamicTargetGroups` 继续只由动态发送链路使用，不做任何修改。

### 不采用：修改发送共用查询

直接给 `selectDynamicTargetGroups` 恢复健康和 baseline 过滤会改变 `MarketingRoundWorker` 的实际发送目标，与当前群发送设计冲突，影响范围过大。

### 不采用：前端按数量截断或隐藏

前端没有群健康、baseline 和群链接状态的完整事实，无法可靠判断哪条群记录应隐藏。

## 影响边界

本次允许修改：

- `MarketingTaskMapper` 方法声明；
- `MarketingTaskMapper.xml` 新增账号树专用查询；
- `MarketingAccountTreeRealtimeService.accountGroups` 的 mapper 调用；
- 对应 Service 单测、SQL 形状测试和账号树 DbTest。

本次明确不修改：

- `selectAccountTreeAccounts`、`selectAccountTreeAccount` 及其计数口径；
- `selectDynamicTargetGroups` 和 `MarketingRoundWorker`；
- `AccountMapper.xml` 的账号列表 `groupsNum` 统计；
- `account.groups_reported`、membership 快照写入和协议层群查询；
- 前端、API URL、DTO、表结构和 Flyway。

## 测试设计

1. Service 单测证明展开调用账号树专用查询，不再调用发送共用查询。
2. SQL 形状测试证明专用查询包含群链接关系态、健康、封禁和 baseline 过滤，并保持 `selectDynamicTargetGroups` 的当前发送口径不变。
3. 真库 DbTest 同时准备：
   - 一个正常且非 baseline 的群，展开时返回；
   - 一个正常但属于 baseline 的历史群，不返回；
   - 一个健康异常或封禁群，不返回；
   - 一个群链接关系态不允许的群，不返回。
4. 运行现有动态发送 Mapper/Worker 测试，确认实际发送查询没有发生行为变化。

## 验收标准

- 账号树父节点显示 `0个群` 时，展开列表为空。
- 父节点计数为 `N` 时，基于同一份本地快照展开得到同口径的 `N` 条可营销群；并发事件同步造成的瞬时差异除外。
- 动态发送仍按当前 membership 和发送时间边界解析目标，不受展示修复影响。
- Web 协议统计、账号列表群统计和群同步事件链路无代码改动。
