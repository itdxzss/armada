# Online Confirmed State Event Design

## Goal

Clear Armada `PENDING_ONLINE` when a protocol online command targets an account that is already `ONLINE`.

## Problem

Armada marks accounts as `PENDING_ONLINE` after the online command is accepted into outbox. In the incident on 2026-07-03, several accounts already had a live protocol context when another online command was sent. The protocol state machine does not emit a transition for `ONLINE -> ONLINE`, and Armada does not consume `account.online_changed`. The command attempt therefore had no reliable success event to clear `PENDING_ONLINE`.

## Design

Keep `account.state_changed` as the durable state settlement channel. When the protocol layer receives `connection.update=open`, it will first call the existing `publishStateChange(ctx, 'ONLINE', 'ws_open_confirmed')`.

If that call returns `false` because the account is already `ONLINE`, the protocol layer will publish an explicit confirmation event on the same topic:

```json
{
  "tenantId": 1,
  "accountId": 100,
  "protocolAccountId": "acc_1000",
  "onlineAttemptId": "oa_1",
  "previousOnlineAttemptId": null,
  "commandId": "cmd_1",
  "batchId": "batch_1",
  "proxyId": 4035,
  "source": "batch_online",
  "from": "ONLINE",
  "to": "ONLINE",
  "reason": "online_confirmed",
  "semantic": "ONLINE_CONFIRMED"
}
```

This confirmation is not a state-machine transition. It must not change protocol state, account-state metrics, or reconnect counters. It is only a durable command-attempt settlement signal for Armada.

Armada will continue to consume the event through the existing `account.state_changed` consumer and `AccountStateEventServiceImpl`. Since `to=ONLINE`, the existing state service updates `login_state=ONLINE`, updates `last_state_sync_time`, and marks recoverable lifecycle states as normal.

## Boundaries

This fix does not skip duplicate batch-online commands and does not solve `428 Connection Terminated`. Those are separate proxy/command admission concerns.

## Tests

- Protocol unit test: a second online command for an already-online account publishes `account.state_changed` with `from=ONLINE`, `to=ONLINE`, `semantic=ONLINE_CONFIRMED`, and the new `onlineAttemptId`.
- Armada DbTest: a `PENDING_ONLINE` account becomes `ONLINE` after an `ONLINE -> ONLINE` confirmation event.
