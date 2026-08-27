# Armada 同租户用户数据隔离设计

## 1. 已确认需求

- 普通用户只访问自己归属的数据。
- `TENANT_ADMIN` 访问当前租户全部数据。
- IP 继续作为平台/租户共享资源，不做用户私有化。
- 暂不提供共享、转移、代创建或团队数据范围。
- 允许按业务切片分阶段上线。

## 2. 当前事实

认证会话已经可信保存 `userId + tenantId`，但请求数据上下文和 MyBatis 租户插件只传播、注入 `tenant_id`。现有 RBAC 只回答“能否使用功能”，不回答“能操作谁的数据”。因此同租户内相同权限用户默认共享业务行。

`created_by` 只在部分表和创建链中存在，历史值可空且语义是操作者，不能直接承担所有权。少量现有 `owner_user_id` 也有前端传值路径，必须改为服务端可信身份写入后才能用于授权。

## 3. 授权模型

### 3.1 DataScope

用户请求进入业务服务前，由可信 `AuthPrincipal` 构造不可变范围：

| Mode | 规则 |
|---|---|
| `SELF` | 查询和修改必须满足 `owner_user_id = actorUserId` |
| `ALL` | `TENANT_ADMIN` 可访问当前租户全部 owner，包括历史 `NULL` |

`ALL` 不是缺少过滤条件的隐式默认值。缺少 scope、未知 mode 或普通用户 owner 不匹配都必须 fail-closed。跨租户边界仍由 `tenant_id` 拦截器保证。

### 3.2 创建与审计

- 所有用户创建入口由后端写 `owner_user_id = AuthPrincipal.userId`，忽略或移除前端 owner 入参。
- 管理员新建数据同样归管理员本人；管理员依靠 `ALL` 查看他人数据。
- `ALL` 不等于可以隐式共享：在共享/转移功能上线前，一个新业务聚合引用的账号和分组必须属于同一 owner。
- 模板与图片文件都是独立权限根；模板只能引用同 owner 图片。管理员复制他人模板会形成新的跨 owner 文件引用，因此共享/转移上线前拒绝该操作。
- `created_by` / `updated_by` 继续独立记录实际操作者。

### 3.3 聚合继承

只有需要独立授权和生命周期的聚合根保存 owner。明细表通过父 ID 继承：

```text
account(owner_user_id)
  -> state / credential / baseline / attempt-log

marketing_template(owner_user_id)
  -> referenced marketing_template_file(same owner)

pull_task_group_avatar_file(owner_user_id)
  -> tenant local avatar binary(file_key)

marketing_task(owner_user_id)
  -> target / wave / member / attempt / result / export

group_creation_marketing_task(owner_user_id)
  -> item / protocol result / synchronous export

join_task(owner_user_id)
  -> join_task_result / dispatch / protocol result

pull_task(owner_user_id)
  -> draft / standard setting / execution / account / action / protocol result

promotion_channel(owner_user_id)
  -> tracking config / management probe
```

按 ID 的详情、更新和批量操作必须在同一 SQL 或同一事务内校验根 owner，不能只依赖列表隐藏。

## 4. 特殊资源

- `ip_proxy` 不增加 owner。普通用户不能枚举代理敏感信息；分配、解绑和检测操作以目标账号的 owner 为授权入口。
- WhatsApp 群 JID、invite、成员和协议状态保留租户级 canonical 去重；用户文件夹、标签、备注、导入批次和可见关系单独归 owner。
- 标准拉群头像文件以独立元数据表保存 owner，磁盘仍按租户目录存放。普通用户只能使用本人上传的头像；管理员可查看全租户，但创建任务也只能绑定本人头像。历史无元数据文件仅管理员和已授权任务执行链可读。
- 推广渠道已有 `owner_user_id`，但旧接口允许前端传入和编辑。新建改为服务端可信操作者，编辑保持原 owner；列表、详情、编辑、删除和管理探测按 DataScope 隔离。公开落地页/配对与 Outbox CAPI 正式投递保留显式内部读取，不要求登录态 DataScope。
- 推广渠道统计表通过 `channel_id` 继承渠道 owner，不重复保存 owner。全部直接 JDBC 查询和人工广告数据写入必须先读取当前范围内渠道；SELF 的账号解绑统计同时过滤 `account.owner_user_id`。
- 手机号、JID、协议句柄等物理唯一键默认仍按租户唯一；用户看不到其他 owner 的冲突行时，接口返回通用业务冲突，不泄露对方信息。

## 5. 历史数据

新增 owner 列先允许 `NULL`。历史数据不根据 `created_by` 自动猜归属：

- `SELF` 不匹配 `NULL`，普通用户不可见。
- `ALL` 可见，管理员可继续运营。
- 后续共享/转移阶段提供显式归属分配。

迁移期间新写入必须非空；待历史归属策略完成后再评估数据库 `NOT NULL`。

## 6. 分阶段切片

1. DataScope 基础设施与安全契约测试，不改变业务可见性。
2. 账号、账号分组、导入批次完整闭环；系统默认组改为每 owner 一份。
3. 群运营属性、模板和用户文件（群运营句柄/分组/文件夹/导入/批处理、营销模板/图片及标准拉群头像已完成）。
4. 进群、拉群、营销、建群及批处理任务完整继承链（普通营销、拉群营销、建群营销、进群、标准拉群、新建普群和群批处理已完成）。
5. 推广、统计、直接 SQL、Scheduler、Kafka、Outbox 和前端收口（推广渠道管理、渠道统计直接 SQL 及其公开/内部调用边界已完成）。

每个切片必须同时覆盖列表、详情、创建、修改、删除、批量、导出/下载、异步回调；未完整覆盖前不得打开该业务的隔离开关。

## 7. 验证矩阵

- 同租户普通用户 U1 只能读写 U1 数据，无法通过 ID、批量 ID、文件 key 或导出 taskIds 访问 U2。
- U2 与 U1 对称隔离。
- `TENANT_ADMIN` 可访问 U1、U2 和历史 `owner IS NULL` 数据。
- 不同租户即使 userId/ownerId 相同也不可互访。
- 混合批量请求包含不可见 ID 时全批拒绝，不静默操作可见子集。
- 缺少 DataScope 的用户入口返回空集或拒绝写入。
- 后台任务从聚合根恢复 owner，执行完成后清理上下文，线程复用不串号。
- 唯一键冲突、并发更新、软删复活和管理员视图均有 H2/SQL 契约测试。

## 8. 回滚

账号切片的 V140 会把分组名称唯一范围从租户级改为 owner 级；模板切片的 V141 会把活跃模板名称唯一范围改为 owner 级。V142-V145 增加任务 owner，V146 新增头像 owner 元数据，V147 改写群运营 URL/名称/request_id 唯一键，V148 改写新建普群幂等键。旧应用仍会写入普通用户不可见的 NULL owner 新数据，因此本阶段不支持旧、新应用实例滚动混跑。上线时必须进入维护窗口，停止旧版本写流量，执行迁移并一次性切换到 owner-aware 应用；迁移和新版本健康检查全部通过后再恢复流量。

新版本开始处理流量后，管理员或多个用户可能已经创建同名分组，旧应用的“按租户名称查单组”假设随即失效。此时禁止直接降级到不识别 owner 的旧版本，首选在新 schema 上前向修复或部署仍兼容 owner 的上一构建。只有在业务写入持续暂停、确认没有同租户同名活跃分组，并恢复旧租户级唯一键后，才允许切回旧应用。

回滚脚本不会删除 owner 列、清空 owner 或改写 Flyway 历史；任何结构回滚都必须单独确认并先通过冲突守卫。
