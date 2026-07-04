# Marketing Task Text Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 armada 营销任务创建支持「营销模板」或「纯文本内容」二选一,其中用户输入 URL 时也按普通文字保存和发送,不做链接卡片或 URL 校验。

**Architecture:** 只扩展 `com.armada.marketing` 的营销任务聚合。任务主表增加发送内容类型和文本内容,模板任务继续引用 `marketing_template`,文本任务不创建临时模板、不污染模板列表。当前仓库没有前端工程,本计划完成 armada 后端契约、校验、读写、DbTest 和文档同步。

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway `V036`, real MySQL DbTest, MockMvc controller DbTest.

---

## 当前结论

- 当前工作树已经存在 `armada-api/src/main/resources/db/migration/V035__marketing_template_file.sql`,本任务新增迁移固定使用 `V036__marketing_task_text_content.sql`。
- `MarketingTaskServiceImpl.createTask` 当前固定执行 `requireTemplate(request.marketingTemplateId())`,这是文本任务创建必须拆开的核心路径。
- `PUT /api/marketing-tasks/{id}/marketing-template` 当前默认每个任务都有模板。文本任务没有模板,该接口必须拒绝文本任务。
- `MarketingTaskMapper.xml#stopRunnableTasksByTemplateIds` 当前按 `marketing_template_id` 停止任务。扩列后需要限定 `send_content_type = 1`,避免空模板字段的文本任务被误纳入模板删除逻辑。

## 文件结构

新建:

- `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTaskContentType.java`
- `armada-api/src/main/resources/db/migration/V036__marketing_task_text_content.sql`
- `docs/superpowers/plans/2026-07-04-marketing-task-text-content.md`

修改:

- `armada-api/src/main/java/com/armada/marketing/model/dto/CreateMarketingTaskDTO.java`
- `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java`
- `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java`
- `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java`
- `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- `armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java`
- `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
- `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`
- `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMaterialUpdateDbTest.java`
- `armada-api/src/test/java/com/armada/marketing/service/MarketingTemplateDeletionDbTest.java`
- `docs/business/marketing-task-data-model.md`
- `.harness/changes/marketing-task/summary.md`
- `.harness/wiki/数据模型.md`
- `.harness/wiki/接口协议.md`

## Checkpoint 1: Schema Contract

**Scope:** 先用 DbTest 锁定 `marketing_task` 的新列、模板字段可空、历史任务默认保持模板模式。

- [ ] 在 `MarketingTaskDataModelMigrationDbTest` 增加 schema 断言。

新增 helper:

```java
private String nullable(String table, String column) {
    return jdbc.queryForObject("""
            SELECT IS_NULLABLE
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            """, String.class, table, column);
}
```

新增测试:

```java
@Test
void marketingTask_supportsTemplateOrTextContent() {
    assertThat(columnType("marketing_task", "send_content_type")).isEqualTo("tinyint");
    assertThat(columnComment("marketing_task", "send_content_type"))
            .isEqualTo("发送内容类型:1=营销模板 2=纯文本");
    assertThat(columnType("marketing_task", "text_content")).isEqualTo("text");
    assertThat(columnComment("marketing_task", "text_content"))
            .isEqualTo("纯文本发送内容;send_content_type=2时使用");
    assertThat(nullable("marketing_task", "marketing_template_id")).isEqualTo("YES");
    assertThat(nullable("marketing_task", "marketing_template_name")).isEqualTo("YES");
}
```

- [ ] 运行 focused schema test,确认 RED。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskDataModelMigrationDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

预期失败点:缺少 `send_content_type` / `text_content`,或模板字段仍是 `NO`。

- [ ] 新增 Flyway 迁移 `V036__marketing_task_text_content.sql`。

```sql
ALTER TABLE marketing_task
    MODIFY COLUMN marketing_template_id BIGINT NULL COMMENT '营销模板ID;send_content_type=1时必填',
    MODIFY COLUMN marketing_template_name VARCHAR(128) NULL COMMENT '营销模板名称快照;send_content_type=1时必填',
    ADD COLUMN send_content_type TINYINT NOT NULL DEFAULT 1
        COMMENT '发送内容类型:1=营销模板 2=纯文本'
        AFTER marketing_template_name,
    ADD COLUMN text_content TEXT NULL
        COMMENT '纯文本发送内容;send_content_type=2时使用'
        AFTER send_content_type;
```

- [ ] 重新运行 schema test,确认 GREEN。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskDataModelMigrationDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] Commit checkpoint。

```bash
git add armada-api/src/main/resources/db/migration/V036__marketing_task_text_content.sql \
        armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java
git commit -m "test: cover marketing task text content schema"
```

## Checkpoint 2: Model And Mapper Plumbing

**Scope:** Java 模型、DTO、VO、MyBatis 映射先打通,让任务读写能携带发送内容类型和纯文本内容。

- [ ] 新增 `MarketingTaskContentType`。

```java
package com.armada.marketing.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.util.StringUtils;

public enum MarketingTaskContentType {
    TEMPLATE(1),
    TEXT(2);

    private final int code;

    MarketingTaskContentType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MarketingTaskContentType fromRequest(String value, boolean hasTemplate, boolean hasText) {
        if (!StringUtils.hasText(value)) {
            if (hasTemplate && !hasText) {
                return TEMPLATE;
            }
            if (!hasTemplate && hasText) {
                return TEXT;
            }
            throw new BusinessException(ErrorCode.VALIDATION, "请选择营销模板或填写文本内容");
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "TEMPLATE", "1" -> TEMPLATE;
            case "TEXT", "2" -> TEXT;
            default -> throw new BusinessException(ErrorCode.VALIDATION, "发送内容类型不正确");
        };
    }
}
```

- [ ] 扩展 `CreateMarketingTaskDTO`,把新字段放在 `remark` 和 `selections` 之间。

```java
String remark,
String sendContentType,
String textContent,
List<MarketingSelectionDTO> selections
```

- [ ] 扩展 `MarketingTask` entity。

```java
private Integer sendContentType;
private String textContent;
```

同时补齐 getter/setter。

- [ ] 扩展 `MarketingTaskVO` 和 `MarketingTaskDetailVO`,字段放在模板字段后面。

```java
Long marketingTemplateId,
String marketingTemplateName,
Integer sendContentType,
String textContent,
Integer status,
```

- [ ] 修改 `MarketingTaskMapper.xml`。

`MarketingTaskResultMap` 增加:

```xml
<result column="send_content_type" property="sendContentType"/>
<result column="text_content" property="textContent"/>
```

`TaskColumns` 增加:

```xml
marketing_template_name, send_content_type, text_content, status,
```

`insertTask` 增加列和值:

```xml
task_name, account_group_id, account_group_name, marketing_template_id, marketing_template_name,
send_content_type, text_content, status
```

```xml
#{taskName}, #{accountGroupId}, #{accountGroupName}, #{marketingTemplateId}, #{marketingTemplateName},
#{sendContentType}, #{textContent}, #{status}
```

`stopRunnableTasksByTemplateIds` 增加模板模式限定:

```xml
AND send_content_type = 1
AND marketing_template_id IN
```

- [ ] 更新所有现有 test helper 的 `CreateMarketingTaskDTO` 构造参数,模板任务统一传:

```java
"素材更新测试",
"TEMPLATE",
null,
List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))
```

- [ ] 运行编译和现有营销任务 focused tests,确认 plumbing 编译通过。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskCreateReadDbTest,MarketingTaskControllerDbTest,MarketingTaskMaterialUpdateDbTest,MarketingTemplateDeletionDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] Commit checkpoint。

```bash
git add armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTaskContentType.java \
        armada-api/src/main/java/com/armada/marketing/model/dto/CreateMarketingTaskDTO.java \
        armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java \
        armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java \
        armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java \
        armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
        armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java \
        armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMaterialUpdateDbTest.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingTemplateDeletionDbTest.java
git commit -m "feat: plumb marketing task send content fields"
```

## Checkpoint 3: Create Task Validation And Persistence

**Scope:** 实现二选一后端兜底。模板任务保持旧行为,文本任务不查模板,URL 原样作为普通文本保存。

- [ ] 在 `MarketingTaskCreateReadDbTest` 增加文本任务创建测试。

```java
@Test
void createTask_withTextContent_persistsPlainTextWithoutTemplate() {
    Fixture fixture = seedFixture("text-content");
    CreateMarketingTaskDTO request = request(
            "纯文本任务",
            fixture.accountGroupId(),
            null,
            "PENDING",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))),
            "TEXT",
            "活动说明:https://example.com/path?a=1 按普通文字发送");

    MarketingTaskVO created = service.createTask(request);

    assertThat(created.marketingTemplateId()).isNull();
    assertThat(created.marketingTemplateName()).isNull();
    assertThat(created.sendContentType()).isEqualTo(2);
    assertThat(created.textContent()).isEqualTo("活动说明:https://example.com/path?a=1 按普通文字发送");
    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM marketing_task_target WHERE marketing_task_id = ?",
            Integer.class,
            created.id())).isEqualTo(1);
}
```

把 helper 改成显式接收内容模式:

```java
private CreateMarketingTaskDTO request(String taskName,
                                       long accountGroupId,
                                       Long templateId,
                                       String startMode,
                                       List<MarketingSelectionDTO> selections,
                                       String sendContentType,
                                       String textContent) {
    return new CreateMarketingTaskDTO(
            taskName,
            accountGroupId,
            "营销账号组",
            templateId,
            templateId == null ? null : "营销模板",
            startMode,
            1,
            30,
            true,
            true,
            false,
            "备注",
            sendContentType,
            textContent,
            selections);
}
```

- [ ] 增加二选一失败测试。

```java
@Test
void createTask_withoutTemplateAndText_throwsValidation() {
    Fixture fixture = seedFixture("empty-content");
    CreateMarketingTaskDTO request = request("空内容任务", fixture.accountGroupId(), null, "PENDING",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))),
            null,
            null);

    assertThatThrownBy(() -> service.createTask(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请选择营销模板或填写文本内容");
}

@Test
void createTask_withTemplateAndText_throwsValidation() {
    Fixture fixture = seedFixture("both-content");
    CreateMarketingTaskDTO request = request("双内容任务", fixture.accountGroupId(), fixture.templateId(), "PENDING",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))),
            "TEMPLATE",
            "不允许同时填写");

    assertThatThrownBy(() -> service.createTask(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("营销模板和文本内容只能选择其中一种");
}
```

- [ ] 运行 focused service test,确认 RED。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskCreateReadDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] 修改 `MarketingTaskServiceImpl#createTask`。

```java
validateRequest(request);
NormalizedSendContent content = normalizeSendContent(request);
long now = System.currentTimeMillis();
List<MarketingTaskTarget> targets = buildTargets(request, now);
MarketingTask task = buildTask(request, content, targets, now);
```

- [ ] 新增归一化方法和内部 record。

```java
private NormalizedSendContent normalizeSendContent(CreateMarketingTaskDTO request) {
    boolean hasTemplate = request.marketingTemplateId() != null;
    String trimmedText = StringUtils.hasText(request.textContent()) ? request.textContent().trim() : null;
    boolean hasText = StringUtils.hasText(trimmedText);
    if (hasTemplate && hasText) {
        throw new BusinessException(ErrorCode.VALIDATION, "营销模板和文本内容只能选择其中一种");
    }
    if (!hasTemplate && !hasText) {
        throw new BusinessException(ErrorCode.VALIDATION, "请选择营销模板或填写文本内容");
    }

    MarketingTaskContentType contentType =
            MarketingTaskContentType.fromRequest(request.sendContentType(), hasTemplate, hasText);
    if (contentType == MarketingTaskContentType.TEMPLATE) {
        if (!hasTemplate || hasText) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板和文本内容只能选择其中一种");
        }
        MarketingTemplate template = requireTemplate(request.marketingTemplateId());
        return new NormalizedSendContent(contentType, template.getId(), template.getTemplateName(), null);
    }
    if (hasTemplate || !hasText) {
        throw new BusinessException(ErrorCode.VALIDATION, "营销模板和文本内容只能选择其中一种");
    }
    return new NormalizedSendContent(contentType, null, null, trimmedText);
}

private record NormalizedSendContent(MarketingTaskContentType contentType,
                                     Long templateId,
                                     String templateName,
                                     String textContent) {
}
```

- [ ] 修改 `validateRequest`,删除旧的模板必填校验,保留通用字段和 selections 校验。

删除:

```java
if (request.marketingTemplateId() == null) {
    throw new BusinessException(ErrorCode.VALIDATION, "请选择营销模板");
}
```

- [ ] 修改 `buildTask` 签名和内容字段写入。

```java
private MarketingTask buildTask(CreateMarketingTaskDTO request,
                                NormalizedSendContent content,
                                List<MarketingTaskTarget> targets,
                                long now)
```

在现有 `MarketingTask task = new MarketingTask();` 后,用以下 4 行替换旧的模板字段赋值:

```java
task.setMarketingTemplateId(content.templateId());
task.setMarketingTemplateName(content.templateName());
task.setSendContentType(content.contentType().code());
task.setTextContent(content.textContent());
```

- [ ] 修改 `toVO` / `toDetailVO`,返回新字段。

```java
task.getMarketingTemplateId(), task.getMarketingTemplateName(),
task.getSendContentType(), task.getTextContent(), task.getStatus(),
```

- [ ] 运行 focused service test,确认 GREEN。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskCreateReadDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] Commit checkpoint。

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "feat: support marketing task text content creation"
```

## Checkpoint 4: Controller Contract

**Scope:** MockMvc 锁住创建接口的 JSON 入参/出参。前端可以不传 `sendContentType`,后端按内容推断;也可以传 `TEXT`。

- [ ] 在 `MarketingTaskControllerDbTest` 增加文本创建用例。

```java
@Test
void postCreate_withTextContent_returnsCreatedTextTask() throws Exception {
    Fixture fixture = seedFixture("controller-text");
    String body = """
            {
              "taskName":"纯文本接口任务",
              "accountGroupId":%d,
              "accountGroupName":"营销账号组",
              "marketingTemplateId":null,
              "marketingTemplateName":null,
              "sendContentType":"TEXT",
              "textContent":"https://example.com/promo 按普通文字发送",
              "startMode":"PENDING",
              "sendPerRound":1,
              "sendIntervalSeconds":30,
              "onlineCheckEnabled":true,
              "abnormalGroupSkipped":true,
              "autoRetryEnabled":false,
              "selections":[{"accountId":%d,"groupLinkIds":[%d]}]
            }
            """.formatted(fixture.accountGroupId(), fixture.accountId(), fixture.groupLinkId());

    mvc.perform(post("/api/marketing-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.marketingTemplateId").doesNotExist())
            .andExpect(jsonPath("$.data.marketingTemplateName").doesNotExist())
            .andExpect(jsonPath("$.data.sendContentType").value(2))
            .andExpect(jsonPath("$.data.textContent").value("https://example.com/promo 按普通文字发送"));
}
```

如果当前项目的 JSON null 序列化策略不是 `doesNotExist`,用下面断言替代:

```java
.andExpect(jsonPath("$.data.marketingTemplateId").isEmpty())
.andExpect(jsonPath("$.data.marketingTemplateName").isEmpty())
```

- [ ] 在 controller test 增加两者都空的错误反馈。

```java
@Test
void postCreate_withoutTemplateAndText_returnsValidationMessage() throws Exception {
    Fixture fixture = seedFixture("controller-empty");
    String body = """
            {
              "taskName":"空内容接口任务",
              "accountGroupId":%d,
              "startMode":"PENDING",
              "sendPerRound":1,
              "sendIntervalSeconds":30,
              "onlineCheckEnabled":true,
              "abnormalGroupSkipped":true,
              "autoRetryEnabled":false,
              "selections":[{"accountId":%d,"groupLinkIds":[%d]}]
            }
            """.formatted(fixture.accountGroupId(), fixture.accountId(), fixture.groupLinkId());

    mvc.perform(post("/api/marketing-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION.code()))
            .andExpect(jsonPath("$.message").value("请选择营销模板或填写文本内容"));
}
```

- [ ] 更新 controller test 中模板任务 helper,补充:

```java
"TEMPLATE",
null,
```

- [ ] 运行 controller focused test。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskControllerDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] Commit checkpoint。

```bash
git add armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java
git commit -m "test: cover marketing task text content api"
```

## Checkpoint 5: Template Update And Deletion Boundaries

**Scope:** 文本任务没有模板,所以任务侧改营销素材要拒绝文本任务;删除模板只影响模板任务。

- [ ] 在 `MarketingTaskMaterialUpdateDbTest` 增加文本任务拒绝修改模板。

```java
@Test
void updateMarketingTemplate_textTask_throwsValidation() {
    Fixture fixture = seedFixture("text-material-update");
    MarketingTaskVO task = service.createTask(new CreateMarketingTaskDTO(
            "文本任务",
            fixture.accountGroupId(),
            "营销账号组",
            null,
            null,
            "PENDING",
            1,
            30,
            true,
            true,
            false,
            "文本任务",
            "TEXT",
            "https://example.com 按普通文字发送",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
    MarketingTemplateDTO request = new MarketingTemplateDTO(
            "文本任务不可改模板",
            1,
            "PROMO",
            null,
            "内容",
            "正文",
            null,
            null,
            "备注");

    assertThatThrownBy(() -> service.updateMarketingTemplate(task.id(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("文本任务没有营销模板");
}
```

- [ ] 修改 `MarketingTaskServiceImpl#updateMarketingTemplate`。

```java
if (!Integer.valueOf(MarketingTaskContentType.TEMPLATE.code()).equals(task.getSendContentType())
        || task.getMarketingTemplateId() == null) {
    throw new BusinessException(ErrorCode.VALIDATION, "文本任务没有营销模板");
}
```

- [ ] 在 `MarketingTemplateDeletionDbTest` 增加文本任务不受模板删除影响的覆盖。

核心断言:

```java
assertThat(jdbc.queryForObject(
        "SELECT status FROM marketing_task WHERE id = ?",
        Integer.class,
        textTaskId)).isEqualTo(2);
```

其中 `textTaskId` 插入时使用:

```sql
marketing_template_id = NULL,
marketing_template_name = NULL,
send_content_type = 2,
text_content = 'https://example.com 按普通文字发送'
```

- [ ] 确认 `MarketingTaskMapper.xml#stopRunnableTasksByTemplateIds` 已包含:

```xml
AND send_content_type = 1
```

- [ ] 运行边界 focused tests。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskMaterialUpdateDbTest,MarketingTemplateDeletionDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] Commit checkpoint。

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMaterialUpdateDbTest.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingTemplateDeletionDbTest.java
git commit -m "feat: guard text marketing tasks from template operations"
```

## Checkpoint 6: Documentation And Full Focused Verification

**Scope:** 同步业务数据模型、change summary 和 wiki,给前端明确契约。UI 二选一交互由前端工程实现,armada 仓库交付后端校验和响应字段。

- [ ] 更新 `docs/business/marketing-task-data-model.md`。

把 `marketing_task` 中模板字段说明改为:

```markdown
| `marketing_template_id` | BIGINT NULL | 模板任务使用,`send_content_type=1` 时必填 |
| `marketing_template_name` | VARCHAR(128) NULL | 模板名称快照,`send_content_type=1` 时必填 |
| `send_content_type` | TINYINT NOT NULL DEFAULT 1 | 发送内容类型:1=营销模板 2=纯文本 |
| `text_content` | TEXT NULL | 纯文本发送内容,`send_content_type=2` 时使用;URL 按普通文本保存 |
```

补充契约:

```markdown
发送内容二选一:

- 模板任务:`send_content_type=1`,`marketing_template_id/name` 非空,`text_content=NULL`。
- 文本任务:`send_content_type=2`,`marketing_template_id/name=NULL`,`text_content` 非空。
- 用户输入 URL 时不做 URL 校验、不生成链接卡片,按普通文本发送。
```

- [ ] 更新 `.harness/changes/marketing-task/summary.md`。

追加:

```markdown
## 2026-07-04 发送内容二选一

- `marketing_task` 新增 `send_content_type` 和 `text_content`,模板字段改为可空。
- `POST /api/marketing-tasks` 支持模板任务和纯文本任务二选一。
- 文本内容中的 URL 按普通文字保存,不校验 URL,不生成链接卡片。
- 文本任务不支持 `PUT /api/marketing-tasks/{id}/marketing-template`。
- 删除营销模板只会停止模板任务,不会影响纯文本任务。
```

- [ ] 重新生成 wiki。

```bash
python3 .harness/wiki/gen_datamodel.py
python3 .harness/wiki/parse_endpoints.py
python3 .harness/wiki/format_api.py
```

- [ ] 运行完整 focused verification。

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskDataModelMigrationDbTest,MarketingTaskCreateReadDbTest,MarketingTaskControllerDbTest,MarketingTaskMutationDbTest,MarketingTaskAccountTreeDbTest,MarketingTaskMaterialUpdateDbTest,MarketingTemplateDeletionDbTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] 运行 diff 检查。

```bash
git diff --check
```

- [ ] Commit docs and final verification evidence。

```bash
git add docs/business/marketing-task-data-model.md \
        .harness/changes/marketing-task/summary.md \
        .harness/wiki/数据模型.md \
        .harness/wiki/接口协议.md
git commit -m "docs: update marketing task text content contract"
```

## Final Acceptance

- [ ] `marketing_task` 既能保存模板任务,也能保存纯文本任务。
- [ ] 纯文本任务 `marketing_template_id` / `marketing_template_name` 为 `NULL`,不会创建或引用营销模板。
- [ ] 文本内容允许 URL,并按普通文字原样保存。
- [ ] 后端拒绝两者都空和两者都有。
- [ ] 列表、详情和创建响应返回 `sendContentType` / `textContent`。
- [ ] 文本任务调用任务侧改模板接口返回 `VALIDATION`。
- [ ] 删除营销模板只停止模板任务,不影响文本任务。
- [ ] Focused DbTest 和 controller DbTest 通过。
- [ ] `.harness/changes/marketing-task/summary.md`、业务数据模型和 wiki 已同步。
