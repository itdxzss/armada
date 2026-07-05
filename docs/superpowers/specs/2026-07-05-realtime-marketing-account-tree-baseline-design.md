# 营销账号树实时群查询与基线过滤 - 规格

- 日期:2026-07-05
- 项目:armada
- 模块:`armada-api` / `wheel-saas-pure-web`
- 范围:新增营销任务账号群树、账号群基线、固定群组目标保存校验

## 1. 背景

新增营销任务时,前端选择账号分组会调用 `GET /api/marketing-tasks/account-tree?groupId=...` 获取账号-群树。
当前实现只读本地 `account_group_membership`,而该表由定时账号群同步异步写入。测试环境已经出现新导入账号的旧群被直接写入 `account_group_membership` 的情况:

- `account_group_baseline.baseline_group_jids` 仍为空数组;
- `account_group_membership` 已有大量旧群 JID;
- 后端日志显示 `baselineGroups=0 visibleGroups=rawGroups`。

这会导致旧群出现在营销账号树里,并可能被固定群组任务或账号动态任务使用。正确口径是:

```text
可营销新群 = 协议实时返回的当前群 - account_group_baseline.baseline_group_jids
```

## 2. 业务口径

- 账号树必须实时调用协议层查询当前账号参与群,不再仅依赖 `account_group_membership` 缓存。
- `account_group_baseline` 是导入/首次上线前旧群快照表。
- `account_group_membership` 只记录 baseline 之外的新群当前关系。
- `group_baseline_state=1` 表示待拍基线。该账号第一次实时查群只用于写 baseline,不展示这些群,也不写 membership。
- `group_baseline_state=2` 表示已拍基线。后续实时查群时,只展示并刷新 baseline 之外的新群。
- `group_baseline_state=3` 表示不启用 baseline 过滤。历史账号按当前群直接展示并刷新 membership。

## 3. 前端触发

前端行为保持:

- 打开新增营销任务抽屉时,若存在账号分组,默认选择第一个账号分组并加载账号树。
- 用户切换账号分组时,触发 `loadAccountTree(groupId)`。
- API 仍是 `GET /api/marketing-tasks/account-tree?groupId=...`。

前端无需直接调用协议层。实时查询由 `armada-api` 完成,避免浏览器暴露协议层地址和鉴权。

## 4. 后端实时账号树流程

`MarketingTaskService.accountTree(groupId)` 改为以下流程:

1. 查询账号分组下在线可用账号:
   - `account.deleted_at IS NULL`;
   - `account.account_group_id = groupId`;
   - `account.protocol_account_id` 非空;
   - `account_state.login_state = ONLINE`;
   - 无风控、未禁言。
2. 读取这些账号的 `group_baseline_state` 和 baseline JSON。
3. 按批调用协议层 `POST /v1/accounts/groups/batch`。
   - 请求体包含协议账号 ID 列表;
   - 协议层内部对每个账号执行 Baileys `groupFetchAllParticipating()`;
   - 单个账号查询失败不影响其它账号,该账号返回 `groupsError=true`。
4. 对每个账号处理协议返回群:
   - 规范化 `groupJid`,去空、去重;
   - `state=1`:将全部当前群写入 `account_group_baseline.baseline_group_jids`,更新 `group_count/captured_at`,并将账号状态改为 `2`;本次返回空 groups;
   - `state=2`:用 baseline JSON 过滤旧群,剩余新群返回给前端并刷新 membership;
   - `state=3`:不做 baseline 过滤,当前群全部返回并刷新 membership。
5. 为返回给前端的新群确保本地 `group_link` / `group_link_preview` / `group_link_health` / `account_group_membership` 有对应行。

刷新本地关系的目的不是让账号树读缓存,而是给账号动态发送和群成员查询继续复用同一事实表。

## 5. 协议层适配

协议层已有接口:

```text
GET  /v1/accounts/{accountId}/groups
POST /v1/accounts/groups/batch
```

本次不改协议层。`armada-api` 新增 HTTP 端口和 adapter:

- `AccountParticipatingGroupPort`
- `HttpAccountParticipatingGroupAdapter`

返回模型保留:

- `protocolAccountId`
- `success`
- `groups`
- `error`

群字段至少包含:

- `groupJid`
- `subject`
- `memberCount/size`
- `ownerJid/owner`
- `isAdmin`
- `announceOnly/announce`

## 6. Baseline 写入规则

新增专门 mapper 方法,不要复用当前 `markGroupSyncRequested` 的空数组写入逻辑。

待拍账号首次实时查群成功时:

```text
account_group_baseline.baseline_group_jids = 当前全部 groupJid JSON 数组
account_group_baseline.group_count = 数组长度
account_group_baseline.captured_at = 当前时间
account.group_baseline_state = 2
```

该操作必须和本次账号树处理同事务完成。若写 baseline 失败,该账号本次树节点标记 `groupsError=true`,不展示群,避免旧群被误当新群。

对于 `state=2` 的账号,不得覆盖 `baseline_group_jids`,只更新 membership 当前关系。

## 7. Membership 写入规则

`account_group_membership` 只写 baseline 过滤后的新群:

- upsert 可见新群关系;
- 本次实时返回的新群集合之外的旧 active membership 软删;
- 旧群仅保留在 baseline JSON 中,不写 active membership。

如果账号协议查询失败,不修改该账号 membership,避免一次失败把已有关系误删。

## 8. 创建任务保存校验

固定群组目标保存时去掉对 `account_group_membership` 的强依赖。原因:

- 创建抽屉的候选群来自同一次实时协议查询;
- 再查 membership 不是实时协议校验,只能校验本地缓存;
- 当前 membership 已被旧群污染,用它做准入会扩大错误影响。

保存固定群组目标时仍保留必要校验:

- 账号属于本次账号分组;
- 账号在线、无风控、未禁言;
- `group_link` 未删除;
- `group_link_preview.group_jid` 非空;
- 若账号 `group_baseline_state=2`,目标 `group_jid` 不在 baseline JSON 中。

账号动态目标保存逻辑不变:只校验账号可用,发送前再解析当前新群。

## 9. 错误处理

- 协议批量查群整体失败:返回在线账号节点,每个账号 `groupsError=true`,groups 为空,前端展示仍可加载但不可选群。
- 单账号查群失败:只影响该账号。
- 待拍账号 baseline 捕获成功但当前群全是旧群:本次 groups 为空,状态改为已拍。
- 已拍账号没有新群:groups 为空。
- 历史账号 `state=3`:直接展示当前群。

## 10. 测试

后端单元/DbTest:

- `accountTree` 对 `state=1` 账号:协议返回旧群后写 baseline JSON,不返回群,不写 membership。
- `accountTree` 对 `state=2` 账号:协议返回 baseline 群和新群,只返回/写入新群。
- `accountTree` 对 `state=3` 账号:协议返回群全部展示并写入 membership。
- 协议单账号失败时不清空已有 membership。
- 固定群组保存不再要求 `account_group_membership` 行存在。
- 固定群组保存仍会排除 baseline JSON 中的旧群。

前端测试:

- 打开新增抽屉默认加载第一个账号分组树。
- 切换账号分组触发 `fetchMarketingAccountTree`。
- 协议失败账号 `groupsError=true` 时树节点不可误选群组。

## 11. 不做范围

- 不改协议层 Baileys 实现。
- 不让前端直接调用协议层。
- 不在营销任务创建保存时同步调用协议层。
- 不清洗测试环境已污染的 `account_group_membership` 数据;数据修复另起运维脚本并确认环境后执行。
