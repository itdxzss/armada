# Armada 群详情抽屉现有入口补齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 只补齐当前群详情抽屉已有入口，使群资料、限时消息、五项权限和成员管理真实连通 WhatsApp，并由 Armada 自动选择执行账号。

**Architecture:** 前端只调用 Armada；Armada 使用独立 `GroupDetailService` 聚合本地资料与协议实时状态，并通过可复用选号器选择“在线、仍在群内、优先管理员”的账号；`armada-protocol` 负责 master 到 owner worker 转发、Baileys 调用、能力声明和协议错误归一。按详情读取、群资料、限时消息、权限、成员管理五个纵向 Slice 交付。

**Tech Stack:** Vue 3 + TypeScript + Element Plus、Java 17 + Spring Boot 3.3.5 + MyBatis、Fastify 5 + TypeScript + Baileys 7.0.0-rc11、JUnit 5/Mockito/DbTest、Jest、Node test。

---

## 0. 执行约束与源规格

- 源规格：`docs/superpowers/specs/2026-07-15-group-detail-drawer-completion-design.md`。
- 目标仓库：
  - `/Users/daishuaishuai/IdeaProjects/armada`
  - `/Users/daishuaishuai/IdeaProjects/armada-protocol`
  - `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`
- 开始执行时必须先使用 `using-git-worktrees`，创建以下三个同主题隔离 worktree；不得在当前含其它在途修改的 `armada` 工作区直接编码：
  - `/Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer`
  - `/Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer`
  - `/Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer`
- 下方命令中的绝对路径均指向上述隔离 worktree；worktree 建立后先分别读取其中的 `AGENTS.md` 并确认基线测试，再开始 Task 1。
- 每个 Task 先写失败测试、确认红灯、写最小实现、确认绿灯，再提交当前仓库。
- 每次提交前只暂存当前 Task 的 **Files** 清单（使用 `git add --` 逐条列出），运行 `git diff --cached --check` 并核对 `git diff --cached --stat`，不得夹带其它在途修改。
- 所有新增或修改的 Java public 类型、构造器和方法都按 `armada/AGENTS.md` 引用的编码规范补齐 Javadoc；计划代码块聚焦契约和控制流，执行时不得省略这些注释。
- 任何远程、SSH、部署或 WhatsApp 真群探测必须先确认目标测试环境；计划中的本地单测和构建不需要远程授权。
- 不新增群描述、实际添加成员、复制/重置邀请链接、审批列表或退群入口。

## 1. 文件结构

### 1.1 armada-protocol

**Modify**

- `protocol-layer/src/master-gateway/routing.ts`：从群详情请求中提取 `accountId`。
- `protocol-layer/src/master-gateway/register.ts`：把群详情 GET/POST 转发到 owner worker。
- `protocol-layer/src/master-gateway/routing.test.ts`
- `protocol-layer/src/master-gateway/register.test.ts`
- `protocol-layer/src/routes/groups.ts`：规范群元数据、头像回读、限时消息、权限错误和成员结果。
- `openapi/protocol-v1.yaml`
- `openapi/generated/types.ts`：只通过生成脚本更新。
- `docs/API-CATALOG.md`

**Create**

- `protocol-layer/src/routes/groups-detail.test.ts`
- `protocol-layer/src/routes/groups-settings.test.ts`
- `protocol-layer/src/routes/groups-participants-mutation.test.ts`
- `protocol-layer/src/routes/groups-test-harness.ts`

**Conditional after live evidence**

- `protocol-layer/patches/baileys+7.0.0-rc11.patch`：只有测试环境确认邀请链接权限的真实 wire 字段和写入标签后才修改。

### 1.2 Armada

**Create**

- `armada-api/src/main/java/com/armada/group/service/GroupDetailService.java`
- `armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java`
- `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- `armada-api/src/main/java/com/armada/group/model/vo/GroupExecutionAccount.java`
- `armada-api/src/main/java/com/armada/group/model/vo/GroupDetailVO.java`
- `armada-api/src/main/java/com/armada/group/model/vo/GroupAvatarUpdateVO.java`
- `armada-api/src/main/java/com/armada/group/model/vo/GroupMemberBatchResultVO.java`
- `armada-api/src/main/java/com/armada/group/model/vo/GroupMemberOperationResultVO.java`
- `armada-api/src/main/java/com/armada/group/model/dto/GroupTimedMessageCommandDTO.java`
- `armada-api/src/main/java/com/armada/group/model/dto/GroupSettingCommandDTO.java`
- `armada-api/src/main/java/com/armada/group/model/dto/GroupMemberBatchCommandDTO.java`
- `armada-api/src/main/java/com/armada/group/model/enums/GroupPermissionKey.java`
- `armada-api/src/main/java/com/armada/group/model/enums/GroupTimedMessageMode.java`
- `armada-api/src/main/java/com/armada/platform/protocol/model/enums/GroupParticipantAction.java`
- `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupMetadataResult.java`
- `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupPictureResult.java`
- `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupParticipantBatchResult.java`
- `armada-api/src/main/java/com/armada/platform/protocol/port/GroupMetadataPort.java`
- `armada-api/src/main/java/com/armada/platform/protocol/port/GroupSettingsPort.java`
- `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapter.java`
- `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java`
- `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java`
- `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java`
- `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapterTest.java`
- `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapterTest.java`

**Modify**

- `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- `armada-api/src/main/java/com/armada/group/service/GroupLinkService.java`
- `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- `armada-api/src/main/java/com/armada/group/model/dto/GroupSubjectCommandDTO.java`
- `armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java`
- `armada-api/src/main/java/com/armada/platform/protocol/port/GroupProfilePort.java`
- `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java`
- `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupProfileAdapter.java`
- `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- `armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolErrorCode.java`
- `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`
- `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`
- `armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java`
- `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupProfileAdapterTest.java`
- `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapterTest.java`
- `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`

**Delete after callers migrate**

- `armada-api/src/main/java/com/armada/group/model/vo/GroupMemberQueryAccount.java`

本期无 Flyway、表或字段变更。

### 1.3 wheel-saas-pure-web

**Modify**

- `src/api/group.ts`
- `src/api/group.test.ts`
- `src/views/group/list/components/GroupMemberDrawer.vue`
- `src/views/group/list/constants.ts`
- `.harness/changes/group-list-frontend/summary.md`

**Create**

- `src/views/group/list/components/GroupMemberDrawer.test.ts`

组件通过删除旧 fallback/占位逻辑来腾出空间；最终 `GroupMemberDrawer.vue` 不超过 600 行，不增加新按钮或新 section。

---

### Task 1: 让协议 master 正确转发群详情请求

**Files:**

- Modify: `protocol-layer/src/master-gateway/routing.ts`
- Modify: `protocol-layer/src/master-gateway/register.ts`
- Modify: `protocol-layer/src/master-gateway/routing.test.ts`
- Modify: `protocol-layer/src/master-gateway/register.test.ts`

- [ ] **Step 1: 写群详情路由提取失败测试**

在 `routing.test.ts` 增加：

```ts
it.each([
  ['/v1/groups/120363detail%40g.us/metadata?accountId=acc_100', undefined],
  ['/v1/groups/120363detail%40g.us/subject', { accountId: 'acc_100', subject: '新群名' }],
  ['/v1/groups/120363detail%40g.us/settings/announcement', { accountId: 'acc_100', mode: 'announcement' }],
  ['/v1/groups/120363detail%40g.us/participants/promote', { accountId: 'acc_100', participants: ['8613800000000@s.whatsapp.net'] }]
])('extracts account id from group detail request %s', (url, body) => {
  expect(extractGatewayAccountId({ url, body })).toBe('acc_100')
})
```

在 `register.test.ts` 增加一个 POST 设置转发测试，断言请求原样转到 `http://worker-2:8082/v1/groups/120363detail%40g.us/settings/announcement`。

- [ ] **Step 2: 运行测试确认红灯**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer/protocol-layer
npm test -- --runInBand src/master-gateway/routing.test.ts src/master-gateway/register.test.ts
```

Expected: 新增 metadata/subject/settings/promote 用例失败，当前提取结果为 `null` 或请求没有转发。

- [ ] **Step 3: 扩展账号提取和网关范围**

在 `routing.ts` 增加并调用：

```ts
function groupItemAccountId(parts: string[], parsed: URL, body: unknown): string | null {
  if (
    parts.length >= 4 &&
    parts[0] === 'v1' &&
    parts[1] === 'groups' &&
    !GROUP_BODY_ACCOUNT_ROUTES.has(parts[2]!)
  ) {
    return bodyAccountId(body) ?? queryAccountId(parsed)
  }
  return null
}
```

在 `extractGatewayAccountId` 的 collection route 判断之后返回 `groupItemAccountId(parts, parsed, input.body)`。在 `register.ts` 的 `isGroupArmadaRequest` 中保留 preview/join/create，并增加：

```ts
if (
  parts.length >= 4 &&
  parts[0] === 'v1' &&
  parts[1] === 'groups'
) {
  return method === 'GET' || method === 'POST'
}
```

- [ ] **Step 4: 运行网关测试确认绿灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer/protocol-layer
npm test -- --runInBand src/master-gateway/routing.test.ts src/master-gateway/register.test.ts
```

Expected: 两个测试文件全部通过，GET query 和 POST body 的 `accountId` 都能路由 owner worker。

- [ ] **Step 5: 提交协议网关改动**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
git add protocol-layer/src/master-gateway/routing.ts protocol-layer/src/master-gateway/register.ts protocol-layer/src/master-gateway/routing.test.ts protocol-layer/src/master-gateway/register.test.ts
git commit -m "fix: forward group detail requests to owner worker"
```

### Task 2: 规范协议群元数据并在头像更新后回读 URL

**Files:**

- Modify: `protocol-layer/src/routes/groups.ts`
- Create: `protocol-layer/src/routes/groups-detail.test.ts`
- Create: `protocol-layer/src/routes/groups-test-harness.ts`
- Modify: `openapi/protocol-v1.yaml`
- Modify (generated): `openapi/generated/types.ts`
- Modify: `docs/API-CATALOG.md`

- [ ] **Step 1: 写群元数据和头像响应测试**

新测试必须断言稳定 wire 字段，而不是直接透传 Baileys 对象：

```ts
import Fastify from 'fastify'

import { registerErrorHandler } from '../error/error-handler.js'
import type { RouteContext } from './_context.js'
import { registerGroupsRoutes } from './groups.js'

export function buildGroupTestApp(sock: unknown) {
  const app = Fastify()
  const logger = { info() {}, debug() {}, warn() {}, error() {} }
  const ctx = {
    accounts: { getSocket: () => sock },
    operationGate: {
      async runGroup(_accountId: string, _operation: string, fn: () => Promise<unknown>) {
        return fn()
      }
    },
    metrics: {
      groupCreateTotal: { inc() {} },
      groupAddParticipantsTotal: { inc() {} }
    },
    logger,
    config: {
      log: { auditSuccessEnabled: false, auditSampleRate: 0 }
    }
  } as unknown as RouteContext
  registerErrorHandler(app, logger as never)
  registerGroupsRoutes(app, ctx)
  return app
}

it('returns drawer metadata and participant phone mappings', async () => {
  const sock = {
    async groupMetadata() {
      return {
        id: '120363detail@g.us',
        subject: '真实群名',
        announce: true,
        restrict: false,
        memberAddMode: true,
        joinApprovalMode: true,
        ephemeralDuration: 604800,
        participants: [{
          id: '12345@lid',
          phoneNumber: '8613800000000@s.whatsapp.net',
          admin: 'superadmin'
        }]
      }
    }
  }
  const app = buildGroupTestApp(sock)
  const response = await app.inject({
    method: 'GET',
    url: '/v1/groups/120363detail@g.us/metadata?accountId=acc_100'
  })

  expect(response.statusCode).toBe(200)
  expect(response.json()).toMatchObject({
    id: '120363detail@g.us',
    subject: '真实群名',
    announce: true,
    restrict: false,
    memberAddMode: true,
    joinApprovalMode: true,
    ephemeralDuration: 604800,
    inviteViaLink: null,
    capabilities: {
      inviteViaLink: {
        supported: false,
        reason: 'Baileys 7.0.0-rc11 does not expose invite-link access'
      }
    },
    participants: [{
      id: '12345@lid',
      phoneNumber: '8613800000000@s.whatsapp.net',
      admin: 'superadmin'
    }]
  })
  await app.close()
})
```

再增加头像测试：`POST /picture` 调用 `updateProfilePicture` 成功后调用 `profilePictureUrl(groupJid, 'image')`，响应包含 `success=true` 和 `avatarUrl`；URL 回读失败时仍返回 `success=true, avatarUrl=null`。同时测试 `GET /picture?accountId=...` 只读取当前 URL，供 Armada 在更新超时后确认实际状态。

- [ ] **Step 2: 运行测试确认红灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer/protocol-layer
npm test -- --runInBand src/routes/groups-detail.test.ts
```

Expected: 元数据缺少 `memberAddMode/joinApprovalMode/ephemeralDuration/capabilities` 的稳定契约，头像响应缺少 `avatarUrl`。

- [ ] **Step 3: 显式构造元数据响应**

在 `groups.ts` 增加映射函数：

```ts
function mapGroupParticipant(participant: Record<string, unknown>) {
  return {
    id: typeof participant.id === 'string' ? participant.id : '',
    phoneNumber: typeof participant.phoneNumber === 'string' ? participant.phoneNumber : null,
    lid: typeof participant.lid === 'string' ? participant.lid : null,
    admin: typeof participant.admin === 'string' ? participant.admin : null
  }
}

function groupMetadataResponse(meta: Record<string, unknown>) {
  const participants = Array.isArray(meta.participants)
    ? meta.participants.map(value => mapGroupParticipant(value as Record<string, unknown>))
    : []
  return {
    id: typeof meta.id === 'string' ? meta.id : '',
    subject: typeof meta.subject === 'string' ? meta.subject : '',
    desc: typeof meta.desc === 'string' ? meta.desc : null,
    owner: typeof meta.owner === 'string' ? meta.owner : null,
    creation: typeof meta.creation === 'number' ? meta.creation : null,
    participants,
    size: participants.length,
    announce: Boolean(meta.announce),
    restrict: Boolean(meta.restrict),
    memberAddMode: Boolean(meta.memberAddMode),
    joinApprovalMode: Boolean(meta.joinApprovalMode),
    ephemeralDuration: typeof meta.ephemeralDuration === 'number' ? meta.ephemeralDuration : 0,
    inviteViaLink: null,
    capabilities: {
      inviteViaLink: {
        supported: false,
        reason: 'Baileys 7.0.0-rc11 does not expose invite-link access'
      }
    },
    isBanned: false,
    lastActivityAt: null
  }
}
```

`GET /metadata` 使用该函数返回，不再 `...meta`。头像更新后用同一 socket 调 `profilePictureUrl`；回读失败只记录 warn，不覆盖已成功的 WhatsApp 修改。增加同路径 GET：

```ts
app.get('/v1/groups/:groupJid/picture', async (req, reply) => {
  const { groupJid } = GroupJidParam.parse(req.params)
  const { accountId } = z.object({ accountId: z.string() }).parse(req.query)
  const sock = ctx.accounts.getSocket(accountId)
  let avatarUrl: string | null = null
  try {
    avatarUrl = await sock.profilePictureUrl(groupJid, 'image')
  } catch (error) {
    ctx.logger.warn({ accountId, groupJid, error: rawErrorMessage(error) },
      'group picture URL read failed')
  }
  reply.send({ groupJid, avatarUrl })
})
```

- [ ] **Step 4: 更新 OpenAPI 并生成类型**

`GroupParticipant` 使用当前真实字段 `id/phoneNumber/lid/admin`；`GroupMetadata` 补 `memberAddMode`、`joinApprovalMode`、`ephemeralDuration`、`inviteViaLink` 和 capability 对象；picture POST 响应补 nullable `avatarUrl`，同路径 GET 响应定义 `groupJid` 和 nullable `avatarUrl`。

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
bash openapi/regenerate-types.sh
```

Expected: YAML/$ref 校验成功，`openapi/generated/types.ts` 更新。

- [ ] **Step 5: 运行协议测试和构建**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer/protocol-layer
npm test -- --runInBand src/routes/groups-detail.test.ts
npm run build
```

Expected: 测试通过，TypeScript build 退出码 0。

- [ ] **Step 6: 提交协议元数据改动**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
git add protocol-layer/src/routes/groups.ts protocol-layer/src/routes/groups-detail.test.ts protocol-layer/src/routes/groups-test-harness.ts openapi/protocol-v1.yaml openapi/generated/types.ts docs/API-CATALOG.md
git commit -m "feat: expose stable group detail metadata"
```

### Task 3: 增加 Armada 群元数据与头像协议端口

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupMetadataResult.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupPictureResult.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupMetadataPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupProfilePort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupProfileAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupProfileAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`

- [ ] **Step 1: 写 metadata/picture adapter 失败测试**

metadata 测试返回 Task 2 的 JSON，并断言：

```java
assertThat(result.subject()).isEqualTo("真实群名");
assertThat(result.memberAddMode()).isTrue();
assertThat(result.joinApprovalMode()).isTrue();
assertThat(result.ephemeralDurationSeconds()).isEqualTo(604800);
assertThat(result.inviteViaLinkSupported()).isFalse();
assertThat(result.participants().get(0).phone()).isEqualTo("8613800000000");
assertThat(result.participants().get(0).owner()).isTrue();
```

picture 测试把协议响应设为 `{"success":true,"avatarUrl":"https://pps.whatsapp.net/new.jpg"}`，断言 port 返回同一 URL。

- [ ] **Step 2: 运行 adapter 测试确认红灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=HttpGroupMetadataAdapterTest,HttpGroupProfileAdapterTest,ProtocolConfigurationTest test
```

Expected: 新类型、port、adapter 或 picture 返回值不存在导致编译/测试失败。

- [ ] **Step 3: 创建稳定的协议领域模型**

`GroupMetadataResult` 使用以下完整字段：

```java
public record GroupMetadataResult(
        String groupJid,
        String subject,
        Boolean announce,
        Boolean restrict,
        Boolean memberAddMode,
        Boolean joinApprovalMode,
        Integer ephemeralDurationSeconds,
        Boolean inviteViaLink,
        boolean inviteViaLinkSupported,
        String inviteViaLinkUnsupportedReason,
        List<GroupParticipantResult> participants) {
}
```

`GroupPictureResult`：

```java
public record GroupPictureResult(boolean applied, String avatarUrl) {
}
```

`GroupMetadataPort`：

```java
public interface GroupMetadataPort {
    GroupMetadataResult getMetadata(String protocolAccountId, String groupJid);
}
```

把 `GroupProfilePort.updatePicture` 的返回值从 `void` 改为 `GroupPictureResult`，并增加只读方法：

```java
String getPictureUrl(String protocolAccountId, String groupJid);
```

现有调用方必须接收或明确忽略 `updatePicture` 返回值，不保留旧重载。`getPictureUrl` 对应 Task 2 的同路径 GET，协议返回 null 时原样返回 null。

- [ ] **Step 4: 实现 HTTP 映射**

`HttpGroupMetadataAdapter.getMetadata` 请求：

```java
MetadataResponse response = httpExecutor.getTyped(
        "/v1/groups/%s/metadata?accountId=%s".formatted(jid, accountId),
        MetadataResponse.class);
List<GroupParticipantResult> participants = response.participants() == null
        ? List.of()
        : response.participants().stream().map(HttpGroupMetadataAdapter::participant).toList();
CapabilityResponse invite = response.capabilities() == null
        ? null
        : response.capabilities().inviteViaLink();
return new GroupMetadataResult(
        response.id(), response.subject(), response.announce(), response.restrict(),
        response.memberAddMode(), response.joinApprovalMode(), response.ephemeralDuration(),
        response.inviteViaLink(), invite != null && invite.supported(),
        invite == null ? "协议未返回能力声明" : invite.reason(), participants);
```

adapter 内部 wire records 和参与者映射完整定义为：

```java
private record MetadataResponse(
        String id,
        String subject,
        Boolean announce,
        Boolean restrict,
        Boolean memberAddMode,
        Boolean joinApprovalMode,
        Integer ephemeralDuration,
        Boolean inviteViaLink,
        CapabilitiesResponse capabilities,
        List<ParticipantResponse> participants) {
}

private record CapabilitiesResponse(CapabilityResponse inviteViaLink) {
}

private record CapabilityResponse(boolean supported, String reason) {
}

private record ParticipantResponse(
        String id,
        String phoneNumber,
        String lid,
        String admin) {
}

private static GroupParticipantResult participant(ParticipantResponse response) {
    String role = blankToNull(response.admin());
    String jid = blankToNull(response.id());
    String phoneSource = blankToNull(response.phoneNumber());
    return new GroupParticipantResult(
            jid,
            phone(phoneSource == null ? jid : phoneSource),
            "admin".equals(role) || "superadmin".equals(role),
            "superadmin".equals(role),
            role);
}

private static String phone(String jid) {
    if (jid == null || jid.isBlank()) {
        return null;
    }
    String normalized = jid.trim();
    int at = normalized.indexOf('@');
    if (at >= 0) {
        normalized = normalized.substring(0, at);
    }
    int device = normalized.indexOf(':');
    if (device >= 0) {
        normalized = normalized.substring(0, device);
    }
    return normalized.isBlank() ? null : normalized;
}

private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
}
```

参与者 phone 优先 `phoneNumber`，其次 `id`；`superadmin` 映射 owner。`HttpGroupProfileAdapter.updatePicture` 改用：

```java
PictureResponse response = httpExecutor.postTyped(
        PICTURE_URI_TEMPLATE.formatted(jid),
        new PictureRequest(accountId, image),
        PictureResponse.class);
return new GroupPictureResult(
        response != null && response.success(),
        response == null ? null : blankToNull(response.avatarUrl()));
```

并增加 `private record PictureResponse(boolean success, String avatarUrl) {}`。

`HttpGroupProfileAdapter.getPictureUrl` 使用 `getTyped` 请求
`/v1/groups/%s/picture?accountId=%s`，读取 `PictureQueryResponse.avatarUrl`；adapter 测试同时覆盖非空 URL 和 null。

- [ ] **Step 5: 注册 bean 并跑绿灯**

在 `ProtocolConfiguration` 注册 `GroupMetadataPort`，然后运行：

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=HttpGroupMetadataAdapterTest,HttpGroupProfileAdapterTest,ProtocolConfigurationTest test
```

Expected: 所有 adapter/configuration 测试通过。

- [ ] **Step 6: 提交 Armada 协议适配**

只暂存本 Task 的 platform/protocol 文件和测试：

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer
git commit -m "feat: add group metadata protocol port"
```

### Task 4: 建立自动选号和群详情聚合 API

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupExecutionAccount.java`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupDetailVO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupTimedMessageMode.java`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupDetailService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`
- Delete: `armada-api/src/main/java/com/armada/group/model/vo/GroupMemberQueryAccount.java`
- Create: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java`

- [ ] **Step 1: 写选号和详情失败测试**

selector 单测：mapper 返回管理员账号时 `find` 返回该账号；mapper 返回空时 `require` 抛 `GROUP_EXECUTOR_UNAVAILABLE`。

detail service 测试：

```java
when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", "本地备注"));
when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
when(selector.find(10L)).thenReturn(Optional.of(new GroupExecutionAccount(7L, "acc_7")));
when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
        .thenReturn(metadata("真实群名", true, true, false, true, 604800));

GroupDetailVO result = service.detail(10L);

assertThat(result.groupName()).isEqualTo("真实群名");
assertThat(result.remark()).isEqualTo("本地备注");
assertThat(result.permissions().editGroupSettings()).isTrue();
assertThat(result.permissions().sendMessages()).isFalse();
assertThat(result.timedMessageMode()).isEqualTo("7d");
assertThat(result.members()).hasSize(1);
```

在同一测试类增加完整 fixture，避免引用其它测试类的 private helper：

```java
private static GroupMetadataResult metadata(
        String subject,
        boolean memberAddMode,
        boolean announce,
        boolean restrict,
        boolean joinApprovalMode,
        int ephemeralSeconds) {
    return new GroupMetadataResult(
            "120363detail@g.us",
            subject,
            announce,
            restrict,
            memberAddMode,
            joinApprovalMode,
            ephemeralSeconds,
            null,
            false,
            "Baileys 7.0.0-rc11 does not expose invite-link access",
            List.of(new GroupParticipantResult(
                    "8613800000000@s.whatsapp.net",
                    "8613800000000",
                    true,
                    true,
                    "superadmin")));
}

private static GroupLink activeLink(Long id, String name, String remark) {
    GroupLink link = new GroupLink();
    link.setId(id);
    link.setGroupName(name);
    link.setRemark(remark);
    return link;
}

private static GroupLinkPreview preview(String groupJid) {
    GroupLinkPreview preview = new GroupLinkPreview();
    preview.setGroupJid(groupJid);
    preview.setAvatarUrl("https://pps.whatsapp.net/current.jpg");
    return preview;
}
```

再写无可用账号和协议异常用例，断言返回本地资料、`liveStateAvailable=false`、实时字段为 null/空列表，不填固定权限。

- [ ] **Step 2: 运行测试确认红灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=GroupExecutionAccountSelectorTest,GroupDetailServiceImplTest,GroupLinkControllerTest test
```

Expected: 新 service/VO/API 不存在。

- [ ] **Step 3: 重命名并封装执行账号选择**

`GroupExecutionAccount`：

```java
public record GroupExecutionAccount(Long accountId, String protocolAccountId) {
}
```

Mapper 方法改为 `selectGroupExecutionAccount`，SQL保持：同租户、membership 未删除、账号未删除、有协议句柄、状态 ONLINE，按 `is_admin DESC, last_seen_at DESC, m.id ASC LIMIT 1`。

selector 公共方法：

```java
@Component
public final class GroupExecutionAccountSelector {

    private final AccountGroupMembershipMapper mapper;

    public GroupExecutionAccountSelector(AccountGroupMembershipMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<GroupExecutionAccount> find(Long groupLinkId) {
        return Optional.ofNullable(mapper.selectGroupExecutionAccount(
                groupLinkId, AccountLoginStateCode.ONLINE));
    }

    public GroupExecutionAccount require(Long groupLinkId) {
        return find(groupLinkId).orElseThrow(() -> new BusinessException(
                ErrorCode.GROUP_EXECUTOR_UNAVAILABLE,
                "没有在线且仍在该群内的账号"));
    }
}
```

在本 Step 同时向 `ErrorCode` 增加执行账号和超时待确认错误；后续所有 mutation 复用，不重复定义：

```java
GROUP_EXECUTOR_UNAVAILABLE(42201, "没有在线且仍在该群内的账号"),
GROUP_PROTOCOL_TIMEOUT(50401, "协议调用超时，操作结果待确认"),
```

本 Task 先从 `GroupLinkServiceImpl.members` 删除旧选号逻辑；真实群名更新仍保留旧路径，到 Task 6 迁移成功后再删除。不要保留旧 record/method。

- [ ] **Step 4: 创建聚合 VO 和 Service**

`GroupDetailVO` 用 nested records 限制文件数量：

```java
public record GroupDetailVO(
        Long groupLinkId,
        String groupJid,
        String groupName,
        String remark,
        String avatarUrl,
        boolean liveStateAvailable,
        String liveStateUnavailableReason,
        String timedMessageMode,
        Permissions permissions,
        Capabilities capabilities,
        boolean membersAvailable,
        String membersUnavailableReason,
        List<GroupLinkMemberVO> members) {

    public record Permissions(
            Boolean editGroupSettings,
            Boolean sendMessages,
            Boolean addMembers,
            Boolean inviteViaLink,
            Boolean adminApproveNewMembers) {
    }

    public record Capabilities(Capability inviteViaLink) {
    }

    public record Capability(boolean supported, String reason) {
    }
}
```

详情读取在本 Task 就需要解析 ephemeral seconds，因此同时创建完整枚举；Task 9 直接复用，不重新定义：

```java
public enum GroupTimedMessageMode {
    /** 关闭消息自动消失。 */
    OFF("off", 0),

    /** 消息保留 24 小时。 */
    HOURS_24("24h", 86_400),

    /** 消息保留 7 天。 */
    DAYS_7("7d", 604_800),

    /** 消息保留 90 天。 */
    DAYS_90("90d", 7_776_000);

    private final String wireValue;
    private final int seconds;

    GroupTimedMessageMode(String wireValue, int seconds) {
        this.wireValue = wireValue;
        this.seconds = seconds;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public int seconds() {
        return seconds;
    }

    @JsonCreator
    public static GroupTimedMessageMode fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(mode -> mode.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的限时消息模式: " + value));
    }

    public static Optional<GroupTimedMessageMode> fromSeconds(Integer seconds) {
        if (seconds == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(mode -> mode.seconds == seconds)
                .findFirst();
    }
}
```

`GroupDetailServiceImpl.detail` 必须：先读本地 link/preview；缺 JID 或账号时返回 local-only；成功时只调用一次 `getMetadata`；使用 `!restrict`、`!announce`、memberAddMode、joinApprovalMode、ephemeral seconds 映射；未知 ephemeral 秒数返回 null，不冒充 off。

核心实现使用以下完整方法边界：

```java
@Override
public GroupDetailVO detail(Long id) {
    GroupTarget target = target(id);
    String localName = firstText(
            target.link().getGroupName(),
            target.preview() == null ? null : target.preview().getWaSubject());
    String avatarUrl = target.preview() == null ? null : target.preview().getAvatarUrl();
    if (target.groupJid() == null) {
        return unavailable(target, localName, avatarUrl,
                "群 JID 未解析，请先预览或等待账号群同步");
    }
    Optional<GroupExecutionAccount> selected = selector.find(id);
    if (selected.isEmpty()) {
        return unavailable(target, localName, avatarUrl,
                "没有在线且仍在该群内的账号");
    }
    try {
        GroupExecutionAccount account = selected.orElseThrow();
        GroupMetadataResult metadata = groupMetadataPort.getMetadata(
                account.protocolAccountId(), target.groupJid());
        List<GroupLinkMemberVO> members = metadata.participants().stream()
                .map(GroupDetailServiceImpl::memberVO)
                .toList();
        return new GroupDetailVO(
                id,
                target.groupJid(),
                firstText(metadata.subject(), localName),
                target.link().getRemark(),
                avatarUrl,
                true,
                null,
                GroupTimedMessageMode.fromSeconds(metadata.ephemeralDurationSeconds())
                        .map(GroupTimedMessageMode::wireValue)
                        .orElse(null),
                new GroupDetailVO.Permissions(
                        invert(metadata.restrict()),
                        invert(metadata.announce()),
                        metadata.memberAddMode(),
                        metadata.inviteViaLink(),
                        metadata.joinApprovalMode()),
                new GroupDetailVO.Capabilities(new GroupDetailVO.Capability(
                        metadata.inviteViaLinkSupported(),
                        metadata.inviteViaLinkUnsupportedReason())),
                true,
                null,
                members);
    } catch (ProtocolException ex) {
        log.warn("群详情实时读取失败 groupLinkId={} code={}", id, ex.errorCode());
        return unavailable(target, localName, avatarUrl, "群实时数据读取失败");
    }
}

private GroupTarget target(Long id) {
    if (id == null || id <= 0) {
        throw new BusinessException(ErrorCode.VALIDATION, "群链接 ID 不能为空");
    }
    GroupLink link = groupLinkMapper.selectActiveById(id);
    if (link == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
    }
    GroupLinkPreview preview = previewMapper.selectByGroupLinkId(id);
    String groupJid = preview == null || preview.getGroupJid() == null
            || preview.getGroupJid().isBlank()
            ? null
            : preview.getGroupJid().trim();
    return new GroupTarget(link, preview, groupJid);
}

private GroupTarget requireLiveTarget(Long id) {
    GroupTarget target = target(id);
    if (target.groupJid() == null) {
        throw new BusinessException(
                ErrorCode.VALIDATION,
                "群链接尚未解析群 JID，请先预览或等待账号群同步");
    }
    return target;
}

private static GroupDetailVO unavailable(
        GroupTarget target,
        String groupName,
        String avatarUrl,
        String reason) {
    return new GroupDetailVO(
            target.link().getId(),
            target.groupJid(),
            groupName,
            target.link().getRemark(),
            avatarUrl,
            false,
            reason,
            null,
            new GroupDetailVO.Permissions(null, null, null, null, null),
            new GroupDetailVO.Capabilities(new GroupDetailVO.Capability(false, reason)),
            false,
            reason,
            List.of());
}

private static Boolean invert(Boolean value) {
    return value == null ? null : !value;
}

private static String firstText(String first, String second) {
    if (first != null && !first.isBlank()) {
        return first.trim();
    }
    return second == null || second.isBlank() ? null : second.trim();
}

private static GroupLinkMemberVO memberVO(GroupParticipantResult participant) {
    return new GroupLinkMemberVO(
            participant.jid(),
            participant.phone(),
            participant.admin(),
            participant.owner(),
            participant.role());
}

private record GroupTarget(GroupLink link, GroupLinkPreview preview, String groupJid) {
}
```

成员刷新沿用同一实时详情来源，不再维护第二套协议映射：

```java
@Override
public GroupLinkMemberListVO members(Long id) {
    GroupDetailVO detail = detail(id);
    if (!detail.membersAvailable()) {
        throw new BusinessException(
                ErrorCode.VALIDATION,
                detail.membersUnavailableReason());
    }
    return new GroupLinkMemberListVO(
            detail.groupLinkId(),
            detail.groupJid(),
            detail.members().size(),
            detail.members());
}
```

- [ ] **Step 5: Controller 新增 detail 并迁移 members**

```java
@GetMapping("/{id}/detail")
public ApiResponse<GroupDetailVO> detail(@PathVariable Long id) {
    return ApiResponse.ok(groupDetailService.detail(id));
}

@GetMapping("/{id}/members")
public ApiResponse<GroupLinkMemberListVO> members(@PathVariable Long id) {
    return ApiResponse.ok(groupDetailService.members(id));
}
```

Controller 构造器新增 `GroupDetailService`。从 `GroupLinkService` 删除 members 方法并从 `GroupLinkServiceImpl` 移走实现，避免原 658 行类继续膨胀。

- [ ] **Step 6: 增加 DbTest 验证排序与隔离**

`GroupExecutionAccountSelectorDbTest` 插入同群普通账号、管理员账号、离线管理员、已软删 membership；断言只返回在线活跃管理员。切换 `TenantContext` 到另一个租户时断言找不到当前租户数据。

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
./dbtest.sh GroupExecutionAccountSelectorDbTest
```

Expected: 真库测试退出码 0。

- [ ] **Step 7: 跑聚合绿灯并提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=GroupExecutionAccountSelectorTest,GroupDetailServiceImplTest,GroupLinkControllerTest,AccountGroupMembershipMapperSqlTest test
```

Expected: 全部通过。

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer
git commit -m "feat: add automatic group detail query"
```

### Task 5: 前端真实加载群详情

**Files:**

- Modify: `wheel-saas-pure-web/src/api/group.ts`
- Modify: `wheel-saas-pure-web/src/api/group.test.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue`
- Create: `wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.test.ts`

- [ ] **Step 1: 写 API 和模板失败测试**

API 测试断言 `getGroupDetail(42)` 请求 `/api/group-links/42/detail` 并正确映射 nested permissions、capability 和 LID/phone 成员。

组件源测试：

```ts
assert.doesNotMatch(source, /editGroupSettings:\s*true/)
assert.doesNotMatch(source, /inviteViaLink:\s*true/)
assert.match(source, /getGroupDetail\(group\.id\)/)
assert.match(source, /permissions\.inviteViaLink/)
assert.doesNotMatch(source, /复制邀请链接|重置邀请链接|添加成员按钮|退出群组/)
```

- [ ] **Step 2: 运行测试确认红灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
```

Expected: `getGroupDetail` 不存在，权限仍用固定值。

- [ ] **Step 3: 收口前端类型和 API**

`GroupDetail` 改为与 Armada 一致：

```ts
export type TimedMessageMode = "off" | "24h" | "7d" | "90d";

export interface GroupPermissionState {
  editGroupSettings: boolean | null;
  sendMessages: boolean | null;
  addMembers: boolean | null;
  inviteViaLink: boolean | null;
  adminApproveNewMembers: boolean | null;
}

export interface GroupDetail {
  groupLinkId: number;
  groupJid: string | null;
  groupName: string | null;
  remark: string | null;
  avatarUrl: string | null;
  liveStateAvailable: boolean;
  liveStateUnavailableReason: string | null;
  timedMessageMode: TimedMessageMode | null;
  permissions: GroupPermissionState;
  capabilities: {
    inviteViaLink: { supported: boolean; reason: string | null };
  };
  membersAvailable: boolean;
  membersUnavailableReason: string | null;
  members: GroupMember[];
}
```

新增 `getGroupDetail`，复用 `toGroupMember` 映射成员。

- [ ] **Step 4: 替换抽屉占位加载**

权限状态初始化全部为 null；打开抽屉调用 `getGroupDetail`，用返回值 hydrate 群名、备注、头像、限时消息、权限和成员。读取失败时保留列表行本地资料并禁用实时控件，不写 true/false fallback。

五个 switch 分别按自身字段增加 null 禁用，例如编辑设置使用 `:disabled="loading || permissions.editGroupSettings == null"`；邀请链接使用 `:disabled="loading || permissions.inviteViaLink == null || !detail?.capabilities.inviteViaLink.supported"`，旁边使用现有文字区域显示 capability reason，不增加新操作入口。

- [ ] **Step 5: 运行前端绿灯和类型检查**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts src/views/group/list/group-member-availability.test.ts
pnpm typecheck
wc -l src/views/group/list/components/GroupMemberDrawer.vue
```

Expected: Node 测试通过、typecheck 退出码 0、组件不超过 600 行。

- [ ] **Step 6: 提交前端详情读取**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
git add src/api/group.ts src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.vue src/views/group/list/components/GroupMemberDrawer.test.ts
git commit -m "feat: load live group drawer detail"
```

### Task 6: 后端自动执行真实群名和 multipart 群头像

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/model/dto/GroupSubjectCommandDTO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupAvatarUpdateVO.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupDetailService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`

- [ ] **Step 1: 写群名、备注边界和头像失败测试**

群名测试请求体只含 `subject`；selector 返回 `acc_7`；断言调用 `groupProfilePort.updateSubject("acc_7", jid, "新群名")` 后才写 `group_link.group_name`。

头像测试使用 `MockMultipartFile("file", "avatar.jpg", "image/jpeg", bytes)`；断言 base64 发往 protocol，返回 URL 时写 preview。再覆盖：非 image、空文件、超过 5 MiB、WhatsApp 成功但 URL 为空时 `applied=true/mirrorSynced=false`。

超时测试必须锁定同一账号回读且不重新选号：群名超时后 metadata subject 已等于目标值则继续写本地镜像；头像超时后当前头像 URL 与旧镜像不同则按 `applied=true` 同步；无法确认时抛 `GROUP_PROTOCOL_TIMEOUT`。

- [ ] **Step 2: 运行测试确认红灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=GroupDetailServiceImplTest,GroupLinkServiceImplTest,GroupLinkControllerTest test
```

- [ ] **Step 3: 修改 DTO 和 Service 契约**

```java
public record GroupSubjectCommandDTO(String subject) {
}

public record GroupAvatarUpdateVO(
        boolean applied,
        boolean mirrorSynced,
        String avatarUrl) {
}
```

`GroupDetailService` 增加：

```java
void updateSubject(Long id, GroupSubjectCommandDTO dto);
GroupAvatarUpdateVO updateAvatar(Long id, MultipartFile file);
```

- [ ] **Step 4: 实现自动选号与镜像顺序**

`updateSubject` 和 `updateAvatar` 使用完整实现；不包跨 HTTP 的数据库事务：

```java
@Override
public void updateSubject(Long id, GroupSubjectCommandDTO dto) {
    if (dto == null || dto.subject() == null || dto.subject().isBlank()) {
        throw new BusinessException(ErrorCode.VALIDATION, "群名称不能为空");
    }
    String subject = dto.subject().trim();
    if (subject.length() > 100) {
        throw new BusinessException(ErrorCode.VALIDATION, "群名称不能超过 100 个字符");
    }
    GroupTarget target = requireLiveTarget(id);
    GroupExecutionAccount account = selector.require(id);
    try {
        groupProfilePort.updateSubject(account.protocolAccountId(), target.groupJid(), subject);
    } catch (ProtocolException ex) {
        if (ex.errorCode() != ProtocolErrorCode.TIMEOUT
                || !subjectConfirmed(account, target.groupJid(), subject)) {
            throw profileMutationFailure(ex);
        }
    }
    if (groupLinkMapper.updateGroupName(id, subject, System.currentTimeMillis()) == 0) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
    }
}

@Override
public GroupAvatarUpdateVO updateAvatar(Long id, MultipartFile file) {
    validateAvatar(file);
    GroupTarget target = requireLiveTarget(id);
    GroupExecutionAccount account = selector.require(id);
    String base64 = Base64.getEncoder().encodeToString(readBytes(file));
    String oldAvatarUrl = target.preview() == null ? null : target.preview().getAvatarUrl();
    GroupPictureResult result;
    try {
        result = groupProfilePort.updatePicture(
                account.protocolAccountId(), target.groupJid(), null, base64);
    } catch (ProtocolException ex) {
        String confirmedUrl = ex.errorCode() == ProtocolErrorCode.TIMEOUT
                ? pictureUrlAfterTimeout(account, target.groupJid(), oldAvatarUrl)
                : null;
        if (confirmedUrl == null) {
            throw profileMutationFailure(ex);
        }
        result = new GroupPictureResult(true, confirmedUrl);
    }
    boolean mirrorSynced = result.avatarUrl() != null
            && previewMapper.upsertAvatarUrl(
                    id, result.avatarUrl(), System.currentTimeMillis()) > 0;
    return new GroupAvatarUpdateVO(result.applied(), mirrorSynced, result.avatarUrl());
}

private static void validateAvatar(MultipartFile file) {
    if (file == null || file.isEmpty()) {
        throw new BusinessException(ErrorCode.VALIDATION, "请选择群头像");
    }
    String contentType = file.getContentType();
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
        throw new BusinessException(ErrorCode.VALIDATION, "只能上传图片文件");
    }
    if (file.getSize() > MAX_AVATAR_BYTES) {
        throw new BusinessException(ErrorCode.VALIDATION, "群头像不能超过 5 MiB");
    }
}

private static byte[] readBytes(MultipartFile file) {
    try {
        return file.getBytes();
    } catch (IOException ex) {
        throw new BusinessException(ErrorCode.VALIDATION, "群头像读取失败");
    }
}

private boolean subjectConfirmed(
        GroupExecutionAccount account,
        String groupJid,
        String expectedSubject) {
    try {
        GroupMetadataResult metadata = groupMetadataPort.getMetadata(
                account.protocolAccountId(), groupJid);
        return expectedSubject.equals(metadata.subject());
    } catch (ProtocolException readEx) {
        log.warn("群名称超时回读失败 code={}", readEx.errorCode());
        return false;
    }
}

private String pictureUrlAfterTimeout(
        GroupExecutionAccount account,
        String groupJid,
        String oldAvatarUrl) {
    try {
        String current = groupProfilePort.getPictureUrl(
                account.protocolAccountId(), groupJid);
        return current == null || current.isBlank() || current.equals(oldAvatarUrl)
                ? null
                : current.trim();
    } catch (ProtocolException readEx) {
        log.warn("群头像超时回读失败 code={}", readEx.errorCode());
        return null;
    }
}

private static BusinessException profileMutationFailure(ProtocolException ex) {
    if (ex.errorCode() == ProtocolErrorCode.TIMEOUT) {
        return new BusinessException(
                ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                "协议调用超时，操作结果待确认，请刷新");
    }
    return new BusinessException(ErrorCode.VALIDATION, "群资料修改失败");
}
```

回读失败只记录协议 code，不记录 base64、完整原始异常或更换账号。Task 10/11 再把通用协议失败细化为稳定的权限码。`MAX_AVATAR_BYTES = 5L * 1024 * 1024`。

- [ ] **Step 5: Controller 接通现有入口**

```java
@PostMapping("/{id}/avatar")
public ApiResponse<GroupAvatarUpdateVO> updateAvatar(
        @PathVariable Long id,
        @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(groupDetailService.updateAvatar(id, file));
}
```

`/{id}/subject` 改委托 `GroupDetailService`，并在同一 Step 从 `GroupLinkService`、`GroupLinkServiceImpl` 及其测试删除旧 `updateSubject`，否则 DTO 去掉 `accountId` 后旧实现无法编译。现有 `/description`、`/announcement-text`、`/picture` 保持兼容，不被抽屉调用。

- [ ] **Step 6: 跑绿灯并提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=GroupDetailServiceImplTest,GroupLinkServiceImplTest,GroupLinkControllerTest test
```

Expected: tests pass。

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer
git commit -m "feat: auto execute group profile updates"
```

### Task 7: 前端群名、备注和头像分别提交

**Files:**

- Modify: `src/api/group.ts`
- Modify: `src/api/group.test.ts`
- Modify: `src/views/group/list/components/GroupMemberDrawer.vue`
- Modify: `src/views/group/list/components/GroupMemberDrawer.test.ts`

- [ ] **Step 1: 写三种 API 调用失败测试**

断言：

```ts
await updateGroupSubject(42, "新群名");
await updateGroupRemark(42, "本地备注");
await uploadGroupAvatar(42, imageFile);
```

分别发往 `/subject`（JSON subject）、`PATCH /42`（只含 remark）、`/avatar`（FormData）。

- [ ] **Step 2: 运行测试确认红灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
```

- [ ] **Step 3: 实现 API 和字段级保存结果**

API 签名：

```ts
export interface GroupAvatarUpdate {
  applied: boolean;
  mirrorSynced: boolean;
  avatarUrl: string | null;
}

export function updateGroupSubject(id: number, subject: string): Promise<void>;
export function updateGroupRemark(id: number, remark: string): Promise<void>;
export function uploadGroupAvatar(id: number, file: File): Promise<GroupAvatarUpdate>;
```

抽屉保存时对实际变化字段构造两个 Promise，用 `Promise.allSettled`；成功字段更新基线，失败字段保留用户输入并显示“群名称保存失败”或“群备注保存失败”。头像 `mirrorSynced=false` 时提示“头像已更新，本地列表待刷新”，不能提示完全失败。

- [ ] **Step 4: 运行定向测试、typecheck 和提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
pnpm typecheck
git commit -m "feat: connect group profile drawer actions"
```

### Task 8: 协议层暴露四档限时消息

**Files:**

- Modify: `protocol-layer/src/routes/groups.ts`
- Modify: `protocol-layer/src/routes/groups-settings.test.ts`
- Modify: `openapi/protocol-v1.yaml`
- Modify (generated): `openapi/generated/types.ts`
- Modify: `docs/API-CATALOG.md`

- [ ] **Step 1: 写四档映射失败测试**

在 `groups-settings.test.ts` 使用 table test：

```ts
it.each([
  ['off', 0],
  ['24h', 86400],
  ['7d', 604800],
  ['90d', 7776000]
])('maps ephemeral mode %s to %i seconds', async (mode, seconds) => {
  const groupToggleEphemeral = jest.fn(async () => undefined)
  const app = buildGroupTestApp({ groupToggleEphemeral })
  const response = await app.inject({
    method: 'POST',
    url: '/v1/groups/120363detail@g.us/settings/ephemeral',
    payload: { accountId: 'acc_100', mode }
  })
  expect(response.statusCode).toBe(200)
  expect(groupToggleEphemeral).toHaveBeenCalledWith('120363detail@g.us', seconds)
  await app.close()
})
```

`groups-settings.test.ts` 从 `groups-test-harness.ts` 导入 `buildGroupTestApp`，并从 `@jest/globals` 导入 `jest`。

- [ ] **Step 2: 运行红灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer/protocol-layer
npm test -- --runInBand src/routes/groups-settings.test.ts
```

Expected: route not found。

- [ ] **Step 3: 实现 route**

```ts
const EphemeralMode = z.enum(['off', '24h', '7d', '90d'])
const EPHEMERAL_SECONDS = { off: 0, '24h': 86400, '7d': 604800, '90d': 7776000 } as const

app.post('/v1/groups/:groupJid/settings/ephemeral', async (req, reply) => {
  const { groupJid } = GroupJidParam.parse(req.params)
  const { accountId, mode } = z.object({ accountId: z.string(), mode: EphemeralMode }).parse(req.body)
  await ctx.operationGate.runGroup(accountId, 'group.settings.ephemeral', async () => {
    const sock = ctx.accounts.getSocket(accountId)
    await sock.groupToggleEphemeral(groupJid, EPHEMERAL_SECONDS[mode])
  })
  reply.send({ success: true, mode, ephemeralDuration: EPHEMERAL_SECONDS[mode] })
})
```

- [ ] **Step 4: 更新 OpenAPI、跑绿灯、构建和提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
bash openapi/regenerate-types.sh
cd protocol-layer
npm test -- --runInBand src/routes/groups-settings.test.ts
npm run build
git commit -m "feat: expose group ephemeral settings"
```

### Task 9: Armada 和前端接通限时消息

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupTimedMessageCommandDTO.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupDetailService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupSettingsPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`
- Modify: `src/api/group.ts`
- Modify: `src/api/group.test.ts`
- Modify: `src/views/group/list/constants.ts`
- Modify: `src/views/group/list/components/GroupMemberDrawer.vue`
- Modify: `src/views/group/list/components/GroupMemberDrawer.test.ts`

- [ ] **Step 1: 写后端 adapter/service/controller 红灯测试**

adapter 断言 `mode=7d` 发往 `/settings/ephemeral`；service 断言自动选择 `acc_7`，成功后调用 `getMetadata` 并返回确认后的 `7d`；如果回读仍不是 7 天，抛 `GROUP_PROTOCOL_TIMEOUT`。再覆盖设置调用 TIMEOUT 后用同一 `acc_7` 立即回读：实际已是 7 天则视为成功，否则抛待确认错误，selector 不得再次调用。

- [ ] **Step 2: 创建限时模式和设置 port**

```java
public record GroupTimedMessageCommandDTO(GroupTimedMessageMode mode) {
}

public interface GroupSettingsPort {
    void setEphemeralDuration(String protocolAccountId, String groupJid, int durationSeconds);
}
```

复用 Task 4 已创建的 `GroupTimedMessageMode`。业务 Service 把 `dto.mode().seconds()` 传给 port；platform adapter 只依赖整数秒数并映射成协议 wire mode，禁止 platform 反向依赖 group 业务枚举。

`HttpGroupSettingsAdapter.setEphemeralDuration`：

```java
@Override
public void setEphemeralDuration(
        String protocolAccountId,
        String groupJid,
        int durationSeconds) {
    String mode = switch (durationSeconds) {
        case 0 -> "off";
        case 86_400 -> "24h";
        case 604_800 -> "7d";
        case 7_776_000 -> "90d";
        default -> throw new ProtocolException(
                ProtocolErrorCode.BAD_REQUEST,
                "不支持的群限时消息秒数: " + durationSeconds);
    };
    postMode(protocolAccountId, groupJid, "/settings/ephemeral", mode);
}
```

- [ ] **Step 3: 实现后端 API 并跑绿灯**

```java
@PostMapping("/{id}/timed-message")
public ApiResponse<Void> updateTimedMessage(
        @PathVariable Long id,
        @RequestBody GroupTimedMessageCommandDTO dto) {
    groupDetailService.updateTimedMessage(id, dto);
    return ApiResponse.ok();
}
```

Service 对正常响应和 TIMEOUT 都最终调用同一个 `confirmTimedMessage(account, groupJid, expectedSeconds)`；该方法读取 metadata 并比较 `ephemeralDurationSeconds`。TIMEOUT 后确认一致即返回，不一致或回读再次失败统一抛 Task 4 的 `GROUP_PROTOCOL_TIMEOUT`，不选择第二个账号。

```java
private void confirmTimedMessage(
        GroupExecutionAccount account,
        String groupJid,
        int expectedSeconds) {
    GroupMetadataResult metadata;
    try {
        metadata = groupMetadataPort.getMetadata(account.protocolAccountId(), groupJid);
    } catch (ProtocolException ex) {
        throw new BusinessException(
                ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                "限时消息设置结果待确认，请刷新");
    }
    if (!Integer.valueOf(expectedSeconds).equals(metadata.ephemeralDurationSeconds())) {
        throw new BusinessException(
                ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                "限时消息设置结果待确认，请刷新");
    }
}
```

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=HttpGroupSettingsAdapterTest,GroupDetailServiceImplTest,GroupLinkControllerTest,ProtocolConfigurationTest test
git commit -m "feat: connect group timed messages"
```

- [ ] **Step 4: 写前端 API/抽屉红灯测试并实现**

API 使用完整实现：

```ts
export function updateTimedMessage(
  id: number,
  mode: TimedMessageMode
): Promise<void> {
  return armadaRequest<void>("post", `/api/group-links/${id}/timed-message`, {
    data: { mode }
  });
}
```

`onTimedMessageChange(mode)` 保存旧值，失败恢复旧值，成功后重新 `getGroupDetail`；提交中禁用 radio group。

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
pnpm typecheck
git commit -m "feat: connect group timed message control"
```

### Task 10: 归一协议群管理权限错误并锁定四项稳定权限

**Files:**

- Modify: `protocol-layer/src/routes/groups.ts`
- Modify: `protocol-layer/src/routes/groups-settings.test.ts`
- Modify: `openapi/protocol-v1.yaml`
- Modify (generated): `openapi/generated/types.ts`

- [ ] **Step 1: 写四项设置和无权限失败测试**

分别断言：

- announcement: `true -> not_announcement`, `false -> announcement` 由上游 adapter 转换，route 接受协议 mode。
- locked: `unlocked/locked`。
- member-add-mode: `all_member_add/admin_add`。
- join-approval: `on/off`。
- socket 抛 `not-authorized` 或 `forbidden` 时，HTTP 403 且 code 为 `GROUP_PERMISSION_DENIED`。

- [ ] **Step 2: 运行红灯并实现错误归一**

在 `groups.ts` 增加：

```ts
async function runGroupAdminMutation<T>(
  accountId: string,
  groupJid: string,
  action: string,
  operation: () => Promise<T>
): Promise<T> {
  try {
    return await operation()
  } catch (error) {
    const message = rawErrorMessage(error).toLowerCase()
    if (message.includes('not-authorized') || message.includes('forbidden') || message.includes('not admin')) {
      throw new ProtocolError(403, 'GROUP_PERMISSION_DENIED',
        `account ${accountId} cannot perform ${action}`, { accountId, groupJid })
    }
    throw error
  }
}
```

subject、picture、ephemeral、四项稳定设置、promote/demote/remove 的 socket 调用都经过该 helper；operationGate 仍包在外层。

- [ ] **Step 3: 更新 OpenAPI 并验证**

所有相关 mutation route 增加 403 `GROUP_PERMISSION_DENIED` 响应；修正 demote 段重复的 `application/json` 键。

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
bash openapi/regenerate-types.sh
cd protocol-layer
npm test -- --runInBand src/routes/groups-settings.test.ts
npm run build
git commit -m "fix: normalize group permission errors"
```

### Task 11: Armada 和前端接通四项稳定群权限

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupPermissionKey.java`
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupSettingCommandDTO.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupDetailService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupSettingsPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolErrorCode.java`
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapterTest.java`
- Modify: `src/api/group.ts`
- Modify: `src/api/group.test.ts`
- Modify: `src/views/group/list/components/GroupMemberDrawer.vue`
- Modify: `src/views/group/list/components/GroupMemberDrawer.test.ts`

- [ ] **Step 1: 写后端权限映射失败测试**

使用 `MockRestServiceServer` 锁定映射，不测试 adapter 的 private helper：

```java
server.expect(requestTo("http://protocol-master.internal/v1/groups/120363detail@g.us/settings/locked"))
        .andExpect(content().json("{\"accountId\":\"acc_7\",\"mode\":\"unlocked\"}"))
        .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
port.setEditGroupSettingsAllowed("acc_7", "120363detail@g.us", true);

server.expect(requestTo("http://protocol-master.internal/v1/groups/120363detail@g.us/settings/announcement"))
        .andExpect(content().json("{\"accountId\":\"acc_7\",\"mode\":\"announcement\"}"))
        .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
port.setSendMessagesAllowed("acc_7", "120363detail@g.us", false);

server.expect(requestTo("http://protocol-master.internal/v1/groups/120363detail@g.us/settings/member-add-mode"))
        .andExpect(content().json("{\"accountId\":\"acc_7\",\"mode\":\"all_member_add\"}"))
        .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
port.setAddMembersAllowed("acc_7", "120363detail@g.us", true);

server.expect(requestTo("http://protocol-master.internal/v1/groups/120363detail@g.us/settings/join-approval"))
        .andExpect(content().json("{\"accountId\":\"acc_7\",\"mode\":\"on\"}"))
        .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
port.setJoinApprovalEnabled("acc_7", "120363detail@g.us", true);
server.verify();
```

service 测试还要断言 `GROUP_PERMISSION_DENIED` 映射成 `ErrorCode.GROUP_PERMISSION_DENIED`；TIMEOUT 后使用同一账号回读，匹配时成功、不匹配或回读失败时映射成 `GROUP_PROTOCOL_TIMEOUT`，且 selector 始终只调用一次。

- [ ] **Step 2: 创建权限契约和错误码**

```java
public enum GroupPermissionKey {
    EDIT_GROUP_SETTINGS,
    SEND_MESSAGES,
    ADD_MEMBERS,
    INVITE_VIA_LINK,
    ADMIN_APPROVE_NEW_MEMBERS
}

public record GroupSettingCommandDTO(GroupPermissionKey key, boolean enabled) {
}
```

在 Task 9 的 `GroupSettingsPort` 上增加五个显式权限方法；port 不接收 group 业务枚举：

```java
void setEditGroupSettingsAllowed(String protocolAccountId, String groupJid, boolean enabled);

void setSendMessagesAllowed(String protocolAccountId, String groupJid, boolean enabled);

void setAddMembersAllowed(String protocolAccountId, String groupJid, boolean enabled);

void setInviteViaLinkAllowed(String protocolAccountId, String groupJid, boolean enabled);

void setJoinApprovalEnabled(String protocolAccountId, String groupJid, boolean enabled);
```

`ProtocolErrorCode` 增加 `GROUP_PERMISSION_DENIED`、`GROUP_CAPABILITY_UNSUPPORTED`；`ErrorCode` 增加：

```java
GROUP_PERMISSION_DENIED(40301, "执行账号没有管理员权限"),
GROUP_CAPABILITY_UNSUPPORTED(42202, "当前 WhatsApp/协议版本不支持该设置"),
GROUP_MEMBER_NOT_FOUND(40402, "目标成员已不在群内"),
GROUP_OWNER_PROTECTED(40902, "群主不能被降级或踢出"),
GROUP_OPERATION_PARTIAL(20701, "部分成员操作成功，部分失败"),
```

`HttpGroupSettingsAdapter.setInviteViaLinkAllowed` 在 Task 12 尚未确认真实 route 前必须明确失败：

```java
@Override
public void setInviteViaLinkAllowed(
        String protocolAccountId,
        String groupJid,
        boolean enabled) {
    throw new ProtocolException(
            ProtocolErrorCode.GROUP_CAPABILITY_UNSUPPORTED,
            "当前协议版本未暴露通过链接邀请权限");
}
```

其它四项权限方法使用同一 private helper，但每个 public method 显式给出业务语义：

```java
@Override
public void setEditGroupSettingsAllowed(
        String protocolAccountId, String groupJid, boolean enabled) {
    postMode(protocolAccountId, groupJid, "/settings/locked",
            enabled ? "unlocked" : "locked");
}

@Override
public void setSendMessagesAllowed(
        String protocolAccountId, String groupJid, boolean enabled) {
    postMode(protocolAccountId, groupJid, "/settings/announcement",
            enabled ? "not_announcement" : "announcement");
}

@Override
public void setAddMembersAllowed(
        String protocolAccountId, String groupJid, boolean enabled) {
    postMode(protocolAccountId, groupJid, "/settings/member-add-mode",
            enabled ? "all_member_add" : "admin_add");
}

@Override
public void setJoinApprovalEnabled(
        String protocolAccountId, String groupJid, boolean enabled) {
    postMode(protocolAccountId, groupJid, "/settings/join-approval",
            enabled ? "on" : "off");
}

private void postMode(
        String protocolAccountId,
        String groupJid,
        String suffix,
        String mode) {
    String accountId = requireText(protocolAccountId, "protocolAccountId");
    String jid = requireText(groupJid, "groupJid");
    httpExecutor.postVoid(
            "/v1/groups/%s%s".formatted(jid, suffix),
            new ModeRequest(accountId, mode));
}

private record ModeRequest(String accountId, String mode) {
}
```

- [ ] **Step 3: 实现后端设置并回读确认**

```java
public void updateSetting(Long id, GroupSettingCommandDTO dto) {
    if (dto == null || dto.key() == null) {
        throw new BusinessException(ErrorCode.VALIDATION, "群权限设置不能为空");
    }
    GroupTarget target = requireLiveTarget(id);
    GroupExecutionAccount account = selector.require(id);
    try {
        if (dto.key() == GroupPermissionKey.INVITE_VIA_LINK) {
            GroupMetadataResult current = groupMetadataPort.getMetadata(
                    account.protocolAccountId(), target.groupJid());
            if (!current.inviteViaLinkSupported()) {
                throw new BusinessException(
                        ErrorCode.GROUP_CAPABILITY_UNSUPPORTED,
                        current.inviteViaLinkUnsupportedReason());
            }
        }
        switch (dto.key()) {
            case EDIT_GROUP_SETTINGS -> groupSettingsPort.setEditGroupSettingsAllowed(
                    account.protocolAccountId(), target.groupJid(), dto.enabled());
            case SEND_MESSAGES -> groupSettingsPort.setSendMessagesAllowed(
                    account.protocolAccountId(), target.groupJid(), dto.enabled());
            case ADD_MEMBERS -> groupSettingsPort.setAddMembersAllowed(
                    account.protocolAccountId(), target.groupJid(), dto.enabled());
            case INVITE_VIA_LINK -> groupSettingsPort.setInviteViaLinkAllowed(
                    account.protocolAccountId(), target.groupJid(), dto.enabled());
            case ADMIN_APPROVE_NEW_MEMBERS -> groupSettingsPort.setJoinApprovalEnabled(
                    account.protocolAccountId(), target.groupJid(), dto.enabled());
        }
        GroupMetadataResult confirmed = groupMetadataPort.getMetadata(
                account.protocolAccountId(), target.groupJid());
        requireConfirmedPermission(confirmed, dto.key(), dto.enabled());
    } catch (ProtocolException ex) {
        if (ex.errorCode() == ProtocolErrorCode.TIMEOUT) {
            try {
                GroupMetadataResult confirmed = groupMetadataPort.getMetadata(
                        account.protocolAccountId(), target.groupJid());
                requireConfirmedPermission(confirmed, dto.key(), dto.enabled());
                return;
            } catch (ProtocolException readEx) {
                throw new BusinessException(
                        ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                        "群设置结果待确认，请刷新");
            }
        }
        throw groupBusinessException(ex);
    }
}
```

上段中的 target 方法统一使用 Task 4 已定义的 `requireLiveTarget(id)`。确认和错误映射必须在同一 service 中完整定义：

```java
private static void requireConfirmedPermission(
        GroupMetadataResult metadata,
        GroupPermissionKey key,
        boolean expected) {
    Boolean actual = switch (key) {
        case EDIT_GROUP_SETTINGS -> invert(metadata.restrict());
        case SEND_MESSAGES -> invert(metadata.announce());
        case ADD_MEMBERS -> metadata.memberAddMode();
        case INVITE_VIA_LINK -> metadata.inviteViaLink();
        case ADMIN_APPROVE_NEW_MEMBERS -> metadata.joinApprovalMode();
    };
    if (!Boolean.valueOf(expected).equals(actual)) {
        throw new BusinessException(
                ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                "设置请求已返回，但重新读取的 WhatsApp 状态不一致");
    }
}

private static BusinessException groupBusinessException(ProtocolException ex) {
    return switch (ex.errorCode()) {
        case GROUP_PERMISSION_DENIED -> new BusinessException(
                ErrorCode.GROUP_PERMISSION_DENIED,
                "执行账号没有管理员权限");
        case GROUP_CAPABILITY_UNSUPPORTED -> new BusinessException(
                ErrorCode.GROUP_CAPABILITY_UNSUPPORTED,
                "当前 WhatsApp/协议版本不支持该设置");
        case TIMEOUT -> new BusinessException(
                ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                "协议调用超时，操作结果待确认");
        default -> new BusinessException(
                ErrorCode.VALIDATION,
                "群设置修改失败");
    };
}
```

Controller POST `/settings` 委托该方法。`INVITE_VIA_LINK` 在 capability false 时必须抛 unsupported，不调用任何其它 setting route。

- [ ] **Step 4: 跑后端绿灯并提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=HttpGroupSettingsAdapterTest,GroupDetailServiceImplTest,GroupLinkControllerTest,ProtocolConfigurationTest test
git commit -m "feat: connect group permission settings"
```

- [ ] **Step 5: 前端替换权限 placeholder**

API：

```ts
export type GroupPermissionKey =
  | "EDIT_GROUP_SETTINGS"
  | "SEND_MESSAGES"
  | "ADD_MEMBERS"
  | "INVITE_VIA_LINK"
  | "ADMIN_APPROVE_NEW_MEMBERS";

export function updateGroupSetting(
  id: number,
  key: GroupPermissionKey,
  enabled: boolean
): Promise<void> {
  return armadaRequest<void>("post", `/api/group-links/${id}/settings`, {
    data: { key, enabled }
  });
}
```

`togglePermission` 保存旧值 → 乐观切换 → 调单项 API → 重新加载详情；失败恢复旧值并显示后端消息。为五个页面字段建立固定 key map，不能用字符串拼接猜 key。

- [ ] **Step 6: 跑前端绿灯并提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
pnpm typecheck
git commit -m "feat: connect group permission switches"
```

### Task 12: 完成“通过链接邀请”能力门禁和测试环境验证

**Files:**

- Modify: `.harness/changes/2026-07-15-group-detail-drawer-completion.md`
- Modify: `protocol-layer/src/routes/groups-detail.test.ts`
- Modify: `protocol-layer/src/routes/groups-settings.test.ts`
- Conditional modify: `protocol-layer/src/routes/groups.ts`
- Conditional modify: `openapi/protocol-v1.yaml`
- Conditional modify (generated): `openapi/generated/types.ts`
- Conditional modify: `protocol-layer/patches/baileys+7.0.0-rc11.patch`
- Conditional modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `src/views/group/list/components/GroupMemberDrawer.test.ts`

- [ ] **Step 1: 先锁定本地 unsupported 行为**

在未取得 wire 证据前，必须满足：metadata `supported=false/value=null`；Armada detail 原样返回；前端禁用开关并显示原因；点击路径无法调用其它设置。运行三仓相关测试，Expected: 全部通过。

- [ ] **Step 2: 请求并记录测试环境确认**

执行人员在 `.harness/changes/2026-07-15-group-detail-drawer-completion.md` 记录：目标环境、测试账号 Armada ID、协议账号 ID、测试群 JID、账号管理员身份和用户授权时间。未取得这些信息时停止远程动作，但其它 Slice 可以继续。

- [ ] **Step 3: 在确认环境做只读能力探测**

先读取官方客户端中的当前开关状态，再通过测试 worker 对同一群执行交互式 metadata query，保存脱敏后的原始 group node 结构和 Baileys 解析结果。验收只看以下事实：

1. 是否存在独立于 `member_add_mode` 和 `membership_approval_mode` 的字段。
2. 字段 true/false 是否与客户端状态一致。
3. 当前 Baileys 是否已有未公开方法可安全复用。

不得把 heap snapshot、creds、手机号或完整群 JID提交进仓库。

- [ ] **Step 4A: 没有可靠读写证据时保持 unsupported**

在 change 记录写明探测时间、版本和“未确认 wire 契约”；不修改 Baileys patch。运行本地 unsupported 测试并提交记录：

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer/protocol-layer
npm test -- --runInBand src/routes/groups-detail.test.ts src/routes/groups-settings.test.ts
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=GroupDetailServiceImplTest,HttpGroupSettingsAdapterTest test
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer
git add -- .harness/changes/2026-07-15-group-detail-drawer-completion.md
git commit -m "docs: record invite link capability probe"
```

- [ ] **Step 4B: 读写证据完整时实施真实能力**

先在 `groups-settings.test.ts` 写红灯：metadata 解析真实字段、设置 route 调用经过证据确认的 socket 方法、设置后回读一致。然后最小修改 `baileys+7.0.0-rc11.patch`、`groups.ts`、OpenAPI 和 adapter；运行 protocol route/Jest、OpenAPI 生成、TypeScript build、Armada adapter/service tests 和前端 API/template tests。只有全部通过才把 capability 改为 `supported=true`。

三个仓库分别验证并提交，不能把跨仓路径写进同一个 commit：

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
bash openapi/regenerate-types.sh
cd protocol-layer
npm test -- --runInBand src/routes/groups-detail.test.ts src/routes/groups-settings.test.ts
npm run build
cd ..
git add -- protocol-layer/patches/baileys+7.0.0-rc11.patch protocol-layer/src/routes/groups.ts protocol-layer/src/routes/groups-detail.test.ts protocol-layer/src/routes/groups-settings.test.ts openapi/protocol-v1.yaml openapi/generated/types.ts
git commit -m "feat: support group invite link access permission"

cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=GroupDetailServiceImplTest,HttpGroupSettingsAdapterTest test
cd ..
git add -- .harness/changes/2026-07-15-group-detail-drawer-completion.md armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapterTest.java
git commit -m "feat: enable confirmed group invite link capability"

cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
git add -- src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
git commit -m "test: enable confirmed group invite link capability"
```

### Task 13: 锁定协议成员升降管理员和踢人结果

**Files:**

- Modify: `protocol-layer/src/routes/groups.ts`
- Modify: `protocol-layer/src/routes/groups-participants-mutation.test.ts`
- Modify: `openapi/protocol-v1.yaml`
- Modify (generated): `openapi/generated/types.ts`

- [ ] **Step 1: 写三类操作、部分结果和权限失败测试**

```ts
it.each([
  ['promote', 'promote'],
  ['demote', 'demote'],
  ['remove', 'remove']
])('runs participant %s and preserves per-jid result', async (path, action) => {
  const groupParticipantsUpdate = jest.fn(async () => [{
    jid: '8613800000000@s.whatsapp.net',
    status: '200'
  }])
  const app = buildGroupTestApp({ groupParticipantsUpdate })
  const response = await app.inject({
    method: 'POST',
    url: `/v1/groups/120363detail@g.us/participants/${path}`,
    payload: {
      accountId: 'acc_100',
      participants: ['8613800000000@s.whatsapp.net'],
      timeoutMs: 30000
    }
  })
  expect(groupParticipantsUpdate).toHaveBeenCalledWith(
    '120363detail@g.us',
    ['8613800000000@s.whatsapp.net'],
    action
  )
  expect(response.json().results[0]).toMatchObject({
    jid: '8613800000000@s.whatsapp.net',
    status: 'OK',
    rawStatus: '200'
  })
  await app.close()
})
```

`groups-participants-mutation.test.ts` 导入同一 harness 和 Jest `jest`，不再各自复制 Fastify/RouteContext 装配。

再覆盖返回数组短于请求列表时 `partial=true`、timeout 时 `partial=true`、not-authorized 时 403。

- [ ] **Step 2: 运行红灯、修正结果规范并跑绿灯**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer/protocol-layer
npm test -- --runInBand src/routes/groups-participants-mutation.test.ts
```

如果当前 Baileys 返回字段不是 `jid`，在 `normalizeParticipantResults` 中从 `jid/content.attrs.jid/participant` 依次取稳定 JID；缺失成员由 Armada 补 UNKNOWN，不在协议层伪造成功。

- [ ] **Step 3: 更新 OpenAPI、构建和提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
bash openapi/regenerate-types.sh
cd protocol-layer
npm run build
git commit -m "test: lock group participant mutation results"
```

### Task 14: Armada 和前端接通成员批量操作

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/enums/GroupParticipantAction.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupParticipantBatchResult.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java`
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupMemberBatchCommandDTO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupMemberOperationResultVO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupMemberBatchResultVO.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupDetailService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`
- Modify: `src/api/group.ts`
- Modify: `src/api/group.test.ts`
- Modify: `src/views/group/list/components/GroupMemberDrawer.vue`
- Modify: `src/views/group/list/components/GroupMemberDrawer.test.ts`

- [ ] **Step 1: 写 adapter 和 service 红灯测试**

adapter 分别断言 promote/demote/remove URI、单一执行账号、jids 和 timeout。service 测试：

- selector 只调用一次。
- 同一账号先取 metadata，再对非群主成员执行 mutation。
- 群主结果为 `OWNER_PROTECTED`，不发送到协议层。
- 协议只返回部分 JID 时，缺失项补 `UNKNOWN`。
- 整批 TIMEOUT 后用同一账号重新读取 metadata：已达到目标角色/已移出群的成员记 `OK`，其余记 `UNKNOWN`，不得换号重试。
- 成功项不因其它成员失败而回滚。
- 权限不足不再次调用 selector。

- [ ] **Step 2: 创建端口模型**

```java
public enum GroupParticipantAction {
    /** 将普通成员提升为管理员。 */
    PROMOTE("promote"),

    /** 将管理员降为普通成员。 */
    DEMOTE("demote"),

    /** 将成员移出群组。 */
    REMOVE("remove");

    private final String wireValue;

    GroupParticipantAction(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}

public record GroupParticipantBatchResult(
        boolean partial,
        List<Item> results) {
    public record Item(String jid, String status, String rawStatus) {
    }
}
```

`GroupParticipantPort` 增加：

```java
GroupParticipantBatchResult updateParticipants(
        String protocolAccountId,
        String groupJid,
        List<String> participants,
        GroupParticipantAction action);
```

adapter POST `/participants/{wireValue}`，body 固定 timeout 30000。

- [ ] **Step 3: 创建业务 DTO/VO 和 Service 方法**

```java
public record GroupMemberBatchCommandDTO(List<String> jids) {
}

public record GroupMemberOperationResultVO(
        String jid,
        String status,
        String reason) {
}

public record GroupMemberBatchResultVO(
        boolean ok,
        boolean partial,
        String message,
        List<GroupMemberOperationResultVO> results) {
}
```

`GroupDetailService` 增加 `promoteMembers/demoteMembers/kickMembers`。私有 `updateMembers` 校验 1..50、去重、同一 selector、metadata 群主保护、协议结果合并；不返回 null。若 participant mutation 抛 TIMEOUT，立即用同一 `protocolAccountId` 再取 metadata：promote 以 `admin/owner=true`、demote 以 `admin/owner=false`、remove 以 JID 不再存在作为确认条件；无法确认的项返回 `UNKNOWN` 和“操作结果待确认，请刷新”，不把它们伪造成失败或换号重试。

- [ ] **Step 4: Controller 增加现有前端路径**

```java
@PostMapping("/{id}/members/promote-batch")
public ApiResponse<GroupMemberBatchResultVO> promoteMembers(
        @PathVariable Long id,
        @RequestBody GroupMemberBatchCommandDTO dto) {
    return ApiResponse.ok(groupDetailService.promoteMembers(id, dto));
}
```

demote/kick 使用对应 service 方法，路径严格匹配现有 frontend API。

- [ ] **Step 5: 跑后端绿灯并提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=HttpGroupParticipantAdapterTest,GroupDetailServiceImplTest,GroupLinkControllerTest,ProtocolConfigurationTest test
git commit -m "feat: connect group member management"
```

- [ ] **Step 6: 前端展示逐项结果**

扩展 `GroupMemberOpResult.results` 为 `{jid,status,reason}`。表格 selection column 加：

```vue
<el-table-column
  type="selection"
  width="46"
  :selectable="row => !row.locked"
/>
```

成功后刷新成员；`partial=true` 时用现有 `ElMessageBox.alert` 展示失败 JID/号码和原因，不新增持久按钮、tab 或 section。权限错误直接显示 Armada message。

- [ ] **Step 7: 跑前端绿灯并提交**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
pnpm typecheck
git commit -m "feat: connect group member drawer actions"
```

### Task 15: 三仓全量验证、真群验收与记录收口

**Files:**

- Modify: `.harness/changes/2026-07-15-group-detail-drawer-completion.md`
- Modify: `docs/API-CATALOG.md`
- Modify: `.harness/changes/group-list-frontend/summary.md`
- Modify only if verification exposes a defect: the exact production/test file from Tasks 1–14 that owns that defect

- [ ] **Step 1: 协议层完整验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-protocol-group-detail-drawer
bash openapi/regenerate-types.sh
cd protocol-layer
npm test -- --runInBand src/master-gateway/routing.test.ts src/master-gateway/register.test.ts src/routes/groups-detail.test.ts src/routes/groups-settings.test.ts src/routes/groups-participants-mutation.test.ts
npm run lint
npm run build
```

Expected: Jest 全部通过，OpenAPI 生成/校验、lint、build 退出码 0。

- [ ] **Step 2: Armada 单测、真库和编译**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-group-detail-drawer/armada-api
mvn -q -DforkCount=0 -Dtest=GroupExecutionAccountSelectorTest,GroupDetailServiceImplTest,GroupLinkControllerTest,HttpGroupMetadataAdapterTest,HttpGroupProfileAdapterTest,HttpGroupSettingsAdapterTest,HttpGroupParticipantAdapterTest,ProtocolConfigurationTest test
./dbtest.sh GroupExecutionAccountSelectorDbTest
mvn -q -Dmaven.test.skip=true compile
```

Expected: 单测 0 failure/error、DbTest 退出码 0、compile 退出码 0。

- [ ] **Step 3: 前端定向测试和质量门禁**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-saas-pure-web-group-detail-drawer
node --test src/api/group.test.ts src/views/group/list/group-member-availability.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
pnpm typecheck
pnpm exec eslint --max-warnings 0 src/api/group.ts src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.vue src/views/group/list/components/GroupMemberDrawer.test.ts
pnpm exec prettier --check src/api/group.ts src/api/group.test.ts src/views/group/list/components/GroupMemberDrawer.vue src/views/group/list/components/GroupMemberDrawer.test.ts
pnpm build
```

Expected: Node tests、typecheck、ESLint、Prettier、build 全部退出码 0。

- [ ] **Step 4: 本地页面冒烟**

启动 Armada、protocol worker/master 和前端后，用浏览器打开群组列表：抽屉入口数量与实施前一致；无固定权限闪烁；本地资料在协议不可用时仍可见；unsupported 开关禁用并显示原因。

- [ ] **Step 5: 经确认后做 WhatsApp 真群逐项验收**

按详情 → 群名/头像/备注 → 限时消息 → 四项稳定权限 → 邀请链接 capability → 升降管理员/踢人顺序执行。每次修改后同时核对 WhatsApp 客户端和重新打开的抽屉。另用无管理员权限账号验证明确报错且没有换号重试。

- [ ] **Step 6: 更新变更记录并提交文档**

Armada change 记录填写每条命令的真实输出摘要、测试环境、验收结果、各仓 commit；前端 `.harness/changes/group-list-frontend/summary.md` 更新真实接入状态；协议文档同步新增 endpoint/capability。

三个仓库分别 `git diff --check`、`git status --short`，确认只提交本功能文件，然后各自提交文档，不执行部署。

---

## 2. 规格覆盖自检

| 规格要求 | 实施任务 |
|---|---|
| 严格保留当前抽屉入口 | Tasks 5、7、9、11、14 的模板测试 |
| Armada 自动选择在线、在群、优先管理员 | Task 4 selector unit + DbTest |
| 权限不足不换号 | Tasks 10、11、14 |
| 群名称/头像真实修改并同步镜像，备注仅本地 | Tasks 6、7 |
| 四档限时消息 | Tasks 8、9 |
| 五项权限真实状态/能力门禁 | Tasks 2、10、11、12 |
| 成员实时角色和批量部分成功 | Tasks 2、4、13、14 |
| 无新增数据库持久化 | 文件清单与 Task 15 验证 |
| 三仓测试及真群验收 | Task 15 |

计划中的类型、字段和 endpoint 名称在后续任务中均引用首次定义的同一名称；没有重复旧接口 shim、假成功分支或范围外 UI。
