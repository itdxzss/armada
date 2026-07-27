# Android Historical Group JID Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent Android historical groups from being marked exited when Zhuan returns a bare group ID without `@g.us`.

**Architecture:** Normalize Zhuan `group_id` values at the Android response-mapping boundary. Keep the historical-group service and Web protocol path unchanged so the rest of Armada continues comparing canonical full group JIDs.

**Tech Stack:** Java 17, Jackson `JsonNode`, JUnit 5, AssertJ, Maven

---

### Task 1: Normalize Android current-group JIDs

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java`

- [x] **Step 1: Write the failing regression test**

Add this test to `AndroidAccountParticipatingGroupMapperTest`:

```java
@Test
void normalizesBareGroupIdBeforeMatchingHistoricalBaseline() throws Exception {
    JsonNode data = objectMapper.readTree("""
            {
              "Count": 1,
              "GroupInfos": [{
                "group_id": "120363000000000000",
                "subject": "历史群",
                "participants": [
                  {"phone_number":"919000000001","type":"participant"}
                ]
              }]
            }
            """);

    List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(data);
    List<AccountGroupMetadataSummaryResult> summaries = mapper.mapSummaries(
            data,
            List.of("120363000000000000@g.us"),
            "919000000001");

    assertThat(groups)
            .extracting(AccountParticipatingGroupResult.Group::groupJid)
            .containsExactly("120363000000000000@g.us");
    assertThat(summaries).singleElement().satisfies(summary -> {
        assertThat(summary.groupJid()).isEqualTo("120363000000000000@g.us");
        assertThat(summary.success()).isTrue();
        assertThat(summary.selfRole()).isEqualTo("MEMBER");
    });
}
```

- [x] **Step 2: Run the new test and verify RED**

Run:

```bash
cd armada-api
mvn -Dtest='AndroidAccountParticipatingGroupMapperTest#normalizesBareGroupIdBeforeMatchingHistoricalBaseline' test
```

Expected: FAIL because `mapGroups` returns the bare ID and `mapSummaries` reports the full baseline JID as missing.

- [x] **Step 3: Implement the minimal Android-boundary normalization**

Add a suffix constant and normalize only non-empty Zhuan response values:

```java
private static final String GROUP_JID_SUFFIX = "@g.us";

private static String requireGroupJid(JsonNode group) {
    String groupJid = group == null ? null : text(group.get(GROUP_JID_FIELD));
    if (groupJid == null) {
        throw unrecognized("Android 当前群响应缺少 group_id");
    }
    return groupJid.contains("@") ? groupJid : groupJid + GROUP_JID_SUFFIX;
}
```

Do not change Web adapters, historical-group service matching, database data, or Zhuan.

- [x] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
cd armada-api
mvn -Dtest='AndroidAccountParticipatingGroupMapperTest#normalizesBareGroupIdBeforeMatchingHistoricalBaseline' test
```

Expected: PASS.

- [x] **Step 5: Run mapper and historical-group regression tests**

Run:

```bash
cd armada-api
mvn -Dtest='AndroidAccountParticipatingGroupMapperTest,AndroidNativeAccountParticipatingGroupAdapterTest,HistoricalGroupServiceImplTest' test
```

Expected: all selected tests PASS, including the existing full-`@g.us` and malformed-response cases.

- [x] **Step 6: Check the scoped diff**

Run:

```bash
git diff --check -- \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java
git diff -- \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java
```

Expected: only the Android Mapper normalization and its regression test appear. Per user instruction, do not commit.
