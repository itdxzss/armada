# 营销任务支持纯文本发送内容 — 设计

- 日期:2026-07-04
- 项目:armada
- 模块:`armada-api` / marketing 域
- 接口:`/api/marketing-tasks`
- 需求:新增群组营销任务时,发送内容支持「营销模板」或「文本内容」二选一

## 1. 需求口径

当前「新增群组营销任务」只能选择「营销模板」作为发送内容。现在需要允许用户不选择营销模板,直接填写纯文本内容发送。

本期规则:

| 营销模板 | 文本内容 | 是否允许保存 |
|---|---|---|
| 已选择 | 未填写 | 允许 |
| 未选择 | 已填写 | 允许 |
| 未选择 | 未填写 | 不允许 |
| 已选择 | 已填写 | 不允许,前端限制,后端兜底 |

补充口径:

1. 文本内容只作为普通文字发送。
2. 用户输入 URL 时不拦截、不校验 URL、不生成链接卡片,按普通文本原样发送。
3. 不支持图片、视频、文件、链接卡片、按钮卡片等素材型内容。

## 2. armada 当前实现

armada 当前营销任务模型:

1. 表模型是 `marketing_task`、`marketing_task_target`、`marketing_task_send_attempt` 三层。
2. `marketing_task` 当前要求 `marketing_template_id BIGINT NOT NULL`、`marketing_template_name VARCHAR(128) NOT NULL`。
3. `CreateMarketingTaskDTO` 当前只有 `marketingTemplateId` / `marketingTemplateName`,没有文本字段。
4. `MarketingTaskServiceImpl.createTask` 当前强制 `requireTemplate(request.marketingTemplateId())`,任务只保存模板 ID 与名称快照。
5. `MarketingTaskVO` / `MarketingTaskDetailVO` 当前只返回模板字段。
6. `updateMarketingTemplate(taskId, request)` 当前假设每个任务都有模板,会通过任务上的 `marketingTemplateId` 修改共享营销模板。
7. 当前 armada 营销任务 checkpoint 只实现创建、列表、详情、账号群树、启停、批量删除、任务侧修改营销素材;真实发送引擎还未接协议层。

因此本需求在 armada 里主要是:扩展任务主表内容来源、创建校验、任务读写 VO、模板删除/素材修改边界,并为后续发送引擎固定 TEXT 语义。

## 3. 总体方案

在 `marketing_task` 主表增加发送内容类型与纯文本内容:

```text
send_content_type = 1 模板 / 2 纯文本
text_content      = 纯文本发送内容,仅 send_content_type=2 时使用
```

同时把 `marketing_template_id` / `marketing_template_name` 改成可空:

- 模板任务:`send_content_type=1`,模板字段非空,`text_content=NULL`;
- 文本任务:`send_content_type=2`,模板字段为空,`text_content` 非空。

不把文本内容写入 `marketing_template`,也不创建临时模板。这样不会污染营销模板列表,也不会让文本任务被营销模板删除级联停止。

## 4. 数据库设计

新增 Flyway 迁移,版本号以实现前最新迁移为准。当前工作树已有 `V035__marketing_template_file.sql`,落地前需重新确认最新号。

目标 DDL:

```sql
ALTER TABLE marketing_task
    MODIFY COLUMN marketing_template_id BIGINT NULL COMMENT '营销模板ID(→marketing_template.id);send_content_type=1时必填',
    MODIFY COLUMN marketing_template_name VARCHAR(128) NULL COMMENT '营销模板名称快照;send_content_type=1时必填',
    ADD COLUMN send_content_type TINYINT NOT NULL DEFAULT 1
        COMMENT '发送内容类型:1=营销模板 2=纯文本'
        AFTER marketing_template_name,
    ADD COLUMN text_content TEXT NULL
        COMMENT '纯文本发送内容;send_content_type=2时使用'
        AFTER send_content_type;
```

索引:

- 保留 `idx_marketing_task_template (tenant_id, marketing_template_id)`。MySQL 对 nullable 字段可索引,模板任务仍能按模板查关联任务。
- 不给 `text_content` 建索引。本期无按发送文本检索任务的需求。

历史兼容:

- 既有任务通过默认值成为 `send_content_type=1` 模板任务。
- 既有任务模板字段已有值,列表/详情/启停行为不变。

## 5. API 契约

armada 当前 JSON 字段为 camelCase,本需求继续沿用 camelCase。

### 5.1 创建请求

`POST /api/marketing-tasks`

`CreateMarketingTaskDTO` 新增:

```java
String sendContentType;
String textContent;
```

请求示例一:模板任务。

```json
{
  "taskName": "巴铁烟草群发-0519",
  "accountGroupId": 501,
  "marketingTemplateId": 9,
  "marketingTemplateName": "普通超链模版",
  "sendContentType": "TEMPLATE",
  "textContent": null,
  "startMode": "PENDING",
  "sendPerRound": 1,
  "sendIntervalSeconds": 30,
  "onlineCheckEnabled": true,
  "abnormalGroupSkipped": true,
  "autoRetryEnabled": false,
  "selections": []
}
```

请求示例二:文本任务。

```json
{
  "taskName": "纯文本群发",
  "accountGroupId": 501,
  "marketingTemplateId": null,
  "marketingTemplateName": null,
  "sendContentType": "TEXT",
  "textContent": "活动说明:https://example.com 按普通文字发送",
  "startMode": "PENDING",
  "sendPerRound": 1,
  "sendIntervalSeconds": 30,
  "onlineCheckEnabled": true,
  "abnormalGroupSkipped": true,
  "autoRetryEnabled": false,
  "selections": []
}
```

兼容规则:

- `sendContentType` 未传时,后端按内容推断:
  - 有 `marketingTemplateId` 且 `textContent` 为空 -> `TEMPLATE`;
  - 无模板且 `textContent` 非空 -> `TEXT`;
  - 其它情况进入二选一校验。
- `sendContentType` 允许值:`TEMPLATE` / `TEXT`;实现可兼容 `1` / `2`,但前端应传字符串。

后端校验:

1. 任务名、账号分组、账号/群选择、发送数量、发送间隔沿用现有校验。
2. 模板任务:
   - `marketingTemplateId` 必填;
   - `textContent` 必须为空;
   - 营销模板必须存在。
3. 文本任务:
   - `textContent.trim()` 必填;
   - `marketingTemplateId` 与 `marketingTemplateName` 必须为空;
   - 文本允许换行和 URL。
4. 两者都空:抛 `VALIDATION`,提示「请选择营销模板或填写文本内容」。
5. 两者都有:抛 `VALIDATION`,提示「营销模板和文本内容只能选择其中一种」。

### 5.2 响应

`MarketingTaskVO` 与 `MarketingTaskDetailVO` 新增:

```java
Integer sendContentType; // 1=模板,2=纯文本
String textContent;
```

返回规则:

- 模板任务:`sendContentType=1`,`textContent=null`,`marketingTemplateId/name` 非空。
- 文本任务:`sendContentType=2`,`textContent` 非空,`marketingTemplateId/name=null`。

## 6. 后端实现设计

### 6.1 模型与 Mapper

新增/调整:

- `MarketingTask`:新增 `sendContentType`、`textContent`。
- `CreateMarketingTaskDTO`:新增 `sendContentType`、`textContent`。
- `MarketingTaskVO`、`MarketingTaskDetailVO`:新增 `sendContentType`、`textContent`。
- `MarketingTaskMapper.xml`:
  - `MarketingTaskResultMap` 增加新字段;
  - `TaskColumns` 增加新列;
  - `insertTask` 增加新列;
  - `selectPage`、`selectTaskById` 通过 `TaskColumns` 自动带出。
- 新增域内枚举 `MarketingTaskContentType`,放 `com.armada.marketing.model.enums`:
  - `TEMPLATE(1)`;
  - `TEXT(2)`;
  - 提供从请求字符串归一的方法。

### 6.2 创建任务流程

`MarketingTaskServiceImpl.createTask` 调整顺序:

1. `validateRequest` 只校验通用字段和二选一发送内容。
2. 归一发送内容:
   - 模板模式:查 `MarketingTemplate`,保存模板 ID 和名称快照;
   - 文本模式:不查 `marketing_template`,保存 trim 后文本。
3. 继续按现有逻辑生成 `marketing_task_target`。
4. `buildTask` 写入:
   - `sendContentType`;
   - `marketingTemplateId/name`;
   - `textContent`。

文本模式下不调用 `templateMapper.selectById`。这需要测试锁住,避免后续又把模板校验加回来。

### 6.3 任务侧修改营销素材

`PUT /api/marketing-tasks/{id}/marketing-template` 只适用于模板任务。

调整:

- 模板任务:保持现有行为,委托 `MarketingTemplateService.update`。
- 文本任务:拒绝,抛 `VALIDATION`,提示「文本任务没有营销模板」。

前端也应对文本任务禁用或隐藏「修改营销素材」按钮,但后端必须兜底。

### 6.4 营销模板删除联动

`MarketingTemplateServiceImpl` 现在会按模板 ID 停止关联营销任务。文本任务的 `marketing_template_id=NULL`,自然不会命中。

为了语义清晰,`stopRunnableTasksByTemplateIds` 可增加条件:

```sql
AND send_content_type = 1
```

这样即使未来文本任务误填了模板 ID,也不会被模板删除联动影响。

## 7. 前端交互要求

armada 仓库当前只有 `armada-api`,前端实际对接仓库不在本仓内。本节固定接口和交互口径,供前端实现。

新增群组营销任务抽屉的发送内容区域:

1. 营销模板
   - 下拉选择;
   - 取消必填星号;
   - 默认不自动选中第一个模板;
   - 支持清空。
2. 文本内容
   - textarea;
   - 放在营销模板下方、发送状态上方;
   - placeholder:「请输入文本消息内容，仅支持文字内容」;
   - 支持清空。

互斥规则:

- 选择营销模板后:
  - 清空文本内容;
  - 文本框 disabled;
  - 提示「已选择营销模板，文本内容不可填写」。
- 填写文本内容后:
  - 清空营销模板;
  - 模板下拉 disabled;
  - 提示「已填写文本内容，营销模板不可选择」。
- 清空营销模板后,文本内容恢复可填。
- 清空文本内容后,营销模板恢复可选。

保存校验:

- 两者都空时,不提交接口,自动定位到发送内容区域,两个字段均显示红色校验。
- 两者都有时,不提交接口,提示「营销模板和文本内容只能选择其中一种」。

提交 payload:

- 模板任务:`sendContentType="TEMPLATE"`,`textContent=null`;
- 文本任务:`sendContentType="TEXT"`,`marketingTemplateId=null`,`marketingTemplateName=null`,`textContent=用户输入文本`。

列表行:

- 模板任务副标题显示模板名。
- 文本任务副标题显示「文本内容」或文本前缀预览。
- 文本任务禁用或隐藏「修改营销素材」。

## 8. 发送引擎语义

当前 armada 还没有真实发送引擎。本需求先把任务内容来源固化到数据模型和接口,避免后续发送引擎再改表。

未来发送引擎按如下规则实现:

- `sendContentType=1`:按现有营销模板读取规则发送,可走文本、图片、链接卡片、按钮卡片等模板能力。
- `sendContentType=2`:只读取 `marketing_task.text_content`,调用协议层普通文本消息能力。
- `textContent` 包含 URL 时仍按文本发送,不调用链接卡片能力。
- 文本任务不读取 `marketing_template`,不读取图片文件,不拼推广链接。
- 若脏数据导致 `sendContentType=2` 但 `textContent` 为空,发送引擎跳过并记录失败/跳过原因,不发送空消息。

## 9. 测试策略

### 9.1 后端 DbTest / 单测

覆盖现有营销任务测试:

- `MarketingTaskDataModelMigrationDbTest`:
  - `marketing_task.marketing_template_id/name` 允许 NULL;
  - 新增 `send_content_type`、`text_content` 列;
  - `send_content_type` 类型为 `tinyint`。
- `MarketingTaskCreateReadDbTest`:
  - 模板任务创建成功,行为保持现状;
  - 文本任务创建成功,主表模板字段为 NULL,`send_content_type=2`,`text_content` 已保存;
  - 文本只含 URL 也创建成功;
  - 列表和详情返回 `sendContentType/textContent`;
  - 文本任务创建时不查询模板。
- `MarketingTaskControllerDbTest`:
  - `POST /api/marketing-tasks` 支持文本任务 payload。
- `MarketingTaskMaterialUpdateDbTest`:
  - 模板任务可继续修改共享模板;
  - 文本任务调用修改素材接口返回 `VALIDATION`。
- `MarketingTemplateDeletionDbTest`:
  - 删除模板只停止模板任务,不影响文本任务。

新增或调整服务单测:

- 两者都空 -> `VALIDATION`。
- 两者都有 -> `VALIDATION`。
- 非法 `sendContentType` -> `VALIDATION`。

### 9.2 前端测试要求

前端仓落地时需覆盖:

- 抽屉默认不选营销模板。
- 模板选择和文本输入互斥禁用。
- 清空入口恢复另一字段。
- 两者都空时字段级红色校验和定位发送内容区。
- 模板模式与文本模式提交 payload。
- 文本任务禁用或隐藏「修改营销素材」。

## 10. 文档同步

实现后同步更新:

- `docs/business/marketing-task-data-model.md`:把「任务只保存模板 ID 与名称快照」改为「模板/文本二选一」。
- `.harness/wiki/数据模型.md`:重新生成或手工同步 `marketing_task` 新列。
- `.harness/wiki/接口协议.md`:重新生成或同步 `CreateMarketingTaskDTO`、`MarketingTaskVO`、`MarketingTaskDetailVO` 新字段。
- `.harness/changes/marketing-task/summary.md`:补充本次 API/DB 变更与验证证据。

## 11. 回滚

代码回滚:

- 创建任务恢复只支持模板;
- 文本任务入口从前端隐藏;
- 任务侧修改素材恢复只面向模板任务。

数据回滚:

- 新列是向后兼容列,代码回滚后旧代码会忽略 `send_content_type/text_content`。
- 但如果库里已经存在文本任务,旧代码无法发送或修改素材。回滚前应先停止文本任务,必要时清理文本任务数据。
- 不建议在热回滚中立刻 DROP 新列;等确认无文本任务依赖后再通过专门迁移清理。

## 12. 不在本期范围

- 文本任务创建后的编辑能力。
- 文本内容敏感词检测。
- 文本长度产品上限。
- URL 禁止或 URL 卡片化。
- 真实发送引擎落地。
- 图片、视频、文件、链接卡片、按钮卡片等非纯文本任务内容。
