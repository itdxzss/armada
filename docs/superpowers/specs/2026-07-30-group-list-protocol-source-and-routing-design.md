# 群组同步来源与详情协议路由设计

## 背景

群组列表当前已经通过 `group_link`、导入批次、预览、健康状态和管理员结果聚合展示数据。为了展示协议而在每次分页后再关联 `account_group_membership/account`，会增加一次当页 `IN (...)` 聚合查询和多次内存遍历。最大页大小允许 1000 条，这种读取放大不值得。

另一个独立问题是群详情：现有执行账号选择器已经从 `account_group_membership + account + account_state` 选出在线且仍在群内的账号，并携带 `protocol_id`，但 metadata 读取仍调用只接受 Web 协议账号句柄的旧端口，导致 Android 六段号账号也可能被送去 Web 协议查询。

## 目标与口径

- 群组列表展示“曾被哪些协议同步观察过”，不是实时可用协议。
- `WEB` 展示为“JSON号”，`ANDROID` 展示为“六段号”。
- 同一群先后被两类协议观察时同时展示两个标签。
- 群详情和后续实时操作按当前选中的在线在群账号协议路由，不根据同步来源字段路由。
- 不新增关联表，不在群组列表查询中关联账号群关系表。

## 方案比较

### 方案 A：分页后实时聚合账号群关系

优点是能表达当前可用协议；缺点是每次列表请求增加聚合 SQL、`IN (pageSize)` 和结果回填，列表读取路径复杂。放弃该方案。

### 方案 B：群表维护同步来源位掩码，操作时按账号路由

在 `group_link` 增加一个小字段记录历史同步来源，账号同步观察群时原子按位合并；列表直接读取字段。详情复用现有账号选择器，再调用已有 Web/Android 路由端口。该方案读路径最简单，也是本次采用方案。

### 方案 C：新增群协议关联表

模型表达清楚，但需要新增表、关联查询和维护逻辑，对只有两个固定协议的场景过重。放弃该方案。

## 数据模型

`group_link` 新增：

```sql
sync_protocol_mask TINYINT NOT NULL DEFAULT 0
```

位定义：

- `0`：未知或尚未被账号同步观察。
- `1`：Web/JSON 协议观察过。
- `2`：Android/六段号协议观察过。
- `3`：两类协议都观察过。

字段表达历史同步来源，只增不减。账号离群、离线或删除不清除位，避免把历史来源误当成实时能力。迁移使用现有 `account_group_membership + account.protocol_id` 一次性回填存量数据；该聚合只在部署迁移时执行，不进入运行时列表查询。

## 写入流程

账号群同步服务处理某账号上报的群时，根据该账号 `protocol_id` 得到来源位：

- `ANDROID` → `2`
- 空值、未知值或 `WEB` → `1`，与平台现有兼容规则一致

`upsertAccountObservedGroup` 插入新群时写入来源位；命中已有 URL 时使用等价于按位或的原子 `CASE` 合并（兼容 MySQL 与测试使用的 H2 MySQL mode）：

```sql
sync_protocol_mask = CASE
  WHEN sync_protocol_mask = 0 THEN #{syncProtocolMask}
  WHEN sync_protocol_mask = #{syncProtocolMask} THEN sync_protocol_mask
  ELSE 3
END
```

因此同一群后续被另一类协议观察时自然从 `1/2` 变成 `3`，不需要查询旧值，也不会产生读改写竞争。

## 群组列表读取

删除本次新增的 `selectAvailableBackends`、聚合投影和 Service 回填逻辑。原分页 SQL只读取：

```sql
g.sync_protocol_mask AS syncProtocolMask
```

API 返回 `syncProtocolMask` 数字，前端根据位值展示标签。每行本来就需要渲染一次，不新增后端批量查询、映射表或额外遍历。

## 群详情协议路由

保留现有 `GroupExecutionAccountSelector`：它只为当前群查询一次，选择在线、仍在群内且协议身份完整的账号。

把 `GroupDetailProtocolPorts.metadata` 从旧的 `GroupMetadataPort` 改为已有 `FixedAccountGroupMetadataPort`，调用方式改为：

```java
protocolPorts.metadata().getMetadata(account.protocolRef(), target.groupJid());
```

`RoutingFixedAccountGroupMetadataPort` 根据 `account.protocolRef().backend()` 分发：

- Web 账号 → `HttpGroupMetadataAdapter`
- Android 账号 → `AndroidNativeFixedAccountGroupMetadataAdapter`

如果同一群两类账号都在线，仍沿用执行账号选择器现有的管理员优先、最近在群优先规则；选中哪个账号就走哪个协议，不额外引入协议优先级。

## 兼容与错误处理

- 导入但尚未被账号同步观察的群，`syncProtocolMask=0`，列表显示 `-`。
- 旧前端忽略新增字段，不受影响。
- Android metadata 当前无法提供的群设置继续返回未知值，由现有详情降级展示处理。
- 没有在线在群账号或协议读取失败时，沿用当前 `liveStateAvailable=false` 的降级语义。
- 同步来源字段不参与实时路由，避免来源陈旧导致调用错误协议。

## 测试与验收

- Mapper 测试验证新群写入来源位、同一群跨协议重复同步后按位合并为 `3`。
- 迁移结构/SQL 测试验证字段和存量回填语句存在。
- 群组列表 Service 测试验证不再调用协议聚合 Mapper，API 直接返回来源掩码。
- 群详情测试验证 Android 账号以完整 `ProtocolAccountRef` 调用路由 metadata 端口，Web 账号保持 Web 路由。
- 前端测试验证 `0/1/2/3` 分别显示 `-`、JSON号、六段号、双标签。
- 运行后端定向测试、Mapper XML 校验、前端测试、typecheck 和 build。

## 不在本次范围

- 不改变执行账号选择器的优先级。
- 不增加群组列表协议筛选。
- 不维护“当前可用协议集合”缓存。
- 不修改远程数据库或部署环境。
