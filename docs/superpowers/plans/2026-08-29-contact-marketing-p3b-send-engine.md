# 通讯录营销 P3b 发送引擎 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让一个已启用的通讯录营销任务真正把消息发出去——圈号、展开收件人、按轮次投递协议命令、把三级回执写回计数，直到任务自动完成。

**Architecture:** 照搬 `marketing/scheduler` 三件套范式（Scheduler 扫描 → Worker 事务内抢轮次写 outbox → LifecycleWorker 推状态），但目标不是群而是账号自己通讯录里的联系人。启用时一次性展开 `contact_friend_task_recipient`（幂等键 `task_id+task_account_id+contact_phone`），之后每轮只是把 PENDING 收件人排干。回执经 `ProtocolMessageEventConsumer` 分流到新的 `ContactTaskSendResultSink`，按 `recipientId` 条件更新三级计数。

**Tech Stack:** Java 17 / Spring Boot / MyBatis / Flyway（armada-api）；TypeScript + Jest（armada-protocol）；Go（whatsapp-server）

**Spec:** `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md`（§6.2 数据模型、§7.2 生命周期、§7.3 发送引擎）

**交接状态:** `docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md`

---

## Global Constraints

以下每条都是已冻结事实，任何任务都不得违反：

- **协议 payload 四字段名逐字固定**：`contactTaskId` / `taskAccountId` / `recipientId` / `roundNo`。缺任一，`armada-protocol` 判 `invalid message send payload`（`protocol-layer/src/commands/worker-consumer.ts:1210`）。
- **`source` 常量逐字固定**：`contact_task`。
- **Kafka 线上字段名不改**：`WebMessagePayload` / `AndroidMessagePayload` 里目标 JID 的字段仍叫 `groupJid`，私聊时值为 `<phone>@s.whatsapp.net`。两个协议消费者不需要同步发版。
- **`MessageTarget` 只有一个组件 `jid`**（P0 已中立化，见 `MessageSendCommand.java`）。
- **发送间隔是带一位小数的秒**（`DECIMAL(4,1)`，最快 0.1s），**逐条**在 `[minSec, maxSec]` 内随机取值，不是固定值。
- **消息类型只有两种**：`message_type=0` 链接消息 → 协议 `LINK_CARD`；`message_type=1` 图文消息 → 有图 `IMAGE`（正文作 caption），无图 `TEXT`。**没有按钮**。
- **`is_mutual` 系列列当前恒为 0**（两套协议都不暴露双向好友标记）。发送目标集用 `account_contact.is_named=1` 口径，不用 `is_mutual`。
- **不做计费、不做国家风险拦截**（既有结论 #4）。
- **数据库结构只走 Flyway，新列必须带 `COMMENT`**；落地后重跑 `.harness/wiki/gen_datamodel.py`（AGENTS.md）。
- **`ORDER BY` 必须走 `<choose>` 白名单**，不得字符串插值。
- **空批次不得调 `<foreach>` 批量语句**（会生成空 VALUES 语法错），但扫尾删除与计数归零仍必须发生。
- **改既有类构造签名后，必须同时 grep `new <类名>` 和 `@InjectMocks`。**
- **Java record 组件不能叫 `notify`**（与 `Object.notify()` 冲突）。
- **本机没有 `armada-api/.env`，所有 `*DbTest` 必挂**。因此本计划所有新增测试都是纯类测试：SQL 用文本契约测试、Mapper 用 XML 静态契约测试、Service 用 Mockito。

### 回归基线（涨了必须查清，不能归因于环境）

| 范围 | 基线 |
|---|---|
| armada 全量 | `Failures: 7, Errors: 461`（3532 tests） |
| armada-protocol | 既有失败 suite：`worker/baileys-participating-groups.test.ts`、`traffic/baileys-patch.test.ts` |
| whatsapp-server | 既有失败：`pkg/noise`；`internal/armada` 全绿 |

### 跑测试的正确命令

```bash
# armada（根目录没有聚合 pom，mvn -pl armada-api 会失败，必须先进子模块目录）
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest='ContactTask*Test' -DfailIfNoTests=false

# armada-protocol（必须带 ESM flag，裸 npx jest 会让 28 个 suite 假失败）
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/commands/

# whatsapp-server
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go test ./internal/armada/...
```

统计 armada 全量回归数字时**不要用 `mvn 输出 | grep | tail`**（surefire 打多段汇总，`tail` 会抓到分段行给出假数字），从报告聚合：

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api/target/surefire-reports
echo -n "Tests: ";    grep -h "^Tests run:" *.txt | sed 's/Tests run: \([0-9]*\).*/\1/'   | paste -sd+ | bc
echo -n "Failures: "; grep -h "^Tests run:" *.txt | sed 's/.*Failures: \([0-9]*\).*/\1/' | paste -sd+ | bc
echo -n "Errors: ";   grep -h "^Tests run:" *.txt | sed 's/.*Errors: \([0-9]*\).*/\1/'   | paste -sd+ | bc
```

---

## 交接文档没记的两个真实缺口（本计划必须补，别当成"已完成"）

1. **`armada-protocol` 的成功/失败回执不带联系人关联。** `messageResultBase()`（`worker-consumer.ts:1364`）只回填 marketing / groupCreation / historicalGroup 三组关联，**没有** `contactTaskId` / `taskAccountId` / `recipientId`。只有 `invalidMessageResultBase()` 带。不补，正常发送的回执回到 armada 认不出是哪条收件人。→ **Task 10**
2. **`whatsapp-server` 三处解析仍硬校验 `groupJid` 必须以 `@g.us` 结尾**：`validateMessageCommand`（`message_command.go:275`）、`ParseMessageCommandRoute`（:198）、`ParseMessageCommandReference`（:201）。P1 的 `ac5e583` 只改了 `message_sender.go` 的发送路径，没动解析。安卓号的私聊命令会在解析阶段就被拒。→ **Task 11**

---

## 对设计文档的三处有意偏离（**执行者不要"修正"回去**）

| # | 设计文档写的 | 本计划实际做的 | 理由 |
|---|---|---|---|
| 1 | 圈号服务叫 `HyperlinkAccountSelector`，落在超链包 | 叫 `AccountFilterSelector`，落在 `com.armada.account.selection` | 设计自己写了"与超链任务共用"。共用能力放进被共用方（账号域），超链期直接注入，不用反向依赖某个消费方的包 |
| 2 | `invalid_account_num` = 发送期间被封禁的账号数 | = 一条都没发成功的账号数（`task_account.state='FAILED'`） | armada 没有"发送期间封禁"的判定源，协议失败码没有稳定的封号语义分类。先用可确定口径落地，真封号分类等 V3 真机验证后再补 |
| 3 | 未提 | 新增 `V160` 补 `contact_friend_task.current_round_no`、`contact_friend_task_recipient.round_no` / `command_id` | 轮次号是协议 payload 必填四字段之一，V158 漏建；`command_id` 用于跨层排查 |

---

## File Structure

### armada（`armada-api`）

**新建**

| 文件 | 职责 |
|---|---|
| `resources/db/migration/V160__contact_task_engine.sql` | 补 `current_round_no` / `round_no` / `command_id` 三列 |
| `contact/task/model/entity/ContactFriendTaskRecipient.java` | 收件人实体（V158 已建表，P3a 刻意没建实体） |
| `contact/task/mapper/ContactFriendTaskRecipientMapper.java` + `resources/mapper/contact/ContactFriendTaskRecipientMapper.xml` | 收件人批量插入、抢批、条件回写 |
| `account/selection/AccountFilterCriteria.java` | 归一化 JSON → 强类型圈号条件 |
| `account/selection/AccountFilterSelector.java` | 圈号服务，强制注入 `accountState=正常` + 排除已导出 |
| `account/selection/model/SelectedAccount.java` | 圈号结果行（账号协议事实快照） |
| `account/selection/mapper/AccountFilterSelectionMapper.java` + `resources/mapper/account/AccountFilterSelectionMapper.xml` | 圈号 SQL |
| `contact/task/service/ContactSendIntervalPicker.java` | 逐条随机间隔（纯函数，注入 `Random`） |
| `contact/task/service/ContactTaskExpansionService.java` | 启用时圈号 → 同步通讯录 → 写 task_account → 展开 recipient |
| `contact/task/service/ContactTaskMessageCommandFactory.java` | 任务 + 收件人 → `MessageSendCommand` |
| `contact/task/scheduler/ContactTaskSchedulerProperties.java` | 调度参数 |
| `contact/task/scheduler/ContactTaskSchedulerConfiguration.java` | `@Profile("kafka")` 装配 + `Clock` |
| `contact/task/scheduler/ContactTaskRoundScheduler.java` | 扫描到期任务投线程池 |
| `contact/task/scheduler/ContactTaskRoundWorker.java` | 单任务一轮：抢轮次、抢批、写 outbox |
| `contact/task/scheduler/ContactTaskLifecycleWorker.java` | 计划启动、排干后自动完成 |
| `contact/task/service/ContactTaskSendResultSink.java` | 回执三级回写 |

**修改**

| 文件 | 改动 |
|---|---|
| `platform/protocol/model/command/MessageSendCommand.java` | `MessageCorrelation` 加第 6 个组件 `contactTask`；新增 `ContactTaskCorrelation` |
| `platform/protocol/backend/web/WebMessageSendBackend.java` | `WebMessagePayload` 加三字段并编码 |
| `platform/protocol/backend/android/AndroidMessageSendBackend.java` | `AndroidMessagePayload` 加三字段并编码 |
| `marketing/service/MarketingMessageCommandFactory.java`、`marketing/service/impl/GroupCreationMarketingWorker.java`、`group/service/impl/HistoricalGroupMarketingServiceImpl.java` | 补 `null` 到新组件位 |
| `platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java` | 加 `contactTaskId` / `taskAccountId` / `recipientId` |
| `platform/kafka/consumer/message/ProtocolMessageEventConsumer.java` | `contact_task` 分支：三字段必填，marketing 三字段改选填 |
| `contact/task/mapper/ContactFriendTaskMapper.java` + XML | 加调度查询与计数更新 |
| `contact/task/mapper/ContactFriendTaskAccountMapper.java` + XML | 加批量插入、计数更新、终态收敛 |
| `contact/task/service/impl/ContactTaskServiceImpl.java` | 启用路径接入展开服务 |
| `contact/task/config/ContactTaskConfiguration.java` | 装配展开服务 |

### armada-protocol（`protocol-layer`）

`src/commands/worker-consumer.ts`：`messageResultBase()` 与 `messageSendLogFields()` 补三字段。

### whatsapp-server

`internal/armada/message_command.go`：payload 加三字段；三处 `@g.us` 硬校验放开为「群或私聊对端」；加 `contact_task` 关联校验。
`internal/armada/message_event.go`：结果事件加三字段并回填。

---

## Task 1: V160 迁移补三列

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V160__contact_task_engine.sql`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskEngineMigrationSqlTest.java`

**Interfaces:**
- Consumes: V158 已建的 `contact_friend_task`、`contact_friend_task_recipient`
- Produces: `contact_friend_task.current_round_no`（BIGINT NOT NULL DEFAULT 0）；`contact_friend_task_recipient.round_no`（BIGINT NULL）；`contact_friend_task_recipient.command_id`（VARCHAR(64) NULL）

- [ ] **Step 1: 写失败测试**

`armada-api/src/test/java/com/armada/contact/task/ContactTaskEngineMigrationSqlTest.java`：

```java
package com.armada.contact.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V160 发送引擎补列迁移的 SQL 文本契约测试。本机无库，只校验迁移脚本本身。 */
class ContactTaskEngineMigrationSqlTest {

    private static String sql() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/db/migration/V160__contact_task_engine.sql"),
                StandardCharsets.UTF_8);
    }

    @Test
    void addsCurrentRoundNoToTask() throws IOException {
        String text = sql();

        assertThat(text).contains("contact_friend_task");
        assertThat(text).contains("current_round_no");
    }

    @Test
    void addsRoundNoAndCommandIdToRecipient() throws IOException {
        String text = sql();

        assertThat(text).contains("contact_friend_task_recipient");
        assertThat(text).contains("round_no");
        assertThat(text).contains("command_id");
    }

    @Test
    void everyAddedColumnCarriesComment() throws IOException {
        // AGENTS.md 硬要求：新列必须带 COMMENT
        String text = sql();
        long addColumnLines = text.lines().filter(line -> line.contains("ADD COLUMN")).count();
        long commentedLines = text.lines()
                .filter(line -> line.contains("ADD COLUMN") && line.contains("COMMENT"))
                .count();

        assertThat(addColumnLines).isGreaterThanOrEqualTo(3);
        assertThat(commentedLines).isEqualTo(addColumnLines);
    }

    @Test
    void isIdempotentAgainstRepeatedExecution() throws IOException {
        // 与 V157 同一写法：information_schema 探测后再 ALTER，重复执行不炸
        assertThat(sql()).contains("information_schema.columns");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskEngineMigrationSqlTest -DfailIfNoTests=false`

Expected: FAIL，4 个用例全部 `NoSuchFileException: src/main/resources/db/migration/V160__contact_task_engine.sql`

- [ ] **Step 3: 写迁移脚本**

`armada-api/src/main/resources/db/migration/V160__contact_task_engine.sql`：

```sql
-- 通讯录营销发送引擎补列。
-- V158 建表时只覆盖 CRUD 需要的列，轮次号和命令 ID 是发送闭环才用到的。
-- round_no 是协议 payload 的必填四字段之一（contactTaskId/taskAccountId/recipientId/roundNo），
-- 缺了协议层会判 invalid message send payload 直接丢弃。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'contact_friend_task'
       AND column_name = 'current_round_no') = 0,
    'ALTER TABLE contact_friend_task ADD COLUMN current_round_no BIGINT NOT NULL DEFAULT 0 COMMENT ''已抢占的最新轮次号;每轮加一,写进协议关联供回执定位''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'contact_friend_task_recipient'
       AND column_name = 'round_no') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD COLUMN round_no BIGINT DEFAULT NULL COMMENT ''本条最近一次投递所属轮次号;未投递为NULL''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'contact_friend_task_recipient'
       AND column_name = 'command_id') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD COLUMN command_id VARCHAR(64) DEFAULT NULL COMMENT ''本条最近一次投递的协议命令ID;跨层排查用''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskEngineMigrationSqlTest -DfailIfNoTests=false`

Expected: PASS（Tests run: 4, Failures: 0, Errors: 0）

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/resources/db/migration/V160__contact_task_engine.sql
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskEngineMigrationSqlTest.java
git commit -m "feat(contact): add send engine columns migration"
```

---
## Task 2: 收件人实体与 Mapper

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/model/entity/ContactFriendTaskRecipient.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskRecipientMapper.java`
- Create: `armada-api/src/main/resources/mapper/contact/ContactFriendTaskRecipientMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/contact/task/ContactTaskMapperXmlTest.java`

**Interfaces:**
- Consumes: V158 的 `contact_friend_task_recipient` 表 + Task 1 补的 `round_no` / `command_id`
- Produces:
  - `ContactFriendTaskRecipient`：可变 POJO，字段 `id, tenantId, taskId, taskAccountId, contactPhone, contactJid, contactNamed, sendStatus, attemptCount, protocolMessageId, errorCode, errorDesc, firstSentAt, lastAttemptAt, roundNo, commandId, createdAt, updatedAt`，全部标准 getter/setter
  - `ContactFriendTaskRecipientMapper#insertBatch(List<ContactFriendTaskRecipient> rows) : int`
  - `ContactFriendTaskRecipientMapper#selectPendingByAccount(Long taskAccountId, int limit) : List<ContactFriendTaskRecipient>`
  - `ContactFriendTaskRecipientMapper#selectAccountIdsWithPending(Long taskId, int limit) : List<Long>`
  - `ContactFriendTaskRecipientMapper#claimForSend(Long id, Long roundNo, String commandId, long updatedAt) : int`
  - `ContactFriendTaskRecipientMapper#markSuccess(Long id, String protocolMessageId, long resultAt) : int`
  - `ContactFriendTaskRecipientMapper#markFailed(Long id, String errorCode, String errorDesc, long resultAt) : int`
  - `ContactFriendTaskRecipientMapper#markRetry(Long id, String errorCode, String errorDesc, long resultAt) : int`
  - `ContactFriendTaskRecipientMapper#countUnfinished(Long taskId) : long`
  - `ContactFriendTaskRecipientMapper#countInFlight(Long taskId) : long`

- [ ] **Step 1: 写失败测试**

在 `ContactTaskMapperXmlTest.java` 的 import 区加一行：

```java
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
```

在类体末尾（最后一个 `}` 之前）追加四个用例：

```java
    @Test
    void recipientMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("ContactFriendTaskRecipientMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper\"");
        for (String method : declaredMethods(ContactFriendTaskRecipientMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void recipientClaimIsGuardedByPendingStatus() throws IOException {
        // 抢批必须条件更新，否则两个轮次会把同一条收件人投两次
        assertThat(xml("ContactFriendTaskRecipientMapper.xml"))
                .contains("send_status = 'PENDING'");
    }

    @Test
    void recipientResultWriteBackIsGuardedBySendingStatus() throws IOException {
        // 回执重复到达时条件更新返回 0，调用方据此跳过计数，保证幂等
        assertThat(xml("ContactFriendTaskRecipientMapper.xml"))
                .contains("send_status = 'SENDING'");
    }

    @Test
    void recipientBatchInsertIgnoresIdempotencyKeyConflict() throws IOException {
        // 幂等键 (task_id, task_account_id, contact_phone) 冲突时忽略，重复展开不产生重复收件人
        assertThat(xml("ContactFriendTaskRecipientMapper.xml")).contains("INSERT IGNORE");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskMapperXmlTest -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: class ContactFriendTaskRecipientMapper`

- [ ] **Step 3: 写实体**

`armada-api/src/main/java/com/armada/contact/task/model/entity/ContactFriendTaskRecipient.java`：

```java
package com.armada.contact.task.model.entity;

/**
 * 通讯录营销任务收件人明细行。
 *
 * <p>号码与 JID 都是**展开时的快照**，不外键 {@code account_contact}——通讯录会变，
 * 任务事实不能跟着漂（超链一期 §6.6 既有结论）。</p>
 */
public class ContactFriendTaskRecipient {

    private Long id;
    private Long tenantId;
    private Long taskId;
    private Long taskAccountId;
    private String contactPhone;
    private String contactJid;
    private Integer contactNamed;
    private String sendStatus;
    private Integer attemptCount;
    private String protocolMessageId;
    private String errorCode;
    private String errorDesc;
    private Long firstSentAt;
    private Long lastAttemptAt;
    private Long roundNo;
    private String commandId;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getTaskAccountId() {
        return taskAccountId;
    }

    public void setTaskAccountId(Long taskAccountId) {
        this.taskAccountId = taskAccountId;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactJid() {
        return contactJid;
    }

    public void setContactJid(String contactJid) {
        this.contactJid = contactJid;
    }

    public Integer getContactNamed() {
        return contactNamed;
    }

    public void setContactNamed(Integer contactNamed) {
        this.contactNamed = contactNamed;
    }

    public String getSendStatus() {
        return sendStatus;
    }

    public void setSendStatus(String sendStatus) {
        this.sendStatus = sendStatus;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getProtocolMessageId() {
        return protocolMessageId;
    }

    public void setProtocolMessageId(String protocolMessageId) {
        this.protocolMessageId = protocolMessageId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorDesc() {
        return errorDesc;
    }

    public void setErrorDesc(String errorDesc) {
        this.errorDesc = errorDesc;
    }

    public Long getFirstSentAt() {
        return firstSentAt;
    }

    public void setFirstSentAt(Long firstSentAt) {
        this.firstSentAt = firstSentAt;
    }

    public Long getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Long lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Long getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Long roundNo) {
        this.roundNo = roundNo;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

- [ ] **Step 4: 写 Mapper 接口**

`armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskRecipientMapper.java`：

```java
package com.armada.contact.task.mapper;

import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 通讯录营销任务收件人明细的数据访问。 */
@Mapper
public interface ContactFriendTaskRecipientMapper {

    /**
     * 批量写入收件人。幂等键冲突时忽略，重复展开不会产生重复行。
     *
     * @param rows 收件人行，**调用方必须保证非空**（空批次 foreach 会生成空 VALUES 语法错）
     * @return 受影响行数
     */
    int insertBatch(@Param("rows") List<ContactFriendTaskRecipient> rows);

    /**
     * 取某账号下待发送的收件人。
     *
     * @param taskAccountId 任务账号行 ID
     * @param limit 最多取多少条
     * @return 待发送收件人，按 id 升序
     */
    List<ContactFriendTaskRecipient> selectPendingByAccount(
            @Param("taskAccountId") Long taskAccountId, @Param("limit") int limit);

    /**
     * 取本任务下仍有待发送收件人的账号行 ID。
     *
     * @param taskId 任务 ID
     * @param limit 最多取多少个账号，由任务 concurrency 约束
     * @return 任务账号行 ID
     */
    List<Long> selectAccountIdsWithPending(@Param("taskId") Long taskId, @Param("limit") int limit);

    /**
     * 把一条收件人从 PENDING 抢成 SENDING，写入本轮轮次与命令 ID 并自增尝试次数。
     *
     * @param id 收件人 ID
     * @param roundNo 本轮轮次号
     * @param commandId 协议命令 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 1 表示抢占成功，0 表示已被其他轮次抢走
     */
    int claimForSend(@Param("id") Long id,
                     @Param("roundNo") Long roundNo,
                     @Param("commandId") String commandId,
                     @Param("updatedAt") long updatedAt);

    /**
     * 回写成功结果。仅 SENDING 行会被更新，重复回执返回 0。
     *
     * @param id 收件人 ID
     * @param protocolMessageId 协议返回的消息 ID
     * @param resultAt 结果时间（epoch 毫秒）
     * @return 实际更新行数
     */
    int markSuccess(@Param("id") Long id,
                    @Param("protocolMessageId") String protocolMessageId,
                    @Param("resultAt") long resultAt);

    /**
     * 回写终态失败。仅 SENDING 行会被更新，重复回执返回 0。
     *
     * @param id 收件人 ID
     * @param errorCode 失败码
     * @param errorDesc 失败描述
     * @param resultAt 结果时间（epoch 毫秒）
     * @return 实际更新行数
     */
    int markFailed(@Param("id") Long id,
                   @Param("errorCode") String errorCode,
                   @Param("errorDesc") String errorDesc,
                   @Param("resultAt") long resultAt);

    /**
     * 回写可重试失败，置回 PENDING 等下一轮。仅 SENDING 行会被更新。
     *
     * @param id 收件人 ID
     * @param errorCode 失败码
     * @param errorDesc 失败描述
     * @param resultAt 结果时间（epoch 毫秒）
     * @return 实际更新行数
     */
    int markRetry(@Param("id") Long id,
                  @Param("errorCode") String errorCode,
                  @Param("errorDesc") String errorDesc,
                  @Param("resultAt") long resultAt);

    /**
     * 统计任务下未落终态（PENDING 或 SENDING）的收件人数。
     *
     * @param taskId 任务 ID
     * @return 未完成条数
     */
    long countUnfinished(@Param("taskId") Long taskId);

    /**
     * 统计任务下已投递未回执（SENDING）的收件人数，用作积压闸门。
     *
     * @param taskId 任务 ID
     * @return 在途条数
     */
    long countInFlight(@Param("taskId") Long taskId);
}
```

- [ ] **Step 5: 写 Mapper XML**

`armada-api/src/main/resources/mapper/contact/ContactFriendTaskRecipientMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper">

  <resultMap id="recipientMap"
             type="com.armada.contact.task.model.entity.ContactFriendTaskRecipient">
    <id column="id" property="id"/>
    <result column="tenant_id" property="tenantId"/>
    <result column="task_id" property="taskId"/>
    <result column="task_account_id" property="taskAccountId"/>
    <result column="contact_phone" property="contactPhone"/>
    <result column="contact_jid" property="contactJid"/>
    <result column="contact_named" property="contactNamed"/>
    <result column="send_status" property="sendStatus"/>
    <result column="attempt_count" property="attemptCount"/>
    <result column="protocol_message_id" property="protocolMessageId"/>
    <result column="error_code" property="errorCode"/>
    <result column="error_desc" property="errorDesc"/>
    <result column="first_sent_at" property="firstSentAt"/>
    <result column="last_attempt_at" property="lastAttemptAt"/>
    <result column="round_no" property="roundNo"/>
    <result column="command_id" property="commandId"/>
    <result column="created_at" property="createdAt"/>
    <result column="updated_at" property="updatedAt"/>
  </resultMap>

  <sql id="recipientColumns">
    id, tenant_id, task_id, task_account_id, contact_phone, contact_jid,
    contact_named, send_status, attempt_count, protocol_message_id,
    error_code, error_desc, first_sent_at, last_attempt_at,
    round_no, command_id, created_at, updated_at
  </sql>

  <insert id="insertBatch">
    INSERT IGNORE INTO contact_friend_task_recipient
      (tenant_id, task_id, task_account_id, contact_phone, contact_jid,
       contact_named, send_status, attempt_count, created_at, updated_at)
    VALUES
    <foreach collection="rows" item="row" separator=",">
      (#{row.tenantId}, #{row.taskId}, #{row.taskAccountId}, #{row.contactPhone},
       #{row.contactJid}, #{row.contactNamed}, 'PENDING', 0,
       #{row.createdAt}, #{row.updatedAt})
    </foreach>
  </insert>

  <select id="selectPendingByAccount" resultMap="recipientMap">
    SELECT <include refid="recipientColumns"/>
    FROM contact_friend_task_recipient
    WHERE task_account_id = #{taskAccountId}
      AND send_status = 'PENDING'
    ORDER BY id
    LIMIT #{limit}
  </select>

  <select id="selectAccountIdsWithPending" resultType="java.lang.Long">
    SELECT task_account_id
    FROM contact_friend_task_recipient
    WHERE task_id = #{taskId}
      AND send_status = 'PENDING'
    GROUP BY task_account_id
    ORDER BY task_account_id
    LIMIT #{limit}
  </select>

  <update id="claimForSend">
    UPDATE contact_friend_task_recipient
    SET send_status = 'SENDING',
        round_no = #{roundNo},
        command_id = #{commandId},
        attempt_count = attempt_count + 1,
        last_attempt_at = #{updatedAt},
        updated_at = #{updatedAt}
    WHERE id = #{id}
      AND send_status = 'PENDING'
  </update>

  <update id="markSuccess">
    UPDATE contact_friend_task_recipient
    SET send_status = 'SUCCESS',
        protocol_message_id = #{protocolMessageId},
        error_code = NULL,
        error_desc = NULL,
        first_sent_at = COALESCE(first_sent_at, #{resultAt}),
        updated_at = #{resultAt}
    WHERE id = #{id}
      AND send_status = 'SENDING'
  </update>

  <update id="markFailed">
    UPDATE contact_friend_task_recipient
    SET send_status = 'FAILED',
        error_code = #{errorCode},
        error_desc = #{errorDesc},
        updated_at = #{resultAt}
    WHERE id = #{id}
      AND send_status = 'SENDING'
  </update>

  <!-- 置回 PENDING 让下一轮重排；attempt_count 已在 claimForSend 自增，这里不再加 -->
  <update id="markRetry">
    UPDATE contact_friend_task_recipient
    SET send_status = 'PENDING',
        command_id = NULL,
        error_code = #{errorCode},
        error_desc = #{errorDesc},
        updated_at = #{resultAt}
    WHERE id = #{id}
      AND send_status = 'SENDING'
  </update>

  <select id="countUnfinished" resultType="long">
    SELECT COUNT(*)
    FROM contact_friend_task_recipient
    WHERE task_id = #{taskId}
      AND send_status IN ('PENDING', 'SENDING')
  </select>

  <select id="countInFlight" resultType="long">
    SELECT COUNT(*)
    FROM contact_friend_task_recipient
    WHERE task_id = #{taskId}
      AND send_status = 'SENDING'
  </select>

</mapper>
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskMapperXmlTest -DfailIfNoTests=false`

Expected: PASS

- [ ] **Step 7: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/model/entity/ContactFriendTaskRecipient.java
git add armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskRecipientMapper.java
git add armada-api/src/main/resources/mapper/contact/ContactFriendTaskRecipientMapper.xml
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskMapperXmlTest.java
git commit -m "feat(contact): add task recipient persistence layer"
```

---

## Task 3: 账号圈选条件（纯解析）

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/selection/AccountFilterCriteria.java`
- Test: `armada-api/src/test/java/com/armada/account/selection/AccountFilterCriteriaTest.java`

**Interfaces:**
- Consumes: `ContactAccountFilterNormalizer#normalize` 产出的 camelCase JSON 字符串（键集见设计 §2.7）
- Produces:
  - `AccountFilterCriteria`（record）组件：`List<String> countryIso2s`, `List<String> excludeCountryIso2s`, `List<Long> groupIds`, `List<Long> channelIds`, `String protocolId`, `Integer accountType`, `String phone`, `Long friendCountMin`, `Long friendCountMax`, `Long registerDaysMin`, `Long registerDaysMax`, `Boolean groupInviteAllowed`
  - `AccountFilterCriteria#parse(String normalizedJson, ObjectMapper mapper) : AccountFilterCriteria`
  - `AccountFilterCriteria#isUnrestricted() : boolean` —— 全部条件为空时 true

> **口径说明**：归一化白名单里还有 `continent` / `onlineStatus` / `platform` / `widType` / `errorCode` / `errorDesc` / `retentionDays*` / `createdAt*` / `loggedIn*` 等键。armada 当前没有可下推的对应列（大陆、平台、widType 都没落列），本期**解析后丢弃**，不进 SQL。这是有意的能力边界，不是漏掉；`AccountFilterCriteria` 的 Javadoc 必须写明这一点，避免后续误以为筛选已生效。

- [ ] **Step 1: 写失败测试**

`armada-api/src/test/java/com/armada/account/selection/AccountFilterCriteriaTest.java`：

```java
package com.armada.account.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 账号圈选条件解析的纯类测试。 */
class AccountFilterCriteriaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void treatsEmptyObjectAsUnrestricted() {
        AccountFilterCriteria criteria = AccountFilterCriteria.parse("{}", mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }

    @Test
    void treatsNullJsonAsUnrestricted() {
        AccountFilterCriteria criteria = AccountFilterCriteria.parse(null, mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }

    @Test
    void parsesCountryAndExcludeCountryArrays() {
        String json = "{\"countryIso2s\":[\"IN\",\"BR\"],\"excludeCountryIso2s\":[\"CN\"]}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.countryIso2s()).containsExactly("IN", "BR");
        assertThat(criteria.excludeCountryIso2s()).containsExactly("CN");
        assertThat(criteria.isUnrestricted()).isFalse();
    }

    @Test
    void parsesIdArraysAndScalarFields() {
        String json = "{\"groupIds\":[7,9],\"channelIds\":[3],\"protocolId\":\"web\","
                + "\"accountType\":1,\"phone\":\"8613\",\"groupInviteAllowed\":true}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.groupIds()).containsExactly(7L, 9L);
        assertThat(criteria.channelIds()).containsExactly(3L);
        assertThat(criteria.protocolId()).isEqualTo("web");
        assertThat(criteria.accountType()).isEqualTo(1);
        assertThat(criteria.phone()).isEqualTo("8613");
        assertThat(criteria.groupInviteAllowed()).isTrue();
    }

    @Test
    void parsesNumericRangeBounds() {
        String json = "{\"friendCountMin\":10,\"friendCountMax\":500,"
                + "\"registerDaysMin\":3,\"registerDaysMax\":90}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.friendCountMin()).isEqualTo(10L);
        assertThat(criteria.friendCountMax()).isEqualTo(500L);
        assertThat(criteria.registerDaysMin()).isEqualTo(3L);
        assertThat(criteria.registerDaysMax()).isEqualTo(90L);
    }

    @Test
    void dropsKeysWithoutPushdownSupport() {
        // continent / platform / widType 在 armada 没有可下推的列，解析后必须丢弃，
        // 不能悄悄留在条件里让人以为筛选生效了
        String json = "{\"continent\":\"AS\",\"platform\":\"android\",\"widType\":\"lid\"}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }

    @Test
    void treatsUnparseableJsonAsUnrestricted() {
        // 坏 JSON 等价于「不限定」，不让一个筛选字段把整个圈号搞崩
        AccountFilterCriteria criteria = AccountFilterCriteria.parse("{not json", mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=AccountFilterCriteriaTest -DfailIfNoTests=false`

Expected: 编译失败，`package com.armada.account.selection does not exist`

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/account/selection/AccountFilterCriteria.java`：

```java
package com.armada.account.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 账号圈选条件。由归一化后的 camelCase 筛选 JSON 解析而来。
 *
 * <p><b>能力边界</b>：归一化白名单里的 {@code continent} / {@code onlineStatus} /
 * {@code platform} / {@code widType} / {@code errorCode} / {@code errorDesc} /
 * {@code retentionDays*} / {@code createdAt*} / {@code loggedIn*} 在 armada 没有可下推的列，
 * 本类**解析后直接丢弃**，不进 SQL。这是有意的能力边界：宁可少筛也不能让调用方以为筛了。
 * 补列之后在这里加组件、在 {@code AccountFilterSelectionMapper.xml} 加条件即可。</p>
 *
 * @param countryIso2s 命中国家码；空表示不限
 * @param excludeCountryIso2s 排除国家码；空表示不排除
 * @param groupIds 命中分组 ID；空表示不限
 * @param channelIds 命中渠道 ID；空表示不限
 * @param protocolId 接入协议标识；null 表示不限
 * @param accountType 账号类型 1 个人 2 商业；null 表示不限
 * @param phone 号码前缀；null 表示不限
 * @param friendCountMin 双向好友数下界；null 表示不限
 * @param friendCountMax 双向好友数上界；null 表示不限
 * @param registerDaysMin 注册天数下界；null 表示不限
 * @param registerDaysMax 注册天数上界；null 表示不限
 * @param groupInviteAllowed 是否允许被拉群；null 表示不限
 */
public record AccountFilterCriteria(
        List<String> countryIso2s,
        List<String> excludeCountryIso2s,
        List<Long> groupIds,
        List<Long> channelIds,
        String protocolId,
        Integer accountType,
        String phone,
        Long friendCountMin,
        Long friendCountMax,
        Long registerDaysMin,
        Long registerDaysMax,
        Boolean groupInviteAllowed
) {

    /** 全部条件为空的「不限定」实例。 */
    public static final AccountFilterCriteria UNRESTRICTED = new AccountFilterCriteria(
            List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null, null, null, null);

    /** 组件全部经过防御性拷贝，实例不可变。 */
    public AccountFilterCriteria {
        countryIso2s = countryIso2s == null ? List.of() : List.copyOf(countryIso2s);
        excludeCountryIso2s = excludeCountryIso2s == null ? List.of() : List.copyOf(excludeCountryIso2s);
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        channelIds = channelIds == null ? List.of() : List.copyOf(channelIds);
    }

    /**
     * 解析归一化后的筛选 JSON。
     *
     * @param normalizedJson 归一化 JSON；null、空串或非法 JSON 一律视为不限定
     * @param mapper JSON 解码器
     * @return 圈选条件
     */
    public static AccountFilterCriteria parse(String normalizedJson, ObjectMapper mapper) {
        if (normalizedJson == null || normalizedJson.isBlank()) {
            return UNRESTRICTED;
        }
        JsonNode root;
        try {
            root = mapper.readTree(normalizedJson);
        } catch (Exception ex) {
            return UNRESTRICTED;
        }
        if (root == null || !root.isObject()) {
            return UNRESTRICTED;
        }
        return new AccountFilterCriteria(
                textList(root, "countryIso2s"),
                textList(root, "excludeCountryIso2s"),
                longList(root, "groupIds"),
                longList(root, "channelIds"),
                text(root, "protocolId"),
                integer(root, "accountType"),
                text(root, "phone"),
                longValue(root, "friendCountMin"),
                longValue(root, "friendCountMax"),
                longValue(root, "registerDaysMin"),
                longValue(root, "registerDaysMax"),
                bool(root, "groupInviteAllowed"));
    }

    /** 没有任何有效条件时语义为「全部有效账号」。 */
    public boolean isUnrestricted() {
        return countryIso2s.isEmpty()
                && excludeCountryIso2s.isEmpty()
                && groupIds.isEmpty()
                && channelIds.isEmpty()
                && protocolId == null
                && accountType == null
                && phone == null
                && friendCountMin == null
                && friendCountMax == null
                && registerDaysMin == null
                && registerDaysMax == null
                && groupInviteAllowed == null;
    }

    private static List<String> textList(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private static List<Long> longList(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item != null && item.canConvertToLong()) {
                values.add(item.asLong());
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Integer integer(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.canConvertToInt() ? node.asInt() : null;
    }

    private static Long longValue(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.canConvertToLong() ? node.asLong() : null;
    }

    private static Boolean bool(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=AccountFilterCriteriaTest -DfailIfNoTests=false`

Expected: PASS（Tests run: 7）

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/selection/AccountFilterCriteria.java
git add armada-api/src/test/java/com/armada/account/selection/AccountFilterCriteriaTest.java
git commit -m "feat(account): add account filter criteria parsing"
```

---

## Task 4: 账号圈选服务与 SQL

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/selection/model/SelectedAccount.java`
- Create: `armada-api/src/main/java/com/armada/account/selection/mapper/AccountFilterSelectionMapper.java`
- Create: `armada-api/src/main/resources/mapper/account/AccountFilterSelectionMapper.xml`
- Create: `armada-api/src/main/java/com/armada/account/selection/AccountFilterSelector.java`
- Test: `armada-api/src/test/java/com/armada/account/selection/AccountFilterSelectorTest.java`
- Test: `armada-api/src/test/java/com/armada/account/selection/AccountFilterSelectionMapperXmlTest.java`

**Interfaces:**
- Consumes: `AccountFilterCriteria`（Task 3）
- Produces:
  - `SelectedAccount`（record）：`Long accountId, String wsPhone, String protocolId, String protocolAccountId`
  - `AccountFilterSelectionMapper#selectAccounts(AccountFilterCriteria criteria, int normalAccountState, int exportedAccountState, int limit) : List<SelectedAccount>`
  - `AccountFilterSelector#select(String normalizedFilterJson, int limit) : List<SelectedAccount>`

> **强制注入**（设计 §2.7）：`account_status = normal`、`is_exported = false`。armada 侧对应 `account_state.account_state = 2`（正常）且 `!= 4`（导出）。`account_state=2` 已排除 4，SQL 里仍显式排除，防止将来枚举扩展时口径漂移。

- [ ] **Step 1: 写失败测试（Selector）**

`armada-api/src/test/java/com/armada/account/selection/AccountFilterSelectorTest.java`：

```java
package com.armada.account.selection;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 账号圈选服务的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
class AccountFilterSelectorTest {

    @Mock
    private AccountFilterSelectionMapper mapper;

    private AccountFilterSelector selector() {
        return new AccountFilterSelector(mapper, new ObjectMapper());
    }

    @Test
    void passesParsedCriteriaToMapper() {
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        selector().select("{\"groupIds\":[7]}", 100);

        ArgumentCaptor<AccountFilterCriteria> captor =
                ArgumentCaptor.forClass(AccountFilterCriteria.class);
        verify(mapper).selectAccounts(captor.capture(), anyInt(), anyInt(), anyInt());
        assertThat(captor.getValue().groupIds()).containsExactly(7L);
    }

    @Test
    void alwaysInjectsNormalAndNotExportedAccountState() {
        // 设计 §2.7 强制注入：account_status=normal、is_exported=false
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        selector().select("{}", 100);

        verify(mapper).selectAccounts(
                any(),
                eq(AccountFilterSelector.ACCOUNT_STATE_NORMAL),
                eq(AccountFilterSelector.ACCOUNT_STATE_EXPORTED),
                eq(100));
    }

    @Test
    void returnsEmptyListWhenLimitIsNotPositive() {
        // 上限非正数时不该退化成全表扫描
        assertThat(selector().select("{}", 0)).isEmpty();
        assertThat(selector().select("{}", -1)).isEmpty();
    }

    @Test
    void returnsMapperRowsUnchanged() {
        SelectedAccount row = new SelectedAccount(11L, "8613800000000", "web", "acc_8613800000000");
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(row));

        List<SelectedAccount> selected = selector().select("{}", 50);

        assertThat(selected).containsExactly(row);
    }

    @Test
    void tolerantOfNullMapperResult() {
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(null);

        assertThat(selector().select("{}", 50)).isEmpty();
    }
}
```

- [ ] **Step 2: 写失败测试（XML 契约）**

`armada-api/src/test/java/com/armada/account/selection/AccountFilterSelectionMapperXmlTest.java`：

```java
package com.armada.account.selection;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 账号圈选 Mapper XML 静态契约测试。本机无库，只校验契约。 */
class AccountFilterSelectionMapperXmlTest {

    private static String xml() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/account/AccountFilterSelectionMapper.xml"),
                StandardCharsets.UTF_8);
    }

    private static Set<String> declaredMethods(Class<?> mapper) {
        return Arrays.stream(mapper.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void declaresEveryInterfaceMethod() throws IOException {
        String sql = xml();

        assertThat(sql).contains(
                "namespace=\"com.armada.account.selection.mapper.AccountFilterSelectionMapper\"");
        for (String method : declaredMethods(AccountFilterSelectionMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void excludesSoftDeletedAccounts() throws IOException {
        assertThat(xml()).contains("a.deleted_at IS NULL");
    }

    @Test
    void enforcesNormalAndNotExportedAccountState() throws IOException {
        String sql = xml();

        assertThat(sql).contains("s.account_state = #{normalAccountState}");
        assertThat(sql).contains("s.account_state &lt;&gt; #{exportedAccountState}");
    }

    @Test
    void requiresProtocolFactsForSendableAccounts() throws IOException {
        // 没有协议句柄的号发不出消息，圈号阶段就要排掉，别让它进 task_account 占坑
        String sql = xml();

        assertThat(sql).contains("a.protocol_account_id IS NOT NULL");
    }

    @Test
    void boundsResultSetWithLimit() throws IOException {
        assertThat(xml()).contains("LIMIT #{limit}");
    }

    @Test
    void pushesFriendCountBoundsToContactMutualColumn() throws IOException {
        // 筛选控件叫「双向好友数」，对应 account_state.contact_mutual_num（设计 §2.8）
        String sql = xml();

        assertThat(sql).contains("contact_mutual_num");
    }
}
```

- [ ] **Step 3: 跑两个测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='AccountFilterSelectorTest,AccountFilterSelectionMapperXmlTest' -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: class AccountFilterSelectionMapper`

- [ ] **Step 4: 写结果行**

`armada-api/src/main/java/com/armada/account/selection/model/SelectedAccount.java`：

```java
package com.armada.account.selection.model;

/**
 * 圈号命中的一个账号及其协议事实快照。
 *
 * @param accountId Armada 账号主键
 * @param wsPhone WhatsApp 号码
 * @param protocolId 接入协议标识，决定发往 Web 还是 Android 后端
 * @param protocolAccountId 协议账号句柄
 */
public record SelectedAccount(
        Long accountId,
        String wsPhone,
        String protocolId,
        String protocolAccountId
) {
}
```

- [ ] **Step 5: 写 Mapper 接口**

`armada-api/src/main/java/com/armada/account/selection/mapper/AccountFilterSelectionMapper.java`：

```java
package com.armada.account.selection.mapper;

import com.armada.account.selection.AccountFilterCriteria;
import com.armada.account.selection.model.SelectedAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 按账号筛选条件圈号的数据访问。tenant_id 由租户拦截器注入。 */
@Mapper
public interface AccountFilterSelectionMapper {

    /**
     * 按筛选条件圈出可发送账号。
     *
     * @param criteria 圈选条件
     * @param normalAccountState 正常状态码，强制注入
     * @param exportedAccountState 已导出状态码，强制排除
     * @param limit 结果上限
     * @return 命中账号，按 priority 降序、id 升序
     */
    List<SelectedAccount> selectAccounts(
            @Param("criteria") AccountFilterCriteria criteria,
            @Param("normalAccountState") int normalAccountState,
            @Param("exportedAccountState") int exportedAccountState,
            @Param("limit") int limit);
}
```

- [ ] **Step 6: 写 Mapper XML**

`armada-api/src/main/resources/mapper/account/AccountFilterSelectionMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.account.selection.mapper.AccountFilterSelectionMapper">

  <select id="selectAccounts"
          resultType="com.armada.account.selection.model.SelectedAccount">
    SELECT a.id            AS accountId,
           a.ws_phone      AS wsPhone,
           a.protocol_id   AS protocolId,
           a.protocol_account_id AS protocolAccountId
    FROM account a
    INNER JOIN account_state s
            ON s.account_id = a.id AND s.tenant_id = a.tenant_id
    WHERE a.deleted_at IS NULL
      AND a.protocol_account_id IS NOT NULL
      AND a.protocol_account_id &lt;&gt; ''
      AND s.account_state = #{normalAccountState}
      AND s.account_state &lt;&gt; #{exportedAccountState}
      <if test="criteria.accountType != null">
        AND a.account_type = #{criteria.accountType}
      </if>
      <if test="criteria.protocolId != null">
        AND a.protocol_id = #{criteria.protocolId}
      </if>
      <if test="criteria.phone != null">
        AND a.ws_phone LIKE CONCAT(#{criteria.phone}, '%')
      </if>
      <if test="criteria.groupIds != null and criteria.groupIds.size() > 0">
        AND a.account_group_id IN
        <foreach collection="criteria.groupIds" item="groupId" open="(" separator="," close=")">
          #{groupId}
        </foreach>
      </if>
      <if test="criteria.channelIds != null and criteria.channelIds.size() > 0">
        AND a.account_group_id IN
        <foreach collection="criteria.channelIds" item="channelId" open="(" separator="," close=")">
          #{channelId}
        </foreach>
      </if>
      <if test="criteria.countryIso2s != null and criteria.countryIso2s.size() > 0">
        AND s.proxy_country IN
        <foreach collection="criteria.countryIso2s" item="iso2" open="(" separator="," close=")">
          #{iso2}
        </foreach>
      </if>
      <if test="criteria.excludeCountryIso2s != null and criteria.excludeCountryIso2s.size() > 0">
        AND (s.proxy_country IS NULL OR s.proxy_country NOT IN
        <foreach collection="criteria.excludeCountryIso2s" item="iso2" open="(" separator="," close=")">
          #{iso2}
        </foreach>
        )
      </if>
      <if test="criteria.friendCountMin != null">
        AND s.contact_mutual_num &gt;= #{criteria.friendCountMin}
      </if>
      <if test="criteria.friendCountMax != null">
        AND s.contact_mutual_num &lt;= #{criteria.friendCountMax}
      </if>
      <if test="criteria.registerDaysMin != null">
        AND a.created_at &lt;= (UNIX_TIMESTAMP() * 1000 - #{criteria.registerDaysMin} * 86400000)
      </if>
      <if test="criteria.registerDaysMax != null">
        AND a.created_at &gt;= (UNIX_TIMESTAMP() * 1000 - #{criteria.registerDaysMax} * 86400000)
      </if>
    ORDER BY a.priority DESC, a.id
    LIMIT #{limit}
  </select>

</mapper>
```

> **注意**：`groupInviteAllowed` 在 armada 没有落列，XML 里**故意没有对应条件**——与 `AccountFilterCriteria` 的能力边界说明一致。`channelIds` 与 `groupIds` 都下推到 `account_group_id`：竞品把渠道和分组当两个维度，armada 只有一个归一分组列，这是已知的口径收敛，写在 Javadoc 里。

- [ ] **Step 7: 写 Selector**

`armada-api/src/main/java/com/armada/account/selection/AccountFilterSelector.java`：

```java
package com.armada.account.selection;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按账号筛选条件圈出可发送账号。
 *
 * <p>通讯录营销与超链任务共用同一份圈号口径，因此本服务落在账号域而不是任一消费方的包里。</p>
 *
 * <p><b>强制注入</b>（设计 §2.7）：无论筛选条件写了什么，都只圈「正常且未导出」的账号。
 * 筛选条件为空时语义是「全部有效账号」，不是「不圈号」。</p>
 */
@Component
public class AccountFilterSelector {

    /** 账号状态：正常。取值见 {@code V005__account.sql} 的 {@code account_state} 列注释。 */
    public static final int ACCOUNT_STATE_NORMAL = 2;

    /** 账号状态：已导出。强制排除。 */
    public static final int ACCOUNT_STATE_EXPORTED = 4;

    private final AccountFilterSelectionMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建圈号服务。
     *
     * @param mapper 圈号数据访问
     * @param objectMapper JSON 解码器
     */
    public AccountFilterSelector(AccountFilterSelectionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 按归一化后的筛选 JSON 圈号。
     *
     * @param normalizedFilterJson 归一化筛选 JSON；null、空或非法均视为不限定
     * @param limit 结果上限；非正数直接返回空列表，避免退化成全表扫描
     * @return 命中账号，可能为空
     */
    public List<SelectedAccount> select(String normalizedFilterJson, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        AccountFilterCriteria criteria =
                AccountFilterCriteria.parse(normalizedFilterJson, objectMapper);
        List<SelectedAccount> rows = mapper.selectAccounts(
                criteria, ACCOUNT_STATE_NORMAL, ACCOUNT_STATE_EXPORTED, limit);
        return rows == null ? List.of() : List.copyOf(rows);
    }
}
```

- [ ] **Step 8: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='AccountFilterSelectorTest,AccountFilterSelectionMapperXmlTest' -DfailIfNoTests=false`

Expected: PASS（Tests run: 11）

- [ ] **Step 9: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/selection/
git add armada-api/src/main/resources/mapper/account/AccountFilterSelectionMapper.xml
git add armada-api/src/test/java/com/armada/account/selection/
git commit -m "feat(account): add shared account filter selector"
```

---
## Task 5: 逐条随机发送间隔

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactSendIntervalPicker.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactSendIntervalPickerTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `ContactSendIntervalPicker#pickMs(BigDecimal minSec, BigDecimal maxSec, Random random) : int`

> **为什么单独一个类**：设计明确「`sendIntervalMs` 在 `[minSec, maxSec]` 区间**逐条**随机取值」。把它做成注入 `Random` 的纯函数，才能用固定种子把随机性钉死做断言；埋在 worker 里就测不了。竞品最快 0.1s，落成整数会把「最快」这档做没，所以入参是 `BigDecimal` 不是 `int`。

- [ ] **Step 1: 写失败测试**

`armada-api/src/test/java/com/armada/contact/task/ContactSendIntervalPickerTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.service.ContactSendIntervalPicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 逐条随机发送间隔的纯函数测试。种子固定，断言可重复。 */
class ContactSendIntervalPickerTest {

    @Test
    void keepsSubSecondPrecision() {
        // 竞品最快 0.1 秒；落成整数秒会把这一档做没
        int ms = ContactSendIntervalPicker.pickMs(
                new BigDecimal("0.1"), new BigDecimal("0.1"), new Random(1L));

        assertThat(ms).isEqualTo(100);
    }

    @Test
    void staysInsideClosedRange() {
        Random random = new Random(42L);

        for (int i = 0; i < 200; i++) {
            int ms = ContactSendIntervalPicker.pickMs(
                    new BigDecimal("0.5"), new BigDecimal("3.0"), random);

            assertThat(ms).isBetween(500, 3000);
        }
    }

    @Test
    void variesAcrossConsecutiveCalls() {
        // 逐条随机，不是整轮取一个固定值
        Random random = new Random(7L);
        int first = ContactSendIntervalPicker.pickMs(
                new BigDecimal("1.0"), new BigDecimal("10.0"), random);
        int second = ContactSendIntervalPicker.pickMs(
                new BigDecimal("1.0"), new BigDecimal("10.0"), random);
        int third = ContactSendIntervalPicker.pickMs(
                new BigDecimal("1.0"), new BigDecimal("10.0"), random);

        assertThat(java.util.Set.of(first, second, third)).hasSizeGreaterThan(1);
    }

    @Test
    void swapsInvertedBounds() {
        int ms = ContactSendIntervalPicker.pickMs(
                new BigDecimal("3.0"), new BigDecimal("1.0"), new Random(1L));

        assertThat(ms).isBetween(1000, 3000);
    }

    @Test
    void fallsBackToDefaultWhenBoundsAreMissing() {
        assertThat(ContactSendIntervalPicker.pickMs(null, null, new Random(1L)))
                .isEqualTo(ContactSendIntervalPicker.DEFAULT_INTERVAL_MS);
    }

    @Test
    void clampsNonPositiveBoundsToMinimum() {
        // 0 或负间隔会让协议层紧循环发送，必须兜到最小 100ms
        int ms = ContactSendIntervalPicker.pickMs(
                new BigDecimal("0"), new BigDecimal("0"), new Random(1L));

        assertThat(ms).isEqualTo(ContactSendIntervalPicker.MIN_INTERVAL_MS);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactSendIntervalPickerTest -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: class ContactSendIntervalPicker`

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/contact/task/service/ContactSendIntervalPicker.java`：

```java
package com.armada.contact.task.service;

import java.math.BigDecimal;
import java.util.Random;

/**
 * 单条消息发送间隔选取器。
 *
 * <p>竞品的间隔是「秒带一位小数」的闭区间，最快 0.1 秒，且**逐条**在区间内随机取值——
 * 整轮取一个固定值等于没做随机化，风控特征反而更明显。纯函数，随机源由调用方注入。</p>
 */
public final class ContactSendIntervalPicker {

    /** 上下界都缺失时的兜底间隔。 */
    public static final int DEFAULT_INTERVAL_MS = 1000;

    /** 允许的最小间隔，防止 0 或负配置让协议层紧循环。 */
    public static final int MIN_INTERVAL_MS = 100;

    private static final BigDecimal MILLIS_PER_SECOND = new BigDecimal("1000");

    private ContactSendIntervalPicker() {
    }

    /**
     * 在 {@code [minSec, maxSec]} 闭区间内随机取一个毫秒间隔。
     *
     * @param minSec 最小间隔秒数，可为 null
     * @param maxSec 最大间隔秒数，可为 null
     * @param random 随机源，由调用方注入以便测试
     * @return 毫秒间隔，不小于 {@link #MIN_INTERVAL_MS}
     */
    public static int pickMs(BigDecimal minSec, BigDecimal maxSec, Random random) {
        Integer lower = toMillis(minSec);
        Integer upper = toMillis(maxSec);
        if (lower == null && upper == null) {
            return DEFAULT_INTERVAL_MS;
        }
        int low = lower == null ? upper : lower;
        int high = upper == null ? low : upper;
        if (low > high) {
            int swap = low;
            low = high;
            high = swap;
        }
        low = Math.max(MIN_INTERVAL_MS, low);
        high = Math.max(low, high);
        if (low == high) {
            return low;
        }
        return low + random.nextInt(high - low + 1);
    }

    /** 秒转毫秒；null 原样返回 null，让调用方区分「没配」和「配了 0」。 */
    private static Integer toMillis(BigDecimal seconds) {
        if (seconds == null) {
            return null;
        }
        return seconds.multiply(MILLIS_PER_SECOND).intValue();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactSendIntervalPickerTest -DfailIfNoTests=false`

Expected: PASS（Tests run: 6）

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/service/ContactSendIntervalPicker.java
git add armada-api/src/test/java/com/armada/contact/task/ContactSendIntervalPickerTest.java
git commit -m "feat(contact): add per-message send interval picker"
```

---

## Task 6: 协议命令加通讯录关联

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/MarketingMessageCommandFactory.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupMarketingServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/web/WebMessageSendBackendTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingMessageSendPortTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `MessageSendCommand.ContactTaskCorrelation`（record）：`Long taskId, Long taskAccountId, Long recipientId, Long roundNo`
  - `MessageSendCommand.MessageCorrelation` 增加第 6 个组件 `ContactTaskCorrelation contactTask`（追加在 `historicalGroup` 之后）
  - Web/Android wire payload 新增三个字段：`contactTaskId`、`taskAccountId`、`recipientId`（`roundNo` 两侧本来就有）

> **执行前先跑一次**：`grep -rn "new MessageSendCommand.MessageCorrelation" armada-api/src`。当前有 10 处（3 处生产 + 7 处测试）。改 record 组件后**全部**要补 `null`。别只改生产代码——P2 就是漏了 `@InjectMocks` 才挂了 3 个既有测试。

- [ ] **Step 1: 写失败测试（Web 后端编码）**

在 `WebMessageSendBackendTest.java` 追加：

```java
    @Test
    void encodesContactTaskCorrelationFields() {
        // 协议层判 contact_task 时四字段缺一即丢弃，字段名必须逐字一致
        MessageSendCommand command = new MessageSendCommand(
                new ProtocolAccountRef(11L, ProtocolBackend.WEB, "acc_8613800000000", "8613800000000"),
                new MessageSendCommand.MessageTarget("8613900000000@s.whatsapp.net"),
                new MessageSendCommand.MessagePayload(
                        MessageType.TEXT,
                        new MessageSendCommand.MessageContent("hi", null, null, null),
                        false),
                new MessageSendCommand.MessageCorrelation(
                        3L, "contact_task", null, null, null,
                        new MessageSendCommand.ContactTaskCorrelation(77L, 88L, 99L, 5L)),
                "cmd_contact_1",
                800,
                0L);

        backend.enqueue(List.of(command));

        ProtocolMessageOutboxCommand outbox = captureSingleOutboxCommand();
        String json = writeValueAsString(outbox.payload());
        assertThat(json).contains("\"contactTaskId\":77");
        assertThat(json).contains("\"taskAccountId\":88");
        assertThat(json).contains("\"recipientId\":99");
        assertThat(json).contains("\"roundNo\":5");
        assertThat(json).contains("\"source\":\"contact_task\"");
        assertThat(json).contains("\"groupJid\":\"8613900000000@s.whatsapp.net\"");
    }
```

> `captureSingleOutboxCommand()` 与 `writeValueAsString(...)` 若测试类里还没有，按该测试类已有的 mock 捕获写法补两个私有辅助方法；不要引入新框架。

- [ ] **Step 2: 写失败测试（Android 后端编码）**

在 `AndroidMessageSendBackendTest.java` 追加同形状的用例，断言同样三个字段出现在 Android payload JSON 里，且 `wsPhone` 仍被编码（Android 独有字段，不能因为改 correlation 丢掉）。

- [ ] **Step 3: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='WebMessageSendBackendTest,AndroidMessageSendBackendTest' -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: class ContactTaskCorrelation`

- [ ] **Step 4: 改 `MessageSendCommand`**

在 `MessageCorrelation` 的 Javadoc 加一行 `@param contactTask 通讯录营销关联`，组件列表末尾追加 `ContactTaskCorrelation contactTask`：

```java
    /**
     * 消息与营销任务的关联信息。
     *
     * @param tenantId 租户 ID
     * @param source 命令来源
     * @param marketing 普通营销关联
     * @param groupCreation 建群营销关联
     * @param historicalGroup 历史群拉人营销关联
     * @param contactTask 通讯录营销关联
     */
    public record MessageCorrelation(
            Long tenantId,
            String source,
            MarketingCorrelation marketing,
            GroupCreationCorrelation groupCreation,
            HistoricalGroupCorrelation historicalGroup,
            ContactTaskCorrelation contactTask
    ) {
    }
```

在同文件末尾（`MessageButton` record 之后）追加：

```java
    /**
     * 通讯录营销任务关联信息。
     *
     * <p>四个字段是协议层的硬契约：{@code source='contact_task'} 时缺任一，
     * 协议层判 {@code invalid message send payload} 直接丢弃。</p>
     *
     * @param taskId 通讯录营销任务 ID，wire 名 {@code contactTaskId}
     * @param taskAccountId 任务账号行 ID，wire 名 {@code taskAccountId}
     * @param recipientId 收件人明细 ID，wire 名 {@code recipientId}
     * @param roundNo 轮次号，wire 名 {@code roundNo}
     */
    public record ContactTaskCorrelation(
            Long taskId,
            Long taskAccountId,
            Long recipientId,
            Long roundNo
    ) {
    }
```

- [ ] **Step 5: 改 Web 后端**

在 `toOutboxCommand` 里取出关联并编码：

```java
        MessageSendCommand.ContactTaskCorrelation contactTask = correlation.contactTask();
```

`WebMessagePayload` 构造调用的 `roundNo` 参数改为兼容两种来源：

```java
                marketing != null ? marketing.roundNo()
                        : contactTask == null ? null : contactTask.roundNo(),
```

在参数列表末尾追加三个：

```java
                contactTask == null ? null : contactTask.taskId(),
                contactTask == null ? null : contactTask.taskAccountId(),
                contactTask == null ? null : contactTask.recipientId());
```

`WebMessagePayload` record 末尾追加三个组件：

```java
            Long contactTaskId,
            Long taskAccountId,
            Long recipientId
```

- [ ] **Step 6: 改 Android 后端**

同样在 `AndroidMessagePayload` 末尾追加 `Long contactTaskId, Long taskAccountId, Long recipientId`，`roundNo` 同样兼容两种来源，编码处补三个取值。

- [ ] **Step 7: 补齐其余 8 处构造点**

```bash
cd /home/yanwenchao/ideaProject/armada
grep -rn "new MessageSendCommand.MessageCorrelation" armada-api/src
```

对每一处在最后一个实参后补 `null`（通讯录任务自己的构造点在 Task 10 才出现，此刻全部补 `null`）。生产代码三处：
`MarketingMessageCommandFactory#toCommand`、`GroupCreationMarketingWorker`、`HistoricalGroupMarketingServiceImpl`。
测试四个类里各若干处，同样补 `null`。

- [ ] **Step 8: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest='WebMessageSendBackendTest,AndroidMessageSendBackendTest,RoutingMessageSendPortTest,ProtocolCommandOutboxServiceImplTest,MarketingRoundWorkerTest' -DfailIfNoTests=false
```

Expected: PASS，且不出现任何 `MessageCorrelation` 相关编译错误

- [ ] **Step 9: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java
git add armada-api/src/main/java/com/armada/platform/protocol/backend/
git add armada-api/src/main/java/com/armada/marketing/service/
git add armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupMarketingServiceImpl.java
git add armada-api/src/test/java/com/armada/platform/protocol/
git commit -m "feat(protocol): carry contact task correlation in send command"
```

---

## Task 7: 任务与账号 Mapper 的引擎语句

**Files:**
- Modify: `armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/contact/ContactFriendTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskAccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/contact/ContactFriendTaskAccountMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountContactMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/contact/task/ContactTaskMapperXmlTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/contact/AccountContactMapperXmlTest.java`

**Interfaces:**
- Consumes: Task 1 的三列
- Produces（`ContactFriendTaskMapper` 新增）：
  - `List<ContactFriendTask> selectDueRunningTasks(long now, int limit)`
  - `List<ContactFriendTask> selectDueScheduledTasks(long now, int limit)`
  - `int startDueScheduledTask(Long id, long startedAt)`
  - `int claimDueRound(Long id, long now, Long nextRoundAt)`
  - `int postponeDueRound(Long id, long now, Long nextRoundAt)`
  - `int applyExpansionTotals(Long id, int totalSendNum, int usedAccountCount, long updatedAt)`
  - `int incrementSuccessMessageNum(Long id, int delta, long updatedAt)`
  - `int completeDrainedTask(Long id, long finishedAt)`
- Produces（`ContactFriendTaskAccountMapper` 新增）：
  - `int insert(ContactFriendTaskAccount row)`（`useGeneratedKeys` 回填 `id`）
  - `ContactFriendTaskAccount selectById(Long id)`
  - `int incrementSentNum(Long id, long updatedAt)`
  - `int incrementFailNum(Long id, long updatedAt)`
  - `int markRunning(Long id, long updatedAt)`
  - `int settleDrainedAccounts(Long taskId, long updatedAt)`
  - `long countFailedAccounts(Long taskId)`
- Produces（`AccountContactMapper` 新增）：
  - `List<AccountContact> selectNamedByAccount(Long accountId, int limit)`

- [ ] **Step 1: 写失败测试**

在 `ContactTaskMapperXmlTest.java` 追加：

```java
    @Test
    void dueTaskScanIsBoundedAndSkipsSoftDeleted() throws IOException {
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"selectDueRunningTasks\"");
        assertThat(sql).contains("id=\"selectDueScheduledTasks\"");
        assertThat(sql).contains("LIMIT #{limit}");
        assertThat(sql).contains("deleted_at IS NULL");
    }

    @Test
    void roundClaimIsTheConcurrencyGate() throws IOException {
        // claimDueRound 是并发闸门：只有一个线程能把到期任务推进到下一轮
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"claimDueRound\"");
        assertThat(sql).contains("current_round_no = current_round_no + 1");
        assertThat(sql).contains("next_round_at &lt;= #{now}");
    }

    @Test
    void scheduledStartOnlyPromotesEnabledNotStartedTasks() throws IOException {
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"startDueScheduledTask\"");
        assertThat(sql).contains("is_enabled = 1");
    }

    @Test
    void completionDerivesAveragesFromAccountRows() throws IOException {
        // 号均发量与封号数都从账号读模型推导，不靠调用方传值，避免口径分裂
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"completeDrainedTask\"");
        assertThat(sql).contains("avg_send_per_account");
        assertThat(sql).contains("invalid_account_num");
        assertThat(sql).contains("NULLIF(");
    }

    @Test
    void accountInsertBackfillsGeneratedKey() throws IOException {
        // 展开收件人需要 task_account.id，插入必须回填主键
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        assertThat(sql).contains("useGeneratedKeys=\"true\"");
        assertThat(sql).contains("keyProperty=\"id\"");
    }

    @Test
    void drainedAccountSettlementDistinguishesDoneFromFailed() throws IOException {
        // 一条都没发成功的账号收敛为 FAILED，用作 invalid_account_num 的口径
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        assertThat(sql).contains("id=\"settleDrainedAccounts\"");
        assertThat(sql).contains("'FAILED'");
        assertThat(sql).contains("'DONE'");
    }
```

在 `AccountContactMapperXmlTest.java` 追加：

```java
    @Test
    void namedContactQueryIsBoundedAndFiltersByNamedFlag() throws IOException {
        // 发送目标集口径是「通讯录里有名字」（设计 §2.8），不是双向好友
        String sql = xml("AccountContactMapper.xml");

        assertThat(sql).contains("id=\"selectNamedByAccount\"");
        assertThat(sql).contains("is_named = 1");
        assertThat(sql).contains("LIMIT #{limit}");
    }
```

> `AccountContactMapperXmlTest` 里的 `xml(...)` 辅助方法若签名不同，按该类现有写法调整调用，不要改它的既有方法。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='ContactTaskMapperXmlTest,AccountContactMapperXmlTest' -DfailIfNoTests=false`

Expected: FAIL，多个 `XML 缺少语句 id=...`

- [ ] **Step 3: 加 `ContactFriendTaskMapper` 接口方法**

在接口末尾追加（Javadoc 逐个写全）：

```java
    /**
     * 扫描到期的进行中任务。
     *
     * @param now 当前时间（epoch 毫秒）
     * @param limit 单次扫描上限
     * @return 到期任务
     */
    List<ContactFriendTask> selectDueRunningTasks(@Param("now") long now, @Param("limit") int limit);

    /**
     * 扫描已到计划开始时间、仍未开始的已启用任务。
     *
     * @param now 当前时间（epoch 毫秒）
     * @param limit 单次扫描上限
     * @return 到期待启动任务
     */
    List<ContactFriendTask> selectDueScheduledTasks(@Param("now") long now, @Param("limit") int limit);

    /**
     * 把到点的已启用未开始任务推进到进行中并排下一轮。
     *
     * @param id 任务 ID
     * @param startedAt 启动时间（epoch 毫秒）
     * @return 1 表示本次推进成功，0 表示已被并发推进
     */
    int startDueScheduledTask(@Param("id") Long id, @Param("startedAt") long startedAt);

    /**
     * 抢占一轮。并发闸门：只有一个线程能把到期任务推进到下一轮。
     *
     * @param id 任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @param nextRoundAt 下一轮时间（epoch 毫秒）
     * @return 1 表示抢占成功，0 表示已被其他线程抢走
     */
    int claimDueRound(@Param("id") Long id,
                      @Param("now") long now,
                      @Param("nextRoundAt") Long nextRoundAt);

    /**
     * 只推迟下一轮，不消耗轮次号。下游积压时使用。
     *
     * @param id 任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @param nextRoundAt 下一轮时间（epoch 毫秒）
     * @return 受影响行数
     */
    int postponeDueRound(@Param("id") Long id,
                         @Param("now") long now,
                         @Param("nextRoundAt") Long nextRoundAt);

    /**
     * 展开完成后写入计划总量与参与账号数。
     *
     * @param id 任务 ID
     * @param totalSendNum 计划发送总条数
     * @param usedAccountCount 实际参与发送的账号数
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int applyExpansionTotals(@Param("id") Long id,
                             @Param("totalSendNum") int totalSendNum,
                             @Param("usedAccountCount") int usedAccountCount,
                             @Param("updatedAt") long updatedAt);

    /**
     * 累加成功送达条数。
     *
     * @param id 任务 ID
     * @param delta 增量
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int incrementSuccessMessageNum(@Param("id") Long id,
                                   @Param("delta") int delta,
                                   @Param("updatedAt") long updatedAt);

    /**
     * 收件人全部落终态后把任务推进到已完成，并推导号均发量与无效账号数。
     *
     * @param id 任务 ID
     * @param finishedAt 完成时间（epoch 毫秒）
     * @return 1 表示本次完成，0 表示状态已变
     */
    int completeDrainedTask(@Param("id") Long id, @Param("finishedAt") long finishedAt);
```

- [ ] **Step 4: 加 `ContactFriendTaskMapper.xml` 语句**

在现有 mapper 末尾（`</mapper>` 之前）追加：

```xml
  <select id="selectDueRunningTasks" resultMap="taskMap">
    SELECT <include refid="taskColumns"/>
    FROM contact_friend_task
    WHERE deleted_at IS NULL
      AND run_status = 1
      AND next_round_at IS NOT NULL
      AND next_round_at &lt;= #{now}
    ORDER BY next_round_at
    LIMIT #{limit}
  </select>

  <select id="selectDueScheduledTasks" resultMap="taskMap">
    SELECT <include refid="taskColumns"/>
    FROM contact_friend_task
    WHERE deleted_at IS NULL
      AND is_enabled = 1
      AND run_status = 0
      AND task_start_at IS NOT NULL
      AND task_start_at &lt;= #{now}
    ORDER BY task_start_at
    LIMIT #{limit}
  </select>

  <update id="startDueScheduledTask">
    UPDATE contact_friend_task
    SET run_status = 1,
        next_round_at = #{startedAt},
        updated_at = #{startedAt}
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND is_enabled = 1
      AND run_status = 0
  </update>

  <update id="claimDueRound">
    UPDATE contact_friend_task
    SET current_round_no = current_round_no + 1,
        next_round_at = #{nextRoundAt},
        updated_at = #{now}
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND run_status = 1
      AND next_round_at IS NOT NULL
      AND next_round_at &lt;= #{now}
  </update>

  <update id="postponeDueRound">
    UPDATE contact_friend_task
    SET next_round_at = #{nextRoundAt},
        updated_at = #{now}
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND run_status = 1
  </update>

  <update id="applyExpansionTotals">
    UPDATE contact_friend_task
    SET total_send_num = #{totalSendNum},
        used_account_count = #{usedAccountCount},
        updated_at = #{updatedAt}
    WHERE id = #{id}
      AND deleted_at IS NULL
  </update>

  <update id="incrementSuccessMessageNum">
    UPDATE contact_friend_task
    SET success_message_num = success_message_num + #{delta},
        updated_at = #{updatedAt}
    WHERE id = #{id}
      AND deleted_at IS NULL
  </update>

  <!-- 号均发量与无效账号数都从账号读模型推导，不接受调用方传值，避免口径分裂 -->
  <update id="completeDrainedTask">
    UPDATE contact_friend_task t
    SET t.run_status = 2,
        t.next_round_at = NULL,
        t.invalid_account_num = (
          SELECT COUNT(*) FROM contact_friend_task_account a
          WHERE a.task_id = t.id AND a.state = 'FAILED'
        ),
        t.avg_send_per_account = COALESCE(
          ROUND(t.success_message_num / NULLIF(t.used_account_count, 0), 2), 0),
        t.updated_at = #{finishedAt}
    WHERE t.id = #{id}
      AND t.deleted_at IS NULL
      AND t.run_status = 1
  </update>
```

> `taskMap` / `taskColumns` 是 P3a 已有的 `resultMap` 与 `<sql>` 片段。如果 P3a 的 XML 里用的是别的 id，改成实际存在的那个，**不要新建一份重复的列清单**。若 P3a 没有 `taskColumns` 片段，把 `SELECT <include refid="taskColumns"/>` 换成 `SELECT *`，并在 `resultMap` 里保证 `current_round_no` 有映射（同时给 `ContactFriendTask` 实体补 `currentRoundNo` 字段与 getter/setter）。

- [ ] **Step 5: 给 `ContactFriendTask` 补 `currentRoundNo`**

在实体里加：

```java
    /** 已抢占的最新轮次号。 */
    private Long currentRoundNo;

    public Long getCurrentRoundNo() {
        return currentRoundNo;
    }

    public void setCurrentRoundNo(Long currentRoundNo) {
        this.currentRoundNo = currentRoundNo;
    }
```

并在 `ContactFriendTaskMapper.xml` 的 `resultMap` 加一行 `<result column="current_round_no" property="currentRoundNo"/>`。

- [ ] **Step 6: 加 `ContactFriendTaskAccountMapper` 接口方法与 XML**

接口追加：

```java
    /**
     * 插入任务账号行并回填主键。展开收件人需要这个 ID。
     *
     * @param row 账号行
     * @return 受影响行数
     */
    int insert(ContactFriendTaskAccount row);

    /**
     * 按主键读取任务账号行。
     *
     * @param id 账号行 ID
     * @return 账号行，不存在时为 null
     */
    ContactFriendTaskAccount selectById(@Param("id") Long id);

    /**
     * 累加该账号成功条数。
     *
     * @param id 账号行 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int incrementSentNum(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /**
     * 累加该账号失败条数。
     *
     * @param id 账号行 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int incrementFailNum(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /**
     * 把账号行推进到执行中。仅 PENDING 行会被更新。
     *
     * @param id 账号行 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int markRunning(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /**
     * 把已排干的账号行收敛为终态：发成功过至少一条为 DONE，一条都没成功为 FAILED。
     *
     * @param taskId 任务 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int settleDrainedAccounts(@Param("taskId") Long taskId, @Param("updatedAt") long updatedAt);

    /**
     * 统计任务下收敛为 FAILED 的账号数，即 invalid_account_num 的口径。
     *
     * @param taskId 任务 ID
     * @return 失败账号数
     */
    long countFailedAccounts(@Param("taskId") Long taskId);
```

XML 追加：

```xml
  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO contact_friend_task_account
      (tenant_id, task_id, account_id, account_phone_snapshot, account_status_snapshot,
       need_send_num, sent_num, fail_num, state, contact_synced_at, created_at, updated_at)
    VALUES
      (#{tenantId}, #{taskId}, #{accountId}, #{accountPhoneSnapshot}, #{accountStatusSnapshot},
       #{needSendNum}, 0, 0, #{state}, #{contactSyncedAt}, #{createdAt}, #{updatedAt})
    ON DUPLICATE KEY UPDATE
      account_phone_snapshot = VALUES(account_phone_snapshot),
      account_status_snapshot = VALUES(account_status_snapshot),
      need_send_num = VALUES(need_send_num),
      state = VALUES(state),
      contact_synced_at = VALUES(contact_synced_at),
      updated_at = VALUES(updated_at)
  </insert>

  <select id="selectById" resultMap="accountMap">
    SELECT * FROM contact_friend_task_account WHERE id = #{id}
  </select>

  <update id="incrementSentNum">
    UPDATE contact_friend_task_account
    SET sent_num = sent_num + 1, updated_at = #{updatedAt}
    WHERE id = #{id}
  </update>

  <update id="incrementFailNum">
    UPDATE contact_friend_task_account
    SET fail_num = fail_num + 1, updated_at = #{updatedAt}
    WHERE id = #{id}
  </update>

  <update id="markRunning">
    UPDATE contact_friend_task_account
    SET state = 'RUNNING', updated_at = #{updatedAt}
    WHERE id = #{id} AND state = 'PENDING'
  </update>

  <update id="settleDrainedAccounts">
    UPDATE contact_friend_task_account a
    SET a.state = IF(a.sent_num > 0, 'DONE', 'FAILED'),
        a.account_status_snapshot = IF(a.sent_num > 0, 'valid', 'invalid'),
        a.updated_at = #{updatedAt}
    WHERE a.task_id = #{taskId}
      AND a.state IN ('PENDING', 'RUNNING')
      AND NOT EXISTS (
        SELECT 1 FROM contact_friend_task_recipient r
        WHERE r.task_account_id = a.id
          AND r.send_status IN ('PENDING', 'SENDING')
      )
  </update>

  <select id="countFailedAccounts" resultType="long">
    SELECT COUNT(*) FROM contact_friend_task_account
    WHERE task_id = #{taskId} AND state = 'FAILED'
  </select>
```

> `accountMap` 是 P3a 已有的 `resultMap`；沿用，不要新建。

- [ ] **Step 7: 加 `AccountContactMapper#selectNamedByAccount`**

接口追加：

```java
    /**
     * 取本账号通讯录里有名字的联系人，用作通讯录营销的发送目标集。
     *
     * <p>口径是「通讯录里有名字」（设计 §2.8 的 {@code name_num}），
     * 不是「双向好友」——后者两套协议都还拿不到。</p>
     *
     * @param accountId 账号 ID
     * @param limit 条数上限
     * @return 联系人快照，按 id 升序
     */
    List<AccountContact> selectNamedByAccount(@Param("accountId") Long accountId,
                                              @Param("limit") int limit);
```

XML 追加：

```xml
  <select id="selectNamedByAccount" resultMap="contactMap">
    SELECT * FROM account_contact
    WHERE account_id = #{accountId}
      AND is_named = 1
    ORDER BY id
    LIMIT #{limit}
  </select>
```

> `contactMap` 是 P2 已有的 `resultMap`；沿用。

- [ ] **Step 8: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='ContactTaskMapperXmlTest,AccountContactMapperXmlTest' -DfailIfNoTests=false`

Expected: PASS

- [ ] **Step 9: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/mapper/
git add armada-api/src/main/java/com/armada/contact/task/model/entity/ContactFriendTask.java
git add armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactMapper.java
git add armada-api/src/main/resources/mapper/contact/
git add armada-api/src/main/resources/mapper/account/AccountContactMapper.xml
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskMapperXmlTest.java
git add armada-api/src/test/java/com/armada/account/contact/AccountContactMapperXmlTest.java
git commit -m "feat(contact): add send engine data access statements"
```

---
## Task 8: 任务启用时的圈号与收件人展开

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactTaskExpansionService.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskExpansionServiceTest.java`

**Interfaces:**
- Consumes:
  - `AccountFilterSelector#select(String, int)`（Task 4）
  - `AccountContactSyncService#syncIfStale(Long, ContactSyncSource)`（P2 已有）
  - `AccountContactMapper#selectNamedByAccount(Long, int)`（Task 7）
  - `ContactFriendTaskAccountMapper#insert(...)` / `ContactFriendTaskRecipientMapper#insertBatch(...)`（Task 2、7）
  - `ContactFriendTaskMapper#applyExpansionTotals(...)`（Task 7）
- Produces:
  - `ContactTaskExpansionService.ExpansionResult`（record）：`int accountCount, int recipientCount`
  - `ContactTaskExpansionService#expand(ContactFriendTask task) : ExpansionResult`

**展开算法（设计 §7.3 启用流程）**

```
1. 圈号：selector.select(task.accountFilter, task.concurrency)
     concurrency 就是「最大执行账号数」，直接当圈号上限
     命中 0 个 → 抛 BusinessException(VALIDATION, "账号范围内没有可用账号")
2. 逐账号：
   a. syncIfStale(accountId, TASK_START)
        succeeded=false 且 syncedAt=null（从来没成功过）→ 写 SKIPPED 账号行，need_send_num=0，不展开
        succeeded=false 但有历史快照 → 用历史快照继续
   b. 读 selectNamedByAccount(accountId, cap)
        cap = maxSendsPerAccount > 0 ? maxSendsPerAccount : NO_CAP_LIMIT
   c. 联系人为 0 → 写 SKIPPED 账号行，need_send_num=0
   d. 否则写 PENDING 账号行，need_send_num=联系人数，回填 id
   e. 按 EXPAND_BATCH_SIZE 分批 insertBatch 收件人（空批次不调）
3. applyExpansionTotals(taskId, Σneed_send_num, need_send_num>0 的账号数)
```

- [ ] **Step 1: 写失败测试**

`armada-api/src/test/java/com/armada/contact/task/ContactTaskExpansionServiceTest.java`：

```java
package com.armada.contact.task;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.selection.AccountFilterSelector;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.service.ContactTaskExpansionService;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务展开服务的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskExpansionServiceTest {

    @Mock
    private AccountFilterSelector selector;
    @Mock
    private AccountContactSyncService syncService;
    @Mock
    private AccountContactMapper contactMapper;
    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;

    private ContactTaskExpansionService service() {
        return new ContactTaskExpansionService(
                selector, syncService, contactMapper, taskMapper, accountMapper,
                recipientMapper, () -> 1_000L, () -> 5L);
    }

    private static ContactFriendTask task(int concurrency, int maxSendsPerAccount) {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setAccountFilter("{}");
        task.setConcurrency(concurrency);
        task.setMaxSendsPerAccount(maxSendsPerAccount);
        return task;
    }

    private static AccountContact contact(String phone) {
        AccountContact row = new AccountContact();
        row.setContactPhone(phone);
        row.setContactJid(phone + "@s.whatsapp.net");
        row.setIsNamed(1);
        return row;
    }

    private static AccountContactSyncResult fresh(int namedNum) {
        return new AccountContactSyncResult(false, true, namedNum, namedNum, 0, 900L, null);
    }

    @Test
    void rejectsEnablingWhenFilterMatchesNoAccount() {
        when(selector.select(any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service().expand(task(10, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号");
    }

    @Test
    void usesConcurrencyAsAccountSelectionLimit() {
        when(selector.select(any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service().expand(task(7, 0)))
                .isInstanceOf(BusinessException.class);

        verify(selector).select(eq("{}"), eq(7));
    }

    @Test
    void expandsNamedContactsIntoRecipients() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(11L, ContactSyncSource.TASK_START)).thenReturn(fresh(2));
        when(contactMapper.selectNamedByAccount(eq(11L), anyInt()))
                .thenReturn(List.of(contact("8613900000001"), contact("8613900000002")));
        when(accountMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ContactFriendTaskAccount.class).setId(101L);
            return 1;
        });

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.accountCount()).isEqualTo(1);
        assertThat(result.recipientCount()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContactFriendTaskRecipient>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(recipientMapper).insertBatch(captor.capture());
        List<ContactFriendTaskRecipient> rows = captor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getTaskAccountId()).isEqualTo(101L);
        assertThat(rows.get(0).getContactJid()).isEqualTo("8613900000001@s.whatsapp.net");
        assertThat(rows.get(0).getTenantId()).isEqualTo(5L);
    }

    @Test
    void appliesPerAccountSendCap() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(100));
        when(contactMapper.selectNamedByAccount(eq(11L), eq(3)))
                .thenReturn(List.of(contact("1"), contact("2"), contact("3")));
        when(accountMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ContactFriendTaskAccount.class).setId(101L);
            return 1;
        });

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 3));

        assertThat(result.recipientCount()).isEqualTo(3);
        verify(contactMapper).selectNamedByAccount(11L, 3);
    }

    @Test
    void skipsAccountWithoutAnyUsableSnapshot() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any()))
                .thenReturn(new AccountContactSyncResult(true, false, 0, 0, 0, null, "protocol down"));

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isZero();
        verify(recipientMapper, never()).insertBatch(any());

        ArgumentCaptor<ContactFriendTaskAccount> captor =
                ArgumentCaptor.forClass(ContactFriendTaskAccount.class);
        verify(accountMapper).insert(captor.capture());
        assertThat(captor.getValue().getState())
                .isEqualTo(ContactFriendTaskAccount.STATE_SKIPPED);
        assertThat(captor.getValue().getNeedSendNum()).isZero();
    }

    @Test
    void skipsAccountWithZeroNamedContacts() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(0));
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt())).thenReturn(List.of());

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.accountCount()).isZero();
        verify(recipientMapper, never()).insertBatch(any());
    }

    @Test
    void neverCallsBatchInsertWithEmptyList() {
        // 空批次 foreach 会生成空 VALUES 语法错
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(0));
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt())).thenReturn(List.of());

        service().expand(task(10, 0));

        verify(recipientMapper, never()).insertBatch(any());
    }

    @Test
    void writesTaskTotalsAfterExpansion() {
        when(selector.select(any(), anyInt())).thenReturn(List.of(
                new SelectedAccount(11L, "p1", "web", "acc_1"),
                new SelectedAccount(12L, "p2", "web", "acc_2")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(1));
        when(contactMapper.selectNamedByAccount(eq(11L), anyInt())).thenReturn(List.of(contact("1")));
        when(contactMapper.selectNamedByAccount(eq(12L), anyInt()))
                .thenReturn(List.of(contact("2"), contact("3")));
        List<Long> assigned = new ArrayList<>(List.of(101L, 102L));
        when(accountMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ContactFriendTaskAccount.class).setId(assigned.remove(0));
            return 1;
        });

        service().expand(task(10, 0));

        verify(taskMapper).applyExpansionTotals(eq(1L), eq(3), eq(2), anyLong());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskExpansionServiceTest -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: class ContactTaskExpansionService`

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/contact/task/service/ContactTaskExpansionService.java`：

```java
package com.armada.contact.task.service;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.selection.AccountFilterSelector;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 通讯录营销任务启用时的圈号与收件人展开。
 *
 * <p>展开是**一次性**的：启用时把每个命中账号当前通讯录里有名字的联系人固化成
 * {@code contact_friend_task_recipient} 快照，之后的每一轮只是把 PENDING 排干。
 * 通讯录后续变化不回灌已展开的任务——任务事实不跟着主数据漂（超链一期 §6.6）。</p>
 *
 * <p><b>本类刻意不标注 {@code @Service}</b>：构造参数含 Supplier，由
 * {@code ContactTaskConfiguration} 显式构造，以便纯 Mockito 测试。</p>
 */
public class ContactTaskExpansionService {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskExpansionService.class);

    /** {@code max_sends_per_account = 0} 表示不截断，仍需一个物理上限兜底。 */
    static final int NO_CAP_LIMIT = 100_000;

    /** 单次收件人批量插入条数。 */
    static final int EXPAND_BATCH_SIZE = 500;

    private final AccountFilterSelector selector;
    private final AccountContactSyncService syncService;
    private final AccountContactMapper contactMapper;
    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final LongSupplier clock;
    private final Supplier<Long> tenantSupplier;

    /**
     * 创建展开服务。
     *
     * @param selector 账号圈选服务
     * @param syncService 通讯录采集服务
     * @param contactMapper 通讯录快照数据访问
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param clock 当前时间提供者（epoch 毫秒）
     * @param tenantSupplier 当前租户提供者
     */
    public ContactTaskExpansionService(AccountFilterSelector selector,
                                       AccountContactSyncService syncService,
                                       AccountContactMapper contactMapper,
                                       ContactFriendTaskMapper taskMapper,
                                       ContactFriendTaskAccountMapper accountMapper,
                                       ContactFriendTaskRecipientMapper recipientMapper,
                                       LongSupplier clock,
                                       Supplier<Long> tenantSupplier) {
        this.selector = selector;
        this.syncService = syncService;
        this.contactMapper = contactMapper;
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.clock = clock;
        this.tenantSupplier = tenantSupplier;
    }

    /**
     * 展开一个任务的账号与收件人。
     *
     * @param task 已通过表单校验的任务
     * @return 展开结果
     * @throws BusinessException 筛选条件命中 0 个可用账号时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ExpansionResult expand(ContactFriendTask task) {
        int accountLimit = task.getConcurrency() == null || task.getConcurrency() < 1
                ? 1
                : task.getConcurrency();
        List<SelectedAccount> accounts = selector.select(task.getAccountFilter(), accountLimit);
        if (accounts.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "账号范围内没有可用账号，无法启用任务");
        }
        long now = clock.getAsLong();
        int usedAccountCount = 0;
        int totalSendNum = 0;
        for (SelectedAccount account : accounts) {
            int expanded = expandOneAccount(task, account, now);
            if (expanded > 0) {
                usedAccountCount++;
                totalSendNum += expanded;
            }
        }
        taskMapper.applyExpansionTotals(task.getId(), totalSendNum, usedAccountCount, now);
        log.info("通讯录任务展开完成 tenantId={} taskId={} selectedAccounts={} usedAccounts={} recipients={}",
                task.getTenantId(), task.getId(), accounts.size(), usedAccountCount, totalSendNum);
        return new ExpansionResult(usedAccountCount, totalSendNum);
    }

    /** 展开单个账号，返回该账号实际展开的收件人条数；跳过时返回 0。 */
    private int expandOneAccount(ContactFriendTask task, SelectedAccount account, long now) {
        AccountContactSyncResult sync =
                syncService.syncIfStale(account.accountId(), ContactSyncSource.TASK_START);
        if (!sync.succeeded() && sync.syncedAt() == null) {
            // 从来没成功同步过，没有任何可用快照，只能跳过
            insertAccountRow(task, account, 0, null,
                    ContactFriendTaskAccount.STATE_SKIPPED, now);
            log.warn("通讯录任务跳过无快照账号 taskId={} accountId={} reason={}",
                    task.getId(), account.accountId(), sync.failReason());
            return 0;
        }
        int cap = task.getMaxSendsPerAccount() == null || task.getMaxSendsPerAccount() <= 0
                ? NO_CAP_LIMIT
                : task.getMaxSendsPerAccount();
        List<AccountContact> contacts =
                contactMapper.selectNamedByAccount(account.accountId(), cap);
        if (contacts == null || contacts.isEmpty()) {
            insertAccountRow(task, account, 0, sync.syncedAt(),
                    ContactFriendTaskAccount.STATE_SKIPPED, now);
            return 0;
        }
        ContactFriendTaskAccount accountRow = insertAccountRow(
                task, account, contacts.size(), sync.syncedAt(),
                ContactFriendTaskAccount.STATE_PENDING, now);
        insertRecipients(task, accountRow.getId(), contacts, now);
        return contacts.size();
    }

    private ContactFriendTaskAccount insertAccountRow(ContactFriendTask task,
                                                      SelectedAccount account,
                                                      int needSendNum,
                                                      Long contactSyncedAt,
                                                      String state,
                                                      long now) {
        ContactFriendTaskAccount row = new ContactFriendTaskAccount();
        row.setTenantId(tenantSupplier.get());
        row.setTaskId(task.getId());
        row.setAccountId(account.accountId());
        row.setAccountPhoneSnapshot(account.wsPhone());
        row.setAccountStatusSnapshot(needSendNum > 0 ? "valid" : "invalid");
        row.setNeedSendNum(needSendNum);
        row.setState(state);
        row.setContactSyncedAt(contactSyncedAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        accountMapper.insert(row);
        return row;
    }

    /** 分批插入收件人；空批次绝不下发（{@code foreach} 会生成空 VALUES 语法错）。 */
    private void insertRecipients(ContactFriendTask task,
                                  Long taskAccountId,
                                  List<AccountContact> contacts,
                                  long now) {
        List<ContactFriendTaskRecipient> batch = new ArrayList<>(EXPAND_BATCH_SIZE);
        for (AccountContact contact : contacts) {
            ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
            row.setTenantId(tenantSupplier.get());
            row.setTaskId(task.getId());
            row.setTaskAccountId(taskAccountId);
            row.setContactPhone(contact.getContactPhone());
            row.setContactJid(contact.getContactJid());
            row.setContactNamed(contact.getIsNamed() == null ? 0 : contact.getIsNamed());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            batch.add(row);
            if (batch.size() == EXPAND_BATCH_SIZE) {
                recipientMapper.insertBatch(batch);
                batch = new ArrayList<>(EXPAND_BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            recipientMapper.insertBatch(batch);
        }
    }

    /**
     * 展开结果。
     *
     * @param accountCount 实际参与发送的账号数（need_send_num &gt; 0）
     * @param recipientCount 展开出的收件人总条数
     */
    public record ExpansionResult(int accountCount, int recipientCount) {
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskExpansionServiceTest -DfailIfNoTests=false`

Expected: PASS（Tests run: 8）

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/service/ContactTaskExpansionService.java
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskExpansionServiceTest.java
git commit -m "feat(contact): expand task accounts and recipients on enable"
```

---

## Task 9: 把展开接进任务启用路径

**Files:**
- Modify: `armada-api/src/main/java/com/armada/contact/task/service/impl/ContactTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/contact/task/config/ContactTaskConfiguration.java`
- Modify: `armada-api/src/test/java/com/armada/contact/task/ContactTaskServiceImplTest.java`

**Interfaces:**
- Consumes: `ContactTaskExpansionService#expand(ContactFriendTask)`（Task 8）
- Produces: `ContactTaskServiceImpl` 构造函数新增第 7 个参数 `ContactTaskExpansionService expansionService`（插在 `filterNormalizer` 之后、`tenantSupplier` 之前）

**生命周期口径（设计 §7.2）**

```
create(is_enabled=0)  → run_status=0，不展开
create(is_enabled=1)  → 先落库拿到 id，再展开，run_status 仍为 0（等 start 或计划时间）
update(is_enabled 0→1) → 展开
update(is_enabled 1→1) → 不重复展开（幂等键会挡，但也不该白跑一趟圈号）
action=start          → run_status 0→1，next_round_at=now
```

> **执行提示**：改构造签名后必须同时 `grep -rn "new ContactTaskServiceImpl" armada-api/src` **和** `grep -rn "@InjectMocks" armada-api/src/test/java/com/armada/contact`。P2 就是漏了后者才挂了 3 个既有测试。

- [ ] **Step 1: 写失败测试**

在 `ContactTaskServiceImplTest.java` 追加（并把该类里已有的 `new ContactTaskServiceImpl(...)` 构造处统一补上新参数）：

```java
    @Test
    void expandsWhenCreatedAlreadyEnabled() {
        ContactTaskFormDTO form = enabledForm();
        when(validator.validate(form)).thenReturn(form);
        when(filterNormalizer.normalize(any())).thenReturn("{}");
        when(taskMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ContactFriendTask.class).setId(42L);
            return 1;
        });

        service().create(form, 9L);

        ArgumentCaptor<ContactFriendTask> captor = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(expansionService).expand(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
    }

    @Test
    void doesNotExpandWhenCreatedAsDraft() {
        ContactTaskFormDTO form = draftForm();
        when(validator.validate(form)).thenReturn(form);
        when(filterNormalizer.normalize(any())).thenReturn("{}");
        when(taskMapper.insert(any())).thenReturn(1);

        service().create(form, 9L);

        verify(expansionService, never()).expand(any());
    }

    @Test
    void expandsWhenDraftIsSwitchedOn() {
        ContactFriendTask existing = notStartedTask();
        existing.setIsEnabled(0);
        when(taskMapper.selectById(1L)).thenReturn(existing);
        ContactTaskFormDTO form = enabledForm();
        when(validator.validate(form)).thenReturn(form);
        when(filterNormalizer.normalize(any())).thenReturn("{}");

        service().update(1L, form);

        verify(expansionService).expand(existing);
    }

    @Test
    void doesNotReExpandAlreadyEnabledTask() {
        ContactFriendTask existing = notStartedTask();
        existing.setIsEnabled(1);
        when(taskMapper.selectById(1L)).thenReturn(existing);
        ContactTaskFormDTO form = enabledForm();
        when(validator.validate(form)).thenReturn(form);
        when(filterNormalizer.normalize(any())).thenReturn("{}");

        service().update(1L, form);

        verify(expansionService, never()).expand(any());
    }
```

> `enabledForm()` / `draftForm()` / `notStartedTask()` / `service()` 若测试类里还没有，按该类现有 fixture 写法补齐；`isEnabled` 分别为 1 和 0。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskServiceImplTest -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: variable expansionService`

- [ ] **Step 3: 改 `ContactTaskServiceImpl`**

构造函数与字段加入 `ContactTaskExpansionService expansionService`。

`create` 在 `taskMapper.insert(row)` 之后追加：

```java
        if (isEnabled(row)) {
            expansionService.expand(row);
        }
```

`update` 在 `taskMapper.updateForm(existing)` 之前记录旧值、之后按需展开：

```java
        boolean wasEnabled = isEnabled(existing);
        // applyForm 会覆盖 isEnabled，所以旧值必须在覆盖前取
        applyForm(existing, normalized, filterNormalizer.normalize(normalized.accountFilterJson()), now);
        taskMapper.updateForm(existing);
        if (!wasEnabled && isEnabled(existing)) {
            expansionService.expand(existing);
        }
```

追加私有工具：

```java
    /** 只有 is_enabled=1 才算启用；null 与 0 都是草稿。 */
    private static boolean isEnabled(ContactFriendTask row) {
        return row.getIsEnabled() != null && row.getIsEnabled() == 1;
    }
```

- [ ] **Step 4: 改 `ContactTaskConfiguration`**

新增展开服务的 bean 并注入任务服务：

```java
    /**
     * 装配通讯录任务展开服务。
     *
     * @param selector 账号圈选服务
     * @param syncService 通讯录采集服务
     * @param contactMapper 通讯录快照数据访问
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @return 展开服务
     */
    @Bean
    public ContactTaskExpansionService contactTaskExpansionService(
            AccountFilterSelector selector,
            AccountContactSyncService syncService,
            AccountContactMapper contactMapper,
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactFriendTaskRecipientMapper recipientMapper) {
        return new ContactTaskExpansionService(
                selector, syncService, contactMapper, taskMapper,
                accountMapper, recipientMapper,
                System::currentTimeMillis, TenantContext::get);
    }
```

并把 `contactTaskService(...)` 的参数与构造调用同步加上 `ContactTaskExpansionService expansionService`。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='ContactTask*Test' -DfailIfNoTests=false`

Expected: PASS

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/service/impl/ContactTaskServiceImpl.java
git add armada-api/src/main/java/com/armada/contact/task/config/ContactTaskConfiguration.java
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskServiceImplTest.java
git commit -m "feat(contact): expand recipients when task is enabled"
```

---

## Task 10: 通讯录消息命令组装

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactTaskMessageCommandFactory.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskMessageCommandFactoryTest.java`

**Interfaces:**
- Consumes:
  - `MessageSendCommand.ContactTaskCorrelation`（Task 6）
  - `ContactSendIntervalPicker#pickMs(...)`（Task 5）
  - `MarketingTemplateFileMapper#selectById(Long)`（既有）
- Produces:
  - `ContactTaskMessageCommandFactory#composeContent(ContactFriendTask task) : ComposedContactMessage`
  - `ContactTaskMessageCommandFactory#toCommand(ContactFriendTask task, ContactFriendTaskAccount accountRow, ContactFriendTaskRecipient recipient, SelectedAccount protocolFacts, ComposedContactMessage content, long roundNo, long notBeforeAt, Random random) : MessageSendCommand`
  - `ContactTaskMessageCommandFactory#newCommandId() : String`（`cmd_` 前缀，与营销侧同格式）
  - `ComposedContactMessage`（record）：`MessageType type, String text, byte[] imageBytes, String imageMimetype, String linkUrl, String linkTitle, String linkDescription`

**消息形态映射（设计 §2.3，只有两种，没有按钮）**

| `message_type` | 协议 `MessageType` | 内容 |
|---|---|---|
| 0 链接消息 | `LINK_CARD` | `text=content`，卡片 `url=promotion_link`、`title=title`、`description=description`、缩略图取 `preview_image_file_id` |
| 1 图文消息 + 有图 | `IMAGE` | 图片字节 + `text=content` 作 caption |
| 1 图文消息 + 无图 | `TEXT` | `text=content` |

- [ ] **Step 1: 写失败测试**

`armada-api/src/test/java/com/armada/contact/task/ContactTaskMessageCommandFactoryTest.java`：

```java
package com.armada.contact.task;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 通讯录消息命令组装的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskMessageCommandFactoryTest {

    @Mock
    private MarketingTemplateFileMapper fileMapper;

    private ContactTaskMessageCommandFactory factory() {
        return new ContactTaskMessageCommandFactory(fileMapper);
    }

    private static ContactFriendTask linkTask() {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setMessageType(0);
        task.setTitle("限时优惠");
        task.setDescription("点开看看");
        task.setPromotionLink("https://example.com/promo");
        task.setContent("正文");
        task.setMsgIntervalMinSec(new BigDecimal("1.0"));
        task.setMsgIntervalMaxSec(new BigDecimal("1.0"));
        return task;
    }

    private static ContactFriendTask imageTask() {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setMessageType(1);
        task.setContent("图文文案");
        task.setMsgIntervalMinSec(new BigDecimal("0.5"));
        task.setMsgIntervalMaxSec(new BigDecimal("0.5"));
        return task;
    }

    private static ContactFriendTaskAccount accountRow() {
        ContactFriendTaskAccount row = new ContactFriendTaskAccount();
        row.setId(101L);
        row.setAccountId(11L);
        return row;
    }

    private static ContactFriendTaskRecipient recipient() {
        ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
        row.setId(999L);
        row.setContactPhone("8613900000001");
        row.setContactJid("8613900000001@s.whatsapp.net");
        return row;
    }

    private static SelectedAccount protocolFacts() {
        return new SelectedAccount(11L, "8613800000000", "web", "acc_8613800000000");
    }

    @Test
    void composesLinkCardForLinkMessageType() {
        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                factory().composeContent(linkTask());

        assertThat(content.type()).isEqualTo(MessageType.LINK_CARD);
        assertThat(content.linkUrl()).isEqualTo("https://example.com/promo");
        assertThat(content.linkTitle()).isEqualTo("限时优惠");
        assertThat(content.linkDescription()).isEqualTo("点开看看");
        assertThat(content.text()).isEqualTo("正文");
    }

    @Test
    void composesImageWhenPictureMessageHasFile() {
        ContactFriendTask task = imageTask();
        task.setPreviewImageFileId(77L);
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setContent(new byte[]{1, 2, 3});
        file.setMimetype("image/png");
        when(fileMapper.selectById(77L)).thenReturn(file);

        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                factory().composeContent(task);

        assertThat(content.type()).isEqualTo(MessageType.IMAGE);
        assertThat(content.imageBytes()).containsExactly(1, 2, 3);
        assertThat(content.imageMimetype()).isEqualTo("image/png");
        assertThat(content.text()).isEqualTo("图文文案");
    }

    @Test
    void fallsBackToTextWhenPictureMessageHasNoFile() {
        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                factory().composeContent(imageTask());

        assertThat(content.type()).isEqualTo(MessageType.TEXT);
        assertThat(content.imageBytes()).isNull();
        assertThat(content.text()).isEqualTo("图文文案");
    }

    @Test
    void neverProducesButtonCard() {
        // 竞品的通讯录消息没有按钮（设计 §2.3），任何 message_type 都不该出现 BUTTON_CARD
        assertThat(factory().composeContent(linkTask()).type()).isNotEqualTo(MessageType.BUTTON_CARD);
        assertThat(factory().composeContent(imageTask()).type()).isNotEqualTo(MessageType.BUTTON_CARD);
    }

    @Test
    void targetsPeerJidNotGroupJid() {
        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), recipient(), protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));

        assertThat(command.target().jid()).isEqualTo("8613900000001@s.whatsapp.net");
    }

    @Test
    void carriesAllFourContactCorrelationFields() {
        // 缺任一协议层就判 invalid message send payload 丢弃
        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), recipient(), protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));

        MessageSendCommand.ContactTaskCorrelation correlation = command.correlation().contactTask();
        assertThat(correlation.taskId()).isEqualTo(1L);
        assertThat(correlation.taskAccountId()).isEqualTo(101L);
        assertThat(correlation.recipientId()).isEqualTo(999L);
        assertThat(correlation.roundNo()).isEqualTo(5L);
        assertThat(command.correlation().source()).isEqualTo("contact_task");
        assertThat(command.correlation().marketing()).isNull();
    }

    @Test
    void resolvesProtocolBackendFromAccountProtocolId() {
        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), recipient(), protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));

        assertThat(command.account().backend()).isEqualTo(ProtocolBackend.WEB);
        assertThat(command.account().protocolAccountId()).isEqualTo("acc_8613800000000");
        assertThat(command.account().wsPhone()).isEqualTo("8613800000000");
    }

    @Test
    void picksSendIntervalInsideConfiguredRange() {
        ContactFriendTask task = linkTask();
        task.setMsgIntervalMinSec(new BigDecimal("0.5"));
        task.setMsgIntervalMaxSec(new BigDecimal("3.0"));

        MessageSendCommand command = factory().toCommand(
                task, accountRow(), recipient(), protocolFacts(),
                factory().composeContent(task), 5L, 0L, new Random(42L));

        assertThat(command.sendIntervalMs()).isBetween(500, 3000);
    }

    @Test
    void generatesDistinctCommandIdsWithMarketingPrefix() {
        ContactTaskMessageCommandFactory factory = factory();

        String first = factory.newCommandId();
        String second = factory.newCommandId();

        assertThat(first).startsWith("cmd_");
        assertThat(first).isNotEqualTo(second);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskMessageCommandFactoryTest -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: class ContactTaskMessageCommandFactory`

> **先确认字段名**：`MarketingTemplateFile` 的字节列与 MIME 列在本仓库里叫什么，跑一次
> `sed -n '1,40p' armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTemplateFile.java`，
> 上面测试里的 `setContent` / `setMimetype` 按实际 getter/setter 名改，**不要新增字段**。

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/contact/task/service/ContactTaskMessageCommandFactory.java`：

```java
package com.armada.contact.task.service;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * 通讯录营销消息内容与协议无关发送命令的组装器。
 *
 * <p>竞品的通讯录消息只有两种形态且**没有按钮**（设计 §2.3）：
 * {@code message_type=0} 链接消息落 {@code LINK_CARD}；{@code message_type=1} 图文消息
 * 有图落 {@code IMAGE}（正文作 caption），无图退化为 {@code TEXT}。</p>
 */
@Component
public class ContactTaskMessageCommandFactory {

    /** 协议层识别通讯录任务命令的来源常量，逐字固定。 */
    public static final String SOURCE_CONTACT_TASK = "contact_task";

    private static final int MESSAGE_TYPE_LINK = 0;

    private final MarketingTemplateFileMapper fileMapper;

    /**
     * 创建组装器。
     *
     * @param fileMapper 营销模板图片数据访问（图片沿用既有字节存储，不新建表）
     */
    public ContactTaskMessageCommandFactory(MarketingTemplateFileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    /**
     * 从任务组合一条可发送内容。整轮只组一次，逐条复用。
     *
     * @param task 通讯录营销任务
     * @return 已组合内容
     */
    public ComposedContactMessage composeContent(ContactFriendTask task) {
        MarketingTemplateFile file = task.getPreviewImageFileId() == null
                ? null
                : fileMapper.selectById(task.getPreviewImageFileId());
        byte[] bytes = file == null ? null : file.getContent();
        String mimetype = file == null ? null : file.getMimetype();
        boolean hasImage = bytes != null && bytes.length > 0;
        if (Integer.valueOf(MESSAGE_TYPE_LINK).equals(task.getMessageType())) {
            return new ComposedContactMessage(
                    MessageType.LINK_CARD,
                    task.getContent(),
                    hasImage ? bytes : null,
                    hasImage ? mimetype : null,
                    task.getPromotionLink(),
                    task.getTitle(),
                    task.getDescription());
        }
        return new ComposedContactMessage(
                hasImage ? MessageType.IMAGE : MessageType.TEXT,
                task.getContent(),
                hasImage ? bytes : null,
                hasImage ? mimetype : null,
                null, null, null);
    }

    /**
     * 组装单条协议无关发送命令。
     *
     * @param task 通讯录营销任务
     * @param accountRow 任务账号行
     * @param recipient 收件人明细
     * @param protocolFacts 账号协议事实
     * @param content 已组合内容
     * @param roundNo 本轮轮次号
     * @param notBeforeAt Armada 内部最早投递时间（epoch 毫秒），0 表示立即
     * @param random 随机源，用于逐条取发送间隔
     * @return 协议无关消息命令
     */
    public MessageSendCommand toCommand(ContactFriendTask task,
                                        ContactFriendTaskAccount accountRow,
                                        ContactFriendTaskRecipient recipient,
                                        SelectedAccount protocolFacts,
                                        ComposedContactMessage content,
                                        long roundNo,
                                        long notBeforeAt,
                                        Random random) {
        return new MessageSendCommand(
                new ProtocolAccountRef(
                        protocolFacts.accountId(),
                        ProtocolBackend.fromProtocolId(protocolFacts.protocolId()),
                        protocolFacts.protocolAccountId(),
                        protocolFacts.wsPhone()),
                new MessageSendCommand.MessageTarget(recipient.getContactJid()),
                payload(content),
                new MessageSendCommand.MessageCorrelation(
                        task.getTenantId(),
                        SOURCE_CONTACT_TASK,
                        null,
                        null,
                        null,
                        new MessageSendCommand.ContactTaskCorrelation(
                                task.getId(), accountRow.getId(), recipient.getId(), roundNo)),
                recipient.getCommandId() == null ? newCommandId() : recipient.getCommandId(),
                ContactSendIntervalPicker.pickMs(
                        task.getMsgIntervalMinSec(), task.getMsgIntervalMaxSec(), random),
                notBeforeAt);
    }

    /**
     * 生成与协议 outbox 共用的全局命令 ID，格式与营销侧一致。
     *
     * @return 以 {@code cmd_} 开头的命令 ID
     */
    public String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static MessageSendCommand.MessagePayload payload(ComposedContactMessage content) {
        MessageSendCommand.MessageMedia image = content.imageBytes() == null
                ? null
                : new MessageSendCommand.MessageMedia(
                        content.imageBytes(), content.imageMimetype());
        MessageSendCommand.MessageLinkCard linkCard = content.type() == MessageType.LINK_CARD
                ? new MessageSendCommand.MessageLinkCard(
                        content.linkUrl(), content.linkTitle(), content.linkDescription(), image)
                : null;
        return new MessageSendCommand.MessagePayload(
                content.type(),
                new MessageSendCommand.MessageContent(
                        content.text(),
                        content.type() == MessageType.IMAGE ? image : null,
                        linkCard,
                        // 通讯录消息没有按钮，这一位永远是 null
                        null),
                // 私聊没有群成员，提醒所有人无意义
                false);
    }

    /**
     * 一条已组合好的通讯录消息内容。
     *
     * @param type 协议消息类型
     * @param text 正文或 caption
     * @param imageBytes 图片字节，无图为 null
     * @param imageMimetype 图片 MIME，无图为 null
     * @param linkUrl 推广链接，仅链接消息
     * @param linkTitle 卡片标题，仅链接消息
     * @param linkDescription 卡片描述，仅链接消息
     */
    public record ComposedContactMessage(
            MessageType type,
            String text,
            byte[] imageBytes,
            String imageMimetype,
            String linkUrl,
            String linkTitle,
            String linkDescription
    ) {
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=ContactTaskMessageCommandFactoryTest -DfailIfNoTests=false`

Expected: PASS（Tests run: 9）

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/service/ContactTaskMessageCommandFactory.java
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskMessageCommandFactoryTest.java
git commit -m "feat(contact): assemble contact task send commands"
```

---
## Task 11: 轮次调度三件套

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskSchedulerProperties.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskSchedulerConfiguration.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskLifecycleWorker.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskRoundWorker.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskRoundScheduler.java`
- Modify: `armada-api/src/main/java/com/armada/account/selection/mapper/AccountFilterSelectionMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountFilterSelectionMapper.xml`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskRoundWorkerTest.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskLifecycleWorkerTest.java`

**Interfaces:**
- Consumes: Task 2 / 7 的全部 mapper 语句、Task 10 的 `ContactTaskMessageCommandFactory`、既有 `MessageSendPort`
- Produces:
  - `AccountFilterSelectionMapper#selectSendableByIds(List<Long> accountIds, int normalAccountState, int exportedAccountState) : List<SelectedAccount>`
  - `ContactTaskSchedulerProperties`（`armada.contact.round-scheduler` 前缀）：`enabled`(true) / `scanFixedDelayMs`(1000) / `executorPoolSize`(5) / `scanLimit`(20) / `recipientsPerAccountPerRound`(20) / `outboxBatchSize`(200) / `backlogMultiplier`(2)
  - `ContactTaskLifecycleWorker#startDueScheduledTask(Long tenantId, Long taskId) : void`
  - `ContactTaskLifecycleWorker#completeDrainedTask(Long tenantId, Long taskId) : void`
  - `ContactTaskRoundWorker#runRound(Long tenantId, Long taskId) : void`
  - `ContactTaskRoundScheduler#scanDueTasks() : void`（`@Scheduled`）

**一轮的固定顺序（照 `MarketingRoundWorker#doRunRound` 的关闸顺序）**

```
1. 读任务，run_status != 1 直接返回
2. 未到 task_start_at → postponeDueRound 返回（历史数据或并发误置的兜底）
3. 取有 PENDING 收件人的账号（上限 = task.concurrency）
     空 且 countUnfinished == 0 → 交 lifecycleWorker 收尾完成，返回
     空 但 countUnfinished > 0  → 只 postpone（在途还没回执），返回
4. 积压闸门：countInFlight >= backlogMultiplier × 本轮计划条数 → postpone 返回
5. claimDueRound 抢轮次；抢不到直接返回（并发闸门）
6. roundNo = current_round_no + 1
7. 组一次消息内容，批量读账号协议事实（读不到的账号本轮跳过，不消耗收件人）
8. 逐账号 markRunning → 取 PENDING 批 → 逐条 claimForSend（抢不到的跳过）
     → 组命令（间隔逐条随机、notBeforeAt 按账号内位次错开）
9. 按 outboxBatchSize 分批 enqueue；本地拒绝的立刻 markFailed + incrementFailNum
```

- [ ] **Step 1: 写失败测试（RoundWorker）**

`armada-api/src/test/java/com/armada/contact/task/ContactTaskRoundWorkerTest.java`：

```java
package com.armada.contact.task;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.scheduler.ContactTaskRoundWorker;
import com.armada.contact.task.scheduler.ContactTaskSchedulerProperties;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务轮次执行的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskRoundWorkerTest {

    private static final long NOW = 1_700_000_000_000L;

    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;
    @Mock
    private AccountFilterSelectionMapper selectionMapper;
    @Mock
    private MessageSendPort messageSendPort;
    @Mock
    private MarketingTemplateFileMapper fileMapper;
    @Mock
    private ContactTaskLifecycleWorkerStub lifecycleWorker;

    /** 只为在本测试内表达「收尾被调用」的协作意图，实现见 Step 3 的真实类。 */
    interface ContactTaskLifecycleWorkerStub {
        void completeDrainedTask(Long tenantId, Long taskId);
    }

    private ContactTaskRoundWorker worker() {
        return new ContactTaskRoundWorker(
                taskMapper, accountMapper, recipientMapper, selectionMapper,
                new ContactTaskMessageCommandFactory(fileMapper),
                messageSendPort,
                new ContactTaskSchedulerProperties(),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                new Random(1L),
                (tenantId, taskId) -> lifecycleWorker.completeDrainedTask(tenantId, taskId));
    }

    private static ContactFriendTask runningTask() {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setRunStatus(1);
        task.setCurrentRoundNo(4L);
        task.setConcurrency(2);
        task.setMessageType(1);
        task.setContent("文案");
        task.setMsgIntervalMinSec(new BigDecimal("1.0"));
        task.setMsgIntervalMaxSec(new BigDecimal("1.0"));
        return task;
    }

    private static ContactFriendTaskAccount accountRow(Long id, Long accountId) {
        ContactFriendTaskAccount row = new ContactFriendTaskAccount();
        row.setId(id);
        row.setTaskId(1L);
        row.setAccountId(accountId);
        row.setState(ContactFriendTaskAccount.STATE_PENDING);
        return row;
    }

    private static ContactFriendTaskRecipient recipient(Long id, String phone) {
        ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
        row.setId(id);
        row.setTaskId(1L);
        row.setTaskAccountId(101L);
        row.setContactPhone(phone);
        row.setContactJid(phone + "@s.whatsapp.net");
        row.setSendStatus("PENDING");
        return row;
    }

    private void givenOneAccountWithOneRecipient() {
        when(taskMapper.selectById(1L)).thenReturn(runningTask());
        when(recipientMapper.selectAccountIdsWithPending(eq(1L), anyInt())).thenReturn(List.of(101L));
        when(accountMapper.selectById(101L)).thenReturn(accountRow(101L, 11L));
        when(selectionMapper.selectSendableByIds(any(), anyInt(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(recipientMapper.selectPendingByAccount(eq(101L), anyInt()))
                .thenReturn(List.of(recipient(999L, "8613900000001")));
        when(recipientMapper.claimForSend(eq(999L), anyLong(), anyString(), anyLong())).thenReturn(1);
        when(taskMapper.claimDueRound(eq(1L), anyLong(), anyLong())).thenReturn(1);
    }

    @Test
    void skipsTaskThatIsNoLongerRunning() {
        ContactFriendTask paused = runningTask();
        paused.setRunStatus(3);
        when(taskMapper.selectById(1L)).thenReturn(paused);

        worker().runRound(5L, 1L);

        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void skipsMissingTask() {
        when(taskMapper.selectById(1L)).thenReturn(null);

        worker().runRound(5L, 1L);

        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void postponesWhenScheduledStartTimeHasNotArrived() {
        ContactFriendTask task = runningTask();
        task.setTaskStartAt(NOW + 60_000L);
        when(taskMapper.selectById(1L)).thenReturn(task);

        worker().runRound(5L, 1L);

        verify(taskMapper).postponeDueRound(eq(1L), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void completesTaskWhenNothingLeftToSend() {
        when(taskMapper.selectById(1L)).thenReturn(runningTask());
        when(recipientMapper.selectAccountIdsWithPending(eq(1L), anyInt())).thenReturn(List.of());
        when(recipientMapper.countUnfinished(1L)).thenReturn(0L);

        worker().runRound(5L, 1L);

        verify(lifecycleWorker).completeDrainedTask(5L, 1L);
        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void onlyPostponesWhileSendsAreStillInFlight() {
        when(taskMapper.selectById(1L)).thenReturn(runningTask());
        when(recipientMapper.selectAccountIdsWithPending(eq(1L), anyInt())).thenReturn(List.of());
        when(recipientMapper.countUnfinished(1L)).thenReturn(3L);

        worker().runRound(5L, 1L);

        verify(lifecycleWorker, never()).completeDrainedTask(anyLong(), anyLong());
        verify(taskMapper).postponeDueRound(eq(1L), anyLong(), anyLong());
    }

    @Test
    void postponesWithoutClaimingRoundWhenBacklogIsHigh() {
        givenOneAccountWithOneRecipient();
        // 计划 1 账号 × 20 条 = 20；backlogMultiplier 默认 2 → 阈值 40
        when(recipientMapper.countInFlight(1L)).thenReturn(100L);

        worker().runRound(5L, 1L);

        verify(taskMapper).postponeDueRound(eq(1L), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void abortsWhenAnotherThreadClaimedTheRound() {
        givenOneAccountWithOneRecipient();
        when(taskMapper.claimDueRound(eq(1L), anyLong(), anyLong())).thenReturn(0);

        worker().runRound(5L, 1L);

        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void enqueuesCommandCarryingIncrementedRoundNumber() {
        givenOneAccountWithOneRecipient();
        when(messageSendPort.enqueue(any())).thenAnswer(invocation -> {
            List<MessageSendCommand> commands = invocation.getArgument(0);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                    .toList());
        });

        worker().runRound(5L, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort).enqueue(captor.capture());
        MessageSendCommand command = captor.getValue().get(0);
        assertThat(command.correlation().contactTask().roundNo()).isEqualTo(5L);
        assertThat(command.correlation().contactTask().recipientId()).isEqualTo(999L);
        assertThat(command.correlation().contactTask().taskAccountId()).isEqualTo(101L);
        assertThat(command.target().jid()).isEqualTo("8613900000001@s.whatsapp.net");
    }

    @Test
    void skipsRecipientLostToAConcurrentRound() {
        givenOneAccountWithOneRecipient();
        when(recipientMapper.claimForSend(eq(999L), anyLong(), anyString(), anyLong())).thenReturn(0);

        worker().runRound(5L, 1L);

        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void skipsAccountWithoutCurrentProtocolFacts() {
        // 圈号后账号被封或导出，本轮读不到协议事实，不能白白消耗收件人
        givenOneAccountWithOneRecipient();
        when(selectionMapper.selectSendableByIds(any(), anyInt(), anyInt())).thenReturn(List.of());

        worker().runRound(5L, 1L);

        verify(recipientMapper, never()).claimForSend(anyLong(), anyLong(), anyString(), anyLong());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void convertsLocalRejectionIntoRecipientFailure() {
        givenOneAccountWithOneRecipient();
        when(messageSendPort.enqueue(any())).thenAnswer(invocation -> {
            List<MessageSendCommand> commands = invocation.getArgument(0);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.rejected(
                            command.commandId(), "INVALID_ANDROID_BUTTON_CONFIG", "本地拒绝"))
                    .toList());
        });

        worker().runRound(5L, 1L);

        verify(recipientMapper).markFailed(eq(999L), eq("INVALID_ANDROID_BUTTON_CONFIG"),
                anyString(), anyLong());
        verify(accountMapper).incrementFailNum(eq(101L), anyLong());
    }

    @Test
    void marksAccountRunningBeforeSending() {
        givenOneAccountWithOneRecipient();
        when(messageSendPort.enqueue(any())).thenAnswer(invocation -> {
            List<MessageSendCommand> commands = invocation.getArgument(0);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                    .toList());
        });

        worker().runRound(5L, 1L);

        verify(accountMapper).markRunning(eq(101L), anyLong());
    }
}
```

> `MessageSendEnqueueItem.rejected(...)` 的实参个数按仓库里实际签名调整（`sed -n '1,60p' armada-api/src/main/java/com/armada/platform/protocol/model/result/MessageSendEnqueueItem.java`）。

- [ ] **Step 2: 写失败测试（LifecycleWorker）**

`armada-api/src/test/java/com/armada/contact/task/ContactTaskLifecycleWorkerTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.scheduler.ContactTaskLifecycleWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务生命周期推进的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskLifecycleWorkerTest {

    private static final long NOW = 1_700_000_000_000L;

    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;

    private ContactTaskLifecycleWorker worker() {
        return new ContactTaskLifecycleWorker(
                taskMapper, accountMapper, recipientMapper,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void promotesDueScheduledTaskToRunning() {
        when(taskMapper.startDueScheduledTask(eq(1L), anyLong())).thenReturn(1);

        worker().startDueScheduledTask(5L, 1L);

        verify(taskMapper).startDueScheduledTask(1L, NOW);
    }

    @Test
    void settlesAccountsBeforeCompletingTask() {
        when(recipientMapper.countUnfinished(1L)).thenReturn(0L);

        worker().completeDrainedTask(5L, 1L);

        verify(accountMapper).settleDrainedAccounts(1L, NOW);
        verify(taskMapper).completeDrainedTask(1L, NOW);
    }

    @Test
    void doesNotCompleteTaskThatStillHasWork() {
        when(recipientMapper.countUnfinished(1L)).thenReturn(2L);

        worker().completeDrainedTask(5L, 1L);

        verify(taskMapper, never()).completeDrainedTask(anyLong(), anyLong());
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='ContactTaskRoundWorkerTest,ContactTaskLifecycleWorkerTest' -DfailIfNoTests=false`

Expected: 编译失败，`package com.armada.contact.task.scheduler does not exist`

- [ ] **Step 4: 加圈号 Mapper 的按 ID 复查方法**

`AccountFilterSelectionMapper` 追加：

```java
    /**
     * 按账号 ID 批量复查协议事实。轮次执行时用来确认圈号后账号仍可发送。
     *
     * @param accountIds 账号 ID，**调用方必须保证非空**
     * @param normalAccountState 正常状态码
     * @param exportedAccountState 已导出状态码
     * @return 仍可发送的账号；被封或导出的不会出现在结果里
     */
    List<SelectedAccount> selectSendableByIds(
            @Param("accountIds") List<Long> accountIds,
            @Param("normalAccountState") int normalAccountState,
            @Param("exportedAccountState") int exportedAccountState);
```

XML 追加：

```xml
  <select id="selectSendableByIds"
          resultType="com.armada.account.selection.model.SelectedAccount">
    SELECT a.id            AS accountId,
           a.ws_phone      AS wsPhone,
           a.protocol_id   AS protocolId,
           a.protocol_account_id AS protocolAccountId
    FROM account a
    INNER JOIN account_state s
            ON s.account_id = a.id AND s.tenant_id = a.tenant_id
    WHERE a.deleted_at IS NULL
      AND a.protocol_account_id IS NOT NULL
      AND a.protocol_account_id &lt;&gt; ''
      AND s.account_state = #{normalAccountState}
      AND s.account_state &lt;&gt; #{exportedAccountState}
      AND a.id IN
      <foreach collection="accountIds" item="accountId" open="(" separator="," close=")">
        #{accountId}
      </foreach>
  </select>
```

- [ ] **Step 5: 写调度参数**

`armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskSchedulerProperties.java`：

```java
package com.armada.contact.task.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 通讯录营销轮次调度参数。 */
@ConfigurationProperties(prefix = "armada.contact.round-scheduler")
public class ContactTaskSchedulerProperties {

    private boolean enabled = true;
    private long scanFixedDelayMs = 1000;
    private int executorPoolSize = 5;
    private int scanLimit = 20;
    private int recipientsPerAccountPerRound = 20;
    private int outboxBatchSize = 200;
    private int backlogMultiplier = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getScanFixedDelayMs() {
        return scanFixedDelayMs;
    }

    public void setScanFixedDelayMs(long scanFixedDelayMs) {
        this.scanFixedDelayMs = scanFixedDelayMs;
    }

    public int getExecutorPoolSize() {
        return executorPoolSize;
    }

    public void setExecutorPoolSize(int executorPoolSize) {
        this.executorPoolSize = executorPoolSize;
    }

    public int getScanLimit() {
        return scanLimit;
    }

    public void setScanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
    }

    public int getRecipientsPerAccountPerRound() {
        return recipientsPerAccountPerRound;
    }

    public void setRecipientsPerAccountPerRound(int recipientsPerAccountPerRound) {
        this.recipientsPerAccountPerRound = recipientsPerAccountPerRound;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

    public int getBacklogMultiplier() {
        return backlogMultiplier;
    }

    public void setBacklogMultiplier(int backlogMultiplier) {
        this.backlogMultiplier = backlogMultiplier;
    }
}
```

- [ ] **Step 6: 写生命周期推进器**

`armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskLifecycleWorker.java`：

```java
package com.armada.contact.task.scheduler;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 通讯录营销任务的计划启动与自动完成推进器。
 *
 * <p>后台调度器跨租户扫描到期任务后，由本类恢复租户上下文再执行单任务状态流转——
 * 不设 TenantContext，MyBatis 租户拦截器就拦不住，跨租户串数据。</p>
 */
@Component
@Profile("kafka")
public class ContactTaskLifecycleWorker {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskLifecycleWorker.class);

    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final Clock clock;

    /**
     * 创建生命周期推进器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param clock 系统时钟
     */
    public ContactTaskLifecycleWorker(ContactFriendTaskMapper taskMapper,
                                      ContactFriendTaskAccountMapper accountMapper,
                                      ContactFriendTaskRecipientMapper recipientMapper,
                                      Clock clock) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.clock = clock;
    }

    /**
     * 到达计划开始时间后把已启用未开始任务推进到进行中。
     *
     * @param tenantId 租户 ID
     * @param taskId 任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void startDueScheduledTask(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = clock.millis();
            int updated = taskMapper.startDueScheduledTask(taskId, now);
            if (updated > 0) {
                log.info("通讯录任务到达计划开始时间并启动 tenantId={} taskId={} startedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restore(previous);
        }
    }

    /**
     * 收件人全部落终态后收敛账号状态并把任务推进到已完成。
     *
     * @param tenantId 租户 ID
     * @param taskId 任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeDrainedTask(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = clock.millis();
            // 先收敛账号终态，再算任务汇总：invalid_account_num 读的就是收敛后的 FAILED 行
            accountMapper.settleDrainedAccounts(taskId, now);
            if (recipientMapper.countUnfinished(taskId) > 0) {
                return;
            }
            int completed = taskMapper.completeDrainedTask(taskId, now);
            if (completed > 0) {
                log.info("通讯录任务全部收件人落终态并完成 tenantId={} taskId={} finishedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restore(previous);
        }
    }

    private static void restore(Long previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }
}
```

- [ ] **Step 7: 写轮次执行器**

`armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskRoundWorker.java`：

```java
package com.armada.contact.task.scheduler;

import com.armada.account.selection.AccountFilterSelector;
import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 单个通讯录营销任务的一轮发送生成器。
 *
 * <p>一轮只做一件事：把有 PENDING 收件人的账号各排一批出去。真实发送在协议层，
 * 不在本事务内同步执行。轮次抢占（{@code claimDueRound}）与收件人抢占
 * （{@code claimForSend}）是两道并发闸门，缺一就会重复投递。</p>
 */
@Component
@Profile("kafka")
public class ContactTaskRoundWorker {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskRoundWorker.class);

    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final AccountFilterSelectionMapper selectionMapper;
    private final ContactTaskMessageCommandFactory commandFactory;
    private final MessageSendPort messageSendPort;
    private final ContactTaskSchedulerProperties properties;
    private final Clock clock;
    private final Random random;
    private final DrainedTaskSettler settler;

    /** 收尾回调；生产装配传 {@link ContactTaskLifecycleWorker#completeDrainedTask}。 */
    @FunctionalInterface
    public interface DrainedTaskSettler {
        /**
         * 收敛并完成已排干的任务。
         *
         * @param tenantId 租户 ID
         * @param taskId 任务 ID
         */
        void settle(Long tenantId, Long taskId);
    }

    /**
     * 创建轮次执行器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param selectionMapper 账号协议事实复查
     * @param commandFactory 消息命令组装器
     * @param messageSendPort 协议 outbox 端口
     * @param properties 调度参数
     * @param clock 系统时钟
     * @param random 发送间隔随机源
     * @param settler 排干后的收尾回调
     */
    public ContactTaskRoundWorker(ContactFriendTaskMapper taskMapper,
                                  ContactFriendTaskAccountMapper accountMapper,
                                  ContactFriendTaskRecipientMapper recipientMapper,
                                  AccountFilterSelectionMapper selectionMapper,
                                  ContactTaskMessageCommandFactory commandFactory,
                                  MessageSendPort messageSendPort,
                                  ContactTaskSchedulerProperties properties,
                                  Clock clock,
                                  Random random,
                                  DrainedTaskSettler settler) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.selectionMapper = selectionMapper;
        this.commandFactory = commandFactory;
        this.messageSendPort = messageSendPort;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
        this.settler = settler;
    }

    /**
     * 生成一个任务的一轮发送命令。
     *
     * <p>调度器在无请求上下文的后台线程调用，必须显式设置 TenantContext。</p>
     *
     * @param tenantId 租户 ID
     * @param taskId 任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void runRound(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            doRunRound(tenantId, taskId);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private void doRunRound(Long tenantId, Long taskId) {
        ContactFriendTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("通讯录任务轮次跳过:任务不存在 taskId={}", taskId);
            return;
        }
        if (!Integer.valueOf(ContactTaskRunStatus.RUNNING.code()).equals(task.getRunStatus())) {
            return;
        }
        long now = clock.millis();
        int perAccount = Math.max(1, properties.getRecipientsPerAccountPerRound());
        long nextRoundAt = now + (long) perAccount * intervalCeilingMs(task);
        if (task.getTaskStartAt() != null && task.getTaskStartAt() > now) {
            taskMapper.postponeDueRound(taskId, now, task.getTaskStartAt());
            log.warn("通讯录任务轮次退回等待:尚未到计划开始时间 tenantId={} taskId={} taskStartAt={}",
                    tenantId, taskId, task.getTaskStartAt());
            return;
        }
        int accountLimit = task.getConcurrency() == null || task.getConcurrency() < 1
                ? 1
                : task.getConcurrency();
        List<Long> taskAccountIds =
                recipientMapper.selectAccountIdsWithPending(taskId, accountLimit);
        if (taskAccountIds.isEmpty()) {
            if (recipientMapper.countUnfinished(taskId) == 0L) {
                settler.settle(tenantId, taskId);
            } else {
                taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            }
            return;
        }
        long plannedCount = (long) taskAccountIds.size() * perAccount;
        long backlogThreshold = Math.max(1, properties.getBacklogMultiplier()) * plannedCount;
        if (recipientMapper.countInFlight(taskId) >= backlogThreshold) {
            taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            log.info("通讯录任务轮次因积压推迟 tenantId={} taskId={} plannedCount={} threshold={}",
                    tenantId, taskId, plannedCount, backlogThreshold);
            return;
        }
        if (taskMapper.claimDueRound(taskId, now, nextRoundAt) == 0) {
            return;
        }
        long roundNo = (task.getCurrentRoundNo() == null ? 0L : task.getCurrentRoundNo()) + 1L;
        executeClaimedRound(tenantId, task, taskAccountIds, roundNo, now, perAccount);
    }

    private void executeClaimedRound(Long tenantId,
                                     ContactFriendTask task,
                                     List<Long> taskAccountIds,
                                     long roundNo,
                                     long now,
                                     int perAccount) {
        List<ContactFriendTaskAccount> accountRows = new ArrayList<>(taskAccountIds.size());
        List<Long> armadaAccountIds = new ArrayList<>(taskAccountIds.size());
        for (Long taskAccountId : taskAccountIds) {
            ContactFriendTaskAccount row = accountMapper.selectById(taskAccountId);
            if (row != null) {
                accountRows.add(row);
                armadaAccountIds.add(row.getAccountId());
            }
        }
        if (accountRows.isEmpty()) {
            return;
        }
        Map<Long, SelectedAccount> facts = protocolFacts(armadaAccountIds);
        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                commandFactory.composeContent(task);
        List<MessageSendCommand> commands = new ArrayList<>();
        List<ContactFriendTaskRecipient> claimed = new ArrayList<>();
        for (ContactFriendTaskAccount accountRow : accountRows) {
            SelectedAccount protocolFact = facts.get(accountRow.getAccountId());
            if (protocolFact == null) {
                // 圈号后账号被封或导出，本轮跳过；收件人保持 PENDING 等下一轮
                log.info("通讯录任务轮次跳过不可发送账号 tenantId={} taskId={} accountId={}",
                        tenantId, task.getId(), accountRow.getAccountId());
                continue;
            }
            accountMapper.markRunning(accountRow.getId(), now);
            int position = 0;
            for (ContactFriendTaskRecipient recipient
                    : recipientMapper.selectPendingByAccount(accountRow.getId(), perAccount)) {
                String commandId = commandFactory.newCommandId();
                if (recipientMapper.claimForSend(recipient.getId(), roundNo, commandId, now) == 0) {
                    continue;
                }
                recipient.setCommandId(commandId);
                long notBeforeAt = now + (long) position * intervalCeilingMs(task);
                commands.add(commandFactory.toCommand(
                        task, accountRow, recipient, protocolFact, content,
                        roundNo, notBeforeAt, random));
                claimed.add(recipient);
                position++;
            }
        }
        if (commands.isEmpty()) {
            return;
        }
        enqueueInBatches(task, accountRows, commands, claimed, now);
        log.info("通讯录任务轮次发送命令已生成 tenantId={} taskId={} roundNo={} accounts={} commands={}",
                tenantId, task.getId(), roundNo, accountRows.size(), commands.size());
    }

    private Map<Long, SelectedAccount> protocolFacts(List<Long> armadaAccountIds) {
        if (armadaAccountIds.isEmpty()) {
            return Map.of();
        }
        List<SelectedAccount> rows = selectionMapper.selectSendableByIds(
                armadaAccountIds,
                AccountFilterSelector.ACCOUNT_STATE_NORMAL,
                AccountFilterSelector.ACCOUNT_STATE_EXPORTED);
        Map<Long, SelectedAccount> facts = new HashMap<>();
        if (rows != null) {
            for (SelectedAccount row : rows) {
                facts.put(row.accountId(), row);
            }
        }
        return facts;
    }

    private void enqueueInBatches(ContactFriendTask task,
                                  List<ContactFriendTaskAccount> accountRows,
                                  List<MessageSendCommand> commands,
                                  List<ContactFriendTaskRecipient> claimed,
                                  long now) {
        int batchSize = Math.max(1, Math.min(500, properties.getOutboxBatchSize()));
        for (int start = 0; start < commands.size(); start += batchSize) {
            int end = Math.min(commands.size(), start + batchSize);
            List<MessageSendCommand> batch = commands.subList(start, end);
            List<ContactFriendTaskRecipient> batchRecipients = claimed.subList(start, end);
            MessageSendEnqueueResult result = messageSendPort.enqueue(batch);
            if (result == null || result.items().size() != batch.size()) {
                throw new IllegalStateException("通讯录消息入队结果数量与命令不一致");
            }
            for (int i = 0; i < batch.size(); i++) {
                MessageSendEnqueueItem item = result.items().get(i);
                ContactFriendTaskRecipient recipient = batchRecipients.get(i);
                if (item != null && item.accepted()) {
                    continue;
                }
                String reasonCode = item == null ? "ENQUEUE_UNKNOWN" : item.reasonCode();
                String reasonMessage = item == null ? "入队结果缺失" : item.reasonMessage();
                if (recipientMapper.markFailed(
                        recipient.getId(), reasonCode, reasonMessage, now) > 0) {
                    accountMapper.incrementFailNum(recipient.getTaskAccountId(), now);
                }
            }
        }
    }

    /** 用配置区间上界作为节奏基准；无效配置兜底为 1 秒，避免紧循环。 */
    private static int intervalCeilingMs(ContactFriendTask task) {
        if (task.getMsgIntervalMaxSec() == null) {
            return 1000;
        }
        int ms = task.getMsgIntervalMaxSec().multiply(new java.math.BigDecimal("1000")).intValue();
        return Math.max(100, ms);
    }
}
```

- [ ] **Step 8: 写调度器与装配**

`armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskRoundScheduler.java`：

```java
package com.armada.contact.task.scheduler;

import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通讯录营销轮次调度器。
 *
 * <p>调度线程只扫描到期任务并投递到固定线程池，真正的抢占、抢批和写 outbox
 * 都在 {@link ContactTaskRoundWorker} 的事务里完成。</p>
 */
@Component
@Profile("kafka")
public class ContactTaskRoundScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskRoundScheduler.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ContactFriendTaskMapper taskMapper;
    private final ContactTaskRoundWorker worker;
    private final ContactTaskLifecycleWorker lifecycleWorker;
    private final ContactTaskSchedulerProperties properties;
    private final ExecutorService executor;

    /**
     * 创建调度器并按配置建立轮次执行线程池。
     *
     * @param taskMapper 任务主表数据访问
     * @param worker 轮次执行器
     * @param lifecycleWorker 生命周期推进器
     * @param properties 调度参数
     */
    public ContactTaskRoundScheduler(ContactFriendTaskMapper taskMapper,
                                     ContactTaskRoundWorker worker,
                                     ContactTaskLifecycleWorker lifecycleWorker,
                                     ContactTaskSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.worker = worker;
        this.lifecycleWorker = lifecycleWorker;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.getExecutorPoolSize()), runnable -> {
                    Thread thread = new Thread(runnable,
                            "contact-task-round-worker-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 按配置周期扫描到期任务。 */
    @Scheduled(fixedDelayString = "${armada.contact.round-scheduler.scan-fixed-delay-ms:1000}")
    public void scanDueTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int limit = Math.max(1, properties.getScanLimit());
        for (ContactFriendTask task : taskMapper.selectDueScheduledTasks(now, limit)) {
            startSafely(task);
        }
        for (ContactFriendTask task : taskMapper.selectDueRunningTasks(now, limit)) {
            executor.execute(() -> runSafely(task));
        }
    }

    private void startSafely(ContactFriendTask task) {
        try {
            lifecycleWorker.startDueScheduledTask(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("通讯录任务自动启动失败 tenantId={} taskId={}",
                    task.getTenantId(), task.getId(), ex);
        }
    }

    /** 单任务失败只记日志，不影响同批其他任务继续执行。 */
    private void runSafely(ContactFriendTask task) {
        try {
            worker.runRound(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("通讯录任务轮次执行失败 tenantId={} taskId={}",
                    task.getTenantId(), task.getId(), ex);
        }
    }

    /** 应用关闭时停止线程池，避免测试和部署退出时悬挂后台线程。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
```

`armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskSchedulerConfiguration.java`：

```java
package com.armada.contact.task.scheduler;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.platform.protocol.port.MessageSendPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.util.Random;

/** 通讯录营销调度装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContactTaskSchedulerProperties.class)
public class ContactTaskSchedulerConfiguration {

    /**
     * 通讯录调度专用时钟。与营销调度分开命名，避免 bean 名冲突。
     *
     * @return UTC 系统时钟
     */
    @Bean
    @Profile("kafka")
    public Clock contactTaskClock() {
        return Clock.systemUTC();
    }

    /**
     * 发送间隔随机源。集中成 bean，测试里可换成固定种子。
     *
     * @return 随机源
     */
    @Bean
    @Profile("kafka")
    public Random contactTaskSendIntervalRandom() {
        return new Random();
    }

    /**
     * 装配轮次执行器，并把排干收尾接到生命周期推进器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param selectionMapper 账号协议事实复查
     * @param commandFactory 消息命令组装器
     * @param messageSendPort 协议 outbox 端口
     * @param properties 调度参数
     * @param contactTaskClock 系统时钟
     * @param contactTaskSendIntervalRandom 随机源
     * @param lifecycleWorker 生命周期推进器
     * @return 轮次执行器
     */
    @Bean
    @Profile("kafka")
    public ContactTaskRoundWorker contactTaskRoundWorker(
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactFriendTaskRecipientMapper recipientMapper,
            AccountFilterSelectionMapper selectionMapper,
            ContactTaskMessageCommandFactory commandFactory,
            MessageSendPort messageSendPort,
            ContactTaskSchedulerProperties properties,
            Clock contactTaskClock,
            Random contactTaskSendIntervalRandom,
            ContactTaskLifecycleWorker lifecycleWorker) {
        return new ContactTaskRoundWorker(
                taskMapper, accountMapper, recipientMapper, selectionMapper,
                commandFactory, messageSendPort, properties,
                contactTaskClock, contactTaskSendIntervalRandom,
                lifecycleWorker::completeDrainedTask);
    }
}
```

> `ContactTaskRoundWorker` 与 `ContactTaskLifecycleWorker` 上的 `@Component` 与本 `@Bean` 二选一。
> 采用本 `@Bean` 装配，就把 `ContactTaskRoundWorker` 的 `@Component` 去掉，只保留
> `ContactTaskLifecycleWorker` 的 `@Component`（它的构造参数 Spring 能自动装配）。

- [ ] **Step 9: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='ContactTaskRoundWorkerTest,ContactTaskLifecycleWorkerTest,AccountFilterSelectionMapperXmlTest' -DfailIfNoTests=false`

Expected: PASS

- [ ] **Step 10: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/scheduler/
git add armada-api/src/main/java/com/armada/account/selection/mapper/AccountFilterSelectionMapper.java
git add armada-api/src/main/resources/mapper/account/AccountFilterSelectionMapper.xml
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskRoundWorkerTest.java
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskLifecycleWorkerTest.java
git commit -m "feat(contact): add contact task round scheduler"
```

---
## Task 12: 回执三级回写

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java`
- Modify: `armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskRecipientMapper.java`
- Modify: `armada-api/src/main/resources/mapper/contact/ContactFriendTaskRecipientMapper.xml`
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskSendResultSinkTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumerTest.java`

**Interfaces:**
- Consumes: `ProtocolMessageSendResultReportedSink`（既有接口）、Task 2 / 7 的 mapper 语句
- Produces:
  - `ProtocolMessageSendResultReportedEvent` 末尾追加三个组件：`Long contactTaskId, Long taskAccountId, Long recipientId`
  - `ContactFriendTaskRecipientMapper#selectById(Long id) : ContactFriendTaskRecipient`
  - `ContactTaskSendResultSink implements ProtocolMessageSendResultReportedSink`

**回写规则**

```
supports(event)  ← "contact_task".equals(event.source())

成功：
  markSuccess(recipientId, messageId, ts) 返回 0 → 重复回执，直接返回不动任何计数
  返回 1 → accountMapper.incrementSentNum(taskAccountId) + taskMapper.incrementSuccessMessageNum(taskId, 1)

失败：
  读 recipient 与 task 拿 attempt_count / retry_max
  attempt_count < retry_max → markRetry（置回 PENDING，下一轮重排），不动任何计数
  否则 markFailed 返回 1 → accountMapper.incrementFailNum(taskAccountId)
```

> **幂等基石**：`markSuccess` / `markFailed` / `markRetry` 都带 `send_status = 'SENDING'` 条件。
> 重复回执时更新行数为 0，计数一律不动。所有三级计数都挂在「本次更新真的生效了」这个条件上。

- [ ] **Step 1: 写失败测试**

`armada-api/src/test/java/com/armada/contact/task/ContactTaskSendResultSinkTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.service.ContactTaskSendResultSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务发送结果回写的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskSendResultSinkTest {

    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;

    private ContactTaskSendResultSink sink() {
        return new ContactTaskSendResultSink(
                taskMapper, accountMapper, recipientMapper, () -> 2_000L);
    }

    private static ProtocolMessageSendResultReportedEvent event(boolean success, String source) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_1", 5L,
                null, null, null, 7L,
                "acc_1", "8613900000001@s.whatsapp.net", "cmd_1",
                success, success ? "wamid.ABC" : null,
                success ? null : "TIMEOUT", success ? null : "发送超时",
                1_999L, "worker-1",
                null, null, source,
                "UNCONFIRMED", "PRECHECK_SKIPPED_BY_PEER_TARGET", 1_998L,
                null, null,
                1L, 101L, 999L);
    }

    private static ContactFriendTaskRecipient recipient(int attemptCount) {
        ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
        row.setId(999L);
        row.setTaskId(1L);
        row.setTaskAccountId(101L);
        row.setAttemptCount(attemptCount);
        return row;
    }

    private static ContactFriendTask taskWithRetryMax(int retryMax) {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setRetryMax(retryMax);
        return task;
    }

    @Test
    void claimsOnlyContactTaskSource() {
        assertThat(sink().supports(event(true, "contact_task"))).isTrue();
        assertThat(sink().supports(event(true, "marketing_task"))).isFalse();
        assertThat(sink().supports(event(true, "group_creation_marketing"))).isFalse();
        assertThat(sink().supports(null)).isFalse();
    }

    @Test
    void writesBackAllThreeLevelsOnSuccess() {
        when(recipientMapper.markSuccess(eq(999L), eq("wamid.ABC"), anyLong())).thenReturn(1);

        sink().handleSendResultReported(event(true, "contact_task"));

        verify(recipientMapper).markSuccess(999L, "wamid.ABC", 2_000L);
        verify(accountMapper).incrementSentNum(eq(101L), anyLong());
        verify(taskMapper).incrementSuccessMessageNum(eq(1L), eq(1), anyLong());
    }

    @Test
    void ignoresDuplicateSuccessReport() {
        // 条件更新返回 0 = 这条已经落过终态，计数一律不能再动
        when(recipientMapper.markSuccess(anyLong(), anyString(), anyLong())).thenReturn(0);

        sink().handleSendResultReported(event(true, "contact_task"));

        verify(accountMapper, never()).incrementSentNum(anyLong(), anyLong());
        verify(taskMapper, never()).incrementSuccessMessageNum(anyLong(), anyInt(), anyLong());
    }

    @Test
    void requeuesFailureWhileRetriesRemain() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(1));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(3));

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper).markRetry(eq(999L), eq("TIMEOUT"), anyString(), anyLong());
        verify(recipientMapper, never()).markFailed(anyLong(), anyString(), anyString(), anyLong());
        verify(accountMapper, never()).incrementFailNum(anyLong(), anyLong());
    }

    @Test
    void terminatesFailureWhenRetryBudgetIsSpent() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(3));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(3));
        when(recipientMapper.markFailed(eq(999L), anyString(), anyString(), anyLong())).thenReturn(1);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper).markFailed(eq(999L), eq("TIMEOUT"), anyString(), anyLong());
        verify(accountMapper).incrementFailNum(eq(101L), anyLong());
    }

    @Test
    void treatsZeroRetryMaxAsNoRetry() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(1));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(0));
        when(recipientMapper.markFailed(anyLong(), anyString(), anyString(), anyLong())).thenReturn(1);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper).markFailed(eq(999L), anyString(), anyString(), anyLong());
    }

    @Test
    void ignoresDuplicateFailureReport() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(3));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(3));
        when(recipientMapper.markFailed(anyLong(), anyString(), anyString(), anyLong())).thenReturn(0);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(accountMapper, never()).incrementFailNum(anyLong(), anyLong());
    }

    @Test
    void ignoresEventForUnknownRecipient() {
        when(recipientMapper.selectById(999L)).thenReturn(null);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper, never()).markRetry(anyLong(), anyString(), anyString(), anyLong());
        verify(recipientMapper, never()).markFailed(anyLong(), anyString(), anyString(), anyLong());
    }
}
```

在 `ProtocolMessageEventConsumerTest.java` 追加：

```java
    @Test
    void parsesContactTaskCorrelationAndSkipsMarketingRequirements() {
        // contact_task 事件没有 marketingTaskId/targetId/attemptId，不能因此判非法
        String envelope = """
                {"eventId":"evt_1","event":"message.send_result_reported","workerId":"w1",
                 "data":{"tenantId":5,"source":"contact_task","contactTaskId":1,
                         "taskAccountId":101,"recipientId":999,"roundNo":7,
                         "protocolAccountId":"acc_1","groupJid":"8613900000001@s.whatsapp.net",
                         "commandId":"cmd_1","success":true,"messageId":"wamid.ABC",
                         "timestamp":1999}}
                """;

        consumer.onMessage(envelope, null);

        ArgumentCaptor<ProtocolMessageSendResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(contactSink).handleSendResultReported(captor.capture());
        ProtocolMessageSendResultReportedEvent event = captor.getValue();
        assertThat(event.contactTaskId()).isEqualTo(1L);
        assertThat(event.taskAccountId()).isEqualTo(101L);
        assertThat(event.recipientId()).isEqualTo(999L);
        assertThat(event.roundNo()).isEqualTo(7L);
        assertThat(event.marketingTaskId()).isNull();
    }
```

> 该测试类若还没有 `contactSink` mock，按它现有的 sink mock 写法补一个，并让它的 `supports` 返回 true、其他 sink 返回 false（`selectSink` 要求恰好一个 sink 命中）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='ContactTaskSendResultSinkTest,ProtocolMessageEventConsumerTest' -DfailIfNoTests=false`

Expected: 编译失败，`constructor ProtocolMessageSendResultReportedEvent cannot be applied to given types`

- [ ] **Step 3: 扩事件 record**

在 `ProtocolMessageSendResultReportedEvent` 的 Javadoc 追加三行 `@param`，组件列表末尾追加：

```java
        Long contactTaskId,
        Long taskAccountId,
        Long recipientId
```

Javadoc 追加：

```
 * @param contactTaskId 通讯录营销任务 ID;source=contact_task 时使用
 * @param taskAccountId 通讯录任务账号行 ID;source=contact_task 时使用
 * @param recipientId 通讯录任务收件人 ID;source=contact_task 时使用
```

- [ ] **Step 4: 改消费器**

在 `ProtocolMessageEventConsumer` 顶部常量区加：

```java
    private static final String SOURCE_CONTACT_TASK = "contact_task";
```

在 `toSendResultReportedEvent` 里，`historicalGroupPull` 之后加：

```java
        boolean contactTask = SOURCE_CONTACT_TASK.equals(source);
```

把三处 `groupCreationMarketing || historicalGroupPull` 的判断统一改成
`groupCreationMarketing || historicalGroupPull || contactTask`（`marketingTaskId` / `targetId` / `attemptId` 三处；
`roundNo` 那一处也改，因为 contact_task 的 roundNo 走 `longValue` 即可，它在自身分支里必填）。

在构造实参末尾追加：

```java
                contactTask
                        ? requiredLong(data, "contactTaskId", "协议消息发送结果事件缺少 data.contactTaskId")
                        : longValue(data, "contactTaskId"),
                contactTask
                        ? requiredLong(data, "taskAccountId", "协议消息发送结果事件缺少 data.taskAccountId")
                        : longValue(data, "taskAccountId"),
                contactTask
                        ? requiredLong(data, "recipientId", "协议消息发送结果事件缺少 data.recipientId")
                        : longValue(data, "recipientId"));
```

- [ ] **Step 5: 加 `selectById` 到收件人 Mapper**

接口追加：

```java
    /**
     * 按主键读取收件人。回执处理时用来读取当前尝试次数。
     *
     * @param id 收件人 ID
     * @return 收件人行，不存在时为 null
     */
    ContactFriendTaskRecipient selectById(@Param("id") Long id);
```

XML 追加：

```xml
  <select id="selectById" resultMap="recipientMap">
    SELECT <include refid="recipientColumns"/>
    FROM contact_friend_task_recipient
    WHERE id = #{id}
  </select>
```

- [ ] **Step 6: 写 sink**

`armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java`：

```java
package com.armada.contact.task.service;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.LongSupplier;

/**
 * 通讯录营销发送结果的三级回写。
 *
 * <p>幂等基石是条件更新：{@code markSuccess/markFailed/markRetry} 都要求收件人当前处于
 * {@code SENDING}。重复回执时更新行数为 0，账号与任务计数一律不动——所有计数都挂在
 * 「这次更新真的生效了」这个条件上，而不是挂在事件本身。</p>
 *
 * <p><b>本类刻意不标注 {@code @Service}</b>：构造参数含 Supplier，由
 * {@code ContactTaskConfiguration} 显式构造，以便纯 Mockito 测试。</p>
 */
public class ContactTaskSendResultSink implements ProtocolMessageSendResultReportedSink {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskSendResultSink.class);

    /** 协议层识别通讯录任务命令的来源常量，逐字固定。 */
    public static final String SOURCE_CONTACT_TASK = "contact_task";

    private static final int ERROR_DESC_MAX_LENGTH = 255;

    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final LongSupplier clock;

    /**
     * 创建回执回写器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param clock 当前时间提供者（epoch 毫秒）
     */
    public ContactTaskSendResultSink(ContactFriendTaskMapper taskMapper,
                                     ContactFriendTaskAccountMapper accountMapper,
                                     ContactFriendTaskRecipientMapper recipientMapper,
                                     LongSupplier clock) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(ProtocolMessageSendResultReportedEvent event) {
        return event != null && SOURCE_CONTACT_TASK.equals(event.source());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            if (event.success()) {
                applySuccess(event);
            } else {
                applyFailure(event);
            }
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private void applySuccess(ProtocolMessageSendResultReportedEvent event) {
        long now = clock.getAsLong();
        if (recipientMapper.markSuccess(event.recipientId(), event.messageId(), now) == 0) {
            log.debug("通讯录任务重复成功回执,跳过计数 recipientId={} commandId={}",
                    event.recipientId(), event.commandId());
            return;
        }
        accountMapper.incrementSentNum(event.taskAccountId(), now);
        taskMapper.incrementSuccessMessageNum(event.contactTaskId(), 1, now);
    }

    private void applyFailure(ProtocolMessageSendResultReportedEvent event) {
        ContactFriendTaskRecipient recipient = recipientMapper.selectById(event.recipientId());
        if (recipient == null) {
            log.warn("通讯录任务失败回执找不到收件人 recipientId={} commandId={}",
                    event.recipientId(), event.commandId());
            return;
        }
        long now = clock.getAsLong();
        String errorCode = event.reasonCode();
        String errorDesc = truncate(event.reasonMessage());
        if (hasRetryBudget(event.contactTaskId(), recipient)) {
            recipientMapper.markRetry(event.recipientId(), errorCode, errorDesc, now);
            return;
        }
        if (recipientMapper.markFailed(event.recipientId(), errorCode, errorDesc, now) > 0) {
            accountMapper.incrementFailNum(event.taskAccountId(), now);
        }
    }

    /** {@code retry_max=0} 表示不重试；attempt_count 在抢批时已自增，所以比较的是已用次数。 */
    private boolean hasRetryBudget(Long taskId, ContactFriendTaskRecipient recipient) {
        ContactFriendTask task = taskMapper.selectById(taskId);
        int retryMax = task == null || task.getRetryMax() == null ? 0 : task.getRetryMax();
        int attempts = recipient.getAttemptCount() == null ? 0 : recipient.getAttemptCount();
        return attempts < retryMax;
    }

    /** 失败描述落库前按列宽截断，避免协议层长文案撑爆 VARCHAR(255)。 */
    private static String truncate(String value) {
        if (value == null || value.length() <= ERROR_DESC_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_DESC_MAX_LENGTH);
    }
}
```

- [ ] **Step 7: 装配 sink**

在 `ContactTaskConfiguration` 加：

```java
    /**
     * 装配通讯录任务发送结果回写器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @return 结果回写 sink
     */
    @Bean
    public ContactTaskSendResultSink contactTaskSendResultSink(
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactFriendTaskRecipientMapper recipientMapper) {
        return new ContactTaskSendResultSink(
                taskMapper, accountMapper, recipientMapper, System::currentTimeMillis);
    }
```

- [ ] **Step 8: 补齐其余事件构造点**

```bash
cd /home/yanwenchao/ideaProject/armada
grep -rn "new ProtocolMessageSendResultReportedEvent" armada-api/src
```

每一处在末尾补三个 `null`（通讯录测试里传真实值）。

- [ ] **Step 9: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest='ContactTaskSendResultSinkTest,ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest,HistoricalGroupSendResultServiceImplTest' -DfailIfNoTests=false`

Expected: PASS

- [ ] **Step 10: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/platform/kafka/consumer/message/
git add armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java
git add armada-api/src/main/java/com/armada/contact/task/config/ContactTaskConfiguration.java
git add armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskRecipientMapper.java
git add armada-api/src/main/resources/mapper/contact/ContactFriendTaskRecipientMapper.xml
git add armada-api/src/test/java/com/armada/
git commit -m "feat(contact): write back contact task send results"
```

---

## Task 13: 协议层回执补通讯录关联（armada-protocol）

**Files:**
- Modify: `protocol-layer/src/commands/worker-consumer.ts`
- Test: `protocol-layer/src/commands/message-send-peer.test.ts`

**Interfaces:**
- Consumes: `MessageSendPayload` 已有的 `contactTaskId` / `taskAccountId` / `recipientId`（P0 已加，`worker-consumer.ts:1156`）
- Produces: `messageResultBase()` 与 `messageSendLogFields()` 的返回对象各多三个字段

> **这是交接文档没记的缺口。** 当前 `messageResultBase()`（:1364）只回填 marketing / groupCreation / historicalGroup，
> **成功与失败回执都不带通讯录关联**——只有 `invalidMessageResultBase()`（:940）带。
> 不补这一处，armada 侧的 `ContactTaskSendResultSink` 永远收不到 `recipientId`。

- [ ] **Step 1: 写失败测试**

在 `protocol-layer/src/commands/message-send-peer.test.ts` 追加：

```typescript
  it('carries contact task correlation on successful send results', async () => {
    const published: Array<{ event: string; data: Record<string, unknown> }> = []
    const deps = makeDeps({
      publisher: {
        publish: async (event: string, _accountId: string, data: Record<string, unknown>) => {
          published.push({ event, data })
        }
      }
    })

    await executeWorkerCommand(peerSendCommand({ contactTaskId: 77, taskAccountId: 88, recipientId: 99, roundNo: 5 }), deps)

    const result = published.find(entry => entry.event === 'message.send_result_reported')
    expect(result).toBeDefined()
    expect(result!.data.contactTaskId).toBe(77)
    expect(result!.data.taskAccountId).toBe(88)
    expect(result!.data.recipientId).toBe(99)
    expect(result!.data.roundNo).toBe(5)
    expect(result!.data.source).toBe('contact_task')
  })

  it('carries contact task correlation on failed send results', async () => {
    const published: Array<{ event: string; data: Record<string, unknown> }> = []
    const deps = makeDeps({
      failSend: true,
      publisher: {
        publish: async (event: string, _accountId: string, data: Record<string, unknown>) => {
          published.push({ event, data })
        }
      }
    })

    await executeWorkerCommand(peerSendCommand({ contactTaskId: 77, taskAccountId: 88, recipientId: 99, roundNo: 5 }), deps)

    const result = published.find(entry => entry.event === 'message.send_result_reported')
    expect(result!.data.success).toBe(false)
    expect(result!.data.recipientId).toBe(99)
  })
```

> `makeDeps` / `peerSendCommand` 按该测试文件已有的 fixture 写法复用或小幅扩展（加一个 `failSend` 开关让 `sock.sendMessage` 抛错）。不要新建一套 fixture。

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/commands/message-send-peer.test.ts
```
Expected: FAIL，`expect(received).toBe(77)` 收到 `undefined`

- [ ] **Step 3: 改 `messageResultBase`**

在 `worker-consumer.ts:1364` 的返回对象里，`historicalMemberId` 之后插入：

```typescript
    contactTaskId: payload.contactTaskId,
    taskAccountId: payload.taskAccountId,
    recipientId: payload.recipientId,
```

- [ ] **Step 4: 改 `messageSendLogFields`**

同样在 `historicalMemberId` 之后插入同样三行，让排查日志与回执字段对齐。

- [ ] **Step 5: 跑测试确认通过**

Run:
```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/commands/
```
Expected: PASS（`worker/baileys-participating-groups.test.ts` 与 `traffic/baileys-patch.test.ts` 是既有失败，不在本目录）

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/commands/worker-consumer.ts
git add protocol-layer/src/commands/message-send-peer.test.ts
git commit -m "feat(message): report contact task correlation in send results"
```

---

## Task 14: 安卓协议放开私聊目标（whatsapp-server）

**Files:**
- Modify: `internal/armada/message_command.go`
- Modify: `internal/armada/message_event.go`
- Test: `internal/armada/message_command_test.go`
- Test: `internal/armada/message_event_test.go`

**Interfaces:**
- Consumes: armada 侧 `AndroidMessagePayload` 新增的三字段（Task 6）
- Produces:
  - `MessageCommandPayload` 新增 `ContactTaskID` / `TaskAccountID` / `RecipientID`（JSON tag 逐字 `contactTaskId` / `taskAccountId` / `recipientId`）
  - `MessageResultEventData` 新增同三字段（`omitempty`）
  - `isMessageTargetJID(jid string) bool`：`@g.us` 或 `@s.whatsapp.net` 均接受

> **这是交接文档没记的缺口。** `ac5e583` 只改了 `message_sender.go` 的发送路径，
> 三处解析仍硬校验 `@g.us`：`validateMessageCommand`（:275）、`ParseMessageCommandRoute`（:198）、
> `ParseMessageCommandReference`（:201）。不放开，安卓号的通讯录命令在解析阶段就被拒。

- [ ] **Step 1: 写失败测试**

在 `internal/armada/message_command_test.go` 追加：

```go
func TestParseMessageCommandAcceptsPeerTargetForContactTask(t *testing.T) {
	raw := []byte(`{"commandId":"cmd_1","commandType":"message.send.requested",` +
		`"protocolAccountId":"acc_1","payload":{"tenantId":5,"accountId":11,` +
		`"protocolAccountId":"acc_1","wsPhone":"8613800000000",` +
		`"groupJid":"8613900000001@s.whatsapp.net","messageType":"TEXT","text":"hi",` +
		`"source":"contact_task","contactTaskId":77,"taskAccountId":88,` +
		`"recipientId":99,"roundNo":5}}`)

	command, err := ParseMessageCommand(raw)

	if err != nil {
		t.Fatalf("peer target must be accepted for contact tasks: %v", err)
	}
	if command.Payload.ContactTaskID != 77 || command.Payload.TaskAccountID != 88 ||
		command.Payload.RecipientID != 99 || command.Payload.RoundNo != 5 {
		t.Fatalf("contact correlation was not parsed: %+v", command.Payload)
	}
}

func TestParseMessageCommandRejectsIncompleteContactCorrelation(t *testing.T) {
	for _, missing := range []string{"contactTaskId", "taskAccountId", "recipientId", "roundNo"} {
		payload := map[string]any{
			"contactTaskId": 77, "taskAccountId": 88, "recipientId": 99, "roundNo": 5,
		}
		delete(payload, missing)
		raw := contactTaskCommandJSON(t, payload)

		if _, err := ParseMessageCommand(raw); err == nil {
			t.Fatalf("missing %s must be rejected", missing)
		}
	}
}

func TestParseMessageCommandStillRejectsNonChatTarget(t *testing.T) {
	raw := []byte(`{"commandId":"cmd_1","commandType":"message.send.requested",` +
		`"protocolAccountId":"acc_1","payload":{"tenantId":5,"accountId":11,` +
		`"protocolAccountId":"acc_1","wsPhone":"8613800000000",` +
		`"groupJid":"broadcast","messageType":"TEXT","text":"hi",` +
		`"source":"contact_task","contactTaskId":77,"taskAccountId":88,` +
		`"recipientId":99,"roundNo":5}}`)

	if _, err := ParseMessageCommand(raw); err == nil {
		t.Fatal("a target that is neither a group nor a peer must still be rejected")
	}
}

func TestParseMessageCommandRouteAcceptsPeerTarget(t *testing.T) {
	raw := []byte(`{"commandId":"cmd_1","commandType":"message.send.requested",` +
		`"protocolAccountId":"acc_1","payload":{"protocolAccountId":"acc_1",` +
		`"groupJid":"8613900000001@s.whatsapp.net","sendIntervalMs":800}}`)

	if _, err := ParseMessageCommandRoute(raw); err != nil {
		t.Fatalf("route parsing must accept peer targets: %v", err)
	}
}
```

> `contactTaskCommandJSON(t, payload)` 是本测试文件里要新增的小辅助：把传入的关联字段合并进一份
> 完整的合法命令 JSON 再序列化。按文件已有风格实现，别引入新依赖。

在 `internal/armada/message_event_test.go` 追加：

```go
func TestBuildMessageResultEventCarriesContactCorrelation(t *testing.T) {
	command := MessageCommand{
		CommandID: "cmd_1", CommandType: CommandTypeMessageSendRequested,
		ProtocolAccountID: "acc_1",
		Payload: MessageCommandPayload{
			TenantID: 5, AccountID: 11, ProtocolAccountID: "acc_1",
			GroupJID: "8613900000001@s.whatsapp.net", Source: "contact_task",
			ContactTaskID: 77, TaskAccountID: 88, RecipientID: 99, RoundNo: 5,
		},
	}

	envelope, err := BuildMessageResultEvent(
		command, MessageSendResult{Success: true, MessageID: "wamid.ABC"},
		"worker-1", time.UnixMilli(1999))

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if envelope.Data.ContactTaskID != 77 || envelope.Data.TaskAccountID != 88 ||
		envelope.Data.RecipientID != 99 {
		t.Fatalf("contact correlation was not echoed: %+v", envelope.Data)
	}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan && go test ./internal/armada/...`

Expected: 编译失败，`command.Payload.ContactTaskID undefined`

- [ ] **Step 3: 加 payload 字段**

`MessageCommandPayload` 在 `HistoricalMemberID` 之后追加：

```go
	ContactTaskID         int64              `json:"contactTaskId"`
	TaskAccountID         int64              `json:"taskAccountId"`
	RecipientID           int64              `json:"recipientId"`
```

`ParseMessageCommandReference` 里的 `referencePayload` 匿名结构体同样追加这三个字段，
并在拼装 `payload` 时带上。

- [ ] **Step 4: 放开目标校验**

新增判定函数：

```go
// isMessageTargetJID 判断目标是否为可发送对象。
// 群营销发 @g.us，通讯录营销发 @s.whatsapp.net 私聊对端。
// groupJid 这个字段名是历史线上契约，不改；语义由后缀决定。
func isMessageTargetJID(jid string) bool {
	return strings.HasSuffix(jid, "@g.us") || strings.HasSuffix(jid, "@s.whatsapp.net")
}
```

把三处硬校验替换掉：

- `validateMessageCommand`：
  ```go
	if !isMessageTargetJID(payload.GroupJID) {
		return invalidCommand("groupJid", "must be a group or peer JID")
	}
  ```
- `ParseMessageCommandRoute`：
  ```go
	if !isMessageTargetJID(strings.TrimSpace(routePayload.GroupJID)) {
		return MessageCommandRoute{}, invalidCommand("groupJid", "must be a group or peer JID")
	}
  ```
- `ParseMessageCommandReference`：
  ```go
	if !isMessageTargetJID(payload.GroupJID) {
		return MessageCommandReference{}, invalidCommand("groupJid", "must be a group or peer JID")
	}
  ```

- [ ] **Step 5: 加 `contact_task` 关联校验**

在 `validateMessageCommand` 与 `ParseMessageCommandReference` 的来源分支里，
`historical_group_pull` 之后各插入一段（两处逐字一致）：

```go
	} else if payload.Source == "contact_task" {
		if payload.ContactTaskID == 0 || payload.TaskAccountID == 0 ||
			payload.RecipientID == 0 || payload.RoundNo == 0 {
			return invalidCommand("correlation", "is incomplete")
		}
```

（`ParseMessageCommandReference` 里的返回值形状不同，按该函数的返回签名调整。）

- [ ] **Step 6: 回填结果事件**

`MessageResultEventData` 在 `HistoricalMemberID` 之后追加：

```go
	ContactTaskID         int64  `json:"contactTaskId,omitempty"`
	TaskAccountID         int64  `json:"taskAccountId,omitempty"`
	RecipientID           int64  `json:"recipientId,omitempty"`
```

`BuildMessageResultEvent` 组装 `Data` 时补上：

```go
			ContactTaskID: command.Payload.ContactTaskID,
			TaskAccountID: command.Payload.TaskAccountID,
			RecipientID:   command.Payload.RecipientID,
```

- [ ] **Step 7: 跑测试确认通过**

Run: `cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan && go test ./internal/armada/...`

Expected: PASS（`internal/armada` 基线是全绿，任何失败都必须查清）

- [ ] **Step 8: 提交**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
git add internal/armada/message_command.go internal/armada/message_event.go
git add internal/armada/message_command_test.go internal/armada/message_event_test.go
git commit -m "feat(message): accept peer targets and contact task correlation"
```

---

## Task 15: 全量回归、数据模型 wiki 与文档回填

**Files:**
- Modify: `docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md`
- Modify: `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md`
- Modify: `.harness/wiki/` 下由 `gen_datamodel.py` 生成的数据模型文档

**Interfaces:**
- Consumes: Task 1–14 的全部产出
- Produces: 更新后的交接文档与设计文档；重新生成的数据模型 wiki

- [ ] **Step 1: 跑三仓全量回归**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer && \
  node --experimental-vm-modules ./node_modules/.bin/jest
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan && go test ./...
```

- [ ] **Step 2: 用 surefire 报告核对 armada 数字**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api/target/surefire-reports
echo -n "Tests: ";    grep -h "^Tests run:" *.txt | sed 's/Tests run: \([0-9]*\).*/\1/'   | paste -sd+ | bc
echo -n "Failures: "; grep -h "^Tests run:" *.txt | sed 's/.*Failures: \([0-9]*\).*/\1/' | paste -sd+ | bc
echo -n "Errors: ";   grep -h "^Tests run:" *.txt | sed 's/.*Errors: \([0-9]*\).*/\1/'   | paste -sd+ | bc
```

Expected: `Failures` 与 `Errors` 不高于基线 `7 / 461`。**高于基线就必须逐个查清，不许归因于环境。**

- [ ] **Step 3: 重跑数据模型生成器**

```bash
cd /home/yanwenchao/ideaProject/armada && python3 .harness/wiki/gen_datamodel.py
```

Expected: `contact_friend_task.current_round_no`、`contact_friend_task_recipient.round_no` / `command_id` 出现在生成结果里

- [ ] **Step 4: 回填交接文档**

在 `2026-08-29-contact-marketing-handoff.md` 里：

- §0 一句话现状改为「四块里做完三块半：协议层、通讯录采集、任务 CRUD、发送引擎已落地；前端未做」
- §3 已完成追加 P3b 的 commit 清单
- §4 未完成删掉 P3b 整节，只保留 P4 前端
- §6.1「本机验证不了」清单追加：`V160` 的 Flyway 执行、五张表加两张新 mapper 的租户拦截器注入、`@Profile("kafka")` 三个调度 bean 的装配、`INSERT IGNORE` 与 `ON DUPLICATE KEY UPDATE` 的真实幂等行为
- §7 踩过的坑追加两条：
  1. `messageResultBase` 与 `invalidMessageResultBase` 是两条独立回执路径，加关联字段要**两处都改**，只改一处会出现「失败带关联、成功不带」的诡异现象
  2. Go 侧目标校验散在三个解析函数里（`validateMessageCommand` / `ParseMessageCommandRoute` / `ParseMessageCommandReference`），改发送路径不等于改了解析路径

- [ ] **Step 5: 回填设计文档**

在 `2026-08-28-contact-marketing-replication-design.md` §7.3 末尾追加「实现偏离」小节，
逐字抄入本计划开头《对设计文档的三处有意偏离》表格。

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md
git add docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md
git add .harness/wiki/
git commit -m "docs(contact): record send engine landing state"
```

---

## Self-Review 记录

**1. Spec 覆盖**

| 设计条目 | 落在 |
|---|---|
| §7.3 圈号（强制注入 normal + 未导出） | Task 3、4 |
| §7.3 按 TTL 同步通讯录 | Task 8（`syncIfStale`，P2 已实现） |
| §7.3 写 `need_send_num` | Task 8 |
| §7.3 展开 recipient | Task 2、8 |
| §7.3 写 `total_send_num` / `used_account_count` | Task 7、8 |
| §7.3 轮次取批、concurrency 约束 | Task 11 |
| §7.3 `MessageTarget(contactJid)` | Task 10 |
| §7.3 LINK_CARD / IMAGE / TEXT 分支 | Task 10 |
| §7.3 逐条随机间隔 | Task 5、10 |
| §7.3 `ContactTaskCorrelation` 四字段 | Task 6、10 |
| §7.3 回执三级回写 + 重试 | Task 12 |
| §7.2 计划启动、自动完成 | Task 11 |
| §7.2 `action=start/pause/resume/stop` | P3a 已实现（`ContactTaskStateMachine`），本期未改 |
| §6.2 recipient 表 | Task 2 |
| §2.3 没有按钮 | Task 10（`neverProducesButtonCard`） |
| §2.5 带一位小数的秒 | Task 5（`keepsSubSecondPrecision`） |
| 协议四字段契约 | Task 6、10、13、14 |

**未覆盖且有意不做**：
- `invalid_account_num` 的「发送期间被封禁」真实语义 —— 见偏离表 #2，等 V3 真机验证
- `groupInviteAllowed` / `continent` / `platform` / `widType` 等筛选键 —— armada 无对应列，见 Task 3 能力边界说明
- 前端（P4）—— 不在本计划范围

**2. 占位符扫描**：无 TBD / TODO / "similar to Task N" / "add error handling"。每个代码步骤都给了可直接落盘的完整内容。三处「按仓库实际签名调整」是**已知的、点名到文件与命令的核对动作**（`MarketingTemplateFile` 字段名、`MessageSendEnqueueItem.rejected` 签名、P3a 的 `resultMap` id），不是留白。

**3. 类型一致性**

- `ContactTaskCorrelation(taskId, taskAccountId, recipientId, roundNo)` —— Task 6 定义，Task 10 构造，Task 11 断言，三处组件名一致
- `SelectedAccount(accountId, wsPhone, protocolId, protocolAccountId)` —— Task 4 定义，Task 8、10、11 消费，一致
- `ExpansionResult(accountCount, recipientCount)` —— Task 8 定义与断言一致
- `ComposedContactMessage` —— Task 10 定义与自身测试一致
- 收件人 mapper 方法名 —— Task 2 定义，Task 8、11、12 消费；Task 12 追加 `selectById`，与 Task 2 的 `recipientColumns` 片段配套
- 事件 record 三个新组件顺序 `contactTaskId, taskAccountId, recipientId` —— Task 12 定义与 sink 测试的构造实参顺序一致
- wire 字段名 `contactTaskId` / `taskAccountId` / `recipientId` / `roundNo` —— Task 6（Java 编码）、Task 13（TS 回填）、Task 14（Go 双向）四处逐字一致
