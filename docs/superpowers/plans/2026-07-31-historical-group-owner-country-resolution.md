# Historical Group Owner Country Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 只在确认历史群群主/创建者是真实 WhatsApp PN 时解析并展示国家；LID、内部身份、无效号码和未知身份统一返回空，前端显示 `--`。

**Architecture:** 协议防腐层把 creator 归一成 `PN / LID / UNKNOWN`，稳定结果同时携带 `ownerJid`、`ownerPhone` 和身份类型。预览持久化用“已观察/未观察”标记表达 PN 写入、LID 清空、UNKNOWN 保留；历史群查询用 libphonenumber 严格解析 ISO2，再批量关联启用国家主数据。现有 IP 代理最长前缀逻辑保持不变。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis/MyBatis-Plus、JUnit 5、Mockito、AssertJ、H2 MySQL mode、MySQL DbTest、Google libphonenumber 9.0.32

---

## 实施约束

- 所有命令从 `/Users/daishuaishuai/IdeaProjects/armada/armada-api` 执行，除非步骤明确指定仓库根目录。
- 不修改 `wheel-saas-pure-web`、`armada-protocol` 或 Android Zhuan HTTP 接口。
- 不新增数据库列、索引或 Flyway 迁移，不批量清理任何环境的数据。
- 不改 `CountryService.resolveIpRegionByPhonePrefix` 和 `resolveIpRegionsByPhonePrefix` 的最长前缀语义。
- 不从关联账号号码回退计算群国家。
- 每个任务遵循红灯测试、最小实现、绿灯测试、聚焦提交；不要把 `.claude/worktrees/*` 的既有脏状态纳入提交。

## Task 1: 新增严格国际号码国家解析

**Files:**

- Modify: `armada-api/pom.xml`
- Modify: `armada-api/src/main/java/com/armada/platform/country/service/CountryService.java`
- Modify: `armada-api/src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java`

- [ ] **Step 1: 把现有群创建者前缀测试改成严格号码测试**

将 `resolveActiveCountriesByPhonePrefix_usesAllActiveCountriesAndLongestPrefix` 替换为覆盖以下合同的测试：

```java
@Test
void resolveActiveCountriesByPhoneNumbers_requiresValidInternationalNumbers() {
    when(mapper.selectActive()).thenReturn(List.of(
            country("CA", "加拿大", "+1", "🇨🇦"),
            country("US", "美国", "+1", "🇺🇸"),
            country("PE", "秘鲁", "+51", "🇵🇪"),
            country("KE", "肯尼亚", "+254", "🇰🇪")));

    Map<String, CountryReferenceVO> result = service.resolveActiveCountriesByPhoneNumbers(
            List.of(
                    "14165550123@s.whatsapp.net",
                    "12025550123",
                    "51943333070",
                    "254713151300",
                    "193088878297313",
                    "12306742263892",
                    "193088878297313@lid"));

    assertThat(result.get("14165550123@s.whatsapp.net").iso2()).isEqualTo("CA");
    assertThat(result.get("12025550123").iso2()).isEqualTo("US");
    assertThat(result.get("51943333070").iso2()).isEqualTo("PE");
    assertThat(result.get("254713151300").iso2()).isEqualTo("KE");
    assertThat(result).doesNotContainKeys(
            "193088878297313", "12306742263892", "193088878297313@lid");
    verify(mapper).selectActive();
}
```

另加一个测试，确认 `null`、空集合、空字符串、带字母、未知 JID 后缀和号码对应的 ISO2 未启用时均不产生映射。保留两个 IP 前缀测试原样。

- [ ] **Step 2: 运行测试，确认红灯**

Run:

```bash
mvn -q -Dtest=CountryServiceImplTest test
```

Expected: 编译失败，提示 `resolveActiveCountriesByPhoneNumbers` 尚不存在；现有 IP 测试没有被删除。

- [ ] **Step 3: 添加 libphonenumber 依赖**

在 `pom.xml` properties 中增加：

```xml
<libphonenumber.version>9.0.32</libphonenumber.version>
```

在业务依赖区增加：

```xml
<dependency>
    <groupId>com.googlecode.libphonenumber</groupId>
    <artifactId>libphonenumber</artifactId>
    <version>${libphonenumber.version}</version>
</dependency>
```

- [ ] **Step 4: 用严格方法替换群展示专用的前缀方法**

在 `CountryService` 中删除 `resolveActiveCountriesByPhonePrefix`，新增：

```java
/**
 * 批量按已确认的 WhatsApp 国际手机号解析启用国家。
 * 无效号码、LID、未知 JID 或未启用国家不会出现在返回 Map 中。
 */
Map<String, CountryReferenceVO> resolveActiveCountriesByPhoneNumbers(
        Collection<String> wsPhones);
```

在 `CountryServiceImpl` 中只为这个新方法使用 libphonenumber：

```java
private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

@Override
public Map<String, CountryReferenceVO> resolveActiveCountriesByPhoneNumbers(
        Collection<String> wsPhones) {
    if (wsPhones == null || wsPhones.isEmpty()) {
        return Map.of();
    }
    Map<String, Country> countriesByIso2 = mapper.selectActive().stream()
            .filter(country -> StringUtils.hasText(country.getIso2()))
            .collect(Collectors.toMap(
                    country -> country.getIso2().trim().toUpperCase(Locale.ROOT),
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    Map<String, CountryReferenceVO> result = new LinkedHashMap<>();
    for (String wsPhone : new LinkedHashSet<>(wsPhones)) {
        String iso2 = validRegionIso2(wsPhone);
        Country country = iso2 == null ? null : countriesByIso2.get(iso2);
        if (country != null) {
            result.put(wsPhone, toReference(country));
        }
    }
    return Collections.unmodifiableMap(result);
}

private static String validRegionIso2(String raw) {
    String international = internationalPhone(raw);
    if (international == null) {
        return null;
    }
    try {
        Phonenumber.PhoneNumber parsed = PHONE_NUMBER_UTIL.parse(international, "ZZ");
        if (!PHONE_NUMBER_UTIL.isValidNumber(parsed)) {
            return null;
        }
        String region = PHONE_NUMBER_UTIL.getRegionCodeForNumber(parsed);
        return StringUtils.hasText(region)
                ? region.toUpperCase(Locale.ROOT)
                : null;
    } catch (NumberParseException ignored) {
        return null;
    }
}
```

`internationalPhone` 只接受纯数字、可选的单个前导 `+`，或数字 user 加 `@s.whatsapp.net`；遇到 `@lid`、其他 `@` 后缀、空白夹杂、字母、设备后缀或第二个 `+` 一律返回 `null`。不要调用 `resolveCountryByPhonePrefix` 兜底。

- [ ] **Step 5: 运行国家服务测试，确认绿灯**

Run:

```bash
mvn -q -Dtest=CountryServiceImplTest test
```

Expected: PASS；CA、US、PE、KE 严格解析通过，两个内部身份和 LID 不返回映射，IP 最长前缀用例仍通过。

- [ ] **Step 6: 提交严格解析**

```bash
git add pom.xml src/main/java/com/armada/platform/country/service/CountryService.java src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java
git commit -m "fix: strictly resolve historical group owner countries"
```

## Task 2: 建立协议 creator 身份归一化器

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/enums/OwnerIdentityKind.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/util/OwnerIdentityNormalizer.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/util/OwnerIdentityNormalizerTest.java`

- [ ] **Step 1: 先写身份矩阵测试**

测试至少覆盖：

```java
@ParameterizedTest
@MethodSource("owners")
void normalizesOwnerIdentity(
        String owner,
        String addressingMode,
        String expectedJid,
        String expectedPhone,
        OwnerIdentityKind expectedKind) {
    assertThat(OwnerIdentityNormalizer.normalize(owner, addressingMode))
            .extracting(
                    OwnerIdentityNormalizer.OwnerIdentity::ownerJid,
                    OwnerIdentityNormalizer.OwnerIdentity::ownerPhone,
                    OwnerIdentityNormalizer.OwnerIdentity::kind)
            .containsExactly(expectedJid, expectedPhone, expectedKind);
}

static Stream<Arguments> owners() {
    return Stream.of(
            arguments("51943333070", "pn", "51943333070@s.whatsapp.net", "51943333070", PN),
            arguments("193088878297313", "lid", "193088878297313@lid", null, LID),
            arguments("254713151300@s.whatsapp.net", null,
                    "254713151300@s.whatsapp.net", "254713151300", PN),
            arguments("12306742263892@lid", null, "12306742263892@lid", null, LID),
            arguments("12306742263892@lid", "pn", "12306742263892@lid", null, UNKNOWN),
            arguments("51943333070", null, "51943333070", null, UNKNOWN),
            arguments(null, null, null, null, UNKNOWN));
}
```

再覆盖 `normalize(rawOwner, "PN")` 大小写、PN user 的 `:device` 去除、非法 PN user 返回 UNKNOWN，以及显式 LID 永不输出 `ownerPhone`。

- [ ] **Step 2: 运行测试，确认红灯**

Run:

```bash
mvn -q -Dtest=OwnerIdentityNormalizerTest test
```

Expected: 编译失败，因为 enum 和 normalizer 尚不存在。

- [ ] **Step 3: 实现三态类型和归一化器**

Enum：

```java
public enum OwnerIdentityKind {
    PN,
    LID,
    UNKNOWN
}
```

Normalizer 公共合同：

```java
public final class OwnerIdentityNormalizer {

    public static OwnerIdentity normalize(String rawOwner, String rawAddressingMode) {
        String owner = text(rawOwner);
        if (owner == null) {
            return OwnerIdentity.unknown(null);
        }
        OwnerIdentityKind fromMode = kindFromMode(rawAddressingMode);
        OwnerIdentityKind fromSuffix = kindFromSuffix(owner);
        if (fromMode != UNKNOWN && fromSuffix != UNKNOWN && fromMode != fromSuffix) {
            return OwnerIdentity.unknown(owner);
        }
        OwnerIdentityKind kind = fromMode != UNKNOWN ? fromMode : fromSuffix;
        return switch (kind) {
            case PN -> pn(owner);
            case LID -> lid(owner);
            case UNKNOWN -> OwnerIdentity.unknown(owner);
        };
    }

    public record OwnerIdentity(
            String ownerJid,
            String ownerPhone,
            OwnerIdentityKind kind) {
        private static OwnerIdentity unknown(String ownerJid) {
            return new OwnerIdentity(ownerJid, null, OwnerIdentityKind.UNKNOWN);
        }
    }
}
```

实现细节：mode trim 后只认 `pn/lid`；suffix 只认 `@s.whatsapp.net/@lid`；PN 的 user 去除 `:device` 和单个前导 `+` 后必须全为数字，否则 UNKNOWN；裸 PN/LID 根据 mode 补完整后缀；UNKNOWN 保留 trim 后原值供诊断，但不产出 phone。

- [ ] **Step 4: 运行测试，确认绿灯**

Run:

```bash
mvn -q -Dtest=OwnerIdentityNormalizerTest test
```

Expected: PASS，冲突、LID 和 UNKNOWN 均没有 `ownerPhone`。

- [ ] **Step 5: 提交归一化器**

```bash
git add src/main/java/com/armada/platform/protocol/model/enums/OwnerIdentityKind.java src/main/java/com/armada/platform/protocol/util/OwnerIdentityNormalizer.java src/test/java/com/armada/platform/protocol/util/OwnerIdentityNormalizerTest.java
git commit -m "feat: normalize WhatsApp group owner identities"
```

## Task 3: 在 Android/Web 适配器和历史群刷新链传播身份

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/result/AccountParticipatingGroupResult.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupAccountGroupRefreshService.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingAccountParticipatingGroupPortTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupAccountGroupRefreshServiceTest.java`

- [ ] **Step 1: 扩充适配器合同测试**

Android 测试数据增加 `addressing_mode`，断言：

```java
assertThat(pnGroup.ownerJid()).isEqualTo("51943333070@s.whatsapp.net");
assertThat(pnGroup.ownerPhone()).isEqualTo("51943333070");
assertThat(pnGroup.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.PN);

assertThat(lidGroup.ownerJid()).isEqualTo("193088878297313@lid");
assertThat(lidGroup.ownerPhone()).isNull();
assertThat(lidGroup.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.LID);
```

分别增加缺失 mode 的裸 creator、mode/suffix 冲突用例，均断言 UNKNOWN。Web 测试把原来的裸 owner 改为带 `@s.whatsapp.net`，并新增 `@lid` 与裸数字，分别断言 PN、LID、UNKNOWN。

历史刷新测试捕获传给 `snapshotService.replaceVisibleGroups` 的 `AccountGroupsReportedEvent.Group`，确认 PN 的 `ownerPhone` 被传递，LID 的 `ownerPhone` 保持 null；metadata summary 补齐角色时身份字段不丢失。

- [ ] **Step 2: 运行聚焦测试，确认红灯**

Run:

```bash
mvn -q -Dtest='AndroidAccountParticipatingGroupMapperTest,HttpAccountParticipatingGroupAdapterTest,HistoricalGroupAccountGroupRefreshServiceTest,RoutingAccountParticipatingGroupPortTest' test
```

Expected: 编译失败，提示 `Group` 缺少 `ownerPhone` / `ownerIdentityKind`。

- [ ] **Step 3: 扩展稳定结果模型并更新全部构造点**

将内部 Group 改为：

```java
public record Group(
        String groupJid,
        String subject,
        Integer memberCount,
        String ownerJid,
        String ownerPhone,
        OwnerIdentityKind ownerIdentityKind,
        Boolean admin,
        Boolean announceOnly,
        Long createdAt) {
}
```

不增加兼容构造器。一次性修正 `rg -n "new AccountParticipatingGroupResult.Group" src/main/java src/test/java` 找到的所有构造点，使编译器强制每个调用方明确身份。

- [ ] **Step 4: 接入 Android/Web mapper**

Android 增加字段常量：

```java
private static final String ADDRESSING_MODE_FIELD = "addressing_mode";
```

构造 Group 前调用：

```java
OwnerIdentity owner = OwnerIdentityNormalizer.normalize(
        text(group.get(CREATOR_FIELD)),
        text(group.get(ADDRESSING_MODE_FIELD)));
```

Web 的 `toGroup` 与 `toLightGroup` 都用 `normalize(response.owner(), null)`；明确后缀可识别，裸 owner 不猜测。

- [ ] **Step 5: 历史刷新保持身份字段**

`completeGroups` 重建记录时原样复制：

```java
group.ownerJid(),
group.ownerPhone(),
group.ownerIdentityKind(),
```

`toReportedGroups` 把 `group.ownerPhone()` 写入现有 `AccountGroupsReportedEvent.Group.ownerPhone`，删除当前硬编码 `null`。不扩展 Kafka 事件合同。

- [ ] **Step 6: 运行聚焦测试，确认绿灯**

Run:

```bash
mvn -q -Dtest='OwnerIdentityNormalizerTest,AndroidAccountParticipatingGroupMapperTest,HttpAccountParticipatingGroupAdapterTest,HistoricalGroupAccountGroupRefreshServiceTest,RoutingAccountParticipatingGroupPortTest' test
```

Expected: PASS；Android/Web 的 PN/LID/UNKNOWN 矩阵通过，角色补齐不丢身份。

- [ ] **Step 7: 提交协议传播链**

```bash
git add src/main/java/com/armada/platform/protocol/model/result/AccountParticipatingGroupResult.java src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java src/main/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapter.java src/main/java/com/armada/group/service/impl/HistoricalGroupAccountGroupRefreshService.java src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java src/test/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapterTest.java src/test/java/com/armada/platform/protocol/routing/RoutingAccountParticipatingGroupPortTest.java src/test/java/com/armada/group/service/impl/HistoricalGroupAccountGroupRefreshServiceTest.java
git commit -m "fix: preserve historical group owner identity"
```

## Task 4: 实现账号群快照 owner_phone 三态持久化

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkPreview.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [ ] **Step 1: 先写 service 三态测试**

新增三组 `AccountGroupsReportedEvent.Group`：

- 显式 `ownerPhone="51943333070"`：捕获预览行，断言 phone 为该值、observed 为 true。
- `ownerJid="193088878297313@lid"` 且 phone 为空：断言 phone 为 null、observed 为 true。
- 裸 `ownerJid="193088878297313"` 且 phone 为空：断言 phone 为 null、observed 为 false。

同时删除对 `ownerJid` 任意截取 `@` 前数字的旧预期。

- [ ] **Step 2: 先写真实 XML 三态测试**

在 `MysqlModeMapperInMemoryTest` 的 preview fixture 为 tenant 7 行加入旧值 `owner_phone='8613800000000'`，依次执行：

```java
GroupLinkPreview unknown = previewUpdate();
unknown.setOwnerPhone(null);
unknown.setOwnerPhoneObserved(false);
membershipMapper.updatePreviewFromAccountSync(unknown);
assertThat(ownerPhone(21L)).isEqualTo("8613800000000");

GroupLinkPreview lid = previewUpdate();
lid.setOwnerPhone(null);
lid.setOwnerPhoneObserved(true);
membershipMapper.updatePreviewFromAccountSync(lid);
assertThat(ownerPhone(21L)).isNull();

GroupLinkPreview pn = previewUpdate();
pn.setOwnerPhone("51943333070");
pn.setOwnerPhoneObserved(true);
membershipMapper.updatePreviewFromAccountSync(pn);
assertThat(ownerPhone(21L)).isEqualTo("51943333070");
```

再调用 `upsertPreviewFromAccountSync` 覆盖同样三态，确保 INSERT ... ON DUPLICATE KEY UPDATE 分支也执行真实 XML。为 H2 表补 `(tenant_id, group_link_id)` 唯一约束，使测试确实进入 duplicate-key 分支。

- [ ] **Step 3: 运行测试，确认红灯**

Run:

```bash
mvn -q -Dtest='AccountGroupMembershipSnapshotServiceImplTest,MysqlModeMapperInMemoryTest' test
```

Expected: 编译或断言失败，因为 observation 字段和 CASE SQL 尚未实现，LID 仍被截成数字或 UNKNOWN 覆盖语义不正确。

- [ ] **Step 4: 增加非持久化观察属性**

在 `GroupLinkPreview` 加 POJO 属性及 getter/setter：

```java
/** 本次响应是否明确观察到群主身份；仅供 Mapper 三态更新，不对应数据库列。 */
private Boolean ownerPhoneObserved;
```

该实体当前只由显式 XML 映射，不新增数据库列。所有新增 SQL 只能读取此参数，不能把它加入 INSERT 列表。

- [ ] **Step 5: 删除任意 JID 截取并生成 observation**

在 `AccountGroupMembershipSnapshotServiceImpl` 用一个私有 record 表达写入意图：

```java
private record OwnerPhoneObservation(String phone, boolean observed) {
}
```

规则：

```java
private static OwnerPhoneObservation ownerPhoneObservation(
        AccountGroupsReportedEvent.Group group) {
    OwnerIdentity explicitPhone = OwnerIdentityNormalizer.normalize(group.ownerPhone(), "pn");
    if (explicitPhone.kind() == OwnerIdentityKind.PN) {
        return new OwnerPhoneObservation(explicitPhone.ownerPhone(), true);
    }
    OwnerIdentity owner = OwnerIdentityNormalizer.normalize(group.ownerJid(), null);
    if (owner.kind() == OwnerIdentityKind.LID) {
        return new OwnerPhoneObservation(null, true);
    }
    return new OwnerPhoneObservation(null, false);
}
```

`previewRow` 同时设置 phone 和 observed。完全删除旧 `ownerPhone(group)` 的 ownerJid substring fallback。

- [ ] **Step 6: 在 Mapper 参数和 SQL 中实现三态**

`upsertPreviewFromAccountSync` 在 `ownerPhone` 后增加：

```java
@Param("ownerPhoneObserved") Boolean ownerPhoneObserved
```

duplicate-key SQL：

```xml
owner_phone = CASE
  WHEN #{ownerPhoneObserved} = TRUE
    THEN NULLIF(TRIM(VALUES(owner_phone)), '')
  ELSE owner_phone
END,
```

update SQL：

```xml
owner_phone = CASE
  WHEN #{ownerPhoneObserved} = TRUE
    THEN NULLIF(TRIM(#{ownerPhone}), '')
  ELSE owner_phone
END,
```

快照服务传 `preview.getOwnerPhoneObserved()`；`GroupLinkRegistryServiceImpl.registerSelfBuiltGroup` 已拿到明确 owner phone，传 `true`。同步更新 Mockito verify 的参数数量和位置。

- [ ] **Step 7: 运行测试，确认绿灯**

Run:

```bash
mvn -q -Dtest='AccountGroupMembershipSnapshotServiceImplTest,MysqlModeMapperInMemoryTest' test
```

Expected: PASS；PN 写入、LID 清空、UNKNOWN 保留，tenant 8 的对照行不变。

- [ ] **Step 8: 校验 XML 并提交**

```bash
xmllint --noout src/main/resources/mapper/group/AccountGroupMembershipMapper.xml
git add src/main/java/com/armada/group/model/entity/GroupLinkPreview.java src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java src/main/resources/mapper/group/AccountGroupMembershipMapper.xml src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java src/test/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImplTest.java src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java
git commit -m "fix: persist group owner phone observations safely"
```

## Task 5: 统一实时群预览的 owner 身份与持久化语义

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkPreviewMapperDbTest.java`

- [ ] **Step 1: 先写实时预览 PN/LID/UNKNOWN 测试**

在 `GroupLinkServiceImplTest` 保留 PN 用例并增加对 `ownerPhoneObserved=true` 的断言；再加：

- owner 为 `193088878297313@lid`：返回 item ownerPhone 为 null，持久化行 ownerPhone null、observed true。
- owner 为裸 `193088878297313`：返回 item ownerPhone 为 null，持久化行 ownerPhone null、observed false。

在 `GroupLinkPreviewMapperDbTest` 先写入 PN，再分别用 observed=false/null 验证保留、observed=true/null 验证清空、observed=true/PE phone 验证替换。

- [ ] **Step 2: 运行测试，确认红灯**

Run:

```bash
mvn -q -Dtest='GroupLinkServiceImplTest,GroupLinkPreviewMapperDbTest' test
```

Expected: service 测试失败，因为旧 helper 会截取 LID/裸数字；Mapper DbTest 的 UNKNOWN 保留断言失败。若本机 MySQL DbTest 无法启动，记录基础设施原因，但必须先让 `GroupLinkServiceImplTest` 出现预期红灯，并继续实现。

- [ ] **Step 3: GroupLinkService 复用统一归一化器**

删除私有 `ownerPhone(String ownerJid)`。在 preview loop 中：

```java
OwnerIdentity owner = OwnerIdentityNormalizer.normalize(preview.ownerJid(), null);
persistSuccessfulPreview(link, preview, owner, previewAt);
items.add(successItem(link, preview, owner.ownerPhone(), previewAt));
```

持久化时：

```java
row.setOwnerPhone(owner.ownerPhone());
row.setOwnerPhoneObserved(owner.kind() != OwnerIdentityKind.UNKNOWN);
```

- [ ] **Step 4: GroupLinkPreviewMapper 使用同一三态标记**

保持 INSERT 时 `owner_phone=#{ownerPhone}`；duplicate-key 更新改为：

```xml
owner_phone = CASE
  WHEN #{ownerPhoneObserved} = TRUE
    THEN NULLIF(TRIM(VALUES(owner_phone)), '')
  ELSE owner_phone
END,
```

所有直接构造带 ownerPhone 的 Mapper 测试都显式 `setOwnerPhoneObserved(true)`，不能依赖 null 的隐式含义。

- [ ] **Step 5: 运行单测与真库测试，确认绿灯**

Run:

```bash
mvn -q -Dtest=GroupLinkServiceImplTest test
./dbtest.sh GroupLinkPreviewMapperDbTest
```

Expected: 两条命令 PASS；PN、LID、UNKNOWN 行为一致。如果 DbTest 因本机 MySQL 缺失仍无法启动，保留失败输出作为交付说明，不得声称真库测试通过。

- [ ] **Step 6: 校验 XML 并提交**

```bash
xmllint --noout src/main/resources/mapper/group/GroupLinkPreviewMapper.xml
git add src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java src/main/resources/mapper/group/GroupLinkPreviewMapper.xml src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java src/test/java/com/armada/group/mapper/GroupLinkPreviewMapperDbTest.java
git commit -m "fix: normalize real-time group preview owners"
```

## Task 6: 历史群列表切换到严格国家解析

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupAccountGroupQueryService.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupAccountGroupQueryServiceTest.java`

- [ ] **Step 1: 更新有效 PN 用例并新增无效 owner 用例**

把现有 mock 改为：

```java
when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("51943333070")))
        .thenReturn(Map.of(
                "51943333070",
                new CountryReferenceVO(51L, "PE", "秘鲁", "+51", "🇵🇪")));
```

新增第二个列表测试，row.ownerPhone 为 `193088878297313`，country service 返回空 Map，断言：

```java
assertThat(item.countryIso2()).isNull();
assertThat(item.countryName()).isNull();
assertThat(item.countryFlag()).isNull();
assertThat(item.subject()).isEqualTo("内部身份群");
assertThat(item.accountPhones()).containsExactly("51943333070");
```

这同时证明国家解析失败不会影响其它展示字段，也没有回退关联账号国家。

- [ ] **Step 2: 运行测试，确认红灯**

Run:

```bash
mvn -q -Dtest=HistoricalGroupAccountGroupQueryServiceTest test
```

Expected: Mockito 严格校验失败或旧方法调用断言失败，因为 service 仍调用前缀方法。

- [ ] **Step 3: 切换调用并保持 API 空值合同**

唯一业务改动：

```java
Map<String, CountryReferenceVO> countries = creators.isEmpty()
        ? Map.of()
        : countryService.resolveActiveCountriesByPhoneNumbers(creators);
```

`toItem` 继续在 country 缺失时输出三个 null；不修改 VO、Controller 或前端。

- [ ] **Step 4: 运行国家与历史群聚焦回归**

Run:

```bash
mvn -q -Dtest='CountryServiceImplTest,HistoricalGroupAccountGroupQueryServiceTest,HistoricalGroupAccountGroupRefreshServiceTest' test
```

Expected: PASS；PE 有展示，无效 owner 三个国家字段为空。

- [ ] **Step 5: 确认旧群展示前缀方法已完全移除并提交**

```bash
rg -n "resolveActiveCountriesByPhonePrefix" src/main/java src/test/java
git add src/main/java/com/armada/group/service/impl/HistoricalGroupAccountGroupQueryService.java src/test/java/com/armada/group/service/impl/HistoricalGroupAccountGroupQueryServiceTest.java
git commit -m "fix: hide countries for invalid group owners"
```

Expected: `rg` 无输出并返回 1；提交只包含两个历史群查询文件。

## Task 7: 记录变更并执行完整验证

**Files:**

- Create: `.harness/changes/historical-group-owner-country-resolution/summary.md`
- Verify only: `armada-api/src/main/java/**`
- Verify only: `armada-api/src/test/java/**`

- [ ] **Step 1: 创建 Harness 变更记录**

记录以下已实现事实：

- 历史群国家语义仍是群主/创建者国家。
- PN 写入、LID 清空、UNKNOWN 保留；无数据库迁移。
- libphonenumber 严格解析，不改 IP 前缀算法。
- 无效 owner 返回 null，前端现有逻辑展示 `--`。
- 未连接或修改远程数据库；第一套环境核验留到部署后执行。

- [ ] **Step 2: 跑全部聚焦测试**

```bash
mvn -q -Dtest='OwnerIdentityNormalizerTest,AndroidAccountParticipatingGroupMapperTest,HttpAccountParticipatingGroupAdapterTest,RoutingAccountParticipatingGroupPortTest,HistoricalGroupAccountGroupRefreshServiceTest,AccountGroupMembershipSnapshotServiceImplTest,CountryServiceImplTest,HistoricalGroupAccountGroupQueryServiceTest,GroupLinkServiceImplTest,MysqlModeMapperInMemoryTest' test
```

Expected: PASS，0 failures / 0 errors。

- [ ] **Step 3: 校验两份 Mapper XML**

```bash
xmllint --noout src/main/resources/mapper/group/AccountGroupMembershipMapper.xml src/main/resources/mapper/group/GroupLinkPreviewMapper.xml
```

Expected: exit 0，无输出。

- [ ] **Step 4: 跑真库 Mapper 回归**

```bash
./dbtest.sh GroupLinkPreviewMapperDbTest
```

Expected: PASS。若本机 MySQL 基础设施不可用，保留完整错误证据并在变更记录中标注，不使用远程数据库替代。

- [ ] **Step 5: 跑后端全量测试和构建**

```bash
mvn test
mvn -q -DskipTests package
```

Expected: 全量测试 PASS，随后 package exit 0。若存在与本次无关的既有失败，逐项记录失败类、失败原因，并再次确认聚焦测试仍全绿。

- [ ] **Step 6: 做静态边界检查**

```bash
rg -n "resolveActiveCountriesByPhonePrefix|ownerPhone\(AccountGroupsReportedEvent.Group|ownerPhone\(String ownerJid\)" src/main/java src/test/java
git diff --check
git status --short
```

Expected:

- 不再存在群展示专用的旧前缀方法和两个 owner JID 任意截取实现。
- `git diff --check` exit 0。
- 状态只包含本任务预期的 Harness 记录；`.claude/worktrees/*` 既有脏项未被改动或暂存。

- [ ] **Step 7: 提交变更记录**

从仓库根目录执行：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add .harness/changes/historical-group-owner-country-resolution/summary.md
git commit -m "docs: record historical group owner country fix"
```

- [ ] **Step 8: 部署后第一套环境只读验收（不在本地实现提交中执行）**

在用户另行确认部署目标和窗口后：

1. 部署 Armada 后端，不部署前端、不执行数据库脚本。
2. 打开账号组 `混合劫持` 历史群列表，确认原四行国家显示 `--`。
3. 点击“加载群列表”，只读核对对应 preview：明确 LID 的 `owner_phone` 已清空，UNKNOWN 不覆盖已有合法 PN。
4. 抽查一个明确 PN 的群，确认 ISO2、中文名和国旗正确。
5. 抽查账号上线/IP 分配，确认原最长前缀行为未变化。

## 完成定义

- Android/Web 映射能区分 PN、LID、UNKNOWN，冲突不猜测。
- 只有 PN 能产生 `ownerPhone`；所有旧的 owner JID 截取数字逻辑已删除。
- 两套预览写入都实现 PN 写、LID 清、UNKNOWN 留的三态。
- libphonenumber 可以区分 CA/US，并正确解析 PE/KE；两个第一套环境异常内部身份返回空。
- 历史群接口无法确认国家时返回三个 null，前端无需改动并显示 `--`。
- 无 Flyway、无远程数据修改、无关联账号国家回退、无 IP 国家算法变化。
- 聚焦测试、XML 校验、构建通过；真库/全量测试若受既有环境影响，交付中提供可复现证据。
