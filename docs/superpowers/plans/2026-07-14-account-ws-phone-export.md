# Account WS Phone Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tenant-safe endpoint that exports the selected normal accounts' cleaned, unique WS phone numbers as a TXT download and reports the actual line count in a response header.

**Architecture:** A dedicated export service validates and chunks selected IDs, asks `AccountMapper` for only normal active accounts, cleans and deduplicates phone numbers, and returns an in-memory file result. `AccountController` only converts that result into a UTF-8 attachment response; business and unexpected failures continue through the project's `BusinessException` JSON envelope.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis-Plus/MySQL, JUnit 5, Mockito, AssertJ, MockMvc.

## Global Constraints

- Only explicitly selected account IDs may be exported.
- The account must belong to the current tenant, be non-deleted, and have `account_state.account_state = AccountStateCode.NORMAL` (`2`).
- Accept 1–2000 unique non-null IDs and query in chunks of 500.
- Keep only ASCII digits `0`–`9`, preserve digit order/country code, skip empty results, and deduplicate after cleaning.
- TXT contains one number per `\n`-separated line and no trailing blank line.
- Filename is `<safe groupName>_YYYY-MM-DD.txt`, or `全部WS号_YYYY-MM-DD.txt` when no usable group name is supplied; date uses `Asia/Shanghai`.
- Successful response is a real `text/plain;charset=UTF-8` attachment with `X-Export-Count`; failures are the existing JSON response envelope.
- Do not add a filename sequence table or `_2`/`_3` suffix in this iteration.
- Do not add a Flyway migration, temporary files, async jobs, or frontend changes.

---

## File Map

- Create `armada-api/src/main/java/com/armada/account/model/dto/AccountWsPhoneExportDTO.java`: selected IDs and optional group name.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountWsPhoneExportRow.java`: lightweight Mapper projection.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountWsPhoneExportFile.java`: filename, bytes, and actual count.
- Create `armada-api/src/main/java/com/armada/account/service/AccountWsPhoneExportService.java`: export use-case interface.
- Create `armada-api/src/main/java/com/armada/account/service/impl/AccountWsPhoneExportServiceImpl.java`: validation, chunking, cleaning, deduplication, filename, and exception conversion.
- Modify `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`: declare the lightweight normal-account query.
- Modify `armada-api/src/main/resources/mapper/account/AccountMapper.xml`: implement tenant-intercepted active/normal query.
- Modify `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`: add the export failure code.
- Modify `armada-api/src/main/java/com/armada/account/controller/AccountController.java`: add the download endpoint.
- Create `armada-api/src/test/java/com/armada/account/service/impl/AccountWsPhoneExportServiceImplTest.java`: service behavior tests.
- Create `armada-api/src/test/java/com/armada/account/mapper/AccountWsPhoneExportMapperDbTest.java`: real database filtering test.
- Modify `armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java`: download contract and JSON error tests.

### Task 1: Build the export service with a unit-level red/green cycle

**Files:**
- Create: `armada-api/src/test/java/com/armada/account/service/impl/AccountWsPhoneExportServiceImplTest.java`
- Create: `armada-api/src/main/java/com/armada/account/model/dto/AccountWsPhoneExportDTO.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountWsPhoneExportRow.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountWsPhoneExportFile.java`
- Create: `armada-api/src/main/java/com/armada/account/service/AccountWsPhoneExportService.java`
- Create: `armada-api/src/main/java/com/armada/account/service/impl/AccountWsPhoneExportServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`

**Interfaces:**
- Consumes: `AccountMapper.selectNormalWsPhonesByIds(List<Long>, int)` and `AccountStateCode.NORMAL`.
- Produces: `AccountWsPhoneExportService.export(AccountWsPhoneExportDTO)` returning `AccountWsPhoneExportFile`.

- [ ] **Step 1: Write the failing service tests**

Create `AccountWsPhoneExportServiceImplTest` with a fixed Shanghai clock and focused tests:

```java
package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountWsPhoneExportFile;
import com.armada.account.model.vo.AccountWsPhoneExportRow;
import com.armada.shared.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountWsPhoneExportServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private AccountMapper accountMapper;

    private AccountWsPhoneExportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountWsPhoneExportServiceImpl(accountMapper, FIXED_CLOCK);
    }

    @Test
    void exportCleansDeduplicatesAndCountsActualLines() {
        when(accountMapper.selectNormalWsPhonesByIds(
                List.of(3L, 1L, 2L), AccountStateCode.NORMAL))
                .thenReturn(List.of(
                        row(1L, "+60 (12) 345-6789"),
                        row(2L, "60-12-345-6789"),
                        row(3L, "001 234 ABC")));

        AccountWsPhoneExportFile file = service.export(
                new AccountWsPhoneExportDTO(Arrays.asList(3L, null, 1L, 3L, 2L), "马来西亚客户组"));

        assertThat(file.filename()).isEqualTo("马来西亚客户组_2026-07-14.txt");
        assertThat(new String(file.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("60123456789\n001234");
        assertThat(file.exportedCount()).isEqualTo(2);
        verify(accountMapper).selectNormalWsPhonesByIds(
                List.of(3L, 1L, 2L), AccountStateCode.NORMAL);
    }

    @Test
    void exportSkipsNullEmptyAndNonDigitPhones() {
        when(accountMapper.selectNormalWsPhonesByIds(List.of(1L, 2L, 3L), AccountStateCode.NORMAL))
                .thenReturn(Arrays.asList(row(1L, null), row(2L, "  +()-  "), row(3L, "")));

        assertThatThrownBy(() -> service.export(
                new AccountWsPhoneExportDTO(List.of(1L, 2L, 3L), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前所选账号中没有可导出的有效WS号码。");
    }

    @Test
    void exportUsesFallbackAndSanitizesUnsafeFilenameCharacters() {
        when(accountMapper.selectNormalWsPhonesByIds(List.of(1L), AccountStateCode.NORMAL))
                .thenReturn(List.of(row(1L, "8613800138000")));

        AccountWsPhoneExportFile fallback = service.export(
                new AccountWsPhoneExportDTO(List.of(1L), "   "));
        AccountWsPhoneExportFile safe = service.export(
                new AccountWsPhoneExportDTO(List.of(1L), " 马来/西亚:*?组. "));

        assertThat(fallback.filename()).isEqualTo("全部WS号_2026-07-14.txt");
        assertThat(safe.filename()).isEqualTo("马来_西亚___组_2026-07-14.txt");
    }

    @Test
    void exportQueriesAtMostFiveHundredIdsPerChunk() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 501; id++) {
            ids.add(id);
        }
        when(accountMapper.selectNormalWsPhonesByIds(anyList(), eq(AccountStateCode.NORMAL)))
                .thenAnswer(invocation -> {
                    List<Long> chunk = invocation.getArgument(0);
                    return chunk.contains(501L) ? List.of(row(501L, "8613800138000")) : List.of();
                });

        AccountWsPhoneExportFile file = service.export(new AccountWsPhoneExportDTO(ids, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
        verify(accountMapper, org.mockito.Mockito.times(2))
                .selectNormalWsPhonesByIds(chunks.capture(), eq(AccountStateCode.NORMAL));
        assertThat(chunks.getAllValues()).extracting(List::size).containsExactly(500, 1);
        assertThat(file.exportedCount()).isEqualTo(1);
    }

    @Test
    void exportRejectsEmptyAndMoreThanTwoThousandUniqueIds() {
        assertThatThrownBy(() -> service.export(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号 ID 列表不能为空");
        assertThatThrownBy(() -> service.export(new AccountWsPhoneExportDTO(Arrays.asList(null, null), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号 ID 列表不能为空");

        List<Long> tooMany = new ArrayList<>();
        for (long id = 1; id <= 2001; id++) {
            tooMany.add(id);
        }
        assertThatThrownBy(() -> service.export(new AccountWsPhoneExportDTO(tooMany, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("单次最多导出 2000 个账号");
        verify(accountMapper, never()).selectNormalWsPhonesByIds(anyList(), eq(AccountStateCode.NORMAL));
    }

    @Test
    void exportAllowsExactlyTwoThousandUniqueIds() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 2000; id++) {
            ids.add(id);
        }
        when(accountMapper.selectNormalWsPhonesByIds(anyList(), eq(AccountStateCode.NORMAL)))
                .thenAnswer(invocation -> {
                    List<Long> chunk = invocation.getArgument(0);
                    return chunk.contains(2000L) ? List.of(row(2000L, "8613800138000")) : List.of();
                });

        AccountWsPhoneExportFile file = service.export(new AccountWsPhoneExportDTO(ids, null));

        assertThat(file.exportedCount()).isEqualTo(1);
        verify(accountMapper, org.mockito.Mockito.times(4))
                .selectNormalWsPhonesByIds(anyList(), eq(AccountStateCode.NORMAL));
    }

    @Test
    void exportConvertsDataAccessFailureToStableBusinessError() {
        when(accountMapper.selectNormalWsPhonesByIds(List.of(1L), AccountStateCode.NORMAL))
                .thenThrow(new IllegalStateException("database details"));

        assertThatThrownBy(() -> service.export(new AccountWsPhoneExportDTO(List.of(1L), null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(50001);
                    assertThat(ex.getMessage()).isEqualTo("导出失败，请重新操作。");
                });
    }

    private static AccountWsPhoneExportRow row(Long id, String phone) {
        AccountWsPhoneExportRow row = new AccountWsPhoneExportRow();
        row.setId(id);
        row.setWsPhone(phone);
        return row;
    }
}
```

- [ ] **Step 2: Run the service test and verify RED**

Run from `armada-api/`:

```bash
mvn -q -Dtest=AccountWsPhoneExportServiceImplTest test
```

Expected: compilation FAIL because the export DTO/result/service classes and Mapper method do not exist.

- [ ] **Step 3: Add the request, projection, result, and service contract**

Create the DTO:

```java
package com.armada.account.model.dto;

import java.util.List;

/** WS 号码批量导出请求。 */
public record AccountWsPhoneExportDTO(List<Long> ids, String groupName) {
}
```

Create the Mapper projection as a MyBatis-friendly bean:

```java
package com.armada.account.model.vo;

/** WS 号码导出查询的最小投影。 */
public class AccountWsPhoneExportRow {
    private Long id;
    private String wsPhone;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWsPhone() { return wsPhone; }
    public void setWsPhone(String wsPhone) { this.wsPhone = wsPhone; }
}
```

Create the file result and service interface:

```java
package com.armada.account.model.vo;

/** @param exportedCount TXT 中实际写入的唯一号码数量 */
public record AccountWsPhoneExportFile(String filename, byte[] bytes, int exportedCount) {
}
```

```java
package com.armada.account.service;

import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.vo.AccountWsPhoneExportFile;

/** 所选账号 WS 号码导出服务。 */
public interface AccountWsPhoneExportService {
    AccountWsPhoneExportFile export(AccountWsPhoneExportDTO request);
}
```

- [ ] **Step 4: Declare the Mapper query and error code**

Add to `AccountMapper`:

```java
List<AccountWsPhoneExportRow> selectNormalWsPhonesByIds(
        @Param("ids") List<Long> ids,
        @Param("normalAccountState") int normalAccountState);
```

Import `AccountWsPhoneExportRow`. In `ErrorCode`, replace the current last member:

```java
LOGIN_FAILED(40103, "租户码或密码错误"),

/** 账号 WS 号码导出执行失败，调用方可提示重试。 */
ACCOUNT_WS_PHONE_EXPORT_FAILED(50001, "导出失败，请重新操作。");
```

- [ ] **Step 5: Implement the service minimally**

Create `AccountWsPhoneExportServiceImpl`:

```java
package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountWsPhoneExportFile;
import com.armada.account.model.vo.AccountWsPhoneExportRow;
import com.armada.account.service.AccountWsPhoneExportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 所选正常账号 WS 号码导出实现。 */
@Service
public class AccountWsPhoneExportServiceImpl implements AccountWsPhoneExportService {

    private static final Logger log = LoggerFactory.getLogger(AccountWsPhoneExportServiceImpl.class);
    private static final int MAX_IDS = 2000;
    private static final int QUERY_CHUNK_SIZE = 500;
    private static final String DEFAULT_FILE_PREFIX = "全部WS号";
    private static final ZoneId EXPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String WINDOWS_FORBIDDEN = "<>:\"/\\|?*";

    private final AccountMapper accountMapper;
    private final Clock clock;

    @Autowired
    public AccountWsPhoneExportServiceImpl(AccountMapper accountMapper) {
        this(accountMapper, Clock.system(EXPORT_ZONE));
    }

    AccountWsPhoneExportServiceImpl(AccountMapper accountMapper, Clock clock) {
        this.accountMapper = accountMapper;
        this.clock = clock;
    }

    @Override
    public AccountWsPhoneExportFile export(AccountWsPhoneExportDTO request) {
        List<Long> ids = normalizeIds(request == null ? null : request.ids());
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (ids.size() > MAX_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次最多导出 2000 个账号");
        }

        try {
            // 固定 500 条分片，限制单条 IN SQL 的参数数量。
            Set<String> phones = new LinkedHashSet<>();
            for (int from = 0; from < ids.size(); from += QUERY_CHUNK_SIZE) {
                int to = Math.min(from + QUERY_CHUNK_SIZE, ids.size());
                List<AccountWsPhoneExportRow> rows = accountMapper.selectNormalWsPhonesByIds(
                        ids.subList(from, to), AccountStateCode.NORMAL);
                if (rows == null) {
                    continue;
                }
                for (AccountWsPhoneExportRow row : rows) {
                    String phone = digitsOnly(row == null ? null : row.getWsPhone());
                    if (!phone.isEmpty()) {
                        phones.add(phone);
                    }
                }
            }
            if (phones.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION,
                        "当前所选账号中没有可导出的有效WS号码。");
            }

            String content = String.join("\n", phones);
            String filename = buildFilename(request == null ? null : request.groupName());
            return new AccountWsPhoneExportFile(
                    filename,
                    content.getBytes(StandardCharsets.UTF_8),
                    phones.size());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("账号 WS 号码导出失败 selectedCount={}", ids.size(), ex);
            throw new BusinessException(ErrorCode.ACCOUNT_WS_PHONE_EXPORT_FAILED);
        }
    }

    private static List<Long> normalizeIds(List<Long> source) {
        if (source == null) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : source) {
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String digitsOnly(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder digits = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        return digits.toString();
    }

    private String buildFilename(String groupName) {
        String prefix = safeFilePrefix(groupName);
        String date = LocalDate.now(clock).format(DATE_FORMAT);
        return prefix + "_" + date + ".txt";
    }

    private static String safeFilePrefix(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return DEFAULT_FILE_PREFIX;
        }
        String trimmed = groupName.trim();
        StringBuilder safe = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isISOControl(ch) || WINDOWS_FORBIDDEN.indexOf(ch) >= 0) {
                safe.append('_');
            } else {
                safe.append(ch);
            }
        }
        String result = safe.toString().replaceFirst("[. ]+$", "");
        return result.isBlank() ? DEFAULT_FILE_PREFIX : result;
    }
}
```

- [ ] **Step 6: Run the service test and verify GREEN**

```bash
mvn -q -Dtest=AccountWsPhoneExportServiceImplTest test
```

Expected: PASS. Confirm the test output has no warnings or unexpected errors.

- [ ] **Step 7: Commit the service layer**

```bash
git add src/main/java/com/armada/account/model/dto/AccountWsPhoneExportDTO.java \
  src/main/java/com/armada/account/model/vo/AccountWsPhoneExportRow.java \
  src/main/java/com/armada/account/model/vo/AccountWsPhoneExportFile.java \
  src/main/java/com/armada/account/service/AccountWsPhoneExportService.java \
  src/main/java/com/armada/account/service/impl/AccountWsPhoneExportServiceImpl.java \
  src/main/java/com/armada/account/mapper/AccountMapper.java \
  src/main/java/com/armada/shared/exception/ErrorCode.java \
  src/test/java/com/armada/account/service/impl/AccountWsPhoneExportServiceImplTest.java
git commit -m "feat(account): build ws phone export file"
```

### Task 2: Add and verify the tenant-safe normal-account query

**Files:**
- Create: `armada-api/src/test/java/com/armada/account/mapper/AccountWsPhoneExportMapperDbTest.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`

**Interfaces:**
- Consumes: `AccountMapper.selectNormalWsPhonesByIds(ids, normalAccountState)` declared in Task 1.
- Produces: ID-ordered `AccountWsPhoneExportRow` values for only active, current-tenant, normal accounts.

- [ ] **Step 1: Write the failing real-database test**

Create `AccountWsPhoneExportMapperDbTest`:

```java
package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountWsPhoneExportRow;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountWsPhoneExportMapperDbTest extends DbTestBase {

    @Autowired private AccountMapper accountMapper;
    @Autowired private AccountStateMapper accountStateMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void queryReturnsOnlySelectedActiveNormalAccountsFromCurrentTenant() {
        long now = System.currentTimeMillis();
        String prefix = "601" + now % 1_000_000L;
        Account normalFirst = seed(prefix + "01", AccountStateCode.NORMAL, now, false);
        Account normalSecond = seed(prefix + "02", AccountStateCode.NORMAL, now + 1, false);
        Account banned = seed(prefix + "03", AccountStateCode.BANNED, now + 2, false);
        Account nullState = seed(prefix + "04", null, now + 3, false);
        Account noState = seed(prefix + "05", null, now + 4, true);
        Account deleted = seed(prefix + "06", AccountStateCode.NORMAL, now + 5, false);
        jdbcTemplate.update("UPDATE account SET deleted_at = ? WHERE id = ?", now, deleted.getId());

        TenantContext.set(TEST_TENANT_ID + 1);
        Account otherTenant = seed(prefix + "07", AccountStateCode.NORMAL, now + 6, false);
        TenantContext.set(TEST_TENANT_ID);

        List<AccountWsPhoneExportRow> rows = accountMapper.selectNormalWsPhonesByIds(
                List.of(
                        otherTenant.getId(), deleted.getId(), noState.getId(), nullState.getId(),
                        banned.getId(), normalSecond.getId(), normalFirst.getId()),
                AccountStateCode.NORMAL);

        assertThat(rows).extracting(AccountWsPhoneExportRow::getId)
                .containsExactly(normalFirst.getId(), normalSecond.getId());
        assertThat(rows).extracting(AccountWsPhoneExportRow::getWsPhone)
                .containsExactly(normalFirst.getWsPhone(), normalSecond.getWsPhone());
    }

    private Account seed(String phone, Integer stateCode, long now, boolean omitStateRow) {
        Account account = new Account();
        account.setWsPhone(phone);
        account.setAccountType(1);
        account.setOwnership(1);
        account.setPriority(0);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);
        if (omitStateRow) {
            return account;
        }

        AccountState state = new AccountState();
        state.setAccountId(account.getId());
        state.setProxyFailureCount(0);
        state.setPullIntoGroupCount(0);
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
        accountStateMapper.insert(state);
        if (stateCode != null) {
            jdbcTemplate.update(
                    "UPDATE account_state SET account_state = ? WHERE account_id = ?",
                    stateCode, account.getId());
        }
        return account;
    }
}
```

- [ ] **Step 2: Run the Mapper DB test and verify RED**

```bash
mvn -q -Dtest=AccountWsPhoneExportMapperDbTest test
```

Expected: FAIL with a MyBatis binding/statement error because the XML statement has not been added.

- [ ] **Step 3: Implement the Mapper XML query**

Add after `selectActiveByIds` in `AccountMapper.xml`:

```xml
  <!--
    所选账号 WS 号码导出最小投影。
    tenant_id 由租户拦截器注入；INNER JOIN 排除无状态行，normalAccountState 由业务常量传入。
  -->
  <select id="selectNormalWsPhonesByIds"
          resultType="com.armada.account.model.vo.AccountWsPhoneExportRow">
    <if test="ids != null and ids.size() &gt; 0">
    SELECT a.id, a.ws_phone AS wsPhone
    FROM account a
    INNER JOIN account_state s
      ON s.account_id = a.id
     AND s.tenant_id = a.tenant_id
    WHERE a.deleted_at IS NULL
      AND s.account_state = #{normalAccountState}
      AND a.id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
    ORDER BY a.id ASC
    </if>
    <if test="ids == null or ids.size() == 0">
    SELECT NULL AS id, NULL AS wsPhone FROM DUAL WHERE 1=0
    </if>
  </select>
```

- [ ] **Step 4: Run Mapper and service tests and verify GREEN**

```bash
mvn -q -Dtest=AccountWsPhoneExportMapperDbTest,AccountWsPhoneExportServiceImplTest test
```

Expected: PASS. The DB test must exclude the other tenant, deleted, banned, null-state, and missing-state rows.

- [ ] **Step 5: Commit the query**

```bash
git add src/main/resources/mapper/account/AccountMapper.xml \
  src/test/java/com/armada/account/mapper/AccountWsPhoneExportMapperDbTest.java
git commit -m "feat(account): query normal ws phones for export"
```

### Task 3: Expose the TXT attachment endpoint

**Files:**
- Modify: `armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java`
- Modify: `armada-api/src/main/java/com/armada/account/controller/AccountController.java`

**Interfaces:**
- Consumes: `AccountWsPhoneExportService.export(request)` from Task 1.
- Produces: `POST /api/accounts/export-ws-phones` with TXT bytes and `X-Export-Count` on success; JSON on failure.

- [ ] **Step 1: Write failing Controller tests**

Add a mock field and include it in the `AccountController` constructor call:

```java
@Mock
private AccountWsPhoneExportService accountWsPhoneExportService;
```

Register project exception handling in `setUp`:

```java
mockMvc = MockMvcBuilders
        .standaloneSetup(new AccountController(
                accountService,
                accountGroupService,
                accountOnlineCommandService,
                accountBatchLifecycleService,
                accountLifecycleCommandService,
                accountOnlineAttemptLogService,
                accountWsPhoneExportService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
```

Add imports for `AccountWsPhoneExportFile`, `AccountWsPhoneExportService`, `BusinessException`, `ErrorCode`,
`GlobalExceptionHandler`, `StandardCharsets`, and static MockMvc `content`/`header` matchers. Add tests:

```java
@Test
void postExportWsPhonesReturnsTxtAndActualCountHeaders() throws Exception {
    byte[] bytes = "60123456789\n8613800138000".getBytes(StandardCharsets.UTF_8);
    when(accountWsPhoneExportService.export(any())).thenReturn(
            new AccountWsPhoneExportFile("马来西亚客户组_2026-07-14.txt", bytes, 2));

    mockMvc.perform(post("/api/accounts/export-ws-phones")
                    .contentType("application/json")
                    .content("{\"ids\":[101,102],\"groupName\":\"马来西亚客户组\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain;charset=UTF-8"))
            .andExpect(content().bytes(bytes))
            .andExpect(header().string("X-Export-Count", "2"))
            .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
            .andExpect(header().string("Access-Control-Expose-Headers",
                    org.hamcrest.Matchers.containsString("X-Export-Count")))
            .andExpect(header().string("Content-Disposition",
                    org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(result -> assertThat(
                    org.springframework.http.ContentDisposition.parse(
                            result.getResponse().getHeader("Content-Disposition"))
                            .getFilename())
                    .isEqualTo("马来西亚客户组_2026-07-14.txt"));

    verify(accountWsPhoneExportService).export(any());
}

@Test
void postExportWsPhonesReturnsJsonAndNoAttachmentWhenNoValidPhoneExists() throws Exception {
    when(accountWsPhoneExportService.export(any())).thenThrow(
            new BusinessException(
                    ErrorCode.VALIDATION,
                    "当前所选账号中没有可导出的有效WS号码。"));

    mockMvc.perform(post("/api/accounts/export-ws-phones")
                    .contentType("application/json")
                    .content("{\"ids\":[101]}"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.message").value("当前所选账号中没有可导出的有效WS号码。"))
            .andExpect(header().doesNotExist("Content-Disposition"))
            .andExpect(header().doesNotExist("X-Export-Count"));
}

@Test
void postExportWsPhonesReturnsStableJsonMessageForExportFailure() throws Exception {
    when(accountWsPhoneExportService.export(any())).thenThrow(
            new BusinessException(ErrorCode.ACCOUNT_WS_PHONE_EXPORT_FAILED));

    mockMvc.perform(post("/api/accounts/export-ws-phones")
                    .contentType("application/json")
                    .content("{\"ids\":[101]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(50001))
            .andExpect(jsonPath("$.message").value("导出失败，请重新操作。"))
            .andExpect(header().doesNotExist("Content-Disposition"));
}
```

- [ ] **Step 2: Run the Controller test and verify RED**

```bash
mvn -q -Dtest=AccountControllerTest test
```

Expected: compilation FAIL because `AccountController` does not yet accept the export service or expose the endpoint.

- [ ] **Step 3: Inject the export service and add the endpoint**

Add `AccountWsPhoneExportService` as the final constructor dependency and field in `AccountController`. Add imports for the DTO/result,
`StandardCharsets`, `HttpHeaders`, `MediaType`, and `ResponseEntity`. Add:

```java
/**
 * 导出前端勾选且账号状态正常的 WS 号码。
 *
 * <p>成功直接返回 UTF-8 TXT 文件；业务失败由全局异常处理器返回统一 JSON，避免生成空文件。</p>
 *
 * @param request 所选账号 ID 和可选分组名称
 * @return TXT 附件响应，X-Export-Count 为实际写入号码数
 */
@PostMapping("/export-ws-phones")
public ResponseEntity<byte[]> exportWsPhones(@RequestBody AccountWsPhoneExportDTO request) {
    AccountWsPhoneExportFile file = accountWsPhoneExportService.export(request);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
    headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
            .filename(file.filename(), StandardCharsets.UTF_8)
            .build());
    headers.setContentLength(file.bytes().length);
    headers.set("X-Export-Count", String.valueOf(file.exportedCount()));
    headers.setAccessControlExposeHeaders(List.of(
            HttpHeaders.CONTENT_DISPOSITION,
            "X-Export-Count"));
    return ResponseEntity.ok().headers(headers).body(file.bytes());
}
```

- [ ] **Step 4: Run Controller and service tests and verify GREEN**

```bash
mvn -q -Dtest=AccountControllerTest,AccountWsPhoneExportServiceImplTest test
```

Expected: PASS. Success is TXT with attachment/count headers; the two failures remain JSON without attachment headers.

- [ ] **Step 5: Commit the endpoint**

```bash
git add src/main/java/com/armada/account/controller/AccountController.java \
  src/test/java/com/armada/account/controller/AccountControllerTest.java
git commit -m "feat(account): expose ws phone txt export"
```

### Task 4: Verify the complete backend change

**Files:**
- Verify all files listed above; do not modify unrelated dirty-worktree files.

**Interfaces:**
- Consumes: the completed Service, Mapper, and Controller contracts.
- Produces: evidence that the feature and existing backend suite pass.

- [ ] **Step 1: Run all focused export tests**

```bash
mvn -q -Dtest=AccountWsPhoneExportServiceImplTest,AccountWsPhoneExportMapperDbTest,AccountControllerTest test
```

Expected: BUILD SUCCESS with all selected tests passing.

- [ ] **Step 2: Run related account regression tests**

```bash
mvn -q -Dtest=AccountImportParserTest,AccountServiceImplTest,AccountControllerDbTest test
```

Expected: BUILD SUCCESS; existing import validation, account service, and controller database behavior remain unchanged.

- [ ] **Step 3: Run the full backend suite**

```bash
mvn -q test
```

Expected: BUILD SUCCESS. If the environment lacks the configured MySQL/Testcontainers runtime, report that environment failure separately and do not describe the suite as passing.

- [ ] **Step 4: Inspect comments, formatting, and repository state**

Run from repository root:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors in feature files. Preserve and report the pre-existing `.gitattributes`, deploy script, `.agents`, `.codex`, and temporary deploy-file changes without staging or editing them.

- [ ] **Step 5: Confirm requirement coverage before completion**

Verify from test evidence and final diff:

```text
TXT attachment exists and contains one unique cleaned number per line
country-code digits remain intact
only selected active NORMAL accounts from current tenant are queried
empty final output returns exact no-valid-number JSON and no file
unexpected failures return exact retry JSON
X-Export-Count equals actual TXT line count
Shanghai-date filename and fallback filename are correct
no database migration or filename sequence was introduced
```
