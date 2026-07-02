# 账号上线离线可观测性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每次账号上线生成可追踪的 `onlineAttemptId`，让 VERIFYING 卡住、428、心跳超时、救援失败、认证失效等离线原因能在 Armada 持久化时间线中查到。

**Architecture:** Armada 负责生成 attempt id、写 outbox、消费协议诊断事件并落库；协议层负责保留命令上下文、把关键离线节点归类成诊断码并发布 `account.offline_diagnosed`。`account.state_changed` 继续只负责状态同步，诊断链路独立失败、独立降级。

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway, MySQL, JUnit 5, AssertJ, Mockito, MockMvc, TypeScript, Node 20, Jest, KafkaJS, Baileys.

---

## Source Spec

- 中文设计稿：`docs/superpowers/specs/2026-07-02-account-online-observability-design.md`
- Armada 命令 outbox：`armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Armada 命令 publisher：`armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`
- Armada 协议事件 consumer：`armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- 协议层 worker 命令消费：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
- 协议层账号状态机入口：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`

## Scope Check

这是一条跨仓链路，但不是两个独立功能。单独只做 Armada 落库查不到协议层诊断，只做协议层事件也无法给测试环境形成持久化排查入口，所以放在一个实施计划中。

本计划包含：

- Armada 数据表、mapper、服务、consumer、查询接口。
- Armada 上线命令携带 `onlineAttemptId`。
- Armada publisher 把 attempt 字段带到 Kafka payload。
- 协议层 command envelope 保留 `batchId`，worker online 保留 `commandId/batchId/onlineAttemptId/proxyId/source`。
- 协议层诊断码映射和 `account.offline_diagnosed` 事件。
- 单元测试、DbTest、MockMvc 测试和协议层 Jest 测试。

本计划不包含：

- 前端页面。
- 底层代理 TCP/DNS/TLS/CONNECT 分阶段埋点。
- 改造代理分配策略。
- 改造 Baileys。

## File Structure

Armada 创建：

- `armada-api/src/main/resources/db/migration/V030__account_online_attempt_log.sql`
- `armada-api/src/main/java/com/armada/account/model/entity/AccountOnlineAttemptLog.java`
- `armada-api/src/main/java/com/armada/account/mapper/AccountOnlineAttemptLogMapper.java`
- `armada-api/src/main/resources/mapper/account/AccountOnlineAttemptLogMapper.xml`
- `armada-api/src/main/java/com/armada/account/service/AccountOfflineDiagnosedEvent.java`
- `armada-api/src/main/java/com/armada/account/service/AccountOnlineAttemptLogService.java`
- `armada-api/src/main/java/com/armada/account/service/OnlineAttemptIdGenerator.java`
- `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImpl.java`
- `armada-api/src/main/java/com/armada/account/service/impl/AccountOfflineDiagnosedSinkAdapter.java`
- `armada-api/src/main/java/com/armada/account/model/vo/AccountOnlineAttemptLogVO.java`
- `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountOfflineDiagnosedEvent.java`
- `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountOfflineDiagnosedSink.java`
- `armada-api/src/test/java/com/armada/account/mapper/AccountOnlineAttemptLogMapperDbTest.java`
- `armada-api/src/test/java/com/armada/account/service/OnlineAttemptIdGeneratorTest.java`
- `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImplTest.java`

Armada 修改：

- `armada-api/src/main/java/com/armada/account/controller/AccountController.java`
- `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolOnlineCommandRequest.java`
- `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`
- `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- `armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java`
- `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
- `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`
- `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`

协议层创建：

- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/offline-diagnosis.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/offline-diagnosis.test.ts`

协议层修改：

- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/events/subjects.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/observability/metrics.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/types.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/types.test.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.transient.test.ts`
- `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.heartbeat.test.ts`

## Version Guard

实施前在 `armada` 仓库运行：

```bash
ls armada-api/src/main/resources/db/migration | sort | tail
```

当前最新 migration 是 `V029__ip_proxy_nullable_check_status.sql`，本计划使用 `V030__account_online_attempt_log.sql`。如果执行时已经出现 `V030`，先把本计划中的 migration 文件名整体改成下一个空闲版本。

---

## Task 1: Armada 诊断时间线表和 Mapper

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V030__account_online_attempt_log.sql`
- Create: `armada-api/src/main/java/com/armada/account/model/entity/AccountOnlineAttemptLog.java`
- Create: `armada-api/src/main/java/com/armada/account/mapper/AccountOnlineAttemptLogMapper.java`
- Create: `armada-api/src/main/resources/mapper/account/AccountOnlineAttemptLogMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountOnlineAttemptLogMapperDbTest.java`

- [ ] **Step 1: Write the failing DbTest**

Create `AccountOnlineAttemptLogMapperDbTest.java`:

```java
package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.armada.testsupport.DbTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountOnlineAttemptLogMapperDbTest extends DbTestBase {

    @Autowired
    private AccountOnlineAttemptLogMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schema_hasDiagnosisTimelineColumnsAndIndexes() {
        assertThat(tableExists("account_online_attempt_log")).isTrue();
        assertThat(columnType("account_online_attempt_log", "online_attempt_id")).isEqualTo("varchar");
        assertThat(columnType("account_online_attempt_log", "diagnosis_code")).isEqualTo("varchar");
        assertThat(columnType("account_online_attempt_log", "evidence_json")).isEqualTo("json");
        assertThat(indexColumns("idx_attempt")).containsExactly("tenant_id", "online_attempt_id");
        assertThat(indexColumns("idx_account_time")).containsExactly("tenant_id", "account_id", "occurred_at");
        assertThat(indexColumns("idx_proxy_time")).containsExactly("tenant_id", "proxy_id", "occurred_at");
        assertThat(indexColumns("idx_code_time")).containsExactly("tenant_id", "diagnosis_code", "occurred_at");
    }

    @Test
    void insertAndQuery_roundTripsOfflineDiagnosisEvidence() {
        AccountOnlineAttemptLog row = sampleRow("oa_20260702101716_x7k9m2", 9L, 4035L);

        int inserted = mapper.insert(row);

        assertThat(inserted).isEqualTo(1);
        assertThat(row.getId()).isNotNull();

        List<AccountOnlineAttemptLog> byAttempt = mapper.selectByAttemptId("oa_20260702101716_x7k9m2", 20);
        assertThat(byAttempt).singleElement().satisfies(found -> {
            assertThat(found.getTenantId()).isEqualTo(TEST_TENANT_ID);
            assertThat(found.getAccountId()).isEqualTo(9L);
            assertThat(found.getProtocolAccountId()).isEqualTo("acc_252625852450");
            assertThat(found.getOnlineAttemptId()).isEqualTo("oa_20260702101716_x7k9m2");
            assertThat(found.getCommandId()).isEqualTo("cmd_1");
            assertThat(found.getBatchId()).isEqualTo("batch_1");
            assertThat(found.getProxyId()).isEqualTo(4035L);
            assertThat(found.getSource()).isEqualTo("batch_online");
            assertThat(found.getFromState()).isEqualTo("VERIFYING");
            assertThat(found.getToState()).isEqualTo("PROXY_FAILED");
            assertThat(found.getDiagnosisCode()).isEqualTo("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
            assertThat(found.getRawCode()).isEqualTo(408);
            assertThat(found.getEvidenceJson()).contains("\"connectionField\":\"connecting\"");
        });

        List<AccountOnlineAttemptLog> recent = mapper.selectRecentByAccountId(9L, 10);
        assertThat(recent).extracting(AccountOnlineAttemptLog::getOnlineAttemptId)
                .contains("oa_20260702101716_x7k9m2");
    }

    private static AccountOnlineAttemptLog sampleRow(String attemptId, Long accountId, Long proxyId) {
        AccountOnlineAttemptLog row = new AccountOnlineAttemptLog();
        row.setAccountId(accountId);
        row.setProtocolAccountId("acc_252625852450");
        row.setOnlineAttemptId(attemptId);
        row.setPreviousOnlineAttemptId(null);
        row.setCommandId("cmd_1");
        row.setBatchId("batch_1");
        row.setProxyId(proxyId);
        row.setSource("batch_online");
        row.setFromState("VERIFYING");
        row.setToState("PROXY_FAILED");
        row.setDiagnosisCode("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
        row.setDiagnosisClass("PROXY_OR_WA_CONNECTIVITY");
        row.setRawCode(408);
        row.setRawReason("no connection.update open/close before verify timeout");
        row.setRecoverability("RETRYABLE");
        row.setActionTaken("MARK_PROXY_FAILED_RELEASE_SLOT");
        row.setWorkerId("w3");
        row.setEvidenceJson("{\"connectionField\":\"connecting\",\"wsOpen\":false}");
        row.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 18, 0, 123_000_000));
        row.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 18, 1, 0));
        return row;
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private List<String> indexColumns(String indexName) {
        return jdbc.query(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'account_online_attempt_log' "
                        + "AND index_name = ? ORDER BY seq_in_index",
                (rs, rowNum) -> rs.getString("column_name"),
                indexName);
    }
}
```

- [ ] **Step 2: Run the focused DbTest and verify RED**

Run from `armada`:

```bash
armada-api/dbtest.sh AccountOnlineAttemptLogMapperDbTest
```

Expected: FAIL because the mapper, entity, XML, and table do not exist.

- [ ] **Step 3: Add migration**

Create `V030__account_online_attempt_log.sql`:

```sql
CREATE TABLE account_online_attempt_log (
    id                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id                  BIGINT       NOT NULL                COMMENT '租户ID',
    account_id                 BIGINT       NOT NULL                COMMENT 'Armada账号ID',
    protocol_account_id        VARCHAR(128) NOT NULL                COMMENT '协议层账号ID',
    online_attempt_id          VARCHAR(64)  NOT NULL                COMMENT '本次上线尝试ID',
    previous_online_attempt_id VARCHAR(64)           DEFAULT NULL   COMMENT '上一轮上线尝试ID',
    command_id                 VARCHAR(64)           DEFAULT NULL   COMMENT '协议命令ID',
    batch_id                   VARCHAR(64)           DEFAULT NULL   COMMENT '批量命令ID',
    proxy_id                   BIGINT                DEFAULT NULL   COMMENT '本次使用的代理ID',
    source                     VARCHAR(64)           DEFAULT NULL   COMMENT '上线来源',
    from_state                 VARCHAR(32)           DEFAULT NULL   COMMENT '协议层原状态',
    to_state                   VARCHAR(32)           DEFAULT NULL   COMMENT '协议层目标状态',
    diagnosis_code             VARCHAR(64)  NOT NULL                COMMENT '诊断码',
    diagnosis_class            VARCHAR(64)  NOT NULL                COMMENT '诊断分类',
    raw_code                   INT                   DEFAULT NULL   COMMENT '协议层原始错误码',
    raw_reason                 VARCHAR(512)          DEFAULT NULL   COMMENT '协议层原始原因,已截断',
    recoverability             VARCHAR(32)           DEFAULT NULL   COMMENT '是否可重试',
    action_taken               VARCHAR(64)           DEFAULT NULL   COMMENT '协议层已执行动作',
    worker_id                  VARCHAR(64)           DEFAULT NULL   COMMENT '协议worker ID',
    evidence_json              JSON                  DEFAULT NULL   COMMENT '脱敏后的诊断证据',
    occurred_at                DATETIME(3)  NOT NULL                COMMENT '协议层事件发生时间',
    created_at                 DATETIME(3)  NOT NULL                COMMENT 'Armada落库时间',
    PRIMARY KEY (id),
    KEY idx_attempt (tenant_id, online_attempt_id),
    KEY idx_account_time (tenant_id, account_id, occurred_at),
    KEY idx_proxy_time (tenant_id, proxy_id, occurred_at),
    KEY idx_code_time (tenant_id, diagnosis_code, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号上线尝试诊断时间线';
```

- [ ] **Step 4: Add entity and mapper**

Create `AccountOnlineAttemptLog.java` as a plain POJO. It must expose standard JavaBean getters/setters for every field shown below, because MyBatis XML maps by camel-case property names and the tests call getters directly:

```java
package com.armada.account.model.entity;

import java.time.LocalDateTime;

/**
 * 账号上线尝试诊断时间线实体,映射 account_online_attempt_log。
 */
public class AccountOnlineAttemptLog {
    private Long id;
    private Long tenantId;
    private Long accountId;
    private String protocolAccountId;
    private String onlineAttemptId;
    private String previousOnlineAttemptId;
    private String commandId;
    private String batchId;
    private Long proxyId;
    private String source;
    private String fromState;
    private String toState;
    private String diagnosisCode;
    private String diagnosisClass;
    private Integer rawCode;
    private String rawReason;
    private String recoverability;
    private String actionTaken;
    private String workerId;
    private String evidenceJson;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
```

The final file must compile without Lombok and must not add business logic to this entity.

Create `AccountOnlineAttemptLogMapper.java`:

```java
package com.armada.account.mapper;

import com.armada.account.model.entity.AccountOnlineAttemptLog;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountOnlineAttemptLogMapper {

    int insert(AccountOnlineAttemptLog row);

    List<AccountOnlineAttemptLog> selectByAttemptId(@Param("onlineAttemptId") String onlineAttemptId,
                                                    @Param("limit") int limit);

    List<AccountOnlineAttemptLog> selectRecentByAccountId(@Param("accountId") Long accountId,
                                                          @Param("limit") int limit);

    String selectLatestAttemptIdByAccountId(@Param("accountId") Long accountId);

    int deleteBefore(@Param("cutoff") LocalDateTime cutoff,
                     @Param("limit") int limit);
}
```

Create `AccountOnlineAttemptLogMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.account.mapper.AccountOnlineAttemptLogMapper">

  <sql id="Columns">
    id, tenant_id, account_id, protocol_account_id, online_attempt_id,
    previous_online_attempt_id, command_id, batch_id, proxy_id, source,
    from_state, to_state, diagnosis_code, diagnosis_class, raw_code, raw_reason,
    recoverability, action_taken, worker_id, evidence_json, occurred_at, created_at
  </sql>

  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO account_online_attempt_log (
      account_id, protocol_account_id, online_attempt_id, previous_online_attempt_id,
      command_id, batch_id, proxy_id, source, from_state, to_state,
      diagnosis_code, diagnosis_class, raw_code, raw_reason, recoverability,
      action_taken, worker_id, evidence_json, occurred_at, created_at
    ) VALUES (
      #{accountId}, #{protocolAccountId}, #{onlineAttemptId}, #{previousOnlineAttemptId},
      #{commandId}, #{batchId}, #{proxyId}, #{source}, #{fromState}, #{toState},
      #{diagnosisCode}, #{diagnosisClass}, #{rawCode}, #{rawReason}, #{recoverability},
      #{actionTaken}, #{workerId}, #{evidenceJson}, #{occurredAt}, #{createdAt}
    )
  </insert>

  <select id="selectByAttemptId" resultType="com.armada.account.model.entity.AccountOnlineAttemptLog">
    SELECT <include refid="Columns"/>
    FROM account_online_attempt_log
    WHERE online_attempt_id = #{onlineAttemptId}
    ORDER BY occurred_at ASC, id ASC
    LIMIT #{limit}
  </select>

  <select id="selectRecentByAccountId" resultType="com.armada.account.model.entity.AccountOnlineAttemptLog">
    SELECT <include refid="Columns"/>
    FROM account_online_attempt_log
    WHERE account_id = #{accountId}
    ORDER BY occurred_at DESC, id DESC
    LIMIT #{limit}
  </select>

  <select id="selectLatestAttemptIdByAccountId" resultType="string">
    SELECT online_attempt_id
    FROM account_online_attempt_log
    WHERE account_id = #{accountId}
    ORDER BY occurred_at DESC, id DESC
    LIMIT 1
  </select>

  <delete id="deleteBefore">
    DELETE FROM account_online_attempt_log
    WHERE occurred_at &lt; #{cutoff}
    ORDER BY occurred_at ASC, id ASC
    LIMIT #{limit}
  </delete>
</mapper>
```

- [ ] **Step 5: Run Task 1 tests and verify GREEN**

Run:

```bash
armada-api/dbtest.sh AccountOnlineAttemptLogMapperDbTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add armada-api/src/main/resources/db/migration/V030__account_online_attempt_log.sql \
  armada-api/src/main/java/com/armada/account/model/entity/AccountOnlineAttemptLog.java \
  armada-api/src/main/java/com/armada/account/mapper/AccountOnlineAttemptLogMapper.java \
  armada-api/src/main/resources/mapper/account/AccountOnlineAttemptLogMapper.xml \
  armada-api/src/test/java/com/armada/account/mapper/AccountOnlineAttemptLogMapperDbTest.java
git commit -m "feat: add account online attempt log"
```

---

## Task 2: Armada 诊断事件解析和落库服务

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/service/AccountOfflineDiagnosedEvent.java`
- Create: `armada-api/src/main/java/com/armada/account/service/AccountOnlineAttemptLogService.java`
- Create: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/account/service/impl/AccountOfflineDiagnosedSinkAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountOfflineDiagnosedEvent.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountOfflineDiagnosedSink.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- Test: `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`
- Test: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImplTest.java`

- [ ] **Step 1: Write failing consumer test**

In `ProtocolAccountEventConsumerTest`, add a mock sink and update constructor setup:

```java
@Mock
private ProtocolAccountOfflineDiagnosedSink offlineDiagnosedSink;

@BeforeEach
void setUp() {
    consumer = new ProtocolAccountEventConsumer(
            new ObjectMapper(),
            sink,
            groupsReportedSink,
            offlineDiagnosedSink);
}
```

Add this test:

```java
@Test
void onMessage_offlineDiagnosedEnvelope_dispatchesParsedDiagnosisEvent() {
    String raw = """
            {
              "eventId": "evt-diagnosis-1",
              "event": "account.offline_diagnosed",
              "version": "v1",
              "accountId": "acc_252625852450",
              "occurredAt": "2026-07-02T10:18:00.123Z",
              "workerId": "w3",
              "evidence": {
                "connectionField": "connecting",
                "wsOpen": false
              },
              "data": {
                "tenantId": 1,
                "accountId": 9,
                "protocolAccountId": "acc_252625852450",
                "onlineAttemptId": "oa_20260702101716_x7k9m2",
                "previousOnlineAttemptId": null,
                "commandId": "cmd_1",
                "batchId": "batch_1",
                "proxyId": 4035,
                "source": "batch_online",
                "from": "VERIFYING",
                "to": "PROXY_FAILED",
                "diagnosisCode": "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE",
                "diagnosisClass": "PROXY_OR_WA_CONNECTIVITY",
                "rawCode": 408,
                "rawReason": "no connection.update open/close before verify timeout",
                "recoverability": "RETRYABLE",
                "actionTaken": "MARK_PROXY_FAILED_RELEASE_SLOT"
              }
            }
            """;

    consumer.onMessage(raw);

    ArgumentCaptor<ProtocolAccountOfflineDiagnosedEvent> captor =
            ArgumentCaptor.forClass(ProtocolAccountOfflineDiagnosedEvent.class);
    verify(offlineDiagnosedSink).handleOfflineDiagnosed(captor.capture());
    ProtocolAccountOfflineDiagnosedEvent event = captor.getValue();
    assertThat(event.eventId()).isEqualTo("evt-diagnosis-1");
    assertThat(event.tenantId()).isEqualTo(1L);
    assertThat(event.accountId()).isEqualTo(9L);
    assertThat(event.protocolAccountId()).isEqualTo("acc_252625852450");
    assertThat(event.onlineAttemptId()).isEqualTo("oa_20260702101716_x7k9m2");
    assertThat(event.proxyId()).isEqualTo(4035L);
    assertThat(event.from()).isEqualTo("VERIFYING");
    assertThat(event.to()).isEqualTo("PROXY_FAILED");
    assertThat(event.diagnosisCode()).isEqualTo("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
    assertThat(event.rawCode()).isEqualTo(408);
    assertThat(event.occurredAt()).isEqualTo(1782987480123L);
    assertThat(event.workerId()).isEqualTo("w3");
    assertThat(event.evidenceJson()).contains("\"connectionField\":\"connecting\"");
    verifyNoInteractions(sink, groupsReportedSink);
}
```

- [ ] **Step 2: Write failing service test**

Create `AccountOnlineAttemptLogServiceImplTest.java`:

```java
package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.account.mapper.AccountOnlineAttemptLogMapper;
import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.armada.account.service.AccountOfflineDiagnosedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountOnlineAttemptLogServiceImplTest {

    private final AccountOnlineAttemptLogMapper mapper = org.mockito.Mockito.mock(AccountOnlineAttemptLogMapper.class);
    private final AccountOnlineAttemptLogServiceImpl service = new AccountOnlineAttemptLogServiceImpl(mapper);

    @Test
    void applyOfflineDiagnosed_truncatesRawReasonAndPersistsDiagnosisLog() {
        String longReason = "x".repeat(800);
        AccountOfflineDiagnosedEvent event = new AccountOfflineDiagnosedEvent(
                1L, 9L, "acc_252625852450", "oa_1", null, "cmd_1", "batch_1",
                4035L, "batch_online", "VERIFYING", "PROXY_FAILED",
                "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE", "PROXY_OR_WA_CONNECTIVITY",
                408, longReason, "RETRYABLE", "MARK_PROXY_FAILED_RELEASE_SLOT",
                1782987480123L, "w3", "{\"wsOpen\":false}");

        service.applyOfflineDiagnosed(event);

        ArgumentCaptor<AccountOnlineAttemptLog> captor = ArgumentCaptor.forClass(AccountOnlineAttemptLog.class);
        verify(mapper).insert(captor.capture());
        AccountOnlineAttemptLog row = captor.getValue();
        assertThat(row.getAccountId()).isEqualTo(9L);
        assertThat(row.getOnlineAttemptId()).isEqualTo("oa_1");
        assertThat(row.getRawReason()).hasSize(512);
        assertThat(row.getEvidenceJson()).isEqualTo("{\"wsOpen\":false}");
        assertThat(row.getOccurredAt()).isEqualTo("2026-07-02T10:18:00.123");
        assertThat(row.getCreatedAt()).isNotNull();
    }
}
```

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=ProtocolAccountEventConsumerTest,AccountOnlineAttemptLogServiceImplTest test
```

Expected: FAIL because new event, sink, and service classes do not exist.

- [ ] **Step 4: Add platform event record and sink**

Create `ProtocolAccountOfflineDiagnosedEvent.java`:

```java
package com.armada.platform.kafka.consumer.account;

public record ProtocolAccountOfflineDiagnosedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String onlineAttemptId,
        String previousOnlineAttemptId,
        String commandId,
        String batchId,
        Long proxyId,
        String source,
        String from,
        String to,
        String diagnosisCode,
        String diagnosisClass,
        Integer rawCode,
        String rawReason,
        String recoverability,
        String actionTaken,
        Long occurredAt,
        String workerId,
        String evidenceJson) {
}
```

Create `ProtocolAccountOfflineDiagnosedSink.java`:

```java
package com.armada.platform.kafka.consumer.account;

public interface ProtocolAccountOfflineDiagnosedSink {

    void handleOfflineDiagnosed(ProtocolAccountOfflineDiagnosedEvent event);
}
```

- [ ] **Step 5: Extend ProtocolAccountEventConsumer**

Add the event constant, constructor dependency, and branch:

```java
public static final String EVENT_ACCOUNT_OFFLINE_DIAGNOSED = "account.offline_diagnosed";

private final ProtocolAccountOfflineDiagnosedSink offlineDiagnosedSink;
```

Constructor:

```java
public ProtocolAccountEventConsumer(ObjectMapper objectMapper,
                                    ProtocolAccountStateChangedSink stateChangedSink,
                                    ProtocolAccountGroupsReportedSink groupsReportedSink,
                                    ProtocolAccountOfflineDiagnosedSink offlineDiagnosedSink) {
    this.objectMapper = objectMapper;
    this.stateChangedSink = stateChangedSink;
    this.groupsReportedSink = groupsReportedSink;
    this.offlineDiagnosedSink = offlineDiagnosedSink;
}
```

Branch in `onMessage` before the unknown-event warning:

```java
if (EVENT_ACCOUNT_OFFLINE_DIAGNOSED.equals(eventType)) {
    ProtocolAccountOfflineDiagnosedEvent event = toOfflineDiagnosedEvent(envelope);
    log.info("协议账号离线诊断事件收到 eventId={} tenantId={} accountId={} protocolAccountId={} "
                    + "attemptId={} diagnosisCode={} rawCode={} workerId={}",
            event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
            event.onlineAttemptId(), event.diagnosisCode(), event.rawCode(), event.workerId());
    offlineDiagnosedSink.handleOfflineDiagnosed(event);
    return;
}
```

Add parser helpers:

```java
private ProtocolAccountOfflineDiagnosedEvent toOfflineDiagnosedEvent(JsonNode envelope) {
    JsonNode data = dataNode(envelope);
    String evidenceJson = null;
    JsonNode evidence = envelope.path("evidence").isObject() ? envelope.path("evidence") : data.path("evidence");
    if (evidence.isObject()) {
        evidenceJson = evidence.toString();
    }
    return new ProtocolAccountOfflineDiagnosedEvent(
            text(envelope, "eventId"),
            requiredLong(data, "tenantId", "协议账号离线诊断事件缺少 data.tenantId"),
            requiredLong(data, "accountId", "协议账号离线诊断事件缺少 data.accountId"),
            requiredText(data, "protocolAccountId", "协议账号离线诊断事件缺少 data.protocolAccountId"),
            requiredText(data, "onlineAttemptId", "协议账号离线诊断事件缺少 data.onlineAttemptId"),
            text(data, "previousOnlineAttemptId"),
            text(data, "commandId"),
            text(data, "batchId"),
            longValue(data, "proxyId"),
            text(data, "source"),
            text(data, "from"),
            requiredText(data, "to", "协议账号离线诊断事件缺少 data.to"),
            requiredText(data, "diagnosisCode", "协议账号离线诊断事件缺少 data.diagnosisCode"),
            requiredText(data, "diagnosisClass", "协议账号离线诊断事件缺少 data.diagnosisClass"),
            integer(data, "rawCode"),
            text(data, "rawReason"),
            text(data, "recoverability"),
            text(data, "actionTaken"),
            occurredAt(envelope),
            text(envelope, "workerId"),
            evidenceJson);
}
```

`longValue` already exists in the consumer and can be reused.

- [ ] **Step 6: Add account domain service and adapter**

Create `AccountOfflineDiagnosedEvent.java` with the same business fields, without `eventId`:

```java
package com.armada.account.service;

public record AccountOfflineDiagnosedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String onlineAttemptId,
        String previousOnlineAttemptId,
        String commandId,
        String batchId,
        Long proxyId,
        String source,
        String from,
        String to,
        String diagnosisCode,
        String diagnosisClass,
        Integer rawCode,
        String rawReason,
        String recoverability,
        String actionTaken,
        Long occurredAt,
        String workerId,
        String evidenceJson) {
}
```

Create `AccountOnlineAttemptLogService.java`:

```java
package com.armada.account.service;

import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import java.util.List;

public interface AccountOnlineAttemptLogService {

    void applyOfflineDiagnosed(AccountOfflineDiagnosedEvent event);

    List<AccountOnlineAttemptLogVO> recentByAccount(Long accountId, int limit);

    List<AccountOnlineAttemptLogVO> timeline(String onlineAttemptId, int limit);

    String latestAttemptId(Long accountId);
}
```

Create `AccountOnlineAttemptLogServiceImpl.java`:

```java
package com.armada.account.service.impl;

import com.armada.account.mapper.AccountOnlineAttemptLogMapper;
import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import com.armada.account.service.AccountOfflineDiagnosedEvent;
import com.armada.account.service.AccountOnlineAttemptLogService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AccountOnlineAttemptLogServiceImpl implements AccountOnlineAttemptLogService {

    private static final int RAW_REASON_MAX_LENGTH = 512;
    private final AccountOnlineAttemptLogMapper mapper;

    public AccountOnlineAttemptLogServiceImpl(AccountOnlineAttemptLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void applyOfflineDiagnosed(AccountOfflineDiagnosedEvent event) {
        AccountOnlineAttemptLog row = new AccountOnlineAttemptLog();
        row.setAccountId(event.accountId());
        row.setProtocolAccountId(event.protocolAccountId());
        row.setOnlineAttemptId(event.onlineAttemptId());
        row.setPreviousOnlineAttemptId(event.previousOnlineAttemptId());
        row.setCommandId(event.commandId());
        row.setBatchId(event.batchId());
        row.setProxyId(event.proxyId());
        row.setSource(event.source());
        row.setFromState(event.from());
        row.setToState(event.to());
        row.setDiagnosisCode(event.diagnosisCode());
        row.setDiagnosisClass(event.diagnosisClass());
        row.setRawCode(event.rawCode());
        row.setRawReason(truncate(event.rawReason(), RAW_REASON_MAX_LENGTH));
        row.setRecoverability(event.recoverability());
        row.setActionTaken(event.actionTaken());
        row.setWorkerId(event.workerId());
        row.setEvidenceJson(event.evidenceJson());
        row.setOccurredAt(epochMillis(event.occurredAt()));
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        mapper.insert(row);
    }

    @Override
    public List<AccountOnlineAttemptLogVO> recentByAccount(Long accountId, int limit) {
        return mapper.selectRecentByAccountId(accountId, normalizeLimit(limit)).stream()
                .map(AccountOnlineAttemptLogVO::from)
                .toList();
    }

    @Override
    public List<AccountOnlineAttemptLogVO> timeline(String onlineAttemptId, int limit) {
        return mapper.selectByAttemptId(onlineAttemptId, normalizeLimit(limit)).stream()
                .map(AccountOnlineAttemptLogVO::from)
                .toList();
    }

    @Override
    public String latestAttemptId(Long accountId) {
        return mapper.selectLatestAttemptIdByAccountId(accountId);
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, 200);
    }

    private static LocalDateTime epochMillis(Long value) {
        long millis = value == null ? System.currentTimeMillis() : value;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
```

Create `AccountOfflineDiagnosedSinkAdapter.java`:

```java
package com.armada.account.service.impl;

import com.armada.account.service.AccountOfflineDiagnosedEvent;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountOfflineDiagnosedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountOfflineDiagnosedSink;
import org.springframework.stereotype.Service;

@Service
public class AccountOfflineDiagnosedSinkAdapter implements ProtocolAccountOfflineDiagnosedSink {

    private final AccountOnlineAttemptLogService service;

    public AccountOfflineDiagnosedSinkAdapter(AccountOnlineAttemptLogService service) {
        this.service = service;
    }

    @Override
    public void handleOfflineDiagnosed(ProtocolAccountOfflineDiagnosedEvent event) {
        service.applyOfflineDiagnosed(new AccountOfflineDiagnosedEvent(
                event.tenantId(),
                event.accountId(),
                event.protocolAccountId(),
                event.onlineAttemptId(),
                event.previousOnlineAttemptId(),
                event.commandId(),
                event.batchId(),
                event.proxyId(),
                event.source(),
                event.from(),
                event.to(),
                event.diagnosisCode(),
                event.diagnosisClass(),
                event.rawCode(),
                event.rawReason(),
                event.recoverability(),
                event.actionTaken(),
                event.occurredAt(),
                event.workerId(),
                event.evidenceJson()));
    }
}
```

- [ ] **Step 7: Run Task 2 tests and verify GREEN**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=ProtocolAccountEventConsumerTest,AccountOnlineAttemptLogServiceImplTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add armada-api/src/main/java/com/armada/account/service/AccountOfflineDiagnosedEvent.java \
  armada-api/src/main/java/com/armada/account/service/AccountOnlineAttemptLogService.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImpl.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountOfflineDiagnosedSinkAdapter.java \
  armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountOfflineDiagnosedEvent.java \
  armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountOfflineDiagnosedSink.java \
  armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java \
  armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImplTest.java
git commit -m "feat: persist account offline diagnoses"
```

---

## Task 3: Armada 生成并写入 onlineAttemptId

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/service/OnlineAttemptIdGenerator.java`
- Create: `armada-api/src/test/java/com/armada/account/service/OnlineAttemptIdGeneratorTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolOnlineCommandRequest.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`

- [ ] **Step 1: Write failing generator test**

Create `OnlineAttemptIdGeneratorTest.java`:

```java
package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OnlineAttemptIdGeneratorTest {

    @Test
    void nextId_returnsPrefixedTimestampAndShortRandomSuffix() {
        OnlineAttemptIdGenerator generator = new OnlineAttemptIdGenerator();

        String first = generator.nextId();
        String second = generator.nextId();

        assertThat(first).matches("oa_\\d{14}_[a-z0-9]{6,12}");
        assertThat(second).matches("oa_\\d{14}_[a-z0-9]{6,12}");
        assertThat(second).isNotEqualTo(first);
    }
}
```

- [ ] **Step 2: Extend account service tests**

In `AccountOnlineCommandServiceImplTest`, add:

```java
@Mock
private OnlineAttemptIdGenerator onlineAttemptIdGenerator;

@Mock
private AccountOnlineAttemptLogService accountOnlineAttemptLogService;
```

In tests that enqueue online commands, stub ids before calling `service.online` or `service.onlineBatch`:

```java
when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_test_single");
```

Add assertions:

```java
assertThat(command.onlineAttemptId()).isEqualTo("oa_test_single");
assertThat(command.previousOnlineAttemptId()).isNull();
```

For `reonlineAfterProxyFailure`, add a test that inserts a previous attempt through the service mock:

```java
when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_retry_1");
when(accountOnlineAttemptLogService.latestAttemptId(100L)).thenReturn("oa_previous_1");

service.reonlineAfterProxyFailure(100L);

ProtocolOnlineCommandRequest command = commandsCaptor.getValue().get(0);
assertThat(command.onlineAttemptId()).isEqualTo("oa_retry_1");
assertThat(command.previousOnlineAttemptId()).isEqualTo("oa_previous_1");
assertThat(command.source()).isEqualTo("proxy_failed_reonline");
```

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=OnlineAttemptIdGeneratorTest,AccountOnlineCommandServiceImplTest test
```

Expected: FAIL because the generator and new record fields do not exist.

- [ ] **Step 4: Add generator**

Create `OnlineAttemptIdGenerator.java`:

```java
package com.armada.account.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class OnlineAttemptIdGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public String nextId() {
        String timestamp = FORMATTER.format(LocalDateTime.now(ZoneOffset.UTC));
        String random = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
        String suffix = random.length() > 12 ? random.substring(0, 12) : random;
        if (suffix.length() < 6) {
            suffix = (suffix + "000000").substring(0, 6);
        }
        return "oa_" + timestamp + "_" + suffix;
    }
}
```

- [ ] **Step 5: Extend ProtocolOnlineCommandRequest**

Replace the record signature:

```java
public record ProtocolOnlineCommandRequest(
        Long accountId,
        String protocolAccountId,
        CredentialFormat credentialFormat,
        Long proxyId,
        String source,
        String onlineAttemptId,
        String previousOnlineAttemptId
) {
}
```

Update every `new ProtocolOnlineCommandRequest(...)` call to pass the two new fields.

- [ ] **Step 6: Inject generator and log service into AccountOnlineCommandServiceImpl**

Add fields:

```java
private final OnlineAttemptIdGenerator onlineAttemptIdGenerator;
private final AccountOnlineAttemptLogService accountOnlineAttemptLogService;
```

Update constructor parameters and assignments.

Add helper:

```java
private String previousAttemptId(Long accountId, String source) {
    if (!SOURCE_PROXY_FAILED_REONLINE.equals(source)) {
        return null;
    }
    return accountOnlineAttemptLogService.latestAttemptId(accountId);
}
```

When creating a single online command:

```java
String onlineAttemptId = onlineAttemptIdGenerator.nextId();
ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
        account.getId(),
        protocolAccountId,
        credentialFormat,
        allocation.proxyId(),
        source,
        onlineAttemptId,
        previousAttemptId(account.getId(), source));
```

When creating batch commands:

```java
String onlineAttemptId = onlineAttemptIdGenerator.nextId();
ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
        accountId,
        protocolAccountId,
        credentialFormat,
        allocation.proxyId(),
        source,
        onlineAttemptId,
        previousAttemptId(accountId, source));
```

Add attempt id to safe operational logs:

```java
log.info("账号上线写入 outbox 前准备 command accountId={} attemptId={} allocatedProxyId={} credentialFormat={} credentialLength={}",
        account.getId(), onlineAttemptId, allocation.proxyId(), credentialFormat, credentialLength(credential.getCredsJson()));
```

- [ ] **Step 7: Run Task 3 tests and verify GREEN**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=OnlineAttemptIdGeneratorTest,AccountOnlineCommandServiceImplTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add armada-api/src/main/java/com/armada/account/service/OnlineAttemptIdGenerator.java \
  armada-api/src/test/java/com/armada/account/service/OnlineAttemptIdGeneratorTest.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolOnlineCommandRequest.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java
git commit -m "feat: add online attempt ids to account commands"
```

---

## Task 4: Armada outbox payload 和 Kafka payload 透传 attempt 字段

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`

- [ ] **Step 1: Write failing outbox payload assertion**

In `ProtocolCommandOutboxServiceImplTest.enqueueOnlineCommands_singleCommand_insertsPendingRowWithStableEnvelopeAndSafePayload`, update helper command to include ids and add assertions:

```java
assertThat(payload)
        .containsEntry("onlineAttemptId", "oa_100")
        .containsEntry("previousOnlineAttemptId", "oa_99");
```

Update `onlineCommand` helper:

```java
return new ProtocolOnlineCommandRequest(
        accountId,
        protocolAccountId,
        credentialFormat,
        proxyId,
        "manual_online",
        "oa_" + accountId,
        "oa_" + (accountId - 1));
```

- [ ] **Step 2: Write failing Kafka payload assertion**

In `ProtocolCommandPublisherTest.publishBatch_onlineRowsHydratesCredentialsAndProxiesWithBulkQueriesBeforeSending`, include attempt fields in both outbox row JSON strings:

```java
"\"onlineAttemptId\":\"oa_100\",\"previousOnlineAttemptId\":\"oa_99\""
```

Assert on the captured envelope:

```java
assertThat(firstEnvelope.payload().get("onlineAttemptId").asText()).isEqualTo("oa_100");
assertThat(firstEnvelope.payload().get("previousOnlineAttemptId").asText()).isEqualTo("oa_99");
assertThat(firstEnvelope.payload().get("source").asText()).isEqualTo("batch_online");
```

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=ProtocolCommandOutboxServiceImplTest,ProtocolCommandPublisherTest test
```

Expected: FAIL because payloads do not include attempt fields.

- [ ] **Step 4: Extend outbox payload record**

In `ProtocolCommandOutboxServiceImpl.payloadJson(ProtocolOnlineCommandRequest command)`, pass attempt fields:

```java
ProtocolOnlineCommandPayload payload = new ProtocolOnlineCommandPayload(
        command.accountId(),
        command.protocolAccountId(),
        command.credentialFormat(),
        command.proxyId(),
        command.source(),
        command.onlineAttemptId(),
        command.previousOnlineAttemptId());
```

Update private record:

```java
private record ProtocolOnlineCommandPayload(
        Long accountId,
        String protocolAccountId,
        CredentialFormat credentialFormat,
        Long proxyId,
        String source,
        String onlineAttemptId,
        String previousOnlineAttemptId
) {
}
```

Add validation in `validateOnlineCommand`:

```java
if (isBlank(command.onlineAttemptId())) {
    throw new BusinessException(ErrorCode.VALIDATION, "协议上线命令缺少 onlineAttemptId");
}
```

- [ ] **Step 5: Extend publisher hydration**

In `ProtocolCommandPublisher.onlineRef`, read the fields:

```java
String onlineAttemptId = requiredText(payload, "onlineAttemptId", row.getCommandId());
String previousOnlineAttemptId = textOrDefault(payload, "previousOnlineAttemptId", null);
return new OnlineRowRef(row, tenantId, accountId, protocolAccountId, format, proxyId, source,
        onlineAttemptId, previousOnlineAttemptId);
```

Extend `OnlineRowRef`:

```java
private record OnlineRowRef(
        ProtocolCommandOutbox row,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        CredentialFormat format,
        Long proxyId,
        String source,
        String onlineAttemptId,
        String previousOnlineAttemptId
) {
}
```

Extend `OnlineCommandKafkaPayload`:

```java
private record OnlineCommandKafkaPayload(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String format,
        Map<String, Object> credential,
        ProxyDescriptor proxy,
        String source,
        String onlineAttemptId,
        String previousOnlineAttemptId
) {
}
```

Pass the fields when creating `OnlineCommandKafkaPayload`.

- [ ] **Step 6: Run Task 4 tests and verify GREEN**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=ProtocolCommandOutboxServiceImplTest,ProtocolCommandPublisherTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java \
  armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java \
  armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java \
  armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java
git commit -m "feat: propagate online attempt payloads"
```

---

## Task 5: 协议层命令上下文贯穿到 AccountManager

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/types.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/types.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.transient.test.ts`

- [ ] **Step 1: Write failing parseMasterCommand test**

In `types.test.ts`, add:

```ts
it('preserves nullable batchId from Armada command envelope', () => {
  const parsed = parseMasterCommand({
    commandId: 'cmd_online_1',
    batchId: 'batch_1',
    commandType: 'account.online.requested',
    aggregateType: 'ACCOUNT',
    aggregateId: 9,
    protocolAccountId: 'acc_252625852450',
    payload: {}
  })

  expect(parsed.ok).toBe(true)
  if (parsed.ok) {
    expect(parsed.command.batchId).toBe('batch_1')
  }
})
```

- [ ] **Step 2: Write failing worker-consumer test**

Update the existing `account.online.requested` test in `worker-consumer.test.ts`:

```ts
const command: MasterCommandEnvelope = {
  commandId: 'cmd_online_1',
  batchId: 'batch_1',
  type: 'account.online.requested',
  version: 'v1',
  accountId: 'acc_1',
  createdAt: '2026-06-29T00:00:00.000Z',
  payload: {
    tenantId: 1,
    accountId: 100,
    protocolAccountId: 'acc_1',
    format: 'baileys_json',
    credential: { creds: { me: { id: 'acc_1' } }, keys: {} },
    proxy,
    proxyId: 4035,
    source: 'batch_online',
    onlineAttemptId: 'oa_1',
    previousOnlineAttemptId: 'oa_0'
  }
}
```

Assert:

```ts
calls.push(`online:${accountId}:${source}`)
expect(businessRef).toEqual({
  tenantId: 1,
  accountId: 100,
  protocolAccountId: 'acc_1',
  onlineAttemptId: 'oa_1',
  previousOnlineAttemptId: 'oa_0',
  commandId: 'cmd_online_1',
  batchId: 'batch_1',
  proxyId: 4035,
  source: 'batch_online'
})
expect(calls).toEqual(['online:acc_1:batch_online', 'ack'])
```

- [ ] **Step 3: Run focused protocol tests and verify RED**

Run from `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer`:

```bash
npm test -- src/commands/types.test.ts src/commands/worker-consumer.test.ts --runInBand
```

Expected: FAIL because `batchId` and attempt context are not preserved.

- [ ] **Step 4: Extend MasterCommandEnvelope**

In `types.ts`, add `batchId`:

```ts
export interface MasterCommandEnvelope<TPayload = Record<string, unknown>> {
  commandId: string
  batchId?: string | null
  type: MasterCommandType
  version: 'v1'
  accountId: string
  createdAt: string
  payload: TPayload
}
```

In `parseMasterCommand`, set:

```ts
batchId: typeof value.batchId === 'string' && value.batchId.length > 0 ? value.batchId : null,
```

- [ ] **Step 5: Extend AccountBusinessRef**

In `account-manager.ts`, replace `AccountBusinessRef`:

```ts
export interface AccountBusinessRef {
  tenantId: number
  accountId: number
  protocolAccountId: string
  onlineAttemptId?: string | null
  previousOnlineAttemptId?: string | null
  commandId?: string | null
  batchId?: string | null
  proxyId?: number | null
  source?: string | null
}
```

Change `online` source type from the narrow union to string:

```ts
source: string = 'api',
```

- [ ] **Step 6: Extend worker-consumer account online payload**

In `worker-consumer.ts`, update `AccountOnlinePayload`:

```ts
interface AccountOnlinePayload {
  tenantId: number
  accountId: number
  protocolAccountId?: string
  format: string
  credential: Record<string, unknown>
  proxy: ProxyBinding
  proxyId?: number | null
  source?: string
  onlineAttemptId?: string | null
  previousOnlineAttemptId?: string | null
}
```

In `accountOnlinePayload`, read fields:

```ts
const proxyId = numericPayloadField(payload, 'proxyId')
return {
  tenantId,
  accountId,
  protocolAccountId: typeof payload.protocolAccountId === 'string' ? payload.protocolAccountId : undefined,
  format,
  credential,
  proxy,
  proxyId,
  source: typeof payload.source === 'string' && payload.source.length > 0 ? payload.source : 'api',
  onlineAttemptId: typeof payload.onlineAttemptId === 'string' && payload.onlineAttemptId.length > 0
    ? payload.onlineAttemptId
    : null,
  previousOnlineAttemptId: typeof payload.previousOnlineAttemptId === 'string'
    ? payload.previousOnlineAttemptId
    : null
}
```

When calling `accounts.online`, pass `payload.source` and expanded business ref:

```ts
const protocolAccountId = payload.protocolAccountId !== undefined ? payload.protocolAccountId : command.accountId
const onlineAttemptId = payload.onlineAttemptId !== null && payload.onlineAttemptId !== undefined
  ? payload.onlineAttemptId
  : `oa_legacy_${command.commandId}`
const batchId = command.batchId !== null && command.batchId !== undefined ? command.batchId : null
const proxyId = payload.proxyId !== null && payload.proxyId !== undefined ? payload.proxyId : null

await deps.accounts.online(
  command.accountId,
  payload.proxy,
  undefined,
  undefined,
  payload.source,
  { creds: resolved.creds, keys: resolved.keys },
  {
    tenantId: payload.tenantId,
    accountId: payload.accountId,
    protocolAccountId,
    onlineAttemptId,
    previousOnlineAttemptId: payload.previousOnlineAttemptId,
    commandId: command.commandId,
    batchId,
    proxyId,
    source: payload.source
  }
)
```

- [ ] **Step 7: Update transient test for state_changed metadata**

In `account-manager.transient.test.ts`, extend the business ref and assert state_changed still carries old fields:

```ts
await mgr.online('acc_3', proxy, undefined, undefined, 'batch_online', transient, {
  tenantId: 1,
  accountId: 100,
  protocolAccountId: 'acc_3',
  onlineAttemptId: 'oa_3',
  commandId: 'cmd_3',
  batchId: 'batch_3',
  proxyId: 4035,
  source: 'batch_online'
})

expect(published).toContainEqual({
  event: 'account.state_changed',
  accountId: 'acc_3',
  data: expect.objectContaining({
    tenantId: 1,
    accountId: 100,
    protocolAccountId: 'acc_3',
    to: 'VERIFYING'
  })
})
```

- [ ] **Step 8: Run Task 5 tests and verify GREEN**

Run:

```bash
npm test -- src/commands/types.test.ts src/commands/worker-consumer.test.ts src/worker/account-manager.transient.test.ts --runInBand
```

Expected: PASS.

- [ ] **Step 9: Commit in armada-protocol**

```bash
git add src/commands/types.ts src/commands/types.test.ts \
  src/commands/worker-consumer.ts src/commands/worker-consumer.test.ts \
  src/worker/account-manager.ts src/worker/account-manager.transient.test.ts
git commit -m "feat: preserve online attempt command context"
```

---

## Task 6: 协议层诊断码 helper

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/offline-diagnosis.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/offline-diagnosis.test.ts`

- [ ] **Step 1: Write failing diagnosis tests**

Create `offline-diagnosis.test.ts`:

```ts
import { diagnoseOffline } from './offline-diagnosis.js'

describe('diagnoseOffline', () => {
  it('classifies VERIFY timeout without connection update', () => {
    expect(diagnoseOffline({
      target: 'PROXY_FAILED',
      reason: 'verify_timeout:no_connection_update',
      rawCode: 408,
      rawReason: 'no connection.update open/close before verify timeout'
    })).toEqual(expect.objectContaining({
      diagnosisCode: 'VERIFY_TIMEOUT_NO_CONNECTION_UPDATE',
      diagnosisClass: 'PROXY_OR_WA_CONNECTIVITY',
      recoverability: 'RETRYABLE',
      actionTaken: 'MARK_PROXY_FAILED_RELEASE_SLOT'
    }))
  })

  it('classifies raw 428 connection termination', () => {
    expect(diagnoseOffline({
      target: 'PROXY_FAILED',
      reason: 'class_B:Connection Terminated',
      rawCode: 428,
      rawReason: 'Connection Terminated'
    })).toEqual(expect.objectContaining({
      diagnosisCode: 'WA_CONNECTION_TERMINATED_428',
      diagnosisClass: 'WA_TRANSIENT',
      recoverability: 'RETRYABLE'
    }))
  })

  it('classifies heartbeat and quick rescue failures', () => {
    expect(diagnoseOffline({ target: 'STALE', reason: 'heartbeat_timeout', rawCode: 408 }))
      .toEqual(expect.objectContaining({ diagnosisCode: 'HEARTBEAT_TIMEOUT' }))

    expect(diagnoseOffline({ target: 'PROXY_FAILED', reason: 'quick_rescue_failed:heartbeat_timeout' }))
      .toEqual(expect.objectContaining({ diagnosisCode: 'QUICK_RESCUE_FAILED' }))
  })

  it('classifies auth and manual offline states', () => {
    expect(diagnoseOffline({ target: 'NEED_REAUTH', reason: 'logged_out', rawCode: 401 }))
      .toEqual(expect.objectContaining({ diagnosisCode: 'NEED_REAUTH', diagnosisClass: 'AUTH' }))

    expect(diagnoseOffline({ target: 'OFFLINE', reason: 'manual_offline' }))
      .toEqual(expect.objectContaining({ diagnosisCode: 'MANUAL_OFFLINE', diagnosisClass: 'OPERATOR_ACTION' }))
  })
})
```

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
npm test -- src/worker/offline-diagnosis.test.ts --runInBand
```

Expected: FAIL because `offline-diagnosis.ts` does not exist.

- [ ] **Step 3: Add diagnosis helper**

Create `offline-diagnosis.ts`:

```ts
import type { AccountState } from '../types/api.js'

export type DiagnosisCode =
  | 'VERIFY_TIMEOUT_NO_CONNECTION_UPDATE'
  | 'WA_CONNECTION_TERMINATED_428'
  | 'HEARTBEAT_TIMEOUT'
  | 'QUICK_RESCUE_FAILED'
  | 'STALE_HALF_OPEN_WS'
  | 'NEED_REAUTH'
  | 'LOGGED_OUT'
  | 'DEVICE_REMOVED'
  | 'MANUAL_OFFLINE'
  | 'PROXY_CONNECT_ERROR'
  | 'PROXY_CONNECT_TIMEOUT'
  | 'PROXY_AUTH_FAILED'
  | 'UNKNOWN_OFFLINE'

export type DiagnosisClass =
  | 'PROXY_OR_WA_CONNECTIVITY'
  | 'WA_TRANSIENT'
  | 'STALE_CONNECTION'
  | 'AUTH'
  | 'OPERATOR_ACTION'
  | 'PROXY_NETWORK'
  | 'UNKNOWN'

export interface OfflineDiagnosisInput {
  target: AccountState
  reason: string
  rawCode?: number | null
  rawReason?: string | null
}

export interface OfflineDiagnosis {
  diagnosisCode: DiagnosisCode
  diagnosisClass: DiagnosisClass
  recoverability: 'RETRYABLE' | 'REAUTH_REQUIRED' | 'NOT_RETRYABLE' | 'UNKNOWN'
  actionTaken: string
}

export function diagnoseOffline(input: OfflineDiagnosisInput): OfflineDiagnosis {
  const reason = input.reason.toLowerCase()
  const rawReason = (input.rawReason !== undefined && input.rawReason !== null ? input.rawReason : '').toLowerCase()
  if (reason.includes('verify_timeout:no_connection_update')) {
    return retryable('VERIFY_TIMEOUT_NO_CONNECTION_UPDATE', 'PROXY_OR_WA_CONNECTIVITY', 'MARK_PROXY_FAILED_RELEASE_SLOT')
  }
  if (input.rawCode === 428 || rawReason.includes('connection terminated')) {
    return retryable('WA_CONNECTION_TERMINATED_428', 'WA_TRANSIENT', 'MARK_PROXY_FAILED_RELEASE_SLOT')
  }
  if (reason.startsWith('quick_rescue_failed:')) {
    return retryable('QUICK_RESCUE_FAILED', 'STALE_CONNECTION', 'MARK_PROXY_FAILED_RELEASE_SLOT')
  }
  if (reason.includes('heartbeat_timeout')) {
    return retryable('HEARTBEAT_TIMEOUT', 'STALE_CONNECTION', 'START_QUICK_RESCUE')
  }
  if (reason.includes('half_open_ws') || reason === 'stale') {
    return retryable('STALE_HALF_OPEN_WS', 'STALE_CONNECTION', 'START_QUICK_RESCUE')
  }
  if (input.target === 'NEED_REAUTH') {
    return { diagnosisCode: 'NEED_REAUTH', diagnosisClass: 'AUTH', recoverability: 'REAUTH_REQUIRED', actionTaken: 'RELEASE_SLOT' }
  }
  if (input.target === 'LOGGED_OUT') {
    return { diagnosisCode: 'LOGGED_OUT', diagnosisClass: 'AUTH', recoverability: 'REAUTH_REQUIRED', actionTaken: 'RELEASE_SLOT' }
  }
  if (input.target === 'DEVICE_REMOVED') {
    return { diagnosisCode: 'DEVICE_REMOVED', diagnosisClass: 'AUTH', recoverability: 'REAUTH_REQUIRED', actionTaken: 'RELEASE_SLOT' }
  }
  if (reason === 'manual_offline') {
    return { diagnosisCode: 'MANUAL_OFFLINE', diagnosisClass: 'OPERATOR_ACTION', recoverability: 'NOT_RETRYABLE', actionTaken: 'MANUAL_OFFLINE' }
  }
  if (reason.includes('proxy_auth')) {
    return retryable('PROXY_AUTH_FAILED', 'PROXY_NETWORK', 'MARK_PROXY_FAILED_RELEASE_SLOT')
  }
  if (reason.includes('proxy_connect_timeout')) {
    return retryable('PROXY_CONNECT_TIMEOUT', 'PROXY_NETWORK', 'MARK_PROXY_FAILED_RELEASE_SLOT')
  }
  if (reason.includes('proxy_connect')) {
    return retryable('PROXY_CONNECT_ERROR', 'PROXY_NETWORK', 'MARK_PROXY_FAILED_RELEASE_SLOT')
  }
  return { diagnosisCode: 'UNKNOWN_OFFLINE', diagnosisClass: 'UNKNOWN', recoverability: 'UNKNOWN', actionTaken: 'UNKNOWN' }
}

function retryable(diagnosisCode: DiagnosisCode, diagnosisClass: DiagnosisClass, actionTaken: string): OfflineDiagnosis {
  return { diagnosisCode, diagnosisClass, recoverability: 'RETRYABLE', actionTaken }
}
```

- [ ] **Step 4: Run Task 6 tests and verify GREEN**

Run:

```bash
npm test -- src/worker/offline-diagnosis.test.ts --runInBand
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/worker/offline-diagnosis.ts src/worker/offline-diagnosis.test.ts
git commit -m "feat: classify account offline diagnoses"
```

---

## Task 7: 协议层发布 account.offline_diagnosed

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/events/subjects.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/observability/metrics.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.heartbeat.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.transient.test.ts`

- [ ] **Step 1: Add failing AccountManager tests**

In `account-manager.heartbeat.test.ts`, extend the VERIFY timeout test:

```ts
expect(published).toContainEqual(
  expect.objectContaining({
    event: 'account.offline_diagnosed',
    accountId: 'acc_verify_timeout',
    data: expect.objectContaining({
      tenantId: 1,
      accountId: 100,
      protocolAccountId: 'acc_verify_timeout',
      onlineAttemptId: 'oa_verify_timeout',
      commandId: 'cmd_verify_timeout',
      proxyId: 4035,
      source: 'batch_online',
      from: 'VERIFYING',
      to: 'PROXY_FAILED',
      diagnosisCode: 'VERIFY_TIMEOUT_NO_CONNECTION_UPDATE',
      diagnosisClass: 'PROXY_OR_WA_CONNECTIVITY',
      rawCode: 408,
      recoverability: 'RETRYABLE',
      actionTaken: 'MARK_PROXY_FAILED_RELEASE_SLOT'
    }),
    evidence: expect.objectContaining({
      connectionField: 'connecting',
      wsOpen: false
    })
  })
)
```

Pass business ref in that test's `mgr.online` call:

```ts
await mgr.online('acc_verify_timeout', proxy, undefined, undefined, 'batch_online', transient, {
  tenantId: 1,
  accountId: 100,
  protocolAccountId: 'acc_verify_timeout',
  onlineAttemptId: 'oa_verify_timeout',
  commandId: 'cmd_verify_timeout',
  batchId: 'batch_verify_timeout',
  proxyId: 4035,
  source: 'batch_online'
})
```

Add similar assertions to the quick rescue failed and manual offline tests:

```ts
expect(published).toContainEqual(expect.objectContaining({
  event: 'account.offline_diagnosed',
  data: expect.objectContaining({ diagnosisCode: 'QUICK_RESCUE_FAILED' })
}))
```

```ts
expect(published).toContainEqual(expect.objectContaining({
  event: 'account.offline_diagnosed',
  data: expect.objectContaining({ diagnosisCode: 'MANUAL_OFFLINE' })
}))
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
npm test -- src/worker/account-manager.heartbeat.test.ts src/worker/account-manager.transient.test.ts --runInBand
```

Expected: FAIL because no diagnosis event is published.

- [ ] **Step 3: Register event type**

In `events/subjects.ts`, add to `EVENT_TYPES`:

```ts
'account.offline_diagnosed',
```

Add to `CRITICAL_EVENTS`:

```ts
'account.offline_diagnosed',
```

- [ ] **Step 4: Add metric counter**

In `observability/metrics.ts`, add:

```ts
const offlineDiagnosedTotal = new Counter({
  name: 'unsea_account_offline_diagnosed_total',
  help: 'Account offline diagnoses by low-cardinality diagnosis fields',
  labelNames: ['code', 'class', 'action'],
  registers: [registry]
})
```

Return it from the metrics object with the other counters. The label values must come from diagnosis enum strings, never account id or attempt id.

- [ ] **Step 5: Publish diagnosis from AccountManager**

In `account-manager.ts`, import:

```ts
import { diagnoseOffline } from './offline-diagnosis.js'
```

Add helper:

```ts
private publishOfflineDiagnosed(
  ctx: AccountContext,
  from: AccountState,
  target: AccountState,
  reason: string,
  detail?: { rawCode?: number | null; rawReason?: string | null; semantic?: string }
): void {
  if (!ctx.businessRef) {
    return
  }
  const diagnosis = diagnoseOffline({
    target,
    reason,
    rawCode: detail?.rawCode !== undefined && detail?.rawCode !== null ? detail.rawCode : null,
    rawReason: detail?.rawReason !== undefined && detail?.rawReason !== null ? detail.rawReason : reason
  })
  this.deps.metrics.offlineDiagnosedTotal?.inc({
    code: diagnosis.diagnosisCode,
    class: diagnosis.diagnosisClass,
    action: diagnosis.actionTaken
  })
  void this.deps.publisher
    .publish('account.offline_diagnosed', ctx.accountId, {
      ...this.businessEventData(ctx),
      onlineAttemptId: ctx.businessRef.onlineAttemptId !== undefined && ctx.businessRef.onlineAttemptId !== null
        ? ctx.businessRef.onlineAttemptId
        : `oa_legacy_${ctx.businessRef.commandId !== undefined && ctx.businessRef.commandId !== null ? ctx.businessRef.commandId : ctx.accountId}`,
      previousOnlineAttemptId: ctx.businessRef.previousOnlineAttemptId !== undefined ? ctx.businessRef.previousOnlineAttemptId : null,
      commandId: ctx.businessRef.commandId !== undefined ? ctx.businessRef.commandId : null,
      batchId: ctx.businessRef.batchId !== undefined ? ctx.businessRef.batchId : null,
      proxyId: ctx.businessRef.proxyId !== undefined ? ctx.businessRef.proxyId : null,
      source: ctx.businessRef.source !== undefined ? ctx.businessRef.source : null,
      from,
      to: target,
      diagnosisCode: diagnosis.diagnosisCode,
      diagnosisClass: diagnosis.diagnosisClass,
      rawCode: detail?.rawCode !== undefined && detail?.rawCode !== null ? detail.rawCode : null,
      rawReason: truncate(detail?.rawReason !== undefined && detail?.rawReason !== null ? detail.rawReason : reason, 512),
      recoverability: diagnosis.recoverability,
      actionTaken: diagnosis.actionTaken
    }, this.getEvidence(ctx.accountId))
    .catch(publishErr =>
      this.logger.warn({ err: publishErr, accountId: ctx.accountId, target }, 'publish account.offline_diagnosed failed')
    )
}
```

Add helper:

```ts
function truncate(value: string, maxLength: number): string {
  return value.length <= maxLength ? value : value.slice(0, maxLength)
}
```

Before diagnosis points, capture from-state and publish after state change:

```ts
const from = ctx.state.state
this.publishStateChange(ctx, 'PROXY_FAILED', reason, {
  rawCode,
  rawReason,
  semantic: 'PROXY_FAILED'
})
this.publishOfflineDiagnosed(ctx, from, 'PROXY_FAILED', reason, {
  rawCode,
  rawReason,
  semantic: 'PROXY_FAILED'
})
```

Apply the same pattern in:

- `offline`: target `OFFLINE`, reason `manual_offline`.
- `logout` or auth terminal branches: target `NEED_REAUTH`, `LOGGED_OUT`, `DEVICE_REMOVED`.
- `handleAccountHeartbeatFailure`: target `STALE`, reason `heartbeat_timeout`.
- `markProxyFailed`: target `PROXY_FAILED`, current reason and raw fields.

Keep the publish fire-and-forget. Do not await it from state transitions.

- [ ] **Step 6: Run Task 7 tests and verify GREEN**

Run:

```bash
npm test -- src/worker/offline-diagnosis.test.ts src/worker/account-manager.heartbeat.test.ts src/worker/account-manager.transient.test.ts --runInBand
npm run lint
```

Expected: PASS and TypeScript compile success.

- [ ] **Step 7: Commit**

```bash
git add src/events/subjects.ts src/observability/metrics.ts \
  src/worker/account-manager.ts src/worker/account-manager.heartbeat.test.ts \
  src/worker/account-manager.transient.test.ts
git commit -m "feat: publish account offline diagnoses"
```

---

## Task 8: Armada 查询接口

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountOnlineAttemptLogVO.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountOnlineAttemptLogService.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/controller/AccountController.java`
- Test: `armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java`

- [ ] **Step 1: Write failing MockMvc test**

In `AccountControllerDbTest`, add:

```java
@Test
void get_onlineAttempts_returnsRecentDiagnosisTimeline() throws Exception {
    Long accountId = importOneAccount("252625" + (System.currentTimeMillis() % 1000000L));
    jdbc.update("""
            INSERT INTO account_online_attempt_log (
              tenant_id, account_id, protocol_account_id, online_attempt_id, command_id,
              batch_id, proxy_id, source, from_state, to_state, diagnosis_code,
              diagnosis_class, raw_code, raw_reason, recoverability, action_taken,
              worker_id, evidence_json, occurred_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), NOW(3), NOW(3))
            """,
            TEST_TENANT_ID, accountId, "acc_252625852450", "oa_test_1", "cmd_1",
            "batch_1", 4035L, "batch_online", "VERIFYING", "PROXY_FAILED",
            "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE", "PROXY_OR_WA_CONNECTIVITY", 408,
            "no connection.update open/close before verify timeout", "RETRYABLE",
            "MARK_PROXY_FAILED_RELEASE_SLOT", "w3", "{\"wsOpen\":false}");

    mockMvc.perform(get("/api/accounts/{id}/online-attempts", accountId)
                    .param("limit", "10")
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].onlineAttemptId").value("oa_test_1"))
            .andExpect(jsonPath("$.data[0].diagnosisCode").value("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE"))
            .andExpect(jsonPath("$.data[0].proxyId").value(4035));
}
```

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
armada-api/dbtest.sh AccountControllerDbTest#get_onlineAttempts_returnsRecentDiagnosisTimeline
```

Expected: FAIL because VO and endpoint do not exist.

- [ ] **Step 3: Add VO**

Create `AccountOnlineAttemptLogVO.java`:

```java
package com.armada.account.model.vo;

import com.armada.account.model.entity.AccountOnlineAttemptLog;

public record AccountOnlineAttemptLogVO(
        Long id,
        Long accountId,
        String protocolAccountId,
        String onlineAttemptId,
        String previousOnlineAttemptId,
        String commandId,
        String batchId,
        Long proxyId,
        String source,
        String fromState,
        String toState,
        String diagnosisCode,
        String diagnosisClass,
        Integer rawCode,
        String rawReason,
        String recoverability,
        String actionTaken,
        String workerId,
        String evidenceJson,
        String occurredAt) {

    public static AccountOnlineAttemptLogVO from(AccountOnlineAttemptLog row) {
        return new AccountOnlineAttemptLogVO(
                row.getId(),
                row.getAccountId(),
                row.getProtocolAccountId(),
                row.getOnlineAttemptId(),
                row.getPreviousOnlineAttemptId(),
                row.getCommandId(),
                row.getBatchId(),
                row.getProxyId(),
                row.getSource(),
                row.getFromState(),
                row.getToState(),
                row.getDiagnosisCode(),
                row.getDiagnosisClass(),
                row.getRawCode(),
                row.getRawReason(),
                row.getRecoverability(),
                row.getActionTaken(),
                row.getWorkerId(),
                row.getEvidenceJson(),
                row.getOccurredAt() == null ? null : row.getOccurredAt().toString());
    }
}
```

- [ ] **Step 4: Add controller methods**

Inject `AccountOnlineAttemptLogService` into `AccountController`.

Add endpoints:

```java
@GetMapping("/{id}/online-attempts")
public ApiResponse<List<AccountOnlineAttemptLogVO>> onlineAttempts(@PathVariable Long id,
                                                                   @RequestParam(defaultValue = "20") int limit) {
    return ApiResponse.ok(accountOnlineAttemptLogService.recentByAccount(id, limit));
}

@GetMapping("/online-attempts/{onlineAttemptId}")
public ApiResponse<List<AccountOnlineAttemptLogVO>> onlineAttemptTimeline(@PathVariable String onlineAttemptId,
                                                                          @RequestParam(defaultValue = "100") int limit) {
    return ApiResponse.ok(accountOnlineAttemptLogService.timeline(onlineAttemptId, limit));
}
```

Add imports:

```java
import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import com.armada.account.service.AccountOnlineAttemptLogService;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
```

- [ ] **Step 5: Run Task 8 tests and verify GREEN**

Run:

```bash
armada-api/dbtest.sh AccountControllerDbTest#get_onlineAttempts_returnsRecentDiagnosisTimeline
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add armada-api/src/main/java/com/armada/account/model/vo/AccountOnlineAttemptLogVO.java \
  armada-api/src/main/java/com/armada/account/service/AccountOnlineAttemptLogService.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineAttemptLogServiceImpl.java \
  armada-api/src/main/java/com/armada/account/controller/AccountController.java \
  armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java
git commit -m "feat: expose account online attempt timeline"
```

---

## Task 9: End-to-End Verification and Deployment Order

**Files:**
- Modify: `docs/superpowers/specs/2026-07-02-account-online-observability-design.md` only if implementation discovers a concrete mismatch.

- [ ] **Step 1: Run Armada focused test suite**

Run from `armada`:

```bash
mvn -f armada-api/pom.xml -Dtest=OnlineAttemptIdGeneratorTest,AccountOnlineCommandServiceImplTest,ProtocolCommandOutboxServiceImplTest,ProtocolCommandPublisherTest,ProtocolAccountEventConsumerTest,AccountOnlineAttemptLogServiceImplTest test
armada-api/dbtest.sh AccountOnlineAttemptLogMapperDbTest
armada-api/dbtest.sh AccountControllerDbTest#get_onlineAttempts_returnsRecentDiagnosisTimeline
```

Expected: all PASS.

- [ ] **Step 2: Run protocol focused test suite**

Run from `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer`:

```bash
npm test -- src/commands/types.test.ts src/commands/worker-consumer.test.ts src/worker/offline-diagnosis.test.ts src/worker/account-manager.heartbeat.test.ts src/worker/account-manager.transient.test.ts --runInBand
npm run lint
npm run build
```

Expected: all PASS.

- [ ] **Step 3: Deploy order**

Deploy Armada first, then protocol:

1. Armada first is safe because it can generate attempt ids before protocol uses them.
2. Protocol second starts publishing `account.offline_diagnosed`.
3. If protocol is deployed first, it uses `oa_legacy_<commandId>` for missing attempt ids, so old in-flight commands still produce bounded diagnosis events.

- [ ] **Step 4: Smoke test account 252625852450**

After both deployments:

1. Trigger online for account `252625852450` in test environment.
2. Query Armada DB:

```sql
SELECT online_attempt_id, command_id, batch_id, proxy_id, source, diagnosis_code, raw_code, raw_reason, occurred_at
FROM account_online_attempt_log
WHERE tenant_id = 1
  AND protocol_account_id = 'acc_252625852450'
ORDER BY occurred_at DESC
LIMIT 20;
```

Expected after a failure or rescue event: rows contain the same `online_attempt_id` that appears in `protocol_command_outbox.payload_json`.

3. Query API:

```bash
curl -s 'http://armada.65.2.123.53.nip.io/api/accounts/9/online-attempts?limit=20' \
  -H 'X-Tenant-Code: demo'
```

Expected: response `code=0`, `data` contains diagnosis timeline rows after protocol emits a diagnosis event.

- [ ] **Step 5: Final commit check**

In `armada`:

```bash
git status --short
```

Expected: only unrelated pre-existing files are dirty. No implementation file from this plan is unstaged.

In `armada-protocol`:

```bash
git status --short
```

Expected: clean after the protocol commits, or only unrelated pre-existing files are dirty.
