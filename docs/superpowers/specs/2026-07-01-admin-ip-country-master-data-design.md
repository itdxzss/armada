# IP 管理国家主数据设计 — armada

> 状态:**已 brainstorm 定稿,待用户审 → 转 writing-plans**(2026-07-01)
> 范围:把 IP 管理里的国家下拉从前端写死改为后端国家主数据。IP 代理池仍保留现有 `tenant_id`,角色菜单权限暂不做。
> 关联模块:`com.armada.admin` 国家主数据,`com.armada.resource` IP 管理,原型文件 `/Users/daishuaishuai/IdeaProjects/0630IP管理、IP统计.html`。

---

## 1. 目标与非目标

**目标:**
- 新增平台级国家主数据表,记录国家/地区中文名、ISO2、国旗、手机号区号、排序和启用状态。
- IP 管理的国家搜索下拉改为读取国家主数据,样式和交互对齐原型里的「国旗 + 国家 + 区号 + 搜索」。
- 初始导入原型 `BUYER_CHANNEL_COUNTRY_LIST` 中的 248 个国家/地区。
- IP 管理收归管理员入口;当前还没有角色菜单权限体系,本次不设计菜单权限。

**非目标:**
- 不把 `ip_proxy` 迁成无租户表。现有 `ip_proxy.tenant_id` 继续保留,后续统一调整。
- 不改 IP 分配优先级。仍按「指定国家 → 混合（不限国家）→ 其它国家」。
- 不在本次引入国家价格、供应商库存、路由规则等扩展能力。
- 不追溯清洗历史 `ip_proxy.region` 和 `account_import_batch.ip_region` 文本。

---

## 2. 现状

原型文件里,IP 管理国家筛选通过 `renderOpsIpCountryFilter()` 复用渠道管理的国家下拉:
- `BUYER_CHANNEL_COUNTRY_LIST` 是前端常量,当前统计为 248 条。
- `getBuyerChannelCountryFilterOptions()` 额外在列表顶部拼出虚拟选项 `mixed` / `混合（不限国家）`。
- 选项展示包含国旗、国家中文名、手机号区号,支持输入搜索。

armada 后端现状:
- `ip_proxy.region` 保存国家/分组中文展示名,例如 `印度`、`混合（不限国家）`。
- `IpProxyService.listRegions()` 现在从当前租户的 `ip_proxy.region` 去重,只会显示已经导入过 IP 的国家。
- `IpProxyImportDTO.region` 和 `IpProxyQuery.region` 都是中文展示名文本。
- `account_import_batch.ip_region` 保存账号导入时选择的 IP 国家,上线分配时作为国家偏好。

这些列承载的是业务选择结果,不是国家字典本身,所以不能继续用 `ip_proxy.region` 去重结果充当下拉源。

---

## 3. 数据模型

新增表:`country`

聚合归属:
- `country` 属于 `admin` 域的平台主数据聚合。
- 它没有 `tenant_id`,所有管理员和后续租户侧选择都读同一份国家字典。
- 实现时必须把 `country` 加入 `MyBatisConfig.IGNORED_TABLES`,否则租户拦截器会给 SQL 注入不存在的 `tenant_id`。

为什么必须新建:
- `ip_proxy.region` 是代理池行属性,会随 IP 导入变化,不能表达「还没有 IP 但可选择」的国家。
- `account_import_batch.ip_region` 是导入批次快照,不能作为全局字典。
- 前端写死常量无法由后台调整启用状态、排序和手机号区号。

推荐 schema:

```sql
CREATE TABLE country (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    iso2         CHAR(2)     NOT NULL COMMENT 'ISO 3166-1 alpha-2 国家/地区码,大写',
    name_zh      VARCHAR(64) NOT NULL COMMENT '中文展示名',
    name_en      VARCHAR(128)         DEFAULT NULL COMMENT '英文展示名',
    phone_prefix VARCHAR(16)          DEFAULT NULL COMMENT '手机号国际区号,如 +91、+1-684',
    flag         VARCHAR(16)          DEFAULT NULL COMMENT '国旗 emoji',
    is_enabled   TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用:1=启用 0=停用',
    is_ip_supported TINYINT  NOT NULL DEFAULT 1 COMMENT 'IP 管理是否展示:1=展示 0=不展示',
    sort_order   INT         NOT NULL DEFAULT 0 COMMENT '排序值,越小越靠前',
    remark       VARCHAR(255)         DEFAULT NULL COMMENT '备注',
    created_at   BIGINT      NOT NULL COMMENT '创建时间(epoch毫秒,应用层写)',
    updated_at   BIGINT      NOT NULL COMMENT '更新时间(epoch毫秒,应用层写)',
    deleted_at   BIGINT               DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删',
    is_active    TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL COMMENT '软删唯一键辅助:活行=1 软删=NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_country_iso2_active (iso2, is_active),
    KEY idx_country_enabled_sort (is_enabled, is_ip_supported, sort_order, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '国家/地区主数据';
```

字段约束:
- `iso2` 是真实国家/地区的稳定值,接口传参优先使用它。
- `name_zh` 是现有 `ip_proxy.region` 和页面展示的兼容文本。
- `phone_prefix` 保留 `+1-684` 这类复合区号,不用拆分。
- `is_enabled` 控制主数据是否可用。
- `is_ip_supported` 控制 IP 管理下拉是否展示。本次初始 248 条全部设为 `1`,后续可在后台停用或收窄。
- `created_at` / `updated_at` / `deleted_at` 统一使用 epoch 毫秒,不使用数据库 `DATETIME`。页面展示时由前端或接口展示层按北京时间格式化。
- `混合（不限国家）` 不是国家,不入 `country` 表,由 IP 选项接口作为虚拟选项置顶返回。

初始数据:
- 导入 248 行真实国家/地区。
- 数据源使用原型 `BUYER_CHANNEL_COUNTRY_LIST` 的中文名、国旗和区号。
- 迁移脚本里必须显式写入 `iso2`,不要在运行时从 emoji 推导 ISO2。
- `sort_order` 按原型数组顺序从 10、20、30 递增,留出插入空间。

---

## 4. 后端接口

新增 `admin` 域只读接口,供 IP 管理和后续账号导入复用:

`GET /api/admin/countries/options?scope=ip`

响应 VO:

```json
{
  "rows": [
    {
      "value": "MIXED",
      "iso2": null,
      "nameZh": "混合（不限国家）",
      "phonePrefix": "",
      "flag": "🌐",
      "virtual": true
    },
    {
      "value": "IN",
      "iso2": "IN",
      "nameZh": "印度",
      "phonePrefix": "+91",
      "flag": "🇮🇳",
      "virtual": false
    }
  ]
}
```

查询规则:
- `scope=ip` 时只返回 `deleted_at IS NULL AND is_enabled=1 AND is_ip_supported=1` 的国家。
- 返回顺序:`MIXED` 虚拟项第一,真实国家按 `sort_order,id`。
- 当前没有角色菜单权限体系,接口先走现有登录态/管理员入口约束,不新增菜单权限数据。

写入/筛选兼容:
- 前端选择真实国家时提交 `iso2`;选择混合时提交 `MIXED`。
- 后端在导入 IP 或查询 IP 列表前把 `iso2` 解析成 `country.name_zh`,继续写入/查询现有 `ip_proxy.region`。
- 混合值兼容 `MIXED`、`mixed` 和 `混合（不限国家）`,统一解析成现有中文 region。
- 若请求仍传旧中文国家名,后端可短期兼容:先按 `iso2` 查,查不到再按 `name_zh` 查;都查不到时按业务校验失败处理。
- `ip_proxy.region` 本次不改名、不加 `country_id`,避免与现有历史数据产生双写分歧。

---

## 5. 前端数据流

IP 管理页加载时:
1. 调 `/api/admin/countries/options?scope=ip`。
2. 用接口返回数据渲染国家搜索下拉。
3. 下拉展示字段:国旗、中文名、区号。
4. 选中值保存接口的 `value`。

搜索:
- 前端本地按 `nameZh`、`phonePrefix`、`iso2` 做包含匹配。
- 不再把 `ip_proxy.region` 去重结果作为下拉候选源。

提交:
- IP 列表筛选传 `countryValue=IN` 或 `countryValue=MIXED`。
- IP 批量导入传 `countryValue=IN` 或 `countryValue=MIXED`。
- 后端解析后继续落到现有 `IpProxyQuery.region` / `IpProxyImportDTO.region` 对应的中文 region。

---

## 6. 迁移与兼容

Flyway:
- 新增 `V021__country_master_data.sql`。
- 建表 `country`。
- 插入 248 条国家/地区主数据。
- 修改 `MyBatisConfig.IGNORED_TABLES` 增加 `country`。

兼容策略:
- 现有 `ip_proxy` 数据不迁移。
- 现有 `account_import_batch.ip_region` 数据不迁移。
- 现有分配逻辑继续使用中文 region,不会因为新增国家表改变行为。
- 下拉候选会比当前 `listRegions()` 更完整:即使某国家没有已导入 IP,也能显示。

后续可选演进:
- 当 `ip_proxy` 统一收归平台池时,再评估 `tenant_id`、`ownership` 和 `country_id/iso2` 的结构调整。
- 当角色菜单权限实现后,再补 IP 管理菜单和国家主数据维护菜单权限。
- 如果后台需要手动维护国家启停,再补增删改接口;本次只要求动态下拉。

---

## 7. 测试线

后端 DbTest:
- Flyway 后 `country` 活跃行数为 248。
- 查询 `country` 不被租户拦截器注入 `tenant_id`。
- `scope=ip` 选项返回 249 条:1 条 `MIXED` 虚拟项 + 248 条真实国家。
- `MIXED` 永远排第一。
- 用 `IN` 查询/导入时解析为 `印度`,继续匹配或写入 `ip_proxy.region`。
- 旧中文入参 `印度` 在兼容期仍可用。

前端验证:
- IP 管理国家下拉不再依赖写死数组。
- 输入中文名、区号、ISO2 都能过滤。
- 选中混合和真实国家后,列表筛选与导入提交值正确。

---

## 8. 决策日志

| 决策 | 结论 |
|---|---|
| 国家数据量 | 初始导入 248 个真实国家/地区 |
| `tenant_id` | 国家表无 `tenant_id`;`ip_proxy.tenant_id` 保留 |
| 混合选项 | 不入表,接口虚拟返回并置顶 |
| 接口传值 | 新接口用 ISO2;后端兼容旧中文 region |
| `ip_proxy.region` | 本次继续作为中文快照,不改成 `country_id` |
| 权限 | 角色菜单权限未研发,本次跳过 |
