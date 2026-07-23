# 拉群营销协议与 Android 适配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让拉群营销使用同一组群操作端口同时支持 Web/Baileys 和 Android 建群账号，补齐 Android 的加群成员、设置管理员、获取群链接、退出群组四项 Armada 接入，并保证拉群营销消息只在群状态允许时实际发送。

**Architecture:** Android Zhuan 服务端四个 HTTP 能力和 `armada-protocol` 的 Web 群路由都已经存在，不新增重复接口；Armada 防腐层只补 Android client 方法、统一 backend 路由和结果映射。上层始终传 `ProtocolAccountRef`，不得出现按 Web/Android 编写的业务 `if/else`。营销发送继续复用现有消息链路，但用内部 source `group_pull_marketing` 同时驱动 Web 与 Android 的严格发送前闸门，并在 Android 命令解析与账号队列路由阶段保留该来源的 `sendIntervalMs=0`，不改变普通营销和历史群发送策略。

**Tech Stack:** Java 17、Spring Boot 3.3、JUnit 5、Mockito、AssertJ、TypeScript/Jest、Go testing、现有 `ProtocolHttpExecutor` 与 Android response decoder。

---

## 已核实的外部契约

Android 服务仓库 `whatsapp-server-feature-android-zhuan` 已有：

| 能力 | 方法与路径 | 请求体 | 成功 Data |
| --- | --- | --- | --- |
| 添加群成员 | `POST /ws/v1/groups/members/add/:key` | `group_id`, `participants` | `groupId`, `members[{lid,jid,err}]` |
| 设置/取消管理员 | `POST /ws/v1/groups/admin/set/:key` | `group_id`, `state`, `participant` | 字符串消息 |
| 获取群邀请链接 | `POST /ws/v1/groups/qrcode/:key` | `group_id` | 完整 `https://chat.whatsapp.com/...` |
| 退出群组 | `POST /ws/v1/groups/leave/:key` | `group_id` | 字符串消息 |

发言权限已经由 `AndroidNativeClient.setGroupAnnouncement(...)` 接入，路径为 `POST /ws/v1/groups/settings/sendmessage/:key`，其中 `state=true` 表示所有成员可发言，`false` 表示仅管理员可发言。

Web 服务仓库 `armada-protocol/protocol-layer` 已有：

- `POST /v1/groups/:groupJid/participants/:action`
- `GET /v1/groups/:groupJid/invite-code?accountId=...`
- `POST /v1/groups/:groupJid/leave`
- `POST /v1/groups/:groupJid/settings/announcement`

因此两个协议服务仓库本期不新建群操作接口；只在既有消息执行器中补充 `group_pull_marketing` 的发送前闸门并做回归验证。

## Task 1: 锁定 Android 原生四接口的 HTTP 契约

**Files:**

- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClientTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java`

- [ ] **Step 1: 先写四个失败测试**

在 `HttpAndroidNativeClientTest` 增加 WireMock/现有 HTTP stub 测试，逐项断言：

```java
client.addGroupMembers(
        "8613800000000",
        "120363000000001@g.us",
        List.of("8613900000000@s.whatsapp.net"));
// POST /ws/v1/groups/members/add/8613800000000
// {"group_id":"120363000000001@g.us","participants":["8613900000000@s.whatsapp.net"]}

client.setGroupAdmin(
        "8613800000000",
        "120363000000001@g.us",
        "8613900000000@s.whatsapp.net",
        true);
// POST /ws/v1/groups/admin/set/8613800000000
// {"group_id":"120363000000001@g.us","state":true,"participant":"8613900000000@s.whatsapp.net"}

client.groupInvite("8613800000000", "120363000000001@g.us");
// POST /ws/v1/groups/qrcode/8613800000000
// {"group_id":"120363000000001@g.us"}

client.leaveGroup("8613800000000", "120363000000001@g.us");
// POST /ws/v1/groups/leave/8613800000000
// {"group_id":"120363000000001@g.us"}
```

同时覆盖手机号非纯数字、群 JID 为空、参与者为空等参数拒绝行为。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl armada-api -Dtest=HttpAndroidNativeClientTest test
```

Expected: 编译失败，提示四个 client 方法不存在。

- [ ] **Step 3: 扩展 AndroidNativeClient**

新增以下原生方法，继续返回 `AndroidResponseEnvelope`，不在 client 层解释业务成功：

```java
AndroidResponseEnvelope addGroupMembers(
        String wsPhone, String groupJid, List<String> participants);

AndroidResponseEnvelope setGroupAdmin(
        String wsPhone, String groupJid, String participant, boolean enabled);

AndroidResponseEnvelope groupInvite(String wsPhone, String groupJid);

AndroidResponseEnvelope leaveGroup(String wsPhone, String groupJid);
```

- [ ] **Step 4: 在 HttpAndroidNativeClient 实现准确路径和 JSON 字段**

新增 URI 常量与请求 record：

```java
private static final String GROUP_MEMBERS_ADD_URI_PREFIX =
        "/ws/v1/groups/members/add/";
private static final String GROUP_ADMIN_SET_URI_PREFIX =
        "/ws/v1/groups/admin/set/";
private static final String GROUP_INVITE_URI_PREFIX =
        "/ws/v1/groups/qrcode/";
private static final String GROUP_LEAVE_URI_PREFIX =
        "/ws/v1/groups/leave/";

private record GroupMembersRequest(
        @JsonProperty("group_id") String groupId,
        List<String> participants) {}

private record GroupAdminRequest(
        @JsonProperty("group_id") String groupId,
        @JsonProperty("state") boolean enabled,
        String participant) {}

private record GroupRequest(@JsonProperty("group_id") String groupId) {}
```

不得把 Android 原生 `Data` 展平或改造成 Web 响应；该动作留给 adapter。

- [ ] **Step 5: 运行测试**

```bash
mvn -pl armada-api -Dtest=HttpAndroidNativeClientTest test
```

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClientTest.java
git commit -m "feat: expose android group operation clients"
```

## Task 2: 将群成员端口改为按账号后端路由

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupParticipantBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupParticipantPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupParticipantAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupParticipantPortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupParticipantAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapterTest.java`

- [ ] **Step 1: 写路由和 Android 映射失败测试**

覆盖：

1. Web `ProtocolAccountRef` 只调用 Web backend；Android 只调用 Android backend。
2. 同一 `ProtocolBackend` 注册两个实现时构造失败。
3. 缺失 backend 时抛 `UNSUPPORTED_BACKEND`。
4. Android `ADD` 按请求列表生成规范化用户 JID，再用手机号关联原生 `members`：`err` 为空即 `status=OK`；原生明确表示成员已经在群内时映射 `status=ALREADY_IN`；其他错误才映射 `status=FAILED`，原始错误写入 `rawStatus`。返回的 `Item.jid` 必须与规范化后的请求 JID 一致。
5. 原生返回的成员数少于请求数时 `partial=true`。
6. Android `PROMOTE` 调 `setGroupAdmin(..., true)`，成功返回目标成员一条 `OK`；原生明确表示该成员已经是管理员时也按目标状态达成返回 `OK`。
7. Android `DEMOTE` 调 `setGroupAdmin(..., false)`。
8. Android `REMOVE` 明确抛 `GROUP_CAPABILITY_UNSUPPORTED`，本期不暗接其它接口。
9. Android envelope 业务失败通过 `AndroidGroupOperationErrorMapper` 转为带 backend、operation 的 `ProtocolException`。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupParticipantPortTest,AndroidNativeGroupParticipantAdapterTest,HttpGroupParticipantAdapterTest \
  test
```

Expected: 新类型不存在或旧签名不匹配。

- [ ] **Step 3: 修改统一端口签名**

```java
GroupParticipantBatchResult updateParticipants(
        ProtocolAccountRef account,
        String groupJid,
        List<String> participants,
        GroupParticipantAction action);
```

`GroupParticipantBackend` 暴露 `backend()` 和相同业务方法；`RoutingGroupParticipantPort` 使用 `EnumMap<ProtocolBackend, GroupParticipantBackend>`，路由逻辑与 `RoutingGroupCreatePort` 一致。

- [ ] **Step 4: 把 Web adapter 改为 Web backend**

`HttpGroupParticipantAdapter` 实现 `GroupParticipantBackend`：

- `backend()` 返回 `ProtocolBackend.WEB`；
- 请求体的 `accountId` 使用 `account.protocolAccountId()`；
- 其余 URL、timeout、partial 和逐项映射保持现有行为。

- [ ] **Step 5: 实现 Android participant backend**

处理规则必须是穷举 `switch(action)`：

```java
return switch (action) {
    case ADD -> add(account, groupJid, participants);
    case PROMOTE -> setAdmin(account, groupJid, participants, true);
    case DEMOTE -> setAdmin(account, groupJid, participants, false);
    case REMOVE -> throw unsupported(account, action);
};
```

管理员原生接口一次只接受一个 `participant`；对列表按输入顺序逐个调用并聚合结果。新增一个仅识别原生明确“已在群内/已经是管理员”结果的目标状态分类器，测试用固定原生 Code/Msg/成员 err 样例锁定，不用模糊关键词把 timeout、离线或未知错误误判成功。一个目标真正失败时把统一错误码写入该目标 `rawStatus`，不吞掉已经成功的目标，结果 `partial=true`。不得在 adapter 内重试，业务重试由拉群执行状态机统一控制。

- [ ] **Step 6: 运行单元测试**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupParticipantPortTest,AndroidNativeGroupParticipantAdapterTest,HttpGroupParticipantAdapterTest \
  test
```

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/GroupParticipantBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupParticipantPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupParticipantAdapter.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupParticipantPortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupParticipantAdapterTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapterTest.java
git commit -m "refactor: route group participant operations by backend"
```

## Task 3: 将群设置端口改为按账号后端路由

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupSettingsPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupSettingsBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupSettingsPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupSettingsAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupSettingsPortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupSettingsAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapterTest.java`

- [ ] **Step 1: 写失败测试**

覆盖 Web/Android 路由、重复 backend、缺失 backend；Android 仅对 `setSendMessagesAllowed` 调用已经存在的：

```java
client.setGroupAnnouncement(
        account.wsPhone(), groupJid, membersCanSend);
```

并覆盖 Android 对本期不用的五项设置统一抛 `GROUP_CAPABILITY_UNSUPPORTED`，禁止静默成功：限时消息、编辑群资料、成员加人权限、邀请链接权限、入群审批。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupSettingsPortTest,AndroidNativeGroupSettingsAdapterTest,HttpGroupSettingsAdapterTest \
  test
```

- [ ] **Step 3: 把 GroupSettingsPort 的首参统一改为 ProtocolAccountRef**

六个方法只改执行账号类型，不改布尔业务语义。例如：

```java
void setSendMessagesAllowed(
        ProtocolAccountRef account, String groupJid, boolean enabled);
```

- [ ] **Step 4: 实现 Web backend 与 routing port**

`HttpGroupSettingsAdapter` 使用 `account.protocolAccountId()` 组装现有 `accountId + mode` 请求；Web 端现有 mode 映射全部保留。

- [ ] **Step 5: 实现 Android settings backend**

仅 `setSendMessagesAllowed` 有真实能力。`enabled=true` 原样传给原生 `state=true`，表示所有成员可发言；`enabled=false` 表示禁言。不要在 Android adapter 反转布尔值。

- [ ] **Step 6: 运行测试并提交**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupSettingsPortTest,AndroidNativeGroupSettingsAdapterTest,HttpGroupSettingsAdapterTest \
  test
git add armada-api/src/main/java/com/armada/platform/protocol/port/GroupSettingsPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/GroupSettingsBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupSettingsPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupSettingsAdapter.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupSettingsPortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupSettingsAdapterTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapterTest.java
git commit -m "refactor: route group settings by backend"
```

## Task 4: 将群邀请链接查询改为 Web/Android 路由

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupInviteBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupInvitePort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupInvitePortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapterTest.java`

- [ ] **Step 1: 写失败测试**

Android 成功响应 `Data="https://chat.whatsapp.com/AbCdEf"` 应映射为：

```java
new GroupInviteResult(
        requestedGroupJid,
        "AbCdEf",
        "https://chat.whatsapp.com/AbCdEf")
```

覆盖空 Data、非 WhatsApp 邀请 URL、envelope 业务失败；这些都不得伪装成功。再覆盖 Web/Android 路由与缺失 backend。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupInvitePortTest,AndroidNativeGroupInviteAdapterTest,HttpGroupInviteAdapterTest \
  test
```

- [ ] **Step 3: 实现 backend 与 routing**

`GroupInvitePort` 已经使用 `ProtocolAccountRef`，签名保持不变；只把 `HttpGroupInviteAdapter` 从直接 port 实现改为 `GroupInviteBackend`，新增 `backend()=WEB`。Android adapter 解码 URL 并提取最后一个非空 path segment 作为 inviteCode。

- [ ] **Step 4: 运行测试并提交**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupInvitePortTest,AndroidNativeGroupInviteAdapterTest,HttpGroupInviteAdapterTest \
  test
git add armada-api/src/main/java/com/armada/platform/protocol/routing/GroupInviteBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupInvitePort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapter.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupInvitePortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapterTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapterTest.java
git commit -m "feat: route group invite lookup to android"
```

## Task 5: 新增统一退出群组端口

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupLeavePort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupLeaveBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupLeavePort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupLeaveAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupLeaveAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupLeavePortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupLeaveAdapterTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupLeaveAdapterTest.java`

- [ ] **Step 1: 写端口、路由和两端适配失败测试**

统一端口：

```java
public interface GroupLeavePort {
    void leave(ProtocolAccountRef account, String groupJid);
}
```

Web adapter 必须发送：

```text
POST /v1/groups/{encodedGroupJid}/leave
body: {"accountId":"web protocol account id"}
```

Android adapter 必须调用 `client.leaveGroup(account.wsPhone(), groupJid)` 并由 decoder/error mapper 判断业务结果。

两端再各覆盖一次目标状态幂等：协议明确返回 `ALREADY_LEFT/ACCOUNT_NOT_PARTICIPANT` 时 `leave` 正常返回；timeout、网络异常或无法识别的响应仍抛异常，不能把不确定结果伪造成已退群。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupLeavePortTest,HttpGroupLeaveAdapterTest,AndroidNativeGroupLeaveAdapterTest \
  test
```

- [ ] **Step 3: 实现端口、backend、路由与适配器**

所有参数校验失败统一映射 `BAD_REQUEST`；协议缺失 backend 映射 `UNSUPPORTED_BACKEND`；Android 业务失败必须带 `operation=group.leave`。Web/Android adapter 只把原生或 HTTP 响应中能够明确归类为“账号已不在目标群”的结果归一为成功，其他错误原样抛出并交业务固定重试。

- [ ] **Step 4: 运行测试并提交**

```bash
mvn -pl armada-api \
  -Dtest=RoutingGroupLeavePortTest,HttpGroupLeaveAdapterTest,AndroidNativeGroupLeaveAdapterTest \
  test
git add armada-api/src/main/java/com/armada/platform/protocol/port/GroupLeavePort.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/GroupLeaveBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupLeavePort.java \
  armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupLeaveAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupLeaveAdapter.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupLeavePortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupLeaveAdapterTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupLeaveAdapterTest.java
git commit -m "feat: add routed group leave port"
```

## Task 6: 扩展群执行账号引用并迁移现有调用方

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupExecutionAccount.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImplTest.java`

- [ ] **Step 1: 写执行账号选择测试**

`GroupExecutionAccountSelectorDbTest` 应断言选择结果包含：

- Armada account id；
- `protocol_id`；
- `protocol_type` 映射后的 `ProtocolBackend`；
- `ws_phone`。

记录改为：

```java
public record GroupExecutionAccount(
        Long accountId,
        String protocolAccountId,
        ProtocolBackend backend,
        String wsPhone) {

    public ProtocolAccountRef toProtocolAccountRef() {
        return new ProtocolAccountRef(
                accountId, backend, protocolAccountId, wsPhone);
    }
}
```

- [ ] **Step 2: 运行受影响测试确认编译失败**

```bash
mvn -pl armada-api \
  -Dtest=GroupExecutionAccountSelectorTest,GroupExecutionAccountSelectorDbTest,GroupDetailServiceImplTest,HistoricalGroupServiceImplTest,HistoricalGroupPullWorkerImplTest \
  test
```

- [ ] **Step 3: 修改查询和现有调用方**

所有 `GroupParticipantPort`、`GroupSettingsPort` 调用都传完整 ref：

```java
protocolPorts.participants().updateParticipants(
        account.toProtocolAccountRef(), groupJid, participants, action);

protocolPorts.settings().setSendMessagesAllowed(
        account.toProtocolAccountRef(), groupJid, enabled);
```

`HistoricalGroupServiceImpl` 已持有 `ProtocolAccountRef`，直接传 ref。`HistoricalGroupPullWorkerImpl` 不再只把 `protocolAccountId` 传入 `addBatch`，必须把执行账号完整 ref 一并带入。

本任务只迁移签名，不改变历史群的账号筛选策略；原来明确只支持 Web 的流程继续按原业务限制筛选 Web。

- [ ] **Step 4: 运行回归测试并提交**

```bash
mvn -pl armada-api \
  -Dtest=GroupExecutionAccountSelectorTest,GroupExecutionAccountSelectorDbTest,GroupDetailServiceImplTest,HistoricalGroupServiceImplTest,HistoricalGroupPullWorkerImplTest \
  test
git add armada-api/src/main/java/com/armada/group/model/vo/GroupExecutionAccount.java \
  armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java \
  armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java \
  armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImpl.java \
  armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml \
  armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java \
  armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java \
  armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java \
  armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java \
  armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImplTest.java
git commit -m "refactor: pass protocol account refs to group operations"
```

## Task 7: 在 Spring 配置中注册 Web/Android 双后端

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`

- [ ] **Step 1: 写 Spring context 失败测试**

断言 context 中每个公共端口只有一个 Bean：

```java
assertThat(context).hasSingleBean(GroupParticipantPort.class);
assertThat(context).hasSingleBean(GroupSettingsPort.class);
assertThat(context).hasSingleBean(GroupInvitePort.class);
assertThat(context).hasSingleBean(GroupLeavePort.class);
```

并分别断言 Web/Android backend 各两份，不把具体 backend 暴露为同一 port 造成 `NoUniqueBeanDefinitionException`。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl armada-api -Dtest=ProtocolConfigurationTest test
```

- [ ] **Step 3: 替换四个旧直连 Bean**

配置方式与现有 create/contact/member-list 一致：

```java
@Bean
public GroupParticipantPort groupParticipantPort(
        List<GroupParticipantBackend> backends) {
    return new RoutingGroupParticipantPort(backends);
}
```

对 settings、invite、leave 同样注册；Web backend 使用 `registry.required(ProtocolBackend.WEB)`，Android backend 注入 `AndroidNativeClient`、`AndroidResponseDecoder` 和 `AndroidGroupOperationErrorMapper`。

- [ ] **Step 4: 运行测试**

```bash
mvn -pl armada-api -Dtest=ProtocolConfigurationTest test
```

Expected: PASS，四个端口均为 routing 实现。

- [ ] **Step 5: 提交**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java
git commit -m "feat: wire routed group operation ports"
```

## Task 8: 在 GroupCreatePort 外层实现严格 Redis 幂等

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/config/GroupCreateIdempotencyProperties.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/config/GroupCreateIdempotencyConfiguration.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/idempotency/GroupCreateIdempotencyRecord.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/idempotency/GroupCreateIdempotencyStore.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/idempotency/RedisGroupCreateIdempotencyStore.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/idempotency/IdempotentGroupCreatePort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolErrorCode.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/main/resources/application.yml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/idempotency/RedisGroupCreateIdempotencyStoreTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/idempotency/IdempotentGroupCreatePortTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: 写严格幂等行为失败测试**

`IdempotentGroupCreatePortTest` 覆盖：

1. 首次 `operationId` 原子写入 `PROCESSING` 后才调用 delegate。
2. delegate 成功后保存完整精简结果：`groupJid`、`partial`、逐参与者 `jid/status/rawStatus`。
3. 相同 `operationId` 命中 `SUCCEEDED` 时直接反序列化首次结果，delegate 调用总次数仍为 1。
4. 相同 `operationId` 命中 `PROCESSING` 时抛 `GROUP_CREATE_RESULT_UNCONFIRMED`，不得再次调用 delegate。
5. Redis 在首次登记前不可用时抛 `IDEMPOTENCY_STORE_UNAVAILABLE`，delegate 调用次数为 0；上层把它记录为系统异常并等待恢复。
6. delegate 返回明确拒绝、确定未创建的异常时删除本次 `PROCESSING`，保留业务状态机固定两次重试机会。
7. delegate 出现 timeout/network/未知响应等可能已经创建的异常时保留 `PROCESSING`，统一抛 `GROUP_CREATE_RESULT_UNCONFIRMED`。
8. delegate 已成功但写 `SUCCEEDED` 失败时同样抛 `GROUP_CREATE_RESULT_UNCONFIRMED`，绝不允许上层重建。
9. 现有建群营销 worker 收到 `GROUP_CREATE_RESULT_UNCONFIRMED` 时，把当前执行项一次性收口为失败并保留该原因，不再换账号重试；其他能够确认未创建的异常继续沿用原换号重试。

- [ ] **Step 2: 写 Redis store 原子语义失败测试**

store 接口固定为：

```java
public interface GroupCreateIdempotencyStore {
    Optional<GroupCreateIdempotencyRecord> find(String operationId);
    boolean tryBegin(String operationId, String claimToken);
    void saveSuccess(
            String operationId, String claimToken, GroupCreateResult result);
    void clearProcessing(String operationId, String claimToken);
}
```

使用 mock `StringRedisTemplate`/嵌入式测试替身断言：

- wrapper 每次首次竞争生成随机 `claimToken`，它只保存在 Redis value 中，不是业务 operationId，也不进入数据库；
- `tryBegin` 使用 `SET key PROCESSING+claimToken NX TTL`，不是先 GET 再 SET；
- 首次 `find` 为空但 `tryBegin=false` 时必须立即再次 `find`：读到 `SUCCEEDED` 就回放结果，读到 `PROCESSING` 就返回未确认；若恰逢明确失败方清理后再次为空，只允许重新走一次原子领取循环。任何路径都不得因为竞争失败直接调用 delegate，只有持有当前 claimToken 的调用方可以建群；
- `saveSuccess` 和 `clearProcessing` 使用 compare-and-set/delete Lua，只允许当前 Redis value 的 claimToken 与本次一致，不能覆盖另一执行者或误删成功结果；
- key 使用独立前缀，不与 `android-zhuan:` 图片 key 混用；
- JSON 损坏按幂等存储不可用处理，不调用建群 delegate。

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn -pl armada-api \
  -Dtest=RedisGroupCreateIdempotencyStoreTest,IdempotentGroupCreatePortTest \
  test
```

Expected: 新类型和错误码不存在。

- [ ] **Step 4: 增加有限保留期配置和专用字符串模板**

配置不进入业务页面，默认：

```yaml
armada:
  protocol:
    group-create-idempotency:
      key-prefix: "armada:group-create:idempotency:"
      ttl: 30d
```

`GroupCreateIdempotencyConfiguration` 复用现有、已带 standalone/cluster/ACL/TLS 配置的 `androidImageRedisConnectionFactory`，但创建单独的 `StringRedisTemplate`。只共享 Redis 连接和物理部署，不共享 key 前缀或 value serializer。

TTL 必须显著长于建群协议超时和业务短租约；执行记录一旦命中未确认会进入人工处理，即使记录以后自然过期也不会被 worker 自动重建。

- [ ] **Step 5: 实现结果记录和异常分类**

新增错误码：

```java
GROUP_CREATE_RESULT_UNCONFIRMED,
IDEMPOTENCY_STORE_UNAVAILABLE
```

只有能够确认请求未造成建群的本地校验/明确拒绝才清理 `PROCESSING`，例如 `BAD_REQUEST`、`ACCOUNT_NOT_ONLINE`、`UNSUPPORTED_BACKEND`、`ACCOUNT_REACHOUT_RESTRICTED`。`TIMEOUT`、`NETWORK`、`HTTP_ERROR`、`ANDROID_RESPONSE_UNRECOGNIZED`、`UNKNOWN` 以及成功结果写 Redis 失败全部保留处理中记录并改抛 `GROUP_CREATE_RESULT_UNCONFIRMED`。

不得把 Redis value 或 operationId 写入五张拉群业务表。

- [ ] **Step 6: 用幂等 port 包住现有 Web/Android 路由**

`ProtocolConfiguration` 仍只暴露一个 `GroupCreatePort` Bean：

```java
@Bean
public GroupCreatePort groupCreatePort(
        List<GroupCreateBackend> backends,
        GroupCreateIdempotencyStore store) {
    return new IdempotentGroupCreatePort(
            new RoutingGroupCreatePort(backends), store);
}
```

这样手工建群、现有建群营销和新拉群营销都从同一个外层进入；Web/Android backend 内部不得各自再实现第二套幂等。现有调用方已经生成 operationId，不改变业务接口。

同步修改 `GroupCreationMarketingWorker`：捕获 `ProtocolException` 后先识别 `GROUP_CREATE_RESULT_UNCONFIRMED`，在事务内调用现有 `markItemFailed` 收口当前 item，`reason_code` 原样记录该错误码，并直接返回；不得进入 `resetItemForAccountRetry`。旧任务没有“人工处理”状态，因此这里使用既有失败终态，保证不重复建群且不引入新状态。

- [ ] **Step 7: 运行测试**

```bash
mvn -pl armada-api \
  -Dtest=RedisGroupCreateIdempotencyStoreTest,IdempotentGroupCreatePortTest,RoutingGroupCreatePortTest,ProtocolConfigurationTest,GroupCreationMarketingWorkerTest \
  test
```

Expected: PASS，Spring context 中仍只有一个 `GroupCreatePort` Bean。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/config/GroupCreateIdempotencyProperties.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/GroupCreateIdempotencyConfiguration.java \
  armada-api/src/main/java/com/armada/platform/protocol/idempotency/GroupCreateIdempotencyRecord.java \
  armada-api/src/main/java/com/armada/platform/protocol/idempotency/GroupCreateIdempotencyStore.java \
  armada-api/src/main/java/com/armada/platform/protocol/idempotency/RedisGroupCreateIdempotencyStore.java \
  armada-api/src/main/java/com/armada/platform/protocol/idempotency/IdempotentGroupCreatePort.java \
  armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolErrorCode.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/test/java/com/armada/platform/protocol/idempotency/RedisGroupCreateIdempotencyStoreTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/idempotency/IdempotentGroupCreatePortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java
git commit -m "feat: make group creation idempotent across backends"
```

## Task 9: 为拉群营销增加 Web/Android 严格发送前闸门

**Files:**

- Modify: `../armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
- Modify: `../armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`
- Modify: `../whatsapp-server-feature-android-zhuan/internal/armada/message_command.go`
- Modify: `../whatsapp-server-feature-android-zhuan/internal/armada/message_command_test.go`
- Modify: `../whatsapp-server-feature-android-zhuan/internal/armada/message_sender.go`
- Modify: `../whatsapp-server-feature-android-zhuan/internal/armada/message_sender_test.go`

- [ ] **Step 1: 写 Web 消息闸门失败测试**

在 `worker-consumer.test.ts` 用 `source=group_pull_marketing` 覆盖：

1. `groupStatus=NORMAL` 才调用 `sendMessage/relayMessage`；
2. `BANNED/CHAT_SUSPENDED` 不调用真实发送，直接发布失败结果，保留群状态并使用稳定 `reasonCode=GROUP_BANNED`；
3. `NO_PERMISSION/ANNOUNCE_ONLY_NON_ADMIN` 不调用真实发送，发布 `reasonCode=NO_PERMISSION`；
4. `UNCONFIRMED` 或状态解析异常不发送，发布 `reasonCode=GROUP_STATUS_UNCONFIRMED`，交 Armada 的单次消息重试和后续轮次处理；
5. 结果发布成功后才 ack，发布失败仍不 ack；
6. `marketing_task` 和 `historical_group_pull` 现有测试保持原行为，不能被新闸门误伤。

- [ ] **Step 2: 实现 Web source 专用闸门**

`executeMessageSend` 取得 `resolveGroupSendability` 结果后，只在 `payload.source === "group_pull_marketing"` 时执行严格判断。非 `NORMAL` 使用现有 `message.send_result_reported` 结构发布一次失败结果并返回，不获取发送 socket、不调用 WhatsApp 发送。不得新增 HTTP 接口或第二套结果事件。

- [ ] **Step 3: 写 Android 零群间隔解析失败测试**

在 `message_command_test.go` 增加完整命令、账号队列路由和拒绝结果引用三组断言：

1. `source=group_pull_marketing, sendIntervalMs=0` 时，`ParseMessageCommand` 的 `Payload.SendIntervalMS` 必须为 `0`；
2. 同一命令经 `ParseMessageCommandRoute` 后，`SendInterval` 必须为 `0`，账号串行队列不得重新引入 500ms 间隔；
3. 经 `ParseMessageCommandReference` 后仍保留 `SendIntervalMS=0`，确保命令被拒绝时引用内容一致；
4. 普通 `marketing_task` 的字段缺失或显式 `sendIntervalMs=0` 继续规范为现有默认 `500ms`，显式非零值继续原样保留。

- [ ] **Step 4: 实现 Android source 感知的间隔规范化**

在 `ParseMessageCommandRoute` 的最小 payload 中补读 `source`，完整命令的 `trim()`、route 和 reference 三条解析路径统一调用 source 感知的规范化函数：

```go
func normalizeMessageSendInterval(intervalMS int64, source string) int64 {
	if intervalMS == 0 && strings.TrimSpace(source) != "group_pull_marketing" {
		return defaultMessageSendIntervalMS
	}
	return intervalMS
}
```

调用前先对 source 做 `TrimSpace`。不向 `MessageCommandRoute` 增加无用的公开业务字段，只在解析 route payload 时读取 source 用于决定队列间隔；不得把普通营销的既有 500ms 默认值一并取消。

- [ ] **Step 5: 写 Android 消息闸门失败测试**

在 `message_sender_test.go` 增加与 Web 相同的 source 矩阵，断言 `fakeZhuanMessageClient` 的真实 Send 调用次数：仅 `NORMAL` 为 1，其余为 0；返回的 `MessageSendResult` 保留 `GroupStatus/GroupStatusReason/GroupStatusCheckedAt` 和相同稳定错误码。普通 `marketing_task` 继续沿用当前实际发送后诊断策略。

- [ ] **Step 6: 实现 Android source 专用闸门**

`ZhuanMessageSender.BeginSend` 在 `command.Payload.Source == "group_pull_marketing"` 时，先调用现有 `ResolveGroupSendability`：

- `NORMAL`：继续原发送流程；
- `BANNED`、`NO_PERMISSION`：直接返回终态失败，不调用原生发送；
- 查询失败、空结果或 `UNCONFIRMED`：返回 `GROUP_STATUS_UNCONFIRMED`，不降级为盲发；
- context 取消/超时继续按现有取消语义返回，不能伪造成业务成功。

不得改 Android HTTP 路由、Kafka schema 或消息结果 topic；source 字段已经存在，只增加策略分支。

- [ ] **Step 7: 分别运行两端测试**

```bash
pnpm --dir ../armada-protocol/protocol-layer test -- \
  src/commands/worker-consumer.test.ts
go -C ../whatsapp-server-feature-android-zhuan test ./internal/armada/...
```

Expected: PASS；两端只对 `group_pull_marketing` 严格拦截；Android 完整命令和账号队列都保留该来源的 0 间隔，普通营销的 500ms 默认值及其他回归断言不变。

- [ ] **Step 8: 分仓提交**

在 `armada-protocol` 和 `whatsapp-server-feature-android-zhuan` 各自仓库只提交本 Task 的文件，不跨仓库合并提交。

## Task 10: 协议契约与回归验收

**Files:**

- Verify only: `../armada-protocol/protocol-layer/src/routes/groups.ts`
- Verify only: `../armada-protocol/protocol-layer/src/master-gateway/routing.ts`
- Verify only: `../whatsapp-server-feature-android-zhuan/api/router/router.go`
- Verify only: `../whatsapp-server-feature-android-zhuan/api/controller/group.go`
- Verify only: `../whatsapp-server-feature-android-zhuan/api/service/group.go`

- [ ] **Step 1: 运行 Armada 协议防腐层全部测试**

```bash
mvn -pl armada-api \
  -Dtest='com.armada.platform.protocol.**.*Test,com.armada.group.**.*Test' \
  test
```

Expected: PASS。

- [ ] **Step 2: 运行 armada-protocol 现有群路由测试**

```bash
pnpm --dir ../armada-protocol/protocol-layer test -- \
  src/routes/groups-participants-mutation.test.ts \
  src/routes/groups-settings.test.ts \
  src/master-gateway/routing.test.ts \
  src/master-gateway/register.test.ts
```

Expected: PASS；若 package script 不接受文件参数，执行 `pnpm --dir ../armada-protocol/protocol-layer test`。

- [ ] **Step 3: 运行 Android 服务现有 Go 测试**

```bash
go -C ../whatsapp-server-feature-android-zhuan test ./api/... ./internal/...
```

Expected: PASS。这里只验证既有四个群操作接口没有被发送前闸门改动破坏；Go 端业务改动仅限 Task 9 的 source 专用发送策略和 source 感知的零间隔保留。

- [ ] **Step 4: 运行 Armada 模块编译**

```bash
mvn -pl armada-api -DskipTests package
```

Expected: BUILD SUCCESS。

## 协议验收口径

- 拉群业务层只认识 `GroupParticipantPort`、`GroupSettingsPort`、`GroupInvitePort`、`GroupLeavePort`，不判断协议类型。
- Web 账号仍走现有 `armada-protocol` 路径，Android 账号走 Zhuan 原生路径。
- Android 添加成员保留逐成员成功/失败，不把部分成功折叠为整体成功。
- Android 设置管理员一次一个目标，按输入顺序聚合；拉群场景只传一个营销账号。
- 群邀请链接失败和退群失败均由上层按原需求处理；adapter 不自行补查、不自行重试。
- Android 仅实现有真实原生接口的能力；不支持的设置显式报错，不做伪映射。
- 创建群组在 Web/Android 路由外共用同一 Redis operationId 状态；Redis 不可用或首次结果不明确时绝不再次下发建群。
- 两个协议服务仓库本期没有新增接口，也没有复制一套拉群业务状态机。
