# Historical Group Creator LID Participant PN Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Android returns a LID creator together with the same participant JID and a `phone_number`, persist and display the confirmed creator country instead of `--`.

**Architecture:** Keep the change inside the existing Android protocol anti-corruption mapper. Normalize the creator first; only when it is LID, scan the same group's raw participants for the same normalized LID and promote the result to PN using that exact participant's `phone_number`. Mismatches and malformed phone values preserve the current LID/null behavior.

**Tech Stack:** Java 17, Jackson `JsonNode`, JUnit 5, AssertJ, Maven.

---

### Task 1: Lock the real Zhuan response shape with a regression test

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java`

- [x] **Step 1: Write the failing test**

Add a test containing three LID groups: an exact creator/member match with `phone_number`, a mismatched member LID, and an exact member with an invalid `phone_number`. Assert that only the first group is promoted to PN:

```java
@Test
void resolvesLidCreatorOnlyFromExactParticipantPhoneMapping() throws Exception {
    JsonNode data = objectMapper.readTree("""
            {
              "Count": 3,
              "GroupInfos": [{
                "group_id": "120363resolved@g.us",
                "creator": "193088878297313",
                "addressing_mode": "lid",
                "participants": [{
                  "jid": "193088878297313@lid",
                  "phone_number": "254713151300@s.whatsapp.net",
                  "type": "superadmin"
                }]
              }, {
                "group_id": "120363mismatch@g.us",
                "creator": "12306742263892",
                "addressing_mode": "lid",
                "participants": [{
                  "jid": "193088878297313@lid",
                  "phone_number": "51943333070@s.whatsapp.net",
                  "type": "superadmin"
                }]
              }, {
                "group_id": "120363invalid@g.us",
                "creator": "55500000000001",
                "addressing_mode": "lid",
                "participants": [{
                  "jid": "55500000000001@lid",
                  "phone_number": "not-a-phone",
                  "type": "superadmin"
                }]
              }]
            }
            """);

    List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(
            data, "254713151300");

    assertThat(groups.get(0)).satisfies(group -> {
        assertThat(group.ownerJid()).isEqualTo("254713151300@s.whatsapp.net");
        assertThat(group.ownerPhone()).isEqualTo("254713151300");
        assertThat(group.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.PN);
    });
    assertThat(groups.get(1).ownerIdentityKind()).isEqualTo(OwnerIdentityKind.LID);
    assertThat(groups.get(1).ownerPhone()).isNull();
    assertThat(groups.get(2).ownerIdentityKind()).isEqualTo(OwnerIdentityKind.LID);
    assertThat(groups.get(2).ownerPhone()).isNull();
}
```

- [x] **Step 2: Run the test to verify RED**

Run:

```bash
cd armada-api
mvn -Dtest='AndroidAccountParticipatingGroupMapperTest#resolvesLidCreatorOnlyFromExactParticipantPhoneMapping' test
```

Expected: FAIL because the resolved group still has `ownerJid=193088878297313@lid`, `ownerPhone=null`, and `ownerIdentityKind=LID`.

### Task 2: Resolve the exact LID/PN identity pair

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java`

- [x] **Step 1: Add the minimal owner resolver**

Define constants for `participants`, participant `jid`, and `phone_number`. Replace the direct `WhatsappJids.ownerIdentity(...)` call with `resolveOwner(group)`. The helper must normalize the creator, return non-LID identities unchanged, compare only the participant `jid` normalized as LID, and accept only a PN parsed from the matching row's `phone_number`:

```java
private static WhatsappJids.OwnerIdentity resolveOwner(JsonNode group) {
    WhatsappJids.OwnerIdentity creator = WhatsappJids.ownerIdentity(
            text(group.get(CREATOR_FIELD)),
            text(group.get(ADDRESSING_MODE_FIELD)));
    if (creator.kind() != OwnerIdentityKind.LID) {
        return creator;
    }
    JsonNode participants = group.get(PARTICIPANTS_FIELD);
    if (participants == null || !participants.isArray()) {
        return creator;
    }
    for (JsonNode participant : participants) {
        WhatsappJids.OwnerIdentity participantLid = WhatsappJids.ownerIdentity(
                text(participant.get(PARTICIPANT_JID_FIELD)), "lid");
        if (participantLid.kind() != OwnerIdentityKind.LID
                || !creator.ownerJid().equals(participantLid.ownerJid())) {
            continue;
        }
        WhatsappJids.OwnerIdentity participantPhone = WhatsappJids.ownerIdentity(
                text(participant.get(PARTICIPANT_PHONE_FIELD)), "pn");
        if (participantPhone.kind() == OwnerIdentityKind.PN) {
            return participantPhone;
        }
    }
    return creator;
}
```

- [x] **Step 2: Run the focused test to verify GREEN**

Run:

```bash
cd armada-api
mvn -Dtest='AndroidAccountParticipatingGroupMapperTest#resolvesLidCreatorOnlyFromExactParticipantPhoneMapping' test
```

Expected: PASS, 1 test run with no failures or errors.

### Task 3: Verify the owner-country pipeline

**Files:**
- Verify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java`
- Verify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeAccountParticipatingGroupAdapterTest.java`
- Verify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImplTest.java`
- Verify: `armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java`
- Verify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupAccountGroupQueryServiceTest.java`

- [x] **Step 1: Run the focused pipeline suite**

```bash
cd armada-api
mvn -Dtest='AndroidAccountParticipatingGroupMapperTest,AndroidNativeAccountParticipatingGroupAdapterTest,AccountGroupMembershipSnapshotServiceImplTest,CountryServiceImplTest,HistoricalGroupAccountGroupQueryServiceTest' test
```

Expected: all selected tests pass with no failures or errors.

- [x] **Step 2: Run the full safe test suite**

```bash
cd armada-api
mvn test
```

Expected: record the actual test count, failures, errors, and skips. Any pre-existing failures must be identified by exact class and must not be described as passing.

- [x] **Step 3: Verify the build and diff**

```bash
cd armada-api
mvn -DskipTests package
cd ..
git diff --check
git diff -- armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java docs/superpowers/specs/2026-07-31-historical-group-owner-country-resolution-design.md .harness/changes/2026-07-31-historical-group-owner-country-resolution.md
```

Expected: package and diff check exit 0; review confirms the change is limited to exact creator identity resolution and documentation.

No commit or deployment is included. The current worktree already contains uncommitted changes for the same owner-country task, so execution must remain inline in this worktree and preserve all existing edits.
