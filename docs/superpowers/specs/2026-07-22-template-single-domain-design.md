# 渠道模板单域名约束设计

## 业务关系

`channel_code` 是渠道的唯一公开推广码。一个模板只绑定一个域名，但一个模板可以创建多个渠道，因此关系为：

```text
模板 1 -> 1 域名 -> N 渠道（N 个不同 channel_code）
```

## 新增与编辑规则

- 相同模板、相同域名：复用现有 `promotion_domain`，允许创建或编辑多个渠道。
- 相同模板、不同域名：返回 `ErrorCode.CONFLICT`，提示该模板已经绑定的域名。
- 不同模板、相同域名：保持现有冲突逻辑，拒绝复用。
- 模板没有域名且域名未占用：创建一条模板与域名绑定记录。
- 编辑渠道只切换 `promotion_domain_id` 引用；`channel_code` 保持不变，不修改共享域名记录。

## 最小实现

- Mapper 增加模板反向查询；唯一键冲突分支另用两条 `FOR UPDATE` 当前读识别并发赢家。
- `resolveDomain` 先按域名查占用，再按模板查绑定，新增和编辑共用同一逻辑。
- Flyway V064 为 `promotion_domain` 增加唯一键 `(tenant_id, landing_template_id)`，作为并发最终约束。
- 插入域名发生唯一键冲突后，以当前读重新查询域名和模板，避免 MySQL `REPEATABLE READ` 旧快照，并返回稳定业务冲突。
- 不新增表、不修改接口 DTO/VO、不新增公共错误码、不调整渠道码生成逻辑。

## 验证

- Service 单测覆盖新增和编辑时“同模板不同域名”拒绝，以及“同模板同域名”继续复用。
- Mapper SQL 契约测试覆盖模板反向查询、软删除条件和冲突后的 `FOR UPDATE` 当前读。
- Promotion schema 契约测试覆盖 V064 唯一键。
- 运行渠道定向测试、Flyway 版本/语法测试和 Maven 打包；本地存在明确测试库配置时再运行真库 DbTest。
