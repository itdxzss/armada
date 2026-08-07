# 群组列表仅展示上控管理员号码设计

## 背景

群组列表当前从 `whatsapp_group_member_snapshot` 聚合最后一次完整成员快照中的全部群主和管理员号码，并通过 `admin` / `adminPhones` 返回前端。因此，未录入 Armada 的外部管理员也会出现在“全部管理员号码”列中。

本次调整后，该列只展示当前租户已上控的管理员号码。群主属于管理员角色；如果群主号码是当前租户的有效上控账号，也必须展示。

## 已确认口径

- 角色事实继续取最后一次成功的完整成员快照。
- `is_admin = 1` 同时覆盖管理员和群主；现有快照写入逻辑已保证群主落库时 `is_admin = 1`。
- “已上控”指同租户 `account` 表中号码相同且 `deleted_at IS NULL` 的账号。
- 上控管理员即使离线、风控或当前不可执行，也保留在“全部管理员号码”列。
- 非上控管理员和非上控群主不展示。
- “可用管理员”字段继续沿用现有在线、正常、在群、有效协议绑定条件，不与本次展示口径合并。

## SQL 设计

只修改 `GroupLinkMapper.xml` 中现有 `admins` 派生表：在成员快照与聚合之间增加一次同租户 `account` 内连接。

```sql
SELECT member.tenant_id,
       member.group_link_id,
       GROUP_CONCAT(DISTINCT member.phone ORDER BY member.phone SEPARATOR ', ') AS admin
FROM whatsapp_group_member_snapshot member
INNER JOIN account controlled_account
  ON controlled_account.tenant_id = member.tenant_id
 AND controlled_account.ws_phone = member.phone
 AND controlled_account.deleted_at IS NULL
WHERE member.is_admin = 1
  AND member.phone IS NOT NULL
  AND TRIM(member.phone) <> ''
GROUP BY member.tenant_id, member.group_link_id
```

该设计保持现有查询结构：

- 不新增表、列、索引或数据迁移。
- 不新增相关子查询、嵌套聚合或 Java 内存过滤。
- 不改 `operable` 可用管理员聚合。
- 列表投影和管理员关键字搜索继续复用 `admins.admin`，不会出现展示与搜索口径不一致。
- `account` 已有租户内有效号码唯一约束；连接不会放大同一有效账号的数据行，现有 `DISTINCT` 继续防御快照重复号码。

## API 与前端影响

接口字段结构不变：

- `admin` 仍返回逗号分隔字符串，供旧调用兼容。
- `adminPhones` 仍返回号码数组。
- `availableAdmin`、`availableAdminCount` 不变。

前端无需修改；原“全部管理员号码”列会直接收到过滤后的号码集合。管理员关键字搜索只命中上控管理员或上控群主号码。

## 测试设计

在 `GroupLinkMapperDbTest` 中使用真实 Mapper XML 和 H2 MySQL 模式补回归场景：

1. 同一群快照包含上控管理员、上控群主、非上控管理员和普通成员。
2. 列表 `admin` 只包含上控管理员与上控群主，且号码去重、排序稳定。
3. 上控管理员即使离线也仍在 `admin` 中；其是否可用仍由 `availableAdminCount` 独立决定。
4. 用上控管理员号码搜索可命中该群，用非上控管理员号码搜索不能命中。
5. 另租户存在相同号码时不能让当前租户的外部管理员变成“上控管理员”。

同时更新 SQL 结构测试，锁定管理员聚合包含按 `tenant_id + ws_phone` 连接有效 `account`，且没有把在线状态条件混入展示聚合。

## 回滚

回滚仅需撤销 `admins` 聚合中的 `account` 内连接及对应测试；无数据库和数据回滚动作。
