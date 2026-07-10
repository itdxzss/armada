# Android Zhuan Batch Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unusable Android protocol runtime with `whatsapp-server-feature-android-zhuan` for six-segment batch account online/offline commands and unified Armada account-state events.

**Architecture:** Armada keeps its existing batch API, outbox, Kafka routing, and state sink. Armada normalizes the Zhuan six-segment credential order and publishes structured lifecycle commands; a new focused `internal/armada` package inside Zhuan consumes those commands, calls `SixLoginService`/`LogOutService`, correlates callbacks through Redis, and publishes `account.state_changed` exactly once per command state.

**Tech Stack:** Java 17, Spring Boot 3.3, JUnit 5, AssertJ, Mockito, MyBatis, Spring Kafka; Go 1.25, Gin, `kafka-go`, `go-redis/v9`, `miniredis`, Zap; MySQL, Redis, Kafka.

---

## Execution prerequisites and file map

Read the approved design first:

- `armada/docs/superpowers/specs/2026-07-10-android-zhuan-batch-lifecycle-design.md`

Execution must begin with `superpowers:using-git-worktrees` for the `armada` repository because the current checkout contains unrelated marketing changes. Do not stage, commit, rewrite, or clean those changes.

`whatsapp-server-feature-android-zhuan` is currently a source snapshot without Git metadata. Task 1 establishes a safe baseline repository before behavior changes. Its private TOML files, `.env`, logs, caches, archives, binaries, and PEM files must remain untracked.

### Armada files

- Modify `armada-api/src/main/java/com/armada/account/service/AccountImportParser.java`: normalize the verified Zhuan six-segment order.
- Modify `armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java`: prove the new field mapping and row-level validation.
- Modify `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolOnlineCommandRequest.java`: carry `isBusiness` without carrying credentials.
- Modify `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`: derive `isBusiness` from immutable account type.
- Modify `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`: cover personal/business commands.
- Modify `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`: persist `isBusiness` in the safe outbox payload.
- Modify `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`: prove safe payload contents.
- Modify `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`: hydrate Zhuan metadata into Kafka.
- Modify `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`: lock the exact Zhuan command contract.
- Create `docs/operations/android-zhuan-lifecycle-cutover.md`: record non-automatic deletion and cutover gates.

### Zhuan files

- Modify `.gitignore` and `configs/prod_configs_example.toml`: establish a safe source-control baseline.
- Modify `go.mod` and `go.sum`: add `kafka-go` and test-only `miniredis`.
- Create `internal/armada/command.go` and `command_test.go`: command and sixdata mapping.
- Create `internal/armada/context_store.go` and `context_store_test.go`: Redis context indexes.
- Create `internal/armada/publish_once.go` and `publish_once_test.go`: state-event deduplication.
- Create `internal/armada/event.go` and `event_test.go`: unified state mapping.
- Modify `internal/external/type.go`; create `type_test.go`: preserve login reason codes.
- Modify `internal/service/app/waapp.go`: emit the tested login-failure event.
- Create `internal/armada/executor.go` and `executor_test.go`: online/offline execution.
- Modify `internal/external/art.go`; create `art_test.go`: internal callback observers.
- Create `internal/armada/callback.go` and `callback_test.go`: callback bridge.
- Create `internal/armada/kafka.go`, `client.go`, `client_test.go`, `consumer.go`, and `consumer_test.go`: Kafka transport.
- Create `internal/armada/options.go`, `options_test.go`, `config.go`, `start.go`, and `start_test.go`: configuration and composition.
- Modify `internal/configs/configs.go`, both production example TOMLs, and `cmd/server/main.go`: startup wiring.

## Task 1: Establish a safe Zhuan source-control baseline

**Files:**
- Modify: `whatsapp-server-feature-android-zhuan/.gitignore`
- Modify: `whatsapp-server-feature-android-zhuan/configs/prod_configs_example.toml`

- [ ] **Step 1: Extend ignore rules before Git initialization**

Add these exact entries if absent:

```gitignore
.DS_Store
.gocache/
.gomodcache/
*.log
*.pem
*.key
deploy/.env
deploy/configs/prod_configs.toml
configs/dev_configs.toml
configs/prod_configs.toml
```

- [ ] **Step 2: Remove example credential literals**

Make the non-deploy example use empty credentials:

```toml
[redis]
addr = "127.0.0.1:6379"
pass = ""
db = 0
maxretries = 3
minidleconns = 5
poolsize = 20

[mysql]
user = ""
pass = ""
addr = "127.0.0.1:3306"
name = "whatsapp_android_zhuan"
charset = "utf8mb4"
debug = false
connmaxlifetime = 60
maxidleconn = 20
maxopenconn = 50

[server]
host = "http://localhost:8080/api/protocol/callback"
```

- [ ] **Step 3: Run the unchanged baseline tests**

Run from `whatsapp-server-feature-android-zhuan`:

```bash
go test ./...
```

Expected: exit code `0`; no real WhatsApp login is attempted.

- [ ] **Step 4: Initialize Git and stage the baseline**

```bash
git init
git add .
git status --short
```

Expected: safe source is staged; private configs, logs, caches, binaries, archives, `.env`, PEM, and key files are absent.

- [ ] **Step 5: Run the staged-file gate**

```bash
git diff --cached --name-only | rg '(^|/)(dev_configs\.toml|prod_configs\.toml|\.env|[^/]+\.(pem|key|log|zip))$'
```

Expected: no output. If any file is listed, unstage it and strengthen `.gitignore`.

- [ ] **Step 6: Commit the baseline**

```bash
git commit -m "chore: baseline android zhuan source"
```

## Task 2: Normalize Armada six-segment imports to the Zhuan order

**Files:**
- Modify: `armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountImportParser.java`

- [ ] **Step 1: Replace the old-order test with a Zhuan-order test**

```java
@Test
void six_zhuanOrder_normalizesSemanticCredentialFields() {
    String line = "919000000001,static-pub,static-pri,identity-pub,identity-pri,phone-id";

    List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, line);

    assertThat(entries).hasSize(1);
    ParsedEntry entry = entries.get(0);
    assertThat(entry.getParseError()).isNull();
    assertThat(entry.getWid()).isEqualTo("919000000001");
    assertThat(entry.getRawPayload()).isEqualTo(line);
    assertThat(entry.getData().get("static_pub_key").asText()).isEqualTo("static-pub");
    assertThat(entry.getData().get("static_pri_key").asText()).isEqualTo("static-pri");
    assertThat(entry.getData().get("id_pub_key").asText()).isEqualTo("identity-pub");
    assertThat(entry.getData().get("id_pri_key").asText()).isEqualTo("identity-pri");
    assertThat(entry.getData().get("phone_id").asText()).isEqualTo("phone-id");
    assertThat(entry.getData().has("device_identity_key")).isFalse();
}
```

Add these row-level assertions:

```java
@Test
void six_wrongColumnCount_marksOnlyThatRowFailed() {
    List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null,
            "919000000001,static-pub,static-pri,identity-pub,identity-pri");
    assertThat(entries).singleElement().extracting(ParsedEntry::getParseError)
            .asString().contains("应为6列");
}

@Test
void six_emptyPhoneId_marksOnlyThatRowFailed() {
    List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null,
            "919000000001,static-pub,static-pri,identity-pub,identity-pri,");
    assertThat(entries).singleElement().extracting(ParsedEntry::getParseError)
            .asString().contains("第6列为空");
}

@Test
void six_invalidPhone_marksOnlyThatRowFailed() {
    List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null,
            "phone,static-pub,static-pri,identity-pub,identity-pri,phone-id");
    assertThat(entries).singleElement().extracting(ParsedEntry::getParseError)
            .asString().contains("wid 不合法");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `armada/armada-api`:

```bash
mvn -Dtest=AccountImportParserTest#six_zhuanOrder_normalizesSemanticCredentialFields test
```

Expected: FAIL because the current parser writes old field names.

- [ ] **Step 3: Change only the six-column labels and JSON mapping**

```java
if (parts.length != 6) {
    entry.setParseError(
            "六段格式错误:应为6列(phone,static_pub_key,static_pri_key,id_pub_key,id_pri_key,phone_id)");
    return entry;
}

ObjectNode data = mapper.createObjectNode();
data.put("phone", phone);
data.put("static_pub_key", parts[1]);
data.put("static_pri_key", parts[2]);
data.put("id_pub_key", parts[3]);
data.put("id_pri_key", parts[4]);
data.put("phone_id", parts[5]);
entry.setData(data);
```

Update comments to the same order. Do not add compatibility for the old order.

- [ ] **Step 4: Run the class tests and verify GREEN**

```bash
mvn -Dtest=AccountImportParserTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/account/service/AccountImportParser.java armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java
git commit -m "feat(account): align six credentials with zhuan"
```

## Task 3: Carry business type through Armada safe outbox metadata

**Files:**
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolOnlineCommandRequest.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`

- [ ] **Step 1: Add a compile-only request shape and keep the baseline green**

End the `ProtocolOnlineCommandRequest` record signature with:

```java
ProtocolBackend protocolBackend,
boolean isBusiness
```

Keep an eight-argument constructor delegating to `isBusiness=false`, and keep the seven-argument constructor delegating to `WEB, false`. Do not yet derive or persist the value.

```bash
mvn -Dtest=AccountOnlineCommandServiceImplTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: PASS; this is compile scaffolding only, with no new behavior.

- [ ] **Step 2: Write failing command-construction tests**

Set `account.setAccountType(2)` in the existing single Android test and add:

```java
assertThat(command.isBusiness()).isTrue();
```

Add a two-account batch assertion:

```java
assertThat(commandsCaptor.getValue())
        .extracting(ProtocolOnlineCommandRequest::isBusiness)
        .containsExactly(false, true);
```

- [ ] **Step 3: Write a failing safe-payload assertion**

Construct an Android command with `isBusiness=true`, enqueue it, parse `row.getPayloadJson()`, and assert:

```java
assertThat(payload)
        .containsEntry("isBusiness", true)
        .doesNotContainKeys("credential", "sixdata", "proxy");
```

- [ ] **Step 4: Run and verify RED**

```bash
mvn -Dtest=AccountOnlineCommandServiceImplTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: tests compile, then FAIL because constructed business commands still contain `false` and the outbox payload has no `isBusiness` key.

- [ ] **Step 5: Derive the immutable account type**

Add to `AccountOnlineCommandServiceImpl`:

```java
private static boolean isBusiness(Account account) {
    return account != null && Integer.valueOf(2).equals(account.getAccountType());
}
```

Pass `isBusiness(account)` from both single and batch command construction.

- [ ] **Step 6: Persist only the boolean in the safe payload**

Add `boolean isBusiness` to `ProtocolOnlineCommandPayload`, and construct it with:

```java
ProtocolOnlineCommandPayload payload = new ProtocolOnlineCommandPayload(
        command.accountId(),
        command.protocolAccountId(),
        command.credentialFormat(),
        command.proxyId(),
        command.source(),
        command.onlineAttemptId(),
        command.previousOnlineAttemptId(),
        command.protocolBackend(),
        command.isBusiness());
```

- [ ] **Step 7: Run and verify GREEN**

```bash
mvn -Dtest=AccountOnlineCommandServiceImplTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolOnlineCommandRequest.java armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java
git commit -m "feat(protocol): carry android business account type"
```

## Task 4: Lock the Armada-to-Zhuan Kafka payload contract

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`

- [ ] **Step 1: Write the failing Android payload test**

Use an outbox safe payload containing `isBusiness=true`; make the credential mapper return:

```json
{"phone":"919000000001","static_pub_key":"static-pub","static_pri_key":"static-pri","id_pub_key":"identity-pub","id_pri_key":"identity-pri","phone_id":"phone-id"}
```

Capture the envelope and assert:

```java
JsonNode payload = captor.getValue().payload();
assertThat(payload.get("format").asText()).isEqualTo("six");
assertThat(payload.get("isBusiness").asBoolean()).isTrue();
assertThat(payload.path("credential").path("static_pub_key").asText()).isEqualTo("static-pub");
assertThat(payload.path("credential").path("phone_id").asText()).isEqualTo("phone-id");
assertThat(payload.path("proxy").path("url").asText()).startsWith("socks5://");
assertThat(payload.get("protocolBackend").asText()).isEqualTo("ANDROID");
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
mvn -Dtest=ProtocolCommandPublisherTest#publishBatch_onlineAndroidRowBuildsZhuanLifecyclePayload test
```

Expected: FAIL because `isBusiness` is absent from the hydrated payload.

- [ ] **Step 3: Hydrate the boolean**

Add `boolean isBusiness` to `OnlineRowRef` and `OnlineCommandKafkaPayload`. Read it with:

```java
private static boolean booleanValue(JsonNode payload, String fieldName, boolean defaultValue) {
    JsonNode value = payload.path(fieldName);
    if (value.isMissingNode() || value.isNull()) {
        return defaultValue;
    }
    if (!value.isBoolean()) {
        throw validation("协议上线命令字段不是布尔值: " + fieldName);
    }
    return value.asBoolean();
}
```

Pass `ref.isBusiness()` into `OnlineCommandKafkaPayload` between `proxy` and `source`.

- [ ] **Step 4: Run tests and verify GREEN**

```bash
mvn -Dtest=ProtocolCommandPublisherTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java
git commit -m "feat(kafka): publish zhuan lifecycle metadata"
```

## Task 5: Decode lifecycle commands and assemble sixdata

**Files:**
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/command_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/command.go`

- [ ] **Step 1: Add compile-only command API scaffolding**

Create `command.go` with the JSON-tagged `ProtocolCommand`, `CommandPayload`, `SixCredential`, `ProxyPayload`, and `CommandContext` data shapes plus the two lifecycle command constants. Add signatures that return a private sentinel and no useful value:

```go
var errCommandBehaviorNotImplemented = errors.New("armada command behavior not implemented")

func ParseCommand([]byte) (ProtocolCommand, error) {
    return ProtocolCommand{}, errCommandBehaviorNotImplemented
}

func (ProtocolCommand) ToSixLoginDTO() (*dto.SixLoginDto, CommandContext, error) {
    return nil, CommandContext{}, errCommandBehaviorNotImplemented
}

func (ProtocolCommand) ToLogout(*CommandContext) (string, string, error) {
    return "", "", errCommandBehaviorNotImplemented
}
```

Run `go test ./internal/armada`; expected PASS with no tests in the package. Do not decode JSON, validate fields, or assemble `sixdata` in this step.

- [ ] **Step 2: Write failing online/offline mapping tests**

```go
func TestOnlineCommandBuildsVerifiedZhuanSixData(t *testing.T) {
    raw := []byte(`{"commandId":"cmd_1","batchId":"batch_1","commandType":"account.online.requested","aggregateType":"ACCOUNT","aggregateId":100,"protocolAccountId":"acc_919000000001","payload":{"tenantId":7,"accountId":100,"protocolAccountId":"acc_919000000001","format":"six","credential":{"phone":"919000000001","static_pub_key":"static-pub","static_pri_key":"static-pri","id_pub_key":"identity-pub","id_pri_key":"identity-pri","phone_id":"phone-id"},"proxy":{"protocol":"SOCKS5","url":"socks5://user:pass@proxy.test:1080"},"isBusiness":true,"source":"batch_online","onlineAttemptId":"oa_1","previousOnlineAttemptId":null,"protocolBackend":"ANDROID"}}`)

    command, err := ParseCommand(raw)
    if err != nil { t.Fatal(err) }
    login, commandContext, err := command.ToSixLoginDTO()
    if err != nil { t.Fatal(err) }

    want := "919000000001,static-pub,static-pri,identity-pub,identity-pri,phone-id"
    if login.SixData != want { t.Fatalf("sixdata = %q, want %q", login.SixData, want) }
    if login.Socks5 != "socks5://user:pass@proxy.test:1080" || !login.IsBusiness { t.Fatalf("login = %#v", login) }
    if commandContext.CommandID != "cmd_1" { t.Fatalf("context = %#v", commandContext) }
}
```

Add these focused cases next to the online test:

```go
func TestOnlineCommandRejectsNonSixFormat(t *testing.T) {
    command := validOnlineCommand()
    command.Payload.Format = "baileys_json"
    if _, _, err := command.ToSixLoginDTO(); err == nil || !strings.Contains(err.Error(), "format must be six") {
        t.Fatalf("err = %v", err)
    }
}

func TestOnlineCommandRejectsMissingPhoneID(t *testing.T) {
    command := validOnlineCommand()
    command.Payload.Credential.PhoneID = ""
    if _, _, err := command.ToSixLoginDTO(); err == nil || !strings.Contains(err.Error(), "phone_id") {
        t.Fatalf("err = %v", err)
    }
}

func TestOfflineCommandUsesStoredPhone(t *testing.T) {
    command := ProtocolCommand{CommandID: "cmd_off", CommandType: CommandTypeAccountOfflineRequested,
        ProtocolAccountID: "acc_919000000001", Payload: CommandPayload{TenantID: 7, AccountID: 100}}
    phone, _, err := command.ToLogout(&CommandContext{Phone: "919000000009", ProtocolAccountID: "acc_919000000001"})
    if err != nil || phone != "919000000009" { t.Fatalf("phone = %q, err = %v", phone, err) }
}

func TestOfflineCommandDerivesPhoneFromProtocolAccountID(t *testing.T) {
    command := ProtocolCommand{CommandID: "cmd_off", CommandType: CommandTypeAccountOfflineRequested,
        ProtocolAccountID: "acc_919000000001", Payload: CommandPayload{TenantID: 7, AccountID: 100}}
    phone, _, err := command.ToLogout(nil)
    if err != nil || phone != "919000000001" { t.Fatalf("phone = %q, err = %v", phone, err) }
}
```

Define `validOnlineCommand()` in the test file with the same semantic fields as the JSON-envelope test; it is test setup, not production behavior.

- [ ] **Step 3: Run and verify RED**

```bash
go test ./internal/armada -run 'TestOnlineCommand|TestOfflineCommand'
```

Expected: tests compile, then FAIL with `armada command behavior not implemented`.

- [ ] **Step 4: Implement the command behavior**

```go
type SixCredential struct {
    Phone        string `json:"phone"`
    StaticPubKey string `json:"static_pub_key"`
    StaticPriKey string `json:"static_pri_key"`
    IdentityPub  string `json:"id_pub_key"`
    IdentityPri  string `json:"id_pri_key"`
    PhoneID      string `json:"phone_id"`
}

type CommandPayload struct {
    TenantID                int64          `json:"tenantId"`
    AccountID               int64          `json:"accountId"`
    ProtocolAccountID       string         `json:"protocolAccountId"`
    Format                  string         `json:"format"`
    Credential              SixCredential `json:"credential"`
    Proxy                   ProxyPayload   `json:"proxy"`
    IsBusiness              bool           `json:"isBusiness"`
    Phone                   string         `json:"phone"`
    Source                  string         `json:"source"`
    OnlineAttemptID         string         `json:"onlineAttemptId"`
    PreviousOnlineAttemptID string         `json:"previousOnlineAttemptId"`
    ProtocolBackend         string         `json:"protocolBackend"`
}
```

`ToSixLoginDTO` joins exactly:

```go
SixData: strings.Join([]string{
    credential.Phone,
    credential.StaticPubKey,
    credential.StaticPriKey,
    credential.IdentityPub,
    credential.IdentityPri,
    credential.PhoneID,
}, ",")
```

Define `CommandContext` with tenant/account/protocol account/phone/command/batch/source/current and previous attempt IDs.

- [ ] **Step 5: Run and verify GREEN**

```bash
go test ./internal/armada -run 'TestOnlineCommand|TestOfflineCommand'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add internal/armada/command.go internal/armada/command_test.go
git commit -m "feat(armada): map zhuan lifecycle commands"
```

## Task 6: Persist callback context and deduplicate state events

**Files:**
- Modify: `whatsapp-server-feature-android-zhuan/go.mod`
- Modify: `whatsapp-server-feature-android-zhuan/go.sum`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/context_store_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/context_store.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/publish_once_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/publish_once.go`

- [ ] **Step 1: Add the Redis test dependency**

```bash
go get -t github.com/alicebob/miniredis/v2@v2.35.0
```

- [ ] **Step 2: Add compile-only Redis API scaffolding**

Define the compile-only APIs exactly as follows; each method returns `errRedisBehaviorNotImplemented` without reading or writing Redis:

```go
func NewRedisContextStore(client *redis.Client, ttl time.Duration) *RedisContextStore
func (s *RedisContextStore) Save(ctx context.Context, command CommandContext) error
func (s *RedisContextStore) FindByProtocolAccountID(ctx context.Context, id string) (*CommandContext, error)
func (s *RedisContextStore) FindByPhone(ctx context.Context, phone string) (*CommandContext, error)

func NewRedisPublishOnceGuard(client *redis.Client, ttl time.Duration) *RedisPublishOnceGuard
func (g *RedisPublishOnceGuard) Claim(ctx context.Context, commandID, target string) (bool, error)
func (g *RedisPublishOnceGuard) Release(ctx context.Context, commandID, target string) error
func (g *RedisPublishOnceGuard) MarkCommandPublished(ctx context.Context, commandID string) error
func (g *RedisPublishOnceGuard) CommandPublished(ctx context.Context, commandID string) (bool, error)
```

```bash
go test ./internal/armada
```

Expected: PASS before the new tests are added.

- [ ] **Step 3: Write failing Redis tests**

Use `miniredis.RunT(t)` with a real `redis.Client`. Verify `Save` followed by `FindByProtocolAccountID` and `FindByPhone` returns the exact context. Verify first state claim succeeds, a duplicate fails, command publication is observable, and `Release` permits a new claim.

```go
claimed, err := guard.Claim(ctx, "cmd_1", "ONLINE")
if err != nil || !claimed { t.Fatalf("first claim = %v, %v", claimed, err) }
claimed, err = guard.Claim(ctx, "cmd_1", "ONLINE")
if err != nil || claimed { t.Fatalf("second claim = %v, %v", claimed, err) }
```

- [ ] **Step 4: Run and verify RED**

```bash
go test ./internal/armada -run 'TestRedisContextStore|TestRedisPublishOnce'
```

Expected: tests compile, then FAIL with `armada redis behavior not implemented`; verify Redis has no keys so the test cannot pass accidentally.

- [ ] **Step 5: Implement Redis-backed storage**

Use these exact namespaces:

```go
func contextKeyByProtocolAccountID(v string) string { return "armada:zhuan:context:protocol:" + v }
func contextKeyByPhone(v string) string { return "armada:zhuan:context:phone:" + v }
func publishStateKey(commandID, target string) string { return "armada:zhuan:published:state:" + commandID + ":" + target }
func publishCommandKey(commandID string) string { return "armada:zhuan:published:command:" + commandID }
```

`Claim` uses `SET NX`; `Release` deletes the state key; `MarkCommandPublished` sets the command marker only after Kafka publication succeeds.

- [ ] **Step 6: Run and verify GREEN**

```bash
go test ./internal/armada -run 'TestRedisContextStore|TestRedisPublishOnce'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add go.mod go.sum internal/armada/context_store.go internal/armada/context_store_test.go internal/armada/publish_once.go internal/armada/publish_once_test.go
git commit -m "feat(armada): persist lifecycle callback context"
```

## Task 7: Preserve WhatsApp failure reasons and map Zhuan states

**Files:**
- Create: `whatsapp-server-feature-android-zhuan/internal/external/type_test.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/external/type.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/service/app/waapp.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/event_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/event.go`

- [ ] **Step 1: Add compile-only failure-event scaffolding**

Add the optional field to `WhatsAppEventPayload`:

```go
ReasonCode int `json:"reasonCode,omitempty"`
```

Add `NewLoginFailedEvent` with its final signature but return `&WhatsAppEventPayload{}`. Run `go test ./internal/external ./internal/service/app`; expected PASS. Do not edit `waapp.go` yet.

- [ ] **Step 2: Write a failing login-failure factory test**

```go
func TestNewLoginFailedEventPreservesReasonCode(t *testing.T) {
    event := NewLoginFailedEvent("919000000001", "919000000001", "403")
    if event.Code != -401 || event.ReasonCode != 403 || event.EventType != EventLoginFailed {
        t.Fatalf("event = %#v", event)
    }
}
```

- [ ] **Step 3: Run and verify RED**

```bash
go test ./internal/external -run TestNewLoginFailedEventPreservesReasonCode
```

Expected: test compiles, then FAIL because the returned code, reason, and event type are zero values.

- [ ] **Step 4: Implement the factory and use it in the login callback**

Replace the scaffold with:

```go
func NewLoginFailedEvent(user, qrID, reason string) *WhatsAppEventPayload {
    reasonCode, _ := strconv.Atoi(strings.TrimSpace(reason))
    return &WhatsAppEventPayload{
        User: user,
        QrID: qrID,
        Code: -401,
        ReasonCode: reasonCode,
        Message: fmt.Sprintf("登录失败: %s", reason),
        EventType: EventLoginFailed,
    }
}
```

Replace the inline login-failure payload in `waapp.go` with `external.NewLoginFailedEvent(...)`.

- [ ] **Step 5: Run and verify GREEN**

```bash
go test ./internal/external -run TestNewLoginFailedEventPreservesReasonCode
```

Expected: PASS.

- [ ] **Step 6: Add compile-only event-mapping scaffolding**

Create `EventEnvelope` and `StateChangedData` with their final JSON fields, then add:

```go
var errEventMappingNotImplemented = errors.New("armada event mapping not implemented")

func BuildStateChangedEvent(
    command CommandContext,
    payload external.WhatsAppEventPayload,
    workerID string,
    occurredAt time.Time,
) (EventEnvelope, error) {
    return EventEnvelope{}, errEventMappingNotImplemented
}
```

Run `go test ./internal/armada`; expected PASS before `event_test.go` is added.

- [ ] **Step 7: Write failing state-mapping tests**

```go
tests := []struct {
    name string
    payload external.WhatsAppEventPayload
    wantTo string
    wantSemantic string
    wantRawCode int
}{
    {"online", external.WhatsAppEventPayload{Code: 200, EventType: external.EventLoginSuccess}, "ONLINE", "", 200},
    {"offline", external.WhatsAppEventPayload{Code: 101, OffLineType: external.OfflineTypeNormal}, "OFFLINE", "", 101},
    {"proxy", external.WhatsAppEventPayload{Code: 302}, "PROXY_FAILED", "PROXY_FAILED", 302},
    {"replaced", external.WhatsAppEventPayload{Code: 303}, "LOGIN_REPLACED", "LOGIN_REPLACED", 303},
    {"removed", external.WhatsAppEventPayload{Code: -407}, "DEVICE_REMOVED", "DEVICE_REMOVED", -407},
    {"banned", external.WhatsAppEventPayload{Code: -401, ReasonCode: 403, EventType: external.EventLoginFailed}, "NEED_REAUTH", "LOGIN_FAILED", 403},
}
```

For each case, build an event with a complete `CommandContext` and assert `Data.To`, `Data.Semantic`, and `Data.RawCode`.

- [ ] **Step 8: Run and verify RED**

```bash
go test ./internal/armada -run TestBuildStateChangedEvent
```

Expected: test compiles, then FAIL with `armada event mapping not implemented`.

- [ ] **Step 9: Implement the Java-compatible event envelope**

```go
type EventEnvelope struct {
    EventID    string           `json:"eventId"`
    Event      string           `json:"event"`
    Version    string           `json:"version"`
    AccountID  string           `json:"accountId"`
    OccurredAt string           `json:"occurredAt"`
    WorkerID   string           `json:"workerId"`
    Data       StateChangedData `json:"data"`
}
```

Set `event="account.state_changed"`, top-level `accountId=ProtocolAccountID`, RFC3339 `occurredAt`, and all context fields. Prefer `ReasonCode` over `Code` when nonzero. Use stable event IDs:

```go
eventID := fmt.Sprintf("%s:account.state_changed:%s", command.CommandID, targetState)
```

- [ ] **Step 10: Run and verify GREEN**

```bash
go test ./internal/armada -run TestBuildStateChangedEvent
go test ./internal/external ./internal/service/app
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add internal/external/type.go internal/external/type_test.go internal/service/app/waapp.go internal/armada/event.go internal/armada/event_test.go
git commit -m "feat(armada): map zhuan lifecycle states"
```

## Task 8: Execute six-login and idempotent logout commands

**Files:**
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/executor_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/executor.go`

- [ ] **Step 1: Add compile-only executor scaffolding**

Create the `ContextStore` and `StateEventService` interfaces shown in Step 4, the injectable function types, and:

```go
var errExecutorNotImplemented = errors.New("armada lifecycle executor not implemented")

type LifecycleExecutor struct {
    Contexts ContextStore
    Events   StateEventService
    SixLogin SixLoginFunc
    Logout   LogoutFunc
}

func (e *LifecycleExecutor) Execute(context.Context, ProtocolCommand) error {
    return errExecutorNotImplemented
}
```

Run `go test ./internal/armada`; expected PASS before adding `executor_test.go`.

- [ ] **Step 2: Write four failing executor tests with fakes**

Create these exact tests:

```go
func TestLifecycleExecutorOnlineSavesContextBeforeLogin(t *testing.T)
func TestLifecycleExecutorOnlineParameterFailurePublishesNeedReauth(t *testing.T)
func TestLifecycleExecutorOnlineCallbackAlreadyPublishedDoesNotDuplicate(t *testing.T)
func TestLifecycleExecutorOfflineAlreadyStoppedPublishesOffline(t *testing.T)
```

In the first test, append `"save"` in the fake context store and `"login"` in the fake login function, then assert:

```go
if diff := cmp.Diff([]string{"save", "login"}, calls); diff != "" { t.Fatal(diff) }
if got.SixData != "919000000001,static-pub,static-pri,identity-pub,identity-pri,phone-id" { t.Fatalf("sixdata = %q", got.SixData) }
if got.Socks5 != "socks5://proxy.example:1080" { t.Fatalf("socks5 = %q", got.Socks5) }
if !got.IsBusiness { t.Fatal("is_business = false") }
```

The other tests assert, respectively: one synthetic event whose target is `NEED_REAUTH` and a nil handler error; zero new events when `CommandPublished` returns true; and one `OFFLINE` event plus a nil error when logout returns `账号919000000001不存在或已下线`.

The target injectable APIs are:

```go
type SixLoginFunc func(*dto.SixLoginDto) vo.Resp
type LogoutFunc func(string, string) vo.Resp
```

The online test must record call order and assert `Save` occurs before the login function.

- [ ] **Step 3: Run and verify RED**

```bash
go test ./internal/armada -run TestLifecycleExecutor
```

Expected: tests compile, then FAIL with `armada lifecycle executor not implemented`; fake call lists remain empty.

- [ ] **Step 4: Implement explicit deterministic/transient classification**

Use:

```go
type ContextStore interface {
    Save(context.Context, CommandContext) error
    FindByProtocolAccountID(context.Context, string) (*CommandContext, error)
    FindByPhone(context.Context, string) (*CommandContext, error)
}

type StateEventService interface {
    Publish(context.Context, CommandContext, external.WhatsAppEventPayload) error
    CommandPublished(context.Context, string) (bool, error)
}
```

Online response handling is:

```go
response := executor.SixLogin(loginDTO)
if response.Code == vo.SuccessCode {
    return nil
}
published, err := executor.Events.CommandPublished(ctx, command.CommandID)
if err != nil {
    return err
}
if published {
    return nil
}
if response.Code == vo.ParameterErrorCode {
    return executor.Events.Publish(ctx, commandContext, external.WhatsAppEventPayload{
        Code: response.Code,
        ReasonCode: response.Code,
        EventType: external.EventLoginFailed,
        Message: fmt.Sprint(response.Msg),
    })
}
return fmt.Errorf("zhuan online transient failure commandId=%s code=%d", command.CommandID, response.Code)
```

Offline treats success or a message containing `不存在或已下线` as `OFFLINE`; other failures remain retryable.

- [ ] **Step 5: Run and verify GREEN**

```bash
go test ./internal/armada -run TestLifecycleExecutor
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add internal/armada/executor.go internal/armada/executor_test.go
git commit -m "feat(armada): execute zhuan lifecycle commands"
```

## Task 9: Bridge Zhuan callbacks to publish-once Armada events

**Files:**
- Create: `whatsapp-server-feature-android-zhuan/internal/external/art_test.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/external/art.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/callback_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/callback.go`

- [ ] **Step 1: Add compile-only observer scaffolding**

Add the final callback type and no-op functions to `art.go`:

```go
type EventObserver func(string, *WhatsAppEventPayload)

func RegisterEventObserver(EventObserver) func() { return func() {} }
func notifyEventObservers(string, *WhatsAppEventPayload) {}
```

Run `go test ./internal/external`; expected PASS before adding `art_test.go`.

- [ ] **Step 2: Write a failing observer registry test**

```go
func TestEventObserverReceivesEventAndCanUnregister(t *testing.T) {
    calls := 0
    unregister := RegisterEventObserver(func(string, *WhatsAppEventPayload) { calls++ })
    notifyEventObservers("919000000001", &WhatsAppEventPayload{Code: 200})
    unregister()
    notifyEventObservers("919000000001", &WhatsAppEventPayload{Code: 101})
    if calls != 1 { t.Fatalf("calls = %d", calls) }
}
```

- [ ] **Step 3: Run and verify RED**

```bash
go test ./internal/external -run TestEventObserverReceivesEventAndCanUnregister
```

Expected: test compiles, then FAIL with `calls = 0`.

- [ ] **Step 4: Implement synchronous internal observation**

Add a mutex-protected observer registry. At the start of `AsyncCallBackEvent`, call:

```go
notifyEventObservers(username, event)
```

before launching the existing HTTP callback goroutine. Notify a copied observer slice and recover each observer panic independently.

- [ ] **Step 5: Run and verify GREEN**

```bash
go test ./internal/external -run TestEventObserverReceivesEventAndCanUnregister
```

Expected: PASS.

- [ ] **Step 6: Add compile-only callback publishing scaffolding**

Create `callback.go` with:

```go
var errCallbackPublishingNotImplemented = errors.New("armada callback publishing not implemented")

type AccountEventWriter interface {
    Publish(context.Context, string, EventEnvelope) error
}

type StateEventPublisher struct {
    Guard     *RedisPublishOnceGuard
    Publisher AccountEventWriter
    WorkerID  string
    Now       func() time.Time
}

func (s *StateEventPublisher) Publish(context.Context, CommandContext, external.WhatsAppEventPayload) error {
    return errCallbackPublishingNotImplemented
}

func (s *StateEventPublisher) CommandPublished(context.Context, string) (bool, error) {
    return false, errCallbackPublishingNotImplemented
}

type CallbackObserver struct {
    Contexts ContextStore
    Events   StateEventService
}

func (o *CallbackObserver) HandleAndroidEvent(context.Context, string, *external.WhatsAppEventPayload) error {
    return errCallbackPublishingNotImplemented
}
```

Run `go test ./internal/armada`; expected PASS before adding `callback_test.go`.

- [ ] **Step 7: Write failing callback and publish-once tests**

Create these exact tests:

```go
func TestCallbackObserverPublishesOnlineWithArmadaContext(t *testing.T)
func TestCallbackObserverIgnoresEventWithoutArmadaContext(t *testing.T)
func TestStateEventPublisherSuppressesDuplicateTarget(t *testing.T)
func TestStateEventPublisherReleasesClaimAfterPublishFailure(t *testing.T)
```

For the first test, save a context indexed by phone, call `HandleAndroidEvent` with `200/loginSuccess`, and assert exactly one `ONLINE` event contains the original tenant, account, and protocol-account IDs. Invoke the same callback again in the duplicate test and assert the writer count stays at one. In the failure test, make the first writer call fail, assert the claim is released, then make the second call succeed and assert one event is published.

- [ ] **Step 8: Run and verify RED**

```bash
go test ./internal/armada -run TestCallbackObserver
```

Expected: tests compile, then FAIL with `armada callback publishing not implemented`; writer count remains zero.

- [ ] **Step 9: Implement the concrete state service and callback adapter**

Add the implementation used by both the executor and callback path:

```go
type StateEventPublisher struct {
    Guard     *RedisPublishOnceGuard
    Publisher AccountEventWriter
    WorkerID  string
    Now       func() time.Time
}

func (s *StateEventPublisher) Publish(ctx context.Context, command CommandContext, payload external.WhatsAppEventPayload) error
func (s *StateEventPublisher) CommandPublished(ctx context.Context, commandID string) (bool, error)
```

`Publish` builds the Java-compatible envelope, claims `commandId+targetState`, suppresses an existing claim, publishes the event, releases the claim on writer failure, and calls `MarkCommandPublished` after success. `CommandPublished` delegates to the Redis guard. This type must satisfy the `StateEventService` interface defined in Task 8.

`CallbackObserver.HandleAndroidEvent` finds context by `event.QrID`, falls back to username/phone, ignores missing Armada context, and delegates to `StateEventPublisher.Publish`.

- [ ] **Step 10: Run and verify GREEN**

```bash
go test ./internal/armada -run TestCallbackObserver
go test ./internal/external
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add internal/external/art.go internal/external/art_test.go internal/armada/callback.go internal/armada/callback_test.go
git commit -m "feat(armada): bridge zhuan lifecycle callbacks"
```

## Task 10: Add Kafka event publishing and reliable command consumption

**Files:**
- Modify: `whatsapp-server-feature-android-zhuan/go.mod`
- Modify: `whatsapp-server-feature-android-zhuan/go.sum`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/kafka.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/client_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/client.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/consumer_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/consumer.go`

- [ ] **Step 1: Add Kafka dependency**

```bash
go get github.com/segmentio/kafka-go@v0.4.49
```

- [ ] **Step 2: Add compile-only transport scaffolding**

Create the final transport data types and interfaces:

```go
type KafkaMessage struct { Topic string; Key, Value []byte }
type CommandMessage struct { ID string; Key, Value []byte }
type CommandHandler func(context.Context, []byte) error

type MessageWriter interface {
    Write(context.Context, KafkaMessage) error
    Close() error
}

type CommandReader interface {
    Fetch(context.Context) (CommandMessage, error)
    Commit(context.Context, CommandMessage) error
    Close() error
}

type AccountEventPublisher struct {
    Topic  string
    Writer MessageWriter
}

type CommandConsumer struct {
    Reader     CommandReader
    Handler    CommandHandler
    RetryDelay time.Duration
}
```

Add `AccountEventPublisher.Publish` and `CommandConsumer.HandleNext` with their final signatures, returning `errKafkaBehaviorNotImplemented`. Confirm `var _ AccountEventWriter = (*AccountEventPublisher)(nil)`, then run `go test ./internal/armada`; expected PASS before transport tests are added.

- [ ] **Step 3: Write failing transport tests**

Create these exact tests:

```go
func TestEventPublisherWritesExpectedTopicKeyAndEnvelope(t *testing.T)
func TestCommandConsumerCommitsAfterHandlerSuccess(t *testing.T)
func TestCommandConsumerRetriesSameMessageBeforeNextFetch(t *testing.T)
func TestCommandConsumerRetriesCommitWithoutRehandling(t *testing.T)
```

The publisher test injects a recording writer and asserts topic `protocol.account.events.v1`, key `acc_919000000001`, and JSON event `account.state_changed`.

For `TestCommandConsumerRetriesSameMessageBeforeNextFetch`, configure the handler to return `errors.New("temporary")` once and nil on its second call; set retry delay to zero; then assert `Fetch` was called once, the same message ID reached the handler twice, and `Commit` was called once. For `TestCommandConsumerRetriesCommitWithoutRehandling`, make `Commit` fail once and then succeed; assert one fetch, one handler call, and two commit calls.

- [ ] **Step 4: Run and verify RED**

```bash
go test ./internal/armada -run 'TestEventPublisher|TestCommandConsumer'
```

Expected: tests compile, then FAIL with `armada kafka behavior not implemented`; writer/fetch/commit counters remain zero.

- [ ] **Step 5: Implement testable Kafka adapters**

```go
type MessageWriter interface {
    Write(context.Context, KafkaMessage) error
    Close() error
}

type CommandReader interface {
    Fetch(context.Context) (CommandMessage, error)
    Commit(context.Context, CommandMessage) error
    Close() error
}
```

`HandleNext` fetches one message, retries that message until handler success, then retries its commit until success. It must not fetch another message first. TLS helpers require TLS 1.2 or newer.

- [ ] **Step 6: Run and verify GREEN**

```bash
go test ./internal/armada -run 'TestEventPublisher|TestCommandConsumer|TestKafka'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add go.mod go.sum internal/armada/kafka.go internal/armada/client.go internal/armada/client_test.go internal/armada/consumer.go internal/armada/consumer_test.go
git commit -m "feat(armada): add reliable kafka lifecycle transport"
```

## Task 11: Configure and start four ordered Kafka consumers

**Files:**
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/options_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/options.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/config.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/start_test.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/start.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/configs/configs.go`
- Modify: `whatsapp-server-feature-android-zhuan/configs/prod_configs_example.toml`
- Modify: `whatsapp-server-feature-android-zhuan/deploy/configs/prod_configs.example.toml`
- Modify: `whatsapp-server-feature-android-zhuan/cmd/server/main.go`

- [ ] **Step 1: Add compile-only option/start scaffolding**

Create `Options` with the final fields (`Enabled`, `Brokers`, both topics, group, worker ID, context TTL, security protocol, and concurrency), plus the `ConsumerRunner`/`ConsumerFactory` types shown in Step 6. Add these signatures with bodies that return `errStartupNotImplemented`:

```go
type StopFunc func(context.Context) error

func NormalizeOptions(Options) (Options, error)
func OptionsFromConfig(*configs.Config) (Options, error)
func buildConsumerRunners(int, ConsumerFactory) ([]ConsumerRunner, error)
func startWithFactory(context.Context, Options, ConsumerFactory) (StopFunc, error)
func Start(context.Context, Options) (StopFunc, error)
```

Run `go test ./internal/armada`; expected PASS before adding option/start tests. Do not add defaults, validation, readers, or goroutines in this step.

- [ ] **Step 2: Write failing option/start tests**

```go
if got.CommandTopic != "protocol.android.commands.v1" { t.Fatalf("command topic = %q", got.CommandTopic) }
if got.ConsumerGroup != "whatsapp-server-feature-android-armada" { t.Fatalf("group = %q", got.ConsumerGroup) }
if got.AccountEventTopic != "protocol.account.events.v1" { t.Fatalf("event topic = %q", got.AccountEventTopic) }
if got.Concurrency != 4 { t.Fatalf("concurrency = %d", got.Concurrency) }
if got.ContextTTL != 7*24*time.Hour { t.Fatalf("ttl = %s", got.ContextTTL) }
```

Add these exact tests:

```go
func TestNormalizeOptionsRejectsEnabledWithoutBrokers(t *testing.T)
func TestNormalizeOptionsRejectsConcurrencyBelowOne(t *testing.T)
func TestStartDisabledCreatesNoConsumers(t *testing.T)
func TestBuildConsumerRunnersCreatesConfiguredConcurrency(t *testing.T)
```

In the last test, use a recording factory and assert `buildConsumerRunners(4, factory)` creates indexes `0,1,2,3`; production supplies the same group ID to all four readers. In the disabled test, call `startWithFactory`, assert the factory call count remains zero, and assert the returned stop function succeeds.

- [ ] **Step 3: Run and verify RED**

```bash
go test ./internal/armada -run 'TestNormalizeOptions|TestStartDisabled|TestBuildConsumerRunners'
```

Expected: tests compile, then FAIL with `armada startup not implemented`; no consumer is created.

- [ ] **Step 4: Implement options and TOML mapping**

Add to `configs.Config`:

```go
Kafka struct {
    Enabled           bool   `toml:"enabled"`
    Brokers           string `toml:"brokers"`
    CommandTopic      string `toml:"commandtopic"`
    ConsumerGroup     string `toml:"consumergroup"`
    AccountEventTopic string `toml:"accounteventtopic"`
    WorkerID          string `toml:"workerid"`
    ContextTTLSeconds int    `toml:"contextttlseconds"`
    SecurityProtocol  string `toml:"securityprotocol"`
    Concurrency       int    `toml:"concurrency"`
} `toml:"kafka"`
```

`NormalizeOptions` defaults concurrency to `4`; enabled mode requires brokers and concurrency `>=1`.

- [ ] **Step 5: Add safe example configuration**

Append to both example TOMLs:

```toml
[kafka]
enabled = false
brokers = ""
commandtopic = "protocol.android.commands.v1"
consumergroup = "whatsapp-server-feature-android-armada"
accounteventtopic = "protocol.account.events.v1"
workerid = "whatsapp-server-feature-android-zhuan"
contextttlseconds = 604800
securityprotocol = "PLAINTEXT"
concurrency = 4
```

- [ ] **Step 6: Implement composition and startup**

`Start` must return a stop function and, when enabled:

1. obtain initialized Redis;
2. construct context store, guard, event publisher, state service, callback observer, and executor;
3. register the callback observer;
4. start exactly `Concurrency` readers with the same consumer group;
5. stop by unregistering, cancelling, closing readers, and closing the publisher.

Keep fan-out testable through:

```go
type ConsumerRunner interface {
    Run(context.Context) error
    Close() error
}

type ConsumerFactory func(index int) (ConsumerRunner, error)

func buildConsumerRunners(concurrency int, factory ConsumerFactory) ([]ConsumerRunner, error)
```

In `cmd/server/main.go`, after `common.InitDatabase()`:

```go
armadaOptions, err := armada.OptionsFromConfig(configs.Get())
common.HandleError(err, "解析 Armada Kafka 配置失败")
stopArmada, err := armada.Start(context.Background(), armadaOptions)
common.HandleError(err, "启动 Armada Kafka consumer 失败")
defer stopArmada(context.Background())
```

- [ ] **Step 7: Run and verify GREEN**

```bash
go test ./internal/armada ./internal/configs ./cmd/server
```

Expected: PASS. Disabled mode creates no Kafka readers.

- [ ] **Step 8: Format, run all Zhuan tests, and build**

```bash
gofmt -w internal/armada/*.go internal/external/type.go internal/external/type_test.go internal/external/art.go internal/external/art_test.go internal/service/app/waapp.go internal/configs/configs.go cmd/server/main.go
go test ./...
go build ./cmd/server
```

Expected: all tests PASS and build succeeds.

- [ ] **Step 9: Commit**

```bash
git add internal/armada internal/configs/configs.go configs/prod_configs_example.toml deploy/configs/prod_configs.example.toml cmd/server/main.go
git commit -m "feat(armada): start zhuan lifecycle adapter"
```

## Task 12: Add the guarded cutover runbook

**Files:**
- Create: `docs/operations/android-zhuan-lifecycle-cutover.md`

- [ ] **Step 1: Write the runbook with exact gates**

```markdown
# Android Zhuan Lifecycle Cutover

1. Confirm target environment, tenant scope, and operator.
2. Stop `whatsapp-server-feature-android`; do not start Zhuan consumption yet.
3. Verify no old Android consumer process remains.
4. Inventory active rows where `protocol_id = 'ANDROID'`; save returned account IDs as the immutable deletion list.
5. Inventory pending/retry outbox rows for only those account IDs and Android backend.
6. Report both counts and wait for explicit deletion approval.
7. Delete or soft-delete only the immutable account-ID list through the approved environment-specific operation; release proxy bindings and terminate matching pending outbox rows.
8. Verify old IDs are no longer active and no pending Android commands remain for them.
9. Deploy Zhuan with Kafka disabled; verify MySQL, Redis, HTTP health, and configuration redaction.
10. Enable Zhuan Kafka with the existing consumer group only after the old consumer is stopped.
11. Re-import a small batch using `phone,staticPub,staticPri,identityPub,identityPri,phoneId`.
12. Verify batch online, ONLINE callbacks, batch offline, and OFFLINE callbacks before increasing batch size.
```

State explicitly that the runbook does not authorize SSH, deployment, or deletion; each requires separate target-environment confirmation.

- [ ] **Step 2: Verify no credentials or executable destructive SQL**

```bash
rg -n '(BEGIN;|DELETE FROM|UPDATE account|staticPriKey=|password=)' docs/operations/android-zhuan-lifecycle-cutover.md
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add docs/operations/android-zhuan-lifecycle-cutover.md
git commit -m "docs: add android zhuan cutover gates"
```

## Task 13: Final cross-repository verification

**Files:**
- Verify all files changed in Tasks 2–12.

- [ ] **Step 1: Run focused Armada tests**

Run from `armada/armada-api`:

```bash
mvn -Dtest=AccountImportParserTest,AccountOnlineCommandServiceImplTest,ProtocolCommandOutboxServiceImplTest,ProtocolCommandPublisherTest test
```

Expected: PASS.

- [ ] **Step 2: Run the full Armada module suite**

```bash
mvn test
```

Expected: PASS. Reproduce any suspected pre-existing failure against the isolated worktree base before attributing it to this feature.

- [ ] **Step 3: Run all Zhuan tests and build**

Run from `whatsapp-server-feature-android-zhuan`:

```bash
gofmt -w internal/armada/*.go internal/external/art.go internal/external/art_test.go internal/external/type.go internal/external/type_test.go internal/configs/configs.go internal/service/app/waapp.go cmd/server/main.go
go test ./...
go build ./cmd/server
```

Expected: clean formatting, all tests PASS, build succeeds.

- [ ] **Step 4: Run credential-leak and repository gates**

From Armada:

```bash
git diff --check
git status --short
```

Expected: no diff errors and only intentional feature changes in the isolated worktree.

From Zhuan:

```bash
git diff --check
git status --short
git ls-files | rg '(^|/)(dev_configs\.toml|prod_configs\.toml|\.env|[^/]+\.(pem|key|log|zip))$'
```

Expected: no diff errors, clean status after commits, and no private/generated file paths.

- [ ] **Step 5: Review commit boundaries**

Run this command once from Armada and once from Zhuan:

```bash
git log --oneline --decorate -12
```

Expected in Armada: separate credential-order, business-metadata, Kafka-payload, and runbook commits. Expected in Zhuan: baseline plus focused command/context/event/executor/callback/transport/startup commits.

- [ ] **Step 6: Stop before remote mutation**

Do not SSH, deploy, delete historical accounts, alter Kafka topics/groups, or run a real WhatsApp login in this implementation session. Report local verification and request explicit target-environment approval for the cutover runbook.
