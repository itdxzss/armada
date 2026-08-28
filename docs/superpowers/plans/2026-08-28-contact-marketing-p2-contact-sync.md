# 通讯录营销 P2 通讯录采集与快照 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 armada 持久化每个账号的 WhatsApp 通讯录快照，并把「有名字联系人数」「双向好友数」两个计数回流到 `account_state`，供账号筛选下推与后续任务展开使用。

**Architecture:** 三张写入面。`account_contact` 存联系人快照行（整批替换语义），`account_contact_sync` 存每账号一行的同步状态，`account_state` 增两列冗余计数只服务 SQL 下推。采集通过 P0 落地的 `ContactListPort` 同步 HTTP 拉取，触发点有两个：账号 `ONLINE` 状态事件（走独立附属任务线程池）和 P3 任务启动时的 TTL 判定。

**Tech Stack:** Java 17 / Spring Boot / MyBatis-Plus / Flyway / JUnit 5 + Mockito + AssertJ

**Spec:** `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md`（§4.1、§6.1、§9 P2）

**上游计划:** `docs/superpowers/plans/2026-08-28-contact-marketing-p0p1-protocol.md`（已完成，提供 `ContactListPort` 与 `AccountContactSnapshot`）

## Global Constraints

- 工作分支 `feat/contact-marketing`（armada 仓）。本计划只动 `armada`，不碰两个协议仓。
- Flyway **从 `V157` 起**。`V156` 已被 `hyperlink_data_package_full_replication` 占用。新列必须带 `COMMENT`。
- **每张新表必须有 `tenant_id` 列。** MyBatis-Plus `TenantLineInnerInterceptor` 会给所有不在
  `MyBatisConfig.IGNORED_TABLES` 里的表自动注入 `AND tenant_id = ?`；没有该列的表会抛 Unknown column。
  不要把新表加进忽略名单。
- 所有新增 Java 类、public 方法必须有中文 Javadoc。
- 联系人号码统一为**不带加号的纯数字**，JID 统一为 `<phone>@s.whatsapp.net`。协议 adapter 层
  （P0 已完成）已做过一次归一，本层只做去重与空值裁剪，不重复解析 JID。
- **跑测试：`cd armada-api && mvn -o test`**。armada 根目录没有聚合 pom，`mvn -pl armada-api` 会失败。
  全量超过 120 秒，注意超时设置。
- **本机无法跑 `*DbTest`**：缺 `armada-api/.env`（gitignore）里的库凭据，93 个 DbTest 会因
  `Unknown database 'armada'` 秒挂，Spring 上下文起不来。因此**本计划的可测逻辑必须放在纯类里**
  （归一化、TTL 判定），Service 层用 Mockito，迁移用 SQL 文本契约测试。
- armada 既有失败基线（与本计划无关，不要试图修）：全量 `Tests run: 3437, Failures: 7, Errors: 461`，
  其中 461 全是 DbTest 环境问题。
- **`is_mutual` / `contact_mutual_num` 先建列但恒为 0。** 真机验证 V2（两套协议是否拿得到双向好友
  标记）未完成，`AccountContactSnapshot.Contact` 没有对应字段。**不要为了让它有值去猜字段**。
  验证通过后单独一期回填，届时只需改归一化器一处。

---

### Task 1: `V157` 迁移与 SQL 契约测试

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V157__account_contact_sync.sql`
- Test: `armada-api/src/test/java/com/armada/account/contact/AccountContactMigrationSqlTest.java`

**Interfaces:**
- Consumes: 无
- Produces: 表 `account_contact`、`account_contact_sync`；`account_state` 新增列
  `contact_named_num`、`contact_mutual_num`

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/account/contact/AccountContactMigrationSqlTest.java`：

```java
package com.armada.account.contact;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 账号通讯录快照 Flyway 脚本契约测试。 */
class AccountContactMigrationSqlTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V157__account_contact_sync.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void createsBothTablesEachCarryingTenantColumn() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS account_contact")
                .contains("CREATE TABLE IF NOT EXISTS account_contact_sync");

        String[] parts = sql.split("CREATE TABLE IF NOT EXISTS account_contact_sync");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).contains("tenant_id BIGINT NOT NULL");
        assertThat(parts[1]).contains("tenant_id BIGINT NOT NULL");
    }

    @Test
    void contactTableIsUniquePerAccountAndPhone() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_account_contact (tenant_id, account_id, contact_phone)")
                .contains("KEY idx_account_contact_named (tenant_id, account_id, is_named)")
                .contains("KEY idx_account_contact_sweep (tenant_id, account_id, synced_at)");
    }

    @Test
    void syncTableIsOneRowPerAccount() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_account_contact_sync (tenant_id, account_id)");
    }

    @Test
    void addsTwoIdempotentGuardedColumnsToAccountState() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("table_name = 'account_state'")
                .contains("column_name = 'contact_named_num'")
                .contains("column_name = 'contact_mutual_num'")
                .contains("ADD COLUMN contact_named_num INT NOT NULL DEFAULT 0")
                .contains("ADD COLUMN contact_mutual_num INT NOT NULL DEFAULT 0");
    }

    @Test
    void everyColumnDefinitionCarriesComment() throws IOException {
        java.util.List<String> uncommented = sql().lines()
                .map(String::trim)
                .filter(line -> line.matches("^[a-z_]+ (BIGINT|INT|VARCHAR|CHAR|TINYINT).*"))
                .filter(line -> !line.contains("COMMENT"))
                .toList();

        assertThat(uncommented).as("这些列缺 COMMENT").isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactMigrationSqlTest
```

Expected: FAIL，`NoSuchFileException` 指向 `V157__account_contact_sync.sql`

- [ ] **Step 3: 写迁移**

创建 `armada-api/src/main/resources/db/migration/V157__account_contact_sync.sql`：

```sql
-- 账号通讯录快照与同步状态。
-- 快照是账号资产，不属于通讯录营销一个业务：超链任务的「双向好友数」筛选也读这份数据。
-- 写入语义是整批替换：一次成功同步先 upsert 本批号码，再删除 synced_at 更早的残留行。
-- 同步失败时不动任何已有数据，只在 account_contact_sync 记 FAILED。

CREATE TABLE IF NOT EXISTS account_contact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '联系人快照主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT '所属账号ID',
    contact_phone VARCHAR(32) NOT NULL COMMENT '联系人号码;不带加号的纯数字',
    contact_jid VARCHAR(64) NOT NULL COMMENT '联系人JID;phone@s.whatsapp.net',
    full_name VARCHAR(128) DEFAULT NULL COMMENT '通讯录全名;Web协议无此概念时为NULL',
    first_name VARCHAR(128) DEFAULT NULL COMMENT '通讯录名;Web协议恒为NULL',
    push_name VARCHAR(128) DEFAULT NULL COMMENT '对方设置的展示名',
    business_name VARCHAR(128) DEFAULT NULL COMMENT '商业号认证名;Web侧取verifiedName',
    is_named TINYINT NOT NULL DEFAULT 0 COMMENT '通讯录里是否有名字:1有 0无;竞品好友数口径',
    is_mutual TINYINT NOT NULL DEFAULT 0 COMMENT '是否双向好友:1是 0否;两套协议暂不暴露该标记恒为0',
    synced_at BIGINT NOT NULL COMMENT '本行所属同步批次时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_contact (tenant_id, account_id, contact_phone),
    KEY idx_account_contact_named (tenant_id, account_id, is_named),
    KEY idx_account_contact_sweep (tenant_id, account_id, synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号通讯录联系人快照';

CREATE TABLE IF NOT EXISTS account_contact_sync (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '同步状态主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT '账号ID;一账号一行',
    last_synced_at BIGINT DEFAULT NULL COMMENT '最近一次成功同步时间(epoch毫秒);从未成功为NULL',
    last_sync_source VARCHAR(32) DEFAULT NULL COMMENT '最近一次触发来源:ONLINE_EVENT TASK_START MANUAL',
    contact_num INT NOT NULL DEFAULT 0 COMMENT '最近一次成功同步到的联系人总数',
    named_num INT NOT NULL DEFAULT 0 COMMENT '其中通讯录有名字的数量',
    mutual_num INT NOT NULL DEFAULT 0 COMMENT '其中双向好友数量;当前恒为0',
    sync_status VARCHAR(16) NOT NULL DEFAULT 'NEVER' COMMENT '同步状态:NEVER SYNCING SUCCESS FAILED',
    fail_reason VARCHAR(255) DEFAULT NULL COMMENT '最近一次失败原因;成功时置空',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_contact_sync (tenant_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号通讯录同步状态';

-- account_state 两列冗余计数只服务账号筛选的 SQL 下推（好友数 >= / <=）。
-- 写入点唯一：AccountContactSyncService 同步成功时与 account_contact_sync 一并更新。
-- 任何其他地方不得直写这两列，否则筛选口径会分裂。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_state'
       AND column_name = 'contact_named_num') = 0,
    'ALTER TABLE account_state ADD COLUMN contact_named_num INT NOT NULL DEFAULT 0 COMMENT ''通讯录有名字联系人数;仅供筛选下推,由通讯录同步服务唯一写入''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_state'
       AND column_name = 'contact_mutual_num') = 0,
    'ALTER TABLE account_state ADD COLUMN contact_mutual_num INT NOT NULL DEFAULT 0 COMMENT ''双向好友数;仅供筛选下推,协议暂不暴露该标记时恒为0''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactMigrationSqlTest
```

Expected: PASS，5 个用例全绿

- [ ] **Step 5: 确认 Flyway 序号无冲突**

```bash
ls /home/yanwenchao/ideaProject/armada/armada-api/src/main/resources/db/migration | sort -V | tail -3
```

Expected: 最后三个是 `V155__...`、`V156__...`、`V157__account_contact_sync.sql`。
若 `V157` 已被别人占用，改成当前最大号 +1，并同步改测试里的路径常量。

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/resources/db/migration/V157__account_contact_sync.sql
git add armada-api/src/test/java/com/armada/account/contact/AccountContactMigrationSqlTest.java
git commit -m "feat(contact): add account contact snapshot schema"
```

---

### Task 2: 联系人归一化纯函数

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/contact/model/NormalizedContacts.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/service/AccountContactNormalizer.java`
- Test: `armada-api/src/test/java/com/armada/account/contact/AccountContactNormalizerTest.java`

**Interfaces:**
- Consumes: P0 的 `com.armada.platform.protocol.model.result.AccountContactSnapshot`
- Produces:
  - `record NormalizedContacts(List<Row> rows, int contactNum, int namedNum, int mutualNum)`，
    内嵌 `record Row(String phone, String jid, String fullName, String firstName, String pushName, String businessName, boolean named, boolean mutual)`
  - `AccountContactNormalizer.normalize(AccountContactSnapshot)` → `NormalizedContacts`

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/account/contact/AccountContactNormalizerTest.java`：

```java
package com.armada.account.contact;

import com.armada.account.contact.model.NormalizedContacts;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountContactNormalizerTest {

    private final AccountContactNormalizer normalizer = new AccountContactNormalizer();

    private static AccountContactSnapshot.Contact contact(
            String phone, String fullName, String firstName, String pushName, String businessName) {
        return new AccountContactSnapshot.Contact(
                phone,
                phone == null ? null : phone + "@s.whatsapp.net",
                fullName, firstName, pushName, businessName);
    }

    @Test
    void countsNamedContactsByFullNameOrFirstNameOnly() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", "张三", null, null, null),
                contact("8613800000002", null, "三", null, null),
                contact("8613800000003", null, null, "zhangsan", null),
                contact("8613800000004", null, null, null, null)
        ), 1L));

        assertThat(result.contactNum()).isEqualTo(4);
        // pushName 是对方自己设的展示名，不算「通讯录里有名字」
        assertThat(result.namedNum()).isEqualTo(2);
    }

    @Test
    void mutualCountIsAlwaysZeroUntilProtocolExposesTheFlag() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", "张三", null, null, null)
        ), 1L));

        assertThat(result.mutualNum()).isZero();
        assertThat(result.rows().get(0).mutual()).isFalse();
    }

    @Test
    void deduplicatesByPhoneMergingNonBlankFields() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", null, null, "zhangsan", null),
                contact("8613800000001", "张三", null, null, "某商铺")
        ), 1L));

        assertThat(result.contactNum()).isEqualTo(1);
        NormalizedContacts.Row row = result.rows().get(0);
        assertThat(row.fullName()).isEqualTo("张三");
        assertThat(row.pushName()).isEqualTo("zhangsan");
        assertThat(row.businessName()).isEqualTo("某商铺");
        assertThat(row.named()).isTrue();
    }

    @Test
    void dropsRowsWithoutUsableDigitsOnlyPhone() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact(null, "无号码", null, null, null),
                contact("   ", "空白号码", null, null, null),
                contact("86abc", "非数字", null, null, null),
                contact("8613800000001", "张三", null, null, null)
        ), 1L));

        assertThat(result.contactNum()).isEqualTo(1);
        assertThat(result.rows().get(0).phone()).isEqualTo("8613800000001");
    }

    @Test
    void blankStringsBecomeNullNotEmpty() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", "  ", "", "  ", "")
        ), 1L));

        NormalizedContacts.Row row = result.rows().get(0);
        assertThat(row.fullName()).isNull();
        assertThat(row.firstName()).isNull();
        assertThat(row.pushName()).isNull();
        assertThat(row.businessName()).isNull();
        assertThat(row.named()).isFalse();
    }

    @Test
    void backfillsJidWhenProtocolOmitsIt() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                new AccountContactSnapshot.Contact(
                        "8613800000001", null, "张三", null, null, null)
        ), 1L));

        assertThat(result.rows().get(0).jid()).isEqualTo("8613800000001@s.whatsapp.net");
    }

    @Test
    void emptyAndNullSnapshotsProduceZeroCounts() {
        assertThat(normalizer.normalize(new AccountContactSnapshot(List.of(), 1L)).contactNum())
                .isZero();
        assertThat(normalizer.normalize(new AccountContactSnapshot(null, null)).contactNum())
                .isZero();
        assertThat(normalizer.normalize(null).contactNum()).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactNormalizerTest
```

Expected: FAIL，`cannot find symbol: class NormalizedContacts`

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/account/contact/model/NormalizedContacts.java`：

```java
package com.armada.account.contact.model;

import java.util.List;

/**
 * 一次通讯录协议快照归一化后的结果。
 *
 * @param rows 去重、裁空后的联系人行
 * @param contactNum 联系人总数
 * @param namedNum 通讯录里有名字的数量（竞品「好友数」口径）
 * @param mutualNum 双向好友数量（协议暂不暴露该标记时恒为 0）
 */
public record NormalizedContacts(List<Row> rows, int contactNum, int namedNum, int mutualNum) {

    /** 空结果常量。 */
    public static final NormalizedContacts EMPTY = new NormalizedContacts(List.of(), 0, 0, 0);

    /**
     * 归一化后的单个联系人。
     *
     * @param phone 不带加号的纯数字号码
     * @param jid 规范用户 JID
     * @param fullName 通讯录全名，空白值归一为 null
     * @param firstName 通讯录名，空白值归一为 null
     * @param pushName 对方设置的展示名，空白值归一为 null
     * @param businessName 商业号认证名，空白值归一为 null
     * @param named 通讯录里是否有名字
     * @param mutual 是否双向好友
     */
    public record Row(
            String phone,
            String jid,
            String fullName,
            String firstName,
            String pushName,
            String businessName,
            boolean named,
            boolean mutual
    ) {
    }
}
```

`armada-api/src/main/java/com/armada/account/contact/service/AccountContactNormalizer.java`：

```java
package com.armada.account.contact.service;

import com.armada.account.contact.model.NormalizedContacts;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通讯录协议快照归一化器。
 *
 * <p>协议 adapter 已做过一次号码归一，本类只负责去重、裁空、派生计数，不重复解析 JID。</p>
 */
@Component
public class AccountContactNormalizer {

    private static final String USER_SERVER = "@s.whatsapp.net";

    /**
     * 把协议快照归一为可落库的行与计数。
     *
     * @param snapshot 协议通讯录快照，允许为 null
     * @return 归一结果，输入为空时返回 {@link NormalizedContacts#EMPTY}
     */
    public NormalizedContacts normalize(AccountContactSnapshot snapshot) {
        if (snapshot == null || snapshot.contacts() == null || snapshot.contacts().isEmpty()) {
            return NormalizedContacts.EMPTY;
        }
        Map<String, NormalizedContacts.Row> byPhone = new LinkedHashMap<>();
        for (AccountContactSnapshot.Contact contact : snapshot.contacts()) {
            if (contact == null) {
                continue;
            }
            String phone = digits(contact.phone());
            if (phone == null) {
                continue;
            }
            NormalizedContacts.Row prev = byPhone.get(phone);
            String fullName = coalesce(text(contact.fullName()), prev == null ? null : prev.fullName());
            String firstName = coalesce(text(contact.firstName()), prev == null ? null : prev.firstName());
            String pushName = coalesce(text(contact.pushName()), prev == null ? null : prev.pushName());
            String businessName =
                    coalesce(text(contact.businessName()), prev == null ? null : prev.businessName());
            byPhone.put(phone, new NormalizedContacts.Row(
                    phone,
                    coalesce(text(contact.jid()), phone + USER_SERVER),
                    fullName,
                    firstName,
                    pushName,
                    businessName,
                    fullName != null || firstName != null,
                    // 双向好友标记两套协议都不暴露（spec §5.1 待验证项 V2），恒为 false。
                    // 协议补齐后只需改这一处。
                    false));
        }
        List<NormalizedContacts.Row> rows = List.copyOf(new ArrayList<>(byPhone.values()));
        int namedNum = (int) rows.stream().filter(NormalizedContacts.Row::named).count();
        int mutualNum = (int) rows.stream().filter(NormalizedContacts.Row::mutual).count();
        return new NormalizedContacts(rows, rows.size(), namedNum, mutualNum);
    }

    private static String digits(String value) {
        String trimmed = text(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.chars().allMatch(Character::isDigit) ? trimmed : null;
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String coalesce(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactNormalizerTest
```

Expected: PASS，7 个用例全绿

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/contact/
git add armada-api/src/test/java/com/armada/account/contact/AccountContactNormalizerTest.java
git commit -m "feat(contact): add contact snapshot normalizer"
```

---

### Task 3: 快照新鲜度判定与配置

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/contact/config/AccountContactProperties.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/service/ContactSnapshotFreshness.java`
- Test: `armada-api/src/test/java/com/armada/account/contact/ContactSnapshotFreshnessTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ContactSnapshotFreshness.isStale(Long lastSyncedAt, long now, int ttlHours)` → `boolean`
  - `AccountContactProperties.syncOnOnlineOrDefault()` / `snapshotTtlHoursOrDefault()`

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/account/contact/ContactSnapshotFreshnessTest.java`：

```java
package com.armada.account.contact;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.service.ContactSnapshotFreshness;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactSnapshotFreshnessTest {

    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_756_345_678_901L;

    @Test
    void neverSyncedIsAlwaysStale() {
        assertThat(ContactSnapshotFreshness.isStale(null, NOW, 24)).isTrue();
    }

    @Test
    void snapshotInsideTtlIsFresh() {
        assertThat(ContactSnapshotFreshness.isStale(NOW - 23 * HOUR, NOW, 24)).isFalse();
    }

    @Test
    void snapshotExactlyAtTtlIsStale() {
        assertThat(ContactSnapshotFreshness.isStale(NOW - 24 * HOUR, NOW, 24)).isTrue();
    }

    @Test
    void snapshotBeyondTtlIsStale() {
        assertThat(ContactSnapshotFreshness.isStale(NOW - 25 * HOUR, NOW, 24)).isTrue();
    }

    @Test
    void nonPositiveTtlForcesEveryReadToRefetch() {
        assertThat(ContactSnapshotFreshness.isStale(NOW, NOW, 0)).isTrue();
        assertThat(ContactSnapshotFreshness.isStale(NOW, NOW, -1)).isTrue();
    }

    @Test
    void clockSkewFromTheFutureCountsAsFresh() {
        // 多节点时钟漂移导致快照时间晚于 now，不应触发无意义重拉
        assertThat(ContactSnapshotFreshness.isStale(NOW + HOUR, NOW, 24)).isFalse();
    }

    @Test
    void propertiesFallBackToSaneDefaults() {
        AccountContactProperties unset = new AccountContactProperties(null, null);
        assertThat(unset.syncOnOnlineOrDefault()).isTrue();
        assertThat(unset.snapshotTtlHoursOrDefault()).isEqualTo(24);

        AccountContactProperties set = new AccountContactProperties(false, 6);
        assertThat(set.syncOnOnlineOrDefault()).isFalse();
        assertThat(set.snapshotTtlHoursOrDefault()).isEqualTo(6);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactSnapshotFreshnessTest
```

Expected: FAIL，`cannot find symbol: class ContactSnapshotFreshness`

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/account/contact/service/ContactSnapshotFreshness.java`：

```java
package com.armada.account.contact.service;

/**
 * 通讯录快照新鲜度判定。
 *
 * <p>纯函数工具类，不持有状态，供同步服务与后续任务展开共用同一套口径。</p>
 */
public final class ContactSnapshotFreshness {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private ContactSnapshotFreshness() {
    }

    /**
     * 判断快照是否已过期需要重拉。
     *
     * @param lastSyncedAt 最近一次成功同步时间（epoch 毫秒），从未成功为 null
     * @param now 当前时间（epoch 毫秒）
     * @param ttlHours 快照有效期小时数；小于等于 0 表示每次都重拉
     * @return 需要重拉则 true
     */
    public static boolean isStale(Long lastSyncedAt, long now, int ttlHours) {
        if (lastSyncedAt == null || ttlHours <= 0) {
            return true;
        }
        long age = now - lastSyncedAt;
        // 时钟漂移导致 age 为负时视为新鲜，避免无意义重拉。
        if (age < 0) {
            return false;
        }
        return age >= ttlHours * MILLIS_PER_HOUR;
    }
}
```

`armada-api/src/main/java/com/armada/account/contact/config/AccountContactProperties.java`：

```java
package com.armada.account.contact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 账号通讯录采集配置。
 *
 * @param syncOnOnline 账号上线后是否自动同步一次通讯录，未配置时为 true
 * @param snapshotTtlHours 快照有效期小时数，未配置时为 24；小于等于 0 表示每次读取都重拉
 */
@ConfigurationProperties(prefix = "armada.account-contact")
public record AccountContactProperties(
        Boolean syncOnOnline,
        Integer snapshotTtlHours
) {

    /** 未配置时默认在上线后同步。 */
    public boolean syncOnOnlineOrDefault() {
        return syncOnOnline == null || syncOnOnline;
    }

    /** 未配置时默认快照有效期 24 小时。 */
    public int snapshotTtlHoursOrDefault() {
        return snapshotTtlHours == null ? 24 : snapshotTtlHours;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=ContactSnapshotFreshnessTest
```

Expected: PASS，7 个用例全绿

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/contact/service/ContactSnapshotFreshness.java
git add armada-api/src/main/java/com/armada/account/contact/config/AccountContactProperties.java
git add armada-api/src/test/java/com/armada/account/contact/ContactSnapshotFreshnessTest.java
git commit -m "feat(contact): add snapshot freshness policy"
```

---

### Task 4: 实体、Mapper 与 XML

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/contact/model/entity/AccountContact.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/model/entity/AccountContactSync.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactMapper.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactSyncMapper.java`
- Create: `armada-api/src/main/resources/mapper/account/AccountContactMapper.xml`
- Create: `armada-api/src/main/resources/mapper/account/AccountContactSyncMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/contact/AccountContactMapperXmlTest.java`

**Interfaces:**
- Consumes: Task 1 的表结构
- Produces:
  - `AccountContactMapper.upsertBatch(List<AccountContact>)` → `int`
  - `AccountContactMapper.deleteStale(Long accountId, long syncedAt)` → `int`
  - `AccountContactMapper.countNamed(Long accountId)` → `int`
  - `AccountContactSyncMapper.selectByAccountId(Long accountId)` → `AccountContactSync`
  - `AccountContactSyncMapper.upsert(AccountContactSync)` → `int`
  - `AccountStateMapper.updateContactCounts(Long accountId, int namedNum, int mutualNum, long updatedAt)` → `int`

- [ ] **Step 1: 写失败测试**

本机无库，Mapper 行为跑不了 DbTest。改为 **XML 静态契约测试**：校验 namespace 与接口对应、
每个接口方法都有同名语句、批量写入用 upsert 而不是先删后插。

创建 `armada-api/src/test/java/com/armada/account/contact/AccountContactMapperXmlTest.java`：

```java
package com.armada.account.contact;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
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

/** 通讯录 Mapper XML 与接口的静态契约测试。本机无库，只校验契约不校验行为。 */
class AccountContactMapperXmlTest {

    private static String xml(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/account/" + name), StandardCharsets.UTF_8);
    }

    private static Set<String> declaredMethods(Class<?> mapper) {
        return Arrays.stream(mapper.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void contactMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("AccountContactMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.account.contact.mapper.AccountContactMapper\"");
        for (String method : declaredMethods(AccountContactMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void syncMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("AccountContactSyncMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.account.contact.mapper.AccountContactSyncMapper\"");
        for (String method : declaredMethods(AccountContactSyncMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void batchWriteIsUpsertNotTruncateThenInsert() throws IOException {
        String sql = xml("AccountContactMapper.xml");

        // 整批替换靠 upsert + 扫尾删除实现；不能先全量 DELETE 再插，
        // 否则同步中途失败会把账号通讯录清空。
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql).doesNotContain("TRUNCATE");
    }

    @Test
    void staleSweepIsBoundedByAccountAndSyncedAt() throws IOException {
        String sql = xml("AccountContactMapper.xml");

        assertThat(sql)
                .contains("account_id = #{accountId}")
                .contains("synced_at &lt; #{syncedAt}");
    }

    @Test
    void accountStateContactCountUpdateExists() throws IOException {
        String sql = xml("AccountStateMapper.xml");

        assertThat(sql)
                .contains("id=\"updateContactCounts\"")
                .contains("contact_named_num = #{namedNum}")
                .contains("contact_mutual_num = #{mutualNum}");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactMapperXmlTest
```

Expected: FAIL，编译错误 `package com.armada.account.contact.mapper does not exist`

- [ ] **Step 3: 写两个实体**

`armada-api/src/main/java/com/armada/account/contact/model/entity/AccountContact.java`：

```java
package com.armada.account.contact.model.entity;

/** 账号通讯录联系人快照行，对应 account_contact 表。 */
public class AccountContact {

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 所属账号 ID。 */
    private Long accountId;
    /** 联系人号码，不带加号的纯数字。 */
    private String contactPhone;
    /** 联系人 JID。 */
    private String contactJid;
    /** 通讯录全名。 */
    private String fullName;
    /** 通讯录名。 */
    private String firstName;
    /** 对方设置的展示名。 */
    private String pushName;
    /** 商业号认证名。 */
    private String businessName;
    /** 通讯录里是否有名字，1 有 0 无。 */
    private Integer isNamed;
    /** 是否双向好友，协议暂不暴露时恒为 0。 */
    private Integer isMutual;
    /** 本行所属同步批次时间（epoch 毫秒）。 */
    private Long syncedAt;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getContactJid() { return contactJid; }
    public void setContactJid(String contactJid) { this.contactJid = contactJid; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getPushName() { return pushName; }
    public void setPushName(String pushName) { this.pushName = pushName; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public Integer getIsNamed() { return isNamed; }
    public void setIsNamed(Integer isNamed) { this.isNamed = isNamed; }
    public Integer getIsMutual() { return isMutual; }
    public void setIsMutual(Integer isMutual) { this.isMutual = isMutual; }
    public Long getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Long syncedAt) { this.syncedAt = syncedAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
```

`armada-api/src/main/java/com/armada/account/contact/model/entity/AccountContactSync.java`：

```java
package com.armada.account.contact.model.entity;

/** 账号通讯录同步状态，对应 account_contact_sync 表，一账号一行。 */
public class AccountContactSync {

    /** 同步状态：从未同步。 */
    public static final String STATUS_NEVER = "NEVER";
    /** 同步状态：进行中。 */
    public static final String STATUS_SYNCING = "SYNCING";
    /** 同步状态：成功。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 同步状态：失败。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 账号 ID。 */
    private Long accountId;
    /** 最近一次成功同步时间（epoch 毫秒）。 */
    private Long lastSyncedAt;
    /** 最近一次同步触发来源。 */
    private String lastSyncSource;
    /** 最近一次成功同步到的联系人总数。 */
    private Integer contactNum;
    /** 其中通讯录有名字的数量。 */
    private Integer namedNum;
    /** 其中双向好友数量。 */
    private Integer mutualNum;
    /** 同步状态。 */
    private String syncStatus;
    /** 最近一次失败原因。 */
    private String failReason;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Long getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Long lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getLastSyncSource() { return lastSyncSource; }
    public void setLastSyncSource(String lastSyncSource) { this.lastSyncSource = lastSyncSource; }
    public Integer getContactNum() { return contactNum; }
    public void setContactNum(Integer contactNum) { this.contactNum = contactNum; }
    public Integer getNamedNum() { return namedNum; }
    public void setNamedNum(Integer namedNum) { this.namedNum = namedNum; }
    public Integer getMutualNum() { return mutualNum; }
    public void setMutualNum(Integer mutualNum) { this.mutualNum = mutualNum; }
    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 4: 写两个 Mapper 接口**

`armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactMapper.java`：

```java
package com.armada.account.contact.mapper;

import com.armada.account.contact.model.entity.AccountContact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 账号通讯录联系人快照的数据访问。 */
@Mapper
public interface AccountContactMapper {

    /**
     * 批量写入或更新本批联系人。
     *
     * @param rows 本批联系人行，必须非空
     * @return 受影响行数
     */
    int upsertBatch(@Param("rows") List<AccountContact> rows);

    /**
     * 删除本账号下早于本批同步时间的残留联系人。
     *
     * @param accountId 账号 ID
     * @param syncedAt 本批同步时间（epoch 毫秒）
     * @return 删除行数
     */
    int deleteStale(@Param("accountId") Long accountId, @Param("syncedAt") long syncedAt);

    /**
     * 统计本账号通讯录里有名字的联系人数。
     *
     * @param accountId 账号 ID
     * @return 有名字的联系人数
     */
    int countNamed(@Param("accountId") Long accountId);
}
```

`armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactSyncMapper.java`：

```java
package com.armada.account.contact.mapper;

import com.armada.account.contact.model.entity.AccountContactSync;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账号通讯录同步状态的数据访问。 */
@Mapper
public interface AccountContactSyncMapper {

    /**
     * 读取账号当前同步状态。
     *
     * @param accountId 账号 ID
     * @return 同步状态行，从未同步过时为 null
     */
    AccountContactSync selectByAccountId(@Param("accountId") Long accountId);

    /**
     * 写入或更新账号同步状态。
     *
     * @param row 同步状态行
     * @return 受影响行数
     */
    int upsert(AccountContactSync row);
}
```

- [ ] **Step 5: 写两个 Mapper XML**

`armada-api/src/main/resources/mapper/account/AccountContactMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.account.contact.mapper.AccountContactMapper">

  <insert id="upsertBatch">
    INSERT INTO account_contact (
      tenant_id, account_id, contact_phone, contact_jid,
      full_name, first_name, push_name, business_name,
      is_named, is_mutual, synced_at, created_at, updated_at
    ) VALUES
    <foreach collection="rows" item="row" separator=",">
      (#{row.tenantId}, #{row.accountId}, #{row.contactPhone}, #{row.contactJid},
       #{row.fullName}, #{row.firstName}, #{row.pushName}, #{row.businessName},
       #{row.isNamed}, #{row.isMutual}, #{row.syncedAt}, #{row.createdAt}, #{row.updatedAt})
    </foreach>
    ON DUPLICATE KEY UPDATE
      contact_jid = VALUES(contact_jid),
      full_name = VALUES(full_name),
      first_name = VALUES(first_name),
      push_name = VALUES(push_name),
      business_name = VALUES(business_name),
      is_named = VALUES(is_named),
      is_mutual = VALUES(is_mutual),
      synced_at = VALUES(synced_at),
      updated_at = VALUES(updated_at)
  </insert>

  <delete id="deleteStale">
    DELETE FROM account_contact
     WHERE account_id = #{accountId}
       AND synced_at &lt; #{syncedAt}
  </delete>

  <select id="countNamed" resultType="int">
    SELECT COUNT(*)
      FROM account_contact
     WHERE account_id = #{accountId}
       AND is_named = 1
  </select>

</mapper>
```

`armada-api/src/main/resources/mapper/account/AccountContactSyncMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.account.contact.mapper.AccountContactSyncMapper">

  <resultMap id="syncMap" type="com.armada.account.contact.model.entity.AccountContactSync">
    <id property="id" column="id"/>
    <result property="tenantId" column="tenant_id"/>
    <result property="accountId" column="account_id"/>
    <result property="lastSyncedAt" column="last_synced_at"/>
    <result property="lastSyncSource" column="last_sync_source"/>
    <result property="contactNum" column="contact_num"/>
    <result property="namedNum" column="named_num"/>
    <result property="mutualNum" column="mutual_num"/>
    <result property="syncStatus" column="sync_status"/>
    <result property="failReason" column="fail_reason"/>
    <result property="createdAt" column="created_at"/>
    <result property="updatedAt" column="updated_at"/>
  </resultMap>

  <select id="selectByAccountId" resultMap="syncMap">
    SELECT id, tenant_id, account_id, last_synced_at, last_sync_source,
           contact_num, named_num, mutual_num, sync_status, fail_reason,
           created_at, updated_at
      FROM account_contact_sync
     WHERE account_id = #{accountId}
     LIMIT 1
  </select>

  <insert id="upsert">
    INSERT INTO account_contact_sync (
      tenant_id, account_id, last_synced_at, last_sync_source,
      contact_num, named_num, mutual_num, sync_status, fail_reason,
      created_at, updated_at
    ) VALUES (
      #{tenantId}, #{accountId}, #{lastSyncedAt}, #{lastSyncSource},
      #{contactNum}, #{namedNum}, #{mutualNum}, #{syncStatus}, #{failReason},
      #{createdAt}, #{updatedAt}
    )
    ON DUPLICATE KEY UPDATE
      last_synced_at = VALUES(last_synced_at),
      last_sync_source = VALUES(last_sync_source),
      contact_num = VALUES(contact_num),
      named_num = VALUES(named_num),
      mutual_num = VALUES(mutual_num),
      sync_status = VALUES(sync_status),
      fail_reason = VALUES(fail_reason),
      updated_at = VALUES(updated_at)
  </insert>

</mapper>
```

- [ ] **Step 6: 扩 `AccountStateMapper`**

在 `armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java` 的
`updateLifecycleState` 声明之后加：

```java
    /**
     * 回写账号通讯录计数。仅供通讯录同步服务调用，其他地方不得直写这两列。
     *
     * @param accountId 账号 ID
     * @param namedNum 通讯录有名字联系人数
     * @param mutualNum 双向好友数
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int updateContactCounts(@Param("accountId") Long accountId,
                            @Param("namedNum") int namedNum,
                            @Param("mutualNum") int mutualNum,
                            @Param("updatedAt") long updatedAt);
```

在 `armada-api/src/main/resources/mapper/account/AccountStateMapper.xml` 的 `</mapper>` 之前加：

```xml
  <update id="updateContactCounts">
    UPDATE account_state
       SET contact_named_num = #{namedNum},
           contact_mutual_num = #{mutualNum},
           updated_at = #{updatedAt}
     WHERE account_id = #{accountId}
  </update>
```

- [ ] **Step 7: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactMapperXmlTest
```

Expected: PASS，5 个用例全绿

- [ ] **Step 8: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/contact/
git add armada-api/src/main/resources/mapper/account/AccountContactMapper.xml
git add armada-api/src/main/resources/mapper/account/AccountContactSyncMapper.xml
git add armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java
git add armada-api/src/main/resources/mapper/account/AccountStateMapper.xml
git add armada-api/src/test/java/com/armada/account/contact/AccountContactMapperXmlTest.java
git commit -m "feat(contact): add contact snapshot persistence layer"
```

---

### Task 5: `AccountContactSyncService`

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/contact/model/ContactSyncSource.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/model/AccountContactSyncResult.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/service/AccountContactSyncService.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSyncServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/account/contact/AccountContactSyncServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 `AccountContactNormalizer`、Task 3 `ContactSnapshotFreshness` + `AccountContactProperties`、
  Task 4 三个 Mapper、P0 `ContactListPort`
- Produces:
  - `enum ContactSyncSource { ONLINE_EVENT, TASK_START, MANUAL }`
  - `record AccountContactSyncResult(boolean refreshed, boolean succeeded, int contactNum, int namedNum, int mutualNum, Long syncedAt, String failReason)`
  - `AccountContactSyncService.syncNow(Long accountId, ContactSyncSource source)`
  - `AccountContactSyncService.syncIfStale(Long accountId, ContactSyncSource source)`

- [ ] **Step 1: 已确证的依赖事实（无需再查）**

`com.armada.account.service.AccountProtocolLookupService` 的
`Optional<ProtocolAccountRef> findActiveProtocolRef(Long accountId)`（该接口第 28 行）
就是本任务需要的解析方法。

实现类通过构造注入 `Function<Long, ProtocolAccountRef>` 拿引用，装配在 Task 6 的配置类里完成。
**不要在 Service 里直接依赖 `AccountProtocolLookupService`**——注入函数是为了让本类
能用纯 Mockito 测试，不必起 Spring 上下文（本机无库，上下文起不来）。

- [ ] **Step 2: 写失败测试**

创建 `armada-api/src/test/java/com/armada/account/contact/AccountContactSyncServiceImplTest.java`：

```java
package com.armada.account.contact;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.impl.AccountContactSyncServiceImpl;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.port.ContactListPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountContactSyncServiceImplTest {

    private static final long NOW = 1_756_345_678_901L;
    private static final long TENANT = 1L;
    private static final long ACCOUNT = 501L;
    private static final long HOUR = 3_600_000L;

    private ContactListPort contactListPort;
    private AccountContactMapper contactMapper;
    private AccountContactSyncMapper syncMapper;
    private AccountStateMapper accountStateMapper;
    private AccountContactSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        contactListPort = mock(ContactListPort.class);
        contactMapper = mock(AccountContactMapper.class);
        syncMapper = mock(AccountContactSyncMapper.class);
        accountStateMapper = mock(AccountStateMapper.class);
        service = new AccountContactSyncServiceImpl(
                contactListPort,
                contactMapper,
                syncMapper,
                accountStateMapper,
                new AccountContactNormalizer(),
                new AccountContactProperties(true, 24),
                accountId -> new ProtocolAccountRef(
                        ACCOUNT, ProtocolBackend.WEB, "acc_501", "8613800000000"),
                () -> TENANT,
                () -> NOW);
    }

    private static AccountContactSnapshot snapshot(int size) {
        return new AccountContactSnapshot(
                IntStream.range(0, size)
                        .mapToObj(i -> {
                            String phone = "861380000" + String.format("%04d", i);
                            return new AccountContactSnapshot.Contact(
                                    phone, phone + "@s.whatsapp.net", "联系人" + i, null, null, null);
                        })
                        .toList(),
                NOW);
    }

    @Test
    void syncNowWritesRowsSweepsStaleAndBackfillsCounts() {
        when(contactListPort.list(any())).thenReturn(snapshot(3));

        AccountContactSyncResult result = service.syncNow(ACCOUNT, ContactSyncSource.ONLINE_EVENT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.refreshed()).isTrue();
        assertThat(result.contactNum()).isEqualTo(3);
        assertThat(result.namedNum()).isEqualTo(3);
        assertThat(result.mutualNum()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountContact>> rows = ArgumentCaptor.forClass(List.class);
        verify(contactMapper).upsertBatch(rows.capture());
        assertThat(rows.getValue()).hasSize(3);
        assertThat(rows.getValue().get(0).getTenantId()).isEqualTo(TENANT);
        assertThat(rows.getValue().get(0).getSyncedAt()).isEqualTo(NOW);

        // 扫尾删除必须用同一个 syncedAt，否则会把刚写进去的行删掉
        verify(contactMapper).deleteStale(ACCOUNT, NOW);
        verify(accountStateMapper).updateContactCounts(ACCOUNT, 3, 0, NOW);
    }

    @Test
    void emptySnapshotStillSweepsAndZeroesCounts() {
        when(contactListPort.list(any())).thenReturn(new AccountContactSnapshot(List.of(), NOW));

        AccountContactSyncResult result = service.syncNow(ACCOUNT, ContactSyncSource.MANUAL);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.contactNum()).isZero();
        // 空批次不能调 upsertBatch（foreach 会生成空 VALUES 导致语法错），但扫尾与归零必须发生
        verify(contactMapper, never()).upsertBatch(any());
        verify(contactMapper).deleteStale(ACCOUNT, NOW);
        verify(accountStateMapper).updateContactCounts(ACCOUNT, 0, 0, NOW);
    }

    @Test
    void protocolFailureLeavesExistingSnapshotUntouched() {
        when(contactListPort.list(any())).thenThrow(new ProtocolException(
                ProtocolErrorCode.UNSUPPORTED_BACKEND, "账号不在线"));

        AccountContactSyncResult result = service.syncNow(ACCOUNT, ContactSyncSource.TASK_START);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failReason()).contains("账号不在线");
        verify(contactMapper, never()).upsertBatch(any());
        verify(contactMapper, never()).deleteStale(anyLong(), anyLong());
        verify(accountStateMapper, never())
                .updateContactCounts(anyLong(), anyInt(), anyInt(), anyLong());

        ArgumentCaptor<AccountContactSync> saved = ArgumentCaptor.forClass(AccountContactSync.class);
        verify(syncMapper).upsert(saved.capture());
        assertThat(saved.getValue().getSyncStatus()).isEqualTo(AccountContactSync.STATUS_FAILED);
    }

    @Test
    void syncIfStaleSkipsWhenSnapshotIsFresh() {
        AccountContactSync existing = new AccountContactSync();
        existing.setAccountId(ACCOUNT);
        existing.setLastSyncedAt(NOW - HOUR);
        existing.setContactNum(7);
        existing.setNamedNum(5);
        existing.setMutualNum(0);
        when(syncMapper.selectByAccountId(ACCOUNT)).thenReturn(existing);

        AccountContactSyncResult result = service.syncIfStale(ACCOUNT, ContactSyncSource.TASK_START);

        assertThat(result.refreshed()).isFalse();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.contactNum()).isEqualTo(7);
        assertThat(result.namedNum()).isEqualTo(5);
        verify(contactListPort, never()).list(any());
    }

    @Test
    void syncIfStaleRefetchesWhenSnapshotExpired() {
        AccountContactSync existing = new AccountContactSync();
        existing.setAccountId(ACCOUNT);
        existing.setLastSyncedAt(NOW - 25 * HOUR);
        when(syncMapper.selectByAccountId(ACCOUNT)).thenReturn(existing);
        when(contactListPort.list(any())).thenReturn(snapshot(2));

        AccountContactSyncResult result = service.syncIfStale(ACCOUNT, ContactSyncSource.TASK_START);

        assertThat(result.refreshed()).isTrue();
        assertThat(result.contactNum()).isEqualTo(2);
        verify(contactListPort).list(any());
    }

    @Test
    void syncIfStaleRefetchesWhenNeverSynced() {
        when(syncMapper.selectByAccountId(ACCOUNT)).thenReturn(null);
        when(contactListPort.list(any())).thenReturn(snapshot(1));

        AccountContactSyncResult result = service.syncIfStale(ACCOUNT, ContactSyncSource.ONLINE_EVENT);

        assertThat(result.refreshed()).isTrue();
        verify(contactListPort).list(any());
    }

    @Test
    void successRecordsSourceTenantAndClearsFailReason() {
        when(contactListPort.list(any())).thenReturn(snapshot(1));

        service.syncNow(ACCOUNT, ContactSyncSource.ONLINE_EVENT);

        ArgumentCaptor<AccountContactSync> saved = ArgumentCaptor.forClass(AccountContactSync.class);
        verify(syncMapper).upsert(saved.capture());
        assertThat(saved.getValue().getSyncStatus()).isEqualTo(AccountContactSync.STATUS_SUCCESS);
        assertThat(saved.getValue().getLastSyncSource()).isEqualTo("ONLINE_EVENT");
        assertThat(saved.getValue().getLastSyncedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getFailReason()).isNull();
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
    }
}
```

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/account/contact/model/ContactSyncSource.java`：

```java
package com.armada.account.contact.model;

/** 通讯录同步的触发来源。 */
public enum ContactSyncSource {

    /** 账号上线状态事件触发。 */
    ONLINE_EVENT,
    /** 通讯录任务启动时按 TTL 触发。 */
    TASK_START,
    /** 运营手动触发。 */
    MANUAL
}
```

`armada-api/src/main/java/com/armada/account/contact/model/AccountContactSyncResult.java`：

```java
package com.armada.account.contact.model;

/**
 * 一次通讯录同步的结果。
 *
 * @param refreshed 本次是否真的向协议层拉取过（TTL 命中现有快照时为 false）
 * @param succeeded 快照当前是否可用
 * @param contactNum 联系人总数
 * @param namedNum 通讯录有名字的数量
 * @param mutualNum 双向好友数量
 * @param syncedAt 快照时间（epoch 毫秒），从未成功时为 null
 * @param failReason 失败原因，成功时为 null
 */
public record AccountContactSyncResult(
        boolean refreshed,
        boolean succeeded,
        int contactNum,
        int namedNum,
        int mutualNum,
        Long syncedAt,
        String failReason
) {
}
```

`armada-api/src/main/java/com/armada/account/contact/service/AccountContactSyncService.java`：

```java
package com.armada.account.contact.service;

import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;

/** 账号通讯录采集服务。 */
public interface AccountContactSyncService {

    /**
     * 强制向协议层重拉一次通讯录并落快照。
     *
     * @param accountId 账号 ID
     * @param source 触发来源
     * @return 同步结果；协议失败时 succeeded 为 false 且不动已有快照
     */
    AccountContactSyncResult syncNow(Long accountId, ContactSyncSource source);

    /**
     * 快照过期才重拉，未过期直接返回现有计数。
     *
     * @param accountId 账号 ID
     * @param source 触发来源
     * @return 同步结果
     */
    AccountContactSyncResult syncIfStale(Long accountId, ContactSyncSource source);
}
```

`armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSyncServiceImpl.java`：

```java
package com.armada.account.contact.service.impl;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.model.NormalizedContacts;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.contact.service.ContactSnapshotFreshness;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.port.ContactListPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 账号通讯录采集服务实现。
 *
 * <p>整批替换语义：先 upsert 本批号码，再删除 synced_at 早于本批的残留行。
 * 协议拉取失败时<b>不动任何已有数据</b>，只把同步状态标为 FAILED，
 * 保证「拉不到」不会退化成「通讯录被清空」。</p>
 *
 * <p><b>有意的失败语义</b>：失败时 last_synced_at 写为 NULL，也就是会抹掉上次成功的时间戳，
 * 下一次 syncIfStale 必定重拉。这是刻意选择——拉不到通讯录时任务本就不该拿旧快照发送。
 * 不要把它当 bug 改成 COALESCE 保留旧时间戳。</p>
 *
 * <p><b>本类刻意不标注 @Service</b>：构造参数里有 Function 与 Supplier，
 * Spring 无法自动装配，必须由 AccountContactConfiguration 显式 new 出来。
 * 这样做是为了让本类能用纯 Mockito 测试，不需要起 Spring 上下文（本机无库跑不了上下文）。</p>
 */
public class AccountContactSyncServiceImpl implements AccountContactSyncService {

    private static final Logger log = LoggerFactory.getLogger(AccountContactSyncServiceImpl.class);
    private static final int UPSERT_BATCH_SIZE = 500;
    private static final int FAIL_REASON_MAX = 255;

    private final ContactListPort contactListPort;
    private final AccountContactMapper contactMapper;
    private final AccountContactSyncMapper syncMapper;
    private final AccountStateMapper accountStateMapper;
    private final AccountContactNormalizer normalizer;
    private final AccountContactProperties properties;
    private final Function<Long, ProtocolAccountRef> accountRefResolver;
    private final Supplier<Long> tenantSupplier;
    private final LongSupplier clock;

    /**
     * 创建通讯录采集服务。
     *
     * @param contactListPort 通讯录读取协议端口
     * @param contactMapper 联系人快照数据访问
     * @param syncMapper 同步状态数据访问
     * @param accountStateMapper 账号状态数据访问，用于回写计数
     * @param normalizer 协议快照归一化器
     * @param properties 通讯录采集配置
     * @param accountRefResolver 账号 ID 到协议账号引用的解析器
     * @param tenantSupplier 当前租户提供者
     * @param clock 当前时间提供者（epoch 毫秒）
     */
    public AccountContactSyncServiceImpl(
            ContactListPort contactListPort,
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer,
            AccountContactProperties properties,
            Function<Long, ProtocolAccountRef> accountRefResolver,
            Supplier<Long> tenantSupplier,
            LongSupplier clock) {
        this.contactListPort = contactListPort;
        this.contactMapper = contactMapper;
        this.syncMapper = syncMapper;
        this.accountStateMapper = accountStateMapper;
        this.normalizer = normalizer;
        this.properties = properties;
        this.accountRefResolver = accountRefResolver;
        this.tenantSupplier = tenantSupplier;
        this.clock = clock;
    }

    @Override
    public AccountContactSyncResult syncNow(Long accountId, ContactSyncSource source) {
        long now = clock.getAsLong();
        Long tenantId = tenantSupplier.get();
        AccountContactSnapshot snapshot;
        try {
            snapshot = contactListPort.list(accountRefResolver.apply(accountId));
        } catch (RuntimeException ex) {
            String reason = truncate(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("账号通讯录同步失败,保留既有快照 tenantId={} accountId={} source={} reason={}",
                    tenantId, accountId, source, reason);
            saveSyncState(tenantId, accountId, source, now, null, NormalizedContacts.EMPTY,
                    AccountContactSync.STATUS_FAILED, reason);
            return new AccountContactSyncResult(true, false, 0, 0, 0, null, reason);
        }

        NormalizedContacts normalized = normalizer.normalize(snapshot);
        writeSnapshot(tenantId, accountId, now, normalized);
        accountStateMapper.updateContactCounts(
                accountId, normalized.namedNum(), normalized.mutualNum(), now);
        saveSyncState(tenantId, accountId, source, now, now, normalized,
                AccountContactSync.STATUS_SUCCESS, null);

        log.info("账号通讯录同步成功 tenantId={} accountId={} source={} contactNum={} namedNum={}",
                tenantId, accountId, source, normalized.contactNum(), normalized.namedNum());
        return new AccountContactSyncResult(true, true,
                normalized.contactNum(), normalized.namedNum(), normalized.mutualNum(), now, null);
    }

    @Override
    public AccountContactSyncResult syncIfStale(Long accountId, ContactSyncSource source) {
        AccountContactSync existing = syncMapper.selectByAccountId(accountId);
        long now = clock.getAsLong();
        Long lastSyncedAt = existing == null ? null : existing.getLastSyncedAt();
        if (!ContactSnapshotFreshness.isStale(
                lastSyncedAt, now, properties.snapshotTtlHoursOrDefault())) {
            return new AccountContactSyncResult(
                    false, true,
                    orZero(existing.getContactNum()),
                    orZero(existing.getNamedNum()),
                    orZero(existing.getMutualNum()),
                    lastSyncedAt, null);
        }
        return syncNow(accountId, source);
    }

    /** 整批替换：分批 upsert 后扫掉早于本批的残留行。空批次只扫尾。 */
    private void writeSnapshot(
            Long tenantId, Long accountId, long now, NormalizedContacts normalized) {
        List<AccountContact> batch = new ArrayList<>(UPSERT_BATCH_SIZE);
        for (NormalizedContacts.Row row : normalized.rows()) {
            batch.add(toEntity(tenantId, accountId, now, row));
            if (batch.size() == UPSERT_BATCH_SIZE) {
                contactMapper.upsertBatch(batch);
                batch = new ArrayList<>(UPSERT_BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            contactMapper.upsertBatch(batch);
        }
        contactMapper.deleteStale(accountId, now);
    }

    private static AccountContact toEntity(
            Long tenantId, Long accountId, long now, NormalizedContacts.Row row) {
        AccountContact entity = new AccountContact();
        entity.setTenantId(tenantId);
        entity.setAccountId(accountId);
        entity.setContactPhone(row.phone());
        entity.setContactJid(row.jid());
        entity.setFullName(row.fullName());
        entity.setFirstName(row.firstName());
        entity.setPushName(row.pushName());
        entity.setBusinessName(row.businessName());
        entity.setIsNamed(row.named() ? 1 : 0);
        entity.setIsMutual(row.mutual() ? 1 : 0);
        entity.setSyncedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void saveSyncState(
            Long tenantId, Long accountId, ContactSyncSource source, long now,
            Long lastSyncedAt, NormalizedContacts normalized, String status, String failReason) {
        AccountContactSync row = new AccountContactSync();
        row.setTenantId(tenantId);
        row.setAccountId(accountId);
        row.setLastSyncedAt(lastSyncedAt);
        row.setLastSyncSource(source == null ? null : source.name());
        row.setContactNum(normalized.contactNum());
        row.setNamedNum(normalized.namedNum());
        row.setMutualNum(normalized.mutualNum());
        row.setSyncStatus(status);
        row.setFailReason(failReason);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        syncMapper.upsert(row);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= FAIL_REASON_MAX ? value : value.substring(0, FAIL_REASON_MAX);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactSyncServiceImplTest
```

Expected: PASS，7 个用例全绿

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/contact/
git add armada-api/src/test/java/com/armada/account/contact/AccountContactSyncServiceImplTest.java
git commit -m "feat(contact): implement account contact sync service"
```

---

### Task 6: 账号上线后自动同步

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/contact/service/AccountContactOnlineHook.java`
- Create: `armada-api/src/main/java/com/armada/account/contact/config/AccountContactConfiguration.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java`
- Test: `armada-api/src/test/java/com/armada/account/contact/AccountContactOnlineHookTest.java`

**Interfaces:**
- Consumes: Task 5 `AccountContactSyncService`、Task 3 `AccountContactProperties`
- Produces: `AccountContactOnlineHook.onAccountOnline(Long tenantId, Long accountId)`

- [ ] **Step 1: 读现有 ONLINE 附属任务范式**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
sed -n '55,118p' src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java
grep -rn "inviteRecoveryExecutor" src/main/java
```

该文件注释里已写明的硬约束，必须遵守：
**附属任务失败不能反向阻塞账号状态 Kafka 分区。** 所有异常必须在附属任务内部吞掉只打 warn，
且必须自己维护 `TenantContext` 的 set / restore。

- [ ] **Step 2: 写失败测试**

创建 `armada-api/src/test/java/com/armada/account/contact/AccountContactOnlineHookTest.java`：

```java
package com.armada.account.contact;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.service.AccountContactOnlineHook;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountContactOnlineHookTest {

    private static final Executor DIRECT = Runnable::run;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void syncsOnceOnOnlineAndRestoresTenantContext() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        when(service.syncIfStale(eq(501L), any()))
                .thenReturn(new AccountContactSyncResult(true, true, 3, 3, 0, 1L, null));
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(true, 24), DIRECT);

        hook.onAccountOnline(7L, 501L);

        verify(service).syncIfStale(501L, ContactSyncSource.ONLINE_EVENT);
        // 附属任务不能污染调用线程的租户上下文
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void syncFailureNeverPropagatesToTheCaller() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        doThrow(new IllegalStateException("协议不可用"))
                .when(service).syncIfStale(any(), any());
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(true, 24), DIRECT);

        // 通讯录同步是 ONLINE 附属任务，失败绝不能反向阻塞账号状态 Kafka 分区
        assertThatCode(() -> hook.onAccountOnline(7L, 501L)).doesNotThrowAnyException();
    }

    @Test
    void executorRejectionNeverPropagatesToTheCaller() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        Executor rejecting = task -> {
            throw new RejectedExecutionException("队列已满");
        };
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(true, 24), rejecting);

        assertThatCode(() -> hook.onAccountOnline(7L, 501L)).doesNotThrowAnyException();
        verify(service, never()).syncIfStale(any(), any());
    }

    @Test
    void disabledByConfigurationSkipsSyncEntirely() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(false, 24), DIRECT);

        hook.onAccountOnline(7L, 501L);

        verify(service, never()).syncIfStale(any(), any());
    }
}
```

- [ ] **Step 3: 写实现**

`armada-api/src/main/java/com/armada/account/contact/service/AccountContactOnlineHook.java`：

```java
package com.armada.account.contact.service;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * 账号上线后的通讯录同步附属任务。
 *
 * <p>与既有「ONLINE 后恢复群邀请码」同一范式：投递到独立线程池执行，
 * <b>任何失败都在内部吞掉只打 warn</b>，绝不反向阻塞账号状态 Kafka 分区。</p>
 */
public class AccountContactOnlineHook {

    private static final Logger log = LoggerFactory.getLogger(AccountContactOnlineHook.class);

    private final AccountContactSyncService syncService;
    private final AccountContactProperties properties;
    private final Executor executor;

    /**
     * 创建上线同步钩子。
     *
     * @param syncService 通讯录采集服务
     * @param properties 通讯录采集配置
     * @param executor 附属任务线程池
     */
    public AccountContactOnlineHook(
            AccountContactSyncService syncService,
            AccountContactProperties properties,
            Executor executor) {
        this.syncService = syncService;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 账号进入 ONLINE 后触发一次按 TTL 的通讯录同步。
     *
     * @param tenantId 租户 ID
     * @param accountId 账号 ID
     */
    public void onAccountOnline(Long tenantId, Long accountId) {
        if (!properties.syncOnOnlineOrDefault()) {
            return;
        }
        try {
            executor.execute(() -> runSync(tenantId, accountId));
        } catch (RuntimeException ex) {
            log.warn("账号上线后通讯录同步任务投递失败,账号状态事件继续完成 tenantId={} accountId={} errorType={}",
                    tenantId, accountId, ex.getClass().getSimpleName(), ex);
        }
    }

    private void runSync(Long tenantId, Long accountId) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            syncService.syncIfStale(accountId, ContactSyncSource.ONLINE_EVENT);
        } catch (RuntimeException ex) {
            log.warn("账号上线后通讯录同步失败,账号状态事件继续完成 tenantId={} accountId={} errorType={}",
                    tenantId, accountId, ex.getClass().getSimpleName(), ex);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest=AccountContactOnlineHookTest
```

Expected: PASS，4 个用例全绿

- [ ] **Step 5: 写配置类**

创建 `armada-api/src/main/java/com/armada/account/contact/config/AccountContactConfiguration.java`。
线程池规格照抄既有的 `AccountStateInviteRecoveryExecutorConfig`（池 2、队列 1024、优雅停机 30 秒），
但**必须是独立的池**——两个附属任务共池时，通讯录同步的 HTTP 阻塞会拖慢群邀请码恢复：

```java
package com.armada.account.contact.config;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.AccountContactOnlineHook;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.contact.service.impl.AccountContactSyncServiceImpl;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.port.ContactListPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 账号通讯录采集装配。 */
@Configuration
@EnableConfigurationProperties(AccountContactProperties.class)
public class AccountContactConfiguration {

    /** 后台同步并发数；与账号状态 Kafka 消费线程彻底隔离。 */
    private static final int POOL_SIZE = 2;

    /** 覆盖一次全量账号上线的等待队列容量。 */
    private static final int QUEUE_CAPACITY = 1024;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建通讯录同步专用线程池。
     *
     * <p>刻意不复用 accountStateInviteRecoveryExecutor：通讯录同步会做同步 HTTP 调用，
     * 与邀请码恢复共池会互相拖慢。</p>
     *
     * @return 通讯录同步后台执行器
     */
    @Bean(name = "accountContactSyncExecutor")
    public Executor accountContactSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("account-contact-sync-");
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * 装配通讯录采集服务。
     *
     * @param contactListPort 通讯录读取协议端口
     * @param contactMapper 联系人快照数据访问
     * @param syncMapper 同步状态数据访问
     * @param accountStateMapper 账号状态数据访问
     * @param normalizer 协议快照归一化器
     * @param properties 通讯录采集配置
     * @param protocolLookupService 账号协议引用查询服务
     * @return 通讯录采集服务
     */
    @Bean
    public AccountContactSyncService accountContactSyncService(
            ContactListPort contactListPort,
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer,
            AccountContactProperties properties,
            AccountProtocolLookupService protocolLookupService) {
        return new AccountContactSyncServiceImpl(
                contactListPort,
                contactMapper,
                syncMapper,
                accountStateMapper,
                normalizer,
                properties,
                accountId -> protocolLookupService.findActiveProtocolRef(accountId)
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.NOT_FOUND, "账号无可用协议引用: " + accountId)),
                TenantContext::get,
                System::currentTimeMillis);
    }

    /**
     * 装配账号上线后的通讯录同步钩子。
     *
     * @param syncService 通讯录采集服务
     * @param properties 通讯录采集配置
     * @param executor 通讯录同步后台执行器
     * @return 上线同步钩子
     */
    @Bean
    public AccountContactOnlineHook accountContactOnlineHook(
            AccountContactSyncService syncService,
            AccountContactProperties properties,
            @Qualifier("accountContactSyncExecutor") Executor executor) {
        return new AccountContactOnlineHook(syncService, properties, executor);
    }
}
```

> `BusinessException` / `ErrorCode.NOT_FOUND` 在 `com.armada.shared.exception` 下，全仓通用。
> 解析不到协议引用时抛出，会被 `AccountContactOnlineHook` 的 catch 吞掉只打 warn，
> 不影响账号状态主链。

- [ ] **Step 6: 挂进 `AccountStateChangedSinkAdapter`**

构造参数加 `AccountContactOnlineHook contactOnlineHook` 并存字段，把 ONLINE 分支从：

```java
        if (applied && "ONLINE".equalsIgnoreCase(event.to())) {
            submitDeferredInviteResume(event);
        }
```

改为：

```java
        if (applied && "ONLINE".equalsIgnoreCase(event.to())) {
            submitDeferredInviteResume(event);
            contactOnlineHook.onAccountOnline(event.tenantId(), event.accountId());
        }
```

- [ ] **Step 7: 全量回归**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test
```

Expected: `Tests run` = 基线 3437 + 本计划新增用例数；`Failures: 7`、`Errors: 461` 与基线**完全一致**。
**任何 Failures / Errors 的增长都必须查清，不能一句「环境问题」带过。**

- [ ] **Step 8: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/contact/
git add armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java
git add armada-api/src/test/java/com/armada/account/contact/AccountContactOnlineHookTest.java
git commit -m "feat(contact): sync contacts after account goes online"
```

---

## 出口条件与遗留

### 本地可验证

| 项 | 方式 |
|---|---|
| 迁移脚本契约 | `AccountContactMigrationSqlTest`（SQL 文本，不需库） |
| 归一化与计数口径 | `AccountContactNormalizerTest` |
| TTL 判定与配置默认值 | `ContactSnapshotFreshnessTest` |
| Mapper XML 与接口一致性 | `AccountContactMapperXmlTest`（静态契约，不需库） |
| 同步服务写入顺序与失败语义 | `AccountContactSyncServiceImplTest`（Mockito） |
| 上线钩子的异常隔离 | `AccountContactOnlineHookTest` |

### 本地**不可**验证，必须在有库环境补

| # | 项 | 风险 |
|---|---|---|
| 1 | `V157` 能否真正跑通 Flyway | 语法或与既有约束冲突只有真库能发现 |
| 2 | `upsertBatch` + `deleteStale` 在同账号并发同步下是否正确 | 两次同步交错可能互删对方刚写的行 |
| 3 | 租户拦截器是否正确注入到两张新表 | 注入失败会跨租户串数据 |
| 4 | `updateContactCounts` 与 `account_state` 其他写入是否互相覆盖 | 计数可能被状态更新回滚 |

### 依赖真机验证的口径（P0 遗留，未解决）

- **V2 双向好友**：两套协议都不暴露该标记，`is_mutual` / `mutual_num` / `contact_mutual_num` 恒为 0。
  验证通过后只需改 `AccountContactNormalizer` 一处。
  **在此之前，前端不得渲染「双向好友数 ≥/≤」筛选控件。**
- **V1 Baileys 全量性**：若冷启动后只拿到增量，`contact_num` 会偏小，P3 的「计划发送总数」随之失真。
