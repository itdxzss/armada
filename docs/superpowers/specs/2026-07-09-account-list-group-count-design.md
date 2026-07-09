# 账号列表群组数量统计设计

- 日期:2026-07-09
- 项目:armada
- 模块:`armada-api` / `wheel-saas-pure-web`
- 范围:账号列表好友/群展示中的群组数量口径,账号上控后群关系与加入时间记录

## 1. 背景

账号列表当前已有“好友 / 群”列,但 `friendsNum` 和 `groupsNum` 在后端仍是占位值。
本次只落群组数量。好友数暂不做,继续返回 0。

业务需要在账号列表展示账号当前有多少个上控后群组。历史群不能计入,并且后续营销任务需要按账号加入群的时间筛选可发送群。

现有系统已经具备基础模型:

- `account.group_baseline_state` 区分账号群基线状态;
- `account_group_baseline` 保存账号上控前已有群 JID;
- `account_group_membership` 保存账号当前可见群关系;
- 协议层可在 WA 群组变更后拉取当前全部群并回传 Armada。

本设计在现有 baseline 模型上收敛口径,不再让营销账号树懒加载捕获 baseline。

## 2. 业务口径

- 账号上控时间点定义为账号首次上线成功。
- `group_baseline_state=1(PENDING)`:待拍基线。首次收到当前群列表时,只写 baseline,不写可统计 membership。
- `group_baseline_state=2(CAPTURED)`:已拍基线。后续收到当前群列表时,用 `当前全部群 - baseline` 得到上控后群组。
- `group_baseline_state=3(DISABLED)`:不启用 baseline 过滤,用于存量账号兼容。
- 账号列表群组数量只统计 `account_group_membership.deleted_at IS NULL` 的当前有效关系。
- 好友数本期不做,账号列表继续展示 0。

## 3. 加群时间

WhatsApp 不提供稳定的真实加群时间。本系统的 `joined_at` 定义为:

```text
Armada 首次探测到该账号在上控后进入该群的时间
```

写入规则:

- 对 `CAPTURED` 账号,协议回传当前全部群后先过滤 baseline。
- 过滤后首次出现在 `account_group_membership` 的群,写 `joined_at = now`。
- 已存在 active membership 的群,保留原 `joined_at`,只更新 `last_seen_at`。
- 本次回报中没有出现的 active membership,写 `deleted_at`,不再计入账号列表群组数量。
- 如果同一账号离开群后又重新进入同一群,重新激活时应写新的 `joined_at`,表示本轮重新探测到的加入时间。

## 4. 后端数据流

### 首次上线成功与 baseline

1. 协议层回传账号状态 `ONLINE`。
2. Armada 将账号收敛为在线正常状态。
3. 账号若仍是 `group_baseline_state=PENDING`,后续收到的第一份当前群列表只用于捕获 baseline。
4. 捕获成功后写:
   - `account_group_baseline.baseline_group_jids = 当前全部 groupJid JSON`;
   - `account_group_baseline.group_count = 当前群数量`;
   - `account_group_baseline.captured_at = 本次同步时间`;
   - `account.group_baseline_state = CAPTURED`。
5. 本次不写 `account_group_membership`,避免把上控前历史群计入账号列表。

现有 `AccountGroupMembershipReportServiceImpl` 的 PENDING 分支保留该行为。

### 群组变更同步

1. WA 群组变更通知协议层。
2. 协议层获取该账号当前全部参与群。
3. 协议层回传 Armada `account.groups_reported`。
4. Armada 读取账号 baseline 状态:
   - `PENDING`:只拍 baseline 并返回;
   - `CAPTURED`:过滤 baseline 后刷新 membership;
   - `DISABLED`:直接刷新 membership。
5. 刷新 membership 后,账号列表 SQL 聚合 active membership 数量。

## 5. 数据库调整

`account_group_membership` 增加字段:

```sql
joined_at BIGINT DEFAULT NULL COMMENT '账号上控后首次探测到进入该群的时间(epoch毫秒)'
```

建议同步补充索引以支持后续营销任务按加入时间筛选:

```sql
KEY idx_account_group_membership_account_joined (tenant_id, account_id, deleted_at, joined_at)
```

历史已有 active membership 的 `joined_at` 可回填为 `created_at`,表示只能追溯到本地首次创建时间。

## 6. 账号列表接口

`GET /api/accounts` 返回:

- `friendsNum`:本期继续为 0;
- `groupsNum`:当前账号 active membership 数量。

实现方式:

- `AccountListVoRow` 增加 `groupsNum`;
- `AccountMapper.selectPage` 增加按账号聚合的群组数量字段;
- `AccountConverter` 取消 `groupsNum` 固定 0 的映射;
- `friendsNum` 仍固定 0。

前端 `wheel-saas-pure-web` 已经将 `groupsNum` 映射到 `groups_num`,账号列表列保持不变。

## 7. 营销账号树调整

不再允许营销账号树懒加载捕获 baseline。

调整后:

- 营销账号树只读取/刷新已按协议群变更链路维护好的群关系;
- 如果账号仍是 `PENDING`,树接口不应为了展示而主动拍 baseline;
- 账号可营销群仍只来自 baseline 之外的新群。

这样 baseline 捕获只受账号首次上线成功后的协议群列表回报驱动,不会被页面打开时机影响。

## 8. 错误与边界

- 协议群列表回报失败时,不清空已有 membership。
- `PENDING` 账号 baseline 捕获失败时,不得写 membership。
- `CAPTURED` 账号收到空群列表时,应软删该账号现有 active membership,账号列表群数变为 0。
- `DISABLED` 账号用于存量兼容,当前全部群都可写 membership 并计数。
- 列表不做长期缓存口径,以数据库当前 active membership 聚合结果为准。

## 9. 测试

后端:

- PENDING 账号收到群列表后只写 baseline,不写 membership,账号列表群数为 0。
- CAPTURED 账号收到 baseline 群和新群后,只写新群 membership,账号列表群数等于新群数。
- 新群首次写入时设置 `joined_at`;重复同步不覆盖 `joined_at`。
- active membership 在后续同步缺失时软删,账号列表群数减少。
- 离群后重新入群时重新设置 `joined_at`。
- 账号列表 `groupsNum` 从 active membership 聚合,不再恒为 0。
- 营销账号树不再对 PENDING 账号执行 baseline 捕获。

前端:

- API 映射继续将 `groupsNum` 映射为 `groups_num`。
- 账号列表“好友 / 群”列显示 `0 / groups_num`。

## 10. 不做范围

- 不实现好友数。
- 不新增实时推送链路;列表刷新沿用现有前端刷新机制。
- 不修改协议层 Baileys 群列表字段语义。
- 不处理生产/测试环境历史脏数据清洗;如需清洗需单独确认目标环境和脚本。
