# Admin IP Country Master Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build dynamic country options for Armada IP management from admin-owned country master data, seeded with the 248 countries/regions from `0630IP管理、IP统计.html`.

**Architecture:** Add a platform-wide `country` table owned by the `admin` domain and ignored by the tenant SQL interceptor. Expose a read-only admin country options API, then let `resource` call the `CountryService` interface to resolve new `countryValue` inputs back to the existing `ip_proxy.region` Chinese snapshot. Keep `ip_proxy.tenant_id` and current IP allocation behavior unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, MyBatis-Plus tenant interceptor, Flyway, MySQL, JUnit 5, AssertJ, Mockito, MockMvc, real DbTest.

---

## Source Spec

- Spec: `docs/superpowers/specs/2026-07-01-admin-ip-country-master-data-design.md`
- Prototype: `/Users/daishuaishuai/IdeaProjects/0630IP管理、IP统计.html`
- Prototype country list constant: `BUYER_CHANNEL_COUNTRY_LIST`, 248 rows.

## File Structure

Create:
- `armada-api/src/main/resources/db/migration/V021__country_master_data.sql`
- `armada-api/src/main/java/com/armada/platform/country/model/entity/Country.java`
- `armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionVO.java`
- `armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionsVO.java`
- `armada-api/src/main/java/com/armada/platform/country/mapper/CountryMapper.java`
- `armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml`
- `armada-api/src/main/java/com/armada/platform/country/service/CountryService.java`
- `armada-api/src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java`
- `armada-api/src/main/java/com/armada/admin/controller/CountryController.java`
- `armada-api/src/test/java/com/armada/platform/country/mapper/CountryMapperDbTest.java`
- `armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java`
- `armada-api/src/test/java/com/armada/admin/controller/CountryControllerDbTest.java`
- `.harness/changes/ip-country-master-data/db-migrations.sql`
- `.harness/changes/ip-country-master-data/rollback.sql`
- `.harness/changes/ip-country-master-data/summary.md`

Modify:
- `armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java`
- `armada-api/src/main/java/com/armada/resource/model/dto/IpProxyQuery.java`
- `armada-api/src/main/java/com/armada/resource/model/dto/IpProxyImportDTO.java`
- `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`
- `.harness/wiki/数据模型.md`
- `.harness/wiki/armada_endpoints.json`
- `.harness/wiki/接口协议.md`

Do not modify:
- `ip_proxy` table shape.
- `account_import_batch.ip_region`.
- Role/menu permission tables. They do not exist yet for this feature.

## Cross-Domain Rule

`resource` may call `admin.service.CountryService`. It must not import `admin.mapper.CountryMapper`, `admin.model.entity.Country`, or admin controller classes. This follows the harness rule: cross business-domain calls go through the other domain's Service interface.

## Version Guard

Before implementing, run:

```bash
ls armada-api/src/main/resources/db/migration | sort
```

Expected today: latest is `V020__account_import_original_payload.sql`, so use `V021__country_master_data.sql`. If another branch has already added `V021`, use the next free version and update this plan's file references during execution.

---

## Task 1: Schema DbTest First

**Files:**
- Create: `armada-api/src/test/java/com/armada/platform/country/mapper/CountryMapperDbTest.java`

- [ ] **Step 1: Write the failing DbTest**

Create `CountryMapperDbTest`:

```java
package com.armada.platform.country.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.country.model.entity.Country;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 国家/地区主数据 DbTest:验证 Flyway seed、租户拦截忽略表和 IP 下拉排序。
 */
class CountryMapperDbTest extends DbTestBase {

    @Autowired
    private CountryMapper mapper;

    @Test
    void countActive_returnsSeeded248Rows() {
        assertThat(mapper.countActive()).isEqualTo(248);
    }

    @Test
    void selectIpSupported_returnsSeededRowsInSortOrder() {
        List<Country> rows = mapper.selectIpSupported();

        assertThat(rows).hasSize(248);
        assertThat(rows.get(0).getIso2()).isEqualTo("AF");
        assertThat(rows.get(0).getNameZh()).isEqualTo("阿富汗");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getIso2()).isEqualTo("IN");
            assertThat(row.getNameZh()).isEqualTo("印度");
            assertThat(row.getPhonePrefix()).isEqualTo("+91");
            assertThat(row.getFlag()).isEqualTo("🇮🇳");
        });
    }

    @Test
    void selectActiveByIso2AndNameZh_ignoreTenantInterceptor() {
        Country byIso2 = mapper.selectActiveByIso2("IN");
        Country byName = mapper.selectActiveByNameZh("印度");

        assertThat(byIso2).isNotNull();
        assertThat(byIso2.getNameZh()).isEqualTo("印度");
        assertThat(byName).isNotNull();
        assertThat(byName.getIso2()).isEqualTo("IN");
    }
}
```

- [ ] **Step 2: Run the focused DbTest and verify RED**

Run:

```bash
armada-api/dbtest.sh CountryMapperDbTest
```

Expected: FAIL before implementation because `CountryMapper` and `Country` do not exist.

- [ ] **Step 3: Commit only the failing test if following strict TDD commits**

```bash
git add armada-api/src/test/java/com/armada/platform/country/mapper/CountryMapperDbTest.java
git commit -m "test(admin): cover country master data seed"
```

---

## Task 2: Flyway Migration and Tenant Interceptor Ignore

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V021__country_master_data.sql`
- Modify: `armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java`
- Create: `.harness/changes/ip-country-master-data/db-migrations.sql`
- Create: `.harness/changes/ip-country-master-data/rollback.sql`
- Create: `.harness/changes/ip-country-master-data/summary.md`

- [ ] **Step 1: Generate explicit country seed values from the prototype**

Run this local generator from repo root. It reads the prototype, derives the two-letter regional flag code, and writes explicit SQL values. The final migration file will contain literal `iso2` values; the app will not derive ISO2 at runtime.

```bash
node - <<'NODE' > /tmp/armada-country-values.sql
const fs = require("fs");
const htmlPath = "/Users/daishuaishuai/IdeaProjects/0630IP管理、IP统计.html";
const html = fs.readFileSync(htmlPath, "utf8");
const block = html.match(/const BUYER_CHANNEL_COUNTRY_LIST = \[([\s\S]*?)\n\];/);
if (!block) throw new Error("BUYER_CHANNEL_COUNTRY_LIST not found");
const matches = [...block[1].matchAll(/\{countryFlag:'([^']*)', country:'([^']*)', countryCode:'([^']*)'\}/g)];
if (matches.length !== 248) throw new Error(`Expected 248 countries, got ${matches.length}`);
function flagToCode(flag) {
  const cps = [...flag].map(ch => ch.codePointAt(0));
  if (cps.length !== 2 || cps.some(cp => cp < 0x1F1E6 || cp > 0x1F1FF)) {
    throw new Error(`Cannot derive two-letter region code from flag ${flag}`);
  }
  return cps.map(cp => String.fromCharCode(cp - 0x1F1E6 + 65)).join("");
}
function sql(s) {
  return "'" + String(s ?? "").replace(/'/g, "''") + "'";
}
const seedAt = 1767225600000;
const seen = new Set();
const values = matches.map((m, index) => {
  const flag = m[1];
  const nameZh = m[2];
  const phonePrefix = m[3];
  const iso2 = flagToCode(flag);
  if (seen.has(iso2)) throw new Error(`Duplicate region code ${iso2}`);
  seen.add(iso2);
  const sortOrder = (index + 1) * 10;
  return `(${sql(iso2)}, ${sql(nameZh)}, NULL, ${sql(phonePrefix)}, ${sql(flag)}, 1, 1, ${sortOrder}, ${seedAt}, ${seedAt})`;
});
console.log(values.join(",\n") + ";");
NODE
```

Verify the generated values:

```bash
grep -c "^(" /tmp/armada-country-values.sql
grep "('IN', '印度'" /tmp/armada-country-values.sql
grep "('AC', '阿森松岛'" /tmp/armada-country-values.sql
```

Expected:
- First command prints `248`.
- Second command prints the India row.
- Third command prints the Ascension Island row. `AC` is a CLDR/regional flag code used by the prototype; keep the DB column name `iso2` for API stability, but document it as a two-letter country/region code in code comments.

- [ ] **Step 2: Create the Flyway migration**

Create `armada-api/src/main/resources/db/migration/V021__country_master_data.sql` with this header and schema. Append the generated values after `INSERT INTO country ... VALUES`.

```sql
CREATE TABLE country (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    iso2            CHAR(2)      NOT NULL COMMENT 'ISO/CLDR 二字母国家/地区码,大写',
    name_zh         VARCHAR(64)  NOT NULL COMMENT '中文展示名',
    name_en         VARCHAR(128)          DEFAULT NULL COMMENT '英文展示名',
    phone_prefix    VARCHAR(16)           DEFAULT NULL COMMENT '手机号国际区号,如 +91、+1-684',
    flag            VARCHAR(16)           DEFAULT NULL COMMENT '国旗 emoji',
    is_enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用:1=启用 0=停用',
    is_ip_supported TINYINT      NOT NULL DEFAULT 1 COMMENT 'IP 管理是否展示:1=展示 0=不展示',
    sort_order      INT          NOT NULL DEFAULT 0 COMMENT '排序值,越小越靠前',
    remark          VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    created_at      BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒,应用层写)',
    updated_at      BIGINT       NOT NULL COMMENT '更新时间(epoch毫秒,应用层写)',
    deleted_at      BIGINT                DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删',
    is_active       TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL COMMENT '软删唯一键辅助:活行=1 软删=NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_country_iso2_active (iso2, is_active),
    KEY idx_country_enabled_sort (is_enabled, is_ip_supported, sort_order, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '国家/地区主数据';

INSERT INTO country
    (iso2, name_zh, name_en, phone_prefix, flag, is_enabled, is_ip_supported, sort_order, created_at, updated_at)
VALUES
```

Append:

```bash
cat /tmp/armada-country-values.sql
```

The final SQL must end with one semicolon after the last value tuple.

- [ ] **Step 3: Register `country` in the tenant interceptor ignored table set**

Modify `armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java`:

```java
private static final Set<String> IGNORED_TABLES = Set.of("tenant", "country");
```

- [ ] **Step 4: Add harness migration records**

Create `.harness/changes/ip-country-master-data/db-migrations.sql` as a copy of `V021__country_master_data.sql`.

Create `.harness/changes/ip-country-master-data/rollback.sql`:

```sql
DROP TABLE IF EXISTS country;
```

Create `.harness/changes/ip-country-master-data/summary.md`:

```markdown
# 变更记录：IP 管理国家主数据

- 日期 / 分支 / worktree: 2026-07-01 / main / armada
- 需求来源: IP 管理国家下拉从前端写死改为管理员国家主数据
- 状态: 进行中

## 目标（一句话）

新增平台级国家/地区主数据,供 IP 管理国家下拉动态读取。

## 缺口拆解 / 任务清单
- [ ] 建 `country` 表并 seed 248 个国家/地区
- [ ] 国家选项接口返回 `MIXED + 248` 行
- [ ] IP 管理查询/导入兼容 `countryValue`
- [ ] 更新数据模型和接口文档

## 关键设计决策

- `country` 无 `tenant_id`,加入 MyBatis 租户忽略表。
- `ip_proxy.tenant_id` 保留,本次不迁平台池。
- `混合（不限国家）` 不入表,由接口虚拟返回。
- 时间字段使用 epoch 毫秒 BIGINT。

## 验证（evidence-before-done）

实施完成前保持进行中;最终收尾时必须追加真实验证命令和输出。

## 部署

实施完成前保持进行中;部署后必须追加 commit、环境和验证结果。

## 遗留 / 跟进

- 角色菜单权限研发后,再补 IP 管理和国家主数据维护菜单权限。
```

- [ ] **Step 5: Verify migration file contains 248 seed rows**

Run:

```bash
grep -c "^(" /tmp/armada-country-values.sql
! rg -n "[D]ATETIME|[C]URRENT_TIMESTAMP|ON[ ]UPDATE" armada-api/src/main/resources/db/migration/V021__country_master_data.sql
```

Expected: first command prints `248`; second command exits 0 with no output, proving the migration does not use database time types/defaults.

- [ ] **Step 6: Commit**

```bash
git add armada-api/src/main/resources/db/migration/V021__country_master_data.sql \
        armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java \
        .harness/changes/ip-country-master-data
git commit -m "feat(admin): seed country master data"
```

---

## Task 3: Country Entity, Mapper, XML

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/country/model/entity/Country.java`
- Create: `armada-api/src/main/java/com/armada/platform/country/mapper/CountryMapper.java`
- Create: `armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml`

- [ ] **Step 1: Create the entity**

Create `Country.java` with plain getters/setters:

```java
package com.armada.platform.country.model.entity;

/**
 * 国家/地区主数据实体,映射 country 表。无 tenant_id,由 MyBatisConfig 忽略租户拦截。
 */
public class Country {

    private Long id;
    private String iso2;
    private String nameZh;
    private String nameEn;
    private String phonePrefix;
    private String flag;
    private Integer isEnabled;
    private Integer isIpSupported;
    private Integer sortOrder;
    private String remark;
    private Long createdAt;
    private Long updatedAt;
    private Long deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIso2() { return iso2; }
    public void setIso2(String iso2) { this.iso2 = iso2; }
    public String getNameZh() { return nameZh; }
    public void setNameZh(String nameZh) { this.nameZh = nameZh; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getPhonePrefix() { return phonePrefix; }
    public void setPhonePrefix(String phonePrefix) { this.phonePrefix = phonePrefix; }
    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }
    public Integer getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Integer isEnabled) { this.isEnabled = isEnabled; }
    public Integer getIsIpSupported() { return isIpSupported; }
    public void setIsIpSupported(Integer isIpSupported) { this.isIpSupported = isIpSupported; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
```

- [ ] **Step 2: Create the mapper interface**

Create `CountryMapper.java`:

```java
package com.armada.platform.country.mapper;

import com.armada.platform.country.model.entity.Country;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 国家/地区主数据 Mapper。country 表无 tenant_id,必须在 MyBatisConfig.IGNORED_TABLES 中。
 */
@Mapper
public interface CountryMapper {

    long countActive();

    List<Country> selectIpSupported();

    Country selectActiveByIso2(@Param("iso2") String iso2);

    Country selectActiveByNameZh(@Param("nameZh") String nameZh);
}
```

- [ ] **Step 3: Create the mapper XML**

Create `CountryMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.platform.country.mapper.CountryMapper">

  <sql id="Columns">
    id, iso2, name_zh, name_en, phone_prefix, flag,
    is_enabled, is_ip_supported, sort_order, remark,
    created_at, updated_at, deleted_at
  </sql>

  <select id="countActive" resultType="long">
    SELECT COUNT(*)
    FROM country
    WHERE deleted_at IS NULL
  </select>

  <select id="selectIpSupported" resultType="com.armada.platform.country.model.entity.Country">
    SELECT <include refid="Columns"/>
    FROM country
    WHERE deleted_at IS NULL
      AND is_enabled = 1
      AND is_ip_supported = 1
    ORDER BY sort_order ASC, id ASC
  </select>

  <select id="selectActiveByIso2" resultType="com.armada.platform.country.model.entity.Country">
    SELECT <include refid="Columns"/>
    FROM country
    WHERE deleted_at IS NULL
      AND is_enabled = 1
      AND iso2 = #{iso2}
    LIMIT 1
  </select>

  <select id="selectActiveByNameZh" resultType="com.armada.platform.country.model.entity.Country">
    SELECT <include refid="Columns"/>
    FROM country
    WHERE deleted_at IS NULL
      AND is_enabled = 1
      AND name_zh = #{nameZh}
    LIMIT 1
  </select>

</mapper>
```

- [ ] **Step 4: Validate XML and DbTest**

Run:

```bash
xmllint --noout armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml
armada-api/dbtest.sh CountryMapperDbTest
```

Expected: XML command exits 0; DbTest PASS.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/platform/country/model/entity/Country.java \
        armada-api/src/main/java/com/armada/platform/country/mapper/CountryMapper.java \
        armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml
git commit -m "feat(admin): add country mapper"
```

---

## Task 4: Country Service and Options API

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionsVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/country/service/CountryService.java`
- Create: `armada-api/src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/admin/controller/CountryController.java`
- Create: `armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java`
- Create: `armada-api/src/test/java/com/armada/admin/controller/CountryControllerDbTest.java`

- [ ] **Step 1: Write service unit tests first**

Create `CountryServiceImplTest.java`:

```java
package com.armada.platform.country.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.impl.CountryServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CountryServiceImplTest {

    @Mock
    private CountryMapper mapper;

    @InjectMocks
    private CountryServiceImpl service;

    @Test
    void options_ipScopePrependsMixedThenCountries() {
        when(mapper.selectIpSupported()).thenReturn(List.of(country("IN", "印度", "+91", "🇮🇳")));

        CountryOptionsVO result = service.options("ip");

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).value()).isEqualTo("MIXED");
        assertThat(result.rows().get(0).nameZh()).isEqualTo("混合（不限国家）");
        assertThat(result.rows().get(0).virtual()).isTrue();
        assertThat(result.rows().get(1).value()).isEqualTo("IN");
        assertThat(result.rows().get(1).phonePrefix()).isEqualTo("+91");
        assertThat(result.rows().get(1).virtual()).isFalse();
    }

    @Test
    void resolveIpRegion_supportsMixedIso2AndLegacyChinese() {
        when(mapper.selectActiveByIso2("IN")).thenReturn(country("IN", "印度", "+91", "🇮🇳"));
        when(mapper.selectActiveByNameZh("印度")).thenReturn(country("IN", "印度", "+91", "🇮🇳"));

        assertThat(service.resolveIpRegion("MIXED")).isEqualTo("混合（不限国家）");
        assertThat(service.resolveIpRegion("mixed")).isEqualTo("混合（不限国家）");
        assertThat(service.resolveIpRegion("混合（不限国家）")).isEqualTo("混合（不限国家）");
        assertThat(service.resolveIpRegion("IN")).isEqualTo("印度");
        assertThat(service.resolveIpRegion("印度")).isEqualTo("印度");
        assertThat(service.resolveIpRegion("  ")).isNull();
    }

    @Test
    void resolveIpRegion_unknownValueThrowsValidation() {
        assertThatThrownBy(() -> service.resolveIpRegion("ZZ"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("国家不存在或已停用");
                });
    }

    private static Country country(String iso2, String nameZh, String phonePrefix, String flag) {
        Country country = new Country();
        country.setIso2(iso2);
        country.setNameZh(nameZh);
        country.setPhonePrefix(phonePrefix);
        country.setFlag(flag);
        return country;
    }
}
```

- [ ] **Step 2: Run service tests and verify RED**

Run:

```bash
(cd armada-api && mvn -q -Dtest=CountryServiceImplTest test)
```

Expected: FAIL because service and VO classes do not exist.

- [ ] **Step 3: Add VO records**

Create `CountryOptionVO.java`:

```java
package com.armada.platform.country.model.vo;

/**
 * 国家下拉选项。value 是前端提交值:真实国家用 iso2,混合虚拟项用 MIXED。
 */
public record CountryOptionVO(
        String value,
        String iso2,
        String nameZh,
        String phonePrefix,
        String flag,
        boolean virtual) {
}
```

Create `CountryOptionsVO.java`:

```java
package com.armada.platform.country.model.vo;

import java.util.List;

/** 国家下拉选项列表包装,匹配前端 data.rows 读取方式。 */
public record CountryOptionsVO(List<CountryOptionVO> rows) {
}
```

- [ ] **Step 4: Add service interface and implementation**

Create `CountryService.java`:

```java
package com.armada.platform.country.service;

import com.armada.platform.country.model.vo.CountryOptionsVO;

/**
 * 国家/地区主数据服务。跨域消费者只能依赖本 Service,不能直接碰 admin mapper/entity。
 */
public interface CountryService {

    CountryOptionsVO options(String scope);

    String resolveIpRegion(String value);
}
```

Create `CountryServiceImpl.java`:

```java
package com.armada.platform.country.service.impl;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CountryServiceImpl implements CountryService {

    private static final String IP_SCOPE = "ip";
    private static final String MIXED_VALUE = "MIXED";
    private static final String MIXED_REGION = "混合（不限国家）";
    private static final CountryOptionVO MIXED_OPTION =
            new CountryOptionVO(MIXED_VALUE, null, MIXED_REGION, "", "🌐", true);

    private final CountryMapper mapper;

    public CountryServiceImpl(CountryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public CountryOptionsVO options(String scope) {
        String normalizedScope = StringUtils.hasText(scope) ? scope.trim() : IP_SCOPE;
        if (!IP_SCOPE.equalsIgnoreCase(normalizedScope)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的国家选项范围: " + scope);
        }
        List<CountryOptionVO> rows = new ArrayList<>();
        rows.add(MIXED_OPTION);
        for (Country country : mapper.selectIpSupported()) {
            rows.add(new CountryOptionVO(
                    country.getIso2(),
                    country.getIso2(),
                    country.getNameZh(),
                    country.getPhonePrefix() == null ? "" : country.getPhonePrefix(),
                    country.getFlag() == null ? "" : country.getFlag(),
                    false));
        }
        return new CountryOptionsVO(List.copyOf(rows));
    }

    @Override
    public String resolveIpRegion(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (MIXED_VALUE.equalsIgnoreCase(trimmed) || MIXED_REGION.equals(trimmed)) {
            return MIXED_REGION;
        }
        Country country = null;
        if (trimmed.length() == 2) {
            country = mapper.selectActiveByIso2(trimmed.toUpperCase(Locale.ROOT));
        }
        if (country == null) {
            country = mapper.selectActiveByNameZh(trimmed);
        }
        if (country == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家不存在或已停用: " + trimmed);
        }
        return country.getNameZh();
    }
}
```

- [ ] **Step 5: Add controller and controller DbTest**

Create `CountryController.java`:

```java
package com.armada.admin.controller;

import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台国家/地区主数据只读接口。当前无角色菜单权限体系,先复用现有 /api/** 租户头登录态。
 */
@RestController
@RequestMapping("/api/admin/countries")
public class CountryController {

    private final CountryService service;

    public CountryController(CountryService service) {
        this.service = service;
    }

    /**
     * 国家下拉选项。scope=ip 时返回 MIXED 虚拟项 + 启用且 IP 可展示的真实国家。
     *
     * @param scope 选项范围,当前支持 ip;为空时按 ip 处理
     * @return 国家选项列表
     */
    @GetMapping("/options")
    public ApiResponse<CountryOptionsVO> options(@RequestParam(defaultValue = "ip") String scope) {
        return ApiResponse.ok(service.options(scope));
    }
}
```

Create `CountryControllerDbTest.java`:

```java
package com.armada.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class CountryControllerDbTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void options_ipScopeReturnsMixedPlusSeededCountries() throws Exception {
        mockMvc.perform(get("/api/admin/countries/options")
                        .param("scope", "ip")
                        .header("X-Tenant-Code", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rows.length()").value(249))
                .andExpect(jsonPath("$.data.rows[0].value").value("MIXED"))
                .andExpect(jsonPath("$.data.rows[0].nameZh").value("混合（不限国家）"))
                .andExpect(jsonPath("$.data.rows[0].virtual").value(true))
                .andExpect(jsonPath("$.data.rows[1].value").value("AF"))
                .andExpect(jsonPath("$.data.rows[1].nameZh").value("阿富汗"));
    }
}
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
(cd armada-api && mvn -q -Dtest=CountryServiceImplTest test)
armada-api/dbtest.sh CountryControllerDbTest
```

Expected: both PASS.

- [ ] **Step 7: Commit**

```bash
git add armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionVO.java \
        armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionsVO.java \
        armada-api/src/main/java/com/armada/platform/country/service/CountryService.java \
        armada-api/src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java \
        armada-api/src/main/java/com/armada/admin/controller/CountryController.java \
        armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java \
        armada-api/src/test/java/com/armada/admin/controller/CountryControllerDbTest.java
git commit -m "feat(admin): expose country options"
```

---

## Task 5: IP Management Country Value Compatibility

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/model/dto/IpProxyQuery.java`
- Modify: `armada-api/src/main/java/com/armada/resource/model/dto/IpProxyImportDTO.java`
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`

- [ ] **Step 1: Add failing IP service tests**

In `IpProxyServiceImplTest`, add:

```java
@Mock
private com.armada.platform.country.service.CountryService countryService;

@Test
void list_resolvesCountryValueToLegacyRegionBeforeMapper() {
    IpProxyQuery query = new IpProxyQuery();
    query.setCountryValue("IN");
    when(countryService.resolveIpRegion("IN")).thenReturn("印度");
    when(mapper.countPage(query)).thenReturn(0L);

    service.list(query);

    assertThat(query.getRegion()).isEqualTo("印度");
    verify(mapper).countPage(query);
}

@Test
void importProxies_resolvesCountryValueBeforePersistingRegion() {
    when(countryService.resolveIpRegion("IN")).thenReturn("印度");
    when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

    IpProxyImportResultVO result = service.importProxies(
            new IpProxyImportDTO(null, 1, "供应商A", "1.1.1.1:8080:user1:pass1", "IN"));

    assertThat(result.insertedRows()).isEqualTo(1);
    ArgumentCaptor<IpProxy> proxyCaptor = ArgumentCaptor.forClass(IpProxy.class);
    verify(mapper).insert(proxyCaptor.capture());
    assertThat(proxyCaptor.getValue().getRegion()).isEqualTo("印度");
}

@Test
void importProxies_legacyRegionStillResolvesThroughCountryService() {
    when(countryService.resolveIpRegion("印度")).thenReturn("印度");
    when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

    service.importProxies(new IpProxyImportDTO("印度", 1, "供应商A", "1.1.1.1:8080:user1:pass1"));

    ArgumentCaptor<IpProxy> proxyCaptor = ArgumentCaptor.forClass(IpProxy.class);
    verify(mapper).insert(proxyCaptor.capture());
    assertThat(proxyCaptor.getValue().getRegion()).isEqualTo("印度");
}
```

Also update existing tests that use `@InjectMocks` if constructor injection needs the new `countryService` mock. For tests that do not set country behavior and use DTOs with country, add:

```java
when(countryService.resolveIpRegion("中国")).thenReturn("中国");
```

or change the helper `dto(String text)` to stub before calling service in each import test.

- [ ] **Step 2: Run focused unit tests and verify RED**

Run:

```bash
(cd armada-api && mvn -q -Dtest=IpProxyServiceImplTest test)
```

Expected: FAIL because `countryValue` and `CountryService` integration are not implemented.

- [ ] **Step 3: Add `countryValue` to query**

Modify `IpProxyQuery.java`:

```java
/** 新国家下拉提交值:真实国家为 ISO/CLDR 二字母码,混合为 MIXED;为空时兼容旧 region。 */
private String countryValue;

public String getCountryValue() {
    return countryValue;
}

public void setCountryValue(String countryValue) {
    this.countryValue = countryValue;
}
```

- [ ] **Step 4: Add `countryValue` to import DTO without breaking old tests**

Replace `IpProxyImportDTO` with:

```java
package com.armada.resource.model.dto;

/**
 * IP 代理批量导入入参（@RequestBody）。国家/协议/来源作为本次全部记录的统一属性，
 * {@code text} 为多行原文，每行一个代理 {@code host:port:username:password}。
 *
 * @param region       旧国家/分组中文展示名,兼容旧前端
 * @param protocol     协议码（1=HTTP 2=SOCKS5），必填
 * @param source       来源，必填
 * @param text         多行原文，每行 {@code host:port:username:password}
 * @param countryValue 新国家下拉提交值:真实国家为 ISO/CLDR 二字母码,混合为 MIXED
 */
public record IpProxyImportDTO(
        String region,
        Integer protocol,
        String source,
        String text,
        String countryValue) {

    public IpProxyImportDTO(String region, Integer protocol, String source, String text) {
        this(region, protocol, source, text, null);
    }
}
```

- [ ] **Step 5: Inject CountryService into IP service and normalize once**

Modify `IpProxyServiceImpl` constructor:

```java
private final CountryService countryService;

public IpProxyServiceImpl(IpProxyMapper mapper, IpProxyConverter converter, CountryService countryService) {
    this.mapper = mapper;
    this.converter = converter;
    this.countryService = countryService;
}
```

Add import:

```java
import com.armada.platform.country.service.CountryService;
```

Modify `list`:

```java
@Override
public PageResult<IpProxyVO> list(IpProxyQuery query) {
    IpProxyQuery normalized = normalizeQuery(query);
    long total = mapper.countPage(normalized);
    List<IpProxyVO> rows = total == 0
            ? List.of()
            : converter.toVOList(mapper.selectPage(normalized));
    return PageResult.of(rows, normalized.getPage(), normalized.getPageSize(), total);
}
```

Modify `importProxies`:

```java
@Override
@Transactional(rollbackFor = Exception.class)
public IpProxyImportResultVO importProxies(IpProxyImportDTO dto) {
    IpProxyImportDTO normalized = normalizeImport(dto);
    validateImport(normalized);

    List<LineOutcome<ProxyLine, Boolean>> outcomes = LineImporter.run(
            normalized.text(), IpProxyServiceImpl::parseProxyLine, ProxyLine::dedupKey,
            line -> persistProxy(normalized, line));

    int total = outcomes.size();
    int failed = (int) outcomes.stream().filter(o -> o.kind() == Kind.FAILED).count();
    int inserted = (int) outcomes.stream()
            .filter(o -> o.kind() == Kind.PERSISTED && Boolean.TRUE.equals(o.persistResult())).count();
    int skipped = total - failed - inserted;

    List<String> errors = outcomes.stream().filter(o -> o.kind() == Kind.FAILED)
            .map(o -> "第 " + o.lineNo() + " 行：" + o.reason()).toList();
    log.info("IP代理导入 region={} protocol={} total={} inserted={} skipped={} failed={}",
            normalized.region(), normalized.protocol(), total, inserted, skipped, failed);
    return new IpProxyImportResultVO(total, inserted, skipped, failed, errors);
}
```

Add helpers:

```java
private IpProxyQuery normalizeQuery(IpProxyQuery query) {
    IpProxyQuery target = query == null ? new IpProxyQuery() : query;
    String submitted = StringUtils.hasText(target.getCountryValue()) ? target.getCountryValue() : target.getRegion();
    target.setRegion(countryService.resolveIpRegion(submitted));
    return target;
}

private IpProxyImportDTO normalizeImport(IpProxyImportDTO dto) {
    if (dto == null) {
        throw new BusinessException(ErrorCode.VALIDATION, "导入参数不能为空");
    }
    String submitted = StringUtils.hasText(dto.countryValue()) ? dto.countryValue() : dto.region();
    String region = countryService.resolveIpRegion(submitted);
    return new IpProxyImportDTO(region, dto.protocol(), dto.source(), dto.text(), dto.countryValue());
}
```

Add imports if they are not already present:

```java
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
```

- [ ] **Step 6: Run focused unit tests**

Run:

```bash
(cd armada-api && mvn -q -Dtest=IpProxyServiceImplTest test)
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add armada-api/src/main/java/com/armada/resource/model/dto/IpProxyQuery.java \
        armada-api/src/main/java/com/armada/resource/model/dto/IpProxyImportDTO.java \
        armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java \
        armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java
git commit -m "feat(resource): resolve ip country values"
```

---

## Task 6: Documentation Generation

**Files:**
- Modify: `.harness/wiki/数据模型.md`
- Modify: `.harness/wiki/armada_endpoints.json`
- Modify: `.harness/wiki/接口协议.md`

- [ ] **Step 1: Update API docs from controllers**

Run:

```bash
python3 .harness/wiki/parse_endpoints.py --root . --output .harness/wiki/armada_endpoints.json
python3 .harness/wiki/format_api.py --input .harness/wiki/armada_endpoints.json --output .harness/wiki/接口协议.md
```

Expected: `CountryController` appears with `GET /api/admin/countries/options`.

- [ ] **Step 2: Update data model from real DB schema**

After Flyway has migrated the DbTest database, dump information schema TSV files and run the generator:

```bash
set -a
source armada-api/.env
set +a
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -N -B -e "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLUMN_COMMENT,EXTRA,ORDINAL_POSITION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION" > /tmp/wheel_columns.tsv
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -N -B -e "SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,NON_UNIQUE,SEQ_IN_INDEX FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX" > /tmp/wheel_indexes.tsv
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -N -B -e "SELECT TABLE_NAME,TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME" > /tmp/wheel_tables.tsv
python3 .harness/wiki/gen_datamodel.py
cp /tmp/datamodel_tables.md .harness/wiki/数据模型.md
```

Expected: `.harness/wiki/数据模型.md` contains a `country（国家/地区主数据）` section with `created_at bigint`, `updated_at bigint`, and `deleted_at bigint`.

- [ ] **Step 3: Commit docs**

```bash
git add .harness/wiki/数据模型.md .harness/wiki/armada_endpoints.json .harness/wiki/接口协议.md
git commit -m "docs: update country master data wiki"
```

---

## Task 7: Final Verification

**Files:**
- Verify all files changed by Tasks 1-6.

- [ ] **Step 1: Run XML validation**

```bash
xmllint --noout armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml
```

Expected: exit 0.

- [ ] **Step 2: Run focused unit tests**

```bash
(cd armada-api && mvn -q -Dtest='CountryServiceImplTest,IpProxyServiceImplTest' test)
```

Expected: all tests PASS.

- [ ] **Step 3: Run focused DbTests**

```bash
armada-api/dbtest.sh 'CountryMapperDbTest,CountryControllerDbTest'
```

Expected: all tests PASS and no tests skipped silently.

- [ ] **Step 4: Run existing IP mapper DbTest**

```bash
armada-api/dbtest.sh IpProxyMapperDbTest
```

Expected: PASS; existing IP allocation and region ordering behavior unchanged.

- [ ] **Step 5: Run broader backend tests if time permits**

```bash
(cd armada-api && mvn -q test)
```

Expected: all tests PASS. If DbTest env is unavailable for broad run, record that limitation and rely on focused DbTests above.

- [ ] **Step 6: Verify requirements checklist**

Confirm each item:
- `country` table has no `tenant_id`.
- `country` is in `MyBatisConfig.IGNORED_TABLES`.
- Seed count is 248.
- `GET /api/admin/countries/options?scope=ip` returns 249 rows.
- `MIXED` is first and virtual.
- `IN` resolves to `印度`.
- `mixed` and `混合（不限国家）` resolve to `混合（不限国家）`.
- `ip_proxy.tenant_id` and `ip_proxy.region` schema are unchanged.
- No role/menu permission tables are added.
- All time fields use BIGINT epoch milliseconds.

- [ ] **Step 7: Final commit if verification caused doc updates**

```bash
git status --short
git add docs/superpowers/plans/2026-07-01-admin-ip-country-master-data.md
git commit -m "docs: plan admin ip country master data"
```

If the plan was committed before execution, skip this commit.

---

## Frontend Handoff

This armada worktree does not contain `armada-admin-web` or `armada-saas-web`. Backend will expose the contract needed by the IP 管理 page:

- `GET /api/admin/countries/options?scope=ip`
- Response: `ApiResponse<CountryOptionsVO>`
- `data.rows[0]` is `MIXED`.
- Real country options use `value=iso2`, `nameZh`, `phonePrefix`, `flag`.
- IP list can submit `countryValue=IN` or legacy `region=印度`.
- IP import can submit JSON field `countryValue: "IN"` or legacy `region: "印度"`.

When the frontend repo is available, update the IP 管理国家搜索下拉 to call this API and remove reliance on the hard-coded `BUYER_CHANNEL_COUNTRY_LIST` for that menu.

---

## Self-Review

Spec coverage:
- Dynamic country table: Task 2 and Task 3.
- 248-country seed: Task 2 and Task 1 DbTest.
- Tenant interceptor ignore: Task 2.
- Read-only options API with MIXED first: Task 4.
- IP query/import compatibility: Task 5.
- Preserve `ip_proxy.tenant_id`: Task 7 checklist.
- Skip role/menu permissions: File structure and Task 7 checklist.
- Epoch milliseconds and Beijing display requirement: Task 2 schema and data model wiki verification.

No intentionally deferred backend behavior remains in this plan. Frontend implementation is a separate handoff because this repository currently has no frontend app directory.
