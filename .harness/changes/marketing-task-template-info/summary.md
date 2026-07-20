# 营销任务列表模板信息

## 变更概述

营销任务列表返回任务当前引用营销模板的内容、正文和推广链接，供前端展示内容摘要、完整预览和推广链接。

## 影响模块

- VO：`MarketingTaskVO` 新增三个可空模板展示字段。
- Mapper：营销模板新增按 ID 集合批量读取能力。
- Service：任务分页完成后，按当前页去重模板 ID 一次批量补充模板字段。
- API 文档：补充 `MarketingTaskVO` 字段及缺失模板降级规则。

## 数据库变更

无表结构、索引或数据迁移；继续读取现有 `marketing_template`。

## API 变更

`MarketingTaskVO` 新增：

- `marketingTemplateContent: String | null`
- `marketingTemplateBodyText: String | null`
- `marketingTemplatePromotionLink: String | null`

多个任务可以引用同一个模板；响应始终读取当前模板内容。模板已软删除或跨租户不可见时，任务仍返回，三个字段为 `null`。

## Redis、协议与调度变更

无。不修改 Redis、Kafka、Outbox、任务调度或协议层。

## 关键约束

- 不依赖前端模板列表及其单次加载上限。
- 任务列表保持原分页查询，仅批量查询当前页涉及的模板，禁止逐任务查询模板。
- 模板正文不复制到任务表，不引入任务素材快照。
- 沿用 MyBatis 租户行隔离和模板软删除过滤。

## 验证

- TDD RED：新增列表单测后，因 `MarketingTaskVO` 字段和 `selectByIds` 尚不存在而编译失败。
- TDD GREEN：`MarketingTaskServiceImplListTest,MarketingTaskServiceImplLifecycleTest` 通过。
- 真库 DbTest 已补充共享模板、软删除和跨租户场景；本机配置的数据库端口不可达，测试未进入业务断言，本次未启动或修改数据库。
- `xmllint --noout armada-api/src/main/resources/mapper/marketing/MarketingTemplateMapper.xml`：通过。
- `python3 .harness/wiki/test_api_docs.py`：通过，20 个 controller、108 个 endpoint。
- `mvn -q -DskipTests package`：通过。
- 扩大纯离线回归共运行 929 条，出现 1 条范围外既有失败：`GroupCreationMarketingTaskMapperSqlShapeTest` 仍期望 `status = #{pendingStatus}`，当前建群营销 Mapper 使用 `status = #{update.pendingStatus}`；本次未修改该模块。

## 回滚方案

回退 VO 字段、批量 Mapper 查询、Service 映射、对应测试和接口文档；无数据回滚。

## 部署

未提交、未部署。
