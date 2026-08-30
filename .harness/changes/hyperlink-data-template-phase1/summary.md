# 变更记录：超链数据包与超链营销模板一期

- 日期：2026-08-27
- 目标分支：`1.0.3-snapshot`
- 设计：`docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md`
- API 合同：`docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-api-contract.md`
- 状态：前后端实现、迁移链对齐和第一套测试环境部署完成

## 变更概述

- 新增“超链数据包”资源管理：创建、编辑、软删除、分页、国家筛选、号码明细、TXT 追加/覆盖导入。
- 覆盖导入采用 generation 原子切换；旧代号码保留 30 天后分批清理。
- 新增“超链营销模板”管理：列表、详情、创建、完整更新、复制、软删除和任务候选接口。
- 模板一期支持单图文、普通按钮、卡片按钮；消息类型 2（双图文）保留枚举但明确拒绝创建和编辑。
- 图片复用 `marketing_template_file` 的稳定 ID、上传和鉴权下载能力，不改动现有群营销存储模型。
- 新增超链顶级目录、两个页面、八个按钮权限；租户管理员沿用动态全权限规则，普通角色仍需显式授权。

## 影响模块

- `com.armada.hyperlink.data`：数据包 Controller / Service / Mapper / DTO / VO / 维护任务。
- `com.armada.hyperlink.template`：模板 Controller / Service / Mapper / DTO / VO / 内容校验。
- `marketing`：仅扩展已有图片上传、读取接口的超链权限，保留全部旧权限。
- `admin`：菜单组件白名单新增 `hyperlink/data/index`、`hyperlink/templates/index`。
- `db/migration`：保留已落库的用户隔离 V141～V152；超链使用 V153～V155。

## 数据库变更

- `V153__hyperlink_data_package.sql`
  - 新建 `data_package`、`data_package_phone`、`data_package_stat`、`data_package_import`。
  - 导入审计持久记录操作人、包 ID、模式、行数和结果。
  - 数据包软删除持久记录 `deleted_by` 与 `deleted_at`。
- `V154__hyperlink_template.sql`
  - 新建 `hyperlink_template`，保存统一的消息内容和稳定图片 ID。
- `V155__hyperlink_marketing_menu_rbac.sql`
  - 为全部启用租户幂等写入目录、页面和按钮节点。
- 前向执行入口见 `db-migrations.sql`；审阅用逆向脚本见 `rollback.sql`。
- `.harness/wiki/数据模型.md` 是真实 MySQL `information_schema` 生成物。本轮未伪造更新；V153～V155 在确认环境落库后必须重新导出 TSV 并运行生成器。
- 测试环境已执行的用户隔离 V141～V152 原样纳入主线，只用于保持 Flyway 历史一致；当前应用代码仍按租户共享数据运行，新增数据的 `owner_user_id` 允许为 `NULL`。

## API 变更

### 数据包

- `GET /api/data-packages`
- `GET /api/data-packages/countries`
- `GET /api/data-packages/{id}`
- `POST /api/data-packages`
- `PUT /api/data-packages/{id}`
- `POST /api/data-packages/{id}/import`
- `GET /api/data-packages/{id}/phones`
- `DELETE /api/data-packages/{id}`

### 超链模板

- `GET /api/hyperlink-templates`
- `GET /api/hyperlink-templates/options`
- `GET /api/hyperlink-templates/{id}`
- `POST /api/hyperlink-templates`
- `PUT /api/hyperlink-templates/{id}`
- `POST /api/hyperlink-templates/{id}/copy`
- `DELETE /api/hyperlink-templates/{id}`

### 图片兼容

- `POST /api/marketing-template-files` 增加超链模板创建/编辑权限。
- `GET /api/marketing-template-files/{id}/content` 增加超链模板查看/创建/编辑权限。

## Redis / Kafka / 协议层

- 无变更。两个菜单只交付前置资源，不发送 WhatsApp 消息，也不创建任务或点击事实。

## 关键约束

- 所有业务 SQL 由租户拦截器注入 `tenant_id`，跨租户 ID 统一表现为不存在。
- 任务未来只能复制“数据包号码快照 + 模板内容快照”，不能在运行时依赖可覆盖的数据包当前代或可编辑模板。
- `data_package_phone.pool_status` 是当前可领取投影，不是完整投递历史。
- 模板不创建 `task_ref_count` 死列；任务上线后由真实引用关系计算删除保护。
- 普通按钮“底部小字”和卡片按钮“副标题”共用 `content` 字段，均允许最多 2000 字符；前端、后端校验保持一致。
- 一期不做预探测、点击、短链、发送策略、任务执行、市场分析或通用素材库改名。

## 验证

- 聚焦后端回归：超链、H2/MyBatis、鉴权和 Flyway 合同共 53 条通过，0 失败、0 错误。
- `HyperlinkMessageContentValidatorTest`：10 条通过，覆盖按钮消息可选内容的 2000/2001 字符边界。
- V141～V152 与已部署隔离 worktree 逐文件一致，并由固定 Flyway 校验和测试锁定。
- `python3 .harness/wiki/test_api_docs.py`：232 个端点生成与渲染测试通过。
- `mvn -DskipTests package`：Spring Boot jar 构建通过；Flyway 迁移打入制品。
- 前端 `pnpm typecheck`、`pnpm build` 通过，生产包包含超链数据包和模板页面。
- `deploy-test.sh` 语法检查与脚本测试通过；test1 部署前只读深度检查通过。
- `git diff --check`：通过。
- 全量后端测试受仓库既有真库/Testcontainers环境和群组 H2 夹具失败影响；前端全量测试受 Node 23 CSS 导入与既有群组断言影响。本次相关聚焦门禁均已单独通过。

## 回滚方案

- 未部署时：回退本次 Java、XML、文档和迁移提交即可。
- 已部署时：先隐藏菜单并回退依赖新表的前后端，再备份并确认不存在需要保留的数据，最后按 `rollback.sql` 逆序删除菜单和五张业务表。
- Flyway 已登记版本的环境不能只删表；还需按部署规范处理 schema history，禁止直接修改共享库。

## 后续

- 刷新第一套测试环境的数据模型 wiki，并由有权限的测试账号完成功能级页面验收。
- 下一阶段优先实现“即时超链任务”的任务快照与最小发送闭环，同时并行验证 Web 协议的三种消息载荷和 ACK 语义。
