# Online Confirmed State Event Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clear Armada pending-online state when protocol confirms an online command for an account that was already online.

**Architecture:** Reuse the existing durable `account.state_changed` event channel. Protocol emits a synthetic `ONLINE -> ONLINE` confirmation event only when the state machine does not produce a real `ONLINE` transition because the account is already online; Armada consumes it through the existing state event path.

**Tech Stack:** TypeScript/Jest in `armada-protocol/protocol-layer`; Java 17/Spring Boot/MyBatis/JUnit in `armada/armada-api`.

---

### Task 1: Protocol Confirmation Event

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`
- Test: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.heartbeat.test.ts`

- [ ] **Step 1: Write the failing test**

Add a Jest test that brings `acc_online_confirmed` to `ONLINE`, sends a second `online()` with a new `businessRef`, emits `connection.update=open` on the second socket, and expects an `account.state_changed` event with:

```ts
{
  tenantId: 1,
  accountId: 100,
  protocolAccountId: 'acc_business',
  onlineAttemptId: 'oa_online_confirmed',
  commandId: 'cmd_online_confirmed',
  batchId: 'batch_online_confirmed',
  proxyId: 4036,
  source: 'batch_online',
  from: 'ONLINE',
  to: 'ONLINE',
  reason: 'online_confirmed',
  semantic: 'ONLINE_CONFIRMED'
}
```

- [ ] **Step 2: Run the protocol test to verify RED**

Run:

```bash
npm test -- src/worker/account-manager.heartbeat.test.ts --runInBand
```

Expected: the new test fails because the confirmation `account.state_changed` is not published.

- [ ] **Step 3: Implement minimal protocol code**

In `handleConnectionUpdate`, replace the direct online transition call:

```ts
this.publishStateChange(ctx, 'ONLINE', 'ws_open_confirmed')
```

with:

```ts
if (!this.publishStateChange(ctx, 'ONLINE', 'ws_open_confirmed')) {
  this.publishOnlineConfirmed(ctx)
}
```

Add a private helper that only publishes when `ctx.state.state === 'ONLINE'` and `ctx.businessRef` exists:

```ts
private publishOnlineConfirmed(ctx: AccountContext): void {
  if (ctx.state.state !== 'ONLINE' || !ctx.businessRef) return
  void this.deps.publisher
    .publish('account.state_changed', ctx.accountId, {
      ...this.businessEventData(ctx),
      from: 'ONLINE',
      to: 'ONLINE',
      reason: 'online_confirmed',
      semantic: 'ONLINE_CONFIRMED',
      occurredAt: new Date().toISOString()
    }, this.getEvidence(ctx.accountId))
    .catch(publishErr =>
      this.logger.warn(
        { err: sanitizeErrorForLog(publishErr), accountId: ctx.accountId },
        'publish account online confirmation failed'
      )
    )
}
```

- [ ] **Step 4: Run protocol test to verify GREEN**

Run:

```bash
npm test -- src/worker/account-manager.heartbeat.test.ts --runInBand
```

Expected: the new test passes.

### Task 2: Armada Pending Settlement Regression

**Files:**
- Test: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java`

- [ ] **Step 1: Write the failing or characterization test**

Add a DbTest that marks an account as `PENDING_ONLINE`, then applies:

```java
event(account, "ONLINE", "ONLINE", now + 6_000L, "ONLINE_CONFIRMED", null)
```

Assert `login_state=ONLINE`, `state_source=ONLINE_CONFIRMED`, and `last_state_sync_time=now + 6_000L`.

- [ ] **Step 2: Run Armada DbTest**

Run:

```bash
./dbtest.sh 'AccountStateEventServiceImplDbTest#applyStateChanged_onlineConfirmedClearsPendingOnline'
```

Expected: pass if existing service already handles `to=ONLINE`; otherwise fail for the missing behavior.

### Task 3: Verification

**Files:**
- Read: `git diff` in both repositories

- [ ] **Step 1: Run targeted protocol verification**

Run:

```bash
npm test -- src/worker/account-manager.heartbeat.test.ts --runInBand
npm run lint
```

- [ ] **Step 2: Run targeted Armada verification**

Run:

```bash
./dbtest.sh 'AccountStateEventServiceImplDbTest#applyStateChanged_onlineConfirmedClearsPendingOnline'
```

- [ ] **Step 3: Review diffs**

Run:

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol diff -- src/worker/account-manager.ts src/worker/account-manager.heartbeat.test.ts
git -C /Users/daishuaishuai/IdeaProjects/armada diff -- armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java docs/superpowers/specs/2026-07-03-online-confirmed-state-event-design.md docs/superpowers/plans/2026-07-03-online-confirmed-state-event.md
```
