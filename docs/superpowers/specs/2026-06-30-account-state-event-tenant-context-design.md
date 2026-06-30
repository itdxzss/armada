# Account State Event Tenant Context Design

## Goal

Fix Armada account state write-back for Kafka `account.state_changed` events by carrying `tenantId` and Armada `accountId` from the online command into protocol-layer state events, then restoring `TenantContext` before database updates.

## Current Failure

Armada receives protocol account state events, but Kafka listener threads do not have HTTP tenant context. MyBatis injects `tenant_id = -1`, so `selectActiveByProtocolAccountId` returns no row even when the account exists under tenant `1`.

## Design

Armada online command publishing will include `tenantId`, `accountId`, and `protocolAccountId` in the Kafka payload. Protocol worker execution will preserve that business reference on the account runtime context. When the protocol layer publishes `account.state_changed` and `account.need_reauth`, it will keep the top-level `accountId` as the protocol account ID for Kafka ordering, and add the business reference inside `data`.

Armada account event parsing will require `data.tenantId` and `data.accountId` for `account.state_changed`. The account service will temporarily set `TenantContext` to `tenantId`, load the account by local `accountId`, verify `protocol_account_id` matches the event protocol account ID, then apply the existing lifecycle transition logic.

## Non-Goals

- No topic rename.
- No database migration.
- No cross-tenant mapper bypass for normal state updates.
- No implementation of `account.need_reauth` as a separate Armada event handler in this change.
