# 变更记录：推广模板与渠道管理

- 日期 / 分支：2026-07-20 / `1.0.1-snapshot-wyfBranch`
- 需求来源：买号上量系统模板管理、渠道管理和渠道统计 V1.1 文档及渠道页面截图
- 状态：渠道新增和分页接口已实现；渠道统计、编辑、删除、启停和探测接口延期

## 已确认业务口径

- 页面和接口不传租户参数，但保留 Armada MyBatis 自动租户隔离。
- 新增表单的“归属用户”保存为 `promotion_channel.owner_user_id`。
- 页面“创建人”筛选实际按 `owner_user_id` 精确筛选。
- “上级用户”暂不建用户层级字段；前端把选择结果展开为 `ownerUserIds`，后端使用 `owner_user_id IN (...)`。
- Access Token 禁止明文落库和回显，使用 AES-256-GCM 密文、密钥版本及 SHA-256 指纹。
- V058 只创建模板、域名、渠道、追踪配置四张表；渠道统计表和 `promotion_operation_log` 不创建。

## 实现

- `POST /api/promotion-channels`：新增渠道、校验模板/国家、规范化并绑定域名、生成渠道码、加密追踪配置。
- `GET /api/promotion-channels`：按目标国家、混合国家、模板、创建人、上级用户展开集合分页查询。
- 国家展示信息通过 `CountryService` 批量读取，未跨业务域直接依赖 CountryMapper。
- 分页 `count` 和 `select` 复用同一 MyBatis 筛选 SQL，分页在数据库完成。

## 数据模型修正

- 删除最新页面不存在的 `theme_color`、`status_reason`。
- 不增加当前无更新接口需求的 `revision`，不增加当前无软删复活唯一需求的 `is_active`。
- 新增 `is_marketing_allowed` 和三个 CAPI 事件映射字段。
- `preselected_country_id` 改为必填。
- 补充模板编码、域名、渠道码、每渠道追踪配置的数据库唯一约束。
- 渠道主表只保留唯一键和默认分页索引，避免无查询证据的过度索引。

## 验证记录

- TDD RED：首次定向测试因渠道接口类不存在而按预期编译失败。
- TDD GREEN：渠道 Controller、Service、域名规范化、Token 加密、Mapper SQL、V058 SQL 合同和 CountryService 定向测试退出码为 0。
- 真库 DbTest：本地不存在 `armada-api/.env`，未连接真实 MySQL，不声称真库迁移通过。

## 文件约定

- 唯一最终执行 SQL：`armada-api/src/main/resources/db/migration/V058__promotion_template_channel_statistics.sql`。
- `.harness/changes/.../db-migrations.sql` 仅为历史评审副本，不是 Flyway 执行入口。
- 按用户要求只暂存文件，不 commit、不 push。
