# 群组同步协议来源与详情路由

## 目标

- [x] `group_link` 维护历史同步协议位，不新增关联表。
- [x] 群组列表直接返回 `syncProtocolMask`，不做分页后二次聚合。
- [x] 群详情按当前选中账号的实际协议路由 Web/Android metadata。
- [x] 前端展示“JSON号/六段号”标签。

## 关键决策

- `sync_protocol_mask`: `0=未知`、`1=Web/JSON号`、`2=Android/六段号`、`3=两者`。
- 字段表示历史同步来源，只增不减，不作为实时可用性依据。
- 账号同步时使用原子 `CASE` 合并来源位，避免读改写竞争。
- 列表仍只有 count 和 page 两次 Mapper 调用；page SQL 只多读一个 `group_link` 字段。
- 详情复用在线在群账号选择器，并将完整 `ProtocolAccountRef` 传给已有协议路由端口。

## 影响

- 数据库：V084 新增 `group_link.sync_protocol_mask`，迁移一次性按现有账号群关系回填；同时保留 test1 已执行的原始 V082/V083 及其校验和。
- API：`GET /api/group-links` 行新增 `syncProtocolMask`。
- Redis：无。
- 远程环境：未操作。

## 验证

- [x] 迁移、Mapper、同步服务、列表、详情和协议路由测试。
- [x] 前端契约测试、typecheck 和 build。
- [x] Mapper XML、格式和工作区差异检查。
