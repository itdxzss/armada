# Marketing Task Template Info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在普通营销任务列表直接展示任务所引用模板的当前内容摘要和推广链接，并支持完整预览、安全跳转及模板更新后的当前页同步。

**Architecture:** 后端保留现有任务分页 SQL，查询当前页后提取去重的 `marketingTemplateId`，通过模板 Mapper 一次批量查询并填充 `MarketingTaskVO`，避免前端依赖模板选项分页和后端 N+1。前端只消费任务列表响应，使用纯函数处理摘要和安全链接，使用只读 Element Plus 对话框展示完整内容；任务侧更新共享模板后重新加载当前任务页，使所有引用同一模板的可见任务同步更新。

**Tech Stack:** Java 17、Spring Boot、MyBatis XML、JUnit 5、Mockito、AssertJ、真库 DbTest、Vue 3、TypeScript、Element Plus、Node test runner、pnpm、Vite。

---

## 实施边界

- 关系保持为“一个模板可被多个任务引用；一个任务只引用一个模板”。
- 列表展示模板当前值，不把 `content`、`body_text`、`promotion_link` 复制进 `marketing_task`。
- 不新增 Flyway、不改表结构、不改任务调度、发送协议、账号占用和模板保存规则。
- 不从前端已加载的 500 条模板选项中匹配列表数据；`GET /api/marketing-tasks` 自身返回完整展示字段。
- 模板不存在或已软删除时保留任务行，三个新增字段返回 `null`，前端显示 `—`。
- 当前两个仓库都有与本需求无关的在途改动。执行前必须使用 `superpowers:using-git-worktrees` 分别为 `armada` 和 `wheel-saas-pure-web` 建立隔离工作树，并从准备集成的最新已提交分支创建；不得把原工作区的未提交文件复制进工作树。
- 若执行时目标分支已合入营销任务详情层级等并行改动，先把隔离分支 rebase 到该最新提交，再按本计划修改；重点复核 `MarketingTaskServiceImpl` 构造器、`MarketingTaskVO` 和前端 `MarketingTaskRow` 的最新字段，保留并行功能。

## 文件结构

### 后端仓库 `armada`

- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java` — 定义任务响应新增的三个可空模板字段。
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTemplateMapper.java` — 声明按模板 ID 集合批量读取方法。
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTemplateMapper.xml` — 实现租户拦截器可识别的未删除模板批量查询。
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java` — 当前页批量补充模板信息，并统一单任务响应映射。
- Create: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplListTest.java` — 锁定去重批量查询、共享模板映射和缺失模板降级。
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java` — 用真库验证共享/不同/软删除模板返回值和租户隔离。
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java` — 验证创建和生命周期响应不会丢失新增字段。
- Modify: `.harness/wiki/接口协议.md` — 记录列表和单任务响应新增字段。
- Create: `.harness/changes/2026-07-19-marketing-task-template-info.md` — 记录变更、验证、发布和回滚边界。

### 前端仓库 `wheel-saas-pure-web`

- Modify: `src/api/marketing-task.ts` — 扩展 `MarketingTaskRow` 类型。
- Modify: `src/views/task/group-marketing/constants.ts` — 在任务名称后注册两列。
- Create: `src/views/task/group-marketing/components/marketing-template-info.ts` — 摘要、空值和 HTTP(S) 链接安全处理纯函数。
- Create: `src/views/task/group-marketing/components/marketing-template-info.test.ts` — 纯函数行为测试。
- Create: `src/views/task/group-marketing/components/GroupMarketingTemplatePreviewDialog.vue` — 完整模板只读预览。
- Create: `src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts` — 表格列、弹窗、tooltip、安全跳转和无 `v-html` 的结构测试。
- Modify: `src/views/task/group-marketing/components/GroupMarketingTaskTable.vue` — 渲染摘要、链接并打开预览弹窗。
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts` — 更新共享模板成功后刷新当前任务页。
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts` — 验证更新请求后触发列表刷新并采用最新字段。

## Task 1: 后端任务响应按模板 ID 批量补充当前模板信息

**Files:**
- Create: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplListTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTemplateMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTemplateMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`

- [ ] **Step 1: 写失败的 Service 批量查询测试**

创建 `MarketingTaskServiceImplListTest.java`。`AccountService` mock 在尚未合入详情实时登录态改动的分支上可以保持未使用，在合入后的构造器中会被 Mockito 自动注入。

```java
package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingAccountTreeRealtimeService;
import com.armada.marketing.service.impl.MarketingTaskServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketingTaskServiceImplListTest {

    @Mock
    private MarketingTaskMapper taskMapper;
    @Mock
    private MarketingTemplateMapper templateMapper;
    @Mock
    private MarketingTemplateService templateService;
    @Mock
    private MarketingAccountTreeRealtimeService accountTreeRealtimeService;
    @Mock
    private MarketingAccountOccupancyService occupancyService;
    @Mock
    private AccountService accountService;

    @InjectMocks
    private MarketingTaskServiceImpl service;

    @Test
    void listTasks_batchesDistinctTemplateIdsAndKeepsRowsWithMissingTemplates() {
        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setPage(1);
        query.setPageSize(10);
        MarketingTask first = task(1L, 10L, "共享模板任务A");
        MarketingTask second = task(2L, 10L, "共享模板任务B");
        MarketingTask missing = task(3L, 99L, "模板已删除任务");
        MarketingTemplate shared = template(10L, "活动标题", "活动正文", "https://example.com/promo");
        when(taskMapper.countPage(query)).thenReturn(3L);
        when(taskMapper.selectPage(query)).thenReturn(List.of(first, second, missing));
        when(templateMapper.selectByIds(List.of(10L, 99L))).thenReturn(List.of(shared));

        List<MarketingTaskVO> rows = service.listTasks(query).list();

        assertThat(rows).hasSize(3);
        assertThat(rows.subList(0, 2)).allSatisfy(row -> {
            assertThat(row.marketingTemplateContent()).isEqualTo("活动标题");
            assertThat(row.marketingTemplateBodyText()).isEqualTo("活动正文");
            assertThat(row.marketingTemplatePromotionLink()).isEqualTo("https://example.com/promo");
        });
        assertThat(rows.get(2).marketingTemplateContent()).isNull();
        assertThat(rows.get(2).marketingTemplateBodyText()).isNull();
        assertThat(rows.get(2).marketingTemplatePromotionLink()).isNull();
        verify(templateMapper).selectByIds(List.of(10L, 99L));
        verify(templateMapper, never()).selectById(10L);
    }

    private static MarketingTask task(long id, long templateId, String name) {
        MarketingTask task = new MarketingTask();
        task.setId(id);
        task.setTaskName(name);
        task.setMarketingTemplateId(templateId);
        task.setMarketingTemplateName("模板" + templateId);
        task.setStatus(1);
        return task;
    }

    private static MarketingTemplate template(long id, String content, String bodyText, String link) {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(id);
        template.setContent(content);
        template.setBodyText(bodyText);
        template.setPromotionLink(link);
        return template;
    }
}
```

- [ ] **Step 2: 写失败的真库读取测试**

在 `MarketingTaskCreateReadDbTest.java` 增加 `java.util.Map` 和 `java.util.function.Function` import，并加入以下三个测试。第一个测试同时证明“一模板多任务”和不同模板按 ID 正确匹配；第二个测试证明模板软删除不会吞掉任务行；第三个测试证明批量模板查询仍受租户拦截器保护。

```java
@Test
void listTasks_returnsCurrentTemplateInfoForSharedAndDifferentTemplates() {
    Fixture first = seedFixture("template-info-first");
    Fixture second = seedFixture("template-info-second");
    Fixture third = seedFixture("template-info-third");
    jdbc.update("""
            UPDATE marketing_template
            SET content = ?, body_text = ?, promotion_link = ?
            WHERE id = ?
            """, "共享标题", "共享正文", "https://example.com/shared", first.templateId());
    jdbc.update("""
            UPDATE marketing_template
            SET content = ?, body_text = ?, promotion_link = NULL
            WHERE id = ?
            """, "独立标题", "独立正文", third.templateId());

    service.createTask(request("模板展示任务A", first.accountGroupId(), first.templateId(), "PENDING",
            List.of(new MarketingSelectionDTO(first.accountId(), List.of(first.groupLinkId())))));
    service.createTask(request("模板展示任务B", second.accountGroupId(), first.templateId(), "PENDING",
            List.of(new MarketingSelectionDTO(second.accountId(), List.of(second.groupLinkId())))));
    service.createTask(request("模板展示任务C", third.accountGroupId(), third.templateId(), "PENDING",
            List.of(new MarketingSelectionDTO(third.accountId(), List.of(third.groupLinkId())))));
    MarketingTaskQuery query = new MarketingTaskQuery();
    query.setKeyword("模板展示任务");
    query.setPageSize(10);

    PageResult<MarketingTaskVO> page = service.listTasks(query);
    Map<String, MarketingTaskVO> byName = page.list().stream()
            .collect(java.util.stream.Collectors.toMap(MarketingTaskVO::taskName, Function.identity()));

    assertThat(page.total()).isEqualTo(3);
    assertThat(List.of(byName.get("模板展示任务A"), byName.get("模板展示任务B")))
            .allSatisfy(row -> {
                assertThat(row.marketingTemplateContent()).isEqualTo("共享标题");
                assertThat(row.marketingTemplateBodyText()).isEqualTo("共享正文");
                assertThat(row.marketingTemplatePromotionLink()).isEqualTo("https://example.com/shared");
            });
    assertThat(byName.get("模板展示任务C").marketingTemplateContent()).isEqualTo("独立标题");
    assertThat(byName.get("模板展示任务C").marketingTemplateBodyText()).isEqualTo("独立正文");
    assertThat(byName.get("模板展示任务C").marketingTemplatePromotionLink()).isNull();
}

@Test
void listTasks_keepsTaskWhenReferencedTemplateWasSoftDeleted() {
    Fixture fixture = seedFixture("template-info-deleted");
    MarketingTaskVO created = service.createTask(request(
            "模板已删除仍展示任务",
            fixture.accountGroupId(),
            fixture.templateId(),
            "PENDING",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
    jdbc.update("UPDATE marketing_template SET deleted_at = ? WHERE id = ?",
            System.currentTimeMillis(), fixture.templateId());
    MarketingTaskQuery query = new MarketingTaskQuery();
    query.setId(created.id());
    query.setPageSize(10);

    PageResult<MarketingTaskVO> page = service.listTasks(query);

    assertThat(page.list()).singleElement().satisfies(row -> {
        assertThat(row.id()).isEqualTo(created.id());
        assertThat(row.marketingTemplateContent()).isNull();
        assertThat(row.marketingTemplateBodyText()).isNull();
        assertThat(row.marketingTemplatePromotionLink()).isNull();
    });
}

@Test
void listTasks_doesNotExposeTemplateFieldsFromAnotherTenant() {
    Fixture fixture = seedFixture("template-info-tenant");
    MarketingTaskVO created = service.createTask(request(
            "跨租户模板不可见任务",
            fixture.accountGroupId(),
            fixture.templateId(),
            "PENDING",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
    long now = System.currentTimeMillis();
    long foreignTemplateId = insertAndReturnId("""
            INSERT INTO marketing_template
                (tenant_id, template_name, link_mode, text_type, content, body_text,
                 promotion_link, created_at, updated_at)
            VALUES (2, ?, 1, 'PROMO', ?, ?, ?, ?, ?)
            """, ps -> {
        ps.setString(1, "其他租户模板");
        ps.setString(2, "其他租户标题");
        ps.setString(3, "其他租户正文");
        ps.setString(4, "https://other-tenant.example.com");
        ps.setLong(5, now);
        ps.setLong(6, now);
    });
    jdbc.update("UPDATE marketing_task SET marketing_template_id = ? WHERE id = ?",
            foreignTemplateId, created.id());
    MarketingTaskQuery query = new MarketingTaskQuery();
    query.setId(created.id());
    query.setPageSize(10);

    PageResult<MarketingTaskVO> page = service.listTasks(query);

    assertThat(page.list()).singleElement().satisfies(row -> {
        assertThat(row.marketingTemplateContent()).isNull();
        assertThat(row.marketingTemplateBodyText()).isNull();
        assertThat(row.marketingTemplatePromotionLink()).isNull();
    });
}
```

- [ ] **Step 3: 为生命周期响应补失败断言**

在 `MarketingTaskMutationDbTest.startTask_pendingTask_setsSendingAndStartedAt` 的现有断言后加入：

```java
assertThat(created.marketingTemplateContent()).isEqualTo("内容");
assertThat(created.marketingTemplateBodyText()).isEqualTo("正文");
assertThat(created.marketingTemplatePromotionLink()).isNull();
assertThat(started.marketingTemplateContent()).isEqualTo("内容");
assertThat(started.marketingTemplateBodyText()).isEqualTo("正文");
assertThat(started.marketingTemplatePromotionLink()).isNull();
```

- [ ] **Step 4: 运行测试确认失败原因是契约和批量查询尚未实现**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=MarketingTaskServiceImplListTest test
```

Expected: FAIL to compile because `MarketingTaskVO` has no three template accessors and `MarketingTemplateMapper` has no `selectByIds`.

- [ ] **Step 5: 扩展后端 VO 和模板 Mapper**

将 `MarketingTaskVO` 末尾改为：

```java
        Long finishedAt,
        Long createdAt,
        Long updatedAt,
        String marketingTemplateContent,
        String marketingTemplateBodyText,
        String marketingTemplatePromotionLink) {
}
```

在 `MarketingTemplateMapper.selectById` 后加入：

```java
/** 按 ID 集合批量查询未删除模板；调用方保证 ids 非空且已去重。 */
List<MarketingTemplate> selectByIds(@Param("ids") List<Long> ids);
```

在 `MarketingTemplateMapper.xml` 的 `selectById` 后加入：

```xml
<select id="selectByIds" resultType="com.armada.marketing.model.entity.MarketingTemplate">
    SELECT <include refid="Columns"/>
    FROM marketing_template
    WHERE deleted_at IS NULL AND id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
</select>
```

不要手写 `tenant_id`；现有 MyBatis 租户行拦截器必须继续为这条查询注入租户条件。

- [ ] **Step 6: 在 Service 中实现当前页一次批量补充**

将 `listTasks` 的行查询部分替换为：

```java
List<MarketingTaskVO> rows;
if (total == 0) {
    rows = List.of();
} else {
    List<MarketingTask> tasks = taskMapper.selectPage(query);
    Map<Long, MarketingTemplate> templatesById = loadTemplatesById(tasks);
    rows = tasks.stream()
            .map(task -> toVO(task, templatesById.get(task.getMarketingTemplateId())))
            .toList();
}
```

在 `toVO` 前加入批量装载方法：

```java
private Map<Long, MarketingTemplate> loadTemplatesById(List<MarketingTask> tasks) {
    List<Long> templateIds = tasks.stream()
            .map(MarketingTask::getMarketingTemplateId)
            .filter(id -> id != null)
            .distinct()
            .toList();
    if (templateIds.isEmpty()) {
        return Map.of();
    }
    Map<Long, MarketingTemplate> templatesById = new LinkedHashMap<>();
    for (MarketingTemplate template : templateMapper.selectByIds(templateIds)) {
        templatesById.put(template.getId(), template);
    }
    return templatesById;
}
```

把现有静态 `toVO(MarketingTask task)` 替换为以下两个方法；保留原构造器中已有字段的原顺序，只在末尾追加模板字段：

```java
private MarketingTaskVO toVO(MarketingTask task) {
    MarketingTemplate template = task.getMarketingTemplateId() == null
            ? null
            : templateMapper.selectById(task.getMarketingTemplateId());
    return toVO(task, template);
}

private static MarketingTaskVO toVO(MarketingTask task, MarketingTemplate template) {
    return new MarketingTaskVO(
            task.getId(), task.getTaskName(), task.getAccountGroupId(), task.getAccountGroupName(),
            task.getMarketingTemplateId(), task.getMarketingTemplateName(), task.getStatus(),
            task.getSelectedAccountCount(), task.getTargetGroupCount(), task.getTargetPairCount(),
            task.getSentMessageCount(), task.getFailedMessageCount(), task.getSendPerRound(),
            accountGroupSendIntervalSeconds(task.getAccountGroupSendIntervalMs()),
            task.getSendIntervalSeconds(), task.getOnlineCheckEnabled(), task.getAbnormalGroupSkipped(),
            task.getAutoRetryEnabled(), task.getRetryLimit(), task.getRemark(),
            task.getAccountGroupSendAt(), task.getTaskStartAt(), task.getTaskEndAt(), task.getStartedAt(),
            task.getLastSentAt(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt(),
            template == null ? null : template.getContent(),
            template == null ? null : template.getBodyText(),
            template == null ? null : template.getPromotionLink());
}
```

在 `createTask` 中复用事务内已经锁定并读取的 `template`，避免再次查询：

```java
return toVO(taskMapper.selectTaskById(task.getId()), template);
```

其余启动、暂停、继续、关闭路径继续调用实例方法 `toVO(task)`，从而返回当前模板字段。将构造器 Javadoc 中“模板 Mapper 不读取正文”改为：

```java
<p>任务 Mapper 负责本聚合读写；模板 Mapper 负责校验模板存在、读取名称快照，
并为任务列表及单任务响应补充当前模板展示字段；模板正文不复制到任务表。</p>
```

- [ ] **Step 7: 运行单元测试和真库测试确认通过**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=MarketingTaskServiceImplListTest,MarketingTaskServiceImplLifecycleTest test
./dbtest.sh 'MarketingTaskCreateReadDbTest#listTasks_returnsCurrentTemplateInfoForSharedAndDifferentTemplates+listTasks_keepsTaskWhenReferencedTemplateWasSoftDeleted+listTasks_doesNotExposeTemplateFieldsFromAnotherTenant'
./dbtest.sh 'MarketingTaskMutationDbTest#startTask_pendingTask_setsSendingAndStartedAt'
```

Expected: all selected tests PASS；真库测试输出没有跨租户数据、SQL 语法或空集合 `IN ()` 错误。

- [ ] **Step 8: 提交后端功能**

```bash
git add armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java \
  armada-api/src/main/java/com/armada/marketing/mapper/MarketingTemplateMapper.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTemplateMapper.xml \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplListTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java
git commit -m "feat(marketing): expose template info on task responses"
```

## Task 2: 前端模板摘要和推广链接安全处理

**Files:**
- Modify: `src/api/marketing-task.ts`
- Create: `src/views/task/group-marketing/components/marketing-template-info.ts`
- Create: `src/views/task/group-marketing/components/marketing-template-info.test.ts`

- [ ] **Step 1: 写纯函数失败测试**

创建 `marketing-template-info.test.ts`：

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  marketingPromotionHref,
  marketingPromotionLink,
  marketingTemplateSummary,
  marketingTemplateValue
} from "./marketing-template-info";

describe("marketing template task-list display", () => {
  it("trims and joins content with body text", () => {
    assert.equal(marketingTemplateSummary("  标题  ", "\n正文\n"), "标题 正文");
    assert.equal(marketingTemplateSummary("标题", "  "), "标题");
    assert.equal(marketingTemplateSummary(null, undefined), "—");
  });

  it("uses an em dash for empty full-text fields and links", () => {
    assert.equal(marketingTemplateValue("   "), "—");
    assert.equal(marketingTemplateValue("\n正文\n"), "\n正文\n");
    assert.equal(marketingPromotionLink("  "), "");
  });

  it("only returns an href for valid http or https links", () => {
    assert.equal(
      marketingPromotionHref(" https://example.com/a?b=1 "),
      "https://example.com/a?b=1"
    );
    assert.equal(marketingPromotionHref("HTTP://example.com/a"), "HTTP://example.com/a");
    assert.equal(marketingPromotionHref("javascript:alert(1)"), undefined);
    assert.equal(marketingPromotionHref("https://"), undefined);
    assert.equal(marketingPromotionHref("not-a-link"), undefined);
  });
});
```

- [ ] **Step 2: 运行测试确认模块尚不存在**

Run from `wheel-saas-pure-web`:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/marketing-template-info.test.ts
```

Expected: FAIL with module-not-found for `marketing-template-info`.

- [ ] **Step 3: 扩展 API 类型并实现最小纯函数**

在 `MarketingTaskRow.marketingTemplateName` 后加入：

```ts
marketingTemplateContent?: string | null;
marketingTemplateBodyText?: string | null;
marketingTemplatePromotionLink?: string | null;
```

创建 `marketing-template-info.ts`：

```ts
export function marketingTemplateValue(value?: string | null): string {
  return value?.trim() ? value : "—";
}

export function marketingTemplateSummary(
  content?: string | null,
  bodyText?: string | null
): string {
  const parts = [content, bodyText]
    .map(value => value?.trim() || "")
    .filter(Boolean);
  return parts.length > 0 ? parts.join(" ") : "—";
}

export function marketingPromotionLink(value?: string | null): string {
  return value?.trim() || "";
}

export function marketingPromotionHref(
  value?: string | null
): string | undefined {
  const link = marketingPromotionLink(value);
  if (!link) return undefined;
  try {
    const url = new URL(link);
    return (url.protocol === "http:" || url.protocol === "https:") &&
      Boolean(url.hostname)
      ? link
      : undefined;
  } catch {
    return undefined;
  }
}
```

- [ ] **Step 4: 运行纯函数测试确认通过**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/marketing-template-info.test.ts
```

Expected: 3 tests PASS.

- [ ] **Step 5: 提交类型和纯函数**

```bash
git add src/api/marketing-task.ts \
  src/views/task/group-marketing/components/marketing-template-info.ts \
  src/views/task/group-marketing/components/marketing-template-info.test.ts
git commit -m "feat(marketing): add task template display helpers"
```

## Task 3: 前端任务列表新增预览和推广链接两列

**Files:**
- Modify: `src/views/task/group-marketing/constants.ts`
- Create: `src/views/task/group-marketing/components/GroupMarketingTemplatePreviewDialog.vue`
- Create: `src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts`
- Modify: `src/views/task/group-marketing/components/GroupMarketingTaskTable.vue`

- [ ] **Step 1: 写 UI 结构失败测试**

创建 `GroupMarketingTaskTemplateInfoUi.test.ts`：

```ts
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), "utf8");
}

describe("group marketing task template info ui", () => {
  it("registers preview and promotion columns immediately after task name", () => {
    const constants = source("../constants.ts");
    const taskName = constants.indexOf('label: "任务名称"');
    const preview = constants.indexOf('label: "营销模板预览"');
    const promotion = constants.indexOf('label: "推广链接"');
    const online = constants.indexOf('label: "营销账号在线数量"');
    assert.ok(taskName < preview && preview < promotion && promotion < online);
  });

  it("renders an ellipsis preview button and opens the read-only dialog", () => {
    const table = source("./GroupMarketingTaskTable.vue");
    assert.match(table, /marketingTemplateSummary/);
    assert.match(table, /openTemplatePreview/);
    assert.match(table, /class="template-summary"/);
    assert.match(table, /text-overflow: ellipsis/);
    assert.match(table, /GroupMarketingTemplatePreviewDialog/);
    assert.match(table, /dynamicColumns\[9\]/);
  });

  it("uses tooltip and only binds validated external hrefs", () => {
    const table = source("./GroupMarketingTaskTable.vue");
    assert.match(table, /<el-tooltip/);
    assert.match(table, /marketingPromotionHref/);
    assert.match(table, /target="_blank"/);
    assert.match(table, /rel="noopener noreferrer"/);
    assert.match(table, />\s*—\s*<\/span>/);
  });

  it("shows content as plain text with preserved newlines", () => {
    const dialog = source("./GroupMarketingTemplatePreviewDialog.vue");
    assert.match(dialog, /label="内容"/);
    assert.match(dialog, /label="文本"/);
    assert.match(dialog, /marketingTemplateContent/);
    assert.match(dialog, /marketingTemplateBodyText/);
    assert.match(dialog, /white-space: pre-wrap/);
    assert.doesNotMatch(dialog, /v-html/);
    assert.doesNotMatch(dialog, /保存/);
  });
});
```

- [ ] **Step 2: 运行结构测试确认失败**

```bash
node --test src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts
```

Expected: FAIL because the two columns and preview dialog do not exist.

- [ ] **Step 3: 在列配置中注册两列**

把 `taskColumns` 开头改为：

```ts
export const taskColumns: TableColumnList = [
  { label: "ID", prop: "id", width: 90 },
  { label: "任务名称", prop: "taskName", minWidth: 220 },
  {
    label: "营销模板预览",
    prop: "marketingTemplateContent",
    minWidth: 240
  },
  {
    label: "推广链接",
    prop: "marketingTemplatePromotionLink",
    minWidth: 220
  },
  { label: "营销账号在线数量", prop: "selectedAccountCount", width: 150 },
  { label: "营销账号封禁/禁言", prop: "failedMessageCount", width: 150 },
  { label: "营销群组数量", prop: "targetGroupCount", width: 130 },
  { label: "发送条数", prop: "sentMessageCount", width: 110 },
  { label: "发送状态", prop: "status", width: 120 },
  { label: "最后发送时间", prop: "lastSentAt", width: 180 }
];
```

- [ ] **Step 4: 创建只读完整预览弹窗**

创建 `GroupMarketingTemplatePreviewDialog.vue`：

```vue
<script setup lang="ts">
import type { MarketingTaskRow } from "@/api/marketing-task";
import { marketingTemplateValue } from "./marketing-template-info";

defineOptions({
  name: "GroupMarketingTemplatePreviewDialog"
});

defineProps<{
  task: MarketingTaskRow | null;
}>();

const visible = defineModel<boolean>({ required: true });
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`营销模板：${task?.marketingTemplateName || '—'}`"
    width="640px"
    destroy-on-close
  >
    <el-descriptions :column="1" border>
      <el-descriptions-item label="内容">
        <div class="template-full-text">
          {{ marketingTemplateValue(task?.marketingTemplateContent) }}
        </div>
      </el-descriptions-item>
      <el-descriptions-item label="文本">
        <div class="template-full-text">
          {{ marketingTemplateValue(task?.marketingTemplateBodyText) }}
        </div>
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<style scoped>
.template-full-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
```

- [ ] **Step 5: 在任务表中接入摘要、弹窗和安全链接**

把 Vue import 改为：

```ts
import { computed, ref } from "vue";
```

增加组件和纯函数 import：

```ts
import GroupMarketingTemplatePreviewDialog from "./GroupMarketingTemplatePreviewDialog.vue";
import {
  marketingPromotionHref,
  marketingPromotionLink,
  marketingTemplateSummary
} from "./marketing-template-info";
```

在分页 computed 后增加状态和打开函数：

```ts
const templatePreviewVisible = ref(false);
const previewTask = ref<MarketingTaskRow | null>(null);

function openTemplatePreview(row: MarketingTaskRow): void {
  previewTask.value = row;
  templatePreviewVisible.value = true;
}
```

在“任务名称”列后插入：

```vue
<el-table-column
  v-if="!dynamicColumns[2].hide"
  label="营销模板预览"
  min-width="240"
>
  <template #default="{ row }">
    <el-button
      v-if="
        marketingTemplateSummary(
          row.marketingTemplateContent,
          row.marketingTemplateBodyText
        ) !== '—'
      "
      link
      type="primary"
      class="template-preview-button"
      @click="openTemplatePreview(asMarketingTaskRow(row))"
    >
      <span class="template-summary">
        {{
          marketingTemplateSummary(
            row.marketingTemplateContent,
            row.marketingTemplateBodyText
          )
        }}
      </span>
    </el-button>
    <span v-else>—</span>
  </template>
</el-table-column>
<el-table-column
  v-if="!dynamicColumns[3].hide"
  label="推广链接"
  min-width="220"
>
  <template #default="{ row }">
    <el-tooltip
      v-if="marketingPromotionLink(row.marketingTemplatePromotionLink)"
      :content="marketingPromotionLink(row.marketingTemplatePromotionLink)"
      placement="top"
    >
      <el-link
        v-if="marketingPromotionHref(row.marketingTemplatePromotionLink)"
        :href="marketingPromotionHref(row.marketingTemplatePromotionLink)"
        target="_blank"
        rel="noopener noreferrer"
        type="primary"
        class="promotion-link"
      >
        {{ marketingPromotionLink(row.marketingTemplatePromotionLink) }}
      </el-link>
      <span v-else class="promotion-link">
        {{ marketingPromotionLink(row.marketingTemplatePromotionLink) }}
      </span>
    </el-tooltip>
    <span v-else>—</span>
  </template>
</el-table-column>
```

将其余业务列的 `dynamicColumns` 索引依次从 `2..7` 改为 `4..9`。操作列没有动态索引，不改。把预览弹窗放在 `</PureTableBar>` 后、根模板结束前：

```vue
<GroupMarketingTemplatePreviewDialog
  v-model="templatePreviewVisible"
  :task="previewTask"
/>
```

在 scoped style 末尾加入：

```css
.template-preview-button {
  max-width: 100%;
  padding: 0;
}

.template-summary,
.promotion-link {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
```

- [ ] **Step 6: 运行 UI 和纯函数测试确认通过**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/components/marketing-template-info.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts
```

Expected: all selected tests PASS；原生命周期按钮结构测试仍通过。

- [ ] **Step 7: 提交任务列表 UI**

```bash
git add src/views/task/group-marketing/constants.ts \
  src/views/task/group-marketing/components/GroupMarketingTemplatePreviewDialog.vue \
  src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTaskTable.vue
git commit -m "feat(marketing): show template info in task list"
```

## Task 4: 更新共享模板后刷新当前任务页

**Files:**
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`

- [ ] **Step 1: 把素材更新测试改成两段响应并加入刷新断言**

把现有 `updates marketing material without optional body text` 测试中的 `resetArmadaMock(...)` 改为：

```ts
resetArmadaMockQueue([
  {
    id: 18,
    templateName: "活动模板",
    linkMode: 1,
    textType: "PROMO",
    content: "新标题",
    bodyText: "",
    promotionLink: "https://example.com/new",
    mentionAll: true,
    buttons: []
  },
  {
    list: [
      {
        id: 42,
        taskName: "夏季活动",
        marketingTemplateId: 18,
        marketingTemplateName: "活动模板",
        marketingTemplateContent: "新标题",
        marketingTemplateBodyText: "",
        marketingTemplatePromotionLink: "https://example.com/new",
        status: 1
      }
    ],
    total: 1,
    page: 1,
    pageSize: 10
  }
]);
```

把调用数量断言由一条调整为以下内容，同时保留原有 PUT payload 和抽屉关闭断言：

```ts
assert.equal(calls.length, 2);
assert.equal(calls[0].method, "put");
assert.equal(calls[0].url, "/api/marketing-tasks/42/marketing-template");
assert.equal(calls[1].method, "get");
assert.equal(calls[1].url, "/api/marketing-tasks");
assert.equal(pageState.rows.value[0].marketingTemplateContent, "新标题");
assert.equal(
  pageState.rows.value[0].marketingTemplatePromotionLink,
  "https://example.com/new"
);
```

- [ ] **Step 2: 运行组合式函数测试确认只发出 PUT，断言失败**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: FAIL because the second GET call is absent and `rows` has not been refreshed.

- [ ] **Step 3: 更新成功后刷新当前页**

在 `submitMaterialUpdate` 成功分支中，把关闭抽屉后的代码改为：

```ts
ElMessage.success("营销素材已更新");
closeMaterialDrawer();
await refreshTasks();
```

必须刷新列表而不是只替换 `activeTask` 对应行，因为同一个模板可能被当前页多条任务引用。

- [ ] **Step 4: 运行组合式函数测试确认通过**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: all tests PASS，素材更新用例依次记录 PUT 和 GET。

- [ ] **Step 5: 提交共享模板刷新行为**

```bash
git add src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
git commit -m "fix(marketing): refresh tasks after template update"
```

## Task 5: 更新后端接口文档和变更记录

**Files:**
- Modify: `.harness/wiki/接口协议.md`
- Create: `.harness/changes/2026-07-19-marketing-task-template-info.md`

- [ ] **Step 1: 在营销任务接口章节记录新增字段**

在 `.harness/wiki/接口协议.md` 的 `GET /api/marketing-tasks` 响应说明中加入：

```markdown
任务列表以及创建、启动、暂停、继续、关闭返回的 `MarketingTaskVO` 追加以下可空字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `marketingTemplateContent` | `string \| null` | 当前营销模板的 `content` |
| `marketingTemplateBodyText` | `string \| null` | 当前营销模板的 `body_text` |
| `marketingTemplatePromotionLink` | `string \| null` | 当前营销模板的 `promotion_link` |

任务列表先分页查询任务，再按当前页去重后的模板 ID 批量补充字段。模板不存在或已软删除时任务仍返回，以上三个字段为 `null`。
```

- [ ] **Step 2: 创建变更记录**

创建 `.harness/changes/2026-07-19-marketing-task-template-info.md`：

```markdown
# 营销任务列表新增营销模板信息

## 变更范围

- 普通营销任务列表响应增加模板内容、正文和推广链接。
- 当前页任务按去重模板 ID 一次批量查询，不依赖前端模板选项列表。
- 前端新增营销模板预览和推广链接两列；模板内容只读展示，不使用 `v-html`。
- 仅合法 HTTP(S) 推广链接可点击，空值展示 `—`。
- 任务侧更新共享模板成功后刷新当前任务页。

## 数据与兼容性

- 无数据库结构、Flyway、Redis、协议层和任务状态机变更。
- 新增 JSON 字段对旧前端向后兼容。
- 模板缺失或软删除时保留任务行，新增字段返回 `null`。

## 发布与回滚

- 发布顺序：先后端，后前端。
- 回滚只回退后端 VO/Mapper/Service 和前端展示代码；无数据回滚动作。

## 验证

- 后端单元测试与营销任务真库 DbTest。
- 前端 Node 测试、TypeScript 类型检查和生产构建。
```

- [ ] **Step 3: 检查文档差异并提交**

```bash
git diff --check
git diff -- .harness/wiki/接口协议.md .harness/changes/2026-07-19-marketing-task-template-info.md
git add .harness/wiki/接口协议.md .harness/changes/2026-07-19-marketing-task-template-info.md
git commit -m "docs(marketing): document task template fields"
```

Expected: diff 只包含本需求字段、查询语义、发布与回滚说明。

## Task 6: 全量验证和交付检查

**Files:**
- Verify only; do not edit unrelated files while running this task.

- [ ] **Step 1: 后端完整相关验证**

Run from the isolated `armada/armada-api` worktree:

```bash
mvn -q -Dtest=MarketingTaskServiceImplListTest,MarketingTaskServiceImplLifecycleTest test
./dbtest.sh 'MarketingTaskCreateReadDbTest,MarketingTaskMutationDbTest'
mvn -q -DskipTests package
```

Expected: unit tests PASS，两个真库测试类 PASS，package exits 0。

- [ ] **Step 2: 前端完整相关验证**

Run from the isolated `wheel-saas-pure-web` worktree:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/components/marketing-template-info.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm exec eslint --max-warnings 0 \
  src/api/marketing-task.ts \
  src/views/task/group-marketing/constants.ts \
  src/views/task/group-marketing/components/marketing-template-info.ts \
  src/views/task/group-marketing/components/marketing-template-info.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTemplatePreviewDialog.vue \
  src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTaskTable.vue \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm typecheck
pnpm build
```

Expected: tests PASS，ESLint 0 warnings，typecheck exits 0，production build exits 0。

- [ ] **Step 3: 静态安全与改动边界检查**

Run in both isolated worktrees:

```bash
git diff --check
git status --short
```

Run in the frontend worktree:

```bash
rg -n "v-html|javascript:" src/views/task/group-marketing/components/GroupMarketingTemplatePreviewDialog.vue src/views/task/group-marketing/components/GroupMarketingTaskTable.vue
```

Expected: `git diff --check` has no output；状态中没有无关文件；安全扫描没有生产模板中的 `v-html` 或硬编码 `javascript:`（测试文件不在扫描范围）。

- [ ] **Step 4: 手工验收关键交互**

启动本地前后端后，在测试租户完成以下检查：

1. 打开营销任务列表，确认两列位于“任务名称”之后，列设置可隐藏/显示它们。
2. 找到内容较长的模板，确认摘要单行省略，点击后完整显示“内容”和“文本”并保留换行。
3. 找到空推广链接模板，确认显示 `—`。
4. 找到长 HTTP(S) 链接，确认单行省略、悬停显示完整值、点击在新窗口打开。
5. 构造或使用一条非法协议历史值，确认只显示文本、不可点击。
6. 让两条任务引用同一模板，从任务侧修改素材，确认保存后当前页两行都显示新内容。
7. 启动、暂停、继续或关闭一条任务，确认行状态更新后模板预览和推广链接仍存在。

- [ ] **Step 5: 核对提交历史，不自动部署**

```bash
git log --oneline -5
```

Expected backend commits include:

```text
docs(marketing): document task template fields
feat(marketing): expose template info on task responses
```

Expected frontend commits include:

```text
fix(marketing): refresh tasks after template update
feat(marketing): show template info in task list
feat(marketing): add task template display helpers
```

停止在已验证提交处；部署、SSH、远程环境和真实业务数据操作不属于本计划授权范围。
