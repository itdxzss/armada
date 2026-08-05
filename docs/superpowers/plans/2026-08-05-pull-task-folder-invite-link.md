# Pull Task Folder Invite Link Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a selected group folder provide executable WhatsApp invite links instead of internal `wa://group/{jid}` entries.

**Architecture:** Keep the group-domain Service contract and task planning flow unchanged. Correct the real Mapper SQL so internal group entries derive a normalized invite link from `group_link_preview.invite_code`, while entries without an invite code are excluded from both the usable-link result and displayed usable count.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, H2 MySQL mode, JUnit 5, Maven.

---

### Task 1: Reproduce the wrong folder link projection

**Files:**
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupFolderMapperInMemoryTest.java`

- [x] Add an H2 fixture with one healthy `wa://group/{jid}` row having a 22-character `invite_code` and one healthy internal row without a code.
- [x] Assert `selectUsableLinks` returns `chat.whatsapp.com/{inviteCode}`, excludes the missing-code row, and `selectPage` reports the same usable count.
- [x] Run focused tests and verify both failures: raw `wa://group/...` was returned, then a missing code produced `chat.whatsapp.com/`.

### Task 2: Correct the Mapper projection and eligibility rule

**Files:**
- Modify: `armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml`

- [x] Join `group_link_preview` by both tenant and group-link ID in the list and usable-link queries.
- [x] Project internal entries as `chat.whatsapp.com/{inviteCode}` and keep existing external invitation links unchanged.
- [x] Exclude internal entries with a blank invite code and apply the same predicate to `groupCount`.
- [x] Re-run `mvn -Dtest='GroupFolderMapperInMemoryTest' test` and verify all Mapper tests pass.

### Task 3: Regression and quality gates

**Files:**
- Modify: `.harness/changes/pull-task-folder-invite-link/summary.md`

- [x] Run `mvn -Dtest='GroupLinkUrlsTest,GroupFolderMapperInMemoryTest,PullTaskStandardDraftServicePlanTest' test`.
- [ ] Run `mvn test` to completion. Blocked by existing tests that attempt an unconfirmed external data source; the attempt was stopped and recorded.
- [x] Run `xmllint --noout armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml` and `git diff --check`.
- [x] Record exact test counts and remaining constraints in the change summary; do not commit, push, deploy, or access a remote database.
