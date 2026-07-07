# Group Creation Marketing Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add selectable建群营销任务导出: users select tasks in the frontend, backend exports an `.xlsx` workbook with task ID, group name, build count, join count, and totals.

**Architecture:** Persist a send-time group member-count snapshot on each `group_creation_marketing_item` before marketing-message enqueue, then generate export files from database state. The frontend only manages row selection and downloads a backend attachment; all tenant checks, row expansion, counts, and workbook generation stay in Armada backend.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, Flyway, cn.idev.excel/FastExcel 1.3.0 with its POI write hooks, JUnit 5, AssertJ, Mockito, Vue 3, TypeScript, Element Plus, pure-admin table shell.

---

## Scope Check

This is one vertical feature spanning backend data capture, backend export, and frontend selection/download. It produces working software only when all tasks are complete, but each task has its own testable checkpoint.

Execution must preserve unrelated worktree changes. At planning time `armada/` already has unrelated `.claude/worktrees/*` changes and an unrelated untracked account-restricted plan; `wheel-saas-pure-web/` already has unrelated account-list changes. Do not stage or revert those files.

## File Structure

Backend create/modify:

- Create `armada-api/src/main/resources/db/migration/V045__group_creation_marketing_export_member_count.sql`: adds send-time member-count snapshot fields.
- Modify `armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingItem.java`: adds `sendMemberCount` and `sendMemberCountCheckedAt`.
- Modify `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`: maps new fields, updates `markItemMarketingSending`, adds export row SQL.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`: updates mapper signatures.
- Create `armada-api/src/main/java/com/armada/marketing/model/dto/GroupCreationMarketingTaskExportRequest.java`: export request body.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingExportFile.java`: file response payload.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingExportRow.java`: mapper projection for export rows.
- Create `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingExportWorkbookWriter.java`: converts export rows into `.xlsx` bytes.
- Modify `armada-api/src/main/java/com/armada/marketing/service/GroupCreationMarketingTaskService.java`: adds `exportTasks`.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingTaskServiceImpl.java`: validates selected IDs, queries export rows, writes workbook.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`: captures member-count snapshot before enqueue.
- Modify `armada-api/src/main/java/com/armada/marketing/controller/GroupCreationMarketingTaskController.java`: adds file download endpoint.

Backend tests:

- Modify `armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java`.
- Modify `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`.
- Modify `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplTest.java`.
- Modify `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplUnitTest.java`.
- Modify `armada-api/src/test/java/com/armada/marketing/controller/GroupCreationMarketingTaskControllerTest.java`.

Frontend create/modify:

- Modify `wheel-saas-pure-web/src/api/group-creation-marketing.ts`: adds blob export API.
- Modify `wheel-saas-pure-web/src/api/group-creation-marketing.test.ts`: tests export request and filename parsing.
- Modify `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.ts`: selected rows, export state, download.
- Modify `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts`: tests export action.
- Modify `wheel-saas-pure-web/src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.vue`: selection column and export button.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.test.ts`: static template checks.
- Modify `wheel-saas-pure-web/src/views/task/group-creation-marketing/index.vue`: wires selection and export props/events.

---

### Task 1: Backend Schema And Mapper Snapshot Fields

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V045__group_creation_marketing_export_member_count.sql`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingItem.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java`

- [ ] **Step 1: Write the failing mapper DB test**

Add this test method to `GroupCreationMarketingTaskMapperDbTest`:

```java
@Test
void itemMemberSnapshotColumnsAreMapped() {
    long now = System.currentTimeMillis();
    Long taskId = insertTask("mapper-export-snapshot-" + now, 1, now);
    Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), null, now);

    jdbc.update("""
            UPDATE group_creation_marketing_item
            SET send_member_count = ?,
                send_member_count_checked_at = ?
            WHERE id = ?
            """, 6, now + 2, itemId);

    var item = mapper.selectItemById(itemId);

    assertThat(item.getSendMemberCount()).isEqualTo(6);
    assertThat(item.getSendMemberCountCheckedAt()).isEqualTo(now + 2);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskMapperDbTest#itemMemberSnapshotColumnsAreMapped test
```

Expected: FAIL because `send_member_count` does not exist or `getSendMemberCount()` is not defined.

- [ ] **Step 3: Add Flyway migration**

Create `armada-api/src/main/resources/db/migration/V045__group_creation_marketing_export_member_count.sql`:

```sql
ALTER TABLE group_creation_marketing_item
    ADD COLUMN send_member_count INT DEFAULT NULL COMMENT '发送营销消息前查询到的群人数快照' AFTER participant_result_json,
    ADD COLUMN send_member_count_checked_at BIGINT DEFAULT NULL COMMENT '群人数快照查询时间(epoch毫秒)' AFTER send_member_count;
```

- [ ] **Step 4: Add entity fields**

In `GroupCreationMarketingItem.java`, add fields after `participantResultJson`:

```java
private Integer sendMemberCount;
private Long sendMemberCountCheckedAt;
```

Add getters and setters:

```java
public Integer getSendMemberCount() {
    return sendMemberCount;
}

public void setSendMemberCount(Integer sendMemberCount) {
    this.sendMemberCount = sendMemberCount;
}

public Long getSendMemberCountCheckedAt() {
    return sendMemberCountCheckedAt;
}

public void setSendMemberCountCheckedAt(Long sendMemberCountCheckedAt) {
    this.sendMemberCountCheckedAt = sendMemberCountCheckedAt;
}
```

- [ ] **Step 5: Map fields in MyBatis XML**

In `GroupCreationMarketingTaskMapper.xml` `ItemResultMap`, add after `participant_result_json`:

```xml
<result column="send_member_count" property="sendMemberCount"/>
<result column="send_member_count_checked_at" property="sendMemberCountCheckedAt"/>
```

In `ItemColumns`, add the two columns after `participant_result_json`:

```xml
participant_result_json, send_member_count, send_member_count_checked_at, retry_history_json,
```

- [ ] **Step 6: Run test to verify it passes**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskMapperDbTest#itemMemberSnapshotColumnsAreMapped test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/resources/db/migration/V045__group_creation_marketing_export_member_count.sql \
  armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingItem.java \
  armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java
git commit -m "feat(marketing): store group creation member snapshot"
```

---

### Task 2: Backend Worker Captures Send-Time Member Count

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: Write failing worker success test**

Add import:

```java
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
```

Add mock field:

```java
@Mock
private GroupParticipantPort groupParticipantPort;
```

Update the worker constructor in `setUp()` to pass `groupParticipantPort` between `groupCreatePort` and `retryService`.

Add this test method:

```java
@Test
void processOnlineItemStoresSendMemberCountSnapshotBeforeMarketingSend() {
    seedSuccessfulOnlineItem();
    when(groupParticipantPort.listParticipants("acc_7", "120363created@g.us"))
            .thenReturn(List.of(
                    new GroupParticipantResult("8613000000000@s.whatsapp.net", "8613000000000", true, true, "superadmin"),
                    new GroupParticipantResult("8613900000000@s.whatsapp.net", "8613900000000", false, false, null),
                    new GroupParticipantResult("8613911111111@s.whatsapp.net", "8613911111111", false, false, null)));

    worker.processDueItems(10);

    verify(groupParticipantPort).listParticipants("acc_7", "120363created@g.us");
    verify(groupCreationMapper).markItemMarketingSending(
            eq(11L),
            eq("120363created@g.us"),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any(),
            any(),
            eq(3),
            anyLong(),
            anyLong());
}
```

- [ ] **Step 2: Write failing worker failure-tolerance test**

Add this test method:

```java
@Test
void processOnlineItemContinuesWhenMemberSnapshotQueryFails() {
    seedSuccessfulOnlineItem();
    when(groupParticipantPort.listParticipants("acc_7", "120363created@g.us"))
            .thenThrow(new IllegalStateException("participants timeout"));

    worker.processDueItems(10);

    verify(outboxService).enqueueMarketingMessageCommands(anyList());
    verify(groupCreationMapper).markItemMarketingSending(
            eq(11L),
            eq("120363created@g.us"),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any(),
            any(),
            isNull(),
            isNull(),
            anyLong());
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingWorkerTest#processOnlineItemStoresSendMemberCountSnapshotBeforeMarketingSend,GroupCreationMarketingWorkerTest#processOnlineItemContinuesWhenMemberSnapshotQueryFails test
```

Expected: FAIL because `GroupCreationMarketingWorker` has no `GroupParticipantPort` constructor argument and `markItemMarketingSending` has the old signature.

- [ ] **Step 4: Update mapper signature**

In `GroupCreationMarketingTaskMapper.java`, change `markItemMarketingSending` to:

```java
int markItemMarketingSending(@Param("id") Long id,
                             @Param("groupJid") String groupJid,
                             @Param("groupLinkId") Long groupLinkId,
                             @Param("marketingTaskId") Long marketingTaskId,
                             @Param("marketingTargetId") Long marketingTargetId,
                             @Param("marketingAttemptId") Long marketingAttemptId,
                             @Param("commandId") String commandId,
                             @Param("participantResultJson") String participantResultJson,
                             @Param("sendMemberCount") Integer sendMemberCount,
                             @Param("sendMemberCountCheckedAt") Long sendMemberCountCheckedAt,
                             @Param("updatedAt") long updatedAt);
```

- [ ] **Step 5: Update mapper XML**

In `GroupCreationMarketingTaskMapper.xml` `markItemMarketingSending`, add assignments after `participant_result_json = #{participantResultJson},`:

```xml
send_member_count = #{sendMemberCount},
send_member_count_checked_at = #{sendMemberCountCheckedAt},
```

- [ ] **Step 6: Update worker constructor and member snapshot helper**

In `GroupCreationMarketingWorker.java`, add imports:

```java
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
```

Add field:

```java
private final GroupParticipantPort groupParticipantPort;
```

Update constructor arguments so `GroupParticipantPort groupParticipantPort` is placed after `GroupCreatePort groupCreatePort`, and assign:

```java
this.groupParticipantPort = groupParticipantPort;
```

Add helper near `protocolResultJson(...)`:

```java
private GroupMemberSnapshot groupMemberSnapshot(String protocolAccountId, String groupJid) {
    try {
        List<GroupParticipantResult> participants = groupParticipantPort.listParticipants(protocolAccountId, groupJid);
        long checkedAt = System.currentTimeMillis();
        return new GroupMemberSnapshot(participants == null ? 0 : participants.size(), checkedAt);
    } catch (RuntimeException ex) {
        log.warn("建群营销发送前群人数查询失败 protocolAccountId={} groupJid={} reason={}",
                protocolAccountId, groupJid, readableMessage(ex));
        return GroupMemberSnapshot.empty();
    }
}
```

Add record near existing private records:

```java
private record GroupMemberSnapshot(Integer memberCount, Long checkedAt) {
    static GroupMemberSnapshot empty() {
        return new GroupMemberSnapshot(null, null);
    }
}
```

- [ ] **Step 7: Call the snapshot helper before enqueue**

In `doProcessOne`, after `String protocolResultJson = protocolResultJson(contactSaveSummary, groupResult);`, add:

```java
GroupMemberSnapshot memberSnapshot = groupMemberSnapshot(account.getProtocolAccountId(), groupResult.groupJid());
```

Change the `markItemMarketingSending(...)` call to include snapshot fields before the final timestamp:

```java
int marked = groupCreationMapper.markItemMarketingSending(
        item.getId(),
        groupResult.groupJid(),
        null,
        null,
        null,
        null,
        commandId,
        protocolResultJson,
        memberSnapshot.memberCount(),
        memberSnapshot.checkedAt(),
        System.currentTimeMillis());
```

- [ ] **Step 8: Update existing worker tests for new signature**

Every existing `when(groupCreationMapper.markItemMarketingSending(...))` and `verify(groupCreationMapper).markItemMarketingSending(...)` in `GroupCreationMarketingWorkerTest` must include two snapshot arguments before the final `anyLong()`.

For successful existing paths where the member query is not explicitly important, add:

```java
when(groupParticipantPort.listParticipants(anyString(), anyString())).thenReturn(List.of());
```

Then use matcher fragments like:

```java
any(), any(), anyLong()
```

for `sendMemberCount`, `sendMemberCountCheckedAt`, and `updatedAt`.

- [ ] **Step 9: Run worker tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java \
  armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java
git commit -m "feat(marketing): capture group creation send member count"
```

---

### Task 3: Backend Export Service And Workbook Writer

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingExportFile.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingExportRow.java`
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingExportWorkbookWriter.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/GroupCreationMarketingTaskService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingTaskServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplUnitTest.java`

- [ ] **Step 1: Write failing service export test**

Add imports:

```java
import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.read.listener.ReadListener;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
```

Add this test method:

```java
@Test
void exportSelectedTasksExpandsItemsAndCalculatesCounts() {
    long now = System.currentTimeMillis();
    Long firstTaskId = insertExportTask("导出任务A-" + now, 2, now);
    Long secondTaskId = insertExportTask("导出任务B-" + now, 1, now);
    insertExportItem(firstTaskId, 0, "A群-1", 2, 4, now);
    insertExportItem(firstTaskId, 1, "A群-2", 3, null, now);
    insertExportItem(secondTaskId, 0, "B群-1", 1, 2, now);

    var file = service.exportTasks(List.of(secondTaskId, firstTaskId, firstTaskId));

    assertThat(file.filename()).startsWith("建群营销统计导出_").endsWith(".xlsx");
    assertThat(file.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(file.bytes()).isNotEmpty();

    ArrayList<Map<Integer, String>> rows = readExportRows(file.bytes());
    assertThat(rows.get(0).get(0)).startsWith("建群统计导出-");
    assertThat(rows.get(1).get(0)).isEqualTo("任务ID");
    assertThat(rows.get(1).get(1)).isEqualTo("群名称");
    assertThat(rows.get(1).get(2)).isEqualTo("建群人数");
    assertThat(rows.get(1).get(3)).isEqualTo("进群人数（目标数据人数）");
    assertThat(rows.get(2).get(0)).isEqualTo(String.valueOf(secondTaskId));
    assertThat(rows.get(2).get(1)).isEqualTo("B群-1");
    assertThat(rows.get(2).get(2)).isEqualTo("2");
    assertThat(rows.get(2).get(3)).isEqualTo("1");
    assertThat(rows.get(3).get(0)).isEqualTo(String.valueOf(firstTaskId));
    assertThat(rows.get(3).get(1)).isEqualTo("A群-1");
    assertThat(rows.get(3).get(2)).isEqualTo("3");
    assertThat(rows.get(3).get(3)).isEqualTo("3");
    assertThat(rows.get(4).get(0)).isEqualTo(String.valueOf(firstTaskId));
    assertThat(rows.get(4).get(1)).isEqualTo("A群-2");
    assertThat(rows.get(4).get(2)).isEqualTo("4");
    assertThat(rows.get(4).get(3)).isNull();
    assertThat(rows.get(5).get(0)).isEqualTo("合计");
    assertThat(rows.get(5).get(2)).isEqualTo("9");
    assertThat(rows.get(5).get(3)).isEqualTo("4");
}
```

Add helper methods at the bottom of `GroupCreationMarketingTaskServiceImplTest`:

```java
private ArrayList<Map<Integer, String>> readExportRows(byte[] bytes) {
    ArrayList<Map<Integer, String>> rows = new ArrayList<>();
    FastExcel.read(new ByteArrayInputStream(bytes), new ReadListener<Map<Integer, String>>() {
        @Override
        public void invoke(Map<Integer, String> row, AnalysisContext context) {
            rows.add(row);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
        }
    }).sheet().headRowNumber(0).doRead();
    return rows;
}

private Long insertExportTask(String name, int matchedItemCount, long now) {
    return insertReturningId("""
            INSERT INTO group_creation_marketing_task
                (tenant_id, task_name, account_group_id, account_group_name,
                 marketing_template_id, marketing_template_name, status,
                 matched_item_count, unmatched_file_count, success_count,
                 failed_count, abandoned_count, send_interval_seconds,
                 created_at, updated_at)
            VALUES (?, ?, 1, 'A组', 1, '模板', 3, ?, 0, ?, 0, 0, 30, ?, ?)
            """, TEST_TENANT_ID, name, matchedItemCount, matchedItemCount, now, now);
}

private Long insertExportItem(Long taskId,
                              int fileIndex,
                              String groupSubject,
                              int participantCount,
                              Integer sendMemberCount,
                              long now) {
    return insertReturningId("""
            INSERT INTO group_creation_marketing_item
                (tenant_id, task_id, file_index, file_name, material_content,
                 participant_count, account_id, account_phone, protocol_account_id,
                 group_subject, group_jid, participant_result_json,
                 send_member_count, send_member_count_checked_at,
                 status, next_run_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, '8613900000000', ?, 1, '8613000000000',
                    'acc_1', ?, '120363export@g.us', '{}', ?, ?, 4, 0, ?, ?)
            """, TEST_TENANT_ID, taskId, fileIndex, "file-" + fileIndex + ".txt",
            participantCount, groupSubject, sendMemberCount,
            sendMemberCount == null ? null : now + 1, now, now);
}
```

- [ ] **Step 2: Write failing validation test**

Add:

```java
@Test
void exportRejectsEmptySelection() {
    assertThatThrownBy(() -> service.exportTasks(List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请选择要导出的建群营销任务");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskServiceImplTest#exportSelectedTasksExpandsItemsAndCalculatesCounts,GroupCreationMarketingTaskServiceImplTest#exportRejectsEmptySelection test
```

Expected: FAIL because `exportTasks` and export model classes do not exist.

- [ ] **Step 4: Add export file record**

Create `GroupCreationMarketingExportFile.java`:

```java
package com.armada.marketing.model.vo;

public record GroupCreationMarketingExportFile(
        String filename,
        String contentType,
        byte[] bytes
) {
}
```

- [ ] **Step 5: Add export row projection**

Create `GroupCreationMarketingExportRow.java`:

```java
package com.armada.marketing.model.vo;

public class GroupCreationMarketingExportRow {

    private Long taskId;
    private String groupSubject;
    private Integer participantCount;
    private Integer sendMemberCount;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getGroupSubject() {
        return groupSubject;
    }

    public void setGroupSubject(String groupSubject) {
        this.groupSubject = groupSubject;
    }

    public Integer getParticipantCount() {
        return participantCount;
    }

    public void setParticipantCount(Integer participantCount) {
        this.participantCount = participantCount;
    }

    public Integer getSendMemberCount() {
        return sendMemberCount;
    }

    public void setSendMemberCount(Integer sendMemberCount) {
        this.sendMemberCount = sendMemberCount;
    }
}
```

- [ ] **Step 6: Add mapper export method**

In `GroupCreationMarketingTaskMapper.java`, add import:

```java
import com.armada.marketing.model.vo.GroupCreationMarketingExportRow;
```

Add method:

```java
List<GroupCreationMarketingExportRow> selectExportRowsByTaskIds(@Param("taskIds") List<Long> taskIds);
```

In `GroupCreationMarketingTaskMapper.xml`, add:

```xml
<select id="selectExportRowsByTaskIds"
        resultType="com.armada.marketing.model.vo.GroupCreationMarketingExportRow">
    SELECT i.task_id AS taskId,
           i.group_subject AS groupSubject,
           i.participant_count AS participantCount,
           i.send_member_count AS sendMemberCount
    FROM group_creation_marketing_item i
    JOIN group_creation_marketing_task t ON t.id = i.task_id
    WHERE t.deleted_at IS NULL
      AND i.task_id IN
      <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
          #{taskId}
      </foreach>
    ORDER BY FIELD(i.task_id,
      <foreach collection="taskIds" item="taskId" separator=",">
          #{taskId}
      </foreach>
    ), i.file_index ASC, i.id ASC
</select>
```

- [ ] **Step 7: Add workbook writer**

Create `GroupCreationMarketingExportWorkbookWriter.java`:

```java
package com.armada.marketing.service.impl;

import cn.idev.excel.FastExcel;
import cn.idev.excel.write.handler.RowWriteHandler;
import cn.idev.excel.write.handler.SheetWriteHandler;
import cn.idev.excel.write.metadata.holder.WriteSheetHolder;
import cn.idev.excel.write.metadata.holder.WriteTableHolder;
import cn.idev.excel.write.metadata.holder.WriteWorkbookHolder;
import com.armada.marketing.model.vo.GroupCreationMarketingExportRow;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

@Component
public class GroupCreationMarketingExportWorkbookWriter {

    private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter TITLE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final DateTimeFormatter FILENAME_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.of("Asia/Shanghai"));

    public String contentType() {
        return CONTENT_TYPE;
    }

    public String filename(Instant exportedAt) {
        return "建群营销统计导出_" + FILENAME_TIME_FORMAT.format(exportedAt) + ".xlsx";
    }

    public byte[] write(List<GroupCreationMarketingExportRow> exportRows, Instant exportedAt) {
        List<List<Object>> rows = toWorkbookRows(exportRows, exportedAt);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        FastExcel.write(outputStream)
                .needHead(false)
                .registerWriteHandler(new ExportLayoutHandler(rows.size()))
                .sheet("建群统计")
                .doWrite(rows);
        return outputStream.toByteArray();
    }

    private List<List<Object>> toWorkbookRows(List<GroupCreationMarketingExportRow> exportRows, Instant exportedAt) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("建群统计导出-" + TITLE_TIME_FORMAT.format(exportedAt) + "（导出时间）", "", "", ""));
        rows.add(row("任务ID", "群名称", "建群人数", "进群人数（目标数据人数）"));
        int buildTotal = 0;
        int joinedTotal = 0;
        for (GroupCreationMarketingExportRow exportRow : exportRows) {
            int buildCount = safeInt(exportRow.getParticipantCount()) + 1;
            Integer joinedCount = joinedCount(exportRow.getSendMemberCount());
            buildTotal += buildCount;
            if (joinedCount != null) {
                joinedTotal += joinedCount;
            }
            rows.add(row(
                    String.valueOf(exportRow.getTaskId()),
                    blankToDash(exportRow.getGroupSubject()),
                    buildCount,
                    joinedCount));
        }
        rows.add(row("合计", "", buildTotal, joinedTotal));
        return rows;
    }

    private static List<Object> row(Object first, Object second, Object third, Object fourth) {
        ArrayList<Object> row = new ArrayList<>(4);
        row.add(first);
        row.add(second);
        row.add(third);
        row.add(fourth);
        return row;
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private static Integer joinedCount(Integer sendMemberCount) {
        return sendMemberCount == null ? null : Math.max(sendMemberCount - 1, 0);
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static class ExportLayoutHandler implements SheetWriteHandler, RowWriteHandler {
        private final int rowCount;
        private CellStyle titleStyle;
        private CellStyle headerStyle;
        private CellStyle totalStyle;

        ExportLayoutHandler(int rowCount) {
            this.rowCount = rowCount;
        }

        @Override
        public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder, WriteSheetHolder writeSheetHolder) {
            Sheet sheet = writeSheetHolder.getSheet();
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            sheet.setColumnWidth(0, 16 * 256);
            sheet.setColumnWidth(1, 32 * 256);
            sheet.setColumnWidth(2, 16 * 256);
            sheet.setColumnWidth(3, 24 * 256);
            Workbook workbook = writeWorkbookHolder.getWorkbook();
            titleStyle = titleStyle(workbook);
            headerStyle = headerStyle(workbook);
            totalStyle = totalStyle(workbook);
        }

        @Override
        public void afterRowDispose(WriteSheetHolder writeSheetHolder,
                                    WriteTableHolder writeTableHolder,
                                    Row row,
                                    Integer relativeRowIndex,
                                    Boolean isHead) {
            if (row == null) {
                return;
            }
            if (row.getRowNum() == 0) {
                row.setHeightInPoints(24);
                apply(row, titleStyle);
                return;
            }
            if (row.getRowNum() == 1) {
                apply(row, headerStyle);
                return;
            }
            if (row.getRowNum() == rowCount - 1) {
                apply(row, totalStyle);
            }
        }

        private static void apply(Row row, CellStyle style) {
            for (int index = 0; index < 4; index++) {
                Cell cell = row.getCell(index);
                if (cell == null) {
                    cell = row.createCell(index);
                }
                cell.setCellStyle(style);
            }
        }

        private static CellStyle titleStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            Font font = workbook.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 14);
            style.setFont(font);
            return style;
        }

        private static CellStyle headerStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            return style;
        }

        private static CellStyle totalStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            return style;
        }
    }
}
```

- [ ] **Step 8: Add service method interface**

In `GroupCreationMarketingTaskService.java`, import the export file record and add:

```java
GroupCreationMarketingExportFile exportTasks(List<Long> ids);
```

- [ ] **Step 9: Add service implementation**

In `GroupCreationMarketingTaskServiceImpl.java`, add field and constructor argument:

```java
private final GroupCreationMarketingExportWorkbookWriter exportWorkbookWriter;
```

Constructor assignment:

```java
this.exportWorkbookWriter = exportWorkbookWriter;
```

Add method:

```java
@Override
public GroupCreationMarketingExportFile exportTasks(List<Long> ids) {
    List<Long> normalizedIds = normalizeExportIds(ids);
    List<GroupCreationMarketingExportRow> rows = mapper.selectExportRowsByTaskIds(normalizedIds);
    if (rows.isEmpty()) {
        throw new BusinessException(ErrorCode.VALIDATION, "选中的任务没有可导出的建群明细");
    }
    Instant exportedAt = Instant.now();
    return new GroupCreationMarketingExportFile(
            exportWorkbookWriter.filename(exportedAt),
            exportWorkbookWriter.contentType(),
            exportWorkbookWriter.write(rows, exportedAt));
}
```

Add helper:

```java
private static List<Long> normalizeExportIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
        throw new BusinessException(ErrorCode.VALIDATION, "请选择要导出的建群营销任务");
    }
    List<Long> normalized = ids.stream()
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
    if (normalized.isEmpty()) {
        throw new BusinessException(ErrorCode.VALIDATION, "请选择要导出的建群营销任务");
    }
    return normalized;
}
```

Add imports:

```java
import com.armada.marketing.model.vo.GroupCreationMarketingExportFile;
import com.armada.marketing.model.vo.GroupCreationMarketingExportRow;
import java.time.Instant;
```

- [ ] **Step 10: Update direct service constructor unit test**

In `GroupCreationMarketingTaskServiceImplUnitTest.java`, add import:

```java
import com.armada.marketing.service.impl.GroupCreationMarketingExportWorkbookWriter;
```

Change `setUp()` to:

```java
@BeforeEach
void setUp() {
    service = new GroupCreationMarketingTaskServiceImpl(
            mapper,
            templateMapper,
            marketingTaskMapper,
            new GroupCreationMarketingExportWorkbookWriter());
}
```

- [ ] **Step 11: Run service tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskServiceImplTest,GroupCreationMarketingTaskServiceImplUnitTest test
```

Expected: PASS.

- [ ] **Step 12: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingExportFile.java \
  armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingExportRow.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingExportWorkbookWriter.java \
  armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java \
  armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml \
  armada-api/src/main/java/com/armada/marketing/service/GroupCreationMarketingTaskService.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingTaskServiceImpl.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplTest.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplUnitTest.java
git commit -m "feat(marketing): export group creation statistics workbook"
```

---

### Task 4: Backend Export Controller

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/dto/GroupCreationMarketingTaskExportRequest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/controller/GroupCreationMarketingTaskController.java`
- Test: `armada-api/src/test/java/com/armada/marketing/controller/GroupCreationMarketingTaskControllerTest.java`

- [ ] **Step 1: Write failing controller test**

Add imports:

```java
import com.armada.marketing.model.vo.GroupCreationMarketingExportFile;
import java.nio.charset.StandardCharsets;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

Add test method:

```java
@Test
void exportSelectedTasksReturnsXlsxAttachment() throws Exception {
    when(service.exportTasks(List.of(7L, 8L))).thenReturn(new GroupCreationMarketingExportFile(
            "建群营销统计导出_20260707_153000.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlsx".getBytes(StandardCharsets.UTF_8)));

    mockMvc.perform(post("/api/group-creation-marketing-tasks/export")
                    .contentType("application/json")
                    .content("{\"ids\":[7,8]}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                            result.getResponse().getHeader("Content-Disposition"))
                    .contains("attachment")
                    .contains("filename*="))
            .andExpect(content().bytes("xlsx".getBytes(StandardCharsets.UTF_8)));

    verify(service).exportTasks(List.of(7L, 8L));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskControllerTest#exportSelectedTasksReturnsXlsxAttachment test
```

Expected: FAIL because `/export` route does not exist.

- [ ] **Step 3: Add export request DTO**

Create `GroupCreationMarketingTaskExportRequest.java`:

```java
package com.armada.marketing.model.dto;

import java.util.List;

public record GroupCreationMarketingTaskExportRequest(List<Long> ids) {
}
```

- [ ] **Step 4: Add controller endpoint**

In `GroupCreationMarketingTaskController.java`, add imports:

```java
import com.armada.marketing.model.dto.GroupCreationMarketingTaskExportRequest;
import com.armada.marketing.model.vo.GroupCreationMarketingExportFile;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
```

Add method before detail endpoint:

```java
@PostMapping("/export")
public ResponseEntity<byte[]> export(@RequestBody GroupCreationMarketingTaskExportRequest request) {
    GroupCreationMarketingExportFile file = service.exportTasks(request == null ? null : request.ids());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(file.contentType()));
    headers.setContentDisposition(ContentDisposition.attachment()
            .filename(file.filename(), StandardCharsets.UTF_8)
            .build());
    return ResponseEntity.ok().headers(headers).body(file.bytes());
}
```

- [ ] **Step 5: Run controller tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/model/dto/GroupCreationMarketingTaskExportRequest.java \
  armada-api/src/main/java/com/armada/marketing/controller/GroupCreationMarketingTaskController.java \
  armada-api/src/test/java/com/armada/marketing/controller/GroupCreationMarketingTaskControllerTest.java
git commit -m "feat(marketing): expose group creation export endpoint"
```

---

### Task 5: Frontend Export API

**Files:**
- Modify: `wheel-saas-pure-web/src/api/group-creation-marketing.ts`
- Modify: `wheel-saas-pure-web/src/api/group-creation-marketing.test.ts`

- [ ] **Step 1: Write failing API test**

In `group-creation-marketing.test.ts`, add imports:

```ts
import { httpCalls, resetHttpMock } from "./__tests__/http-test-double";
```

Update the API import block to include:

```ts
exportGroupCreationMarketingTasks,
```

Add test:

```ts
it("exports selected group creation marketing tasks as a blob attachment", async () => {
  const blob = new Blob(["xlsx"], {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  });
  resetHttpMock(blob, {
    "content-disposition":
      "attachment; filename*=UTF-8''%E5%BB%BA%E7%BE%A4%E8%90%A5%E9%94%80%E7%BB%9F%E8%AE%A1%E5%AF%BC%E5%87%BA.xlsx"
  });

  const result = await exportGroupCreationMarketingTasks([7, 8]);

  assert.equal(result.filename, "建群营销统计导出.xlsx");
  assert.equal(result.blob, blob);
  assert.deepEqual(httpCalls(), [
    {
      method: "post",
      url: "/api/group-creation-marketing-tasks/export",
      opts: {
        data: { ids: [7, 8] },
        responseType: "blob"
      },
      configKeys: ["beforeResponseCallback"]
    }
  ]);
});
```

- [ ] **Step 2: Run API test to verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm exec tsx src/api/group-creation-marketing.test.ts
```

Expected: FAIL because `exportGroupCreationMarketingTasks` is not defined.

- [ ] **Step 3: Add blob export API**

In `src/api/group-creation-marketing.ts`, add imports:

```ts
import { http } from "@/utils/http";
import type { PureHttpResponse } from "@/utils/http/types.d";
```

Add interface after query types:

```ts
export interface GroupCreationMarketingTaskExport {
  filename: string;
  blob: Blob;
}
```

Add helpers:

```ts
function headerValue(
  headers: PureHttpResponse["headers"],
  name: string
): string | undefined {
  const getter = headers as { get?: (key: string) => unknown };
  const viaGetter = getter.get?.(name);
  if (typeof viaGetter === "string") return viaGetter;

  const record = headers as Record<string, unknown>;
  const direct = record[name] ?? record[name.toLowerCase()];
  return typeof direct === "string" ? direct : undefined;
}

function decodeFilename(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function filenameFromContentDisposition(value?: string): string | undefined {
  if (!value) return undefined;

  const encoded = /filename\*=(?:UTF-8'')?("?)([^";]+)\1/i.exec(value);
  if (encoded?.[2]) {
    return decodeFilename(encoded[2]);
  }

  const plain = /filename=("?)([^";]+)\1/i.exec(value);
  return plain?.[2];
}
```

Add function:

```ts
export function exportGroupCreationMarketingTasks(
  ids: number[]
): Promise<GroupCreationMarketingTaskExport> {
  let filename: string | undefined;
  return http
    .request<Blob>(
      "post",
      "/api/group-creation-marketing-tasks/export",
      {
        data: { ids },
        responseType: "blob"
      },
      {
        beforeResponseCallback: response => {
          filename = filenameFromContentDisposition(
            headerValue(response.headers, "Content-Disposition")
          );
        }
      }
    )
    .then(blob => ({
      filename: filename || "建群营销统计导出.xlsx",
      blob
    }));
}
```

- [ ] **Step 4: Run API test**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm exec tsx src/api/group-creation-marketing.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/api/group-creation-marketing.ts src/api/group-creation-marketing.test.ts
git commit -m "feat(marketing): add group creation export api"
```

---

### Task 6: Frontend Selection, Export Button, And Download Flow

**Files:**
- Modify: `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.ts`
- Modify: `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.vue`
- Create: `wheel-saas-pure-web/src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/group-creation-marketing/index.vue`

- [ ] **Step 1: Write failing table template test**

Create `GroupCreationMarketingTaskTable.test.ts`:

```ts
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const source = readFileSync(
  new URL("./GroupCreationMarketingTaskTable.vue", import.meta.url),
  "utf8"
);

describe("group creation marketing task table template", () => {
  it("shows selection column and export button next to create", () => {
    assert.match(source, /@selection-change="emit\('selection-change', \$event\)"/);
    assert.match(source, /<el-table-column\s+type="selection"\s+width="48"\s*\/>/);
    assert.match(
      source,
      /新增建群营销[\s\S]*导出[\s\S]*selectedCount/
    );
  });
});
```

- [ ] **Step 2: Write failing composable export test**

In `useGroupCreationMarketingPage.test.ts`, add this test:

```ts
it("exports currently selected tasks and downloads backend workbook", async () => {
  const blob = new Blob(["xlsx"], {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  });
  resetArmadaMockQueue([]);

  const originalCreateObjectUrl = URL.createObjectURL;
  const originalRevokeObjectUrl = URL.revokeObjectURL;
  const originalDocument = globalThis.document;
  const clicked: string[] = [];
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: () => "blob:group-creation-export"
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: (url: string) => clicked.push(`revoke:${url}`)
  });
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: {
      body: {
        appendChild: () => undefined
      },
      createElement: () => ({
        href: "",
        download: "",
        click() {
          clicked.push(`download:${this.download}:${this.href}`);
        },
        remove: () => undefined
      })
    }
  });

  try {
    const { resetHttpMock } = await import("@/api/__tests__/http-test-double");
    resetHttpMock(blob, {
      "content-disposition":
        "attachment; filename*=UTF-8''%E5%BB%BA%E7%BE%A4%E8%90%A5%E9%94%80%E7%BB%9F%E8%AE%A1.xlsx"
    });
    const page = useGroupCreationMarketingPage();
    page.onSelectionChange([
      {
        id: 7,
        taskName: "建群营销",
        accountGroupId: 1,
        accountGroupName: "A组",
        marketingTemplateId: 2,
        marketingTemplateName: "模板",
        status: 3,
        matchedItemCount: 1,
        unmatchedFileCount: 0,
        successCount: 1,
        failedCount: 0,
        abandonedCount: 0,
        sendIntervalSeconds: 30
      }
    ]);

    await page.exportSelectedTasks();

    assert.equal(page.selectedCount.value, 1);
    assert.deepEqual(clicked, [
      "download:建群营销统计.xlsx:blob:group-creation-export",
      "revoke:blob:group-creation-export"
    ]);
  } finally {
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: originalCreateObjectUrl
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: originalRevokeObjectUrl
    });
    Object.defineProperty(globalThis, "document", {
      configurable: true,
      value: originalDocument
    });
  }
});
```

- [ ] **Step 3: Run frontend tests to verify they fail**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm exec tsx src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.test.ts
pnpm exec tsx src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts
```

Expected: FAIL because the selection column, export button, and composable export members do not exist.

- [ ] **Step 4: Update table component script**

In `GroupCreationMarketingTaskTable.vue`, import export icon:

```ts
import Download from "~icons/ep/download";
```

Add props:

```ts
selectedCount: number;
exporting: boolean;
```

Add emits:

```ts
(event: "export-selected"): void;
(event: "selection-change", rows: GroupCreationMarketingTaskRow[]): void;
```

- [ ] **Step 5: Update table component template**

In the buttons slot, after the create button, add:

```vue
<el-button
  plain
  :disabled="selectedCount === 0"
  :loading="exporting"
  :icon="useRenderIcon(Download)"
  @click="emit('export-selected')"
>
  导出
  <span v-if="selectedCount">({{ selectedCount }})</span>
</el-button>
```

Change `<el-table>` opening tag to:

```vue
<el-table
  v-loading="loading"
  :data="rows"
  row-key="id"
  border
  @selection-change="emit('selection-change', $event)"
>
```

Add the selection column before the ID column:

```vue
<el-table-column type="selection" width="48" />
```

- [ ] **Step 6: Update composable imports and state**

In `useGroupCreationMarketingPage.ts`, import export API:

```ts
exportGroupCreationMarketingTasks,
```

Add to `GroupCreationMarketingPageState`:

```ts
exportSelectedTasks: () => Promise<void>;
exporting: Ref<boolean>;
onSelectionChange: (rows: GroupCreationMarketingTaskRow[]) => void;
selectedCount: ComputedRef<number>;
selectedRows: Ref<GroupCreationMarketingTaskRow[]>;
```

Add state:

```ts
const selectedRows = ref<GroupCreationMarketingTaskRow[]>([]);
const exporting = ref(false);
const selectedCount = computed(() => selectedRows.value.length);
```

- [ ] **Step 7: Add composable export methods**

Add near other methods:

```ts
function onSelectionChange(rows: GroupCreationMarketingTaskRow[]): void {
  selectedRows.value = rows;
}

function downloadFile(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

async function exportSelectedTasks(): Promise<void> {
  if (selectedRows.value.length === 0) {
    ElMessage.warning("请先选择要导出的建群营销任务");
    return;
  }
  if (exporting.value) return;
  exporting.value = true;
  try {
    const result = await exportGroupCreationMarketingTasks(
      selectedRows.value.map(row => row.id)
    );
    downloadFile(result.filename, result.blob);
    ElMessage.success("建群营销任务已导出");
  } catch (error) {
    ElMessage.error(apiErrorMessage(error, "建群营销任务导出失败"));
  } finally {
    exporting.value = false;
  }
}
```

Return the new state and methods from `useGroupCreationMarketingPage()`.

- [ ] **Step 8: Wire page component**

In `index.vue`, destructure:

```ts
exporting,
exportSelectedTasks,
onSelectionChange,
selectedCount,
```

Pass props/events to `<GroupCreationMarketingTaskTable>`:

```vue
:exporting="exporting"
:selected-count="selectedCount"
@export-selected="exportSelectedTasks"
@selection-change="onSelectionChange"
```

Do not pass `selectedRows` to the table; it stays page-state only.

- [ ] **Step 9: Run frontend tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm exec tsx src/api/group-creation-marketing.test.ts
pnpm exec tsx src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.test.ts
pnpm exec tsx src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.ts \
  src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts \
  src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.vue \
  src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.test.ts \
  src/views/task/group-creation-marketing/index.vue
git commit -m "feat(marketing): export selected group creation tasks"
```

---

### Task 7: Final Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run backend focused tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskMapperDbTest,GroupCreationMarketingWorkerTest,GroupCreationMarketingTaskServiceImplTest,GroupCreationMarketingTaskControllerTest test
```

Expected: PASS.

- [ ] **Step 2: Run frontend focused tests and typecheck**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm exec tsx src/api/group-creation-marketing.test.ts
pnpm exec tsx src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.test.ts
pnpm exec tsx src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 3: Inspect staged and unstaged changes**

Run in both repositories:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short
```

Expected: only intended feature changes are committed; unrelated pre-existing changes are still untouched.

- [ ] **Step 4: Manual browser verification**

Start the frontend dev server only if needed:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm dev
```

Expected manual checks:

- 建群营销 table shows a checkbox column before ID.
- Header buttons show “新增建群营销” followed by “导出”.
- Export button is disabled with no selection.
- Selecting one or more rows enables the button and shows selected count.
- Clicking export downloads an `.xlsx` file when backend is available.

## Self-Review

- Spec coverage: selection column, export button, selected-task-only export, `.xlsx`, send-time member-count snapshot, `participant_count + 1`, `send_member_count - 1`, blank join count for missing snapshot, and total row are all mapped to tasks.
- Placeholder scan: the plan contains no open placeholder markers or incomplete task descriptions.
- Type consistency: backend uses `sendMemberCount`, `sendMemberCountCheckedAt`, `GroupCreationMarketingExportFile`, and `GroupCreationMarketingExportRow` consistently; frontend uses `exportGroupCreationMarketingTasks`, `selectedRows`, `selectedCount`, and `exportSelectedTasks` consistently.
