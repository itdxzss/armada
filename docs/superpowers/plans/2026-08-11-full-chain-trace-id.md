# Full-Chain Trace ID Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用一个 32 位小写十六进制 `traceId` 串联 Armada HTTP/后台任务、事务 Outbox、Kafka、Protocol Master、Redis Stream、Worker 和回传事件，并保留现有业务 ID 作为长期业务关联依据。

**Architecture:** Armada 使用 SLF4J MDC 保存当前 Trace，并把 Trace 持久化到 `protocol_command_outbox.trace_id`；Protocol 使用 Node `AsyncLocalStorage` 保存当前 Trace。命令和事件 Envelope 是消息中的权威 Trace，Kafka Header 是镜像，Redis Stream 继续序列化完整命令 Envelope。账号上线的异步 Baileys 回调通过现有 `AccountBusinessRef` 暂存 Trace，初次上线成功或失败收口后清除，避免 Trace 无限延长。

**Tech Stack:** Java 17、Spring Boot 3.3.5、SLF4J/Logback、MyBatis、Flyway、Spring Kafka、TypeScript 5.8、Node.js 24、Fastify 5、Pino 9、KafkaJS 2、Redis Streams、JUnit 5、Mockito、Jest 30。

## Global Constraints

- Trace ID 必须是 32 位小写十六进制字符串，且不能为全零。
- HTTP Header 固定为 `X-Trace-Id`；JSON、Kafka Header 和日志字段固定为 `traceId`；数据库列固定为 `trace_id`。
- 数据库只给 `protocol_command_outbox` 新增 `trace_id VARCHAR(32) NULL COMMENT '全链路追踪标识'`，不修改业务表、不建 Trace 表、不回填历史数据、第一版不建索引。
- `traceId` 不参与鉴权、租户隔离、幂等、状态机或业务关联；账号、任务、命令、事件等业务 ID 继续保留。
- Envelope 中的合法 Trace 是消息权威值；Kafka Header 仅作镜像和兼容回退；两者不一致时使用 Envelope 并告警。
- 非法外部 Trace 不回显、不写日志，缺失或非法时生成新值；旧 Outbox 行按 `commandId` 稳定派生 Trace。
- HTTP 请求内的多条命令共享 Trace；没有请求上下文的后台 Outbox 按聚合键分配 Trace；同一 Outbox 行重试保留 Trace。
- 账号上线从命令到 `VERIFYING`、`ONLINE` 或失败使用同一 Trace；完成后的心跳超时、断线和独立重连创建新 Trace。
- 所有 MDC/AsyncLocalStorage Scope 必须有并发隔离测试并在边界结束后恢复上下文。
- 消息字段保持向后兼容；协议层先兼容接收，Armada 后生产 Trace。
- 不引入 OpenTelemetry、Span、Collector、采样、Trace UI、前端页面或数据库 Trace 查询接口。
- 不提交、输出或复制 `armada-api/.env`、`dev-1.pem`、`xieyi.pem` 中的任何凭据。

---

## File Structure

### Armada

- `armada-api/src/main/java/com/armada/shared/trace/TraceIds.java`：格式校验、随机生成、稳定派生和 Envelope/Header 解析规则。
- `armada-api/src/main/java/com/armada/shared/trace/TraceContext.java`：MDC Scope 的建立、读取和恢复。
- `armada-api/src/main/java/com/armada/shared/trace/TraceIdFilter.java`：HTTP 请求 Trace 入口和响应 Header。
- `armada-api/src/main/java/com/armada/shared/trace/TraceIdClientHttpRequestInterceptor.java`：协议 HTTP 调用 Header 注入。
- `armada-api/src/main/java/com/armada/platform/kafka/trace/KafkaTraceSupport.java`：Kafka 消费端 Envelope/Header 解析、冲突告警和 Scope 建立。
- `armada-api/src/main/java/com/armada/platform/protocol/model/entity/ProtocolCommandOutbox.java`：新增持久化属性 `traceId`。
- `armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml`：新增 `trace_id` 查询和插入映射。
- `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`：写 Outbox 前统一分配 Trace。
- `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolCommandEnvelope.java`：新增可序列化字段 `traceId`。
- `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`：发送 Envelope 和 Kafka Header，并恢复异步回调日志 Scope。
- `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`：账号事件消费 Scope。
- `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`：群事件消费 Scope。
- `armada-api/src/main/resources/application.yml`：控制台日志增加 `traceId`。
- `armada-api/src/main/resources/db/migration/V111__add_trace_id_to_protocol_command_outbox.sql`：唯一数据库迁移。

### Armada Protocol

- `protocol-layer/src/observability/trace-context.ts`：Trace 校验、生成、稳定派生、候选解析、AsyncLocalStorage 和 Fastify Hook。
- `protocol-layer/src/observability/logger.ts`：Pino `mixin` 自动注入当前 Trace。
- `protocol-layer/src/server.ts`：注册 HTTP Trace Hook，并向 Master Command Consumer 传 Logger。
- `protocol-layer/src/master-gateway/register.ts`：Master 到 Worker 的 HTTP 转发覆盖为已校验 Trace Header。
- `protocol-layer/src/commands/types.ts`：解析后命令统一具有合法 `traceId`。
- `protocol-layer/src/commands/master-consumer.ts`：Kafka Envelope/Header 兼容、接收/转发日志和 Master fallback Trace。
- `protocol-layer/src/commands/worker-inbox.ts`：Redis Stream 保留完整命令 Envelope；主要增加契约测试。
- `protocol-layer/src/commands/worker-stream-consumer.ts`：每条 Worker 命令建立独立 Trace Scope。
- `protocol-layer/src/events/publisher.ts`：Event Envelope/Header 写入 Trace，支持显式异步 Trace。
- `protocol-layer/src/commands/worker-consumer.ts`：账号上线命令把 Trace 写入 `AccountBusinessRef`。
- `protocol-layer/src/worker/account-manager.ts`：异步上线生命周期事件继承 Trace，并在终态后清除。

All Trace contract test files define the same constants locally:

```java
private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";
private static final String OTHER_TRACE_ID = "fedcba9876543210fedcba9876543210";
```

```typescript
const FIXED_TRACE_ID = '0123456789abcdef0123456789abcdef'
const OTHER_TRACE_ID = 'fedcba9876543210fedcba9876543210'
```

---

### Task 1: Armada Trace primitives and HTTP propagation

**Files:**

- Create: `armada-api/src/main/java/com/armada/shared/trace/TraceIds.java`
- Create: `armada-api/src/main/java/com/armada/shared/trace/TraceContext.java`
- Create: `armada-api/src/main/java/com/armada/shared/trace/TraceIdFilter.java`
- Create: `armada-api/src/main/java/com/armada/shared/trace/TraceIdClientHttpRequestInterceptor.java`
- Create: `armada-api/src/test/java/com/armada/shared/trace/TraceIdsTest.java`
- Create: `armada-api/src/test/java/com/armada/shared/trace/TraceContextTest.java`
- Create: `armada-api/src/test/java/com/armada/shared/trace/TraceIdFilterTest.java`
- Create: `armada-api/src/test/java/com/armada/shared/trace/TraceIdClientHttpRequestInterceptorTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java:128-152`
- Modify: `armada-api/src/main/resources/application.yml`

**Interfaces:**

- Produces: `TraceIds.HTTP_HEADER`, `TraceIds.KAFKA_HEADER`, `normalize(String)`, `isValid(String)`, `newTraceId()`, `stableFrom(String)`, `resolveCandidates(String,String,String)`。
- Produces: `TraceContext.current()`, `TraceContext.open(String)` 和 `TraceContext.Scope extends AutoCloseable`。
- Consumed later by: Outbox、Kafka Producer/Consumer、Protocol HTTP Client。

- [ ] **Step 1: Write failing tests for the exact Trace contract**

```java
@Test
void acceptsOnlyCanonicalTraceIdsAndUsesTheFixedCrossRepoSample() {
    String fixed = "0123456789abcdef0123456789abcdef";
    assertThat(TraceIds.normalize(fixed)).isEqualTo(fixed);
    assertThat(TraceIds.normalize(fixed.toUpperCase())).isNull();
    assertThat(TraceIds.normalize("00000000000000000000000000000000")).isNull();
    assertThat(TraceIds.normalize("abc\nforged=true")).isNull();
}

@Test
void canonicalEnvelopeWinsAndReportsHeaderMismatch() {
    TraceIds.Resolution result = TraceIds.resolveCandidates(
            "0123456789abcdef0123456789abcdef",
            "fedcba9876543210fedcba9876543210",
            "cmd-1");
    assertThat(result.traceId()).isEqualTo("0123456789abcdef0123456789abcdef");
    assertThat(result.mismatch()).isTrue();
    assertThat(result.source()).isEqualTo(TraceIds.Source.ENVELOPE);
}
```

- [ ] **Step 2: Run the Trace tests and verify they fail before implementation**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=TraceIdsTest,TraceContextTest,TraceIdFilterTest,TraceIdClientHttpRequestInterceptorTest test
```

Expected: FAIL because the four production classes do not exist.

- [ ] **Step 3: Implement Trace ID validation, generation, stable fallback and MDC Scope**

```java
public final class TraceIds {
    public static final String HTTP_HEADER = "X-Trace-Id";
    public static final String KAFKA_HEADER = "traceId";
    private static final Pattern CANONICAL = Pattern.compile("[0-9a-f]{32}");

    public static String normalize(String candidate) {
        if (candidate == null || !CANONICAL.matcher(candidate).matches()) return null;
        return candidate.chars().allMatch(ch -> ch == '0') ? null : candidate;
    }

    public static boolean isValid(String candidate) {
        return normalize(candidate) != null;
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String stableFrom(String seed) {
        if (seed == null || seed.isBlank()) return newTraceId();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
```

`TraceIds.resolveCandidates` returns a `Resolution(traceId, source, mismatch)` record with precedence `ENVELOPE -> HEADER -> stable seed -> random`。`TraceContext.open` saves the previous MDC value and restores/removes it in `close()`; nested Scope must therefore work without leaking.

- [ ] **Step 4: Implement HTTP ingress, response and outbound propagation**

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = Optional.ofNullable(TraceIds.normalize(request.getHeader(TraceIds.HTTP_HEADER)))
                .orElseGet(TraceIds::newTraceId);
        response.setHeader(TraceIds.HTTP_HEADER, traceId);
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            chain.doFilter(request, response);
        }
    }
}
```

`TraceIdClientHttpRequestInterceptor` sets exactly one `X-Trace-Id` value using current MDC or a newly generated value. Register it in `ProtocolConfiguration.buildRestClient(...)` with `.requestInterceptor(new TraceIdClientHttpRequestInterceptor())`。

- [ ] **Step 5: Configure logs and verify Scope cleanup**

Add a console pattern to `application.yml` that contains `traceId=%X{traceId:-}` while preserving timestamp, level, thread, logger, message and stack trace. The tests must assert the Filter sees the Trace inside `FilterChain`, writes the response Header, ignores an invalid input value, and leaves `MDC.get("traceId") == null` after completion.

- [ ] **Step 6: Run focused tests and commit**

```bash
mvn -q -Dtest=TraceIdsTest,TraceContextTest,TraceIdFilterTest,TraceIdClientHttpRequestInterceptorTest,ProtocolHttpExecutorTest test
git add armada-api/src/main/java/com/armada/shared/trace armada-api/src/test/java/com/armada/shared/trace armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java armada-api/src/main/resources/application.yml
git commit -m "feat: add Armada trace context and HTTP propagation"
```

Expected: all selected tests PASS; only the listed files enter the commit.

---

### Task 2: Persist Trace in the Armada transaction Outbox

**Files:**

- Create: `armada-api/src/main/resources/db/migration/V111__add_trace_id_to_protocol_command_outbox.sql`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/ProtocolCommandOutboxTraceMigrationSqlTest.java`
- Create: `.harness/changes/full-chain-trace-id/db-migrations.sql`
- Create: `.harness/changes/full-chain-trace-id/rollback.sql`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/entity/ProtocolCommandOutbox.java:12-249`
- Modify: `armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml:5-29`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java:601-618`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxSchemaDbTest.java`

**Interfaces:**

- Consumes: `TraceContext.current()`、`TraceIds.newTraceId()`。
- Produces: `ProtocolCommandOutbox.getTraceId()/setTraceId(String)` and persisted `trace_id`。
- Invariant: one HTTP Scope shares one Trace; without Scope, rows sharing `aggregateType:aggregateId` share a generated Trace, while different aggregates receive different Trace IDs。

- [ ] **Step 1: Write failing migration and service tests**

```java
@Test
void migrationAddsOnlyNullableUnindexedTraceColumn() throws IOException {
    String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
    assertThat(sql).contains("table_name = 'protocol_command_outbox'")
            .contains("column_name = 'trace_id'")
            .contains("ADD COLUMN trace_id VARCHAR(32) NULL")
            .contains("COMMENT ''全链路追踪标识''")
            .doesNotContain("CREATE INDEX")
            .doesNotContain("UPDATE protocol_command_outbox");
}
```

In `ProtocolCommandOutboxServiceImplTest`, capture the rows passed to `batchInsertPending` and add two assertions:

```java
try (TraceContext.Scope ignored = TraceContext.open(FIXED_TRACE_ID)) {
    service.enqueueOnlineCommands(List.of(
            onlineCommand(101L, "acc-101", CredentialFormat.BAILEYS_JSON, 11L),
            onlineCommand(102L, "acc-102", CredentialFormat.BAILEYS_JSON, 12L)));
}
List<ProtocolCommandOutbox> rows = capturedRows();
assertThat(rows).extracting(ProtocolCommandOutbox::getTraceId)
        .containsOnly(FIXED_TRACE_ID);
```

In a separate test with a fresh service/mock interaction:

```java
service.enqueueOnlineCommands(List.of(
        onlineCommand(101L, "acc-101", CredentialFormat.BAILEYS_JSON, 11L),
        onlineCommand(102L, "acc-102", CredentialFormat.BAILEYS_JSON, 12L)));
List<ProtocolCommandOutbox> rows = capturedRows();
assertThat(rows).extracting(ProtocolCommandOutbox::getTraceId)
        .allMatch(TraceIds::isValid)
        .doesNotHaveDuplicates();
```

- [ ] **Step 2: Run focused tests and verify failure**

```bash
mvn -q -Dtest=ProtocolCommandOutboxTraceMigrationSqlTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: FAIL because migration, entity field and assignment logic are absent.

- [ ] **Step 3: Add the guarded Flyway migration and rollback evidence**

```sql
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND column_name = 'trace_id') = 0,
    'ALTER TABLE protocol_command_outbox ADD COLUMN trace_id VARCHAR(32) NULL COMMENT ''全链路追踪标识'' AFTER payload_json',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

Copy the same forward SQL into `.harness/changes/full-chain-trace-id/db-migrations.sql`。The rollback file contains one guarded `ALTER TABLE protocol_command_outbox DROP COLUMN trace_id` statement and a comment stating it is an isolated manual cleanup, not part of normal application rollback.

- [ ] **Step 4: Add entity/Mapper mapping and centralized Trace assignment**

Add `traceId` after `payloadJson` in the entity and in the Mapper `Columns` list. Add `trace_id`/`#{r.traceId}` to `batchInsertPending`。

Before `mapper.batchInsertPending(rows)` in `insertPendingRows`, call:

```java
private void assignTraceIds(List<ProtocolCommandOutbox> rows) {
    String current = TraceContext.current();
    Map<String, String> byAggregate = new HashMap<>();
    for (ProtocolCommandOutbox row : rows) {
        String traceId = current;
        if (traceId == null) {
            String key = row.getAggregateType() + ":" + row.getAggregateId();
            if (row.getAggregateId() == null) key = "command:" + row.getCommandId();
            traceId = byAggregate.computeIfAbsent(key, ignored -> TraceIds.newTraceId());
        }
        row.setTraceId(traceId);
    }
}
```

This central insertion point covers all current command builders without modifying fourteen `to*OutboxRow` methods.

- [ ] **Step 5: Run unit/Mapper contract tests**

```bash
mvn -q -Dtest=ProtocolCommandOutboxTraceMigrationSqlTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: PASS. Do not run `dbtest.sh` until the target database host/schema has been shown to the user and confirmed.

- [ ] **Step 6: Commit the Outbox persistence slice**

```bash
git add armada-api/src/main/resources/db/migration/V111__add_trace_id_to_protocol_command_outbox.sql armada-api/src/test/java/com/armada/platform/protocol/ProtocolCommandOutboxTraceMigrationSqlTest.java armada-api/src/main/java/com/armada/platform/protocol/model/entity/ProtocolCommandOutbox.java armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxSchemaDbTest.java .harness/changes/full-chain-trace-id
git commit -m "feat: persist trace id in protocol command outbox"
```

---

### Task 3: Protocol Trace Context, Pino and HTTP propagation

**Files:**

- Create: `protocol-layer/src/observability/trace-context.ts`
- Create: `protocol-layer/src/observability/trace-context.test.ts`
- Modify: `protocol-layer/src/observability/logger.ts:1-33`
- Modify: `protocol-layer/src/server.ts:21-23,505-520`
- Modify: `protocol-layer/src/master-gateway/register.ts:594-618`
- Modify: `protocol-layer/src/master-gateway/register.test.ts`

**Interfaces:**

- Produces: `TRACE_HTTP_HEADER = 'x-trace-id'`, `TRACE_KAFKA_HEADER = 'traceId'`。
- Produces: `normalizeTraceId(unknown)`, `createTraceId()`, `stableTraceId(string)`, `traceIdForCommand(candidate,commandId)`, `resolveTraceCandidates(envelope,header,seed)`, `currentTraceId()`, `runWithTrace(traceId,fn)`, `traceLogMixin()`, `registerTraceContext(app)`。
- Consumed later by: command parser/consumer、Worker、Event Publisher、Account Manager。

- [ ] **Step 1: Write failing unit, concurrency and Fastify tests**

```typescript
it('accepts the shared fixed sample and rejects unsafe values', () => {
  expect(normalizeTraceId('0123456789abcdef0123456789abcdef'))
    .toBe('0123456789abcdef0123456789abcdef')
  expect(normalizeTraceId('ABCDEF0123456789ABCDEF0123456789')).toBeNull()
  expect(normalizeTraceId('00000000000000000000000000000000')).toBeNull()
  expect(normalizeTraceId('abc\nforged=true')).toBeNull()
})

it('isolates concurrent async scopes', async () => {
  const [left, right] = await Promise.all([
    runWithTrace('11111111111111111111111111111111', async () => {
      await Promise.resolve()
      return currentTraceId()
    }),
    runWithTrace('22222222222222222222222222222222', async () => {
      await Promise.resolve()
      return currentTraceId()
    })
  ])
  expect([left, right]).toEqual([
    '11111111111111111111111111111111',
    '22222222222222222222222222222222'
  ])
  expect(currentTraceId()).toBeUndefined()
})
```

Use `Fastify().inject()` to assert a valid request Header is returned unchanged, an invalid Header is replaced, the route sees the same Trace, and the next request gets a different Trace.

- [ ] **Step 2: Run the test and verify failure**

```bash
npm test -- --runInBand src/observability/trace-context.test.ts
```

Expected: FAIL because `trace-context.ts` does not exist.

- [ ] **Step 3: Implement AsyncLocalStorage and Fastify Hook**

```typescript
const storage = new AsyncLocalStorage<{ traceId: string }>()

export function runWithTrace<T>(candidate: unknown, fn: () => T): T {
  const traceId = normalizeTraceId(candidate) ?? createTraceId()
  return storage.run({ traceId }, fn)
}

export function registerTraceContext(app: FastifyInstance): void {
  app.addHook('onRequest', (request, reply, done) => {
    const traceId = normalizeTraceId(request.headers[TRACE_HTTP_HEADER]) ?? createTraceId()
    request.headers[TRACE_HTTP_HEADER] = traceId
    reply.header('X-Trace-Id', traceId)
    runWithTrace(traceId, done)
  })
}
```

Use `randomBytes(16).toString('hex')` for random IDs and SHA-256 first 32 hex characters for stable command fallback. `resolveTraceCandidates` implements the same precedence and mismatch flag as Java.

```typescript
export function traceIdForCommand(candidate: unknown, commandId: string): string {
  return normalizeTraceId(candidate) ?? stableTraceId(commandId)
}
```

- [ ] **Step 4: Wire Pino, server and Master-to-Worker HTTP**

Add `mixin: traceLogMixin` to `createLogger`。Call `registerTraceContext(app)` immediately after Fastify creation and before routes/error handlers. In `normalizeForwardHeaders`, overwrite the outgoing lower-case Header when a current Trace exists:

```typescript
const traceId = currentTraceId()
if (traceId) normalized[TRACE_HTTP_HEADER] = traceId
```

- [ ] **Step 5: Run focused tests, type-check and commit**

```bash
npm test -- --runInBand src/observability/trace-context.test.ts src/master-gateway/register.test.ts
npm run lint
git add protocol-layer/src/observability/trace-context.ts protocol-layer/src/observability/trace-context.test.ts protocol-layer/src/observability/logger.ts protocol-layer/src/server.ts protocol-layer/src/master-gateway/register.ts protocol-layer/src/master-gateway/register.test.ts
git commit -m "feat: add protocol trace context and HTTP propagation"
```

Expected: tests and TypeScript compilation PASS.

---

### Task 4: Propagate Trace through Protocol Kafka Command and Redis Stream

**Files:**

- Modify: `protocol-layer/src/commands/types.ts:27-93`
- Modify: `protocol-layer/src/commands/types.test.ts`
- Modify: `protocol-layer/src/commands/master-consumer.ts:26-179`
- Modify: `protocol-layer/src/commands/master-consumer.test.ts`
- Modify: `protocol-layer/src/commands/worker-inbox.ts:48-56`
- Modify: `protocol-layer/src/commands/worker-inbox.test.ts`
- Modify: `protocol-layer/src/commands/worker-stream-consumer.ts:197-273`
- Modify: `protocol-layer/src/commands/worker-stream-consumer.test.ts`
- Modify: `protocol-layer/src/server.ts:109-120`

**Interfaces:**

- Consumes: Protocol Trace helpers from Task 3。
- Produces: `MasterCommandEnvelope.traceId?: string` remains source-compatible for direct in-process callers; every successfully parsed Kafka/Redis command receives a canonical Trace value。
- Produces: `MasterCommandKafkaMessage.headers?: Record<string, Buffer | string | Array<Buffer | string> | undefined>`。
- Invariant: Redis Stream field `command` contains the complete JSON Envelope including `traceId`。

- [ ] **Step 1: Write failing parser and Kafka precedence tests**

```typescript
it('keeps a valid envelope trace across parsing', () => {
  const parsed = parseMasterCommand({
    commandId: 'cmd-1', commandType: 'account.offline.requested',
    protocolAccountId: 'acc-1', traceId: FIXED_TRACE_ID, payload: {}
  })
  expect(parsed.ok && parsed.command.traceId).toBe(FIXED_TRACE_ID)
})

it('uses envelope trace over a conflicting Kafka header and logs the mismatch', async () => {
  const xadd = jest.fn(async () => '1700000000000-0')
  const lookupBatch = jest.fn(async () => ({ 'acc-1': 'worker-1' }))
  const logger = { info: jest.fn(), warn: jest.fn() }
  const result = await handleMasterCommandMessages([{
    value: Buffer.from(JSON.stringify({
      commandId: 'cmd-1',
      commandType: 'account.offline.requested',
      protocolAccountId: 'acc-1',
      traceId: FIXED_TRACE_ID,
      payload: { tenantId: 1, accountId: 1, protocolAccountId: 'acc-1' }
    })),
    headers: { traceId: Buffer.from(OTHER_TRACE_ID) }
  }], {
    ownerLookup: { lookupBatch },
    redis: { xadd },
    logger
  })
  expect(result.accepted).toBe(1)
  expect(logger.warn).toHaveBeenCalledWith(
    expect.objectContaining({ traceId: FIXED_TRACE_ID, headerTraceId: OTHER_TRACE_ID }),
    'command traceId mismatch'
  )
})
```

Add a Worker Stream test whose mocked publisher observes `currentTraceId() === traceIdForCommand(command.traceId, command.commandId)` for two concurrent account lanes and sees no Trace after the batch.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
npm test -- --runInBand src/commands/types.test.ts src/commands/master-consumer.test.ts src/commands/worker-inbox.test.ts src/commands/worker-stream-consumer.test.ts
```

Expected: FAIL because parsed commands and Worker execution have no Trace context.

- [ ] **Step 3: Parse Envelope/Header with compatibility rules**

Add optional `traceId?: string` to `MasterCommandEnvelope` so existing direct command fixtures remain compatible. Before `parseMasterCommand(parsedJson)` in the Master consumer, resolve `parsedJson.traceId` and the Kafka Header. Write the resolved value back to the parsed object, log a mismatch without logging the invalid raw value, then parse. `parseMasterCommand` also calls `traceIdForCommand(value.traceId, value.commandId)` so its successful result always carries a valid runtime value even though the public property remains optional at type level.

For old messages, use `stableTraceId(commandId)` so a redelivery gets the same Trace. Add `logger?: Pick<Logger, 'info' | 'warn'>` to `MasterCommandConsumerDeps` and pass `input.logger` from `server.ts`。

- [ ] **Step 4: Add one Master receipt/route log per command**

After routing and Redis publication, emit:

```typescript
for (const [workerId, workerCommands] of Object.entries(routedResult.byWorker)) {
  await publishWorkerCommands(deps.redis, workerId, workerCommands)
  for (const command of workerCommands) {
    const traceId = traceIdForCommand(command.traceId, command.commandId)
    runWithTrace(traceId, () => deps.logger?.info({
      commandId: command.commandId,
      accountId: command.accountId,
      workerId
    }, 'master command routed'))
  }
}
```

The serialized Redis command already contains `traceId`; `worker-inbox.test.ts` must parse the `xadd` JSON and assert it equals the fixed Trace.

- [ ] **Step 5: Restore Scope for every Worker command**

Wrap the existing executor body in `processWorkerCommandEntries`:

```typescript
await runWithTrace(traceIdForCommand(command.traceId, command.commandId), async () => {
  await executeWorkerCommand(command, {
    accounts: input.accounts,
    publisher: input.publisher,
    logger: input.logger,
    groupJoinStates: input.groupJoinStates,
    groupJoinTimeoutMs: input.groupJoinTimeoutMs,
    pullTaskActionStates: input.pullTaskActionStates,
    pullTaskActionTimeoutMs: input.pullTaskActionTimeoutMs,
    normalGroupResultTopic: input.normalGroupResultTopic,
    operationGate: input.operationGate,
    ack: async () => input.redis.xack(stream, group, messageId)
  })
})
```

- [ ] **Step 6: Run focused tests, type-check and commit**

```bash
npm test -- --runInBand src/commands/types.test.ts src/commands/master-consumer.test.ts src/commands/worker-inbox.test.ts src/commands/worker-stream-consumer.test.ts
npm run lint
git add protocol-layer/src/commands/types.ts protocol-layer/src/commands/types.test.ts protocol-layer/src/commands/master-consumer.ts protocol-layer/src/commands/master-consumer.test.ts protocol-layer/src/commands/worker-inbox.ts protocol-layer/src/commands/worker-inbox.test.ts protocol-layer/src/commands/worker-stream-consumer.ts protocol-layer/src/commands/worker-stream-consumer.test.ts protocol-layer/src/server.ts
git commit -m "feat: propagate trace id through protocol command pipeline"
```

---

### Task 5: Add Trace to Protocol Event Envelope and Kafka Header

**Files:**

- Modify: `protocol-layer/src/events/publisher.ts:19-42,215-270`
- Modify: `protocol-layer/src/events/publisher.test.ts`
- Modify: `protocol-layer/src/commands/master-consumer.ts:187-400`
- Modify: `protocol-layer/src/commands/master-consumer.test.ts`

**Interfaces:**

- Consumes: `currentTraceId()` and `resolveTraceCandidates()` from Task 3。
- Produces: `EventEnvelope.traceId: string` and `EventPublishOptions.traceId?: string`。
- Produces: Kafka event Header `traceId` exactly equals Envelope `traceId`。

- [ ] **Step 1: Write failing event propagation tests**

```typescript
it('publishes the current trace in both envelope and Kafka header', async () => {
  const publisher = await createEventPublisher(testConfig(), testMetrics(), testLogger())
  await runWithTrace(FIXED_TRACE_ID, () =>
    publisher.publish('account.state_changed', 'acc-1', { to: 'ONLINE' })
  )
  const message = producer.send.mock.calls[0]![0].messages[0]!
  expect(JSON.parse(String(message.value)).traceId).toBe(FIXED_TRACE_ID)
  expect(message.headers?.traceId).toBe(FIXED_TRACE_ID)
})

it('uses an explicit async trace when no scope is active', async () => {
  const publisher = await createEventPublisher(testConfig(), testMetrics(), testLogger())
  await publisher.publish('account.state_changed', 'acc-1', {}, undefined, {
    traceId: FIXED_TRACE_ID
  })
  const message = producer.send.mock.calls[0]![0].messages[0]!
  expect(JSON.parse(String(message.value)).traceId).toBe(FIXED_TRACE_ID)
})
```

Also add a Master owner-missing fallback test asserting the result event uses the original command Trace.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
npm test -- --runInBand src/events/publisher.test.ts src/commands/master-consumer.test.ts
```

Expected: FAIL because Event Envelope and Header do not contain Trace.

- [ ] **Step 3: Implement Event Trace selection and headers**

```typescript
const traceId = normalizeTraceId(options?.traceId)
  ?? currentTraceId()
  ?? createTraceId()

const envelope: EventEnvelope = {
  traceId,
  eventId: options?.eventId ?? createEventId(accountId, evt),
  event: evt,
  version: 'v1',
  accountId,
  occurredAt: new Date().toISOString(),
  workerId: config.workerId,
  evidence,
  data: data as Record<string, unknown>
}
```

Add `traceId: envelope.traceId` to Kafka headers. For every owner-missing fallback publish in `master-consumer.ts`, compute `const traceId = traceIdForCommand(failure.command.traceId, failure.command.commandId)` and pass `{ ...existingOptions, traceId }`; for the offline fallback that currently has no options, pass `undefined, { traceId }` as the fourth/fifth arguments.

After a broker-acknowledged command result or an account state/offline diagnosis event succeeds, emit one Pino info record containing only `traceId`、`eventId`、`event`、`accountId` and `topic`。Lower-value telemetry keeps its current log level so heartbeat volume is not promoted to info.

- [ ] **Step 4: Run focused tests, type-check and commit**

```bash
npm test -- --runInBand src/events/publisher.test.ts src/commands/master-consumer.test.ts
npm run lint
git add protocol-layer/src/events/publisher.ts protocol-layer/src/events/publisher.test.ts protocol-layer/src/commands/master-consumer.ts protocol-layer/src/commands/master-consumer.test.ts
git commit -m "feat: propagate trace id in protocol events"
```

---

### Task 6: Preserve Trace across asynchronous account-online lifecycle events

**Files:**

- Modify: `protocol-layer/src/commands/worker-consumer.ts:430-496`
- Modify: `protocol-layer/src/commands/worker-consumer.test.ts`
- Modify: `protocol-layer/src/worker/account-manager.ts:199-220,282-409,2114-2335,2734-2745,2868`
- Modify: `protocol-layer/src/worker/account-manager.heartbeat.test.ts`

**Interfaces:**

- Consumes: `MasterCommandEnvelope.traceId` and `EventPublishOptions.traceId`。
- Produces: `AccountBusinessRef.traceId?: string | null`。
- Lifecycle rule: `VERIFYING` and the first `ONLINE`/failure facts inherit the command Trace; after ONLINE or `releaseRuntimeSlot`, `businessRef.traceId` is cleared while other business correlation fields remain。

- [ ] **Step 1: Write failing command-to-business-reference test**

```typescript
const input: MasterCommandEnvelope = {
  commandId: 'cmd-online',
  traceId: FIXED_TRACE_ID,
  type: 'account.online.requested',
  version: 'v1',
  accountId: 'acc-online',
  createdAt: '2026-08-11T00:00:00.000Z',
  payload: {
    tenantId: 1,
    accountId: 100,
    protocolAccountId: 'acc-online',
    format: 'baileys_json',
    credential: { creds: { me: { id: 'acc-online' } }, keys: {} },
    proxy: {
      protocol: 'socks5',
      url: 'socks5://user:pass@127.0.0.1:1080',
      sessionId: 'session-test',
      country: 'IN'
    },
    source: 'manual_online'
  }
}
const online = jest.fn(async () => undefined)
const ack = jest.fn(async () => undefined)
await executeWorkerCommand(input, {
  accounts: { offline: async () => undefined, online },
  ack
})

expect(online).toHaveBeenCalledWith(
  expect.any(String), expect.any(Object), undefined, undefined, expect.any(String),
  expect.any(Object),
  expect.objectContaining({ commandId: 'cmd-online', traceId: FIXED_TRACE_ID })
)
```

Add an Account Manager test that captures `publisher.publish` options for `VERIFYING` and `ONLINE`, asserts both use the fixed Trace, then triggers a later heartbeat/offline fact and asserts its explicit option Trace is absent because Event Publisher must create the new lifecycle Trace.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
npm test -- --runInBand src/commands/worker-consumer.test.ts src/worker/account-manager.heartbeat.test.ts
```

Expected: FAIL because `AccountBusinessRef` has no Trace and async publishes lack explicit options.

- [ ] **Step 3: Carry Trace into the existing runtime business reference**

Add `traceId?: string | null` to `AccountBusinessRef` and `AccountOfflineRef`。Set `traceId: traceIdForCommand(command.traceId, command.commandId)` in `executeAccountOnline`。Include a normalized `traceId` field in `businessRefLogFields` so asynchronous Account Manager logs remain searchable even outside an active ALS Scope.

- [ ] **Step 4: Publish lifecycle events with the stored Trace and clear at terminal boundary**

Pass a fifth argument to the four initial lifecycle publishers:

```typescript
{ traceId: ctx.businessRef?.traceId ?? undefined }
```

Apply it in `publishVerifyStarted`, `publishStateChange`, `publishAlreadyOnline` and `publishOfflineDiagnosed`。After scheduling the `ONLINE` state event, set `ctx.businessRef.traceId = undefined`。At the end of `releaseRuntimeSlot`, also clear only `traceId`; retain `onlineAttemptId`, `commandId`, `batchId` and other business correlations.

- [ ] **Step 5: Run focused tests, type-check and commit**

```bash
npm test -- --runInBand src/commands/worker-consumer.test.ts src/worker/account-manager.heartbeat.test.ts
npm run lint
git add protocol-layer/src/commands/worker-consumer.ts protocol-layer/src/commands/worker-consumer.test.ts protocol-layer/src/worker/account-manager.ts protocol-layer/src/worker/account-manager.heartbeat.test.ts
git commit -m "feat: preserve trace across account online lifecycle"
```

---

### Task 7: Publish Trace from Armada Outbox to Kafka Command

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolCommandEnvelope.java:11-27`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java:235-259,438-464`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`

**Interfaces:**

- Consumes: `ProtocolCommandOutbox.traceId` and `TraceIds.stableFrom(commandId)`。
- Produces: `ProtocolCommandEnvelope.traceId()` and Kafka Header `traceId`。
- Invariant: persisted valid Trace wins; legacy null/invalid Outbox Trace deterministically derives from `commandId`。

- [ ] **Step 1: Write failing ProducerRecord tests**

```java
ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
verify(kafkaTemplate).send(captor.capture());

ProducerRecord<String, ProtocolCommandEnvelope> record = captor.getValue();
assertThat(record.value().traceId()).isEqualTo(FIXED_TRACE_ID);
assertThat(new String(record.headers().lastHeader(TraceIds.KAFKA_HEADER).value(), UTF_8))
        .isEqualTo(FIXED_TRACE_ID);
```

Add a legacy-row test that publishes the same `commandId` twice with null `traceId` and asserts both envelopes equal `TraceIds.stableFrom(commandId)`。

- [ ] **Step 2: Run the Producer test and verify failure**

```bash
mvn -q -Dtest=ProtocolCommandPublisherTest test
```

Expected: FAIL because Envelope has no Trace and Publisher uses `KafkaTemplate.send(topic,key,value)`.

- [ ] **Step 3: Add Envelope field and Kafka Header**

Add `String traceId` immediately after `commandId` in `ProtocolCommandEnvelope`。Resolve once in `toEnvelope`:

```java
String traceId = Optional.ofNullable(TraceIds.normalize(row.getTraceId()))
        .orElseGet(() -> TraceIds.stableFrom(row.getCommandId()));
```

Construct a `ProducerRecord<String, ProtocolCommandEnvelope>`, add a UTF-8 `RecordHeader(TraceIds.KAFKA_HEADER, envelope.traceId().getBytes(UTF_8))`, and call `kafkaTemplate.send(record)`。Inside the asynchronous `.handle(...)` callback, open `TraceContext.open(envelope.traceId())` before logging success/failure and close it afterward.

Keep the existing success log at debug level but add `traceId={}` explicitly to its message arguments. The MDC log pattern then provides the same value, while explicit output keeps the record searchable in nonstandard appenders.

- [ ] **Step 4: Run focused tests and commit**

```bash
mvn -q -Dtest=ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest test
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolCommandEnvelope.java armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java
git commit -m "feat: publish trace id with protocol commands"
```

---

### Task 8: Restore Armada MDC when consuming Protocol events

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/kafka/trace/KafkaTraceSupport.java`
- Create: `armada-api/src/test/java/com/armada/platform/kafka/trace/KafkaTraceSupportTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java:105-189`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java:126-142`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaListenerConfigurationTest.java:18-49`

**Interfaces:**

- Consumes: event JSON `traceId`, optional Kafka Header String and `TraceIds.resolveCandidates`。
- Produces: `KafkaTraceSupport.open(JsonNode,String,Logger,String stableSeed)` returning `TraceContext.Scope`。
- Invariant: sink invocation occurs inside Scope; Scope is restored after success and exception。

- [ ] **Step 1: Write failing precedence, cleanup and listener tests**

```java
@Test
void envelopeWinsOverHeaderAndScopeIsRestored() {
    Logger log = mock(Logger.class);
    JsonNode envelope = objectMapper.createObjectNode().put("traceId", FIXED_TRACE_ID);
    try (TraceContext.Scope ignored = KafkaTraceSupport.open(
            envelope, OTHER_TRACE_ID, log, "event-1")) {
        assertThat(TraceContext.current()).isEqualTo(FIXED_TRACE_ID);
    }
    assertThat(TraceContext.current()).isNull();
    verify(log).warn(
            "event traceId mismatch envelopeTraceId={} headerTraceId={}",
            FIXED_TRACE_ID, OTHER_TRACE_ID);
}
```

In each consumer test, make the mocked sink answer with an assertion on `TraceContext.current()` and assert MDC is null after the consumer method returns and after a sink exception.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
mvn -q -Dtest=KafkaTraceSupportTest,ProtocolAccountEventConsumerTest,ProtocolGroupEventConsumerTest,ProtocolKafkaListenerConfigurationTest test
```

Expected: FAIL because consumers do not establish MDC and listener signatures have no Header parameter.

- [ ] **Step 3: Implement shared Kafka Trace resolution**

`KafkaTraceSupport.open` reads `envelope.path("traceId").asText(null)` and resolves against the Header. It logs `event traceId mismatch` only when both candidates are individually valid and different; an invalid raw Header is never logged. It then returns `TraceContext.open(resolution.traceId())`。

- [ ] **Step 4: Wrap all three listener entry points without breaking existing direct tests**

For `onStateMessage`, `onGroupSyncMessage` and group `onMessage`, keep the existing one-argument public method as a test/legacy overload and add an annotated two-argument method:

```java
@KafkaListener(/* existing properties */)
public void onMessage(
        String rawMessage,
        @Header(name = TraceIds.KAFKA_HEADER, required = false) String headerTraceId) {
    JsonNode envelope = readEnvelope(rawMessage);
    try (TraceContext.Scope ignored = KafkaTraceSupport.open(
            envelope, headerTraceId, log, text(envelope, "eventId"))) {
        handleEnvelope(envelope);
    }
}

public void onMessage(String rawMessage) {
    onMessage(rawMessage, null);
}
```

Extract only the existing dispatch body to `handleEnvelope(JsonNode)`; do not alter event validation or sink behavior. Update `ProtocolKafkaListenerConfigurationTest` to reflect the annotated `(String,String)` overload.

- [ ] **Step 5: Run focused tests and commit**

```bash
mvn -q -Dtest=KafkaTraceSupportTest,ProtocolAccountEventConsumerTest,ProtocolGroupEventConsumerTest,ProtocolKafkaListenerConfigurationTest test
git add armada-api/src/main/java/com/armada/platform/kafka/trace armada-api/src/test/java/com/armada/platform/kafka/trace armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaListenerConfigurationTest.java
git commit -m "feat: restore trace context for protocol events"
```

---

### Task 9: Cross-repository contract, regression suite and documentation

**Files:**

- Modify: `armada/docs/superpowers/specs/2026-08-11-full-chain-trace-id-design.md`
- Modify: `armada/.harness/changes/2026-08-11-full-chain-trace-id.md`
- Modify: `armada-protocol/protocol-layer/docs/pull-task-diagnose.md`

**Interfaces:**

- Consumes: all tasks above。
- Produces: verified shared contract and operator search procedure。

- [ ] **Step 1: Run the complete local Armada Trace suite**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=TraceIdsTest,TraceContextTest,TraceIdFilterTest,TraceIdClientHttpRequestInterceptorTest,ProtocolCommandOutboxTraceMigrationSqlTest,ProtocolCommandOutboxServiceImplTest,ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest,KafkaTraceSupportTest,ProtocolAccountEventConsumerTest,ProtocolGroupEventConsumerTest,ProtocolKafkaListenerConfigurationTest test
```

Expected: PASS with zero failures and zero errors.

- [ ] **Step 2: Run the complete local Protocol suite**

Run from `armada-protocol/protocol-layer`:

```bash
npm test -- --runInBand
npm run lint
npm run build
```

Expected: Jest PASS, `tsc --noEmit` PASS, production build PASS.

- [ ] **Step 3: Verify contract names and forbidden scope mechanically**

Run from `/Users/daishuaishuai/IdeaProjects`:

```bash
grep -Rsn --include='*.java' --include='*.ts' --include='*.sql' 'X-Trace-Id\|traceId\|trace_id' armada/armada-api/src armada-protocol/protocol-layer/src
git -C armada diff --check
git -C armada-protocol diff --check
```

Review the output and assert:

- Both sides contain the fixed sample `0123456789abcdef0123456789abcdef` in contract tests.
- No business table migration contains `trace_id`.
- Kafka commands and events both contain Envelope and Header propagation.
- No credential-bearing payload is added to Trace logs.

- [ ] **Step 4: Update operator documentation and evidence**

Replace the protocol diagnosis note that says there is no unified Trace. Document this search sequence:

```text
1. Read X-Trace-Id from the HTTP response or any error log.
2. Search traceId=<value> across Armada and Protocol logs.
3. Use accountId/taskId/commandId/eventId to cross-check business identity.
4. For a later heartbeat/reconnect incident, start from its new traceId and correlate by accountId/onlineAttemptId.
```

Mark the design status as implemented locally. Add exact test commands and outputs to `.harness/changes/2026-08-11-full-chain-trace-id.md`; leave deployment status unchanged.

- [ ] **Step 5: Commit documentation in each repository**

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada add docs/superpowers/specs/2026-08-11-full-chain-trace-id-design.md .harness/changes/2026-08-11-full-chain-trace-id.md
git -C /Users/daishuaishuai/IdeaProjects/armada commit -m "docs: record trace id verification"

git -C /Users/daishuaishuai/IdeaProjects/armada-protocol add protocol-layer/docs/pull-task-diagnose.md
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol commit -m "docs: add trace based diagnosis workflow"
```

---

### Task 10: Confirmed test-environment migration and end-to-end evidence

**Files:**

- Modify after generation: `armada/.harness/wiki/数据模型.md`
- Modify: `armada/.harness/changes/2026-08-11-full-chain-trace-id.md`

**Interfaces:**

- Consumes: confirmed test database and test service deployment targets。
- Produces: real schema evidence, regenerated data model and one end-to-end Trace log chain。

- [ ] **Step 1: Stop and obtain explicit confirmation of target environment**

Report only the database host label/schema name and service environment name without printing usernames, passwords, tokens or PEM contents. Do not connect, migrate, deploy or SSH until the user confirms the target is the intended test environment.

- [ ] **Step 2: Apply Flyway and run real-schema tests after confirmation**

Run from `armada`:

```bash
armada-api/dbtest.sh ProtocolCommandOutboxSchemaDbTest
armada-api/dbtest.sh ProtocolCommandOutboxMapperDbTest
```

Expected: Flyway applies `V111`; schema test reports nullable `varchar(32)` `trace_id`; Mapper test reads back the same Trace; existing dispatch/status tests remain green.

- [ ] **Step 3: Regenerate the data model from confirmed test DB metadata**

Export the confirmed database's `information_schema.columns`、`statistics` and `tables` into the generator's existing `/tmp/wheel_columns.tsv`、`/tmp/wheel_indexes.tsv` and `/tmp/wheel_tables.tsv` inputs using the team's credential-safe procedure. Then run:

```bash
cd .harness/wiki
python3 gen_datamodel.py
```

Expected: `/tmp/datamodel_tables.md` contains `protocol_command_outbox.trace_id` with type `varchar(32)`, nullable `YES`, no Trace index, and comment `全链路追踪标识`. Replace `.harness/wiki/数据模型.md` only with this real generated output; never hand-edit the generated document.

- [ ] **Step 4: Deploy in compatibility order after environment confirmation**

Deploy exactly in this order:

```text
1. V111 database migration
2. armada-protocol version that accepts optional Trace
3. armada version that writes and emits Trace
```

No production deployment is part of this plan unless the user separately names and approves production.

- [ ] **Step 5: Capture one real end-to-end Trace**

Trigger one test operation through an Armada HTTP endpoint. Read `X-Trace-Id` from the response and verify logs contain the same value at all checkpoints:

```text
Armada HTTP -> Outbox insert -> Kafka command publish
-> Protocol Master receipt/route -> Redis Stream -> Worker execution
-> Protocol Kafka event publish -> Armada Kafka consume/sink
```

Also capture the associated `accountId`、`taskId/itemId` where applicable、`commandId` and `eventId`。Repeat with an Outbox retry or redelivery and verify the original Trace remains unchanged.

- [ ] **Step 6: Record evidence and commit generated documentation**

```bash
git add .harness/wiki/数据模型.md .harness/changes/2026-08-11-full-chain-trace-id.md
git commit -m "docs: record test environment trace verification"
```

The change record must contain the confirmed environment name, deployed commits, Flyway result, test commands, and sanitized log checkpoint list; it must not contain credentials or raw sensitive payloads.
