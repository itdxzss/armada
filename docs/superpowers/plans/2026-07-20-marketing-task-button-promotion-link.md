# Marketing Task Button Promotion Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the marketing-task list return the first link-jump button URL as `marketingTemplatePromotionLink` for button templates.

**Architecture:** Keep the existing API field and frontend rendering. Resolve the effective promotion link while `MarketingTaskServiceImpl` assembles `MarketingTaskVO`, reusing `MarketingTemplateConverter` to parse the stored button JSON; non-button templates retain their existing `promotionLink` behavior.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis, MapStruct converter, JUnit 5, AssertJ, Mockito, Maven

---

## File map

- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplListTest.java`: unit-level regression tests for first-link selection and fallback behavior.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: inject the existing template converter and resolve the effective promotion link during VO assembly.
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`: true-DB regression proving stored button JSON reaches the list API field.

The frontend repository is not modified because it already renders `marketingTemplatePromotionLink` with truncation, tooltip, safe HTTP(S) validation, and click-through behavior.

### Task 1: Lock the service behavior with failing unit tests

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplListTest.java`

- [ ] **Step 1: Add the converter mock and button-template regression tests**

Add imports for `MarketingTemplateConverter`, `ButtonType`, `LinkMode`, and `MessageButton`, then add the converter mock:

```java
@Mock
private MarketingTemplateConverter templateConverter;
```

Add a test proving mixed and multiple buttons return only the first link-jump URL:

```java
@Test
void listTasks_usesFirstLinkJumpButtonForButtonTemplates() {
    MarketingTaskQuery query = new MarketingTaskQuery();
    query.setPage(1);
    query.setPageSize(10);
    MarketingTask task = task(1L, 10L, "按钮模板任务");
    MarketingTemplate template = template(10L, "活动标题", "活动正文", null);
    template.setLinkMode(LinkMode.BUTTON.code());
    template.setButtons("button-json");
    when(taskMapper.countPage(query)).thenReturn(1L);
    when(taskMapper.selectPage(query)).thenReturn(List.of(task));
    when(templateMapper.selectByIds(List.of(10L))).thenReturn(List.of(template));
    when(templateConverter.buttonsFromJson("button-json")).thenReturn(List.of(
            new MessageButton(ButtonType.QUICK_REPLY, "立即咨询", null),
            new MessageButton(ButtonType.LINK_JUMP, "查看活动", "https://example.com/first"),
            new MessageButton(ButtonType.LINK_JUMP, "查看详情", "https://example.com/second")));

    MarketingTaskVO row = service.listTasks(query).list().get(0);

    assertThat(row.marketingTemplatePromotionLink()).isEqualTo("https://example.com/first");
}
```

Add a test proving a button template with only non-link buttons does not fall back to stale `promotionLink`:

```java
@Test
void listTasks_returnsNoPromotionLinkWhenButtonTemplateHasNoLinkJump() {
    MarketingTaskQuery query = new MarketingTaskQuery();
    query.setPage(1);
    query.setPageSize(10);
    MarketingTask task = task(1L, 10L, "无跳转按钮任务");
    MarketingTemplate template = template(
            10L, "活动标题", "活动正文", "https://example.com/stale");
    template.setLinkMode(LinkMode.BUTTON.code());
    template.setButtons("button-json");
    when(taskMapper.countPage(query)).thenReturn(1L);
    when(taskMapper.selectPage(query)).thenReturn(List.of(task));
    when(templateMapper.selectByIds(List.of(10L))).thenReturn(List.of(template));
    when(templateConverter.buttonsFromJson("button-json")).thenReturn(List.of(
            new MessageButton(ButtonType.COPY_CONTENT, "复制优惠码", "VIP2026"),
            new MessageButton(ButtonType.QUICK_REPLY, "立即咨询", null)));

    MarketingTaskVO row = service.listTasks(query).list().get(0);

    assertThat(row.marketingTemplatePromotionLink()).isNull();
}
```

Update the existing shared-template fixture to set `LinkMode.NORMAL.code()` explicitly so it continues locking non-button behavior.

- [ ] **Step 2: Run the unit test and verify RED**

Run:

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskServiceImplListTest test
```

Expected: FAIL because button templates still return the entity `promotionLink`; the first-link test receives `null` and the no-link test receives the stale URL.

- [ ] **Step 3: Commit the failing tests**

```bash
git add armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplListTest.java
git commit -m "test: cover button promotion link in marketing tasks"
```

### Task 2: Resolve the effective promotion link in VO assembly

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`

- [ ] **Step 1: Inject the existing template converter**

Import `MarketingTemplateConverter`, `ButtonType`, and `LinkMode`. Add the field and constructor parameter beside the existing template dependencies:

```java
private final MarketingTemplateMapper templateMapper;
private final MarketingTemplateConverter templateConverter;
private final MarketingTemplateService templateService;
```

```java
public MarketingTaskServiceImpl(MarketingTaskMapper taskMapper,
                                MarketingTemplateMapper templateMapper,
                                MarketingTemplateConverter templateConverter,
                                MarketingTemplateService templateService,
                                MarketingAccountTreeRealtimeService accountTreeRealtimeService,
                                MarketingAccountOccupancyService occupancyService,
                                AccountService accountService) {
    this.taskMapper = taskMapper;
    this.templateMapper = templateMapper;
    this.templateConverter = templateConverter;
    this.templateService = templateService;
    this.accountTreeRealtimeService = accountTreeRealtimeService;
    this.occupancyService = occupancyService;
    this.accountService = accountService;
}
```

Update the constructor Javadoc with:

```java
@param templateConverter 营销模板按钮 JSON 转换器
```

- [ ] **Step 2: Add the minimal promotion-link resolver and use it**

Make the two-argument `toVO` method an instance method and replace its final template link argument with `templatePromotionLink(template)`.

Add this focused helper next to the existing VO assembly methods:

```java
private String templatePromotionLink(MarketingTemplate template) {
    if (template == null) {
        return null;
    }
    if (template.getLinkMode() == null || template.getLinkMode() != LinkMode.BUTTON.code()) {
        return template.getPromotionLink();
    }
    return templateConverter.buttonsFromJson(template.getButtons()).stream()
            .filter(button -> button.type() == ButtonType.LINK_JUMP)
            .map(button -> button.param())
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null);
}
```

The relevant `MarketingTaskVO` constructor tail becomes:

```java
template == null ? null : template.getContent(),
template == null ? null : template.getBodyText(),
templatePromotionLink(template));
```

- [ ] **Step 3: Run the unit test and verify GREEN**

Run:

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskServiceImplListTest test
```

Expected: PASS with all `MarketingTaskServiceImplListTest` tests green.

- [ ] **Step 4: Commit the implementation**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java
git commit -m "fix: expose button link in marketing task list"
```

### Task 3: Prove stored button JSON through the true database path

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [ ] **Step 1: Add a focused DbTest**

Add `LinkMode` import and this test near the existing template-info list tests:

```java
@Test
void listTasks_returnsFirstLinkJumpButtonAsPromotionLink() {
    Fixture fixture = seedFixture("template-button-link");
    jdbc.update("""
            UPDATE marketing_template
            SET link_mode = ?, buttons = ?, promotion_link = NULL
            WHERE id = ?
            """,
            LinkMode.BUTTON.code(),
            """
            [{"type":"QUICK_REPLY","text":"咨询","param":null},
             {"type":"LINK_JUMP","text":"首个链接","param":"https://example.com/first"},
             {"type":"LINK_JUMP","text":"第二链接","param":"https://example.com/second"}]
            """,
            fixture.templateId());
    MarketingTaskVO created = service.createTask(request(
            "按钮推广链接任务",
            fixture.accountGroupId(),
            fixture.templateId(),
            "PENDING",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
    MarketingTaskQuery query = new MarketingTaskQuery();
    query.setId(created.id());
    query.setPageSize(10);

    MarketingTaskVO row = service.listTasks(query).list().get(0);

    assertThat(row.marketingTemplatePromotionLink()).isEqualTo("https://example.com/first");
}
```

- [ ] **Step 2: Run the true-DB regression test**

Run:

```bash
cd armada-api
./dbtest.sh 'MarketingTaskCreateReadDbTest#listTasks_returnsFirstLinkJumpButtonAsPromotionLink'
```

Expected: PASS against the configured test database. If the test environment is unavailable, record the exact setup blocker and do not claim the DB path was verified.

- [ ] **Step 3: Commit the DbTest**

```bash
git add armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "test: verify button link through marketing task database path"
```

### Task 4: Run regression gates and review the focused diff

**Files:**
- Verify only; no planned production-file additions.

- [ ] **Step 1: Run the focused marketing task tests**

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskServiceImplListTest,MarketingTemplateConverterTest test
```

Expected: PASS with zero failures and zero errors.

- [ ] **Step 2: Compile the backend artifact without rerunning unrelated tests**

```bash
cd armada-api
mvn -q -DskipTests package
```

Expected: exit code 0.

- [ ] **Step 3: Verify no frontend contract change is required**

From `wheel-saas-pure-web` run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/components/marketing-template-info.test.ts \
  src/views/task/group-marketing/components/GroupMarketingTaskTemplateInfoUi.test.ts
```

Expected: 7 tests pass, 0 fail; existing `marketingTemplatePromotionLink` rendering remains intact.

- [ ] **Step 4: Inspect the final diff without disturbing unrelated worktree changes**

```bash
git diff HEAD~3 -- \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplListTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git diff --check
```

Expected: only the promotion-link resolver, its converter dependency, and focused tests are present; no whitespace errors.

- [ ] **Step 5: Record verification evidence in the final handoff**

Report unit-test counts, package result, DbTest result or blocker, frontend regression result, commits, and the fact that deployment was not performed unless separately authorized.
