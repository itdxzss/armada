# 通讯录营销 P0+P1 协议层能力补齐 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 armada 能读到任意在线账号的 WhatsApp 通讯录，并能向私聊 JID 发送链接卡片和图文消息。

**Architecture:** 三仓协同。Web 协议在 socket 生命周期内维护一份 per-account 联系人投影并暴露 `GET /v1/accounts/{id}/contacts`；Android 协议把已落库的 `wa_contacts` 通过 `POST /ws/v1/contacts/list/{key}` 暴露出来；armada 侧新增 `ContactListPort` 走既有 `RoutingXxxPort` 范式分流。发送侧把 `MessageSendCommand.MessageTarget` 从群语义改为中立 JID，两个协议各自放开私聊路径。

**Tech Stack:** Java 17 / Spring Boot / MyBatis（armada），TypeScript / Fastify / Baileys / Jest（armada-protocol），Go / Gin / GORM（whatsapp-server）

**Spec:** `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md`

## Global Constraints

- 四个仓库均在 `feat/contact-marketing` 分支上工作。基线：`armada` `e1f5d195`、`wheel-saas-pure-web` `a9f039e`、`armada-protocol` `60f40d9`、`whatsapp-server` `f1faa36`。
- armada 接口前缀 `/api/<resource>`、字段 camelCase、返回 `ApiResponse<T>` / `PageResult<T>`。
- 协议层缺的能力全部补齐，不做能力降级；Web 按 Baileys 接，Android 照搬 Web 语义（spec §1-8）。
- 所有新增 Java 类、public 方法必须有中文 Javadoc；TypeScript 导出函数必须有中文 JSDoc；Go 导出函数必须有中文注释。三个仓库现有代码都是这个规矩。
- 联系人号码统一为**不带加号的纯数字**；联系人 JID 统一为 `<phone>@s.whatsapp.net`。归一化只在协议 adapter 层做一次，armada 业务层不再重复处理。
- 本计划**不含**任何数据库迁移。`account_contact` 落库属于 P2，不要在这里建表。
- Task 6 修改的 `MessageSendCommand.MessageTarget` 是跨业务共享 record，超链任务期也要用同一处改动（spec §9.1）。改完必须全量跑 `mvn -q -pl armada-api test`，不能只跑新增测试。

---

### Task 1: Web 协议联系人投影纯模块

**Files:**
- Create: `protocol-layer/src/worker/contact-store.ts`（仓库 `armada-protocol`）
- Test: `protocol-layer/src/worker/contact-store.test.ts`

**Interfaces:**
- Consumes: 无
- Produces:
  - `interface ContactRecord { phone: string; jid: string; name?: string; notify?: string; verifiedName?: string }`
  - `class AccountContactStore` — 方法 `upsertMany(contacts: unknown[]): void`、`list(): ContactRecord[]`、`get size(): number`、`clear(): void`
  - `function normalizeContactJid(value: unknown): { phone: string; jid: string } | null`

- [ ] **Step 1: 写失败测试**

创建 `protocol-layer/src/worker/contact-store.test.ts`：

```ts
import { AccountContactStore, normalizeContactJid } from './contact-store.js'

describe('normalizeContactJid', () => {
  it('把标准用户 JID 拆成纯数字号码和规范 JID', () => {
    expect(normalizeContactJid('8613800000000@s.whatsapp.net')).toEqual({
      phone: '8613800000000',
      jid: '8613800000000@s.whatsapp.net'
    })
  })

  it('容忍带设备后缀的 JID', () => {
    expect(normalizeContactJid('8613800000000:12@s.whatsapp.net')).toEqual({
      phone: '8613800000000',
      jid: '8613800000000@s.whatsapp.net'
    })
  })

  it('丢弃群 JID、广播 JID、LID 和空值', () => {
    expect(normalizeContactJid('120363000000000000@g.us')).toBeNull()
    expect(normalizeContactJid('status@broadcast')).toBeNull()
    expect(normalizeContactJid('123456789@lid')).toBeNull()
    expect(normalizeContactJid('')).toBeNull()
    expect(normalizeContactJid(undefined)).toBeNull()
  })
})

describe('AccountContactStore', () => {
  it('按号码去重并在后续事件里合并非空字段', () => {
    const store = new AccountContactStore()
    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '张三' }])
    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', notify: 'zhangsan' }])

    expect(store.size).toBe(1)
    expect(store.list()).toEqual([
      {
        phone: '8613800000000',
        jid: '8613800000000@s.whatsapp.net',
        name: '张三',
        notify: 'zhangsan'
      }
    ])
  })

  it('后到的非空字段覆盖先到的同名字段，空值不覆盖', () => {
    const store = new AccountContactStore()
    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '旧名' }])
    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '', verifiedName: '某商铺' }])

    expect(store.list()[0]).toEqual({
      phone: '8613800000000',
      jid: '8613800000000@s.whatsapp.net',
      name: '旧名',
      verifiedName: '某商铺'
    })
  })

  it('忽略非用户 JID 和结构不合法的条目', () => {
    const store = new AccountContactStore()
    store.upsertMany([
      { id: '120363000000000000@g.us', name: '某群' },
      { name: '无 id' },
      null,
      'not-an-object'
    ])

    expect(store.size).toBe(0)
    expect(store.list()).toEqual([])
  })

  it('clear 清空全部条目', () => {
    const store = new AccountContactStore()
    store.upsertMany([{ id: '8613800000000@s.whatsapp.net', name: '张三' }])
    store.clear()
    expect(store.size).toBe(0)
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/worker/contact-store.test.ts
```

Expected: FAIL，`Cannot find module './contact-store.js'`

- [ ] **Step 3: 写最小实现**

创建 `protocol-layer/src/worker/contact-store.ts`：

```ts
/**
 * 账号通讯录投影 — Baileys contacts 事件的内存快照。
 *
 * Baileys 的 app-state 同步会 emit contacts.set / contacts.upsert / contacts.update，
 * 但不提供任何持久化。本模块只在 socket 生命周期内维护一份规范化投影，
 * 供 GET /v1/accounts/{accountId}/contacts 读取；落库由 Armada 负责。
 */

/** 规范化后的单个联系人。 */
export interface ContactRecord {
  /** 不带加号的纯数字号码 */
  phone: string
  /** 规范用户 JID，形如 <phone>@s.whatsapp.net */
  jid: string
  /** 通讯录名（对方在本机通讯录里的名字） */
  name?: string
  /** 对方自己设置的展示名（pushName） */
  notify?: string
  /** 商业号认证名 */
  verifiedName?: string
}

const USER_SERVER = '@s.whatsapp.net'

/**
 * 把任意 JID 归一为纯数字号码与规范用户 JID，非用户 JID 一律返回 null。
 *
 * @param value 原始 JID
 * @returns 归一结果，非用户 JID 时为 null
 */
export function normalizeContactJid(value: unknown): { phone: string; jid: string } | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (!trimmed.endsWith(USER_SERVER)) return null
  const user = trimmed.slice(0, -USER_SERVER.length)
  // 去掉 :<deviceId> 后缀
  const phone = user.split(':', 1)[0]
  if (!/^\d{5,20}$/.test(phone)) return null
  return { phone, jid: `${phone}${USER_SERVER}` }
}

/** 单账号联系人投影。非线程安全，仅在单个 worker 事件循环内使用。 */
export class AccountContactStore {
  private readonly byPhone = new Map<string, ContactRecord>()

  /** 当前投影中的联系人数量。 */
  get size(): number {
    return this.byPhone.size
  }

  /**
   * 批量写入或合并联系人。非用户 JID、结构不合法的条目静默丢弃。
   *
   * @param contacts Baileys contacts 事件负载
   */
  upsertMany(contacts: unknown[]): void {
    if (!Array.isArray(contacts)) return
    for (const raw of contacts) {
      if (raw === null || typeof raw !== 'object') continue
      const entry = raw as Record<string, unknown>
      const normalized = normalizeContactJid(entry.id)
      if (!normalized) continue
      const existing = this.byPhone.get(normalized.phone)
      const merged: ContactRecord = {
        phone: normalized.phone,
        jid: normalized.jid,
        ...(existing ?? {})
      }
      assignIfPresent(merged, 'name', entry.name)
      assignIfPresent(merged, 'notify', entry.notify)
      assignIfPresent(merged, 'verifiedName', entry.verifiedName)
      this.byPhone.set(normalized.phone, merged)
    }
  }

  /** 返回全部联系人快照。 */
  list(): ContactRecord[] {
    return [...this.byPhone.values()]
  }

  /** 清空投影。账号终态下线时调用。 */
  clear(): void {
    this.byPhone.clear()
  }
}

function assignIfPresent(
  target: ContactRecord,
  key: 'name' | 'notify' | 'verifiedName',
  value: unknown
): void {
  if (typeof value !== 'string') return
  const trimmed = value.trim()
  if (!trimmed) return
  target[key] = trimmed
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/worker/contact-store.test.ts
```

Expected: PASS，5 个用例全绿

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/contact-store.ts protocol-layer/src/worker/contact-store.test.ts
git commit -m "feat(contacts): add per-account contact projection store"
```

---

### Task 2: 把联系人投影接进 AccountManager 生命周期

**Files:**
- Create: `protocol-layer/src/worker/contact-store-bridge.ts`
- Test: `protocol-layer/src/worker/contact-store-bridge.test.ts`
- Modify: `protocol-layer/src/worker/account-manager.ts`（`attachEventBridge` 调用点约 1406 行、`disposeCaches` 约 2848 行、新增 `getContacts` 访问器）

**Interfaces:**
- Consumes: Task 1 的 `AccountContactStore`、`ContactRecord`
- Produces:
  - `function attachContactStore(sock, store: AccountContactStore): () => void` — 订阅三个 contacts 事件，返回解绑函数
  - `AccountManager.getContacts(accountId: string): ContactRecord[]` — 账号不在线时抛 `AccountUnavailableError`（与 `getSocket` 同语义）

- [ ] **Step 1: 写失败测试**

创建 `protocol-layer/src/worker/contact-store-bridge.test.ts`：

```ts
import { AccountContactStore } from './contact-store.js'
import { attachContactStore } from './contact-store-bridge.js'

function fakeSocket() {
  const handlers = new Map<string, (payload: unknown) => void>()
  return {
    ev: {
      on(event: string, handler: (payload: unknown) => void) {
        handlers.set(event, handler)
      },
      off(event: string) {
        handlers.delete(event)
      }
    },
    emit(event: string, payload: unknown) {
      handlers.get(event)?.(payload)
    },
    get subscribed() {
      return [...handlers.keys()].sort()
    }
  }
}

describe('attachContactStore', () => {
  it('订阅 contacts.set / contacts.upsert / contacts.update 三个事件', () => {
    const sock = fakeSocket()
    attachContactStore(sock as never, new AccountContactStore())
    expect(sock.subscribed).toEqual(['contacts.set', 'contacts.update', 'contacts.upsert'])
  })

  it('contacts.set 的 { contacts } 包裹形态被正确解包', () => {
    const sock = fakeSocket()
    const store = new AccountContactStore()
    attachContactStore(sock as never, store)

    sock.emit('contacts.set', { contacts: [{ id: '8613800000000@s.whatsapp.net', name: '张三' }] })

    expect(store.size).toBe(1)
  })

  it('contacts.upsert / contacts.update 的裸数组形态被正确处理', () => {
    const sock = fakeSocket()
    const store = new AccountContactStore()
    attachContactStore(sock as never, store)

    sock.emit('contacts.upsert', [{ id: '8613800000001@s.whatsapp.net', name: '李四' }])
    sock.emit('contacts.update', [{ id: '8613800000001@s.whatsapp.net', notify: 'lisi' }])

    expect(store.list()).toEqual([
      {
        phone: '8613800000001',
        jid: '8613800000001@s.whatsapp.net',
        name: '李四',
        notify: 'lisi'
      }
    ])
  })

  it('解绑后不再接收事件', () => {
    const sock = fakeSocket()
    const store = new AccountContactStore()
    const detach = attachContactStore(sock as never, store)

    detach()
    sock.emit('contacts.upsert', [{ id: '8613800000002@s.whatsapp.net', name: '王五' }])

    expect(store.size).toBe(0)
    expect(sock.subscribed).toEqual([])
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/worker/contact-store-bridge.test.ts
```

Expected: FAIL，`Cannot find module './contact-store-bridge.js'`

- [ ] **Step 3: 写最小实现**

创建 `protocol-layer/src/worker/contact-store-bridge.ts`：

```ts
/**
 * 把 Baileys contacts 事件接到账号联系人投影上。
 *
 * 与 attachEventBridge 同一范式：调用方拿到解绑函数，压进 ctx.detachers，
 * socket 换代或账号下线时统一解绑。
 */

import type { WASocket } from 'baileys'

import type { AccountContactStore } from './contact-store.js'

const CONTACT_EVENTS = ['contacts.set', 'contacts.upsert', 'contacts.update'] as const

/**
 * 订阅联系人事件并写入投影。
 *
 * @param sock 当前 Baileys socket
 * @param store 该账号的联系人投影
 * @returns 解绑函数
 */
export function attachContactStore(sock: WASocket, store: AccountContactStore): () => void {
  const handler = (payload: unknown): void => {
    store.upsertMany(unwrapContacts(payload))
  }
  for (const event of CONTACT_EVENTS) {
    sock.ev.on(event as never, handler as never)
  }
  return () => {
    for (const event of CONTACT_EVENTS) {
      sock.ev.off(event as never, handler as never)
    }
  }
}

/** contacts.set 是 { contacts }，upsert / update 是裸数组，这里统一成数组。 */
function unwrapContacts(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload
  if (payload !== null && typeof payload === 'object') {
    const wrapped = (payload as { contacts?: unknown }).contacts
    if (Array.isArray(wrapped)) return wrapped
  }
  return []
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/worker/contact-store-bridge.test.ts
```

Expected: PASS，4 个用例全绿

- [ ] **Step 5: 接进 AccountManager**

在 `protocol-layer/src/worker/account-manager.ts` 做四处改动：

1. 顶部 import 区（`attachEventBridge` 那行附近）加：

```ts
import { AccountContactStore, type ContactRecord } from './contact-store.js'
import { attachContactStore } from './contact-store-bridge.js'
```

2. 类字段区（`private accountCaches = new Map<string, AccountCaches>()` 那行下面）加：

```ts
  /** 账号联系人投影。与 accountCaches 同生命周期：终态下线时释放。 */
  private accountContacts = new Map<string, AccountContactStore>()
```

3. `ctx.detachers.push(detachEventBridge)` 那行之后紧接着加：

```ts
    // 接 Baileys contacts 事件 → 账号联系人投影
    const contactStore = this.getOrCreateContactStore(ctx.accountId)
    ctx.detachers.push(attachContactStore(sock, contactStore))
```

4. 在 `disposeCaches` 方法之后加两个方法：

```ts
  /** 取账号联系人投影：已存在则复用，否则新建。 */
  private getOrCreateContactStore(accountId: string): AccountContactStore {
    let store = this.accountContacts.get(accountId)
    if (!store) {
      store = new AccountContactStore()
      this.accountContacts.set(accountId, store)
    }
    return store
  }

  /**
   * 读取账号当前通讯录投影。
   *
   * @param accountId 协议账号 ID
   * @returns 联系人快照
   */
  getContacts(accountId: string): ContactRecord[] {
    // 复用 getSocket 的在线校验：不在线直接抛 AccountUnavailableError
    this.getSocket(accountId)
    return this.accountContacts.get(accountId)?.list() ?? []
  }
```

5. 在 `disposeCaches` 方法体末尾（`this.accountCaches.delete(accountId)` 之后）加：

```ts
    this.accountContacts.get(accountId)?.clear()
    this.accountContacts.delete(accountId)
```

- [ ] **Step 6: 跑 worker 全量测试确认没打坏既有行为**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/worker
```

Expected: PASS，包括既有的 `account-caches.test.ts`、`account-cache-lifecycle.test.ts`

- [ ] **Step 7: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/worker/contact-store-bridge.ts \
        protocol-layer/src/worker/contact-store-bridge.test.ts \
        protocol-layer/src/worker/account-manager.ts
git commit -m "feat(contacts): wire contact projection into account lifecycle"
```

---

### Task 3: Web 协议 `GET /v1/accounts/{accountId}/contacts`

**Files:**
- Modify: `protocol-layer/src/routes/contacts.ts`
- Modify: `openapi/protocol-v1.yaml`（在 `/v1/contacts/{jid}/save` 之前插入新 path）
- Test: `protocol-layer/src/routes/contacts-list.test.ts`

**Interfaces:**
- Consumes: Task 2 的 `AccountManager.getContacts`
- Produces: HTTP `GET /v1/accounts/:accountId/contacts` → `{ accountId, contacts: ContactRecord[], syncedAt: number }`

- [ ] **Step 1: 写失败测试**

创建 `protocol-layer/src/routes/contacts-list.test.ts`：

```ts
import Fastify from 'fastify'

import { registerContactsRoutes } from './contacts.js'

function buildApp(getContacts: (accountId: string) => unknown[]) {
  const app = Fastify()
  const ctx = {
    accounts: { getContacts },
    operationGate: { runGroup: async (_a: string, _o: string, fn: () => Promise<void>) => fn() },
    logger: { info() {}, warn() {}, debug() {} }
  }
  registerContactsRoutes(app, ctx as never)
  return app
}

describe('GET /v1/accounts/:accountId/contacts', () => {
  it('返回账号联系人快照并带 syncedAt', async () => {
    const app = buildApp(() => [
      { phone: '8613800000000', jid: '8613800000000@s.whatsapp.net', name: '张三' }
    ])

    const res = await app.inject({ method: 'GET', url: '/v1/accounts/acc_7/contacts' })

    expect(res.statusCode).toBe(200)
    const body = res.json()
    expect(body.accountId).toBe('acc_7')
    expect(body.contacts).toEqual([
      { phone: '8613800000000', jid: '8613800000000@s.whatsapp.net', name: '张三' }
    ])
    expect(typeof body.syncedAt).toBe('number')
    await app.close()
  })

  it('联系人为空时返回空数组而不是 404', async () => {
    const app = buildApp(() => [])

    const res = await app.inject({ method: 'GET', url: '/v1/accounts/acc_8/contacts' })

    expect(res.statusCode).toBe(200)
    expect(res.json().contacts).toEqual([])
    await app.close()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/routes/contacts-list.test.ts
```

Expected: FAIL，404（路由未注册）

- [ ] **Step 3: 写最小实现**

在 `protocol-layer/src/routes/contacts.ts` 的 `registerContactsRoutes` 函数体最前面（`app.post('/v1/contacts/:jid/save', ...)` 之前）插入：

```ts
  app.get('/v1/accounts/:accountId/contacts', async (req, reply) => {
    const { accountId } = z.object({ accountId: z.string() }).parse(req.params)
    const contacts = ctx.accounts.getContacts(accountId)
    reply.send({ accountId, contacts, syncedAt: Date.now() })
  })
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/routes/contacts-list.test.ts src/routes/contacts-save-app-state-key.test.ts
```

Expected: PASS，新旧用例都绿

- [ ] **Step 5: 补 OpenAPI**

在 `openapi/protocol-v1.yaml` 的 `/v1/contacts/{jid}/save:` 之前插入：

```yaml
  /v1/accounts/{accountId}/contacts:
    get:
      tags: [contacts]
      summary: 读取账号当前通讯录投影
      description: >-
        返回该账号 socket 生命周期内由 Baileys app-state 同步累积的联系人投影。
        协议层不落库，账号离线后投影释放。
      operationId: listAccountContacts
      parameters:
        - $ref: '#/components/parameters/AccountIdPath'
      responses:
        '200':
          description: 联系人快照
          content:
            application/json:
              schema:
                type: object
                required: [accountId, contacts, syncedAt]
                properties:
                  accountId: { type: string }
                  syncedAt: { type: integer, format: int64 }
                  contacts:
                    type: array
                    items:
                      type: object
                      required: [phone, jid]
                      properties:
                        phone: { type: string, description: 不带加号的纯数字号码 }
                        jid: { type: string }
                        name: { type: string, description: 通讯录名 }
                        notify: { type: string, description: 对方设置的展示名 }
                        verifiedName: { type: string, description: 商业号认证名 }
```

同时把文件顶部约 107 行的能力表格里那行改为：

```
    | 读通讯录 / 添加 / 删除联系人 | `GET /v1/accounts/{accountId}/contacts` / `POST /v1/contacts/{jid}/save` / `DELETE /v1/contacts/{jid}` |
```

- [ ] **Step 6: 重新生成 OpenAPI 类型并确认无 diff 遗漏**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/openapi
./regenerate-types.sh
cd ../protocol-layer && npx tsc --noEmit
```

Expected: 类型生成成功，`tsc` 无报错

- [ ] **Step 7: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/routes/contacts.ts \
        protocol-layer/src/routes/contacts-list.test.ts \
        openapi/protocol-v1.yaml openapi/generated
git commit -m "feat(contacts): expose account contact list endpoint"
```

---

### Task 4: Android 协议 `POST /ws/v1/contacts/list/{key}`

**Files:**
- Modify: `api/service/sync.go`（仓库 `whatsapp-server`）
- Modify: `api/controller/sync.go`
- Modify: `api/router/router.go:107-113`
- Test: `api/service/sync_list_test.go`

**Interfaces:**
- Consumes: 既有 `store.InMysqlContacts.LoadContacts(ownerId)`（`internal/service/axolotl/store/contacts.go:58`）
- Produces: `func ListContactsService(k string) vo.Resp` — data 为 `[]gin.H{ {"phone","jid","fullName","firstName","pushName","businessName"} }`

- [ ] **Step 1: 写失败测试**

创建 `api/service/sync_list_test.go`：

```go
package service

import (
	"testing"
	"ws-go/internal/service/appstate"
	"ws-go/internal/service/axolotl/store"
)

// TestBuildContactListPayload 校验通讯录行到 HTTP 载荷的转换。
func TestBuildContactListPayload(t *testing.T) {
	rows := []store.Contacts{
		{
			OwnerId:      "8613800000000",
			BusinessName: "某商铺",
			ContactEntry: appstate.ContactEntry{
				JID:       "8613800000001",
				FirstName: "三",
				FullName:  "张三",
				PnJID:     "zhangsan",
			},
		},
		{
			OwnerId:      "8613800000000",
			ContactEntry: appstate.ContactEntry{JID: "8613800000002"},
		},
	}

	payload := buildContactListPayload(rows)

	if len(payload) != 2 {
		t.Fatalf("expected 2 contacts, got %d", len(payload))
	}
	if payload[0]["phone"] != "8613800000001" {
		t.Errorf("phone = %v, want 8613800000001", payload[0]["phone"])
	}
	if payload[0]["jid"] != "8613800000001@s.whatsapp.net" {
		t.Errorf("jid = %v, want 8613800000001@s.whatsapp.net", payload[0]["jid"])
	}
	if payload[0]["fullName"] != "张三" {
		t.Errorf("fullName = %v, want 张三", payload[0]["fullName"])
	}
	if payload[0]["businessName"] != "某商铺" {
		t.Errorf("businessName = %v, want 某商铺", payload[0]["businessName"])
	}
	if payload[1]["fullName"] != "" {
		t.Errorf("empty fullName should stay empty, got %v", payload[1]["fullName"])
	}
}

// TestBuildContactListPayloadSkipsBlankJID 校验空 JID 行被丢弃。
func TestBuildContactListPayloadSkipsBlankJID(t *testing.T) {
	rows := []store.Contacts{
		{ContactEntry: appstate.ContactEntry{JID: ""}},
		{ContactEntry: appstate.ContactEntry{JID: "8613800000003"}},
	}

	payload := buildContactListPayload(rows)

	if len(payload) != 1 {
		t.Fatalf("expected 1 contact, got %d", len(payload))
	}
	if payload[0]["phone"] != "8613800000003" {
		t.Errorf("phone = %v, want 8613800000003", payload[0]["phone"])
	}
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go test ./api/service/ -run TestBuildContactListPayload -v
```

Expected: FAIL，`undefined: buildContactListPayload`

- [ ] **Step 3: 写最小实现**

在 `api/service/sync.go` 文件末尾追加：

```go
// buildContactListPayload 把通讯录数据行转换为 HTTP 响应载荷。
// JID 为空的行直接丢弃；号码统一为不带加号的纯数字，JID 统一补 @s.whatsapp.net。
func buildContactListPayload(rows []store.Contacts) []gin.H {
	payload := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		phone := strings.TrimSpace(row.JID)
		if phone == "" {
			continue
		}
		if idx := strings.Index(phone, "@"); idx >= 0 {
			phone = phone[:idx]
		}
		if phone == "" {
			continue
		}
		payload = append(payload, gin.H{
			"phone":        phone,
			"jid":          phone + "@s.whatsapp.net",
			"fullName":     row.FullName,
			"firstName":    row.FirstName,
			"pushName":     row.PnJID,
			"businessName": row.BusinessName,
		})
	}
	return payload
}

// ListContactsService 读取账号已同步落库的通讯录。
// 与 SyncQueryContactsService 不同，本接口不发起 usync，只读本地 wa_contacts 表，
// 因此账号不在线也能返回上一次同步结果。
func ListContactsService(k string) vo.Resp {
	contact := &store.Contacts{}
	rows, err := contact.LoadContacts(k)
	if err != nil {
		return vo.AnErrorOccurred(fmt.Sprintf("读取通讯录失败, %s", err.Error()))
	}
	return vo.SuccessJson(buildContactListPayload(rows), "")
}
```

同时在 `api/service/sync.go` 的 import 块中补上 `"strings"` 和 `"ws-go/internal/service/axolotl/store"`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go test ./api/service/ -run TestBuildContactListPayload -v
```

Expected: PASS，两个用例全绿

- [ ] **Step 5: 加 controller**

在 `api/controller/sync.go` 的 `SyncQueryContactController` 之后插入：

```go
// ListContactsController 读取通讯录
// @Summary 读取通讯录
// @Description 读取账号已同步落库的 WhatsApp 通讯录，不发起 usync
// @Tags 联系人
// @Accept json
// @Produce json
// @Param key path string true "手机号"
// @Success 200 {object} vo.Resp "响应结果：0 - 成功；1001 - 系统错误；1002 - 参数错误；1004 - 普通错误"
// @Router /ws/v1/contacts/list/{key} [post]
func ListContactsController(ctx *gin.Context) {
	resp := service.ListContactsService(ctx.Param("key"))
	ctx.JSON(http.StatusOK, &resp)
}
```

- [ ] **Step 6: 注册路由**

在 `api/router/router.go` 的 contacts 分组内（`contacts.POST("/query/:key", ...)` 之前）加：

```go
		contacts.POST("/list/:key", controller.ListContactsController)
```

- [ ] **Step 7: 编译 + 跑路由测试**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go build ./... && go test ./api/... -v
```

Expected: 编译通过，`api/router/router_test.go` 与新增测试全绿

- [ ] **Step 8: 提交**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
git add api/service/sync.go api/service/sync_list_test.go api/controller/sync.go api/router/router.go
git commit -m "feat(contacts): expose stored contact list over http"
```

---

### Task 5: armada `ContactListPort` 与两个 backend

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/AccountContactSnapshot.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/ContactListPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/ContactListBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingContactListPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebContactListAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeContactListAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java`（加 `listContacts`）
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`（注册 bean）
- Test: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingContactListPortTest.java`

**Interfaces:**
- Consumes: Task 3 的 `GET /v1/accounts/{id}/contacts`、Task 4 的 `POST /ws/v1/contacts/list/{key}`
- Produces:
  - `record AccountContactSnapshot(List<Contact> contacts, Long syncedAt)`，内嵌 `record Contact(String phone, String jid, String fullName, String firstName, String pushName, String businessName)`
  - `interface ContactListPort { AccountContactSnapshot list(ProtocolAccountRef account); }`

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingContactListPortTest.java`：

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingContactListPortTest {

    @Test
    void routesOnlyToTheBackendSelectedByTheAccountReference() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingContactListPort port = new RoutingContactListPort(List.of(web, android));
        ProtocolAccountRef account = account(ProtocolBackend.ANDROID);

        AccountContactSnapshot snapshot = port.list(account);

        assertThat(web.lastAccount).isNull();
        assertThat(android.lastAccount).isSameAs(account);
        assertThat(snapshot.contacts()).hasSize(1);
        assertThat(snapshot.contacts().get(0).phone()).isEqualTo("919000000002");
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingContactListPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingContactListPort port = new RoutingContactListPort(List.of(web));
        assertThatThrownBy(() -> port.list(account(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("contact.list");
                });
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(7L, backend, "acc_7", "919000000001");
    }

    private static final class RecordingBackend implements ContactListBackend {
        private final ProtocolBackend backend;
        private ProtocolAccountRef lastAccount;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public AccountContactSnapshot list(ProtocolAccountRef account) {
            lastAccount = account;
            return new AccountContactSnapshot(
                    List.of(new AccountContactSnapshot.Contact(
                            "919000000002",
                            "919000000002@s.whatsapp.net",
                            "张三",
                            "三",
                            "zhangsan",
                            null)),
                    1756345678901L);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada
mvn -q -pl armada-api test -Dtest=RoutingContactListPortTest
```

Expected: FAIL，编译错误 `cannot find symbol: class ContactListBackend`

- [ ] **Step 3: 写模型与端口**

`AccountContactSnapshot.java`：

```java
package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 一次账号通讯录读取的协议事实。
 *
 * @param contacts 联系人列表，协议层已做号码归一
 * @param syncedAt 协议层给出的快照时间（epoch 毫秒），可能为空
 */
public record AccountContactSnapshot(List<Contact> contacts, Long syncedAt) {

    /**
     * 单个联系人。
     *
     * @param phone 不带加号的纯数字号码
     * @param jid 规范用户 JID
     * @param fullName 通讯录全名
     * @param firstName 通讯录名
     * @param pushName 对方设置的展示名
     * @param businessName 商业号认证名
     */
    public record Contact(
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

`ContactListPort.java`：

```java
package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;

/**
 * WhatsApp 账号通讯录读取协议端口。
 */
public interface ContactListPort {

    /**
     * 读取指定账号当前可得的通讯录。
     *
     * @param account 统一协议账号引用
     * @return 通讯录快照
     */
    AccountContactSnapshot list(ProtocolAccountRef account);
}
```

`ContactListBackend.java`：

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;

/**
 * 单个协议后端的通讯录读取实现。
 */
public interface ContactListBackend {

    /**
     * 当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 读取指定账号通讯录。
     *
     * @param account 统一协议账号引用
     * @return 通讯录快照
     */
    AccountContactSnapshot list(ProtocolAccountRef account);
}
```

`RoutingContactListPort.java`（与 `RoutingContactPort` 同构）：

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.port.ContactListPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发通讯录读取的统一端口。
 */
public final class RoutingContactListPort implements ContactListPort {

    private static final String OPERATION = "contact.list";

    private final Map<ProtocolBackend, ContactListBackend> backends;

    /**
     * 创建通讯录路由端口，并拒绝同一协议后端的重复实现。
     *
     * @param implementations 所有通讯录读取协议后端
     */
    public RoutingContactListPort(List<ContactListBackend> implementations) {
        EnumMap<ProtocolBackend, ContactListBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (ContactListBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                ContactListBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的通讯录协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public AccountContactSnapshot list(ProtocolAccountRef account) {
        ProtocolBackend backend = account.backend();
        ContactListBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "通讯录协议后端未注册 backend=" + backend)
                    .withContext(backend, OPERATION, "account:" + account.armadaAccountId());
        }
        return implementation.list(account);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada
mvn -q -pl armada-api test -Dtest=RoutingContactListPortTest
```

Expected: PASS，两个用例全绿

- [ ] **Step 5: 提交路由层**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/platform/protocol/model/result/AccountContactSnapshot.java \
        armada-api/src/main/java/com/armada/platform/protocol/port/ContactListPort.java \
        armada-api/src/main/java/com/armada/platform/protocol/routing/ContactListBackend.java \
        armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingContactListPort.java \
        armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingContactListPortTest.java
git commit -m "feat(contact): add contact list protocol port"
```

- [ ] **Step 6: 写 Web adapter**

创建 `WebContactListAdapter.java`（模板：`WebAccountRuntimeStatusAdapter`）：

```java
package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.routing.ContactListBackend;

import java.util.List;

/**
 * Web/Baileys 原生通讯录读取 backend。
 *
 * <p>读取 {@code GET /v1/accounts/{protocolAccountId}/contacts}。协议层只维护 socket
 * 生命周期内的投影，账号离线时该接口会以账号不可用失败，由调用方决定是否降级。</p>
 */
public final class WebContactListAdapter implements ContactListBackend {

    private static final String ACCOUNT_URI_PREFIX = "/v1/accounts/";
    private static final String CONTACTS_URI_SUFFIX = "/contacts";
    private static final String LIST_OPERATION = "contact.list";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建 Web 通讯录读取 adapter。
     *
     * @param httpExecutor Web 协议后端 HTTP 执行器
     */
    public WebContactListAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public AccountContactSnapshot list(ProtocolAccountRef account) {
        try {
            ContactsResponse response = httpExecutor.getTyped(
                    ACCOUNT_URI_PREFIX + account.protocolAccountId() + CONTACTS_URI_SUFFIX,
                    ContactsResponse.class);
            if (response == null || response.contacts() == null) {
                return new AccountContactSnapshot(List.of(), null);
            }
            return new AccountContactSnapshot(
                    response.contacts().stream()
                            .map(item -> new AccountContactSnapshot.Contact(
                                    item.phone(),
                                    item.jid(),
                                    item.name(),
                                    null,
                                    item.notify(),
                                    item.verifiedName()))
                            .toList(),
                    response.syncedAt());
        } catch (ProtocolException ex) {
            throw ex.withContext(
                    ProtocolBackend.WEB,
                    LIST_OPERATION,
                    ACCOUNT_OPERATION_PREFIX + account.armadaAccountId());
        }
    }

    private record ContactsResponse(String accountId, Long syncedAt, List<ContactItem> contacts) {
    }

    private record ContactItem(
            String phone, String jid, String name, String notify, String verifiedName) {
    }
}
```

> 两处协议差异，都是事实不是缺陷，写进 Javadoc 防止后人当 bug 修：
> ① Baileys `Contact` 没有 `firstName` 概念（只有 `name` / `notify` / `verifiedName`），
>    因此 Web 侧 `firstName` 恒为 null；
> ② Web 的 `verifiedName`（商业号认证名）映射到统一模型的 `businessName`，
>    与 Android 的 `business_name` 列同义。

- [ ] **Step 7: 写 Android adapter 并扩 client**

在 `AndroidNativeClient.java` 的 `saveContacts` 声明之后加：

```java
    /**
     * 读取 Android 账号已同步落库的通讯录。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @return Android 原生响应包
     */
    AndroidResponseEnvelope listContacts(String wsPhone);
```

在 `HttpAndroidNativeClient.java` 顶部常量区（`CONTACTS_ADD_URI_PREFIX` 那行下面）加：

```java
    private static final String CONTACTS_LIST_URI_PREFIX = "/ws/v1/contacts/list/";
```

并在 `saveContacts` 方法之后加实现：

```java
    /**
     * 读取 Zhuan 原生已落库的通讯录。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @return Android 原生响应包
     */
    @Override
    public AndroidResponseEnvelope listContacts(String wsPhone) {
        return httpExecutor.postTyped(
                CONTACTS_LIST_URI_PREFIX + requireDigits(wsPhone),
                null,
                AndroidResponseEnvelope.class);
    }
```

创建 `AndroidNativeContactListAdapter.java`：

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.routing.ContactListBackend;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Android Zhuan 原生通讯录读取 backend。
 *
 * <p>读取 {@code POST /ws/v1/contacts/list/{wsPhone}}。Android 侧联系人已由 app-state
 * 同步落库，因此账号短暂离线也能拿到上一次同步结果。</p>
 */
public final class AndroidNativeContactListAdapter implements ContactListBackend {

    private static final String LIST_OPERATION = "contact.list";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    /**
     * 创建 Android 原生通讯录读取 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 操作错误 mapper
     */
    public AndroidNativeContactListAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    @Override
    public AccountContactSnapshot list(ProtocolAccountRef account) {
        try {
            AndroidDecodedResponse response =
                    decoder.decode(client.listContacts(account.wsPhone()));
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        account,
                        LIST_OPERATION,
                        "account:" + account.armadaAccountId());
            }
            return new AccountContactSnapshot(toContacts(response.data()), null);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    LIST_OPERATION,
                    "account:" + account.armadaAccountId());
        }
    }

    /** Go 侧 vo.SuccessJson 把列表放在 Data 字段，因此这里的 data 就是联系人数组。 */
    private static List<AccountContactSnapshot.Contact> toContacts(JsonNode data) {
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<AccountContactSnapshot.Contact> contacts = new ArrayList<>(data.size());
        for (JsonNode row : data) {
            String phone = text(row.path("phone"));
            if (phone == null) {
                continue;
            }
            contacts.add(new AccountContactSnapshot.Contact(
                    phone,
                    text(row.path("jid")),
                    text(row.path("fullName")),
                    text(row.path("firstName")),
                    text(row.path("pushName")),
                    text(row.path("businessName"))));
        }
        return List.copyOf(contacts);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String trimmed = node.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
```

- [ ] **Step 8: 注册 bean**

在 `ProtocolConfiguration.java` 的 `contactPort` bean 之后插入三个 bean：

```java
    /**
     * 注册 Web/Baileys 通讯录读取后端。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Web/Baileys 通讯录读取后端
     */
    @Bean
    public ContactListBackend webContactListBackend(ProtocolHttpExecutorRegistry registry) {
        return new WebContactListAdapter(registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 原生通讯录读取后端。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @return Android Zhuan 通讯录读取后端
     */
    @Bean
    public ContactListBackend androidContactListBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        return new AndroidNativeContactListAdapter(client, decoder, errorMapper);
    }

    /**
     * 注册统一通讯录读取端口，由路由实现根据账号协议后端选择具体 backend。
     *
     * @param backends Spring 收集的所有通讯录读取 backend
     * @return 后端感知的统一通讯录读取端口
     */
    @Bean
    public ContactListPort contactListPort(List<ContactListBackend> backends) {
        return new RoutingContactListPort(backends);
    }
```

同时补五个 import：

```java
import com.armada.platform.protocol.backend.android.AndroidNativeContactListAdapter;
import com.armada.platform.protocol.backend.web.WebContactListAdapter;
import com.armada.platform.protocol.port.ContactListPort;
import com.armada.platform.protocol.routing.ContactListBackend;
import com.armada.platform.protocol.routing.RoutingContactListPort;
```

- [ ] **Step 9: 全量编译 + 测试**

```bash
cd /home/yanwenchao/ideaProject/armada
mvn -q -pl armada-api test
```

Expected: BUILD SUCCESS，无既有用例回归

- [ ] **Step 10: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/platform/protocol/backend/
git add armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java
git commit -m "feat(contact): implement web and android contact list adapters"
```

---

### Task 6: `MessageTarget` 中立化

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java:30-36`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/MarketingMessageCommandFactory.java:88`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java:1914`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java:67`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java:229`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/model/command/MessageTargetTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `MessageSendCommand.MessageTarget(String jid)` — `jid` 可以是群 JID（`@g.us`）或私聊 JID（`@s.whatsapp.net`）

- [ ] **Step 1: 写失败测试**

创建 `armada-api/src/test/java/com/armada/platform/protocol/model/command/MessageTargetTest.java`：

```java
package com.armada.platform.protocol.model.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTargetTest {

    @Test
    void acceptsGroupJid() {
        MessageSendCommand.MessageTarget target =
                new MessageSendCommand.MessageTarget("120363000000000000@g.us");
        assertThat(target.jid()).isEqualTo("120363000000000000@g.us");
    }

    @Test
    void acceptsPeerJid() {
        MessageSendCommand.MessageTarget target =
                new MessageSendCommand.MessageTarget("8613800000000@s.whatsapp.net");
        assertThat(target.jid()).isEqualTo("8613800000000@s.whatsapp.net");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada
mvn -q -pl armada-api test -Dtest=MessageTargetTest
```

Expected: FAIL，`cannot find symbol: method jid()`

- [ ] **Step 3: 改 record 与四处调用点**

`MessageSendCommand.java` 中把：

```java
    /**
     * 消息发送目标。
     *
     * @param groupJid WhatsApp 群 JID
     */
    public record MessageTarget(String groupJid) {
    }
```

改为：

```java
    /**
     * 消息发送目标。
     *
     * <p>语义中立：群营销填群 JID（{@code @g.us}），私聊营销填用户 JID
     * （{@code <phone>@s.whatsapp.net}）。协议后端按 JID 后缀自行分支，不再假定目标一定是群。</p>
     *
     * @param jid WhatsApp 目标 JID
     */
    public record MessageTarget(String jid) {
    }
```

四处调用点全部把 `.target().groupJid()` 改为 `.target().jid()`：

- `MarketingMessageCommandFactory.java:88` — 构造处 `new MessageSendCommand.MessageTarget(resolved.groupJid())` 保持不变（入参仍是群 JID，只是形参名变了）
- `ProtocolCommandOutboxServiceImpl.java:1914` — `isBlank(outboxCommand.command().target().groupJid())` → `isBlank(outboxCommand.command().target().jid())`
- `WebMessageSendBackend.java:67` — `command.target().groupJid()` → `command.target().jid()`
- `AndroidMessageSendBackend.java:229` — 同上

**Kafka 线上契约不动**：`WebMessagePayload` / `AndroidMessagePayload` 的字段名仍叫 `groupJid`，
两侧协议消费者不需要同步发版。改字段名是 P3 之后的独立收尾项，不塞进本任务。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada
mvn -q -pl armada-api test -Dtest=MessageTargetTest
```

Expected: PASS

- [ ] **Step 5: 全量回归（这一步不能省）**

```bash
cd /home/yanwenchao/ideaProject/armada
mvn -q -pl armada-api test
```

Expected: BUILD SUCCESS。`MessageTarget` 是跨业务共享 record，营销、建群营销、历史群营销三条链路都用它，只跑新增测试不算数。

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java \
        armada-api/src/main/java/com/armada/marketing/service/MarketingMessageCommandFactory.java \
        armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java \
        armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java \
        armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java \
        armada-api/src/test/java/com/armada/platform/protocol/model/command/MessageTargetTest.java
git commit -m "refactor(protocol): make message target jid neutral"
```

---

### Task 7: Web 协议放开私聊发送

**Files:**
- Modify: `protocol-layer/src/commands/worker-consumer.ts`（`executeMessageSend` 约 765 行、`messageSendPayload` 约 1140 行）
- Test: `protocol-layer/src/commands/message-send-peer.test.ts`

**Interfaces:**
- Consumes: Task 6 之后 armada 下发的 payload，`groupJid` 字段值可能是 `<phone>@s.whatsapp.net`，`source` 可能是 `contact_task`
- Produces:
  - `function isPeerJid(jid: string): boolean` — 从 `worker-consumer.ts` 导出，供测试直接调用
  - `contact_task` source 分支：要求 `contactTaskId` / `taskAccountId` / `recipientId` / `roundNo` 四个字段都在

- [ ] **Step 1: 写失败测试**

创建 `protocol-layer/src/commands/message-send-peer.test.ts`：

```ts
import { isPeerJid } from './worker-consumer.js'

describe('isPeerJid', () => {
  it('识别私聊 JID', () => {
    expect(isPeerJid('8613800000000@s.whatsapp.net')).toBe(true)
  })

  it('群 JID 不是私聊', () => {
    expect(isPeerJid('120363000000000000@g.us')).toBe(false)
  })

  it('广播和空值不是私聊', () => {
    expect(isPeerJid('status@broadcast')).toBe(false)
    expect(isPeerJid('')).toBe(false)
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/commands/message-send-peer.test.ts
```

Expected: FAIL，`isPeerJid is not a function`

- [ ] **Step 3: 写实现**

在 `protocol-layer/src/commands/worker-consumer.ts` 中加导出函数（放在 `skippedGroupSendability` 附近）：

```ts
/**
 * 判断目标 JID 是否为私聊。
 *
 * 私聊没有群成员、公告和管理员概念，因此不做群可发送性预检。
 *
 * @param jid 目标 JID
 * @returns 是私聊则 true
 */
export function isPeerJid(jid: string): boolean {
  return typeof jid === 'string' && jid.endsWith('@s.whatsapp.net')
}
```

在 `executeMessageSend` 中把：

```ts
  const groupSendability = payload.source === 'historical_group_pull'
    ? skippedGroupSendability()
    : await resolveGroupSendability(command, payload, deps)
```

改为：

```ts
  // 私聊目标没有群语义，与 historical_group_pull 一样有意跳过预检
  const groupSendability = payload.source === 'historical_group_pull' || isPeerJid(payload.groupJid)
    ? skippedGroupSendability()
    : await resolveGroupSendability(command, payload, deps)
```

在 `messageSendPayload` 的 source 分支里，把：

```ts
  } else if (source === 'historical_group_pull') {
    if (historicalExecutionId === null || historicalMemberId === null) {
      throw new Error('invalid message send payload')
    }
  } else if (marketingTaskId === null || attemptId === null || targetId === null || roundNo === null) {
```

改为：

```ts
  } else if (source === 'historical_group_pull') {
    if (historicalExecutionId === null || historicalMemberId === null) {
      throw new Error('invalid message send payload')
    }
  } else if (source === 'contact_task') {
    if (
      contactTaskId === null ||
      taskAccountId === null ||
      recipientId === null ||
      roundNo === null
    ) {
      throw new Error('invalid message send payload')
    }
  } else if (marketingTaskId === null || attemptId === null || targetId === null || roundNo === null) {
```

并在该函数上方的字段解析区加：

```ts
  const contactTaskId = numericPayloadField(payload, 'contactTaskId')
  const taskAccountId = numericPayloadField(payload, 'taskAccountId')
  const recipientId = numericPayloadField(payload, 'recipientId')
```

同时在 `MessageSendPayload` 接口里加三个可选字段：

```ts
  contactTaskId?: number
  taskAccountId?: number
  recipientId?: number
```

并在函数末尾返回对象里带上这三个字段。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
npx jest src/commands/message-send-peer.test.ts src/commands/worker-consumer.test.ts
```

Expected: PASS，新增用例与既有 `worker-consumer.test.ts` 全绿

- [ ] **Step 5: 提交**

```bash
cd /home/yanwenchao/ideaProject/armada-protocol
git add protocol-layer/src/commands/worker-consumer.ts \
        protocol-layer/src/commands/message-send-peer.test.ts
git commit -m "feat(message): allow peer chat sends and contact task source"
```

> **下游契约（P3 必须对齐）**：本任务冻结了 `source = 'contact_task'` 时 Kafka payload 必须携带
> `contactTaskId` / `taskAccountId` / `recipientId` / `roundNo` 四个数值字段，缺一即被判为
> `invalid message send payload` 丢弃。P3 的 `ContactTaskCorrelation` 与 Web/Android backend
> 编码这四个字段时字段名必须逐字一致。

---

### Task 8: Android 协议放开私聊发送

**Files:**
- Modify: `internal/armada/message_sender.go:100-101`（仓库 `whatsapp-server`）
- Test: `internal/armada/message_sender_peer_test.go`

**Interfaces:**
- Consumes: Task 6 之后 armada 下发的 payload，`GroupJID` 字段值可能是 `<phone>@s.whatsapp.net`
- Produces: `func isPeerTarget(target jabber.JID) bool` — 目标 server 为用户域时返回 true

- [ ] **Step 1: 写失败测试**

创建 `internal/armada/message_sender_peer_test.go`：

```go
package armada

import (
	"testing"
	"ws-go/internal/service/jabber"
)

// TestIsPeerTarget 校验私聊目标识别。
func TestIsPeerTarget(t *testing.T) {
	peer, err := jabber.ParseJID("8613800000000@s.whatsapp.net")
	if err != nil {
		t.Fatalf("parse peer jid: %v", err)
	}
	if !isPeerTarget(peer) {
		t.Errorf("isPeerTarget(peer) = false, want true")
	}

	group, err := jabber.ParseJID("120363000000000000@g.us")
	if err != nil {
		t.Fatalf("parse group jid: %v", err)
	}
	if isPeerTarget(group) {
		t.Errorf("isPeerTarget(group) = true, want false")
	}
}

// TestIsPeerTargetRejectsEmptyUser 校验空用户段不算私聊。
func TestIsPeerTargetRejectsEmptyUser(t *testing.T) {
	if isPeerTarget(jabber.JID{User: "", Server: jabber.DefaultUserServer}) {
		t.Errorf("empty user should not be a peer target")
	}
}
```

> `jabber.DefaultUserServer`（`= "s.whatsapp.net"`）与 `jabber.GroupServer`（`= "g.us"`）
> 都在 `internal/service/jabber/jabber.go:13-14`，直接用，不要新增常量。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go test ./internal/armada/ -run TestIsPeerTarget -v
```

Expected: FAIL，`undefined: isPeerTarget`

- [ ] **Step 3: 写实现**

在 `internal/armada/message_sender.go` 中加：

```go
// isPeerTarget 判断目标是否为私聊对端。
// 私聊没有群成员、公告和管理员概念，因此不走群可发送性预检。
func isPeerTarget(target jabber.JID) bool {
	return target.User != "" && target.Server == jabber.DefaultUserServer
}
```

把第 100~106 行的目标校验：

```go
	group, err := jabber.ParseJID(command.Payload.GroupJID)
	if err != nil || group.User == "" || group.Server != jabber.GroupServer {
```

改为：

```go
	target, err := jabber.ParseJID(command.Payload.GroupJID)
	isGroup := err == nil && target.User != "" && target.Server == jabber.GroupServer
	isPeer := err == nil && isPeerTarget(target)
	if err != nil || (!isGroup && !isPeer) {
```

并把该函数内后续所有 `group` 变量引用改名为 `target`（`PrepareText` / `PrepareImage` /
`PrepareLinkCard` / `PrepareButtonCard` 的入参、`MessagePrepared{... Group: target ...}`）。

在群可发送性预检的调用点（`resolveFailedMessageResult` 与 `ResolveGroupSendability` 那一段，
约 340~394 行）最前面加提前返回：

```go
	if isPeerTarget(target) {
		// 私聊有意跳过预检，与 Web 侧 skippedGroupSendability 同语义
		return newGroupSendabilitySnapshot(
			groupSendStatusUnconfirmed, "PRECHECK_SKIPPED_BY_PEER_TARGET", s.now()), nil
	}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go test ./internal/armada/ -run TestIsPeerTarget -v
```

Expected: PASS，两个用例全绿

- [ ] **Step 5: 全量回归**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
go build ./... && go test ./internal/armada/...
```

Expected: 编译通过，既有 `message_sender_test.go`、`message_sendability_test.go` 无回归

- [ ] **Step 6: 提交**

```bash
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan
git add internal/armada/message_sender.go internal/armada/message_sender_peer_test.go
git commit -m "feat(message): allow peer chat sends on android backend"
```

---

## 真机验证清单（本计划的出口条件）

代码全绿不等于本期完成。以下三项在真机上跑通并记录结论后，P0/P1 才算交付：

| # | 验证项 | 方法 | 失败时的影响 |
|---|---|---|---|
| V1 | Baileys contact store 冷启动后是否全量 | 同一 Web 账号冷启动 → `GET /v1/accounts/{id}/contacts` → 与手机端通讯录条数比对 | 若只拿到增量，Web 侧需追加一次主动 app-state resync，Task 2 要返工 |
| V2 | 双向好友标记两侧是否可得 | 用一个已知双向/单向混合的号，比对 Web `Contact` 与 Android `wa_contacts` 可得字段 | 若都拿不到，P2 只交付 `contactNamedNum`，`双向好友数 ≥/≤` 筛选控件不渲染 |
| V3 | 私聊群发风控表现 | 用测试号按 0.1s / 0.5~1s / 1~3s 三档各发 50 条，记录封号率 | 决定 P3 默认间隔取值，可能推翻竞品的「最快 0.1s」预设 |

验证结论写进 `docs/superpowers/reviews/2026-08-28-contact-protocol-verification.md`，
并回填到 spec 的 §5.1 与 §11。
