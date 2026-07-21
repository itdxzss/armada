# Promotion Template Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于 `promotion_landing_template` 实现租户隔离的模板分页查询，并为 `tenant_id=1` 初始化截图中的五条模板。

**Architecture:** 新建独立 `promotion.template` 业务包，保持 `Controller -> Service -> Mapper`。接口复用 `ApiResponse`、`PageQuery` 和 `PageResult`；租户条件继续由现有 MyBatis 租户拦截器注入，接口与 Mapper 均不接收 `tenantId`。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis XML、MySQL 8、Flyway、JUnit 5、Mockito、MockMvc。

## Global Constraints

- 保留 `promotion_landing_template.tenant_id` 和现有租户内模板编码唯一键。
- 新增 `is_subaccount_visible TINYINT(1) NOT NULL DEFAULT 1`，本期只查询展示，不开发修改逻辑。
- V058 不修改；新增 V060 完成字段扩展与 `tenant_id=1` 固定数据初始化。
- 分页 SQL 必须在 MySQL 中完成，按 `id DESC` 排序，默认 `pageSize=20`。
- 不修改任何公共分页或公共响应方法，不提交、不推送。

---

### Task 1: 分页接口契约

**Files:**
- Create: `armada-api/src/test/java/com/armada/promotion/template/controller/PromotionTemplateControllerTest.java`
- Create: `armada-api/src/test/java/com/armada/promotion/template/service/impl/PromotionTemplateServiceImplTest.java`
- Create: `armada-api/src/test/java/com/armada/promotion/template/mapper/PromotionTemplateMapperSqlContractTest.java`

**Interfaces:**
- Consumes: `PageQuery`、`PageResult`、`ApiResponse`
- Produces: `GET /api/promotion-templates/query`、`PromotionTemplateService.page(PromotionTemplateQuery)`

- [ ] 先编写 Controller、Service、Mapper SQL 失败测试，覆盖默认每页 20 条、空页短路、参数标签转换、MySQL 分页和无 `tenantId` 参数。
- [ ] 运行 `mvn "-Dtest=PromotionTemplateControllerTest,PromotionTemplateServiceImplTest,PromotionTemplateMapperSqlContractTest" test`，确认因类型和接口尚不存在而失败。

### Task 2: 最小分页实现

**Files:**
- Create: `armada-api/src/main/java/com/armada/promotion/template/controller/PromotionTemplateController.java`
- Create: `armada-api/src/main/java/com/armada/promotion/template/mapper/PromotionTemplateMapper.java`
- Create: `armada-api/src/main/java/com/armada/promotion/template/model/dto/PromotionTemplateQuery.java`
- Create: `armada-api/src/main/java/com/armada/promotion/template/model/vo/PromotionTemplateRow.java`
- Create: `armada-api/src/main/java/com/armada/promotion/template/model/vo/PromotionTemplateSupportedParamVO.java`
- Create: `armada-api/src/main/java/com/armada/promotion/template/model/vo/PromotionTemplateVO.java`
- Create: `armada-api/src/main/java/com/armada/promotion/template/service/PromotionTemplateService.java`
- Create: `armada-api/src/main/java/com/armada/promotion/template/service/impl/PromotionTemplateServiceImpl.java`
- Create: `armada-api/src/main/resources/mapper/promotion/template/PromotionTemplateMapper.xml`

**Interfaces:**
- Consumes: Mapper `countPage`、`selectPage`
- Produces: 页面字段 ID、编码、名称、预览 URI、子账号可见、支持参数、备注、创建/更新时间

- [ ] 实现 Query 默认 `pageSize=20`，Controller 只绑定参数和包装响应。
- [ ] Service 先 count，零条时不查列表；非空页解析 JSON 参数代码并返回代码与中文标签。
- [ ] Mapper 共用 `deleted_at IS NULL AND status=1` 口径，按 `id DESC LIMIT #{offset}, #{pageSize}` 查询。
- [ ] 重跑 Task 1 测试，确认全部通过。

### Task 3: 表字段与固定模板数据

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V060__promotion_template_visibility_and_seed.sql`
- Modify: `armada-api/src/test/java/com/armada/promotion/PromotionSchemaSqlContractTest.java`
- Modify: `.harness/wiki/数据模型.md`
- Modify: `docs/business/promotion-template-channel-statistics-data-model.md`

**Interfaces:**
- Consumes: V058 既有 `promotion_landing_template`
- Produces: `is_subaccount_visible` 及 ID `130/40/39/38/37` 的租户 1 固定模板

- [ ] 先扩展 SQL 合同测试，断言 V060 保留 `tenant_id`、新增可见字段并插入五条固定数据。
- [ ] 运行 `PromotionSchemaSqlContractTest` 确认 V060 不存在时失败。
- [ ] 新增 V060；支持参数保存稳定代码 `themeColor`、`showAppDownload`，五条数据可见性均为 1。
- [ ] 同步数据模型和接口文档。

### Task 4: 验证与暂存

**Files:** 本计划涉及的全部新增和修改文件。

- [ ] 运行模板与 Promotion Schema 定向测试，期望 0 failures / 0 errors。
- [ ] 运行 `git diff --check`，期望退出码 0。
- [ ] 仅 `git add` 本次模板分页文件，保留现有暂存内容且不加入无关未跟踪文件。
- [ ] 使用 `git status --short` 核对，不执行 commit 或 push。
