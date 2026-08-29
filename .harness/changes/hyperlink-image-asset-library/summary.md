# 变更记录：超链图片素材库

- 日期：2026-08-29
- 目标分支：`1.0.3-snapshot`
- 设计：`docs/superpowers/specs/2026-08-29-hyperlink-image-asset-library-implementation-design.md`
- 状态：前后端实现与本地自动化验证完成；迁移号已按目标分支分配为 V157，尚未部署

## 变更概述

- 复用 `marketing_template_file` 作为唯一图片字节事实源，增加素材名称、图片尺寸和审计元数据。
- 新增素材分页、标签、上传、编辑、引用保护删除和鉴权内容读取 API。
- 新增租户共享的素材标签字典与关系表；标签按 `utf8mb4_bin` 大小写敏感精确去重。
- 超链模板图片字段改为从共享素材库选择稳定 Asset ID，不再通过模板页临时直传。
- 模板绑定和素材删除按同一文件行加锁，避免并发产生悬空引用。
- 新增图片素材动态菜单以及查看、上传、编辑、删除权限。

## 影响模块

- `com.armada.marketing.asset`：素材 Controller、Service、Mapper、DTO、VO、校验与转换器。
- `marketing`：扩展既有文件实体和 Mapper，旧营销图片上传、读取接口继续保留。
- `hyperlink.template`：模板保存前锁定并校验绑定素材，响应生成素材库内容地址。
- `admin`：菜单组件白名单增加 `hyperlink/library/index`。
- `db/migration`：新增 V157 素材元数据、标签、引用索引和 RBAC。

## 数据库变更

- `marketing_template_file` 增加 `asset_name`、`width`、`height`、`created_by`、`updated_at`。
- 新建 `resource_asset_tag`、`resource_asset_tag_ref`。
- 为素材名称和现有营销模板、超链模板的图片引用增加查询索引。
- V142 已存在的 `owner_user_id` 不参与素材库归属或过滤；素材在租户内共享，`created_by` 仅用于审计。
- 前向执行入口见 `db-migrations.sql`；破坏性逆向脚本见 `rollback.sql`。
- `.harness/wiki/数据模型.md` 只能在迁移后从真实 MySQL `information_schema` 重新生成，本次未手工修改生成物。

## API 变更

- `GET /api/resource-assets`
- `GET /api/resource-assets/tags`
- `GET /api/resource-assets/{id}`
- `GET /api/resource-assets/{id}/content`
- `POST /api/resource-assets`
- `PUT /api/resource-assets/{id}`
- `DELETE /api/resource-assets/{id}`

## Redis / Kafka / 协议层

- 无变更。素材库只保存和绑定图片，不创建任务、不发送消息、不修改 WhatsApp 协议载荷。

## 关键约束

- 素材按租户共享，所有普通 Mapper SQL 由租户拦截器注入 `tenant_id`。
- 主键 `FOR UPDATE` 保留全局租户拦截器自动注入；只有跨表引用统计显式传入可信租户 ID 并跳过自动改写。
- 列表查询不读取 `MEDIUMBLOB content`；标签和引用数按当前页批量查询，禁止 N+1。
- 上传只接受可真实解码的 JPG/JPEG，单张不超过 500KB；每批最多 100 张并串行上传。
- 当前分支没有超链任务表，V157 不猜测未落地结构；任务表合入后需使用新迁移号，并补任务引用统计和写入锁。

## 验证

- 素材、模板、营销模板、菜单与 Flyway 聚焦回归：69 条通过，0 失败、0 错误。
- 其中生产 Mapper XML、租户插件和真实 Spring 事务 H2 测试：5 条通过。
- `mvn -DskipTests package`：通过，Spring Boot jar 构建成功。
- 两份生产 Mapper XML 已通过 `xmllint`；仓库改动已通过 `git diff --check`。
- 真 MySQL `*DbTest` 不作为本轮本地完成门禁；部署测试环境后仍需执行迁移和并发人工验收。

## 回滚方案

- 未部署时：回退本次 Java、XML、前端、文档和 V157 文件即可。
- 已部署时：先回退依赖素材 API 的前后端并完成数据备份，再按 `rollback.sql` 删除菜单、标签表、索引和新增元数据列。
- Flyway 已登记 V157 的环境还需按部署规范处理 schema history，禁止只删除表或直接篡改共享库历史。

## 后续

- 在超链任务模型合入后，为真实任务快照引用补聚合统计、删除保护、绑定行锁和索引回归。
- 部署测试环境后重新生成数据模型 wiki，并完成菜单、上传、选择、编辑、引用保护删除的浏览器人工验收。
