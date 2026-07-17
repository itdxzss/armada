# Android Zhuan Lifecycle Cutover

> **已废弃（2026-07-17）**：本文只保留早期 Android lifecycle 单 topic 迁移的历史记录，步骤 4、11 中的 `protocol.android.commands.v1`、旧 consumer group 和单一 `concurrency` 已不适用于当前版本，禁止按本文执行新切换。当前 lifecycle/message/group-join 三 topic 停机切换必须使用 [dev-1 Android 命令 Topic 隔离切换手册](./android-command-topic-isolation-cutover.md)。

## Authorization boundary

This runbook is a verification checklist only. It does not authorize SSH access, deployment, service changes, account deletion, proxy release, outbox termination, or any other environment mutation. Each such action requires a separate confirmation naming the target environment, tenant scope, operator, and approved account-ID list.

## Guarded cutover gates

1. Confirm the target environment, tenant scope, operator, maintenance window, and rollback owner.
2. Stop `whatsapp-server-feature-android`; do not start Zhuan consumption yet.
3. Verify no old Android consumer process remains. Record the evidence and observation time.
4. Inspect `protocol.android.commands.v1`. Require at least four partitions for the default concurrency of four, or deliberately lower `concurrency` to the observed partition count before enabling consumption.
5. Inventory active rows where `protocol_id = 'ANDROID'`. Save the returned account IDs as an immutable deletion list; do not broaden it later with a fresh query.
6. Inventory pending/retry outbox rows for only those account IDs and the Android backend.
7. Report the account count, outbox count, target environment, tenant scope, and immutable account-ID list. Wait for explicit deletion approval.
8. Delete or soft-delete only the approved immutable account-ID list through the approved environment-specific operation. Release their proxy bindings and terminate only their matching pending outbox rows.
9. Verify the old IDs are no longer active and no pending Android commands remain for them. Stop if either verification fails.
10. Deploy `whatsapp-server-feature-android-zhuan` with Kafka disabled. Verify MySQL, Redis, HTTP health, and configuration/log redaction before proceeding.
11. Enable Zhuan Kafka with the existing consumer group only after the old consumer is confirmed stopped. Verify the configured command topic, event topic, group, worker ID, security protocol, and concurrency.
12. Re-import a small batch using the exact six-field order `phone,staticPub,staticPri,identityPub,identityPri,phoneId`. Do not reuse historical Android rows.
13. Verify batch online, `ONLINE` callbacks, batch offline, and `OFFLINE` callbacks before increasing batch size. Compare results by account ID without assuming completion order across accounts.

## Acceptance evidence

- The old Android service and all of its consumers are absent before Zhuan Kafka is enabled.
- The immutable deletion list and explicit approval are retained with the change record.
- Kafka partition count is compatible with configured concurrency.
- A small re-imported batch reaches terminal per-account states for both online and offline operations.
- Logs contain command IDs, batch IDs, masked phones, states, and error classifications, but no six-part credentials, proxy passwords, or raw Kafka payloads.
- Rollback disables Zhuan Kafka before any old consumer is considered for restart; restarting or deploying any service still requires separate target-environment approval.
