# 通讯录营销 P3a 任务数据层与接口 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让通讯录营销任务可以被创建、查询、编辑和启停——6 个接口全部可用，任务状态机完整，但**还不真正发消息**（发送引擎是 P3b）。

**Architecture:** 三张表。`contact_friend_task` 任务主表，`contact_friend_task_account` 任务×账号读模型，`contact_friend_task_recipient` 任务×账号×联系人明细。本期只建表并实现 CRUD 与状态机；`account` / `recipient` 两张表由 P3b 的展开逻辑填充，本期保持空表，账号数据接口返回空页。

**Tech Stack:** Java 17 / Spring Boot / MyBatis-Plus / Flyway / JUnit 5 + Mockito + AssertJ

**Spec:** `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md`（§2.4、§2.6、§2.7、§2.10、§6.2、§7.1、§7.2、§7.4）

**上游计划:**
- `docs/superpowers/plans/2026-08-28-contact-marketing-p0p1-protocol.md`（已完成）
- `docs/superpowers/plans/2026-08-28-contact-marketing-p2-contact-sync.md`（已完成）

## Global Constraints

- 工作分支 `feat/contact-marketing`（armada 仓）。本计划只动 `armada`。
- Flyway **从 `V158` 起**。`V157` 已被 P2 的 `account_contact_sync` 占用。新列必须带 `COMMENT`。
- **每张新表必须有 `tenant_id` 列**（MyBatis-Plus 租户拦截器会自动注入过滤）。
- 接口前缀 `/api/contact-tasks`，字段 camelCase，返回 `ApiResponse<T>` / `PageResult<T>`。
- 权限四件套：`tenant:contact_task:{view,create,edit,operate}`。**不提供删除接口**（竞品没有）。
- 所有新增 Java 类、public 方法必须有中文 Javadoc。
- **跑测试：`cd armada-api && mvn -o test`**。根目录没有聚合 pom。全量超 120 秒。
- **本机无法跑 `*DbTest`**（缺 `armada-api/.env` 库凭据）。可测逻辑必须放纯类，Service 用 Mockito，
  迁移与 XML 用静态契约测试。
- **改动任何既有类的构造签名后，必须同时 grep `new <类名>` 和 `@InjectMocks`**。
  P2 就是只查了前者，漏掉 `@InjectMocks` 注入 null，导致既有测试挂了 3 个。
- armada 当前失败基线（与本计划无关）：全量 `Tests run: 3473, Failures: 7, Errors: 461`。
  **回归时 Failures / Errors 只要涨了就必须查清，不能归因于环境。**
- **`messageType` 只有 `0` 链接消息 / `1` 图文消息，没有按钮**。不要引入按钮相关字段。

---

### Task 1: `V158` 三张表与 SQL 契约测试

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V163__contact_friend_task.sql`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskMigrationSqlTest.java`

**Interfaces:**
- Consumes: 无
- Produces: 表 `contact_friend_task`、`contact_friend_task_account`、`contact_friend_task_recipient`

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/contact/task/ContactTaskMigrationSqlTest.java`：

```java
package com.armada.contact.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 通讯录营销任务 Flyway 脚本契约测试。 */
class ContactTaskMigrationSqlTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V163__contact_friend_task.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void createsThreeTablesEachCarryingTenantColumn() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS contact_friend_task")
                .contains("CREATE TABLE IF NOT EXISTS contact_friend_task_account")
                .contains("CREATE TABLE IF NOT EXISTS contact_friend_task_recipient");
        // 三张表都必须有 tenant_id，否则 MyBatis-Plus 租户拦截器会注入非法条件
        assertThat(sql.split("tenant_id BIGINT NOT NULL")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void taskCarriesBothStatusFieldsWithCompetitorSemantics() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("is_enabled TINYINT NOT NULL DEFAULT 0")
                .contains("run_status TINYINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("0未开始 1进行中 2已完成 3已暂停 4已停止");
    }

    @Test
    void intervalColumnsKeepOneDecimalSecond() throws IOException {
        // 竞品的发送间隔是带一位小数的秒（最快 0.1s），不能落成整数
        assertThat(sql())
                .contains("msg_interval_min_sec DECIMAL(4,1)")
                .contains("msg_interval_max_sec DECIMAL(4,1)");
    }

    @Test
    void accountTableIsTheReadModelForPerAccountData() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("UNIQUE KEY uq_contact_task_account (task_id, account_id)")
                // 账号数据接口支持按这三列服务端排序
                .contains("KEY idx_contact_task_account_need (task_id, need_send_num)")
                .contains("KEY idx_contact_task_account_sent (task_id, sent_num)")
                .contains("KEY idx_contact_task_account_fail (task_id, fail_num)");
    }

    @Test
    void recipientHasIdempotencyKeyAndSnapshotColumns() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("UNIQUE KEY uq_contact_task_recipient (task_id, task_account_id, contact_phone)")
                .contains("KEY idx_contact_task_recipient_pick (task_id, send_status, id)");
        // recipient 存快照，不外键 account_contact：通讯录会变，任务事实不能跟着漂
        assertThat(sql).doesNotContain("account_contact_id");
    }

    @Test
    void everyColumnDefinitionCarriesComment() throws IOException {
        List<String> uncommented = sql().lines()
                .map(String::trim)
                .filter(line -> line.matches("^[a-z_]+ (BIGINT|INT|VARCHAR|CHAR|TINYINT|DECIMAL|JSON).*"))
                .filter(line -> !line.contains("COMMENT"))
                .toList();

        assertThat(uncommented).as("这些列缺 COMMENT").isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskMigrationSqlTest
```

Expected: FAIL，`NoSuchFileException` 指向 `V163__contact_friend_task.sql`

- [ ] **Step 3: 写迁移**

创建 `armada-api/src/main/resources/db/migration/V163__contact_friend_task.sql`：

```sql
-- 通讯录营销任务。一个任务 = 一组账号筛选条件 + 一条 WhatsApp 消息，
-- 命中的每个账号向自己通讯录里的联系人群发同一条消息。
-- 不复用 marketing_task：营销任务的账号占用是分组级的，通讯录任务按筛选跨分组圈号，套不上那把锁。

CREATE TABLE IF NOT EXISTS contact_friend_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    name VARCHAR(128) NOT NULL COMMENT '任务名称;仅后台展示',
    message_type TINYINT NOT NULL COMMENT '消息类型:0链接消息 1图文消息;创建后不可改',
    title VARCHAR(512) DEFAULT NULL COMMENT '消息标题;仅链接消息',
    description VARCHAR(2048) DEFAULT NULL COMMENT '链接描述;仅链接消息',
    promotion_link VARCHAR(2048) DEFAULT NULL COMMENT '推广链接;仅链接消息',
    content VARCHAR(2000) NOT NULL COMMENT '正文内容或图文文案',
    preview_image_file_id BIGINT DEFAULT NULL COMMENT '预览图或配图;引用marketing_template_file.id',
    account_filter JSON DEFAULT NULL COMMENT '账号筛选条件;白名单归一化后落库,空对象表示不限定',
    msg_interval_min_sec DECIMAL(4,1) NOT NULL DEFAULT 0.5 COMMENT '单号发送最小间隔秒;带一位小数',
    msg_interval_max_sec DECIMAL(4,1) NOT NULL DEFAULT 1.0 COMMENT '单号发送最大间隔秒;带一位小数',
    concurrency INT NOT NULL DEFAULT 10 COMMENT '最大执行账号数',
    max_sends_per_account INT NOT NULL DEFAULT 50 COMMENT '每号最大发送数;0表示全部联系人',
    retry_max INT NOT NULL DEFAULT 3 COMMENT '单条消息失败最大重试次数;0不重试',
    start_mode VARCHAR(16) NOT NULL DEFAULT 'now' COMMENT '启动方式:now立即 scheduled延后',
    task_delay_minutes INT NOT NULL DEFAULT 0 COMMENT '延后执行分钟数;start_mode=now时为0',
    task_start_at BIGINT DEFAULT NULL COMMENT '计划开始时间(epoch毫秒)',
    is_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '任务开关:0已停用仅保存 1启用',
    run_status TINYINT NOT NULL DEFAULT 0 COMMENT '运行状态:0未开始 1进行中 2已完成 3已暂停 4已停止',
    next_round_at BIGINT DEFAULT NULL COMMENT '下一轮调度时间(epoch毫秒)',
    total_send_num INT NOT NULL DEFAULT 0 COMMENT '计划发送总条数',
    success_message_num INT NOT NULL DEFAULT 0 COMMENT '成功送达条数',
    used_account_count INT NOT NULL DEFAULT 0 COMMENT '实际参与发送的账号数',
    invalid_account_num INT NOT NULL DEFAULT 0 COMMENT '发送期间被封禁的账号数',
    avg_send_per_account DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '号均发量',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL为未删',
    PRIMARY KEY (id),
    KEY idx_contact_task_tenant (tenant_id, deleted_at, id),
    KEY idx_contact_task_run (tenant_id, run_status, next_round_at),
    KEY idx_contact_task_created (tenant_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通讯录营销任务主表';

CREATE TABLE IF NOT EXISTS contact_friend_task_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务账号主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '所属任务ID',
    account_id BIGINT NOT NULL COMMENT '发送账号ID',
    account_phone_snapshot VARCHAR(32) DEFAULT NULL COMMENT '账号号码快照;账号改号不影响历史',
    account_status_snapshot VARCHAR(16) DEFAULT NULL COMMENT '账号状态快照:valid有效 invalid无效',
    need_send_num INT NOT NULL DEFAULT 0 COMMENT '该账号计划发送条数',
    sent_num INT NOT NULL DEFAULT 0 COMMENT '该账号已成功条数',
    fail_num INT NOT NULL DEFAULT 0 COMMENT '该账号失败条数',
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '账号执行态:PENDING RUNNING DONE FAILED SKIPPED',
    contact_synced_at BIGINT DEFAULT NULL COMMENT '本任务使用的通讯录快照时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_contact_task_account (task_id, account_id),
    KEY idx_contact_task_account_need (task_id, need_send_num),
    KEY idx_contact_task_account_sent (task_id, sent_num),
    KEY idx_contact_task_account_fail (task_id, fail_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通讯录营销任务账号维度读模型';

CREATE TABLE IF NOT EXISTS contact_friend_task_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '收件人主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '所属任务ID',
    task_account_id BIGINT NOT NULL COMMENT '所属任务账号行ID',
    contact_phone VARCHAR(32) NOT NULL COMMENT '联系人号码快照;不带加号的纯数字',
    contact_jid VARCHAR(64) NOT NULL COMMENT '联系人JID快照',
    contact_named TINYINT NOT NULL DEFAULT 0 COMMENT '展开时该联系人是否有名字',
    send_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '发送状态:PENDING SENDING SUCCESS FAILED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    protocol_message_id VARCHAR(128) DEFAULT NULL COMMENT '协议返回的消息ID',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '失败错误码',
    error_desc VARCHAR(255) DEFAULT NULL COMMENT '失败描述',
    first_sent_at BIGINT DEFAULT NULL COMMENT '首次发出时间(epoch毫秒)',
    last_attempt_at BIGINT DEFAULT NULL COMMENT '最近一次尝试时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_contact_task_recipient (task_id, task_account_id, contact_phone),
    KEY idx_contact_task_recipient_pick (task_id, send_status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通讯录营销任务收件人明细';
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskMigrationSqlTest
```

Expected: PASS，6 个用例全绿

- [ ] **Step 5: 确认 Flyway 序号无冲突**

```bash
ls /home/yanwenchao/ideaProject/armada/armada-api/src/main/resources/db/migration | sort -V | tail -3
```

Expected: `V156`、`V162__account_contact_sync.sql`、`V163__contact_friend_task.sql`

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/resources/db/migration/V163__contact_friend_task.sql
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskMigrationSqlTest.java
git commit -m "feat(contact): add contact marketing task schema"
```

---

### Task 2: 任务状态机纯函数

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/model/enums/ContactTaskRunStatus.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/model/enums/ContactTaskAction.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactTaskStateMachine.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskStateMachineTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum ContactTaskRunStatus { NOT_STARTED(0), RUNNING(1), COMPLETED(2), PAUSED(3), STOPPED(4) }`，
    含 `code()` 与 `static ContactTaskRunStatus fromCode(int)`
  - `enum ContactTaskAction { START, PAUSE, RESUME, STOP }`，含 `static ContactTaskAction fromWire(String)`
  - `ContactTaskStateMachine.next(ContactTaskRunStatus current, ContactTaskAction action)`
    → `Optional<ContactTaskRunStatus>`（不允许的迁移返回空）
  - `ContactTaskStateMachine.isEditable(ContactTaskRunStatus current)` → `boolean`

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/contact/task/ContactTaskStateMachineTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.model.enums.ContactTaskAction;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.service.ContactTaskStateMachine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactTaskStateMachineTest {

    @Test
    void runStatusCodesMatchCompetitorSemantics() {
        assertThat(ContactTaskRunStatus.NOT_STARTED.code()).isZero();
        assertThat(ContactTaskRunStatus.RUNNING.code()).isEqualTo(1);
        assertThat(ContactTaskRunStatus.COMPLETED.code()).isEqualTo(2);
        assertThat(ContactTaskRunStatus.PAUSED.code()).isEqualTo(3);
        assertThat(ContactTaskRunStatus.STOPPED.code()).isEqualTo(4);
        assertThat(ContactTaskRunStatus.fromCode(3)).isEqualTo(ContactTaskRunStatus.PAUSED);
    }

    @Test
    void unknownRunStatusCodeIsRejected() {
        assertThatThrownBy(() -> ContactTaskRunStatus.fromCode(9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionsParseFromLowerCaseWireValues() {
        assertThat(ContactTaskAction.fromWire("start")).isEqualTo(ContactTaskAction.START);
        assertThat(ContactTaskAction.fromWire("PAUSE")).isEqualTo(ContactTaskAction.PAUSE);
        assertThat(ContactTaskAction.fromWire("resume")).isEqualTo(ContactTaskAction.RESUME);
        assertThat(ContactTaskAction.fromWire("stop")).isEqualTo(ContactTaskAction.STOP);
    }

    @Test
    void unknownActionIsRejected() {
        assertThatThrownBy(() -> ContactTaskAction.fromWire("delete"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContactTaskAction.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowedTransitionsFollowCompetitorRules() {
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.START))
                .contains(ContactTaskRunStatus.RUNNING);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.PAUSE))
                .contains(ContactTaskRunStatus.PAUSED);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.PAUSED, ContactTaskAction.RESUME))
                .contains(ContactTaskRunStatus.RUNNING);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.STOP))
                .contains(ContactTaskRunStatus.STOPPED);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.PAUSED, ContactTaskAction.STOP))
                .contains(ContactTaskRunStatus.STOPPED);
    }

    @Test
    void stoppedAndCompletedAreTerminal() {
        for (ContactTaskAction action : ContactTaskAction.values()) {
            assertThat(ContactTaskStateMachine.next(ContactTaskRunStatus.STOPPED, action))
                    .as("已停止是终态,不可恢复 action=%s", action)
                    .isEmpty();
            assertThat(ContactTaskStateMachine.next(ContactTaskRunStatus.COMPLETED, action))
                    .as("已完成是终态 action=%s", action)
                    .isEmpty();
        }
    }

    @Test
    void rejectsNonsenseTransitions() {
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.PAUSE)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.RESUME)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.STOP)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.START)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.RESUME)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.PAUSED, ContactTaskAction.PAUSE)).isEmpty();
    }

    @Test
    void onlyNotStartedTasksAreEditable() {
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.NOT_STARTED)).isTrue();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.RUNNING)).isFalse();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.PAUSED)).isFalse();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.COMPLETED)).isFalse();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.STOPPED)).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskStateMachineTest
```

Expected: FAIL，`cannot find symbol: class ContactTaskRunStatus`

- [ ] **Step 3: 写实现**

`ContactTaskRunStatus.java`：

```java
package com.armada.contact.task.model.enums;

/** 通讯录营销任务运行状态，取值与竞品一致。 */
public enum ContactTaskRunStatus {

    /** 未开始。 */
    NOT_STARTED(0),
    /** 进行中。 */
    RUNNING(1),
    /** 已完成。 */
    COMPLETED(2),
    /** 已暂停，可恢复。 */
    PAUSED(3),
    /** 已停止，终态不可恢复。 */
    STOPPED(4);

    private final int code;

    ContactTaskRunStatus(int code) {
        this.code = code;
    }

    /**
     * 落库与接口使用的状态码。
     *
     * @return 状态码
     */
    public int code() {
        return code;
    }

    /**
     * 由状态码解析枚举。
     *
     * @param code 状态码
     * @return 对应枚举
     * @throws IllegalArgumentException 状态码非法时抛出
     */
    public static ContactTaskRunStatus fromCode(int code) {
        for (ContactTaskRunStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的通讯录任务运行状态: " + code);
    }
}
```

`ContactTaskAction.java`：

```java
package com.armada.contact.task.model.enums;

import java.util.Locale;

/** 通讯录营销任务的操作动作。竞品没有删除动作，本枚举也不提供。 */
public enum ContactTaskAction {

    /** 启动。 */
    START,
    /** 暂停。 */
    PAUSE,
    /** 恢复。 */
    RESUME,
    /** 停止，终态。 */
    STOP;

    /**
     * 由接口传入的动作字符串解析枚举，大小写不敏感。
     *
     * @param value 接口原值
     * @return 对应枚举
     * @throws IllegalArgumentException 动作非法或为空时抛出
     */
    public static ContactTaskAction fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("任务动作不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ContactTaskAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知的通讯录任务动作: " + value);
    }
}
```

`ContactTaskStateMachine.java`：

```java
package com.armada.contact.task.service;

import com.armada.contact.task.model.enums.ContactTaskAction;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;

import java.util.Optional;

/**
 * 通讯录营销任务状态机。
 *
 * <p>纯函数，不碰数据库。迁移规则与竞品一致：已停止和已完成都是终态，
 * 「停止后任务将被终止，且无法恢复」是竞品确认弹框的原文。</p>
 */
public final class ContactTaskStateMachine {

    private ContactTaskStateMachine() {
    }

    /**
     * 计算一次动作后的目标状态。
     *
     * @param current 当前运行状态
     * @param action 请求动作
     * @return 允许迁移时返回目标状态，否则返回空
     */
    public static Optional<ContactTaskRunStatus> next(
            ContactTaskRunStatus current, ContactTaskAction action) {
        if (current == null || action == null) {
            return Optional.empty();
        }
        return switch (current) {
            case NOT_STARTED -> action == ContactTaskAction.START
                    ? Optional.of(ContactTaskRunStatus.RUNNING)
                    : Optional.empty();
            case RUNNING -> switch (action) {
                case PAUSE -> Optional.of(ContactTaskRunStatus.PAUSED);
                case STOP -> Optional.of(ContactTaskRunStatus.STOPPED);
                default -> Optional.empty();
            };
            case PAUSED -> switch (action) {
                case RESUME -> Optional.of(ContactTaskRunStatus.RUNNING);
                case STOP -> Optional.of(ContactTaskRunStatus.STOPPED);
                default -> Optional.empty();
            };
            // 已完成与已停止都是终态，任何动作都不接受
            case COMPLETED, STOPPED -> Optional.empty();
        };
    }

    /**
     * 判断任务当前是否允许编辑。
     *
     * <p>竞品口径：只有未开始的任务可编辑，一旦开始就只能查看。</p>
     *
     * @param current 当前运行状态
     * @return 可编辑则 true
     */
    public static boolean isEditable(ContactTaskRunStatus current) {
        return current == ContactTaskRunStatus.NOT_STARTED;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskStateMachineTest
```

Expected: PASS，8 个用例全绿

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskStateMachineTest.java
git commit -m "feat(contact): add contact task state machine"
```

---

### Task 3: 表单校验与账号筛选归一化

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/model/dto/ContactTaskFormDTO.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactTaskFormValidator.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactAccountFilterNormalizer.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskFormValidatorTest.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactAccountFilterNormalizerTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `record ContactTaskFormDTO(String name, Integer messageType, String title, String description, String promotionLink, String content, java.math.BigDecimal msgIntervalMinSec, java.math.BigDecimal msgIntervalMaxSec, Integer concurrency, Integer maxSendsPerAccount, Integer retryMax, String startMode, Integer taskDelayMinutes, Integer isEnabled, String accountFilterJson)`
  - `ContactTaskFormValidator.validate(ContactTaskFormDTO form)` → 抛 `BusinessException(VALIDATION, ...)`，
    通过则返回归一后的 `ContactTaskFormDTO`
  - `ContactAccountFilterNormalizer.normalize(String rawJson)` → `String`（归一后的 JSON 字符串）

- [ ] **Step 1: 写校验器失败测试**

创建 `armada-api/src/test/java/com/armada/contact/task/ContactTaskFormValidatorTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.service.ContactTaskFormValidator;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactTaskFormValidatorTest {

    private final ContactTaskFormValidator validator = new ContactTaskFormValidator();

    private static ContactTaskFormDTO linkForm() {
        return new ContactTaskFormDTO(
                "春节福利-好友群发", 0, "限时领取红包", "一句话补充", "https://example.com/promo",
                "老朋友专享福利", new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, "now", 0, 1, "{}");
    }

    private static ContactTaskFormDTO imageForm() {
        return new ContactTaskFormDTO(
                "图文任务", 1, null, null, null, "配图文案",
                new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, "now", 0, 1, "{}");
    }

    @Test
    void acceptsValidLinkForm() {
        assertThat(validator.validate(linkForm()).name()).isEqualTo("春节福利-好友群发");
    }

    @Test
    void acceptsValidImageFormWithoutLinkFields() {
        ContactTaskFormDTO normalized = validator.validate(imageForm());
        // 图文消息的三个链接字段一律清空，不允许写脏数据
        assertThat(normalized.title()).isNull();
        assertThat(normalized.description()).isNull();
        assertThat(normalized.promotionLink()).isNull();
    }

    @Test
    void linkMessageRequiresTitleDescriptionAndLink() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 0, null, "d", "https://a.com", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息标题");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 0, "t", null, "https://a.com", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("链接描述");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 0, "t", "d", "  ", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("推广链接");
    }

    @Test
    void nameAndContentAreAlwaysRequired() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "  ", 0, "t", "d", "https://a.com", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务名称");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "  ",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容");
    }

    @Test
    void messageTypeMustBeZeroOrOne() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 3, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息类型");
    }

    @Test
    void intervalIsRoundedToOneDecimalAndMaxIsLiftedToMin() {
        ContactTaskFormDTO normalized = validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("1.26"), new BigDecimal("0.4"),
                10, 50, 3, "now", 0, 1, "{}"));

        // 竞品：Math.round(x*10)/10，且 max 被抬到不小于 min
        assertThat(normalized.msgIntervalMinSec()).isEqualByComparingTo("1.3");
        assertThat(normalized.msgIntervalMaxSec()).isEqualByComparingTo("1.3");
    }

    @Test
    void intervalBelowFloorIsRejected() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.0"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发送间隔");
    }

    @Test
    void numericBoundsFollowCompetitorControls() {
        // concurrency 1~200
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 0, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大执行账号数");
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 201, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大执行账号数");

        // maxSendsPerAccount >= 0，0 表示全部
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, -1, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("每号最大发送数");

        // retryMax 0~10
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 11, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重试次数");
    }

    @Test
    void scheduledModeRequiresPositiveDelayOnlyWhenEnabled() {
        // 启用 + 延后 + 延迟为 0 → 拒绝
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "scheduled", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("延迟");

        // 未启用时同样的表单允许保存
        assertThat(validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "scheduled", 0, 0, "{}"))
                .taskDelayMinutes()).isZero();
    }

    @Test
    void immediateModeForcesDelayToZero() {
        ContactTaskFormDTO normalized = validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 30, 1, "{}"));

        assertThat(normalized.taskDelayMinutes()).isZero();
    }

    @Test
    void unknownStartModeIsRejected() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "cron", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("启动方式");
    }

    @Test
    void overlongTextFieldsAreRejectedNotTruncated() {
        String tooLongName = "n".repeat(129);
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                tooLongName, 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务名称");

        String tooLongContent = "c".repeat(2001);
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, tooLongContent,
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskFormValidatorTest
```

Expected: FAIL，`cannot find symbol: class ContactTaskFormDTO`

- [ ] **Step 3: 写 DTO 与校验器**

`ContactTaskFormDTO.java`：

```java
package com.armada.contact.task.model.dto;

import java.math.BigDecimal;

/**
 * 通讯录营销任务创建与编辑的统一表单。
 *
 * @param name 任务名称，最长 128
 * @param messageType 消息类型：0 链接消息 / 1 图文消息
 * @param title 消息标题，仅链接消息必填，最长 512
 * @param description 链接描述，仅链接消息必填，最长 2048
 * @param promotionLink 推广链接，仅链接消息必填，最长 2048
 * @param content 正文内容或图文文案，必填，最长 2000
 * @param msgIntervalMinSec 单号发送最小间隔秒，带一位小数
 * @param msgIntervalMaxSec 单号发送最大间隔秒，带一位小数
 * @param concurrency 最大执行账号数，1~200
 * @param maxSendsPerAccount 每号最大发送数，0 表示全部联系人
 * @param retryMax 单条消息失败最大重试次数，0~10
 * @param startMode 启动方式：now 立即 / scheduled 延后
 * @param taskDelayMinutes 延后分钟数，now 模式恒为 0
 * @param isEnabled 任务开关：0 已停用仅保存 / 1 启用
 * @param accountFilterJson 账号筛选条件 JSON 字符串
 */
public record ContactTaskFormDTO(
        String name,
        Integer messageType,
        String title,
        String description,
        String promotionLink,
        String content,
        BigDecimal msgIntervalMinSec,
        BigDecimal msgIntervalMaxSec,
        Integer concurrency,
        Integer maxSendsPerAccount,
        Integer retryMax,
        String startMode,
        Integer taskDelayMinutes,
        Integer isEnabled,
        String accountFilterJson
) {
}
```

`ContactTaskFormValidator.java`：

```java
package com.armada.contact.task.service;

import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 通讯录营销任务表单校验与归一化。
 *
 * <p>纯逻辑，不碰数据库。约束逐条对齐竞品前端控件（设计文档 §2.4、§2.5）：
 * 越界一律拒绝而不是静默裁剪，避免用户以为设置生效了。
 * 唯一例外是 max 间隔小于 min 时抬到 min——竞品前端本身就是这么做的。</p>
 */
@Component
public class ContactTaskFormValidator {

    /** 链接消息。 */
    private static final int MESSAGE_TYPE_LINK = 0;
    /** 图文消息。 */
    private static final int MESSAGE_TYPE_IMAGE = 1;

    private static final int NAME_MAX = 128;
    private static final int TITLE_MAX = 512;
    private static final int DESCRIPTION_MAX = 2048;
    private static final int LINK_MAX = 2048;
    private static final int CONTENT_MAX = 2000;

    private static final BigDecimal INTERVAL_MIN = new BigDecimal("0.1");
    private static final BigDecimal INTERVAL_MAX = new BigDecimal("60.0");

    private static final int CONCURRENCY_MIN = 1;
    private static final int CONCURRENCY_MAX = 200;
    private static final int RETRY_MAX_LIMIT = 10;

    private static final String START_MODE_NOW = "now";
    private static final String START_MODE_SCHEDULED = "scheduled";

    /**
     * 校验并归一化任务表单。
     *
     * @param form 原始表单
     * @return 归一化后的表单
     * @throws BusinessException 任一约束不满足时抛出
     */
    public ContactTaskFormDTO validate(ContactTaskFormDTO form) {
        if (form == null) {
            throw invalid("任务表单不能为空");
        }
        String name = required(form.name(), "任务名称");
        limit(name, NAME_MAX, "任务名称");

        int messageType = form.messageType() == null ? -1 : form.messageType();
        if (messageType != MESSAGE_TYPE_LINK && messageType != MESSAGE_TYPE_IMAGE) {
            throw invalid("消息类型只能是 0 链接消息或 1 图文消息");
        }

        String content = required(form.content(), "正文内容");
        limit(content, CONTENT_MAX, "正文内容");

        String title = null;
        String description = null;
        String promotionLink = null;
        if (messageType == MESSAGE_TYPE_LINK) {
            title = required(form.title(), "消息标题");
            limit(title, TITLE_MAX, "消息标题");
            description = required(form.description(), "链接描述");
            limit(description, DESCRIPTION_MAX, "链接描述");
            promotionLink = required(form.promotionLink(), "推广链接");
            limit(promotionLink, LINK_MAX, "推广链接");
        }

        BigDecimal min = interval(form.msgIntervalMinSec(), "发送间隔最小值");
        BigDecimal max = interval(form.msgIntervalMaxSec(), "发送间隔最大值");
        // 与竞品前端一致：最大值小于最小值时抬到最小值，而不是报错
        if (max.compareTo(min) < 0) {
            max = min;
        }

        int concurrency = form.concurrency() == null ? 0 : form.concurrency();
        if (concurrency < CONCURRENCY_MIN || concurrency > CONCURRENCY_MAX) {
            throw invalid("最大执行账号数必须在 " + CONCURRENCY_MIN + "~" + CONCURRENCY_MAX + " 之间");
        }

        int maxSends = form.maxSendsPerAccount() == null ? 0 : form.maxSendsPerAccount();
        if (maxSends < 0) {
            throw invalid("每号最大发送数不能为负数，0 表示全部联系人");
        }

        int retryMax = form.retryMax() == null ? 0 : form.retryMax();
        if (retryMax < 0 || retryMax > RETRY_MAX_LIMIT) {
            throw invalid("失败重试次数必须在 0~" + RETRY_MAX_LIMIT + " 之间");
        }

        String startMode = form.startMode() == null ? "" : form.startMode().trim();
        if (!START_MODE_NOW.equals(startMode) && !START_MODE_SCHEDULED.equals(startMode)) {
            throw invalid("启动方式只能是 now 或 scheduled");
        }

        int enabled = form.isEnabled() == null ? 0 : form.isEnabled();
        if (enabled != 0 && enabled != 1) {
            throw invalid("任务开关只能是 0 或 1");
        }

        int delay = form.taskDelayMinutes() == null ? 0 : form.taskDelayMinutes();
        if (START_MODE_NOW.equals(startMode)) {
            // 立即执行时延迟恒为 0，前端也是这么提交的
            delay = 0;
        } else if (enabled == 1 && delay <= 0) {
            // 只有「启用 + 延后」才要求延迟为正数；仅保存草稿时不拦
            throw invalid("延迟时间需大于 0 分钟");
        }

        return new ContactTaskFormDTO(
                name, messageType, title, description, promotionLink, content,
                min, max, concurrency, maxSends, retryMax, startMode, delay, enabled,
                form.accountFilterJson());
    }

    private static BigDecimal interval(BigDecimal value, String label) {
        if (value == null) {
            throw invalid(label + "不能为空");
        }
        BigDecimal rounded = value.setScale(1, RoundingMode.HALF_UP);
        if (rounded.compareTo(INTERVAL_MIN) < 0 || rounded.compareTo(INTERVAL_MAX) > 0) {
            throw invalid("发送间隔必须在 " + INTERVAL_MIN + "~" + INTERVAL_MAX + " 秒之间");
        }
        return rounded;
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(label + "不能为空");
        }
        return value.trim();
    }

    private static void limit(String value, int max, String label) {
        if (value.length() > max) {
            throw invalid(label + "长度不能超过 " + max);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskFormValidatorTest
```

Expected: PASS，11 个用例全绿

- [ ] **Step 5: 写筛选归一化失败测试**

创建 `armada-api/src/test/java/com/armada/contact/task/ContactAccountFilterNormalizerTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.service.ContactAccountFilterNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactAccountFilterNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContactAccountFilterNormalizer normalizer =
            new ContactAccountFilterNormalizer(new ObjectMapper());

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void nullAndBlankBecomeEmptyObject() {
        assertThat(normalizer.normalize(null)).isEqualTo("{}");
        assertThat(normalizer.normalize("   ")).isEqualTo("{}");
        assertThat(normalizer.normalize("{}")).isEqualTo("{}");
    }

    @Test
    void unknownKeysAreDropped() throws Exception {
        String out = normalizer.normalize(
                "{\"country_iso2s\":[\"cn\"],\"evil_key\":1,\"rotation_status\":\"x\"}");

        JsonNode node = parse(out);
        assertThat(node.has("countryIso2s")).isTrue();
        assertThat(node.has("evil_key")).isFalse();
        // rotation_status 不在通讯录任务的透传白名单里（设计文档 §2.7）
        assertThat(node.has("rotationStatus")).isFalse();
    }

    @Test
    void countryCodesAreUpperCasedAndDeduplicated() throws Exception {
        String out = normalizer.normalize(
                "{\"country_iso2s\":[\"cn\",\"CN\",\"my\",\"\",null]}");

        JsonNode codes = parse(out).get("countryIso2s");
        assertThat(codes).hasSize(2);
        assertThat(codes.get(0).asText()).isEqualTo("CN");
        assertThat(codes.get(1).asText()).isEqualTo("MY");
    }

    @Test
    void idArraysDropNonPositiveAndDuplicates() throws Exception {
        String out = normalizer.normalize("{\"group_ids\":[3,3,0,-1,7]}");

        JsonNode ids = parse(out).get("groupIds");
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0).asLong()).isEqualTo(3L);
        assertThat(ids.get(1).asLong()).isEqualTo(7L);
    }

    @Test
    void nonPositiveRangeBoundsAreDropped() throws Exception {
        String out = normalizer.normalize(
                "{\"friend_count_min\":0,\"friend_count_max\":100,\"retention_days_min\":-3}");

        JsonNode node = parse(out);
        assertThat(node.has("friendCountMin")).isFalse();
        assertThat(node.get("friendCountMax").asInt()).isEqualTo(100);
        assertThat(node.has("retentionDaysMin")).isFalse();
    }

    @Test
    void emptyArraysAndBlankStringsAreDropped() throws Exception {
        String out = normalizer.normalize(
                "{\"group_ids\":[],\"phone\":\"  \",\"online_status\":\"online\"}");

        JsonNode node = parse(out);
        assertThat(node.has("groupIds")).isFalse();
        assertThat(node.has("phone")).isFalse();
        assertThat(node.get("onlineStatus").asText()).isEqualTo("online");
    }

    @Test
    void malformedJsonBecomesEmptyObjectInsteadOfThrowing() {
        // 归一化只做白名单收口，不做输入合法性抗辩：坏 JSON 等价于「不限定」
        assertThat(normalizer.normalize("not-json")).isEqualTo("{}");
        assertThat(normalizer.normalize("[1,2,3]")).isEqualTo("{}");
    }

    @Test
    void booleanFilterIsPreserved() throws Exception {
        String out = normalizer.normalize("{\"group_invite_allowed\":false}");

        assertThat(parse(out).get("groupInviteAllowed").asBoolean()).isFalse();
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactAccountFilterNormalizerTest
```

Expected: FAIL，`cannot find symbol: class ContactAccountFilterNormalizer`

- [ ] **Step 7: 写筛选归一化器**

`ContactAccountFilterNormalizer.java`：

```java
package com.armada.contact.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通讯录营销任务的账号筛选条件归一化器。
 *
 * <p>入库前按白名单收口：未知键丢弃、国家码大写、ID 去重、非正数下界剔除、空值剔除。
 * 白名单取自竞品任务页实际透传的键集（设计文档 §2.7）——注意 rotation_status 与
 * hyperlink_task_count 不在其中，通讯录任务不使用这两项。</p>
 *
 * <p>坏 JSON 归一为空对象而不是抛异常：筛选条件解析不了等价于「不限定」，
 * 让整个建任务请求因为一个筛选字段挂掉不划算。</p>
 */
@Component
public class ContactAccountFilterNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ContactAccountFilterNormalizer.class);

    private static final String EMPTY_OBJECT = "{}";

    /** 大写归一的国家码数组字段。 */
    private static final List<String> COUNTRY_ARRAY_KEYS =
            List.of("country_iso2s", "exclude_country_iso2s");

    /** 正整数 ID 数组字段。 */
    private static final List<String> ID_ARRAY_KEYS = List.of("group_ids", "channel_ids");

    /** 直接透传的字符串字段。 */
    private static final List<String> TEXT_KEYS = List.of(
            "continent", "online_status", "account_type", "platform", "wid_type",
            "phone", "error_code", "error_desc", "protocol_id",
            "created_at_from", "created_at_to", "logged_in_from", "logged_in_to");

    /** 必须为正数才保留的范围字段。 */
    private static final List<String> POSITIVE_NUMBER_KEYS = List.of(
            "friend_count_min", "friend_count_max",
            "retention_days_min", "retention_days_max",
            "register_days_min", "register_days_max");

    /** 布尔字段。 */
    private static final List<String> BOOLEAN_KEYS = List.of("group_invite_allowed");

    private final ObjectMapper objectMapper;

    /**
     * 创建筛选归一化器。
     *
     * @param objectMapper JSON 编解码器
     */
    public ContactAccountFilterNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把前端提交的筛选 JSON 归一为白名单内的 camelCase JSON。
     *
     * @param rawJson 原始 JSON 字符串，允许为 null 或非法
     * @return 归一后的 JSON 字符串，无有效条件时为 {@code {}}
     */
    public String normalize(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return EMPTY_OBJECT;
        }
        JsonNode source;
        try {
            source = objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            log.warn("账号筛选条件 JSON 解析失败,按不限定处理 errorType={}", ex.getClass().getSimpleName());
            return EMPTY_OBJECT;
        }
        if (source == null || !source.isObject()) {
            return EMPTY_OBJECT;
        }

        ObjectNode target = objectMapper.createObjectNode();
        for (String key : COUNTRY_ARRAY_KEYS) {
            Set<String> codes = new LinkedHashSet<>();
            for (JsonNode item : arrayOf(source, key)) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    codes.add(item.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
            if (!codes.isEmpty()) {
                target.putPOJO(camel(key), codes);
            }
        }
        for (String key : ID_ARRAY_KEYS) {
            Set<Long> ids = new LinkedHashSet<>();
            for (JsonNode item : arrayOf(source, key)) {
                if (item != null && item.canConvertToLong() && item.asLong() > 0) {
                    ids.add(item.asLong());
                }
            }
            if (!ids.isEmpty()) {
                target.putPOJO(camel(key), ids);
            }
        }
        for (String key : TEXT_KEYS) {
            JsonNode value = source.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                target.put(camel(key), value.asText().trim());
            }
        }
        for (String key : POSITIVE_NUMBER_KEYS) {
            JsonNode value = source.get(key);
            if (value != null && value.isNumber() && value.asLong() > 0) {
                target.put(camel(key), value.asLong());
            }
        }
        for (String key : BOOLEAN_KEYS) {
            JsonNode value = source.get(key);
            if (value != null && value.isBoolean()) {
                target.put(camel(key), value.asBoolean());
            }
        }
        return target.toString();
    }

    private static Iterable<JsonNode> arrayOf(JsonNode source, String key) {
        JsonNode value = source.get(key);
        return value != null && value.isArray() ? value : List.of();
    }

    /** snake_case 转 camelCase，落库字段统一按 armada 规范。 */
    private static String camel(String key) {
        StringBuilder builder = new StringBuilder(key.length());
        boolean upperNext = false;
        for (char ch : key.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return builder.toString();
    }
}
```

- [ ] **Step 8: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactAccountFilterNormalizerTest
```

Expected: PASS，8 个用例全绿

- [ ] **Step 9: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskFormValidatorTest.java
git add armada-api/src/test/java/com/armada/contact/task/ContactAccountFilterNormalizerTest.java
git commit -m "feat(contact): add task form validation and filter normalization"
```

---

### Task 4: 实体、Mapper 与 XML

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/model/entity/ContactFriendTask.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/model/entity/ContactFriendTaskAccount.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskMapper.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/mapper/ContactFriendTaskAccountMapper.java`
- Create: `armada-api/src/main/resources/mapper/contact/ContactFriendTaskMapper.xml`
- Create: `armada-api/src/main/resources/mapper/contact/ContactFriendTaskAccountMapper.xml`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskMapperXmlTest.java`

**Interfaces:**
- Consumes: Task 1 的表结构
- Produces:
  - `ContactFriendTaskMapper`：`insert`、`selectById`、`selectPage(query)`、`countPage(query)`、
    `updateForm`、`updateRunStatus(id, expectedRunStatus, nextRunStatus, updatedAt)`
  - `ContactFriendTaskAccountMapper`：`selectPage(taskId, sortBy, sortOrder, offset, limit)`、`countByTaskId(taskId)`

> **`ContactFriendTaskRecipient` 实体与 Mapper 属于 P3b**，本期不建——本期没有任何代码会写它。

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/contact/task/ContactTaskMapperXmlTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
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

/** 通讯录任务 Mapper XML 静态契约测试。本机无库，只校验契约。 */
class ContactTaskMapperXmlTest {

    private static String xml(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/contact/" + name), StandardCharsets.UTF_8);
    }

    private static Set<String> declaredMethods(Class<?> mapper) {
        return Arrays.stream(mapper.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void taskMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.contact.task.mapper.ContactFriendTaskMapper\"");
        for (String method : declaredMethods(ContactFriendTaskMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void accountMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.contact.task.mapper.ContactFriendTaskAccountMapper\"");
        for (String method : declaredMethods(ContactFriendTaskAccountMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void listQueryExcludesSoftDeletedTasks() throws IOException {
        assertThat(xml("ContactFriendTaskMapper.xml")).contains("deleted_at IS NULL");
    }

    @Test
    void runStatusUpdateIsGuardedByExpectedStatus() throws IOException {
        // 状态迁移必须条件更新，防止并发下两个动作互相覆盖
        assertThat(xml("ContactFriendTaskMapper.xml"))
                .contains("run_status = #{expectedRunStatus}");
    }

    @Test
    void accountSortColumnIsWhitelistedNotInterpolatedRaw() throws IOException {
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        // 排序列必须走 choose 白名单，不能把用户输入直接拼进 ORDER BY
        assertThat(sql).contains("<choose>");
        assertThat(sql).doesNotContain("ORDER BY ${");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskMapperXmlTest
```

Expected: FAIL，`package com.armada.contact.task.mapper does not exist`

- [ ] **Step 3: 写两个实体**

`ContactFriendTask` 的字段与类型逐列对应 Task 1 的 `contact_friend_task` 表，
`ContactFriendTaskAccount` 对应 `contact_friend_task_account` 表。
**getter/setter 的写法照 `com.armada.account.contact.model.entity.AccountContact`**
（P2 已落地，每个字段一段中文 Javadoc，每个 getter/setter 独立成行）。

`ContactFriendTask` 字段清单（Java 类型 / 列名）：

```
Long id / id
Long tenantId / tenant_id
String name / name
Integer messageType / message_type
String title / title
String description / description
String promotionLink / promotion_link
String content / content
Long previewImageFileId / preview_image_file_id
String accountFilter / account_filter
BigDecimal msgIntervalMinSec / msg_interval_min_sec
BigDecimal msgIntervalMaxSec / msg_interval_max_sec
Integer concurrency / concurrency
Integer maxSendsPerAccount / max_sends_per_account
Integer retryMax / retry_max
String startMode / start_mode
Integer taskDelayMinutes / task_delay_minutes
Long taskStartAt / task_start_at
Integer isEnabled / is_enabled
Integer runStatus / run_status
Long nextRoundAt / next_round_at
Integer totalSendNum / total_send_num
Integer successMessageNum / success_message_num
Integer usedAccountCount / used_account_count
Integer invalidAccountNum / invalid_account_num
BigDecimal avgSendPerAccount / avg_send_per_account
Long createdBy / created_by
Long createdAt / created_at
Long updatedAt / updated_at
Long deletedAt / deleted_at
```

`ContactFriendTaskAccount` 字段清单：

```
Long id / id
Long tenantId / tenant_id
Long taskId / task_id
Long accountId / account_id
String accountPhoneSnapshot / account_phone_snapshot
String accountStatusSnapshot / account_status_snapshot
Integer needSendNum / need_send_num
Integer sentNum / sent_num
Integer failNum / fail_num
String state / state
Long contactSyncedAt / contact_synced_at
Long createdAt / created_at
Long updatedAt / updated_at
```

- [ ] **Step 4: 写查询对象与两个 Mapper 接口**

`armada-api/src/main/java/com/armada/contact/task/model/dto/ContactTaskQuery.java`：

```java
package com.armada.contact.task.model.dto;

/**
 * 通讯录营销任务列表查询条件。
 *
 * @param name 任务名模糊匹配
 * @param runStatus 运行状态精确匹配
 * @param createdAtStart 创建时间起（epoch 毫秒）
 * @param createdAtEnd 创建时间止（epoch 毫秒）
 * @param page 页码，从 1 开始
 * @param pageSize 每页条数
 */
public record ContactTaskQuery(
        String name,
        Integer runStatus,
        Long createdAtStart,
        Long createdAtEnd,
        Integer page,
        Integer pageSize
) {

    /** 未传页码时默认第 1 页。 */
    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    /** 未传每页条数时默认 20，上限 200（与竞品分页选项一致）。 */
    public int pageSizeOrDefault() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }

    /** 下推数据库的偏移量。 */
    public int offset() {
        return (pageOrDefault() - 1) * pageSizeOrDefault();
    }
}
```

`ContactFriendTaskMapper.java`：

```java
package com.armada.contact.task.mapper;

import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.entity.ContactFriendTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 通讯录营销任务主表的数据访问。 */
@Mapper
public interface ContactFriendTaskMapper {

    /**
     * 插入任务并回填主键。
     *
     * @param task 任务行
     * @return 受影响行数
     */
    int insert(ContactFriendTask task);

    /**
     * 按主键读取未软删任务。
     *
     * @param id 任务 ID
     * @return 任务行，不存在或已软删时为 null
     */
    ContactFriendTask selectById(@Param("id") Long id);

    /**
     * 分页查询任务列表。
     *
     * @param query 查询条件
     * @return 当前页任务行
     */
    List<ContactFriendTask> selectPage(@Param("query") ContactTaskQuery query);

    /**
     * 统计查询条件命中的任务总数。
     *
     * @param query 查询条件
     * @return 总数
     */
    long countPage(@Param("query") ContactTaskQuery query);

    /**
     * 更新任务表单字段。仅未开始任务允许调用，调用方负责状态校验。
     *
     * @param task 任务行，需带 id 与全部表单字段
     * @return 受影响行数
     */
    int updateForm(ContactFriendTask task);

    /**
     * 条件更新任务运行状态，防止并发动作互相覆盖。
     *
     * @param id 任务 ID
     * @param expectedRunStatus 期望的当前运行状态
     * @param nextRunStatus 目标运行状态
     * @param nextRoundAt 下一轮调度时间，可为 null
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 实际更新行数，0 表示状态已被并发改变
     */
    int updateRunStatus(@Param("id") Long id,
                        @Param("expectedRunStatus") int expectedRunStatus,
                        @Param("nextRunStatus") int nextRunStatus,
                        @Param("nextRoundAt") Long nextRoundAt,
                        @Param("updatedAt") long updatedAt);
}
```

`ContactFriendTaskAccountMapper.java`：

```java
package com.armada.contact.task.mapper;

import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 通讯录营销任务账号维度读模型的数据访问。 */
@Mapper
public interface ContactFriendTaskAccountMapper {

    /**
     * 分页查询任务的账号发送数据。
     *
     * @param taskId 任务 ID
     * @param sortBy 排序列，仅接受 needSendNum / sentNum / failNum，其余按 id
     * @param sortOrder 排序方向，asc 或 desc
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页账号行
     */
    List<ContactFriendTaskAccount> selectPage(@Param("taskId") Long taskId,
                                              @Param("sortBy") String sortBy,
                                              @Param("sortOrder") String sortOrder,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    /**
     * 统计任务下账号行总数。
     *
     * @param taskId 任务 ID
     * @return 总数
     */
    long countByTaskId(@Param("taskId") Long taskId);
}
```

- [ ] **Step 5: 写两个 Mapper XML**

`armada-api/src/main/resources/mapper/contact/ContactFriendTaskMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.contact.task.mapper.ContactFriendTaskMapper">

  <sql id="taskColumns">
    id, tenant_id, name, message_type, title, description, promotion_link, content,
    preview_image_file_id, account_filter, msg_interval_min_sec, msg_interval_max_sec,
    concurrency, max_sends_per_account, retry_max, start_mode, task_delay_minutes,
    task_start_at, is_enabled, run_status, next_round_at, total_send_num,
    success_message_num, used_account_count, invalid_account_num, avg_send_per_account,
    created_by, created_at, updated_at, deleted_at
  </sql>

  <sql id="listConditions">
    AND deleted_at IS NULL
    <if test="query.name != null and query.name != ''">
      AND name LIKE CONCAT('%', #{query.name}, '%')
    </if>
    <if test="query.runStatus != null">
      AND run_status = #{query.runStatus}
    </if>
    <if test="query.createdAtStart != null">
      AND created_at &gt;= #{query.createdAtStart}
    </if>
    <if test="query.createdAtEnd != null">
      AND created_at &lt;= #{query.createdAtEnd}
    </if>
  </sql>

  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO contact_friend_task (
      tenant_id, name, message_type, title, description, promotion_link, content,
      preview_image_file_id, account_filter, msg_interval_min_sec, msg_interval_max_sec,
      concurrency, max_sends_per_account, retry_max, start_mode, task_delay_minutes,
      task_start_at, is_enabled, run_status, created_by, created_at, updated_at
    ) VALUES (
      #{tenantId}, #{name}, #{messageType}, #{title}, #{description}, #{promotionLink}, #{content},
      #{previewImageFileId}, #{accountFilter}, #{msgIntervalMinSec}, #{msgIntervalMaxSec},
      #{concurrency}, #{maxSendsPerAccount}, #{retryMax}, #{startMode}, #{taskDelayMinutes},
      #{taskStartAt}, #{isEnabled}, #{runStatus}, #{createdBy}, #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectById" resultType="com.armada.contact.task.model.entity.ContactFriendTask">
    SELECT <include refid="taskColumns"/>
      FROM contact_friend_task
     WHERE id = #{id}
       AND deleted_at IS NULL
  </select>

  <select id="selectPage" resultType="com.armada.contact.task.model.entity.ContactFriendTask">
    SELECT <include refid="taskColumns"/>
      FROM contact_friend_task
     WHERE 1 = 1
     <include refid="listConditions"/>
     ORDER BY created_at DESC, id DESC
     LIMIT #{query.offset}, #{query.pageSizeOrDefault}
  </select>

  <select id="countPage" resultType="long">
    SELECT COUNT(*)
      FROM contact_friend_task
     WHERE 1 = 1
     <include refid="listConditions"/>
  </select>

  <update id="updateForm">
    UPDATE contact_friend_task
       SET name = #{name},
           title = #{title},
           description = #{description},
           promotion_link = #{promotionLink},
           content = #{content},
           preview_image_file_id = #{previewImageFileId},
           account_filter = #{accountFilter},
           msg_interval_min_sec = #{msgIntervalMinSec},
           msg_interval_max_sec = #{msgIntervalMaxSec},
           concurrency = #{concurrency},
           max_sends_per_account = #{maxSendsPerAccount},
           retry_max = #{retryMax},
           start_mode = #{startMode},
           task_delay_minutes = #{taskDelayMinutes},
           task_start_at = #{taskStartAt},
           is_enabled = #{isEnabled},
           updated_at = #{updatedAt}
     WHERE id = #{id}
       AND deleted_at IS NULL
  </update>

  <!-- 条件更新：run_status 必须仍等于期望值，否则本次动作作废。 -->
  <update id="updateRunStatus">
    UPDATE contact_friend_task
       SET run_status = #{nextRunStatus},
           next_round_at = #{nextRoundAt},
           updated_at = #{updatedAt}
     WHERE id = #{id}
       AND deleted_at IS NULL
       AND run_status = #{expectedRunStatus}
  </update>

</mapper>
```

`armada-api/src/main/resources/mapper/contact/ContactFriendTaskAccountMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.contact.task.mapper.ContactFriendTaskAccountMapper">

  <select id="selectPage"
          resultType="com.armada.contact.task.model.entity.ContactFriendTaskAccount">
    SELECT id, tenant_id, task_id, account_id, account_phone_snapshot,
           account_status_snapshot, need_send_num, sent_num, fail_num, state,
           contact_synced_at, created_at, updated_at
      FROM contact_friend_task_account
     WHERE task_id = #{taskId}
     ORDER BY
    <choose>
      <when test="sortBy == 'needSendNum'">need_send_num</when>
      <when test="sortBy == 'sentNum'">sent_num</when>
      <when test="sortBy == 'failNum'">fail_num</when>
      <otherwise>id</otherwise>
    </choose>
    <choose>
      <when test="sortOrder == 'asc'">ASC</when>
      <otherwise>DESC</otherwise>
    </choose>
    , id ASC
     LIMIT #{offset}, #{limit}
  </select>

  <select id="countByTaskId" resultType="long">
    SELECT COUNT(*)
      FROM contact_friend_task_account
     WHERE task_id = #{taskId}
  </select>

</mapper>
```

- [ ] **Step 6: 确认 MyBatis 会扫描到新目录**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
grep -rn "mapper-locations\|mapperLocations" src/main/resources/application*.yml src/main/java --include="*.java" | head -5
```

若配置是 `classpath*:mapper/**/*.xml` 之类的通配，新目录自动生效，无需改动。
若是逐个目录枚举，把 `mapper/contact` 加进去。

- [ ] **Step 7: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskMapperXmlTest
```

Expected: PASS，5 个用例全绿

- [ ] **Step 8: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/
git add armada-api/src/main/resources/mapper/contact/
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskMapperXmlTest.java
git commit -m "feat(contact): add contact task persistence layer"
```

---

### Task 5: `ContactTaskService` 与 6 个接口

**Files:**
- Create: `armada-api/src/main/java/com/armada/contact/task/model/vo/ContactTaskListItemVO.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/model/vo/ContactTaskDetailVO.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/model/vo/ContactTaskAccountItemVO.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/service/ContactTaskService.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/service/impl/ContactTaskServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/contact/task/controller/ContactTaskController.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 状态机、Task 3 校验器与归一化器、Task 4 两个 Mapper
- Produces: 6 个接口，见 Step 5

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/contact/task/ContactTaskServiceImplTest.java`：

```java
package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.service.ContactAccountFilterNormalizer;
import com.armada.contact.task.service.ContactTaskFormValidator;
import com.armada.contact.task.service.impl.ContactTaskServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactTaskServiceImplTest {

    private static final long NOW = 1_756_345_678_901L;
    private static final long TENANT = 1L;
    private static final long USER = 88L;

    private ContactFriendTaskMapper taskMapper;
    private ContactFriendTaskAccountMapper accountMapper;
    private ContactTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(ContactFriendTaskMapper.class);
        accountMapper = mock(ContactFriendTaskAccountMapper.class);
        service = new ContactTaskServiceImpl(
                taskMapper,
                accountMapper,
                new ContactTaskFormValidator(),
                new ContactAccountFilterNormalizer(new ObjectMapper()),
                () -> TENANT,
                () -> NOW);
    }

    private static ContactTaskFormDTO form(String startMode, int delay, int enabled) {
        return new ContactTaskFormDTO(
                "任务A", 1, null, null, null, "文案",
                new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, startMode, delay, enabled, "{\"country_iso2s\":[\"cn\"]}");
    }

    private static ContactFriendTask task(int runStatus) {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(9L);
        task.setTenantId(TENANT);
        task.setRunStatus(runStatus);
        task.setMessageType(1);
        return task;
    }

    @Test
    void createPersistsNormalizedFilterAndTenantAndCreator() {
        service.create(form("now", 0, 0), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        ContactFriendTask row = saved.getValue();
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getCreatedBy()).isEqualTo(USER);
        assertThat(row.getRunStatus()).isEqualTo(ContactTaskRunStatus.NOT_STARTED.code());
        // 筛选条件必须是归一化后的 camelCase 白名单 JSON
        assertThat(row.getAccountFilter()).contains("countryIso2s").contains("CN");
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void scheduledEnabledTaskGetsComputedStartTime() {
        service.create(form("scheduled", 30, 1), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        assertThat(saved.getValue().getTaskStartAt()).isEqualTo(NOW + 30 * 60_000L);
    }

    @Test
    void immediateTaskStartsNow() {
        service.create(form("now", 0, 1), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        assertThat(saved.getValue().getTaskStartAt()).isEqualTo(NOW);
    }

    @Test
    void disabledTaskHasNoStartTime() {
        service.create(form("now", 0, 0), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        assertThat(saved.getValue().getTaskStartAt()).isNull();
    }

    @Test
    void updateRejectsStartedTasks() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.RUNNING.code()));

        assertThatThrownBy(() -> service.update(9L, form("now", 0, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已开始");

        verify(taskMapper, never()).updateForm(any());
    }

    @Test
    void updateRejectsMessageTypeChange() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));

        ContactTaskFormDTO changed = new ContactTaskFormDTO(
                "任务A", 0, "标题", "描述", "https://a.com", "文案",
                new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, "now", 0, 0, "{}");

        assertThatThrownBy(() -> service.update(9L, changed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息类型");
    }

    @Test
    void updateAcceptsNotStartedTaskWithSameMessageType() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));
        when(taskMapper.updateForm(any())).thenReturn(1);

        service.update(9L, form("now", 0, 0));

        verify(taskMapper).updateForm(any());
    }

    @Test
    void detailAndUpdateRejectMissingTask() {
        when(taskMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(404L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(404L, form("now", 0, 0)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void startMovesNotStartedToRunning() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));
        when(taskMapper.updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(1);

        service.action(9L, "start");

        verify(taskMapper).updateRunStatus(
                9L,
                ContactTaskRunStatus.NOT_STARTED.code(),
                ContactTaskRunStatus.RUNNING.code(),
                NOW,
                NOW);
    }

    @Test
    void stopFromPausedIsAllowedAndClearsNextRound() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.PAUSED.code()));
        when(taskMapper.updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(1);

        service.action(9L, "stop");

        verify(taskMapper).updateRunStatus(
                9L,
                ContactTaskRunStatus.PAUSED.code(),
                ContactTaskRunStatus.STOPPED.code(),
                null,
                NOW);
    }

    @Test
    void illegalTransitionIsRejectedBeforeTouchingTheDatabase() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.STOPPED.code()));

        assertThatThrownBy(() -> service.action(9L, "resume"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许");

        verify(taskMapper, never()).updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong());
    }

    @Test
    void concurrentStatusChangeIsReportedAsConflict() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.RUNNING.code()));
        // 条件更新命中 0 行 = 状态已被别的请求改掉
        when(taskMapper.updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.action(9L, "pause"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态已变更");
    }

    @Test
    void unknownActionIsRejected() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.RUNNING.code()));

        assertThatThrownBy(() -> service.action(9L, "delete"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accountDataPageIsEmptyUntilTheEngineExpandsIt() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));
        when(accountMapper.countByTaskId(9L)).thenReturn(0L);
        when(accountMapper.selectPage(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(java.util.List.of());

        assertThat(service.accountData(9L, null, null, 1, 20).getTotal()).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskServiceImplTest
```

Expected: FAIL，`cannot find symbol: class ContactTaskServiceImpl`

- [ ] **Step 3: 确认既有响应壳的实际形状**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
grep -n "class PageResult\|record PageResult\|public .*getTotal\|static .*PageResult" \
  $(grep -rl "class PageResult\|record PageResult" src/main/java | head -1)
```

按查到的真实构造方式创建 `PageResult`，不要臆造 `new PageResult<>(list, total)`。
`ContactTaskServiceImplTest` 里用的是 `getTotal()`，若实际是 record 的 `total()`，把测试改成 `total()`。

- [ ] **Step 4: 写 VO、Service 与实现**

三个 VO 按下列字段定义为 record（全部 camelCase，与前端映射层对齐）：

`ContactTaskListItemVO`：`id, name, messageType, title, promotionLink, accountFilter, isEnabled,
runStatus, totalSendNum, successMessageNum, usedAccountCount, invalidAccountNum,
avgSendPerAccount, taskStartAt, createdAt`

`ContactTaskDetailVO`：在列表字段基础上加 `description, content, previewImageFileId,
msgIntervalMinSec, msgIntervalMaxSec, concurrency, maxSendsPerAccount, retryMax,
startMode, taskDelayMinutes, updatedAt`

`ContactTaskAccountItemVO`：`accountId, accountPhone, accountStatus, needSendNum, sentNum, failNum`

`ContactTaskService` 接口方法：

```java
PageResult<ContactTaskListItemVO> list(ContactTaskQuery query);
ContactTaskDetailVO detail(Long id);
ContactTaskDetailVO create(ContactTaskFormDTO form, Long createdBy);
ContactTaskDetailVO update(Long id, ContactTaskFormDTO form);
void action(Long id, String action);
PageResult<ContactTaskAccountItemVO> accountData(
        Long id, String sortBy, String sortOrder, Integer page, Integer pageSize);
```

`ContactTaskServiceImpl` 的关键实现约束（测试已覆盖，逐条实现）：

1. 构造参数为 `(taskMapper, accountMapper, validator, filterNormalizer, Supplier<Long> tenantSupplier, LongSupplier clock)`。
   **不标注 `@Service`**，由配置类装配——理由与 P2 的 `AccountContactSyncServiceImpl` 相同。
2. `create`：先 `validator.validate(form)`，再 `filterNormalizer.normalize(form.accountFilterJson())`，
   `runStatus` 恒为 `NOT_STARTED`，`tenantId` 与 `createdAt/updatedAt` 由 supplier 提供。
   `taskStartAt` 计算规则：`isEnabled=0` → null；`now` 模式 → `now`；`scheduled` 模式 → `now + delay*60000`。
3. `update`：`selectById` 为 null 抛 `NOT_FOUND`；
   `ContactTaskStateMachine.isEditable` 为 false 抛 `CONFLICT`，消息含「已开始」；
   `form.messageType()` 与库中不一致抛 `VALIDATION`，消息含「消息类型」。
4. `action`：`ContactTaskAction.fromWire` 解析（非法抛 `IllegalArgumentException`，
   在 Service 里捕获转成 `BusinessException(VALIDATION)`）；
   `ContactTaskStateMachine.next` 为空抛 `CONFLICT`，消息含「不允许」；
   `updateRunStatus` 返回 0 抛 `CONFLICT`，消息含「状态已变更」。
   `nextRoundAt` 取值：目标为 `RUNNING` 时传 `now`，其余传 `null`。
5. `accountData`：`selectById` 为 null 抛 `NOT_FOUND`；`sortBy` 只透传
   `needSendNum` / `sentNum` / `failNum`，其余传 null；`sortOrder` 只透传 `asc`，其余传 `desc`。

- [ ] **Step 5: 写 Controller**

`armada-api/src/main/java/com/armada/contact/task/controller/ContactTaskController.java`，
范式照 `com.armada.hyperlink.template.controller.HyperlinkTemplateController`
（类级 `@PreAuthorize` 管 view，写操作方法级再加）：

```
@RestController
@RequestMapping("/api/contact-tasks")
@PreAuthorize("hasAuthority('tenant:contact_task:view')")

GET    ""            list(@ModelAttribute ContactTaskQuery query)
GET    "/{id}"       detail(@PathVariable Long id)
POST   ""            create(...)   @PreAuthorize("hasAuthority('tenant:contact_task:create')")
PUT    "/{id}"       update(...)   @PreAuthorize("hasAuthority('tenant:contact_task:edit')")
POST   "/{id}/action" action(...)  @PreAuthorize("hasAuthority('tenant:contact_task:operate')")
GET    "/{id}/data"  accountData(...)
```

创建人取 `@AuthenticationPrincipal AuthPrincipal principal`，与模板控制器一致。

**不提供 `DELETE`**——竞品没有删除接口。

> 创建与编辑接口竞品用的是 `multipart/form-data`（因为要带预览图）。
> **本期先只接 JSON body，`previewImageFileId` 由调用方传已上传文件的 ID。**
> 图片上传复用既有 `MarketingTemplateFileService`，接线放到 P4 前端期一起做，
> 那时才知道前端实际怎么传。这条差异写进接口 Javadoc。

- [ ] **Step 6: 写装配配置**

`armada-api/src/main/java/com/armada/contact/task/config/ContactTaskConfiguration.java`，
声明 `ContactTaskService` bean，把 `TenantContext::get` 与 `System::currentTimeMillis` 装进去。
范式照 P2 的 `AccountContactConfiguration`。

- [ ] **Step 7: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactTaskServiceImplTest
```

Expected: PASS，14 个用例全绿

- [ ] **Step 8: 全量回归**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test
```

Expected: `Failures: 7`、`Errors: 461` 与基线一致，`Tests run` 增加本计划新增用例数。
**若 Failures / Errors 增长，先 grep 新增 Controller 是否触发了既有的权限契约测试
（`BusinessControllerAuthorizationContractTest` 会校验所有 Controller 都带鉴权注解）。**

- [ ] **Step 9: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskServiceImplTest.java
git commit -m "feat(contact): add contact task crud and lifecycle api"
```

---

### Task 6: `V159` 菜单与权限

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V164__contact_marketing_menu_rbac.sql`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactMenuRbacMigrationSqlTest.java`

**Interfaces:**
- Consumes: 无
- Produces: 目录 `ContactMarketing` + 两个页面节点 + 四个权限节点

- [ ] **Step 1: 读 `V155` 的写法**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
cat src/main/resources/db/migration/V155__hyperlink_marketing_menu_rbac.sql
```

本任务照抄该脚本结构，只替换菜单键、路由、组件路径与权限键。

- [ ] **Step 2: 写失败测试**

创建 `armada-api/src/test/java/com/armada/contact/task/ContactMenuRbacMigrationSqlTest.java`：

```java
package com.armada.contact.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 通讯录营销菜单与权限 Flyway 脚本契约测试。 */
class ContactMenuRbacMigrationSqlTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V164__contact_marketing_menu_rbac.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void createsDirectoryAndTwoPages() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("'通讯录营销'")
                .contains("'ContactMarketing'")
                .contains("'/contact'")
                .contains("'通讯录超链任务'")
                .contains("'/contact/hyperlink'")
                .contains("'通讯录剧本任务'")
                .contains("'/contact/script'");
    }

    @Test
    void declaresFourPermissionKeys() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("tenant:contact_task:view")
                .contains("tenant:contact_task:create")
                .contains("tenant:contact_task:edit")
                .contains("tenant:contact_task:operate");
    }

    @Test
    void declaresNoDeletePermission() throws IOException {
        // 竞品没有删除任务的能力，权限节点也不该有
        assertThat(sql()).doesNotContain("tenant:contact_task:delete");
    }

    @Test
    void insertsAreIdempotent() throws IOException {
        // 与 V155 同一策略：INSERT IGNORE，重复执行不炸
        assertThat(sql()).contains("INSERT IGNORE INTO sys_menu");
    }

    @Test
    void doesNotAutoGrantToOrdinaryRoles() throws IOException {
        // V155 的既有结论：迁移只建节点，授权由管理员显式配置
        assertThat(sql()).doesNotContain("INSERT INTO sys_role_menu");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactMenuRbacMigrationSqlTest
```

Expected: FAIL，`NoSuchFileException`

- [ ] **Step 4: 写迁移**

照 Step 1 读到的 `V155` 结构写 `V164__contact_marketing_menu_rbac.sql`：
目录 `ContactMarketing`（`/contact`，图标 `ep:phone`，`sort_no` 取 56，排在超链营销 55 之后），
两个页面节点 `ContactHyperlinkTask`（`/contact/hyperlink`，组件 `contact/hyperlink/index`，
权限 `tenant:contact_task:view`，`sort_no` 10）与 `ContactScriptTask`（`/contact/script`，
组件 `contact/script/index`，权限 `tenant:contact_task:view`，`sort_no` 20），
以及四个按钮权限节点。**只建节点，不写 `sys_role_menu`。**

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactMenuRbacMigrationSqlTest
```

Expected: PASS，5 个用例全绿

- [ ] **Step 6: 全量回归并提交**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/resources/db/migration/V164__contact_marketing_menu_rbac.sql
git add armada-api/src/test/java/com/armada/contact/task/ContactMenuRbacMigrationSqlTest.java
git commit -m "feat(contact): add contact marketing menu and permissions"
```

---

## 出口条件与遗留

### 本期交付后可以做到

- 建、查、改通讯录营销任务，四个动作（启动/暂停/恢复/停止）状态机完整
- 菜单与权限节点就位，前端可以开始接
- 账号数据接口可用，但返回空页（表还没人写）

### 本期**做不到**（P3b 的范围）

- 任务不会真的发消息：没有账号圈选、没有 recipient 展开、没有轮次调度、没有回执回写
- `total_send_num` / `used_account_count` 等汇总列恒为 0

### 本地不可验证，必须在有库环境补

| # | 项 |
|---|---|
| 1 | `V158` / `V159` 能否跑通 Flyway |
| 2 | 列表分页、模糊查询、时间区间的真实 SQL 行为 |
| 3 | `updateRunStatus` 条件更新在并发下的实际效果 |
| 4 | 租户拦截器是否正确注入三张新表 |
| 5 | 新 Controller 是否被既有权限契约测试正确覆盖 |
