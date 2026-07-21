# Android Group Creation Marketing Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Armada route the complete group-creation-marketing flow per account to Web/Baileys or Android Zhuan, including contact pre-save, group creation, announcement-only setting, member snapshot, marketing send, and existing account-retry behavior.

**Architecture:** Replace protocol-account-id-only calls with commands carrying `ProtocolAccountRef`, then route each capability through a `Routing*Port` to one Web or Android backend. Keep synchronous group operations on HTTP, retain the existing Kafka/outbox message path, and preserve best-effort behavior for contact pre-save, announcement-only setting, and member snapshots.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring `RestClient`, JUnit 5, AssertJ, Mockito, Maven.

---

## Execution preflight

- Execute from an isolated worktree created with the `using-git-worktrees` skill because the main checkout contains unrelated in-progress changes.
- Commit this plan, then base the worktree on that branch tip; verify it descends from `fb543ed`, which contains the approved design.
- Read `AGENTS.md`, `.harness/agents/owner.md`, `.harness/rules/编码规范.md`, `.harness/rules/工程结构.md`, and `.harness/rules/开发流程规范.md` inside the worktree before editing.
- Do not modify `whatsapp-server-feature-android-zhuan`; its existing HTTP routes are dependencies of this plan.
- Use `apply_patch` for edits and re-read a file immediately before changing it.

## File map

**Unified commands**

- Create `armada-api/src/main/java/com/armada/platform/protocol/model/command/GroupCreateCommand.java` — account-aware group creation request.
- Create `armada-api/src/main/java/com/armada/platform/protocol/model/command/ContactSaveCommand.java` — account-aware contact save request.
- Create `armada-api/src/main/java/com/armada/platform/protocol/model/command/GroupMemberListQuery.java` — account-aware member snapshot query.

**Routing contracts**

- Modify `armada-api/src/main/java/com/armada/platform/protocol/port/GroupCreatePort.java`.
- Modify `armada-api/src/main/java/com/armada/platform/protocol/port/ContactPort.java`.
- Create `armada-api/src/main/java/com/armada/platform/protocol/port/GroupMemberListPort.java`.
- Modify `armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java` to keep mutation only.
- Create `GroupCreateBackend`, `ContactBackend`, `GroupMemberListBackend` and the three matching `Routing*Port` classes under `platform/protocol/routing`.

**Web backends**

- Modify `HttpGroupCreateAdapter` and `HttpContactAdapter` to implement their backend contracts.
- Create `HttpGroupMemberListAdapter` by extracting the read-only HTTP behavior from `HttpGroupParticipantAdapter`.

**Android HTTP and adapters**

- Extend `AndroidNativeClient` and `HttpAndroidNativeClient` with contact, create-group, and group-announcement calls.
- Create `AndroidGroupMemberMapper`, `AndroidGroupCreateResponseMapper`, and `AndroidGroupOperationErrorMapper`.
- Create `AndroidNativeContactAdapter`, `AndroidNativeGroupCreateAdapter`, and `AndroidNativeGroupMemberListAdapter`.
- Modify `AndroidGroupMembershipVerifier` to reuse the member mapper.

**Business integration and configuration**

- Modify `ProtocolConfiguration`, `GroupCreationMarketingWorker`, `GroupOperationServiceImpl`, and `HistoricalGroupPullWorkerImpl`.
- Update focused tests beside each changed component.
- Create `.harness/changes/2026-07-21-android-group-creation-marketing-routing.md` and keep verification evidence current.

---

### Task 1: Add account-aware group-create routing and migrate existing Web callers

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/GroupCreateCommand.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupCreateBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupCreatePort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupCreatePort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupCreateAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupOperationServiceImpl.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupCreatePortTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupCreateAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupOperationServiceImplTest.java`

- [ ] **Step 1: Write all failing group-create migration tests**

Create `RoutingGroupCreatePortTest` with these exact behaviors:

```java
class RoutingGroupCreatePortTest {

    @Test
    void routesOnlyToBackendSelectedByAccount() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingGroupCreatePort port = new RoutingGroupCreatePort(List.of(web, android));
        GroupCreateCommand command = command(ProtocolBackend.ANDROID);

        GroupCreateResult result = port.create(command);

        assertThat(result.groupJid()).isEqualTo("120363created@g.us");
        assertThat(web.lastCommand).isNull();
        assertThat(android.lastCommand).isSameAs(command);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingGroupCreatePort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingGroupCreatePort port = new RoutingGroupCreatePort(List.of(web));
        assertThatThrownBy(() -> port.create(command(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.create");
                });
    }

    private static GroupCreateCommand command(ProtocolBackend backend) {
        return new GroupCreateCommand(
                new ProtocolAccountRef(7L, backend, "acc_7", "919000000001"),
                "活动群-1",
                List.of("919000000002"),
                true,
                "group-creation-marketing-item:11");
    }

    private static final class RecordingBackend implements GroupCreateBackend {
        private final ProtocolBackend backend;
        private GroupCreateCommand lastCommand;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public GroupCreateResult create(GroupCreateCommand command) {
            lastCommand = command;
            return new GroupCreateResult("120363created@g.us", false, List.of());
        }
    }
}
```

Before running tests, update `HttpGroupCreateAdapterTest` to call a `GroupCreateBackend` with `GroupCreateCommand`. Update `GroupCreationMarketingWorkerTest` and `GroupOperationServiceImplTest` Mockito stubs to capture `GroupCreateCommand`. These test edits happen before any production signature changes. The direct Android assertion is:

```java
ArgumentCaptor<GroupCreateCommand> command = ArgumentCaptor.forClass(GroupCreateCommand.class);
verify(groupCreatePort).create(command.capture());
assertThat(command.getValue().account().backend()).isEqualTo(ProtocolBackend.ANDROID);
assertThat(command.getValue().account().wsPhone()).isEqualTo("919000000001");
```

- [ ] **Step 2: Run the new test and verify RED**

Run:

```bash
cd armada-api
mvn -Dtest=RoutingGroupCreatePortTest test
```

Expected: test compilation fails because `GroupCreateCommand`, `GroupCreateBackend`, and `RoutingGroupCreatePort` do not exist.

- [ ] **Step 3: Add the command and routing contracts**

Create `GroupCreateCommand`:

```java
public record GroupCreateCommand(
        ProtocolAccountRef account,
        String subject,
        List<String> participants,
        boolean announceOnly,
        String operationId) {

    public GroupCreateCommand {
        account = Objects.requireNonNull(account, "account 不能为空");
        subject = requireText(subject, "subject");
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("participants 不能为空");
        }
        participants = participants.stream()
                .map(value -> requireText(value, "participant"))
                .toList();
        operationId = requireText(operationId, "operationId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
```

Replace `GroupCreatePort` with:

```java
public interface GroupCreatePort {

    /**
     * 使用命令中的账号协议事实创建 WhatsApp 群。
     *
     * @param command 账号、群名称、初始成员和操作标识
     * @return 统一建群结果
     */
    GroupCreateResult create(GroupCreateCommand command);
}
```

Create `GroupCreateBackend`:

```java
public interface GroupCreateBackend {
    ProtocolBackend backend();
    GroupCreateResult create(GroupCreateCommand command);
}
```

Create `RoutingGroupCreatePort` with explicit duplicate-registration rejection:

```java
public final class RoutingGroupCreatePort implements GroupCreatePort {
    private static final String OPERATION = "group.create";
    private final Map<ProtocolBackend, GroupCreateBackend> backends;

    public RoutingGroupCreatePort(List<GroupCreateBackend> implementations) {
        EnumMap<ProtocolBackend, GroupCreateBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupCreateBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                GroupCreateBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的建群协议后端 backend=" + implementation.backend());
                }
            }
        }
        backends = Map.copyOf(resolved);
    }

    @Override
    public GroupCreateResult create(GroupCreateCommand command) {
        GroupCreateBackend implementation = backends.get(command.account().backend());
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "建群协议后端未注册 backend=" + command.account().backend())
                    .withContext(command.account().backend(), OPERATION, command.operationId());
        }
        return implementation.create(command);
    }
}
```

- [ ] **Step 4: Convert the Web adapter and Spring bean to routed Web-only behavior**

Change `HttpGroupCreateAdapter` to implement `GroupCreateBackend`, add:

```java
@Override
public ProtocolBackend backend() {
    return ProtocolBackend.WEB;
}
```

Replace its create method with:

```java
@Override
public GroupCreateResult create(GroupCreateCommand command) {
    String accountId = requireText(command.account().protocolAccountId(), "protocolAccountId");
    List<String> participantJids = normalizeParticipants(command.participants());
    CreateResponse response = httpExecutor.postTyped(
            CREATE_URI,
            new CreateRequest(accountId, command.subject(), participantJids, command.announceOnly()),
            CreateResponse.class);
    ResultsResponse results = response.results() == null
            ? new ResultsResponse(response.groupJid(), false, List.of())
            : response.results();
    return new GroupCreateResult(
            response.groupJid(),
            results.partial(),
            results.results() == null ? List.of() : results.results().stream()
                    .map(item -> new GroupCreateParticipantResult(
                            item.jid(), item.status(), item.rawStatus()))
                    .toList());
}
```

Replace the old `groupCreatePort` bean with:

```java
@Bean
public GroupCreateBackend webGroupCreateBackend(ProtocolHttpExecutorRegistry registry) {
    return new HttpGroupCreateAdapter(registry.required(ProtocolBackend.WEB));
}

@Bean
public GroupCreatePort groupCreatePort(List<GroupCreateBackend> backends) {
    return new RoutingGroupCreatePort(backends);
}
```

- [ ] **Step 5: Migrate Worker and direct-create callers**

In `GroupCreationMarketingWorker`, build the account reference once after claim:

```java
ProtocolAccountRef accountRef = protocolAccountRef(account);
```

Call group creation with:

```java
groupResult = groupCreatePort.create(new GroupCreateCommand(
        accountRef,
        item.getGroupSubject(),
        participants,
        true,
        "group-creation-marketing-item:" + item.getId()));
```

Add this helper:

```java
private static ProtocolAccountRef protocolAccountRef(
        GroupCreationMarketingAccountCandidate account) {
    return new ProtocolAccountRef(
            account.getAccountId(),
            ProtocolBackend.fromProtocolId(account.getProtocolId()),
            account.getProtocolAccountId(),
            account.getAccountPhone());
}
```

In `GroupOperationServiceImpl`, replace `resolveOnlineProtocolAccountId` with `resolveOnlineProtocolAccount`, returning:

```java
return new ProtocolAccountRef(
        account.getId(),
        ProtocolBackend.fromProtocolId(account.getProtocolId()),
        account.getProtocolAccountId(),
        account.getWsPhone());
```

Add `java.util.UUID` and call the port with:

```java
GroupCreateCommand command = new GroupCreateCommand(
        account,
        subject,
        participants,
        false,
        "group-create-api:" + UUID.randomUUID());
result = groupCreatePort.create(command);
```

- [ ] **Step 6: Verify Web, Worker, and Service tests are GREEN**

The Web test written in Step 1 uses:

```java
GroupCreateCommand command = new GroupCreateCommand(
        new ProtocolAccountRef(7L, ProtocolBackend.WEB, "acc_861111", "861111"),
        "测试群",
        List.of("+86 139-0000-0000", "8613911111111@s.whatsapp.net"),
        true,
        "test:create");
GroupCreateResult result = backend.create(command);
```

The Worker and Service tests written in Step 1 capture or match `GroupCreateCommand`. The direct-create Android assertion is:

```java
ArgumentCaptor<GroupCreateCommand> command = ArgumentCaptor.forClass(GroupCreateCommand.class);
verify(groupCreatePort).create(command.capture());
assertThat(command.getValue().account().backend()).isEqualTo(ProtocolBackend.ANDROID);
assertThat(command.getValue().account().wsPhone()).isEqualTo("919000000001");
```

Run:

```bash
mvn -Dtest=RoutingGroupCreatePortTest,HttpGroupCreateAdapterTest,GroupOperationServiceImplTest,GroupCreationMarketingWorkerTest test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit Task 1**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/GroupCreateCommand.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/GroupCreateBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupCreatePort.java \
  armada-api/src/main/java/com/armada/platform/protocol/port/GroupCreatePort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupCreateAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupOperationServiceImpl.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupCreatePortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupCreateAdapterTest.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java \
  armada-api/src/test/java/com/armada/group/service/GroupOperationServiceImplTest.java
git commit -m "refactor: route group creation by protocol backend"
```

---

### Task 2: Add account-aware contact-save routing

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ContactSaveCommand.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/ContactBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingContactPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/ContactPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/contact/HttpContactAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImpl.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingContactPortTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/contact/HttpContactAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImplTest.java`

- [ ] **Step 1: Write all failing contact-routing and caller tests**

Use this command factory and assert Android routing, duplicate registration, and missing backend:

```java
private static ContactSaveCommand command(ProtocolBackend backend) {
    return new ContactSaveCommand(
            new ProtocolAccountRef(7L, backend, "acc_7", "919000000001"),
            "919000000002",
            "919000000002",
            "group-creation-marketing-item:11");
}
```

The recording backend must implement:

```java
public interface ContactBackend {
    ProtocolBackend backend();
    void save(ContactSaveCommand command);
}
```

Before production edits, update `HttpContactAdapterTest` to call `backend.save(new ContactSaveCommand(...))`. Update Worker and historical-pull tests to capture `ContactSaveCommand`, including Android account facts and the existing non-blocking contact-failure case.

```java
ArgumentCaptor<ContactSaveCommand> command = ArgumentCaptor.forClass(ContactSaveCommand.class);
verify(contactPort, timeout(500).atLeastOnce()).save(command.capture());
assertThat(command.getAllValues()).allSatisfy(value -> {
    assertThat(value.account().backend()).isEqualTo(ProtocolBackend.ANDROID);
    assertThat(value.account().wsPhone()).isEqualTo("919000000001");
});
assertThat(command.getAllValues()).extracting(ContactSaveCommand::contact)
        .contains("919000000002");
```

Run:

```bash
mvn -Dtest=RoutingContactPortTest test
```

Expected: compilation fails because the new command and routing types do not exist.

- [ ] **Step 2: Implement contact command, Port, backend, and routing**

Create `ContactSaveCommand`:

```java
public record ContactSaveCommand(
        ProtocolAccountRef account,
        String contact,
        String name,
        String operationId) {

    public ContactSaveCommand {
        account = Objects.requireNonNull(account, "account 不能为空");
        contact = requireText(contact, "contact");
        name = requireText(name, "name");
        operationId = requireText(operationId, "operationId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
```

Replace `ContactPort` with:

```java
public interface ContactPort {
    void save(ContactSaveCommand command);
}
```

Create `RoutingContactPort`:

```java
public final class RoutingContactPort implements ContactPort {
    private static final String OPERATION = "contact.save";
    private final Map<ProtocolBackend, ContactBackend> backends;

    public RoutingContactPort(List<ContactBackend> implementations) {
        EnumMap<ProtocolBackend, ContactBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (ContactBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                ContactBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的联系人协议后端 backend=" + implementation.backend());
                }
            }
        }
        backends = Map.copyOf(resolved);
    }

    @Override
    public void save(ContactSaveCommand command) {
        ContactBackend implementation = backends.get(command.account().backend());
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "联系人协议后端未注册 backend=" + command.account().backend())
                    .withContext(
                            command.account().backend(), OPERATION, command.operationId());
        }
        implementation.save(command);
    }
}
```

- [ ] **Step 3: Convert the Web contact adapter and configuration**

Change `HttpContactAdapter` to implement `ContactBackend`, return `ProtocolBackend.WEB`, and replace its method with:

```java
@Override
public void save(ContactSaveCommand command) {
    String accountId = requireText(command.account().protocolAccountId(), "protocolAccountId");
    String jid = WhatsappJids.userJid(command.contact());
    String displayName = displayName(command.name(), jid);
    httpExecutor.postVoid(
            SAVE_URI_TEMPLATE.formatted(jid),
            new SaveContactRequest(accountId, new ContactBody(displayName)));
}
```

Replace the old bean with:

```java
@Bean
public ContactBackend webContactBackend(ProtocolHttpExecutorRegistry registry) {
    return new HttpContactAdapter(registry.required(ProtocolBackend.WEB));
}

@Bean
public ContactPort contactPort(List<ContactBackend> backends) {
    return new RoutingContactPort(backends);
}
```

- [ ] **Step 4: Migrate Worker and historical Web-only caller**

In `GroupCreationMarketingWorker`, pass `ProtocolAccountRef` and a stable item operation ID through `preSaveContacts` and `submitContactPreSave`, then call:

```java
contactPort.save(new ContactSaveCommand(
        account,
        participant,
        participant,
        operationId));
```

Use `operationId = "group-creation-marketing-item:" + item.getId()`; do not include the participant phone in the operation ID or logs.

In `HistoricalGroupPullWorkerImpl`, change `processContacts` to receive the existing `ProtocolAccountRef puller` and call:

```java
protocolPorts.contact().save(new ContactSaveCommand(
        puller,
        member.getPhone(),
        member.getPhone(),
        "historical-group-pull-member:" + member.getId()));
```

The existing `puller.backend() != WEB` rejection remains unchanged.

- [ ] **Step 5: Verify contact tests are GREEN**

Run:

```bash
mvn -Dtest=RoutingContactPortTest,HttpContactAdapterTest,GroupCreationMarketingWorkerTest,HistoricalGroupPullWorkerImplTest test
```

Expected: all selected tests pass, including non-blocking contact failure coverage.

- [ ] **Step 6: Commit Task 2**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ContactSaveCommand.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/ContactBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingContactPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/port/ContactPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/contact/HttpContactAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImpl.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingContactPortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/contact/HttpContactAdapterTest.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java \
  armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImplTest.java
git commit -m "refactor: route contact save by protocol backend"
```

---

### Task 3: Split and route read-only group-member listing

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/GroupMemberListQuery.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupMemberListPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupMemberListBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupMemberListPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMemberListAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupMemberListPortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMemberListAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: Write all failing read-routing, Web-contract, and Worker tests**

Use this query in `RoutingGroupMemberListPortTest`:

```java
new GroupMemberListQuery(
        new ProtocolAccountRef(7L, ProtocolBackend.ANDROID, "acc_7", "919000000001"),
        "120363created@g.us",
        "group-creation-marketing-item:11")
```

Assert Android-only routing plus duplicate and missing registrations. In `HttpGroupMemberListAdapterTest`, assert the existing Web request remains:

```text
GET /v1/groups/120363created@g.us/participants?accountId=acc_7
```

Before production edits, update Worker construction to mock `GroupMemberListPort`, capture `GroupMemberListQuery`, and preserve the existing assertion that member-query failure does not block marketing or populate the count.

Run:

```bash
mvn -Dtest=RoutingGroupMemberListPortTest,HttpGroupMemberListAdapterTest test
```

Expected: compilation fails because the new read capability does not exist.

- [ ] **Step 2: Implement query, Port, backend, and routing**

Create `GroupMemberListQuery`:

```java
public record GroupMemberListQuery(
        ProtocolAccountRef account,
        String groupJid,
        String operationId) {

    public GroupMemberListQuery {
        account = Objects.requireNonNull(account, "account 不能为空");
        groupJid = requireText(groupJid, "groupJid");
        operationId = requireText(operationId, "operationId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
```

Create the Port and backend:

```java
public interface GroupMemberListPort {
    List<GroupParticipantResult> list(GroupMemberListQuery query);
}

public interface GroupMemberListBackend {
    ProtocolBackend backend();
    List<GroupParticipantResult> list(GroupMemberListQuery query);
}
```

Create `RoutingGroupMemberListPort`:

```java
public final class RoutingGroupMemberListPort implements GroupMemberListPort {
    private static final String OPERATION = "group.members.list";
    private final Map<ProtocolBackend, GroupMemberListBackend> backends;

    public RoutingGroupMemberListPort(List<GroupMemberListBackend> implementations) {
        EnumMap<ProtocolBackend, GroupMemberListBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupMemberListBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                GroupMemberListBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的群成员查询协议后端 backend=" + implementation.backend());
                }
            }
        }
        backends = Map.copyOf(resolved);
    }

    @Override
    public List<GroupParticipantResult> list(GroupMemberListQuery query) {
        GroupMemberListBackend implementation = backends.get(query.account().backend());
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "群成员查询协议后端未注册 backend=" + query.account().backend())
                    .withContext(
                            query.account().backend(), OPERATION, query.operationId());
        }
        return implementation.list(query);
    }
}
```

- [ ] **Step 3: Extract the Web list adapter**

Move `PARTICIPANTS_URI_TEMPLATE`, `listParticipants`, and the role/result conversion helpers from `HttpGroupParticipantAdapter` into `HttpGroupMemberListAdapter`. Its core method is:

```java
@Override
public List<GroupParticipantResult> list(GroupMemberListQuery query) {
    String accountId = query.account().protocolAccountId();
    ParticipantResponse[] response = httpExecutor.getTyped(
            PARTICIPANTS_URI_TEMPLATE.formatted(query.groupJid(), accountId),
            ParticipantResponse[].class);
    if (response == null) {
        return List.of();
    }
    return Arrays.stream(response)
            .map(HttpGroupMemberListAdapter::toResult)
            .toList();
}
```

Return `ProtocolBackend.WEB` from `backend()`. Remove `listParticipants` from `GroupParticipantPort`; `HttpGroupParticipantAdapter` retains only `updateParticipants`.

- [ ] **Step 4: Wire Web read routing and migrate Worker**

Add:

```java
@Bean
public GroupMemberListBackend webGroupMemberListBackend(
        ProtocolHttpExecutorRegistry registry) {
    return new HttpGroupMemberListAdapter(registry.required(ProtocolBackend.WEB));
}

@Bean
public GroupMemberListPort groupMemberListPort(List<GroupMemberListBackend> backends) {
    return new RoutingGroupMemberListPort(backends);
}
```

Change the Worker dependency from `GroupParticipantPort` to `GroupMemberListPort` and query with:

```java
List<GroupParticipantResult> participants = groupMemberListPort.list(
        new GroupMemberListQuery(account, groupJid, operationId));
```

- [ ] **Step 5: Verify member-list tests are GREEN**

The tests written in Step 1 move Web list assertions out of `HttpGroupParticipantAdapterTest`, capture `GroupMemberListQuery`, and preserve the existing “member query failure does not block marketing” assertion.

Run:

```bash
mvn -Dtest=RoutingGroupMemberListPortTest,HttpGroupMemberListAdapterTest,HttpGroupParticipantAdapterTest,GroupCreationMarketingWorkerTest test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit Task 3**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/GroupMemberListQuery.java \
  armada-api/src/main/java/com/armada/platform/protocol/port/GroupMemberListPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/GroupMemberListBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupMemberListPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMemberListAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupMemberListPortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMemberListAdapterTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapterTest.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java
git commit -m "refactor: route group member listing by backend"
```

---

### Task 4: Extend the Android native HTTP client contract

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClientTest.java`

- [ ] **Step 1: Add failing HTTP shape assertions**

Extend `sendsExistingAndroidNativeRequestShapes` with these expectations:

```java
server.expect(requestTo("http://android.internal/ws/v1/contacts/add/919000000001"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"Numbers\":[\"919000000002\"]}"))
        .andRespond(withSuccess("{\"Code\":0,\"Data\":[],\"Msg\":\"\"}",
                MediaType.APPLICATION_JSON));
server.expect(requestTo("http://android.internal/ws/v1/groups/create/919000000001"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("""
                {"subject":"活动群-1","participants":["919000000002@s.whatsapp.net"]}
                """))
        .andRespond(withSuccess("""
                {"Code":0,"Data":{"GroupId":"120363001","Participants":[]},"Msg":""}
                """, MediaType.APPLICATION_JSON));
server.expect(requestTo(
        "http://android.internal/ws/v1/groups/settings/sendmessage/919000000001"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("""
                {"group_id":"120363001@g.us","state":false}
                """))
        .andRespond(withSuccess("{\"Code\":0,\"Data\":\"\",\"Msg\":\"\"}",
                MediaType.APPLICATION_JSON));
```

Invoke the new methods and assert `code()==0`.

Run:

```bash
mvn -Dtest=HttpAndroidNativeClientTest test
```

Expected: compilation fails because `saveContacts`, `createGroup`, and `setGroupAnnouncement` are missing.

- [ ] **Step 2: Add interface methods**

Add to `AndroidNativeClient`:

```java
AndroidResponseEnvelope saveContacts(String wsPhone, List<String> numbers);

AndroidResponseEnvelope createGroup(
        String wsPhone,
        String subject,
        List<String> participants);

AndroidResponseEnvelope setGroupAnnouncement(
        String wsPhone,
        String groupJid,
        boolean membersCanSend);
```

- [ ] **Step 3: Implement exact Zhuan request shapes**

Add URI constants:

```java
private static final String CONTACTS_ADD_URI_PREFIX = "/ws/v1/contacts/add/";
private static final String GROUP_CREATE_URI_PREFIX = "/ws/v1/groups/create/";
private static final String GROUP_ANNOUNCEMENT_URI_PREFIX =
        "/ws/v1/groups/settings/sendmessage/";
```

Add methods:

```java
@Override
public AndroidResponseEnvelope saveContacts(String wsPhone, List<String> numbers) {
    return httpExecutor.postTyped(
            CONTACTS_ADD_URI_PREFIX + requireDigits(wsPhone),
            new ContactsRequest(requireTexts(numbers, "numbers")),
            AndroidResponseEnvelope.class);
}

@Override
public AndroidResponseEnvelope createGroup(
        String wsPhone,
        String subject,
        List<String> participants) {
    return httpExecutor.postTyped(
            GROUP_CREATE_URI_PREFIX + requireDigits(wsPhone),
            new CreateGroupRequest(
                    requireText(subject, "subject"),
                    requireTexts(participants, "participants")),
            AndroidResponseEnvelope.class);
}

@Override
public AndroidResponseEnvelope setGroupAnnouncement(
        String wsPhone,
        String groupJid,
        boolean membersCanSend) {
    return httpExecutor.postTyped(
            GROUP_ANNOUNCEMENT_URI_PREFIX + requireDigits(wsPhone),
            new AnnouncementRequest(requireText(groupJid, GROUP_JID_FIELD), membersCanSend),
            AndroidResponseEnvelope.class);
}
```

Use these records:

```java
private record ContactsRequest(@JsonProperty("Numbers") List<String> numbers) {
}

private record CreateGroupRequest(String subject, List<String> participants) {
}

private record AnnouncementRequest(
        @JsonProperty("group_id") String groupId,
        @JsonProperty("state") boolean membersCanSend) {
}
```

Add this list validator so null, empty, and blank entries cannot reach the HTTP layer:

```java
private static List<String> requireTexts(List<String> values, String field) {
    if (values == null || values.isEmpty()) {
        throw new IllegalArgumentException(field + " 不能为空");
    }
    return values.stream()
            .map(value -> requireText(value, field + " item"))
            .toList();
}
```

- [ ] **Step 4: Verify GREEN and validation coverage**

Add blank-list and nonnumeric-phone assertions, then run:

```bash
mvn -Dtest=HttpAndroidNativeClientTest test
```

Expected: all tests pass and `MockRestServiceServer.verify()` confirms every request.

- [ ] **Step 5: Commit Task 4**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClientTest.java
git commit -m "feat: add Android native group creation HTTP calls"
```

---

### Task 5: Add Android member, group-create, and error mappers

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMemberMapper.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupCreateResponseMapper.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapper.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMembershipVerifier.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupMemberMapperTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupCreateResponseMapperTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapperTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupMembershipVerifierTest.java`

- [ ] **Step 1: Write failing member-mapper tests**

Cover `phone`, `phone_number`, `phoneNumber`, and device JID fields:

```java
JsonNode data = new ObjectMapper().readTree("""
        {"Participants":[
          {"phone":"919000000001@s.whatsapp.net","type":"participant"},
          {"phone_number":"919000000002","type":"admin"},
          {"phoneNumber":"919000000003","type":"superadmin"},
          {"jid":"919000000004:12@s.whatsapp.net","type":"participant"}
        ]}
        """);

List<GroupParticipantResult> result = new AndroidGroupMemberMapper().map(data);

assertThat(result).extracting(GroupParticipantResult::phone)
        .containsExactly("919000000001", "919000000002", "919000000003", "919000000004");
assertThat(result.get(1).admin()).isTrue();
assertThat(result.get(2).owner()).isTrue();
```

Also assert malformed non-array `Participants` throws `ANDROID_RESPONSE_UNRECOGNIZED`.

- [ ] **Step 2: Write failing group-create response tests**

Assert group JID suffixing and conservative partial results:

```java
GroupCreateResult result = mapper.map(
        data("""
                {"GroupId":"120363001","Participants":[
                  {"phone":"919000000002","type":"participant"}
                ]}
                """),
        List.of("919000000002", "919000000003"));

assertThat(result.groupJid()).isEqualTo("120363001@g.us");
assertThat(result.partial()).isTrue();
assertThat(result.results()).extracting(GroupCreateParticipantResult::status)
        .containsExactly("OK", "UNKNOWN");
```

Assert missing or blank `GroupId` throws `ANDROID_RESPONSE_UNRECOGNIZED`.

- [ ] **Step 3: Write failing operation-error mapping tests**

Use decoded responses to assert:

```java
assertThat(mapper.toGroupCreateException(rateLimited, account(), "item:11").errorCode())
        .isEqualTo(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED);
assertThat(mapper.toException(offline, account(), "contact.save", "item:11").errorCode())
        .isEqualTo(ProtocolErrorCode.ACCOUNT_NOT_ONLINE);
assertThat(mapper.toException(timeout, account(), "group.members.list", "item:11").errorCode())
        .isEqualTo(ProtocolErrorCode.TIMEOUT);
```

Run:

```bash
mvn -Dtest=AndroidGroupMemberMapperTest,AndroidGroupCreateResponseMapperTest,AndroidGroupOperationErrorMapperTest test
```

Expected: compilation fails because all three mapper classes are missing.

- [ ] **Step 4: Implement the member and create-response mappers**

Implement `AndroidGroupMemberMapper` with these constants and core mapping methods:

```java
private static final String PARTICIPANTS_FIELD = "Participants";
private static final List<String> IDENTITY_FIELDS = List.of(
        "phone", "phone_number", "phoneNumber", "jid");

public List<GroupParticipantResult> map(JsonNode data) {
    JsonNode participants = data == null ? null : data.path(PARTICIPANTS_FIELD);
    if (participants == null || !participants.isArray()) {
        throw unrecognized("Android 群成员响应缺少 Participants 数组");
    }
    List<GroupParticipantResult> results = new ArrayList<>();
    for (JsonNode participant : participants) {
        String phone = participantPhone(participant);
        String role = text(participant.path("type"));
        boolean owner = "superadmin".equalsIgnoreCase(role);
        boolean admin = owner || "admin".equalsIgnoreCase(role);
        results.add(new GroupParticipantResult(
                phone + "@s.whatsapp.net", phone, admin, owner, role));
    }
    return List.copyOf(results);
}

private static String participantPhone(JsonNode participant) {
    for (String field : IDENTITY_FIELDS) {
        String value = text(participant.path(field));
        if (value != null) {
            return normalizePhone(value);
        }
    }
    throw unrecognized("Android 群成员缺少身份字段");
}

private static String normalizePhone(String value) {
    String normalized = value.trim();
    int at = normalized.indexOf('@');
    if (at >= 0) {
        normalized = normalized.substring(0, at);
    }
    int device = normalized.indexOf(':');
    if (device >= 0) {
        normalized = normalized.substring(0, device);
    }
    if (normalized.startsWith("+")) {
        normalized = normalized.substring(1);
    }
    if (normalized.isBlank() || !normalized.chars().allMatch(Character::isDigit)) {
        throw unrecognized("Android 群成员号码格式无法识别");
    }
    return normalized;
}

private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
        return null;
    }
    String value = node.asText("").trim();
    return value.isEmpty() ? null : value;
}

private static ProtocolException unrecognized(String message) {
    return new ProtocolException(
            ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED, message);
}
```

Implement `AndroidGroupCreateResponseMapper.map` by reusing the member mapper:

```java
public GroupCreateResult map(JsonNode data, List<String> requestedParticipants) {
    String rawGroupId = data == null ? null : text(data.path("GroupId"));
    if (rawGroupId == null) {
        throw new ProtocolException(
                ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                "Android 建群成功响应缺少 GroupId");
    }
    String groupJid = rawGroupId.endsWith("@g.us")
            ? rawGroupId
            : rawGroupId + "@g.us";
    Map<String, GroupParticipantResult> returned = memberMapper.map(data).stream()
            .collect(Collectors.toMap(
                    GroupParticipantResult::phone,
                    Function.identity(),
                    (left, right) -> left));
    List<GroupCreateParticipantResult> results = requestedParticipants.stream()
            .map(WhatsappJids::userJid)
            .map(jid -> result(jid, returned.get(phoneFromJid(jid))))
            .toList();
    boolean partial = results.stream()
            .anyMatch(result -> "UNKNOWN".equals(result.status()));
    return new GroupCreateResult(groupJid, partial, results);
}

private static GroupCreateParticipantResult result(
        String jid,
        GroupParticipantResult returned) {
    return new GroupCreateParticipantResult(
            jid,
            returned == null ? "UNKNOWN" : "OK",
            returned == null ? null : returned.role());
}

private static String phoneFromJid(String jid) {
    String localPart = jid.substring(0, jid.indexOf('@'));
    int device = localPart.indexOf(':');
    return device < 0 ? localPart : localPart.substring(0, device);
}

private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
        return null;
    }
    String value = node.asText("").trim();
    return value.isEmpty() ? null : value;
}
```

- [ ] **Step 5: Implement operation-specific Android error mapping**

`AndroidGroupOperationErrorMapper` exposes:

```java
public ProtocolException toGroupCreateException(
        AndroidDecodedResponse response,
        ProtocolAccountRef account,
        String operationId) {
    return mapped(response, account, "group.create", operationId, true);
}

public ProtocolException toException(
        AndroidDecodedResponse response,
        ProtocolAccountRef account,
        String operation,
        String operationId) {
    return mapped(response, account, operation, operationId, false);
}
```

Use this classification and exception construction; group creation alone upgrades a raw 429 or `rate-overlimit` to the existing account-restriction code:

```java
private ProtocolException mapped(
        AndroidDecodedResponse response,
        ProtocolAccountRef account,
        String operation,
        String operationId,
        boolean groupCreate) {
    ProtocolErrorCode code = errorCode(response, groupCreate);
    ProtocolException.Metadata metadata = ProtocolException.Metadata.of(
            200, response.rawProtocolCode(), null, null);
    return new ProtocolException(
            code,
            metadata,
            "Android 协议调用失败 code=" + code
                    + " armadaAccountId=" + account.armadaAccountId()
                    + " messageLength="
                    + (response.message() == null ? 0 : response.message().length()),
            null)
            .withContext(ProtocolBackend.ANDROID, operation, operationId);
}

private static ProtocolErrorCode errorCode(
        AndroidDecodedResponse response,
        boolean groupCreate) {
    String message = response.message() == null
            ? ""
            : response.message().toLowerCase(Locale.ROOT);
    if (response.validationError() != null) {
        return ProtocolErrorCode.BAD_REQUEST;
    }
    if (message.contains("不存在或已下线")
            || message.contains("不在线")
            || message.contains("离线")) {
        return ProtocolErrorCode.ACCOUNT_NOT_ONLINE;
    }
    if (message.contains("time out") || message.contains("timeout")) {
        return ProtocolErrorCode.TIMEOUT;
    }
    boolean rateLimited = "429".equals(response.rawProtocolCode())
            || message.contains("rate-overlimit");
    if (groupCreate && rateLimited) {
        return ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED;
    }
    if ("429".equals(response.rawProtocolCode())) {
        return ProtocolErrorCode.ACCOUNT_BUSY;
    }
    return ProtocolErrorCode.UNKNOWN;
}
```

- [ ] **Step 6: Reuse member parsing in join verification**

Inject `AndroidGroupMemberMapper` into `AndroidGroupMembershipVerifier` and replace its local field loop with:

```java
List<GroupParticipantResult> participants = memberMapper.map(response.data());
boolean joined = participants.stream()
        .anyMatch(item -> account.wsPhone().equals(item.phone()));
return joined ? GroupJoinOutcome.JOINED : GroupJoinOutcome.PENDING_APPROVAL;
```

Remove the old duplicate identity-field constants and normalization helpers. Update its tests to construct the verifier with the mapper.

- [ ] **Step 7: Verify GREEN and commit Task 5**

Run:

```bash
mvn -Dtest=AndroidGroupMemberMapperTest,AndroidGroupCreateResponseMapperTest,AndroidGroupOperationErrorMapperTest,AndroidGroupMembershipVerifierTest test
```

Expected: all selected tests pass.

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMemberMapper.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupCreateResponseMapper.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapper.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMembershipVerifier.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupMemberMapperTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupCreateResponseMapperTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapperTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupMembershipVerifierTest.java
git commit -m "feat: map Android group operation responses"
```

---

### Task 6: Implement Android contact, group-create, and member-list backends

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeContactAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupCreateAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupMemberListAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeContactAdapterTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupCreateAdapterTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupMemberListAdapterTest.java`

- [ ] **Step 1: Write failing Android contact and member backend tests**

For contact, verify `client.saveContacts("919000000001", List.of("919000000002"))`; assert both a failed envelope and a transport `ProtocolException` carry `backend=ANDROID`, `operation=contact.save`, and the command operation ID.

For members, verify:

```java
when(client.members("919000000001", "120363001@g.us"))
        .thenReturn(envelope("""
                {"Code":0,"Data":{"Participants":[
                  {"phone":"919000000002","type":"participant"}
                ]},"Msg":"ok"}
                """));

assertThat(adapter.list(query())).extracting(GroupParticipantResult::phone)
        .containsExactly("919000000002");
```

Also make `client.members` throw a context-free `ProtocolException` and assert the adapter adds `backend=ANDROID`, `operation=group.members.list`, and the query operation ID.

- [ ] **Step 2: Write failing Android group-create backend tests**

Cover these independent behaviors:

```java
@Test
void createsGroupThenRequestsAnnouncementOnly() {
    when(client.createGroup(anyString(), anyString(), anyList()))
            .thenReturn(successCreateEnvelope());
    when(client.setGroupAnnouncement("919000000001", "120363001@g.us", false))
            .thenReturn(successEnvelope());

    GroupCreateResult result = adapter().create(command(true));

    assertThat(result.groupJid()).isEqualTo("120363001@g.us");
    verify(client).setGroupAnnouncement("919000000001", "120363001@g.us", false);
}

@Test
void preservesCreatedGroupWhenAnnouncementRequestFails() {
    when(client.createGroup(anyString(), anyString(), anyList()))
            .thenReturn(successCreateEnvelope());
    when(client.setGroupAnnouncement(anyString(), anyString(), eq(false)))
            .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));

    assertThat(adapter().create(command(true)).groupJid())
            .isEqualTo("120363001@g.us");
}
```

Also verify `announceOnly=false` never calls `setGroupAnnouncement`, and rate-overlimit maps to `ACCOUNT_REACHOUT_RESTRICTED`.
Split announcement failure coverage into three cases: a nonzero Android envelope, a transport `ProtocolException`, and a malformed envelope rejected by `AndroidResponseDecoder`; every case must still return the created group JID.

Run:

```bash
mvn -Dtest=AndroidNativeContactAdapterTest,AndroidNativeGroupCreateAdapterTest,AndroidNativeGroupMemberListAdapterTest test
```

Expected: compilation fails because the three Android backends do not exist.

- [ ] **Step 3: Implement Android contact backend**

Core implementation:

```java
@Override
public ProtocolBackend backend() {
    return ProtocolBackend.ANDROID;
}

@Override
public void save(ContactSaveCommand command) {
    try {
        AndroidDecodedResponse response = decoder.decode(client.saveContacts(
                command.account().wsPhone(),
                List.of(normalizePhone(command.contact()))));
        if (!response.success()) {
            throw errorMapper.toException(
                    response, command.account(), "contact.save", command.operationId());
        }
    } catch (ProtocolException ex) {
        if (ex.backend().isPresent()) {
            throw ex;
        }
        throw ex.withContext(
                ProtocolBackend.ANDROID, "contact.save", command.operationId());
    }
}
```

Add this helper so Zhuan receives a bare phone instead of a user JID:

```java
private static String normalizePhone(String value) {
    String jid = WhatsappJids.userJid(value);
    String phone = jid.substring(0, jid.indexOf('@'));
    int deviceSeparator = phone.indexOf(':');
    if (deviceSeparator >= 0) {
        phone = phone.substring(0, deviceSeparator);
    }
    phone = phone.replace("+", "")
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "");
    if (phone.isBlank() || !phone.chars().allMatch(Character::isDigit)) {
        throw new ProtocolException(
                ProtocolErrorCode.BAD_REQUEST,
                "Android 联系人号码格式非法");
    }
    return phone;
}
```

- [ ] **Step 4: Implement Android member-list backend**

Core implementation:

```java
@Override
public List<GroupParticipantResult> list(GroupMemberListQuery query) {
    try {
        AndroidDecodedResponse response = decoder.decode(
                client.members(query.account().wsPhone(), query.groupJid()));
        if (!response.success()) {
            throw errorMapper.toException(
                    response,
                    query.account(),
                    "group.members.list",
                    query.operationId());
        }
        return memberMapper.map(response.data());
    } catch (ProtocolException ex) {
        if (ex.backend().isPresent()) {
            throw ex;
        }
        throw ex.withContext(
                ProtocolBackend.ANDROID,
                "group.members.list",
                query.operationId());
    }
}
```

- [ ] **Step 5: Implement Android group-create backend with best-effort announcement**

Core implementation:

```java
@Override
public GroupCreateResult create(GroupCreateCommand command) {
    try {
        List<String> participantJids = command.participants().stream()
                .map(WhatsappJids::userJid)
                .toList();
        AndroidDecodedResponse response = decoder.decode(client.createGroup(
                command.account().wsPhone(),
                command.subject(),
                participantJids));
        if (!response.success()) {
            throw errorMapper.toGroupCreateException(
                    response, command.account(), command.operationId());
        }
        GroupCreateResult result = responseMapper.map(response.data(), command.participants());
        requestAnnouncementOnly(command, result.groupJid());
        return result;
    } catch (ProtocolException ex) {
        if (ex.backend().isPresent()) {
            throw ex;
        }
        throw ex.withContext(
                ProtocolBackend.ANDROID,
                "group.create",
                command.operationId());
    }
}
```

The helper must preserve success:

```java
private void requestAnnouncementOnly(GroupCreateCommand command, String groupJid) {
    if (!command.announceOnly()) {
        return;
    }
    try {
        AndroidDecodedResponse response = decoder.decode(client.setGroupAnnouncement(
                command.account().wsPhone(), groupJid, false));
        if (!response.success()) {
            ProtocolException ex = errorMapper.toException(
                    response,
                    command.account(),
                    "group.announcement.update",
                    command.operationId());
            log.warn("Android 建群关闭发言请求失败 armadaAccountId={} groupJid={} errorCode={}",
                    command.account().armadaAccountId(), groupJid, ex.errorCode());
        }
    } catch (RuntimeException ex) {
        log.warn("Android 建群关闭发言请求异常 armadaAccountId={} groupJid={} reason={}",
                command.account().armadaAccountId(), groupJid, ex.getClass().getSimpleName());
    }
}
```

- [ ] **Step 6: Verify GREEN and commit Task 6**

Run:

```bash
mvn -Dtest=AndroidNativeContactAdapterTest,AndroidNativeGroupCreateAdapterTest,AndroidNativeGroupMemberListAdapterTest test
```

Expected: all selected tests pass.

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeContactAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupCreateAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupMemberListAdapter.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeContactAdapterTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupCreateAdapterTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupMemberListAdapterTest.java
git commit -m "feat: add Android group creation backends"
```

---

### Task 7: Register Android backends and verify the complete Worker handoff

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupOperationServiceImplTest.java`

- [ ] **Step 1: Write failing Spring registration assertions**

Add assertions:

```java
assertThat(context).hasSingleBean(GroupCreatePort.class);
assertThat(context.getBeansOfType(GroupCreateBackend.class))
        .containsKeys("webGroupCreateBackend", "androidGroupCreateBackend");
assertThat(context).hasSingleBean(ContactPort.class);
assertThat(context.getBeansOfType(ContactBackend.class))
        .containsKeys("webContactBackend", "androidContactBackend");
assertThat(context).hasSingleBean(GroupMemberListPort.class);
assertThat(context.getBeansOfType(GroupMemberListBackend.class))
        .containsKeys("webGroupMemberListBackend", "androidGroupMemberListBackend");
assertThat(context).hasSingleBean(AndroidGroupMemberMapper.class);
assertThat(context).hasSingleBean(AndroidGroupCreateResponseMapper.class);
assertThat(context).hasSingleBean(AndroidGroupOperationErrorMapper.class);
```

Run:

```bash
mvn -Dtest=ProtocolConfigurationTest test
```

Expected: assertions fail because Android capability backends are not registered.

- [ ] **Step 2: Register mappers and Android backends**

Add mapper beans and update membership verifier construction:

```java
@Bean
public AndroidGroupMemberMapper androidGroupMemberMapper() {
    return new AndroidGroupMemberMapper();
}

@Bean
public AndroidGroupCreateResponseMapper androidGroupCreateResponseMapper(
        AndroidGroupMemberMapper memberMapper) {
    return new AndroidGroupCreateResponseMapper(memberMapper);
}

@Bean
public AndroidGroupOperationErrorMapper androidGroupOperationErrorMapper() {
    return new AndroidGroupOperationErrorMapper();
}

@Bean
public AndroidGroupMembershipVerifier androidGroupMembershipVerifier(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupMemberMapper memberMapper) {
    return new AndroidGroupMembershipVerifier(client, decoder, memberMapper);
}
```

Register:

```java
@Bean
public GroupCreateBackend androidGroupCreateBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupCreateResponseMapper responseMapper,
        AndroidGroupOperationErrorMapper errorMapper) {
    return new AndroidNativeGroupCreateAdapter(client, decoder, responseMapper, errorMapper);
}

@Bean
public ContactBackend androidContactBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupOperationErrorMapper errorMapper) {
    return new AndroidNativeContactAdapter(client, decoder, errorMapper);
}

@Bean
public GroupMemberListBackend androidGroupMemberListBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupMemberMapper memberMapper,
        AndroidGroupOperationErrorMapper errorMapper) {
    return new AndroidNativeGroupMemberListAdapter(client, decoder, memberMapper, errorMapper);
}
```

- [ ] **Step 3: Add complete Android Worker handoff test**

For an Android candidate, capture all four protocol calls:

```java
ArgumentCaptor<ContactSaveCommand> contact = ArgumentCaptor.forClass(ContactSaveCommand.class);
ArgumentCaptor<GroupCreateCommand> create = ArgumentCaptor.forClass(GroupCreateCommand.class);
ArgumentCaptor<GroupMemberListQuery> members = ArgumentCaptor.forClass(GroupMemberListQuery.class);
@SuppressWarnings("unchecked")
ArgumentCaptor<List<MessageSendCommand>> messages = ArgumentCaptor.forClass(List.class);

verify(contactPort, timeout(500).atLeastOnce()).save(contact.capture());
verify(groupCreatePort).create(create.capture());
verify(groupMemberListPort).list(members.capture());
verify(messageSendPort).enqueue(messages.capture());

ProtocolAccountRef expected = new ProtocolAccountRef(
        7L, ProtocolBackend.ANDROID, "acc_android", "919000000001");
assertThat(contact.getValue().account()).isEqualTo(expected);
assertThat(create.getValue().account()).isEqualTo(expected);
assertThat(members.getValue().account()).isEqualTo(expected);
assertThat(messages.getValue()).singleElement()
        .satisfies(message -> assertThat(message.account()).isEqualTo(expected));
```

Retain assertions that successful creation marks the item `MARKETING_SENDING`, group-create failure invokes account retry, contact failure is non-blocking, and member-list failure leaves the count empty.

Update `offlineAssignedAccountIsReplacedByAvailableGroupAccountBeforeGroupCreate` so the original account is `WEB` and the replacement is `ANDROID`. Capture the resulting `GroupCreateCommand` and `MessageSendCommand`, then assert both use the replacement reference:

```java
GroupCreationMarketingAccountCandidate replacementAccount = account(
        9L,
        "919000000009",
        "acc_9",
        AccountStateCode.NORMAL,
        AccountLoginStateCode.ONLINE);
replacementAccount.setProtocolId("ANDROID");
ProtocolAccountRef replacementRef = new ProtocolAccountRef(
        9L, ProtocolBackend.ANDROID, "acc_9", "919000000009");
assertThat(create.getValue().account()).isEqualTo(replacementRef);
assertThat(messages.getValue()).singleElement()
        .satisfies(message -> assertThat(message.account()).isEqualTo(replacementRef));
```

- [ ] **Step 4: Verify direct Android group create routing**

In `GroupOperationServiceImplTest`, seed:

```java
account.setProtocolId("ANDROID");
account.setProtocolAccountId("acc_android");
account.setWsPhone("919000000001");
```

Capture `GroupCreateCommand` and assert backend, `wsPhone`, subject, participants, and `announceOnly=false`.

- [ ] **Step 5: Run integration-level Java tests and commit Task 7**

Run:

```bash
mvn -Dtest=ProtocolConfigurationTest,GroupCreationMarketingWorkerTest,GroupOperationServiceImplTest test
```

Expected: all selected tests pass.

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java \
  armada-api/src/test/java/com/armada/group/service/GroupOperationServiceImplTest.java
git commit -m "feat: enable Android group creation marketing routing"
```

---

### Task 8: Record the change and run full verification

**Files:**

- Create: `.harness/changes/2026-07-21-android-group-creation-marketing-routing.md`
- Verify: all files changed by Tasks 1–7

- [ ] **Step 1: Create the persistent change record**

Write:

```markdown
# 变更记录：Android 建群营销完整协议路由

- 日期 / 分支 / worktree: 2026-07-21 / feat/android-group-creation-marketing-routing / isolated worktree
- 需求来源: 用户要求 Android 与 Web 完整对齐；设计见 `docs/superpowers/specs/2026-07-21-android-group-creation-marketing-routing-design.md`
- 状态: 进行中

## 目标（一句话）

按账号 `protocol_id` 将联系人预存、建群、关闭发言、成员快照和营销消息完整路由到 Web 或 Android。

## 缺口拆解 / 任务清单

- [x] 建群、联系人和群成员读取改为账号感知的统一命令与 Routing Port。
- [x] Web adapter 迁移且保持原 HTTP 契约。
- [x] Android 原生 HTTP client 接入联系人、建群和关闭发言接口。
- [x] Android 建群、成员和错误响应映射。
- [x] Android backends 与 Spring 装配。
- [x] Worker、直接建群和历史 Web-only 联系人调用迁移。
- [ ] Maven 全量测试。
- [ ] 明确测试环境后执行真实联调。

## 关键设计决策

- 使用持久化 `account.protocol_id`，不在运行时解析 JSON/六段导入格式。
- 群操作保持同步 HTTP；营销消息保持 outbox + Kafka。
- 关闭普通成员发言为 best effort，失败不推翻已经成功的建群。
- 不在 Worker 内写 Android 分支，不修改数据库、前端或 Android Go 服务。

## 验证（evidence-before-done）

- 未执行：Task 8 Step 3 的定向测试命令。
- 未执行：Task 8 Step 4 的全量测试命令。

## 部署

- commit / 环境 / 部署后验证结果: 尚未部署；远程操作前确认目标环境。

## 遗留 / 跟进

- 测试环境真实联调需要专用在线六段号、最小料子和明确环境授权。
```

- [ ] **Step 2: Run formatting and diff checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; status lists only files belonging to this feature.

- [ ] **Step 3: Run all focused protocol and marketing tests**

Run:

```bash
cd armada-api
mvn -Dtest='RoutingGroupCreatePortTest,RoutingContactPortTest,RoutingGroupMemberListPortTest,HttpGroupCreateAdapterTest,HttpContactAdapterTest,HttpGroupMemberListAdapterTest,HttpAndroidNativeClientTest,AndroidResponseDecoderTest,AndroidGroupMemberMapperTest,AndroidGroupCreateResponseMapperTest,AndroidGroupOperationErrorMapperTest,AndroidNativeContactAdapterTest,AndroidNativeGroupCreateAdapterTest,AndroidNativeGroupMemberListAdapterTest,AndroidGroupMembershipVerifierTest,ProtocolConfigurationTest,GroupCreationMarketingWorkerTest,GroupOperationServiceImplTest,HistoricalGroupPullWorkerImplTest' test
```

Expected: Maven reports `BUILD SUCCESS`, zero failures, and zero errors.

- [ ] **Step 4: Run the complete Maven suite**

Run:

```bash
mvn test
```

Expected: Maven reports `BUILD SUCCESS`, zero failures, and zero errors. If a pre-existing unrelated failure appears, capture the exact class, method, and output in the change record before deciding whether to proceed.

- [ ] **Step 5: Update the change record with real evidence**

Replace the two verification lines with the actual commands, test counts, elapsed time, and result. Mark `Maven 全量测试` checked only after Step 4 succeeds. Set status to `已完成` only after all local acceptance checks pass; leave deployment explicitly pending.

- [ ] **Step 6: Review scope and protocol safety**

Run:

```bash
git diff --stat fb543ed...HEAD
git diff --name-only fb543ed...HEAD
git diff fb543ed...HEAD -- armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java
```

Confirm:

- no database migration or Mapper SQL changed;
- no Android Go, frontend, credentials, deployment, or environment files changed;
- Worker contains no `if (ANDROID)` branch;
- logs contain no message body, API key, credential, or raw response payload;
- Web group-create, contact, and member-list request shapes remain unchanged.

- [ ] **Step 7: Commit verification record**

```bash
git add .harness/changes/2026-07-21-android-group-creation-marketing-routing.md
git commit -m "docs: record Android group creation marketing verification"
```

- [ ] **Step 8: Request code review before integration**

Use the `requesting-code-review` skill against the approved design and this plan. Resolve findings with new failing tests before changing production behavior, then rerun the focused and full Maven suites.

Do not deploy or run a real WhatsApp group-creation smoke test until the user confirms the exact test environment, Android account, Web account, and material file.
