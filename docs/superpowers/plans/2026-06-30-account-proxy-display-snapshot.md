# Account Proxy Display Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve account list proxy display fields after proxy release.

**Architecture:** Extend the existing `account_state` runtime snapshot with `proxy_source`, write all proxy display fields when online allocation succeeds, and keep proxy release limited to `ip_proxy` binding state. The list mapper reads the snapshot first and the current binding as fallback.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway, MySQL DbTest.

---

### Task 1: Account State Snapshot Storage

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/AccountState.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`
- Create: `armada-api/src/main/resources/db/migration/V019__account_proxy_source_snapshot.sql`

- [ ] Add `proxySource` to `AccountState`.
- [ ] Add `updateProxySnapshot(AccountState row)` to `AccountStateMapper`.
- [ ] Add mapper XML to update `truth_ip`, `proxy_country`, `proxy_source`, and `updated_at` by `account_id`.
- [ ] Add Flyway migration for `account_state.proxy_source`.

### Task 2: Allocation Carries Source

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/IpProxyAllocation.java`
- Modify: `armada-api/src/main/java/com/armada/resource/service/IpProxyAccountAllocation.java`
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Modify tests that construct these records.

- [ ] Extend allocation records with `proxySource`.
- [ ] Populate `proxySource` from `IpProxy.source` in single and batch allocation.

### Task 3: Online Writes Snapshot

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/account/service/AccountOnlineCommandServiceImplDbTest.java`

- [ ] Write a failing DbTest proving batch online stores proxy country/source/address on `account_state`.
- [ ] Inject `AccountStateMapper` into the online command service.
- [ ] After successful allocation and before outbox enqueue, update proxy snapshots for each prepared account.
- [ ] On outbox enqueue failure, keep existing proxy release compensation.

### Task 4: List Reads Snapshot Source

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`

- [ ] Add `proxySource` to list row if needed by MyBatis mapping.
- [ ] Change list SQL to select `COALESCE(s.proxy_source, p.source) AS ipSource`.
- [ ] Write a failing DbTest proving list still shows proxy fields after release.

### Task 5: Harness Change Record and Verification

**Files:**
- Create: `.harness/changes/account-proxy-display-snapshot/summary.md`
- Create: `.harness/changes/account-proxy-display-snapshot/db-migrations.sql`
- Create: `.harness/changes/account-proxy-display-snapshot/rollback.sql`

- [ ] Document the DB/API impact and rollback path.
- [ ] Run focused DbTests.
- [ ] Run XML validation or a mapper DbTest covering the edited XML.
