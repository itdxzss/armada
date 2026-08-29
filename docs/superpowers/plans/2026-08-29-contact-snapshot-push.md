# 通讯录快照推送 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把账号通讯录从「armada 按需 HTTP 拉」改成「协议层定期推全量快照」，同时修掉现有投影漏采 LID 号与批量来源的问题，让 `synced_at` 第一次记录真实的数据新鲜度、让删除能够收敛。

**Architecture:** 协议层清掉 `critical_unblock_low` 的 app-state 版本号并强制 resync，WhatsApp 服务端因此返回全量快照，Baileys 为其中每条记录重放一次 `contacts.upsert`。协议层用一份干净 store 收齐这些事件，打上真实的 `snapshotCutoff`，分片经 Kafka 推给 armada。armada 按「收齐即替换」的判据整批落库，删除随之收敛。

**Tech Stack:** TypeScript + Jest（armada-protocol）；Java 17 / Spring Boot / MyBatis / Flyway（armada-api）；Go（whatsapp-server）

**Spec:** `docs/superpowers/specs/2026-08-29-contact-snapshot-push-design.md`

**上游背景:** `docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md`

---

## Global Constraints

- **事件名逐字固定**：`account.contacts_reported`
- **Topic 独立**：`protocol.account.contact-sync.events.v1`，消费端 `max.poll.records=1`（大消息，照 group-sync 的既定做法）
- **collection 名逐字固定**：`critical_unblock_low`（联系人所在的 app-state collection）
- **`synced_at` 一律用协议给的 `snapshotCutoff`**，armada 不得再用自己的 `now` 盖戳
- **`deleteStale` 由「收齐」触发，不由「最后一片」触发**（Kafka 不保证分片顺序，详见 spec §5.1）
- **丢片时宁可留脏数据也不删**：`n < totalCount` 一律跳过删除
- **`is_mutual` 仍恒为 0**，两套协议都不暴露双向好友标记，归一化器里那处注释保留
- **不转发增量联系人事件**，只推全量快照（spec §3.2）
- **不做命令通道**：armada 不能主动要快照
- **Java record 组件不能叫 `notify`**（与 `Object.notify()` 冲突）
- **空批次不得调 `<foreach>` 批量语句**
- **本机没有 `armada-api/.env`，所有 `*DbTest` 必挂**，新增测试一律纯类测试

### 回归基线（涨了必须查清）

| 范围 | 基线 |
|---|---|
| armada 全量 | `Tests 3624 / Failures 7 / Errors 461` |
| armada-protocol | 既有失败 suite：`worker/baileys-participating-groups.test.ts`、`traffic/baileys-patch.test.ts`（共 5 个用例） |
| whatsapp-server | 既有失败：`pkg/noise`；`internal/armada` 全绿 |

### 跑测试

```bash
# armada-protocol（必须带 ESM flag）
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/worker/

# armada（根目录没有聚合 pom，必须进子模块）
cd /home/yanwenchao/ideaProject/armada/armada-api
mvn -o test -Dtest='AccountContact*Test' -DfailIfNoTests=false

# whatsapp-server
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go test ./internal/armada/...
```

armada 全量数字从 surefire 报告聚合，**不要用 `mvn 输出 | grep | tail`**：

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api/target/surefire-reports
echo -n "Tests: ";    grep -h "^Tests run:" *.txt | sed 's/Tests run: \([0-9]*\).*/\1/'   | paste -sd+ | bc
echo -n "Failures: "; grep -h "^Tests run:" *.txt | sed 's/.*Failures: \([0-9]*\).*/\1/' | paste -sd+ | bc
echo -n "Errors: ";   grep -h "^Tests run:" *.txt | sed 's/.*Errors: \([0-9]*\).*/\1/'   | paste -sd+ | bc
```

---

## 分期与依赖

| 期 | 内容 | 仓库 |
|---|---|---|
| **S1** | Web 投影修复（Task 1-3） | armada-protocol |
| **S2** | 事件契约 + Web 强制快照与推送（Task 4-7） | armada-protocol |
| **S3** | armada 消费落库 + 退役拉取路径（Task 8-13） | armada |
| **S4** | Android 强制快照与推送（Task 14） | whatsapp-server |

S1 → S2 → S3 严格依赖。S4 只依赖 S3 的事件契约，可与 S2 并行。

---

# S1 — Web 投影修复

## Task 1: `normalizeContactJid` 支持 LID

**Files:**
- Modify: `protocol-layer/src/worker/contact-store.ts`
- Test: `protocol-layer/src/worker/contact-store.test.ts`

**Interfaces:**
- Produces: `normalizeContactJid(id: unknown, fallbackPhoneJid?: unknown): { phone: string; jid: string } | null`
  - `id` 是 `@s.whatsapp.net` → 照旧从中取号
  - `id` 是 `@lid` → 改用 `fallbackPhoneJid`（调用方传 payload 的 `phoneNumber` 或 `pnJid`）取号
  - 两者都取不到号 → `null`

> **为什么加第二个参数而不是只认 id**：v7 的 `lidContactAction` 发出的 `id` 是 LID，号码在 payload 的 `phoneNumber` 字段里（`sync-action-utils.js:15` 的 `phoneNumber = idIsPn ? id : action.pnJid`）。只认 id 会把这批号全丢掉。

- [ ] **Step 1: 写失败测试**

在 `contact-store.test.ts` 追加（若文件不存在则新建，import 沿用同目录既有测试的写法）：

```typescript
describe('normalizeContactJid — LID 支持', () => {
  it('LID 形式的 id 用回退号码求号', () => {
    expect(
      normalizeContactJid('123456789@lid', '8613800000000@s.whatsapp.net')
    ).toEqual({ phone: '8613800000000', jid: '8613800000000@s.whatsapp.net' })
  })

  it('LID 且没有回退号码时丢弃', () => {
    expect(normalizeContactJid('123456789@lid')).toBeNull()
    expect(normalizeContactJid('123456789@lid', undefined)).toBeNull()
    expect(normalizeContactJid('123456789@lid', '')).toBeNull()
  })

  it('回退号码本身也可以是纯数字', () => {
    expect(normalizeContactJid('123456789@lid', '8613800000000')).toEqual({
      phone: '8613800000000',
      jid: '8613800000000@s.whatsapp.net'
    })
  })

  it('普通 JID 不受回退参数影响', () => {
    expect(
      normalizeContactJid('8613900000000@s.whatsapp.net', '8613800000000')
    ).toEqual({ phone: '8613900000000', jid: '8613900000000@s.whatsapp.net' })
  })

  it('回退号码非法时不救场', () => {
    expect(normalizeContactJid('123456789@lid', 'not-a-number')).toBeNull()
    expect(normalizeContactJid('123456789@lid', '12@g.us')).toBeNull()
  })

  it('非用户非 LID 的 JID 仍然丢弃', () => {
    expect(normalizeContactJid('120363000000000000@g.us', '8613800000000')).toBeNull()
    expect(normalizeContactJid('status@broadcast')).toBeNull()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/worker/contact-store.test.ts
```
Expected: FAIL —— LID 用例返回 `null`

- [ ] **Step 3: 改实现**

`contact-store.ts` 的 `normalizeContactJid` 整体替换为：

```typescript
const USER_SERVER = '@s.whatsapp.net'
const LID_SERVER = '@lid'

/**
 * 把联系人标识归一为纯数字号码与规范用户 JID。
 *
 * v7 的 lidContactAction 发出的 id 是 LID 而非用户 JID，真实号码在 payload 的
 * phoneNumber / pnJid 字段里。只认 id 会把这批联系人全丢掉，因此允许调用方传回退号码。
 *
 * @param value 联系人标识，可能是用户 JID 或 LID
 * @param fallbackPhone 回退号码，可以是用户 JID 或纯数字；仅在 value 是 LID 时使用
 * @returns 归一结果；无法求出号码时为 null
 */
export function normalizeContactJid(
  value: unknown,
  fallbackPhone?: unknown
): { phone: string; jid: string } | null {
  const direct = phoneFromUserJid(value)
  if (direct) return toResult(direct)
  if (typeof value === 'string' && value.trim().endsWith(LID_SERVER)) {
    const fallback = phoneFromUserJid(fallbackPhone) ?? bareDigits(fallbackPhone)
    if (fallback) return toResult(fallback)
  }
  return null
}

/** 从 <phone>[:device]@s.whatsapp.net 取纯数字号码。 */
function phoneFromUserJid(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (!trimmed.endsWith(USER_SERVER)) return null
  const user = trimmed.slice(0, -USER_SERVER.length)
  return bareDigits(user.split(':', 1)[0])
}

/** 校验并返回纯数字号码。 */
function bareDigits(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return /^\d{5,20}$/.test(trimmed) ? trimmed : null
}

function toResult(phone: string): { phone: string; jid: string } {
  return { phone, jid: `${phone}${USER_SERVER}` }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2

Expected: PASS，且该文件既有用例（`isPeerJid` 之外的原有 normalize 用例）不得回归

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/contact-store.ts protocol-layer/src/worker/contact-store.test.ts
git commit -m "fix(contacts): resolve phone number for LID contacts"
```

---

## Task 2: store 消费 v7 payload 形状

**Files:**
- Modify: `protocol-layer/src/worker/contact-store.ts`
- Test: `protocol-layer/src/worker/contact-store.test.ts`

**Interfaces:**
- Consumes: Task 1 的 `normalizeContactJid(value, fallbackPhone)`
- Produces:
  - `AccountContactStore#upsertMany(contacts: unknown[])` —— 读 `entry.id`，回退号码依次取 `entry.phoneNumber` → `entry.pnJid`
  - `AccountContactStore#snapshot(): ContactRecord[]` —— `list()` 的别名保留，新增语义等价方法不必要，**沿用 `list()`**

> v7 的 `contactAction` 出参形状是 `{ id, name, username, lid, phoneNumber }`（`sync-action-utils.js:18`），**没有 `notify` / `verifiedName`**——那两个字段来自 `contacts.update`（`messages-recv.js:853`）。合并逻辑保持"有值才覆盖"，因此两个来源天然互补，不要改成整体替换。

- [ ] **Step 1: 写失败测试**

```typescript
describe('AccountContactStore — v7 payload', () => {
  it('从 phoneNumber 救回 LID 联系人', () => {
    const store = new AccountContactStore()

    store.upsertMany([
      { id: '99887766@lid', name: '张三', phoneNumber: '8613800000000@s.whatsapp.net' }
    ])

    expect(store.list()).toEqual([
      { phone: '8613800000000', jid: '8613800000000@s.whatsapp.net', name: '张三' }
    ])
  })

  it('phoneNumber 缺失时退用 pnJid', () => {
    const store = new AccountContactStore()

    store.upsertMany([{ id: '99887766@lid', name: '李四', pnJid: '8613900000000' }])

    expect(store.list()[0].phone).toBe('8613900000000')
  })

  it('LID 且两个回退字段都没有时丢弃', () => {
    const store = new AccountContactStore()

    store.upsertMany([{ id: '99887766@lid', name: '无号' }])

    expect(store.size).toBe(0)
  })

  it('contactAction 与 contacts.update 两个来源互补合并', () => {
    const store = new AccountContactStore()

    // 来自 app-state：只有 name
    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '备注名' }])
    // 来自 contacts.update：只有 notify / verifiedName
    store.upsertMany([
      { id: '8613800000000@s.whatsapp.net', notify: '对方昵称', verifiedName: '某某公司' }
    ])

    expect(store.list()).toEqual([
      {
        phone: '8613800000000',
        jid: '8613800000000@s.whatsapp.net',
        name: '备注名',
        notify: '对方昵称',
        verifiedName: '某某公司'
      }
    ])
  })

  it('后到的空值不覆盖已有值', () => {
    const store = new AccountContactStore()

    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '备注名' }])
    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '   ' }])

    expect(store.list()[0].name).toBe('备注名')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: 同 Task 1 Step 2

Expected: FAIL —— LID 用例 `size` 为 0

- [ ] **Step 3: 改 `upsertMany`**

把 `normalizeContactJid(entry.id)` 一行改为带回退：

```typescript
      const normalized = normalizeContactJid(
        entry.id,
        entry.phoneNumber ?? entry.pnJid
      )
      if (!normalized) continue
```

其余合并逻辑（`assignIfPresent`）保持不变。

- [ ] **Step 4: 跑测试确认通过**

Run: 同上。Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/contact-store.ts protocol-layer/src/worker/contact-store.test.ts
git commit -m "fix(contacts): accept v7 contact payload shape"
```

---

## Task 3: bridge 增订 `messaging-history.set`，去掉死订阅

**Files:**
- Modify: `protocol-layer/src/worker/contact-store-bridge.ts`
- Test: `protocol-layer/src/worker/contact-store-bridge.test.ts`

**Interfaces:**
- Consumes: Task 2 的 `AccountContactStore#upsertMany`
- Produces: `attachContactStore(sock, store): () => void` —— 订阅集合改为
  `['contacts.upsert', 'contacts.update', 'messaging-history.set']`

> `contacts.set` 在 Baileys 7.0.0-rc11 的 `Types/Events.d.ts` 里**不存在**，是空订阅，删掉。
> `messaging-history.set` 的 payload 是 `{ chats, contacts, messages, isLatest, ... }`，取 `contacts` 字段。
> 现有 `unwrapContacts` 恰好已能处理 `{ contacts }` 形状，复用即可。

- [ ] **Step 1: 写失败测试**

```typescript
import { AccountContactStore } from './contact-store.js'
import { attachContactStore } from './contact-store-bridge.js'

function fakeSocket() {
  const handlers = new Map<string, ((payload: unknown) => void)[]>()
  return {
    ev: {
      on(event: string, handler: (payload: unknown) => void) {
        const list = handlers.get(event) ?? []
        list.push(handler)
        handlers.set(event, list)
      },
      off(event: string, handler: (payload: unknown) => void) {
        const list = (handlers.get(event) ?? []).filter(item => item !== handler)
        handlers.set(event, list)
      }
    },
    emit(event: string, payload: unknown) {
      for (const handler of handlers.get(event) ?? []) handler(payload)
    },
    subscribed(): string[] {
      return [...handlers.entries()].filter(([, list]) => list.length > 0).map(([event]) => event)
    }
  }
}

describe('attachContactStore', () => {
  it('接收 messaging-history.set 里的批量联系人', () => {
    const sock = fakeSocket()
    const store = new AccountContactStore()
    attachContactStore(sock as never, store)

    sock.emit('messaging-history.set', {
      chats: [],
      messages: [],
      isLatest: true,
      contacts: [
        { id: '8613800000000@s.whatsapp.net', name: '甲' },
        { id: '8613900000000@s.whatsapp.net', name: '乙' }
      ]
    })

    expect(store.size).toBe(2)
  })

  it('不再订阅 v7 中不存在的 contacts.set', () => {
    const sock = fakeSocket()
    attachContactStore(sock as never, new AccountContactStore())

    expect(sock.subscribed()).not.toContain('contacts.set')
    expect(sock.subscribed()).toEqual(
      expect.arrayContaining(['contacts.upsert', 'contacts.update', 'messaging-history.set'])
    )
  })

  it('解绑后不再写入', () => {
    const sock = fakeSocket()
    const store = new AccountContactStore()
    const detach = attachContactStore(sock as never, store)

    detach()
    sock.emit('contacts.upsert', [{ id: '8613800000000@s.whatsapp.net', name: '甲' }])
    sock.emit('messaging-history.set', {
      contacts: [{ id: '8613900000000@s.whatsapp.net', name: '乙' }]
    })

    expect(store.size).toBe(0)
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/worker/contact-store-bridge.test.ts
```
Expected: FAIL —— `messaging-history.set` 用例 `size` 为 0，`contacts.set` 用例发现多余订阅

- [ ] **Step 3: 改实现**

```typescript
const CONTACT_EVENTS = ['contacts.upsert', 'contacts.update', 'messaging-history.set'] as const
```

并把 `unwrapContacts` 的注释改为：

```typescript
/** contacts.* 是裸数组，messaging-history.set 是 { contacts }，这里统一成数组。 */
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS

- [ ] **Step 5: 跑 worker 全目录，确认未回归**

Run:
```bash
node --experimental-vm-modules ./node_modules/.bin/jest src/worker/
```
Expected: 只有既有失败 suite `baileys-participating-groups.test.ts`

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/contact-store-bridge.ts protocol-layer/src/worker/contact-store-bridge.test.ts
git commit -m "fix(contacts): collect bulk contacts from history sync"
```

---
# S2 — 事件契约与 Web 强制快照推送

## Task 4: 事件类型与独立 topic

**Files:**
- Modify: `protocol-layer/src/events/subjects.ts`
- Test: `protocol-layer/src/events/subjects.test.ts`

**Interfaces:**
- Produces:
  - `EventType` 联合类型新增 `'account.contacts_reported'`
  - `EventTopicKind` 新增 `'accountContactSync'`
  - `topicKindFor('account.contacts_reported') === 'accountContactSync'`

> 独立 topic 的理由见 spec §4.3：快照是大消息，仓库既定做法是把大消息事件隔离到自己的 topic。
> **注意 `subjects.ts` 里事件名出现在多处**（联合类型、高优先级列表、`topicKindFor`），
> 执行前先 `grep -n "account.groups_reported" src/events/subjects.ts` 数清楚要跟着加几处。

- [ ] **Step 1: 写失败测试**

在 `subjects.test.ts` 追加：

```typescript
describe('account.contacts_reported', () => {
  it('路由到独立的 contact sync topic', () => {
    expect(topicKindFor('account.contacts_reported')).toBe('accountContactSync')
  })

  it('不与账号状态或群同步共用 topic', () => {
    expect(topicKindFor('account.contacts_reported')).not.toBe(
      topicKindFor('account.state_changed')
    )
    expect(topicKindFor('account.contacts_reported')).not.toBe(
      topicKindFor('account.groups_reported')
    )
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/events/subjects.test.ts
```
Expected: 类型错误或返回值不为 `accountContactSync`

- [ ] **Step 3: 改 `subjects.ts`**

1. `EventType` 联合里，紧跟 `'account.groups_reported'` 之后加一行 `'account.contacts_reported',`
2. 高优先级/白名单数组（第二处出现 `account.groups_reported` 的地方）同样加一行
3. `EventTopicKind` 联合加 `| 'accountContactSync'`
4. `topicKindFor` 在 `accountGroupSync` 分支**之前**加：

```typescript
  if (evt === 'account.contacts_reported') return 'accountContactSync'
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS

- [ ] **Step 5: 补 topic 配置**

`grep -rn "accountGroupSync" src | grep -v test` 找到 topic 名映射处，按同一形状加
`accountContactSync → 'protocol.account.contact-sync.events.v1'`（含默认值与环境变量覆盖，
照 accountGroupSync 那一条逐字对齐）。

- [ ] **Step 6: 跑 events 全目录**

Run: `node --experimental-vm-modules ./node_modules/.bin/jest src/events/`

Expected: PASS

- [ ] **Step 7: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/events/
git commit -m "feat(contacts): add contacts reported event and topic"
```

---

## Task 5: 快照分片（纯函数）

**Files:**
- Create: `protocol-layer/src/worker/contact-snapshot.ts`
- Test: `protocol-layer/src/worker/contact-snapshot.test.ts`

**Interfaces:**
- Consumes: `ContactRecord`（Task 1-2 的 store 产出）
- Produces:
  - `ContactSnapshotMeta` = `{ snapshotId: string; queryStartedAt: string; snapshotCutoff: string; snapshotComplete: boolean }`
  - `ContactSnapshotChunk` = `ContactSnapshotMeta & { chunkSeq: number; chunkCount: number; totalCount: number; contacts: WireContact[] }`
  - `WireContact` = `{ phone; jid; fullName?; firstName?; pushName?; businessName? }`
  - `chunkContactSnapshot(records: ContactRecord[], meta: ContactSnapshotMeta, chunkSize?: number): ContactSnapshotChunk[]`

> **字段映射**（spec §4.1）：store 的 `name` → wire 的 `fullName`；`notify` → `pushName`；
> `verifiedName` → `businessName`；`firstName` 恒不出现（Baileys 无此概念，armada 侧落 null）。
> **空联系人也必须产出一片**：否则「这个号一个联系人都没有」表达不出来，armada 那边的
> `deleteStale` 永远不会触发，历史残留清不掉。

- [ ] **Step 1: 写失败测试**

```typescript
import { chunkContactSnapshot, type ContactSnapshotMeta } from './contact-snapshot.js'
import type { ContactRecord } from './contact-store.js'

const META: ContactSnapshotMeta = {
  snapshotId: 'snap-1',
  queryStartedAt: '2026-08-29T10:00:00.000Z',
  snapshotCutoff: '2026-08-29T10:00:05.000Z',
  snapshotComplete: true
}

function records(count: number): ContactRecord[] {
  return Array.from({ length: count }, (_, i) => ({
    phone: `86138${String(i).padStart(8, '0')}`,
    jid: `86138${String(i).padStart(8, '0')}@s.whatsapp.net`
  }))
}

describe('chunkContactSnapshot', () => {
  it('把 store 字段映射成 wire 字段', () => {
    const chunks = chunkContactSnapshot(
      [
        {
          phone: '8613800000000',
          jid: '8613800000000@s.whatsapp.net',
          name: '备注名',
          notify: '对方昵称',
          verifiedName: '某某公司'
        }
      ],
      META
    )

    expect(chunks[0].contacts[0]).toEqual({
      phone: '8613800000000',
      jid: '8613800000000@s.whatsapp.net',
      fullName: '备注名',
      pushName: '对方昵称',
      businessName: '某某公司'
    })
  })

  it('每片带上共享的快照元信息与总数', () => {
    const chunks = chunkContactSnapshot(records(1200), META, 500)

    expect(chunks).toHaveLength(3)
    for (const chunk of chunks) {
      expect(chunk.snapshotId).toBe('snap-1')
      expect(chunk.snapshotCutoff).toBe('2026-08-29T10:00:05.000Z')
      expect(chunk.snapshotComplete).toBe(true)
      expect(chunk.chunkCount).toBe(3)
      expect(chunk.totalCount).toBe(1200)
    }
    expect(chunks.map(c => c.chunkSeq)).toEqual([0, 1, 2])
    expect(chunks.map(c => c.contacts.length)).toEqual([500, 500, 200])
  })

  it('联系人为空时仍产出一片', () => {
    // 否则「这个号没有联系人」表达不出来，armada 侧的残留清理永远不触发
    const chunks = chunkContactSnapshot([], META)

    expect(chunks).toHaveLength(1)
    expect(chunks[0].totalCount).toBe(0)
    expect(chunks[0].chunkCount).toBe(1)
    expect(chunks[0].contacts).toEqual([])
  })

  it('分片内容不重不漏', () => {
    const source = records(1001)

    const flattened = chunkContactSnapshot(source, META, 500).flatMap(c => c.contacts)

    expect(flattened).toHaveLength(1001)
    expect(new Set(flattened.map(c => c.phone)).size).toBe(1001)
  })

  it('缺省字段不出现在 wire 上', () => {
    const chunks = chunkContactSnapshot(
      [{ phone: '8613800000000', jid: '8613800000000@s.whatsapp.net' }],
      META
    )

    expect(chunks[0].contacts[0]).toEqual({
      phone: '8613800000000',
      jid: '8613800000000@s.whatsapp.net'
    })
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest src/worker/contact-snapshot.test.ts
```
Expected: 模块不存在

- [ ] **Step 3: 写实现**

`protocol-layer/src/worker/contact-snapshot.ts`：

```typescript
/**
 * 通讯录快照分片 — 把内存投影转成可上 Kafka 的分片事件负载。
 *
 * 整账号一条消息会撞 Kafka 单消息上限，群成员那边已经踩过这个坑
 * （account-manager.ts 的 publishGroupsUpsertProfiles 注释）。联系人同理分片。
 */

import type { ContactRecord } from './contact-store.js'

/** 单个联系人的 wire 形状。armada 侧字段名与此逐字一致。 */
export interface WireContact {
  phone: string
  jid: string
  fullName?: string
  pushName?: string
  businessName?: string
}

/** 一次逻辑快照的共享元信息，所有分片相同。 */
export interface ContactSnapshotMeta {
  snapshotId: string
  queryStartedAt: string
  snapshotCutoff: string
  snapshotComplete: boolean
}

/** 一个分片的完整事件负载。 */
export interface ContactSnapshotChunk extends ContactSnapshotMeta {
  chunkSeq: number
  chunkCount: number
  totalCount: number
  contacts: WireContact[]
}

/** 默认分片大小。 */
export const DEFAULT_CHUNK_SIZE = 500

/**
 * 把投影记录切成分片事件负载。
 *
 * 联系人为空时仍产出一片：否则「这个号没有联系人」这一事实表达不出来，
 * armada 侧的残留清理就永远不会触发。
 *
 * @param records 投影中的联系人
 * @param meta 快照共享元信息
 * @param chunkSize 每片最大条数
 * @returns 分片列表，至少一片
 */
export function chunkContactSnapshot(
  records: ContactRecord[],
  meta: ContactSnapshotMeta,
  chunkSize: number = DEFAULT_CHUNK_SIZE
): ContactSnapshotChunk[] {
  const size = Math.max(1, chunkSize)
  const wire = records.map(toWireContact)
  const chunkCount = Math.max(1, Math.ceil(wire.length / size))
  const chunks: ContactSnapshotChunk[] = []
  for (let seq = 0; seq < chunkCount; seq++) {
    chunks.push({
      ...meta,
      chunkSeq: seq,
      chunkCount,
      totalCount: wire.length,
      contacts: wire.slice(seq * size, (seq + 1) * size)
    })
  }
  return chunks
}

/** 投影字段名与 wire 字段名不同，这里是唯一的映射点。 */
function toWireContact(record: ContactRecord): WireContact {
  const contact: WireContact = { phone: record.phone, jid: record.jid }
  if (record.name) contact.fullName = record.name
  if (record.notify) contact.pushName = record.notify
  if (record.verifiedName) contact.businessName = record.verifiedName
  return contact
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS（5 个用例）

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/contact-snapshot.ts protocol-layer/src/worker/contact-snapshot.test.ts
git commit -m "feat(contacts): add contact snapshot chunking"
```

---

## Task 6: 强制全量 resync 与快照采集

**Files:**
- Create: `protocol-layer/src/worker/contact-resync.ts`
- Test: `protocol-layer/src/worker/contact-resync.test.ts`

**Interfaces:**
- Consumes: `AccountContactStore`（Task 2）、`chunkContactSnapshot`（Task 5）
- Produces:
  - `CONTACT_COLLECTION = 'critical_unblock_low'`
  - `ContactResyncDeps` = `{ clearVersion(): Promise<void>; resync(): Promise<void>; collect(): AccountContactStore; now(): number; waitQuiet(): Promise<boolean> }`
  - `forceContactSnapshot(deps): Promise<{ meta: ContactSnapshotMeta; records: ContactRecord[] }>`

**流程（spec §6.1）**

```
1. 记 queryStartedAt
2. clearVersion()  —— 清 critical_unblock_low 的 app-state-sync-version
3. resync()        —— resyncAppState([CONTACT_COLLECTION], true)
4. waitQuiet()     —— 等事件静默；返回 false 表示超时
5. 记 snapshotCutoff，snapshotComplete = waitQuiet 的返回值
```

> **为什么清版本号就能拿全量**：`Socket/chats.js:454` 的
> `return_snapshot: (shouldForceSnapshot || !state.version)`，版本为空即请求快照；
> 且 `chat-utils.js:301` 的 `minimumVersionNumber` 来自存量 state，清掉后为 `undefined`，
> `areMutationsRequired` 恒真，快照里每条记录都会重放成 `contacts.upsert`。
>
> **为什么要 waitQuiet**：`resyncAppState` 被 `ev.createBufferedFunction` 包着
> （`event-buffer.js:141`），事件在其 resolve **之后约 100ms** 才 flush，
> await 完立刻读 store 会读到空的。
>
> **失败不吞**：`clearVersion` / `resync` 抛错直接向上抛，由调用方决定是否降级；
> 只有 `waitQuiet` 超时才降级为 `snapshotComplete = false`。

- [ ] **Step 1: 写失败测试**

```typescript
import { AccountContactStore } from './contact-store.js'
import { CONTACT_COLLECTION, forceContactSnapshot } from './contact-resync.js'

function deps(overrides: Partial<Parameters<typeof forceContactSnapshot>[0]> = {}) {
  const store = new AccountContactStore()
  const calls: string[] = []
  const base = {
    clearVersion: async () => {
      calls.push('clear')
    },
    resync: async () => {
      calls.push('resync')
      store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '甲' }])
    },
    collect: () => store,
    now: () => 1_700_000_000_000,
    waitQuiet: async () => {
      calls.push('quiet')
      return true
    }
  }
  return { deps: { ...base, ...overrides }, calls, store }
}

describe('forceContactSnapshot', () => {
  it('按 清版本号 → resync → 等静默 的顺序执行', async () => {
    const { deps: d, calls } = deps()

    await forceContactSnapshot(d)

    expect(calls).toEqual(['clear', 'resync', 'quiet'])
  })

  it('产出的记录来自 resync 期间收集的 store', async () => {
    const { deps: d } = deps()

    const result = await forceContactSnapshot(d)

    expect(result.records).toHaveLength(1)
    expect(result.records[0].phone).toBe('8613800000000')
  })

  it('元信息带真实的起止时间且标记完整', async () => {
    let tick = 1_700_000_000_000
    const { deps: d } = deps({ now: () => (tick += 1000) })

    const result = await forceContactSnapshot(d)

    expect(result.meta.snapshotComplete).toBe(true)
    expect(Date.parse(result.meta.snapshotCutoff)).toBeGreaterThan(
      Date.parse(result.meta.queryStartedAt)
    )
    expect(result.meta.snapshotId).toBeTruthy()
  })

  it('等静默超时则标记快照不完整', async () => {
    const { deps: d } = deps({ waitQuiet: async () => false })

    const result = await forceContactSnapshot(d)

    expect(result.meta.snapshotComplete).toBe(false)
  })

  it('清版本号失败直接抛出，不产出半份快照', async () => {
    const { deps: d, calls } = deps({
      clearVersion: async () => {
        throw new Error('keys store down')
      }
    })

    await expect(forceContactSnapshot(d)).rejects.toThrow('keys store down')
    expect(calls).not.toContain('resync')
  })

  it('resync 失败直接抛出', async () => {
    const { deps: d } = deps({
      resync: async () => {
        throw new Error('socket closed')
      }
    })

    await expect(forceContactSnapshot(d)).rejects.toThrow('socket closed')
  })

  it('联系人 collection 名固定为 critical_unblock_low', () => {
    // 这个名字是 WhatsApp app-state 的线上契约，写错会拉到别的 collection
    expect(CONTACT_COLLECTION).toBe('critical_unblock_low')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
node --experimental-vm-modules ./node_modules/.bin/jest src/worker/contact-resync.test.ts
```
Expected: 模块不存在

- [ ] **Step 3: 写实现**

`protocol-layer/src/worker/contact-resync.ts`：

```typescript
/**
 * 强制全量通讯录快照。
 *
 * 增量事件表达不了删除（Baileys 解码时把 SET/REMOVE 的 operation 丢给了 LTHash 校验器，
 * processSyncAction 收不到，事件类型里也没有 contacts.delete），因此只能靠周期性的
 * 全量快照让删除收敛。
 */

import { randomUUID } from 'node:crypto'

import type { ContactRecord } from './contact-store.js'
import type { AccountContactStore } from './contact-store.js'
import type { ContactSnapshotMeta } from './contact-snapshot.js'

/** 联系人所在的 app-state collection，WhatsApp 线上契约，不可改。 */
export const CONTACT_COLLECTION = 'critical_unblock_low'

/** 强制快照所需的外部能力，全部由调用方注入以便测试。 */
export interface ContactResyncDeps {
  /** 清掉 CONTACT_COLLECTION 的 app-state-sync-version，使服务端返回全量快照。 */
  clearVersion: () => Promise<void>
  /** 执行 resyncAppState([CONTACT_COLLECTION], true)。 */
  resync: () => Promise<void>
  /** 返回本轮收集事件的 store。 */
  collect: () => AccountContactStore
  /** 当前时间（epoch 毫秒）。 */
  now: () => number
  /** 等待联系人事件静默；返回 false 表示超时。 */
  waitQuiet: () => Promise<boolean>
}

/**
 * 执行一次强制全量快照。
 *
 * @param deps 外部能力
 * @returns 快照元信息与本轮收集到的联系人
 */
export async function forceContactSnapshot(
  deps: ContactResyncDeps
): Promise<{ meta: ContactSnapshotMeta; records: ContactRecord[] }> {
  const queryStartedAt = new Date(deps.now()).toISOString()
  // 清版本号或 resync 失败时直接抛出：半份快照比没有快照更危险，
  // armada 那边会据此删掉「没在快照里」的联系人。
  await deps.clearVersion()
  await deps.resync()
  const complete = await deps.waitQuiet()
  return {
    meta: {
      snapshotId: randomUUID(),
      queryStartedAt,
      snapshotCutoff: new Date(deps.now()).toISOString(),
      snapshotComplete: complete
    },
    records: deps.collect().list()
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS（7 个用例）

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/contact-resync.ts protocol-layer/src/worker/contact-resync.test.ts
git commit -m "feat(contacts): add forced full contact resync"
```

---

## Task 7: 接进 AccountManager 与周期调度

**Files:**
- Modify: `protocol-layer/src/worker/account-manager.ts`
- Create: `protocol-layer/src/worker/contact-snapshot-scheduler.ts`
- Test: `protocol-layer/src/worker/contact-snapshot-scheduler.test.ts`

**Interfaces:**
- Consumes: `forceContactSnapshot`（Task 6）、`chunkContactSnapshot`（Task 5）、`EventPublisher`（既有）
- Produces:
  - `ContactSnapshotScheduler` —— 照 `stale-detector.ts` 的范式：构造注入、`start()` / `stop()`、tick 内吞异常
  - `AccountManager#publishContactSnapshot(accountId): Promise<void>`

**发布调用**

```typescript
await publisher.publish('account.contacts_reported', accountId, chunk, undefined, {
  eventId: `${protocolAccountId}:account.contacts_reported:${chunk.snapshotId}:${chunk.chunkSeq}`
})
```

> `eventId` 必须带 `chunkSeq`，否则同一快照的多个分片会被去重成一片。

**触发点**

1. history sync 完成后（`messaging-history.set` 的 `isLatest === true`）推一次
2. `ContactSnapshotScheduler` 按 TTL 周期推（默认 24h，配置项）

- [ ] **Step 1: 写失败测试（调度器）**

```typescript
import { ContactSnapshotScheduler } from './contact-snapshot-scheduler.js'

const silentLogger = { info: () => {}, warn: () => {}, error: () => {} }

describe('ContactSnapshotScheduler', () => {
  beforeEach(() => jest.useFakeTimers())
  afterEach(() => jest.useRealTimers())

  it('到期后对每个在线账号各推一次', async () => {
    const pushed: string[] = []
    const scheduler = new ContactSnapshotScheduler(
      () => ['acc_1', 'acc_2'],
      async accountId => {
        pushed.push(accountId)
      },
      1000,
      silentLogger as never
    )

    scheduler.start()
    await jest.advanceTimersByTimeAsync(1000)

    expect(pushed).toEqual(['acc_1', 'acc_2'])
    scheduler.stop()
  })

  it('单个账号失败不影响同批其它账号', async () => {
    const pushed: string[] = []
    const scheduler = new ContactSnapshotScheduler(
      () => ['bad', 'good'],
      async accountId => {
        if (accountId === 'bad') throw new Error('resync failed')
        pushed.push(accountId)
      },
      1000,
      silentLogger as never
    )

    scheduler.start()
    await jest.advanceTimersByTimeAsync(1000)

    expect(pushed).toEqual(['good'])
    scheduler.stop()
  })

  it('stop 之后不再触发', async () => {
    let ticks = 0
    const scheduler = new ContactSnapshotScheduler(
      () => ['acc_1'],
      async () => {
        ticks++
      },
      1000,
      silentLogger as never
    )

    scheduler.start()
    await jest.advanceTimersByTimeAsync(1000)
    scheduler.stop()
    await jest.advanceTimersByTimeAsync(5000)

    expect(ticks).toBe(1)
  })

  it('重复 start 不会叠加定时器', async () => {
    let ticks = 0
    const scheduler = new ContactSnapshotScheduler(
      () => ['acc_1'],
      async () => {
        ticks++
      },
      1000,
      silentLogger as never
    )

    scheduler.start()
    scheduler.start()
    await jest.advanceTimersByTimeAsync(1000)

    expect(ticks).toBe(1)
    scheduler.stop()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `node --experimental-vm-modules ./node_modules/.bin/jest src/worker/contact-snapshot-scheduler.test.ts`

Expected: 模块不存在

- [ ] **Step 3: 写调度器**

`protocol-layer/src/worker/contact-snapshot-scheduler.ts`：

```typescript
/**
 * 通讯录快照周期推送器。
 *
 * 范式照 stale-detector：构造注入、start/stop、tick 内吞异常。
 * 单账号失败只记 warn，不影响同批其它账号 —— 强制 resync 是有代价的操作，
 * 一个号失败不该让整轮停摆。
 */

import type { Logger } from 'pino'

export class ContactSnapshotScheduler {
  private timer: ReturnType<typeof setInterval> | null = null

  /**
   * @param onlineAccounts 返回当前应推送的账号列表
   * @param pushSnapshot 对单个账号执行强制快照并推送
   * @param intervalMs 周期，毫秒
   * @param logger 日志
   */
  constructor(
    private readonly onlineAccounts: () => string[],
    private readonly pushSnapshot: (accountId: string) => Promise<void>,
    private readonly intervalMs: number,
    private readonly logger: Logger
  ) {}

  start(): void {
    if (this.timer) return
    this.timer = setInterval(() => {
      this.tick().catch(err => this.logger.error({ err }, 'contact snapshot tick failed'))
    }, this.intervalMs)
    this.logger.info({ intervalMs: this.intervalMs }, 'contact snapshot scheduler started')
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  private async tick(): Promise<void> {
    for (const accountId of this.onlineAccounts()) {
      try {
        await this.pushSnapshot(accountId)
      } catch (err) {
        this.logger.warn({ err, accountId }, 'contact snapshot push failed')
      }
    }
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS（4 个用例）

- [ ] **Step 5: 接进 AccountManager**

在 `account-manager.ts` 增加 `publishContactSnapshot(accountId)`：

```typescript
  /**
   * 对单个账号执行强制全量通讯录快照并分片推送。
   *
   * @param accountId 协议账号 ID
   */
  async publishContactSnapshot(accountId: string): Promise<void> {
    const ctx = this.requireContext(accountId)
    const sock = this.getSocket(accountId)
    const collectStore = new AccountContactStore()
    const detach = attachContactStore(sock, collectStore)
    try {
      const { meta, records } = await forceContactSnapshot({
        clearVersion: async () => {
          await sock.authState.keys.set({
            'app-state-sync-version': { [CONTACT_COLLECTION]: null }
          })
        },
        resync: () => sock.resyncAppState([CONTACT_COLLECTION], true),
        collect: () => collectStore,
        now: () => Date.now(),
        waitQuiet: () => this.waitContactEventsQuiet(collectStore)
      })
      // 整体替换主投影：强制快照就是当前全量，合并会让已删联系人残留
      this.accountContacts.set(accountId, collectStore)
      const protocolAccountId = ctx.protocolAccountId ?? accountId
      for (const chunk of chunkContactSnapshot(records, meta)) {
        await this.deps.publisher.publish('account.contacts_reported', accountId, chunk, undefined, {
          eventId: `${protocolAccountId}:account.contacts_reported:${chunk.snapshotId}:${chunk.chunkSeq}`
        })
      }
    } finally {
      detach()
    }
  }
```

> `requireContext` / `getSocket` / `protocolAccountId` 的取法按该文件既有写法对齐，
> 不要新造访问路径。`waitContactEventsQuiet` 见 Step 6。

- [ ] **Step 6: 实现静默等待**

同文件私有方法：

```typescript
  /** 连续 500ms 无新联系人写入即认为重放结束；60s 兜底超时返回 false。 */
  private async waitContactEventsQuiet(store: AccountContactStore): Promise<boolean> {
    const quietMs = 500
    const timeoutMs = 60_000
    const deadline = Date.now() + timeoutMs
    let lastSize = -1
    let quietSince = Date.now()
    while (Date.now() < deadline) {
      await delay(100)
      if (store.size !== lastSize) {
        lastSize = store.size
        quietSince = Date.now()
        continue
      }
      if (Date.now() - quietSince >= quietMs) return true
    }
    return false
  }
```

`delay` 用该文件已有的延时工具；没有就用 `new Promise(r => setTimeout(r, ms))`。

- [ ] **Step 7: 挂上 history sync 完成触发**

在既有的 `messaging-history.set` handler（`account-manager.ts:1707` 附近）里追加：

```typescript
      if (history.isLatest === true) {
        void this.publishContactSnapshot(ctx.accountId).catch(err =>
          this.deps.logger?.warn({ err, accountId: ctx.accountId }, 'initial contact snapshot failed')
        )
      }
```

> 用 `void` + catch 而不是 await：这是事件回调，阻塞它会拖住 Baileys 的事件循环。

- [ ] **Step 8: 跑 worker 全目录**

Run: `node --experimental-vm-modules ./node_modules/.bin/jest src/worker/`

Expected: 只有既有失败 suite `baileys-participating-groups.test.ts`

- [ ] **Step 9: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/
git commit -m "feat(contacts): publish contact snapshots on sync and schedule"
```

---
# S3 — armada 消费落库与退役拉取路径

## Task 8: V161 迁移（`sync_status` 取值扩充）

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V161__account_contact_partial_status.sql`
- Test: `armada-api/src/test/java/com/armada/account/contact/AccountContactPartialStatusMigrationSqlTest.java`

**Interfaces:**
- Produces: `account_contact_sync.sync_status` 列注释新增 `PARTIAL` 取值（列类型 `VARCHAR(16)` 不变）

- [ ] **Step 1: 写失败测试**

```java
package com.armada.account.contact;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V161 同步状态取值扩充迁移的 SQL 文本契约测试。本机无库，只校验脚本。 */
class AccountContactPartialStatusMigrationSqlTest {

    private static String sql() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/db/migration/V161__account_contact_partial_status.sql"),
                StandardCharsets.UTF_8);
    }

    @Test
    void documentsPartialStatus() throws IOException {
        String text = sql();

        assertThat(text).contains("account_contact_sync");
        assertThat(text).contains("sync_status");
        assertThat(text).contains("PARTIAL");
    }

    @Test
    void keepsExistingStatusValuesInComment() throws IOException {
        String text = sql();

        assertThat(text).contains("NEVER");
        assertThat(text).contains("SYNCING");
        assertThat(text).contains("SUCCESS");
        assertThat(text).contains("FAILED");
    }

    @Test
    void doesNotChangeColumnType() throws IOException {
        // 只改注释，列宽和类型不动；改类型会锁表
        assertThat(sql()).contains("VARCHAR(16)");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test -Dtest=AccountContactPartialStatusMigrationSqlTest -DfailIfNoTests=false`

Expected: `NoSuchFileException`

- [ ] **Step 3: 写迁移**

```sql
-- 通讯录同步状态新增 PARTIAL 取值。
-- 协议层判定快照不完整（强制 resync 中途超时），或 armada 收到的分片没收齐时，
-- 只 upsert 不删除残留行，状态记 PARTIAL —— 宁可留几条脏数据，
-- 也不能因为快照不全把号主的通讯录删掉一半。
-- 只改列注释，不改类型与列宽。

ALTER TABLE account_contact_sync
    MODIFY COLUMN sync_status VARCHAR(16) NOT NULL DEFAULT 'NEVER'
    COMMENT '同步状态:NEVER SYNCING SUCCESS FAILED PARTIAL(快照不完整,已入库未清残留)';
```

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS（3 tests）

- [ ] **Step 5: 加实体常量**

`AccountContactSync.java` 在 `STATUS_FAILED` 之后追加：

```java
    /** 快照不完整：已入库但未清理残留行。 */
    public static final String STATUS_PARTIAL = "PARTIAL";
```

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/resources/db/migration/V161__account_contact_partial_status.sql
git add armada-api/src/main/java/com/armada/account/contact/model/entity/AccountContactSync.java
git add armada-api/src/test/java/com/armada/account/contact/AccountContactPartialStatusMigrationSqlTest.java
git commit -m "feat(contact): add partial sync status"
```

---

## Task 9: 收齐判据所需的数据访问

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountContactMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/account/contact/AccountContactMapperXmlTest.java`

**Interfaces:**
- Produces: `AccountContactMapper#countBySyncedAt(Long accountId, long syncedAt) : int`

> `deleteStale` 已存在（P2 建的），签名 `deleteStale(accountId, syncedAt)`，语义是删掉
> `synced_at < syncedAt` 的行。本任务只补一个精确计数，用于「收齐」判据。

- [ ] **Step 1: 写失败测试**

在 `AccountContactMapperXmlTest` 追加：

```java
    @Test
    void exactSyncedAtCountBacksTheCompletenessCheck() throws IOException {
        // 收齐判据靠精确匹配 synced_at 计数，不能写成 >=，否则会把上一轮的行也算进来
        String sql = xml("AccountContactMapper.xml");

        assertThat(sql).contains("id=\"countBySyncedAt\"");
        assertThat(sql).contains("synced_at = #{syncedAt}");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=AccountContactMapperXmlTest -DfailIfNoTests=false`

Expected: FAIL —— `XML 缺少语句 id=countBySyncedAt`

- [ ] **Step 3: 加接口方法**

```java
    /**
     * 统计本账号在指定快照时间下已落库的联系人数。
     *
     * <p>分片可能乱序到达，因此用「本快照已落库条数 == totalCount」作为收齐判据，
     * 而不是「收到最后一片」。</p>
     *
     * @param accountId 账号 ID
     * @param syncedAt 快照时间（epoch 毫秒）
     * @return 该快照下已落库条数
     */
    int countBySyncedAt(@Param("accountId") Long accountId, @Param("syncedAt") long syncedAt);
```

- [ ] **Step 4: 加 XML**

```xml
  <select id="countBySyncedAt" resultType="int">
    SELECT COUNT(*)
      FROM account_contact
     WHERE account_id = #{accountId}
       AND synced_at = #{syncedAt}
  </select>
```

- [ ] **Step 5: 跑测试确认通过**

Expected: PASS

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/contact/mapper/AccountContactMapper.java
git add armada-api/src/main/resources/mapper/account/AccountContactMapper.xml
git add armada-api/src/test/java/com/armada/account/contact/AccountContactMapperXmlTest.java
git commit -m "feat(contact): count contacts by snapshot time"
```

---

## Task 10: 快照事件与消费器

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/contact/AccountContactsReportedEvent.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/contact/AccountContactsReportedSink.java`（接口）
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/contact/ProtocolAccountContactEventConsumer.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAccountContactEventConsumerProperties.java`
- Test: `armada-api/src/test/java/com/armada/platform/kafka/consumer/contact/ProtocolAccountContactEventConsumerTest.java`

**Interfaces:**
- Produces:
  - `AccountContactsReportedEvent`（record）：`String eventId, Long tenantId, Long accountId, String protocolAccountId, String snapshotId, Long queryStartedAt, Long snapshotCutoff, boolean snapshotComplete, int chunkSeq, int chunkCount, int totalCount, List<ReportedContact> contacts`
  - `AccountContactsReportedEvent.ReportedContact`（record）：`String phone, String jid, String fullName, String firstName, String pushName, String businessName`
  - `AccountContactsReportedSink#handle(AccountContactsReportedEvent event) : void`
  - `ProtocolAccountContactEventConsumer#onMessage(String rawMessage, String headerTraceId) : void`

> **Topic**：`protocol.account.contact-sync.events.v1`，`max.poll.records=1`（大消息，照
> `ProtocolAccountEventConsumer:159` 的 group-sync 写法）。
> **必填字段缺失一律抛 `BusinessException(VALIDATION)`**，让 Kafka 重投而不是静默丢弃 ——
> 与 `ProtocolMessageEventConsumer` 的既定行为一致。
> **`snapshotCutoff` 以 epoch 毫秒进 armada**：wire 上是 ISO8601，消费器负责转换；
> 转不出来直接判非法。

- [ ] **Step 1: 写失败测试**

```java
package com.armada.platform.kafka.consumer.contact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 通讯录快照事件消费器测试。 */
@ExtendWith(MockitoExtension.class)
class ProtocolAccountContactEventConsumerTest {

    @Mock
    private AccountContactsReportedSink sink;

    private ProtocolAccountContactEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolAccountContactEventConsumer(new ObjectMapper(), sink);
    }

    private static String envelope(String dataBody) {
        return """
                {"eventId":"evt_1","event":"account.contacts_reported","version":"v1",
                 "accountId":"acc_1","occurredAt":"2026-08-29T10:00:00.000Z","workerId":"w1",
                 "data":{%s}}
                """.formatted(dataBody);
    }

    private static final String FULL_DATA = """
            "tenantId":5,"accountId":11,"protocolAccountId":"acc_1",
            "snapshotId":"snap-1",
            "queryStartedAt":"2026-08-29T10:00:00.000Z",
            "snapshotCutoff":"2026-08-29T10:00:05.000Z",
            "snapshotComplete":true,"chunkSeq":0,"chunkCount":2,"totalCount":3,
            "contacts":[{"phone":"8613800000000","jid":"8613800000000@s.whatsapp.net",
                         "fullName":"甲","pushName":"昵称","businessName":"公司"}]
            """;

    @Test
    void parsesSnapshotChunkAndDispatchesToSink() {
        consumer.onMessage(envelope(FULL_DATA), null);

        ArgumentCaptor<AccountContactsReportedEvent> captor =
                ArgumentCaptor.forClass(AccountContactsReportedEvent.class);
        verify(sink).handle(captor.capture());
        AccountContactsReportedEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(5L);
        assertThat(event.accountId()).isEqualTo(11L);
        assertThat(event.snapshotId()).isEqualTo("snap-1");
        assertThat(event.snapshotComplete()).isTrue();
        assertThat(event.chunkSeq()).isZero();
        assertThat(event.chunkCount()).isEqualTo(2);
        assertThat(event.totalCount()).isEqualTo(3);
        assertThat(event.contacts()).singleElement().satisfies(contact -> {
            assertThat(contact.phone()).isEqualTo("8613800000000");
            assertThat(contact.fullName()).isEqualTo("甲");
            assertThat(contact.pushName()).isEqualTo("昵称");
            assertThat(contact.businessName()).isEqualTo("公司");
            assertThat(contact.firstName()).isNull();
        });
    }

    @Test
    void convertsIso8601CutoffToEpochMillis() {
        consumer.onMessage(envelope(FULL_DATA), null);

        ArgumentCaptor<AccountContactsReportedEvent> captor =
                ArgumentCaptor.forClass(AccountContactsReportedEvent.class);
        verify(sink).handle(captor.capture());
        assertThat(captor.getValue().snapshotCutoff())
                .isEqualTo(java.time.Instant.parse("2026-08-29T10:00:05.000Z").toEpochMilli());
    }

    @Test
    void acceptsEmptyContactChunk() {
        // 「这个号一个联系人都没有」必须能表达，否则残留永远清不掉
        String data = FULL_DATA.replace(
                """
                "contacts":[{"phone":"8613800000000","jid":"8613800000000@s.whatsapp.net",
                             "fullName":"甲","pushName":"昵称","businessName":"公司"}]
                """.strip(),
                "\"contacts\":[]").replace("\"totalCount\":3", "\"totalCount\":0");

        consumer.onMessage(envelope(data), null);

        ArgumentCaptor<AccountContactsReportedEvent> captor =
                ArgumentCaptor.forClass(AccountContactsReportedEvent.class);
        verify(sink).handle(captor.capture());
        assertThat(captor.getValue().contacts()).isEmpty();
        assertThat(captor.getValue().totalCount()).isZero();
    }

    @Test
    void rejectsMissingSnapshotCutoff() {
        String data = FULL_DATA.replace("\"snapshotCutoff\":\"2026-08-29T10:00:05.000Z\",", "");

        assertThatThrownBy(() -> consumer.onMessage(envelope(data), null))
                .hasMessageContaining("snapshotCutoff");
        verifyNoInteractions(sink);
    }

    @Test
    void rejectsUnparseableSnapshotCutoff() {
        String data = FULL_DATA.replace("2026-08-29T10:00:05.000Z", "not-a-time");

        assertThatThrownBy(() -> consumer.onMessage(envelope(data), null))
                .hasMessageContaining("snapshotCutoff");
    }

    @Test
    void rejectsMissingAccountId() {
        String data = FULL_DATA.replace("\"accountId\":11,", "");

        assertThatThrownBy(() -> consumer.onMessage(envelope(data), null))
                .hasMessageContaining("accountId");
    }

    @Test
    void skipsUnrelatedEventType() {
        String raw = envelope(FULL_DATA).replace(
                "account.contacts_reported", "account.state_changed");

        consumer.onMessage(raw, null);

        verifyNoInteractions(sink);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=ProtocolAccountContactEventConsumerTest -DfailIfNoTests=false`

Expected: 编译失败，`package com.armada.platform.kafka.consumer.contact does not exist`

- [ ] **Step 3: 写事件 record 与 sink 接口**

`AccountContactsReportedEvent.java`：

```java
package com.armada.platform.kafka.consumer.contact;

import java.util.List;

/**
 * 协议层上报的账号通讯录快照分片。
 *
 * <p>分片共享同一个 {@code snapshotId} 与 {@code snapshotCutoff}；Kafka 不保证分片顺序，
 * 因此消费方以「本快照已落库条数 == totalCount」判定收齐，而不是等最后一片。</p>
 *
 * @param eventId 协议事件 ID
 * @param tenantId 租户 ID
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议账号句柄
 * @param snapshotId 同一逻辑快照的稳定标识
 * @param queryStartedAt 开始拉取时间（epoch 毫秒）
 * @param snapshotCutoff 快照截止时间（epoch 毫秒），落库即 synced_at
 * @param snapshotComplete 协议层是否判定本快照完整
 * @param chunkSeq 分片序号，0 起
 * @param chunkCount 分片总数
 * @param totalCount 本快照联系人总条数（跨全部分片）
 * @param contacts 本分片联系人
 */
public record AccountContactsReportedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String snapshotId,
        Long queryStartedAt,
        Long snapshotCutoff,
        boolean snapshotComplete,
        int chunkSeq,
        int chunkCount,
        int totalCount,
        List<ReportedContact> contacts
) {

    /** 组件做防御性拷贝，实例不可变。 */
    public AccountContactsReportedEvent {
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
    }

    /**
     * 快照中的单个联系人。
     *
     * @param phone 不带加号的纯数字号码
     * @param jid 规范用户 JID
     * @param fullName 通讯录全名
     * @param firstName 通讯录名；Web 协议恒为 null
     * @param pushName 对方设置的展示名
     * @param businessName 商业号认证名
     */
    public record ReportedContact(
            String phone,
            String jid,
            String fullName,
            String firstName,
            String pushName,
            String businessName
    ) {
    }
}
```

`AccountContactsReportedSink.java`：

```java
package com.armada.platform.kafka.consumer.contact;

/** 通讯录快照分片的业务处理器。 */
public interface AccountContactsReportedSink {

    /**
     * 落库一个快照分片。
     *
     * @param event 已完成协议字段解析的分片
     */
    void handle(AccountContactsReportedEvent event);
}
```

- [ ] **Step 4: 写消费器**

`ProtocolAccountContactEventConsumer.java` —— 结构照 `ProtocolMessageEventConsumer`：
`readEnvelope` / `dataNode` / `requiredLong` / `requiredText` 等私有工具逐个照搬（**不要抽公共基类**，
既有两个消费器也是各自持有一份，保持一致）。要点：

```java
    public static final String EVENT_ACCOUNT_CONTACTS_REPORTED = "account.contacts_reported";

    @KafkaListener(
            topics = "${armada.protocol.kafka.account-contact-events.topic:protocol.account.contact-sync.events.v1}",
            groupId = "${armada.protocol.kafka.account-contact-events.group-id:armada-api-account-contact-events}",
            concurrency = "${armada.protocol.kafka.account-contact-events.concurrency:2}",
            properties = "max.poll.records=${armada.protocol.kafka.account-contact-events.max-poll-records:1}")
    public void onMessage(String rawMessage,
                          @Header(name = TraceIds.KAFKA_HEADER, required = false) String headerTraceId) {
        JsonNode envelope = readEnvelope(rawMessage);
        try (TraceContext.Scope ignored = KafkaTraceSupport.open(
                envelope, headerTraceId, log, text(envelope, "eventId"))) {
            handleEnvelope(envelope);
        }
    }
```

ISO8601 转 epoch 毫秒：

```java
    /** wire 上是 ISO8601，落库要 epoch 毫秒；转不出来即判非法，交 Kafka 重投。 */
    private static long requiredEpochMillis(JsonNode node, String fieldName) {
        String raw = text(node, fieldName);
        if (raw == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "通讯录快照事件缺少 data." + fieldName);
        }
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "通讯录快照事件 data." + fieldName + " 格式非法");
        }
    }
```

- [ ] **Step 5: 写配置类**

`ProtocolAccountContactEventConsumerProperties.java` 照
`ProtocolAccountGroupSyncEventConsumerProperties` 逐字对齐，默认值：

```java
    public static final String DEFAULT_TOPIC = "protocol.account.contact-sync.events.v1";
    public static final String DEFAULT_GROUP_ID = "armada-api-account-contact-events";
```

- [ ] **Step 6: 跑测试确认通过**

Expected: PASS（7 tests）

- [ ] **Step 7: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/platform/kafka/
git add armada-api/src/test/java/com/armada/platform/kafka/consumer/contact/
git commit -m "feat(contact): consume contact snapshot events"
```

---

## Task 11: 快照落库（收齐判据与丢片防护）

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSnapshotSink.java`
- Modify: `armada-api/src/main/java/com/armada/account/contact/config/AccountContactConfiguration.java`
- Test: `armada-api/src/test/java/com/armada/account/contact/AccountContactSnapshotSinkTest.java`

**Interfaces:**
- Consumes: `AccountContactsReportedEvent`（Task 10）、`AccountContactNormalizer`（既有）、
  `AccountContactMapper#upsertBatch/deleteStale/countBySyncedAt`（Task 9）、`AccountContactSyncMapper#upsert`
- Produces: `AccountContactSnapshotSink implements AccountContactsReportedSink`

**落库规则（spec §5.1）**

```
1. 归一化本片联系人，synced_at = event.snapshotCutoff
2. 非空则 upsertBatch（空片不调，foreach 会生成空 VALUES）
3. n = countBySyncedAt(accountId, snapshotCutoff)
4. n == totalCount 且 snapshotComplete → deleteStale + 回写计数 + SUCCESS
   n == totalCount 且 !snapshotComplete → 不删 + 回写计数 + PARTIAL
   n <  totalCount                      → 不删 + 不回写计数 + SYNCING
```

> **计数只在收齐时回写**：半路回写会让账号筛选读到偏小的 `contact_named_num`。
> **`deleteStale` 只在收齐且协议判定完整时执行**——这是唯一会删数据的地方，两个条件都必须满足。

- [ ] **Step 1: 写失败测试**

```java
package com.armada.account.contact;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.impl.AccountContactSnapshotSink;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.platform.kafka.consumer.contact.AccountContactsReportedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录快照落库的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountContactSnapshotSinkTest {

    private static final long CUTOFF = 1_700_000_005_000L;

    @Mock
    private AccountContactMapper contactMapper;
    @Mock
    private AccountContactSyncMapper syncMapper;
    @Mock
    private AccountStateMapper accountStateMapper;

    private AccountContactSnapshotSink sink() {
        return new AccountContactSnapshotSink(
                contactMapper, syncMapper, accountStateMapper,
                new AccountContactNormalizer(), () -> 2_000L);
    }

    private static AccountContactsReportedEvent chunk(
            int chunkSeq, int totalCount, boolean complete, int contactCount) {
        return new AccountContactsReportedEvent(
                "evt_1", 5L, 11L, "acc_1", "snap-1",
                1_700_000_000_000L, CUTOFF, complete,
                chunkSeq, 2, totalCount,
                java.util.stream.IntStream.range(0, contactCount)
                        .mapToObj(i -> new AccountContactsReportedEvent.ReportedContact(
                                "8613800000" + String.format("%03d", i),
                                "8613800000" + String.format("%03d", i) + "@s.whatsapp.net",
                                "名字" + i, null, null, null))
                        .toList());
    }

    @Test
    void stampsProtocolCutoffAsSyncedAtNotLocalClock() {
        // 这是整件事的目的：synced_at 必须是协议给的真实快照时间，不是 armada 的 now
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 1, true, 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountContact>> captor = ArgumentCaptor.forClass(List.class);
        verify(contactMapper).upsertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getSyncedAt()).isEqualTo(CUTOFF);
    }

    @Test
    void deletesStaleOnlyWhenChunksAreAllIn() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, true, 1));

        verify(contactMapper).deleteStale(11L, CUTOFF);
    }

    @Test
    void doesNotDeleteWhileChunksAreStillMissing() {
        // 丢片时宁可留脏数据，也不能把号主的通讯录删掉一半
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 5, true, 1));

        verify(contactMapper, never()).deleteStale(anyLong(), anyLong());
    }

    @Test
    void doesNotDeleteWhenProtocolMarkedSnapshotIncomplete() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, false, 1));

        verify(contactMapper, never()).deleteStale(anyLong(), anyLong());
    }

    @Test
    void recordsPartialStatusWhenSnapshotIncomplete() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, false, 1));

        ArgumentCaptor<AccountContactSync> captor =
                ArgumentCaptor.forClass(AccountContactSync.class);
        verify(syncMapper).upsert(captor.capture());
        assertThat(captor.getValue().getSyncStatus())
                .isEqualTo(AccountContactSync.STATUS_PARTIAL);
    }

    @Test
    void recordsSyncingStatusWhileIncomplete() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 5, true, 1));

        ArgumentCaptor<AccountContactSync> captor =
                ArgumentCaptor.forClass(AccountContactSync.class);
        verify(syncMapper).upsert(captor.capture());
        assertThat(captor.getValue().getSyncStatus())
                .isEqualTo(AccountContactSync.STATUS_SYNCING);
    }

    @Test
    void writesCountsOnlyWhenSnapshotIsComplete() {
        // 半路回写会让账号筛选读到偏小的 contact_named_num
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 5, true, 1));

        verify(accountStateMapper, never())
                .updateContactCounts(anyLong(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void writesCountsOnCompletion() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, true, 2));

        verify(accountStateMapper).updateContactCounts(eq(11L), eq(2), eq(0), anyLong());
    }

    @Test
    void neverCallsBatchInsertForEmptyChunk() {
        // 空片是合法的（这个号没有联系人），但 foreach 会生成空 VALUES
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(0);

        sink().handle(chunk(0, 0, true, 0));

        verify(contactMapper, never()).upsertBatch(any());
    }

    @Test
    void emptySnapshotStillClearsLeftovers() {
        // 「这个号一个联系人都没有」必须能把历史残留清掉
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(0);

        sink().handle(chunk(0, 0, true, 0));

        verify(contactMapper).deleteStale(11L, CUTOFF);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=AccountContactSnapshotSinkTest -DfailIfNoTests=false`

Expected: 编译失败，`cannot find symbol: class AccountContactSnapshotSink`

- [ ] **Step 3: 写实现**

要点（完整实现照下述骨架填）：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void handle(AccountContactsReportedEvent event) {
    Long previous = TenantContext.get();
    TenantContext.set(event.tenantId());
    try {
        long syncedAt = event.snapshotCutoff();
        NormalizedContacts normalized = normalizer.normalize(toSnapshot(event));
        if (!normalized.rows().isEmpty()) {
            upsertInBatches(event.accountId(), syncedAt, normalized);
        }
        int landed = contactMapper.countBySyncedAt(event.accountId(), syncedAt);
        if (landed < event.totalCount()) {
            saveSyncState(event, landed, AccountContactSync.STATUS_SYNCING, null);
            return;
        }
        if (!event.snapshotComplete()) {
            // 协议自己判定不完整：入库但不清残留
            saveSyncState(event, landed, AccountContactSync.STATUS_PARTIAL,
                    "protocol reported incomplete snapshot");
            return;
        }
        contactMapper.deleteStale(event.accountId(), syncedAt);
        accountStateMapper.updateContactCounts(
                event.accountId(), normalized.namedNum(), normalized.mutualNum(), clock.getAsLong());
        saveSyncState(event, landed, AccountContactSync.STATUS_SUCCESS, null);
    } finally {
        restoreTenant(previous);
    }
}
```

> **注意**：`normalized.namedNum()` 只统计**本片**。收齐时要回写的是**整份快照**的计数，
> 因此 `updateContactCounts` 的入参不能用本片的 `namedNum`，必须查库。
> 实现时补一个 `AccountContactMapper#countNamedBySyncedAt(accountId, syncedAt)`，
> 与 Task 9 的 `countBySyncedAt` 同形状加一个 `AND is_named = 1`，并在 Task 9 的 XML 契约测试里
> 补一条断言。**这一条是实现时必须修正的偏差，别照抄骨架里的 `normalized.namedNum()`。**

- [ ] **Step 4: 装配 bean**

`AccountContactConfiguration` 增加：

```java
    @Bean
    public AccountContactSnapshotSink accountContactSnapshotSink(
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer) {
        return new AccountContactSnapshotSink(
                contactMapper, syncMapper, accountStateMapper, normalizer,
                System::currentTimeMillis);
    }
```

- [ ] **Step 5: 跑测试确认通过**

Expected: PASS（10 tests）

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/account/contact/
git add armada-api/src/main/resources/mapper/account/AccountContactMapper.xml
git add armada-api/src/test/java/com/armada/account/contact/
git commit -m "feat(contact): persist contact snapshots from protocol"
```

---

## Task 12: 任务启用改读快照

**Files:**
- Modify: `armada-api/src/main/java/com/armada/contact/task/service/ContactTaskExpansionService.java`
- Modify: `armada-api/src/main/java/com/armada/contact/task/config/ContactTaskConfiguration.java`
- Modify: `armada-api/src/test/java/com/armada/contact/task/ContactTaskExpansionServiceTest.java`

**Interfaces:**
- Consumes: `AccountContactSyncMapper#selectByAccountId`、`AccountContactProperties#snapshotTtlHoursOrDefault`、
  `ContactSnapshotFreshness#isStale`（全部既有）
- Produces: `ContactTaskExpansionService` 构造参数中的 `AccountContactSyncService` 替换为
  `AccountContactSyncMapper` + `AccountContactProperties`

**行为（spec §8）**

```
快照缺失（selectByAccountId 为 null 或 lastSyncedAt 为 null）→ SKIPPED
快照过期（isStale(lastSyncedAt, now, ttl)）                    → SKIPPED
sync_status = PARTIAL                                          → 可用
其余                                                            → 可用
```

- [ ] **Step 1: 改测试**

把 `ContactTaskExpansionServiceTest` 里的 `syncService` mock 换成 `syncMapper`，
删掉 `usesStaleSnapshotWhenRefreshFailsButHistoryExists` 与 `skipsAccountWithoutAnyUsableSnapshot`
两个基于拉取语义的用例，替换为：

```java
    private static AccountContactSync snapshot(Long lastSyncedAt, String status) {
        AccountContactSync row = new AccountContactSync();
        row.setAccountId(11L);
        row.setLastSyncedAt(lastSyncedAt);
        row.setSyncStatus(status);
        return row;
    }

    @Test
    void skipsAccountWithoutAnySnapshot() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(null);

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isZero();
        verify(recipientMapper, never()).insertBatch(any());
        ArgumentCaptor<ContactFriendTaskAccount> captor =
                ArgumentCaptor.forClass(ContactFriendTaskAccount.class);
        verify(accountMapper).insert(captor.capture());
        assertThat(captor.getValue().getState())
                .isEqualTo(ContactFriendTaskAccount.STATE_SKIPPED);
    }

    @Test
    void skipsAccountWhoseSnapshotIsStale() {
        // 宁可少发，也不拿三天前的通讯录发
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(
                snapshot(1_000L - 100L * 3_600_000L, AccountContactSync.STATUS_SUCCESS));

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isZero();
    }

    @Test
    void usesPartialSnapshot() {
        // PARTIAL 的数据是全的，只是可能多几条已删的，可以用
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(
                snapshot(900L, AccountContactSync.STATUS_PARTIAL));
        when(contactMapper.selectNamedByAccount(eq(11L), anyInt()))
                .thenReturn(List.of(contact("8613900000001")));
        givenGeneratedAccountIds(101L);

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isEqualTo(1);
    }

    @Test
    void neverTriggersASynchronousPull() {
        // 拉取路径已退役，展开时绝不能再有任何同步拉取
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(
                snapshot(900L, AccountContactSync.STATUS_SUCCESS));
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt()))
                .thenReturn(List.of(contact("8613900000001")));
        givenGeneratedAccountIds(101L);

        service().expand(task(10, 0));

        // syncMapper 只读不写
        verify(syncMapper, never()).upsert(any());
    }
```

`service()` 的构造改为传 `syncMapper` 与 `new AccountContactProperties(null, 24)`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=ContactTaskExpansionServiceTest -DfailIfNoTests=false`

Expected: 编译失败（构造签名不匹配）

- [ ] **Step 3: 改实现**

`expandOneAccount` 开头替换为：

```java
        AccountContactSync sync = syncMapper.selectByAccountId(account.accountId());
        Long lastSyncedAt = sync == null ? null : sync.getLastSyncedAt();
        if (ContactSnapshotFreshness.isStale(
                lastSyncedAt, now, properties.snapshotTtlHoursOrDefault())) {
            // 快照缺失或过期：宁可少发，也不拿陈数据发。
            // 号下次上线协议会自动推，下一个任务就能用。
            insertAccountRow(task, account, 0, lastSyncedAt,
                    ContactFriendTaskAccount.STATE_SKIPPED, now);
            log.info("通讯录任务跳过快照不可用账号 taskId={} accountId={} lastSyncedAt={} status={}",
                    task.getId(), account.accountId(), lastSyncedAt,
                    sync == null ? "NONE" : sync.getSyncStatus());
            return 0;
        }
```

> `ContactSnapshotFreshness.isStale(null, ...)` 已经返回 true，所以缺失与过期是同一条分支，
> 不用写两遍。

- [ ] **Step 4: 改装配**

`ContactTaskConfiguration#contactTaskExpansionService` 的参数把 `AccountContactSyncService`
换成 `AccountContactSyncMapper` 与 `AccountContactProperties`。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -Dtest='ContactTask*Test' -DfailIfNoTests=false`

Expected: PASS

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/contact/task/
git add armada-api/src/test/java/com/armada/contact/task/ContactTaskExpansionServiceTest.java
git commit -m "feat(contact): read pushed snapshot when expanding tasks"
```

---

## Task 13: 退役拉取路径

**Files（全部删除）:**
- `armada-api/src/main/java/com/armada/account/contact/service/AccountContactSyncService.java`
- `armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSyncServiceImpl.java`
- `armada-api/src/main/java/com/armada/account/contact/service/AccountContactOnlineHook.java`
- `armada-api/src/main/java/com/armada/platform/protocol/port/ContactListPort.java`
- `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebContactListAdapter.java`
- `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeContactListAdapter.java`
- `armada-api/src/main/java/com/armada/platform/protocol/routing/ContactListBackend.java`（若无其它实现）
- 对应的测试文件

**Files（修改）:**
- `armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java` —— 删掉 `contactOnlineHook` 字段、构造参数与第 83 行调用
- `armada-api/src/main/java/com/armada/account/contact/config/AccountContactConfiguration.java` —— 删掉退役 bean
- `armada-api/src/main/java/com/armada/platform/protocol/model/result/AccountContactSnapshot.java` —— 若已无引用则整体删除

> **执行顺序**：先 `grep -rn "<类名>" src` 确认无引用再删；`AccountStateChangedSinkAdapter`
> 的构造签名变化会打挂用 `@InjectMocks` 的既有测试 —— 这个坑 P2 踩过一次，
> **删之前先 `grep -rn "@InjectMocks" src/test/java/com/armada/account`**。

- [ ] **Step 1: 清点引用**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api
for c in AccountContactSyncService AccountContactOnlineHook ContactListPort \
         WebContactListAdapter AndroidNativeContactListAdapter AccountContactSnapshot; do
  echo "=== $c ==="; grep -rn "$c" src --include=*.java | grep -v "/$c.java" | head
done
grep -rn "@InjectMocks" src/test/java/com/armada/account | head
```

- [ ] **Step 2: 删除并改调用点**

按 Step 1 的结果逐个删除，改 `AccountStateChangedSinkAdapter` 与 `AccountContactConfiguration`。

- [ ] **Step 3: 编译**

Run: `mvn -o test-compile -q 2>&1 | grep -E "ERROR.*\.java" | sort -u`

Expected: 无输出

- [ ] **Step 4: 跑账号与通讯录相关全部测试**

Run: `mvn -o test -Dtest='Account*Test,ContactTask*Test' -DfailIfNoTests=false`

Expected: PASS，无因删除导致的新失败

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add -A armada-api/src
git commit -m "refactor(contact): retire contact pull path"
```

---

# S4 — Android 强制快照与推送

## Task 14: Go 侧快照采集与推送

**Files:**
- Create: `whatsapp-server/internal/armada/contact_snapshot.go`
- Create: `whatsapp-server/internal/armada/contact_snapshot_test.go`
- Modify: `whatsapp-server/internal/armada/event.go`（或新增事件常量文件）

**Interfaces:**
- Produces:
  - `EventAccountContactsReported = "account.contacts_reported"`
  - `ContactSnapshotChunk` struct，JSON tag 与 Web 侧**逐字一致**
  - `ChunkContactSnapshot(entries []appstate.ContactEntry, meta ContactSnapshotMeta, chunkSize int) []ContactSnapshotChunk`
  - `BuildContactSnapshotEvent(chunk, workerID, occurredAt) (envelope, error)`

> **字段名必须与 Web 侧逐字相同**，armada 只有一个消费器。
> 安卓的 `ContactEntry` 有 `FirstName`（Web 没有），照实填。
> **强制全量**：`BuildIqGetAppStatePatch("critical_unblock_low", 0)` —— version 传 0 即
> `return_snapshot=true`（`iq.go:1452`）。

- [ ] **Step 1: 写失败测试**

```go
func TestChunkContactSnapshotSplitsAndSharesMeta(t *testing.T) {
	entries := make([]appstate.ContactEntry, 1200)
	for i := range entries {
		entries[i] = appstate.ContactEntry{JID: fmt.Sprintf("86138%08d", i), FullName: "名"}
	}
	meta := ContactSnapshotMeta{
		SnapshotID: "snap-1", QueryStartedAt: "2026-08-29T10:00:00.000Z",
		SnapshotCutoff: "2026-08-29T10:00:05.000Z", SnapshotComplete: true,
	}

	chunks := ChunkContactSnapshot(entries, meta, 500)

	if len(chunks) != 3 {
		t.Fatalf("want 3 chunks, got %d", len(chunks))
	}
	for i, c := range chunks {
		if c.SnapshotID != "snap-1" || c.ChunkCount != 3 || c.TotalCount != 1200 {
			t.Fatalf("chunk %d lost shared meta: %+v", i, c)
		}
		if c.ChunkSeq != i {
			t.Fatalf("chunk %d has seq %d", i, c.ChunkSeq)
		}
	}
}

func TestChunkContactSnapshotEmitsOneChunkWhenEmpty(t *testing.T) {
	// 「这个号没有联系人」必须能表达，否则 armada 侧的残留清理永不触发
	chunks := ChunkContactSnapshot(nil, ContactSnapshotMeta{SnapshotID: "snap-1"}, 500)

	if len(chunks) != 1 || chunks[0].TotalCount != 0 || len(chunks[0].Contacts) != 0 {
		t.Fatalf("empty snapshot must still produce one chunk: %+v", chunks)
	}
}

func TestContactSnapshotChunkJSONMatchesWebContract(t *testing.T) {
	// armada 只有一个消费器，字段名与 Web 侧必须逐字一致
	chunk := ContactSnapshotChunk{
		ContactSnapshotMeta: ContactSnapshotMeta{
			SnapshotID: "snap-1", QueryStartedAt: "t0",
			SnapshotCutoff: "t1", SnapshotComplete: true,
		},
		ChunkSeq: 0, ChunkCount: 1, TotalCount: 1,
		Contacts: []WireContact{{
			Phone: "8613800000000", JID: "8613800000000@s.whatsapp.net",
			FullName: "甲", FirstName: "小甲",
		}},
	}

	raw, err := json.Marshal(chunk)
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{
		`"snapshotId"`, `"queryStartedAt"`, `"snapshotCutoff"`, `"snapshotComplete"`,
		`"chunkSeq"`, `"chunkCount"`, `"totalCount"`, `"contacts"`,
		`"phone"`, `"jid"`, `"fullName"`, `"firstName"`,
	} {
		if !strings.Contains(string(raw), field) {
			t.Fatalf("missing wire field %s in %s", field, raw)
		}
	}
}

func TestChunkContactSnapshotSkipsEntriesWithoutJID(t *testing.T) {
	chunks := ChunkContactSnapshot(
		[]appstate.ContactEntry{{JID: ""}, {JID: "8613800000000"}},
		ContactSnapshotMeta{SnapshotID: "s"}, 500)

	if chunks[0].TotalCount != 1 {
		t.Fatalf("entries without JID must be dropped: %+v", chunks[0])
	}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan && go test ./internal/armada/...`

Expected: 编译失败，未定义 `ChunkContactSnapshot`

- [ ] **Step 3: 写实现**

`internal/armada/contact_snapshot.go`，注意号码归一沿用 `buildContactListPayload` 的做法
（`api/service/sync.go`）：`JID` 可能带 `@`，取 `@` 前的部分，再补 `@s.whatsapp.net`。

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS

- [ ] **Step 5: 接强制 resync 与推送**

在账号上线完成 app-state 同步后、以及按周期，调
`BuildIqGetAppStatePatch("critical_unblock_low", 0)` 取全量，收集本轮 `ContactEntry`
（**以本轮 mutation 为准，不读 `wa_contacts` 表** —— 表是累积的，同样只增不减），
攒成快照后经现有 Kafka 生产者推 `account.contacts_reported`。

> 具体挂载点执行时按 Go 侧现有的上线流程定位；`grep -rn "BuildIqGetAppStatePatch" internal/`
> 找到既有调用方照其范式接。

- [ ] **Step 6: 全量回归**

Run: `go test ./...`

Expected: 只有 `pkg/noise` 既有失败

- [ ] **Step 7: 提交**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
git add internal/armada/
git commit -m "feat(contacts): push full contact snapshots to armada"
```

---

## 收尾：全量回归与文档回填

- [ ] **Step 1: 三仓全量回归**

```bash
cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer && \
  node --experimental-vm-modules ./node_modules/.bin/jest
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan && go test ./...
```

用 surefire 报告核对 armada 数字，Failures/Errors 不得高于基线 `7 / 461`。

- [ ] **Step 2: 回填交接文档**

`docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md`：

- §0 现状：通讯录改为推模式，`synced_at` 是真实快照时间
- §5.3「双向好友暂时拿不到」保留，另注明 LID 联系人已修复
- §6.1 待验证项追加：V161 迁移、新 topic 与消费器装配、`countBySyncedAt` 真实 SQL
- §7 踩过的坑追加本期新坑
- §8 真机验证追加 spec §11 的 R1–R4

- [ ] **Step 3: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md
git commit -m "docs(contact): record snapshot push landing state"
```

---

## Self-Review 记录

**1. Spec 覆盖**

| spec 条目 | 落在 |
|---|---|
| §1.1 漏订 `messaging-history.set`、死订 `contacts.set` | Task 3 |
| §1.1 LID 号被丢弃 | Task 1、2 |
| §1.2 时间字段是假的 | Task 10（解析 cutoff）、Task 11（落库用 cutoff） |
| §2 强制全量快照机制 | Task 6 |
| §3.1 触发时机（上线 + 周期） | Task 7 |
| §4.1 事件契约 | Task 5（Web 形状）、Task 10（armada 解析）、Task 14（Go 形状） |
| §4.2 分片 | Task 5、Task 14 |
| §4.3 独立 topic | Task 4、Task 10 |
| §5.1 收齐判据与丢片防护 | Task 11 |
| §5.2 `snapshotComplete=false` | Task 11 |
| §5.3 `PARTIAL` 状态 | Task 8 |
| §5.4 计数回写 | Task 11 |
| §6.1 Web 改造 | Task 1-3、6-7 |
| §6.2 Android 改造 | Task 14 |
| §6.3 静默等待 | Task 7 Step 6 |
| §7 退役代码 | Task 13 |
| §8 任务启用行为 | Task 12 |

**未覆盖且有意不做**：spec §10 列出的四项（不转发增量、不做命令通道、不动 `is_mutual`、
不删协议层 HTTP 接口）——本计划无对应任务，符合预期。

**2. 占位符扫描**：无 TBD / TODO。三处「按既有写法对齐」（`subjects.ts` 的多处事件名、
`AccountManager` 的 context 取法、Go 的上线挂载点）都点名了 grep 命令，是核对动作不是留白。

**3. 类型一致性**

- wire 字段名 `snapshotId / queryStartedAt / snapshotCutoff / snapshotComplete / chunkSeq / chunkCount / totalCount / contacts` —— Task 5（TS）、Task 10（Java）、Task 14（Go）三处一致
- 联系人字段 `phone / jid / fullName / firstName / pushName / businessName` —— 三处一致
- `normalizeContactJid(value, fallbackPhone?)` —— Task 1 定义，Task 2 消费，签名一致
- `ContactSnapshotMeta` —— Task 5 定义，Task 6 产出，字段一致
- `AccountContactsReportedEvent` —— Task 10 定义，Task 11 消费，组件顺序一致
- **已知需实现时修正**：Task 11 骨架里的 `normalized.namedNum()` 是本片计数，必须换成
  新增的 `countNamedBySyncedAt` 查库，该偏差已在 Task 11 Step 3 显式标注
