# 导入链接(group import-links)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 armada「导入链接」业务块的后端:WS链接分组 CRUD、群链接导入(TXT/CSV/Excel,同步,upsert-by-url 收编)、明细、导出失败、迁移分组。

**Architecture:** 单工程 `com.armada.group` 域,controller→service→mapper(无 Repository);共享 `group_link` 群组表只建 import 身份段(检测/health 拆出延后);`group_link_label`/`group_link`/`group_link_import_batch`/`group_link_import_detail` 4 表;导入复用并增强 `shared/util/LineImporter`(结构化逐行产出);文件解析 `FileLinesExtractor`(FastExcel)。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.7(租户拦截器)、MapStruct、Flyway、FastExcel 1.3.0、JUnit5 + 真库 DbTest。

## Global Constraints

设计依据见 `armada/docs/business/导入链接.md`。每个 task 隐含遵守:
- 规范:`.harness/rules/{编码规范,数据模型规范,工程结构}.md`。红线:禁魔法值(枚举/常量)、禁重复(复用优先)、禁空catch、禁返null(Optional/空集)、禁System.out、方法≤100行/类≤800行/参数≤5/圈复杂度≤10/嵌套≤3。
- 落位:`com.armada.group.{controller,service,service.impl,mapper,converter,model.{entity,dto,vo}}`;Mapper XML `armada-api/src/main/resources/mapper/group/`;Flyway `armada-api/src/main/resources/db/migration/`。
- 表名单数 snake_case;列 snake_case;时间列 `*_at` DATETIME(UTC);软删 `deleted_at`;枚举列 TINYINT + 逐值 COMMENT + Java enum;`xxx_id` 为关联列(JOIN,**不建物理外键**);**不用虚拟列**,plain 唯一键。
- 租户:4 表均有 `tenant_id`,拦截器自动注入,**不手写 tenant_id**(除非 FOR UPDATE/复杂 SQL,本块无)。
- 对象:entity 普通类+getter/setter(无 Lombok);`*Query` 可变 class extends `PageQuery`;`*DTO`/`*VO` record;转换 MapStruct;分页统一 `PageResult.of(list,page,pageSize,total)`。
- 时间出参:VO 用 `Long` epoch 毫秒(converter `toInstant(ZoneOffset.UTC).toEpochMilli()`,对标 `MarketingTemplateConverter`)。
- 测试:Service 可 mock;Mapper/SQL/Flyway 必须真库 DbTest;Mapper XML 改动过 DbTest(裸尖括号防 crash)。先红后绿,频繁提交。
- 命名口径:`link_url` 为归一化链接;`label_id` 关联 `group_link_label.id`(WS分组归属,只导入链接写)。

## 关键接口契约(跨 task 一致)

```java
// shared/util/LineImporter(重构后)
enum Kind { FAILED, DUPLICATE, PERSISTED }
record LineOutcome<T, R>(int lineNo, String raw, Kind kind, String reason, T record, R persistResult)
static <T,R> List<LineOutcome<T,R>> run(String text, LineParser<T> parser, Function<T,Object> dedupKey, Function<T,R> persist)

// group/model/GroupLinkImportResult: code() 1=SUCCESS 2=ADOPTED 3=DUPLICATE 4=FORMAT_ERROR
// group/service:
PageResult<GroupLinkLabelVO> GroupLinkLabelService.list(GroupLinkLabelQuery q)
GroupLinkLabelVO            GroupLinkLabelService.create(GroupLinkLabelDTO d)
void                        GroupLinkLabelService.update(Long id, GroupLinkLabelDTO d)
int                         GroupLinkLabelService.batchDelete(List<Long> ids)        // 返回 deletedCount
GroupLinkImportResultVO     GroupLinkImportService.importLinks(GroupLinkImportDTO d) // d 含 labelId/batchName/text/file
PageResult<GroupLinkImportDetailVO> GroupLinkImportService.listDetails(GroupLinkImportDetailQuery q)
List<String[]>              GroupLinkImportService.exportFailed(Long labelId, Long batchId) // CSV 行
PageResult<GroupLinkVO>     GroupLinkService.listByLabel(GroupLinkQuery q)
int                         GroupLinkService.migrate(List<Long> linkIds, Long targetLabelId)
void                        GroupLinkService.batchDelete(List<Long> ids)
List<String>               FileLinesExtractor.extract(MultipartFile file, String text)
```

---

## Phase 0 — 脚手架(依赖 + 迁移 + 枚举)

### Task 0.1: 加 FastExcel 依赖

**Files:** Modify: `armada-api/pom.xml`

- [ ] **Step 1: 加依赖**(在 `<dependencies>` 内,挨着 mysql/mybatis)

```xml
<!-- 群链接导入:解析 TXT/CSV/Excel(EasyExcel 维护版,流式不OOM) -->
<dependency>
    <groupId>cn.idev.excel</groupId>
    <artifactId>fastexcel</artifactId>
    <version>1.3.0</version>
</dependency>
```

- [ ] **Step 2: 验证编译**

Run: `cd armada-api && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS(FastExcel + 传递 POI 拉取成功)

- [ ] **Step 3: Commit**

```bash
git add armada-api/pom.xml
git commit -m "build(group): add fastexcel 1.3.0 for link import parsing"
```

### Task 0.2: Flyway V003 建 4 表

**Files:** Create: `armada-api/src/main/resources/db/migration/V003__group_import_links.sql`; Create: `.harness/changes/group-import-links/{db-migrations.sql,rollback.sql}`

- [ ] **Step 1: 写迁移**(逐字取自设计 §5.2,4 表 `CREATE TABLE IF NOT EXISTS`,plain 唯一键、无虚拟列、无物理外键)

```sql
-- group_link_label(WS链接分组)
CREATE TABLE IF NOT EXISTS group_link_label (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    name VARCHAR(100) NOT NULL COMMENT 'WS链接分组名称(租户内不可重复)',
    region VARCHAR(64) DEFAULT NULL COMMENT '使用国家/区域展示名(可「混合」)',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(UTC)',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(UTC)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删时间;NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_name (tenant_id, name),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WS链接分组';

-- group_link(群链接·import身份段)
CREATE TABLE IF NOT EXISTS group_link (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    link_url VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '归一化后群邀请链接(租户内唯一,按字节精确去重)',
    group_name VARCHAR(128) DEFAULT NULL COMMENT '业务群名(导入时填,可空)',
    label_id BIGINT DEFAULT NULL COMMENT '所属WS链接分组(关联group_link_label.id;只导入链接菜单写;拉群/进群粘贴=NULL)',
    import_batch_id BIGINT DEFAULT NULL COMMENT '来源导入批次(关联group_link_import_batch.id)',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注(纯导入备注)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(UTC)',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(UTC)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删时间;NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_url (tenant_id, link_url),
    KEY idx_tenant_label (tenant_id, label_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接(跨业务共享群组表-import身份段)';

-- group_link_import_batch(导入批次)
CREATE TABLE IF NOT EXISTS group_link_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    label_id BIGINT NOT NULL COMMENT '导入目标WS链接分组(关联;导入时固定)',
    batch_name VARCHAR(255) NOT NULL COMMENT '来源文件/批次名称(用户填)',
    source_file_name VARCHAR(255) DEFAULT NULL COMMENT '上传文件原名;纯text导入=NULL',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '解析总行数',
    inserted_rows INT NOT NULL DEFAULT 0 COMMENT '新增行数',
    adopted_rows INT NOT NULL DEFAULT 0 COMMENT '收编行数',
    skipped_rows INT NOT NULL DEFAULT 0 COMMENT '批内重复跳过行数',
    failed_rows INT NOT NULL DEFAULT 0 COMMENT '格式不合格行数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '导入时间(UTC)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删时间;随分组级联软删',
    PRIMARY KEY (id),
    KEY idx_tenant_label (tenant_id, label_id),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接导入批次';

-- group_link_import_detail(导入明细)
CREATE TABLE IF NOT EXISTS group_link_import_detail (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    batch_id BIGINT NOT NULL COMMENT '所属批次(关联;分组维度经JOIN batch.label_id取)',
    line_no INT NOT NULL COMMENT '拼接后行号',
    raw_url VARCHAR(255) DEFAULT NULL COMMENT '原文链接(失败行也保留)',
    group_name VARCHAR(128) DEFAULT NULL COMMENT '群名称(可空)',
    result TINYINT NOT NULL COMMENT '导入结果:1=成功新增 2=收编 3=批内重复 4=格式错误',
    fail_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因(result≥3时)',
    group_link_id BIGINT DEFAULT NULL COMMENT '成功/收编时关联group_link.id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(UTC)',
    PRIMARY KEY (id),
    KEY idx_tenant_batch (tenant_id, batch_id),
    KEY idx_tenant_batch_result (tenant_id, batch_id, result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接导入明细';
```

- [ ] **Step 2: 写 rollback.sql**

```sql
DROP TABLE IF EXISTS group_link_import_detail;
DROP TABLE IF EXISTS group_link_import_batch;
DROP TABLE IF EXISTS group_link;
DROP TABLE IF EXISTS group_link_label;
```
拷一份迁移到 `.harness/changes/group-import-links/db-migrations.sql`。

- [ ] **Step 3: 真库跑迁移**

Run: `cd armada-api && set -a && source .env && set +a && mvn -q flyway:migrate`(或起 app 触发)
Expected: V003 success;`information_schema` 见 4 表(`mysql_query` 验证)。

- [ ] **Step 4: Commit**

```bash
git add armada-api/src/main/resources/db/migration/V003__group_import_links.sql .harness/changes/group-import-links/
git commit -m "feat(group): V003 create group import-links tables (4 tables, plain unique, no FK)"
```

### Task 0.3: GroupLinkImportResult 枚举

**Files:** Create: `armada-api/src/main/java/com/armada/group/model/GroupLinkImportResult.java`; Test: `armada-api/src/test/java/com/armada/group/model/GroupLinkImportResultTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.armada.group.model;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class GroupLinkImportResultTest {
    @Test void codeRoundTrip() {
        assertEquals(2, GroupLinkImportResult.ADOPTED.code());
        assertEquals(GroupLinkImportResult.DUPLICATE, GroupLinkImportResult.fromCode(3));
    }
    @Test void fromCodeRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> GroupLinkImportResult.fromCode(9));
    }
}
```

- [ ] **Step 2: 跑测试看红**  Run: `mvn -q -Dtest=GroupLinkImportResultTest test` Expected: 编译失败(类不存在)

- [ ] **Step 3: 实现枚举**(对标 `IpProxyStatus`)

```java
package com.armada.group.model;

/** 群链接逐行导入结果。 */
public enum GroupLinkImportResult {
    /** 成功:新链接,已入 group_link。 */
    SUCCESS(1),
    /** 收编:已存在的链接,归到本次目标分组(改 label_id 或复活)。 */
    ADOPTED(2),
    /** 重复:本批次内同 url 出现多次,跳过。 */
    DUPLICATE(3),
    /** 格式错误:不符合 WhatsApp 邀请链接格式。 */
    FORMAT_ERROR(4);

    private final int code;
    GroupLinkImportResult(int code) { this.code = code; }
    public int code() { return code; }

    /** 按 code 反查;非法 code 抛 {@link IllegalArgumentException}(禁静默兜底)。 */
    public static GroupLinkImportResult fromCode(int code) {
        for (GroupLinkImportResult r : values()) {
            if (r.code == code) { return r; }
        }
        throw new IllegalArgumentException("未知导入结果码: " + code);
    }
}
```

- [ ] **Step 4: 跑测试看绿**  Run: `mvn -q -Dtest=GroupLinkImportResultTest test` Expected: PASS

- [ ] **Step 5: Commit**  `git add ... && git commit -m "feat(group): add GroupLinkImportResult enum"`

---

## Phase 1 — 共享 LineImporter 重构(结构化逐行产出)+ IP 调用方迁移

> 反屎山 #2:不加并行重载,直接重构 + 改唯一调用方(IP),测试兜底。

### Task 1.1: 重构 LineImporter 为结构化逐行产出

**Files:** Modify: `armada-api/src/main/java/com/armada/shared/util/LineImporter.java`; Modify: `armada-api/src/test/java/com/armada/shared/util/LineImporterTest.java`

- [ ] **Step 1: 改写测试到新 API**(替换旧断言)

```java
package com.armada.shared.util;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
class LineImporterTest {
    @Test void emitsPerLineOutcomes() {
        List<LineOutcome<String, Boolean>> out = LineImporter.run(
            "a\nbad\na\n\n c ",
            line -> { if (line.equals("bad")) throw new ImportLineException("坏行"); return line; },
            s -> s,                                  // dedupKey = 自身
            s -> Boolean.TRUE);                      // persist 恒 INSERTED
        assertEquals(4, out.size());                 // 空行跳过,4 个非空
        assertEquals(Kind.PERSISTED, out.get(0).kind());   // a
        assertEquals(Kind.FAILED, out.get(1).kind());      // bad
        assertEquals("坏行", out.get(1).reason());
        assertEquals(2, out.get(1).lineNo());              // 物理行号
        assertEquals(Kind.DUPLICATE, out.get(2).kind());   // 第二个 a
        assertEquals(Kind.PERSISTED, out.get(3).kind());   // c(trim)
    }
    @Test void nullTextYieldsEmpty() {
        assertTrue(LineImporter.run(null, s -> s, s -> s, s -> 1).isEmpty());
    }
}
```

- [ ] **Step 2: 跑看红**  Run: `mvn -q -Dtest=LineImporterTest test` Expected: 编译失败(新 API 不存在)

- [ ] **Step 3: 重构实现**(整类替换)

```java
package com.armada.shared.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 逐行文本导入通用骨架:按行 trim 跳空 → 解析(抛 {@link ImportLineException}=失败行)→ 批内去重 → 落库,
 * 产出每行的结构化结果(行号/原文/类别/原因/记录/落库返回),由调用方据此汇总统计或落明细。本类不碰 DB、不持状态。
 */
public final class LineImporter {

    private LineImporter() {}

    /** 单行类别。 */
    public enum Kind {
        /** 解析失败(格式不合格)。 */ FAILED,
        /** 批内重复(同去重键本批已出现)。 */ DUPLICATE,
        /** 已交付 persist(落库结果见 {@code persistResult})。 */ PERSISTED
    }

    /**
     * 单行产出。
     *
     * @param lineNo        物理行号(从 1 起)
     * @param raw           trim 后原文
     * @param kind          类别
     * @param reason        失败原因(仅 FAILED 非空)
     * @param record        解析后的记录(FAILED 为 null)
     * @param persistResult persist 返回值(仅 PERSISTED 非空)
     * @param <T> 记录类型
     * @param <R> 落库返回类型
     */
    public record LineOutcome<T, R>(int lineNo, String raw, Kind kind, String reason, T record, R persistResult) {}

    /** 行解析器:行原文 → 记录;不合格抛 {@link ImportLineException}(带原因)。 */
    @FunctionalInterface
    public interface LineParser<T> { T parse(String line); }

    /**
     * 跑一次逐行导入,返回每行产出。
     *
     * @param text     多行原文(null/空 → 空列表)
     * @param parser   行解析器
     * @param dedupKey 记录 → 批内去重键
     * @param persist  记录 → 落库结果(实现内做 DB 去重/收编/插入)
     */
    public static <T, R> List<LineOutcome<T, R>> run(String text,
            LineParser<T> parser, Function<T, Object> dedupKey, Function<T, R> persist) {
        List<LineOutcome<T, R>> out = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        String[] lines = text == null ? new String[0] : text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty()) { continue; }
            int lineNo = i + 1;
            T record;
            try {
                record = parser.parse(raw);
            } catch (ImportLineException e) {
                out.add(new LineOutcome<>(lineNo, raw, Kind.FAILED, e.getMessage(), null, null));
                continue;
            }
            if (!seen.add(dedupKey.apply(record))) {
                out.add(new LineOutcome<>(lineNo, raw, Kind.DUPLICATE, null, record, null));
                continue;
            }
            out.add(new LineOutcome<>(lineNo, raw, Kind.PERSISTED, null, record, persist.apply(record)));
        }
        return out;
    }
}
```

- [ ] **Step 4: 跑看绿**  Run: `mvn -q -Dtest=LineImporterTest test` Expected: PASS

- [ ] **Step 5: Commit**  `git commit -m "refactor(shared): LineImporter emits structured per-line outcomes"`

### Task 1.2: 迁移 IP 调用方到新 LineImporter

**Files:** Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`; Modify: `armada-api/src/test/java/com/armada/marketing/.../IpProxyServiceImplTest.java`(若存在;否则建)

- [ ] **Step 1: 跑现有 IP 测试看红**(LineImporter 改了 → IP 编译失败)
Run: `mvn -q -Dtest=IpProxyServiceImplTest test` Expected: 编译失败(`LineImporter.Result`/`Persisted` 不存在)

- [ ] **Step 2: 改 IP importProxies 用新 API**(替换 `importProxies` + `persistProxy` 返回类型)

```java
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
// ...
@Override
@Transactional(rollbackFor = Exception.class)
public IpProxyImportResultVO importProxies(IpProxyImportDTO dto) {
    validateImport(dto);
    List<LineOutcome<ProxyLine, Boolean>> outcomes = LineImporter.run(
            dto.text(), IpProxyServiceImpl::parseProxyLine, ProxyLine::dedupKey,
            line -> persistProxy(dto, line));   // 返回 true=新增 false=库内已存在跳过
    int total = outcomes.size();
    int failed = (int) outcomes.stream().filter(o -> o.kind() == Kind.FAILED).count();
    int inserted = (int) outcomes.stream()
            .filter(o -> o.kind() == Kind.PERSISTED && Boolean.TRUE.equals(o.persistResult())).count();
    int skipped = total - failed - inserted;   // 批内重复 + 库内已存在
    List<String> errors = outcomes.stream().filter(o -> o.kind() == Kind.FAILED)
            .map(o -> "第 " + o.lineNo() + " 行：" + o.reason()).toList();
    log.info("IP代理导入 region={} protocol={} total={} inserted={} skipped={} failed={}",
            dto.region(), dto.protocol(), total, inserted, skipped, failed);
    return new IpProxyImportResultVO(total, inserted, skipped, failed, errors);
}

/** 落库:DB 去重命中→false(跳过),否则插入→true。 */
private boolean persistProxy(IpProxyImportDTO dto, ProxyLine line) {
    if (mapper.countActiveByFullTuple(line.host(), line.port(), line.username(), line.password()) > 0) {
        return false;
    }
    IpProxy row = new IpProxy();
    // ... (原 set 不变) ...
    mapper.insert(row);
    return true;
}
```
删掉旧 `LineImporter.Result`/`Persisted` 残留引用。

- [ ] **Step 3: 跑看绿**  Run: `mvn -q -Dtest=IpProxyServiceImplTest test` Expected: PASS(导入统计语义不变)

- [ ] **Step 4: 全量编译 + 全测**  Run: `mvn -q test` Expected: 现有全绿(marketing/resource/shared 不回归)

- [ ] **Step 5: Commit**  `git commit -m "refactor(resource): migrate IP import to structured LineImporter"`

---

## Phase 2 — WS链接分组(group_link_label)CRUD

### Task 2.1: GroupLinkLabel 实体 + Mapper + XML(列表分页/聚合链接数)

**Files:** Create: `group/model/entity/GroupLinkLabel.java`、`group/mapper/GroupLinkLabelMapper.java`、`resources/mapper/group/GroupLinkLabelMapper.xml`;Test: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkLabelMapperDbTest.java`

- [ ] **Step 1: 写实体**(普通类+getter/setter,字段:id,tenantId,name,region,remark,createdAt,updatedAt,createdBy,deletedAt;LocalDateTime 时间;省略 getter/setter 体——按 `MarketingTemplate` 同风格全写)

```java
package com.armada.group.model.entity;
import java.time.LocalDateTime;
/** WS链接分组(group_link_label 一行)。 */
public class GroupLinkLabel {
    private Long id;
    private Long tenantId;
    private String name;
    private String region;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private LocalDateTime deletedAt;
    // getter/setter 全部(对照 MarketingTemplate 实体写法)
}
```

- [ ] **Step 2: 写 Mapper 接口**

```java
package com.armada.group.mapper;
import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.entity.GroupLinkLabel;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** WS链接分组数据访问。 */
@Mapper
public interface GroupLinkLabelMapper {
    long countPage(GroupLinkLabelQuery query);
    List<GroupLinkLabelVoRow> selectPage(GroupLinkLabelQuery query);   // 带 linkCount 聚合
    GroupLinkLabel selectActiveByName(@Param("name") String name);     // 活的同名(校验/复活)
    GroupLinkLabel selectDeletedByName(@Param("name") String name);    // 软删的同名(复活)
    int insert(GroupLinkLabel row);
    int reviveById(@Param("id") Long id);                              // deleted_at=NULL
    int updateProfile(GroupLinkLabel row);                            // name/region/remark
    int softDeleteByIds(@Param("ids") List<Long> ids);
    GroupLinkLabel selectById(@Param("id") Long id);
}
```
> `GroupLinkLabelVoRow` = mapper 投影 record(id,name,region,remark,linkCount,createdAt,updatedAt),放 `group/model/vo/`,供 converter 转 VO。

- [ ] **Step 3: 写 XML**(`resources/mapper/group/GroupLinkLabelMapper.xml`;注意 `&gt;`/`&lt;` 转义;`<if>` 包裹 keyword)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.group.mapper.GroupLinkLabelMapper">
  <sql id="filter">
    deleted_at IS NULL
    <if test="keyword != null and keyword != ''">AND name LIKE CONCAT('%', #{keyword}, '%')</if>
    <if test="id != null">AND id = #{id}</if>
    <if test="createdFrom != null">AND created_at &gt;= #{createdFrom}</if>
    <if test="createdTo != null">AND created_at &lt;= #{createdTo}</if>
  </sql>
  <select id="countPage" resultType="long">
    SELECT COUNT(*) FROM group_link_label WHERE <include refid="filter"/>
  </select>
  <select id="selectPage" resultType="com.armada.group.model.vo.GroupLinkLabelVoRow">
    SELECT l.id, l.name, l.region, l.remark, l.created_at AS createdAt, l.updated_at AS updatedAt,
      (SELECT COUNT(*) FROM group_link g WHERE g.label_id = l.id AND g.deleted_at IS NULL) AS linkCount
    FROM group_link_label l WHERE <include refid="filter"/>
    ORDER BY l.created_at DESC LIMIT #{offset}, #{pageSize}
  </select>
  <select id="selectActiveByName" resultType="com.armada.group.model.entity.GroupLinkLabel">
    SELECT * FROM group_link_label WHERE name = #{name} AND deleted_at IS NULL LIMIT 1
  </select>
  <select id="selectDeletedByName" resultType="com.armada.group.model.entity.GroupLinkLabel">
    SELECT * FROM group_link_label WHERE name = #{name} AND deleted_at IS NOT NULL LIMIT 1
  </select>
  <select id="selectById" resultType="com.armada.group.model.entity.GroupLinkLabel">
    SELECT * FROM group_link_label WHERE id = #{id} AND deleted_at IS NULL
  </select>
  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO group_link_label (name, region, remark, created_by)
    VALUES (#{name}, #{region}, #{remark}, #{createdBy})
  </insert>
  <update id="reviveById">UPDATE group_link_label SET deleted_at = NULL WHERE id = #{id}</update>
  <update id="updateProfile">
    UPDATE group_link_label SET name=#{name}, region=#{region}, remark=#{remark} WHERE id=#{id} AND deleted_at IS NULL
  </update>
  <update id="softDeleteByIds">
    UPDATE group_link_label SET deleted_at = NOW() WHERE deleted_at IS NULL AND id IN
    <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
  </update>
</mapper>
```
> `offset`/`pageSize` 来自 `PageQuery`(getOffset())。tenant_id 由拦截器自动注入 WHERE/INSERT,故 XML 不写 tenant_id。

- [ ] **Step 4: 写 DbTest**(真库,验分页/唯一/聚合/软删)

```java
package com.armada.group.mapper;
// 对照 marketing 现有 DbTest 基类/注解(@SpringBootTest + 真库 .env)
class GroupLinkLabelMapperDbTest {
    // @Autowired GroupLinkLabelMapper mapper;  在租户上下文内执行
    @Test void insert_then_selectActiveByName() { /* insert → selectActiveByName 命中 */ }
    @Test void uniqueNameRejectsDuplicateActive() { /* 插两条同名 → 第二条抛 DuplicateKey */ }
    @Test void selectPage_linkCount_countsActiveLinksOnly() { /* 插 label + 2 活 group_link + 1 软删 → linkCount=2 */ }
    @Test void softDeleteThenReviveByName() { /* 软删 → selectDeletedByName 命中 → reviveById → selectActiveByName 命中 */ }
}
```
(每个 @Test 写真实 insert/断言;参照 marketing DbTest 的建租户上下文方式。)

- [ ] **Step 5: 跑 DbTest 看红→实现 XML→看绿**  Run: `bash run-dbtest.sh -Dtest=GroupLinkLabelMapperDbTest`(或 `mvn`)Expected: 4/4 PASS

- [ ] **Step 6: Commit**  `git commit -m "feat(group): group_link_label entity+mapper+xml with DbTest"`

### Task 2.2: GroupLinkLabel DTO/Query/VO + Converter

**Files:** Create: `group/model/dto/{GroupLinkLabelQuery.java,GroupLinkLabelDTO.java,GroupIdsDTO.java}`、`group/model/vo/{GroupLinkLabelVO.java,GroupLinkLabelVoRow.java}`、`group/converter/GroupConverter.java`;Test: `group/converter/GroupConverterTest.java`

- [ ] **Step 1: 写对象**

```java
// GroupLinkLabelQuery(可变 class extends PageQuery)
package com.armada.group.model.dto;
import com.armada.shared.paging.PageQuery;
import java.time.LocalDateTime;
public class GroupLinkLabelQuery extends PageQuery {
    private String keyword; private Long id; private LocalDateTime createdFrom; private LocalDateTime createdTo;
    // getter/setter
}
// GroupLinkLabelDTO(写,record)
package com.armada.group.model.dto;
public record GroupLinkLabelDTO(String name, String region, String remark) {}
// GroupIdsDTO(批量删除通用)
package com.armada.group.model.dto;
import java.util.List;
public record GroupIdsDTO(List<Long> ids) {}
// GroupLinkLabelVoRow(mapper 投影)
package com.armada.group.model.vo;
import java.time.LocalDateTime;
public record GroupLinkLabelVoRow(Long id, String name, String region, String remark,
    long linkCount, LocalDateTime createdAt, LocalDateTime updatedAt) {}
// GroupLinkLabelVO(出参,时间 epoch 毫秒)
package com.armada.group.model.vo;
public record GroupLinkLabelVO(Long id, String name, String region, String remark,
    long linkCount, Long createdAt, Long updatedAt) {}
```

- [ ] **Step 2: 写 Converter 测试**

```java
package com.armada.group.converter;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*; import org.junit.jupiter.api.Test;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
class GroupConverterTest {
    private final GroupConverter c = new com.armada.group.converter.GroupConverterImpl();
    @Test void labelRowToVo_epochMillis() {
        var t = LocalDateTime.of(2024,6,1,0,0,0);
        var vo = c.toLabelVO(new GroupLinkLabelVoRow(1L,"印度","印度","r",5, t, t));
        assertEquals(t.toInstant(ZoneOffset.UTC).toEpochMilli(), vo.createdAt());
        assertEquals(5L, vo.linkCount());
    }
}
```

- [ ] **Step 3: 跑红 → 写 Converter → 跑绿**

```java
package com.armada.group.converter;
import com.armada.group.model.vo.*;
import java.time.LocalDateTime; import java.time.ZoneOffset; import java.util.List;
import org.mapstruct.Mapper;
/** group 域对象转换(时间统一 epoch 毫秒,对标 MarketingTemplateConverter)。 */
@Mapper(componentModel = "spring")
public interface GroupConverter {
    GroupLinkLabelVO toLabelVO(GroupLinkLabelVoRow row);
    List<GroupLinkLabelVO> toLabelVOList(List<GroupLinkLabelVoRow> rows);
    default Long toEpochMilli(LocalDateTime t) { return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli(); }
}
```
Run: `mvn -q -Dtest=GroupConverterTest test` Expected: PASS

- [ ] **Step 4: Commit**  `git commit -m "feat(group): label dto/query/vo + GroupConverter"`

### Task 2.3: GroupLinkLabelService(CRUD + 唯一校验 + 复活 + 级联删)

**Files:** Create: `group/service/GroupLinkLabelService.java`、`group/service/impl/GroupLinkLabelServiceImpl.java`;Test: `group/service/impl/GroupLinkLabelServiceImplTest.java`(mock mapper)

- [ ] **Step 1: 写接口**

```java
package com.armada.group.service;
import com.armada.group.model.dto.*; import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.shared.response.PageResult; import java.util.List;
public interface GroupLinkLabelService {
    PageResult<GroupLinkLabelVO> list(GroupLinkLabelQuery query);
    GroupLinkLabelVO create(GroupLinkLabelDTO dto);
    void update(Long id, GroupLinkLabelDTO dto);
    int batchDelete(List<Long> ids);
}
```

- [ ] **Step 2: 写 Service 测试**(mock GroupLinkLabelMapper + GroupLinkMapper + GroupLinkImportBatchMapper)

```java
// create: 活同名 → BusinessException(VALIDATION "名称重复")
// create: 软删同名 → reviveById + updateProfile,不 insert
// create: 全新 → insert
// batchDelete: 调 group_link/batch 级联软删 + label 软删,返回 count
// batchDelete: 空/超上限 → BusinessException
```
(写出每个 @Test 的 mock 行为 + 断言,参照 MarketingTemplateServiceImplTest。)

- [ ] **Step 3: 跑红 → 实现**

```java
package com.armada.group.service.impl;
// 构造器注入:GroupLinkLabelMapper, GroupLinkMapper, GroupLinkImportBatchMapper, GroupConverter
@Service
public class GroupLinkLabelServiceImpl implements GroupLinkLabelService {
    private static final int BATCH_DELETE_MAX = 100;
    // ... 构造器 ...
    @Override public PageResult<GroupLinkLabelVO> list(GroupLinkLabelQuery q) {
        long total = labelMapper.countPage(q);
        var rows = total == 0 ? List.<GroupLinkLabelVO>of() : converter.toLabelVOList(labelMapper.selectPage(q));
        return PageResult.of(rows, q.getPage(), q.getPageSize(), total);
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public GroupLinkLabelVO create(GroupLinkLabelDTO dto) {
        if (!StringUtils.hasText(dto.name())) throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空");
        if (labelMapper.selectActiveByName(dto.name()) != null)
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在");
        GroupLinkLabel deleted = labelMapper.selectDeletedByName(dto.name());
        GroupLinkLabel row = new GroupLinkLabel();
        row.setName(dto.name()); row.setRegion(dto.region()); row.setRemark(dto.remark());
        if (deleted != null) { row.setId(deleted.getId()); labelMapper.reviveById(deleted.getId()); labelMapper.updateProfile(row); }
        else { labelMapper.insert(row); }
        // 回查返回(linkCount=0 新分组)
        return new GroupLinkLabelVO(row.getId(), dto.name(), dto.region(), dto.remark(), 0L, null, null);
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public void update(Long id, GroupLinkLabelDTO dto) {
        GroupLinkLabel cur = labelMapper.selectById(id);
        if (cur == null) throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在");
        GroupLinkLabel other = labelMapper.selectActiveByName(dto.name());
        if (other != null && !other.getId().equals(id)) throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在");
        GroupLinkLabel row = new GroupLinkLabel();
        row.setId(id); row.setName(dto.name()); row.setRegion(dto.region()); row.setRemark(dto.remark());
        labelMapper.updateProfile(row);
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_DELETE_MAX)
            throw new BusinessException(ErrorCode.VALIDATION, "ids 数量须为 1.." + BATCH_DELETE_MAX);
        // 占用闸延后 no-op(task 表未建)
        groupLinkMapper.softDeleteByLabelIds(ids);
        importBatchMapper.softDeleteByLabelIds(ids);
        int n = labelMapper.softDeleteByIds(ids);
        log.info("WS链接分组批量删除 count={} ids={}", n, ids);
        return n;
    }
}
```
> 需在 `GroupLinkMapper`/`GroupLinkImportBatchMapper` 各加 `softDeleteByLabelIds(List<Long>)`(Task 3.x/3.y 一并加;若先做本 task,加桩方法 + XML)。

- [ ] **Step 4: 跑绿**  Run: `mvn -q -Dtest=GroupLinkLabelServiceImplTest test` Expected: PASS

- [ ] **Step 5: Commit**

### Task 2.4: GroupLinkLabelController(A1-A4)

**Files:** Create: `group/controller/GroupLinkLabelController.java`;Test:(可选 MockMvc 或留到验收)

- [ ] **Step 1: 实现 Controller**(对标 IpProxyController,薄)

```java
package com.armada.group.controller;
@RestController @RequestMapping("/api/group-link-labels")
public class GroupLinkLabelController {
    private final GroupLinkLabelService service;
    public GroupLinkLabelController(GroupLinkLabelService service) { this.service = service; }
    @GetMapping public ApiResponse<PageResult<GroupLinkLabelVO>> list(@ModelAttribute GroupLinkLabelQuery q) { return ApiResponse.ok(service.list(q)); }
    @PostMapping public ApiResponse<GroupLinkLabelVO> create(@RequestBody GroupLinkLabelDTO d) { return ApiResponse.ok(service.create(d)); }
    @PatchMapping("/{id}") public ApiResponse<Void> update(@PathVariable Long id, @RequestBody GroupLinkLabelDTO d) { service.update(id, d); return ApiResponse.ok(); }
    @PostMapping("/batch-delete") public ApiResponse<Integer> batchDelete(@RequestBody GroupIdsDTO r) { return ApiResponse.ok(service.batchDelete(r.ids())); }
}
```

- [ ] **Step 2: 编译 + 启动冒烟**  Run: `mvn -q -DskipTests compile`;起 app `GET /api/group-link-labels` 返回空分页(需鉴权,留验收)。

- [ ] **Step 3: Commit**  `git commit -m "feat(group): WS link-label CRUD endpoints (A1-A4)"`

---

## Phase 3 — 群链接 + 导入引擎(核心)

### Task 3.1: GroupLink 实体 + Mapper + XML(upsert-by-url / 列表 / 级联软删)

**Files:** Create: `group/model/entity/GroupLink.java`、`group/mapper/GroupLinkMapper.java`、`resources/mapper/group/GroupLinkMapper.xml`;Test: `group/mapper/GroupLinkMapperDbTest.java`

- [ ] **Step 1: 实体**(id,tenantId,linkUrl,groupName,labelId,importBatchId,remark,createdAt,updatedAt,createdBy,deletedAt + getter/setter)

- [ ] **Step 2: Mapper 接口**

```java
@Mapper
public interface GroupLinkMapper {
    GroupLink selectAnyByUrl(@Param("url") String url);                    // 含软删,upsert 命中用
    int insert(GroupLink row);                                            // 新链接
    int adoptToLabel(@Param("id") Long id, @Param("labelId") Long labelId,
                     @Param("batchId") Long batchId, @Param("groupName") String groupName); // 复活+改归属
    long countByLabel(GroupLinkQuery query);
    List<GroupLinkVoRow> selectPageByLabel(GroupLinkQuery query);          // JOIN batch 取 sourceFileName
    int migrateToLabel(@Param("ids") List<Long> ids, @Param("labelId") Long labelId);
    int softDeleteByIds(@Param("ids") List<Long> ids);
    int softDeleteByLabelIds(@Param("ids") List<Long> labelIds);
    int countActiveByIds(@Param("ids") List<Long> ids);                   // 迁移/删除存在性校验
}
```

- [ ] **Step 3: XML**(`uq_url` 去重靠唯一键;upsert 走"先 selectAnyByUrl 再 insert/adopt";列表 JOIN batch)

```xml
<mapper namespace="com.armada.group.mapper.GroupLinkMapper">
  <select id="selectAnyByUrl" resultType="com.armada.group.model.entity.GroupLink">
    SELECT * FROM group_link WHERE link_url = #{url} LIMIT 1   <!-- 含软删,供 upsert 复活 -->
  </select>
  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO group_link (link_url, group_name, label_id, import_batch_id, created_by)
    VALUES (#{linkUrl}, #{groupName}, #{labelId}, #{importBatchId}, #{createdBy})
  </insert>
  <update id="adoptToLabel">  <!-- 复活 + 收编到本分组 + 更新来源批次/群名(群名空不覆盖) -->
    UPDATE group_link SET deleted_at = NULL, label_id = #{labelId}, import_batch_id = #{batchId},
      group_name = COALESCE(#{groupName}, group_name) WHERE id = #{id}
  </update>
  <sql id="byLabelFilter">
    deleted_at IS NULL AND label_id = #{labelId}
    <if test="keyword != null and keyword != ''">
      AND (link_url LIKE CONCAT('%', #{keyword}, '%') OR group_name LIKE CONCAT('%', #{keyword}, '%'))
    </if>
  </sql>
  <select id="countByLabel" resultType="long">SELECT COUNT(*) FROM group_link WHERE <include refid="byLabelFilter"/></select>
  <select id="selectPageByLabel" resultType="com.armada.group.model.vo.GroupLinkVoRow">
    SELECT g.id, g.link_url AS url, g.group_name AS groupName, b.source_file_name AS sourceFileName, g.created_at AS createdAt
    FROM group_link g LEFT JOIN group_link_import_batch b ON g.import_batch_id = b.id
    WHERE <include refid="byLabelFilter"/>
    ORDER BY g.created_at DESC LIMIT #{offset}, #{pageSize}
  </select>
  <update id="migrateToLabel">
    UPDATE group_link SET label_id = #{labelId} WHERE deleted_at IS NULL AND id IN
    <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
  </update>
  <update id="softDeleteByIds">
    UPDATE group_link SET deleted_at = NOW() WHERE deleted_at IS NULL AND id IN
    <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
  </update>
  <update id="softDeleteByLabelIds">
    UPDATE group_link SET deleted_at = NOW() WHERE deleted_at IS NULL AND label_id IN
    <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
  </update>
  <select id="countActiveByIds" resultType="int">
    SELECT COUNT(*) FROM group_link WHERE deleted_at IS NULL AND id IN
    <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
  </select>
</mapper>
```

- [ ] **Step 4: DbTest**

```java
// uniqueUrlRejectsSecondActiveInsert: 同 url 插两条 → 第二条 DuplicateKey
// upsertRevivesSoftDeleted: 插→软删→selectAnyByUrl 命中→adoptToLabel→活查命中且 label 改了
// selectPageByLabel_joinsSourceFileName: label 下 2 链接 JOIN batch 取文件名
// softDeleteByLabelIds: 删 label 连带链接软删
```

- [ ] **Step 5: 红→XML→绿;Commit**  `git commit -m "feat(group): group_link entity+mapper+xml (upsert-by-url) with DbTest"`

### Task 3.2: import batch / detail 实体 + Mapper + XML

**Files:** Create: `group/model/entity/{GroupLinkImportBatch.java,GroupLinkImportDetail.java}`、`group/mapper/{GroupLinkImportBatchMapper.java,GroupLinkImportDetailMapper.java}`、`resources/mapper/group/{GroupLinkImportBatchMapper.xml,GroupLinkImportDetailMapper.xml}`;Test: `group/mapper/GroupLinkImportDetailMapperDbTest.java`

- [ ] **Step 1: 实体**(batch:含 total/inserted/adopted/skipped/failed 计数;detail:batchId,lineNo,rawUrl,groupName,result(int),failReason,groupLinkId,createdAt)

- [ ] **Step 2: Mapper 接口**

```java
@Mapper public interface GroupLinkImportBatchMapper {
    int insert(GroupLinkImportBatch row);
    int updateCounts(GroupLinkImportBatch row);   // 回写 total/inserted/adopted/skipped/failed
    int softDeleteByLabelIds(@Param("ids") List<Long> labelIds);
}
@Mapper public interface GroupLinkImportDetailMapper {
    int batchInsert(@Param("rows") List<GroupLinkImportDetail> rows);
    long countByQuery(GroupLinkImportDetailQuery query);            // JOIN batch
    List<GroupLinkImportDetailVoRow> selectPage(GroupLinkImportDetailQuery query);
    List<GroupLinkImportDetailVoRow> selectFailed(@Param("labelId") Long labelId, @Param("batchId") Long batchId);
}
```

- [ ] **Step 3: XML**(detail 列表/失败导出按 `batch_id` 或 JOIN batch 的 `label_id` 筛 + result 筛;批量 insert)

```xml
<!-- GroupLinkImportDetailMapper.xml 关键片段 -->
<insert id="batchInsert">
  INSERT INTO group_link_import_detail (batch_id, line_no, raw_url, group_name, result, fail_reason, group_link_id)
  VALUES <foreach collection="rows" item="r" separator=",">
    (#{r.batchId}, #{r.lineNo}, #{r.rawUrl}, #{r.groupName}, #{r.result}, #{r.failReason}, #{r.groupLinkId})
  </foreach>
</insert>
<sql id="detailFilter">
  d.tenant_id = b.tenant_id AND b.deleted_at IS NULL
  <if test="batchId != null">AND d.batch_id = #{batchId}</if>
  <if test="labelId != null">AND b.label_id = #{labelId}</if>
  <if test="result != null">AND d.result = #{result}</if>
</sql>
<select id="countByQuery" resultType="long">
  SELECT COUNT(*) FROM group_link_import_detail d JOIN group_link_import_batch b ON d.batch_id = b.id WHERE <include refid="detailFilter"/>
</select>
<select id="selectPage" resultType="com.armada.group.model.vo.GroupLinkImportDetailVoRow">
  SELECT d.line_no AS lineNo, d.group_name AS groupName, d.raw_url AS rawUrl, b.source_file_name AS sourceFileName,
         d.result AS result, d.fail_reason AS failReason, d.created_at AS createdAt
  FROM group_link_import_detail d JOIN group_link_import_batch b ON d.batch_id = b.id
  WHERE <include refid="detailFilter"/> ORDER BY d.batch_id DESC, d.line_no ASC LIMIT #{offset}, #{pageSize}
</select>
<select id="selectFailed" resultType="com.armada.group.model.vo.GroupLinkImportDetailVoRow">
  SELECT d.line_no AS lineNo, d.group_name AS groupName, d.raw_url AS rawUrl, b.source_file_name AS sourceFileName,
         d.result AS result, d.fail_reason AS failReason, d.created_at AS createdAt
  FROM group_link_import_detail d JOIN group_link_import_batch b ON d.batch_id = b.id
  WHERE b.deleted_at IS NULL AND d.result &gt;= 3
    <if test="batchId != null">AND d.batch_id = #{batchId}</if>
    <if test="labelId != null">AND b.label_id = #{labelId}</if>
  ORDER BY d.batch_id DESC, d.line_no ASC
</select>
```
> detail JOIN batch 时 `d.tenant_id = b.tenant_id` 显式写;两表均有 tenant_id 拦截器注入,JOIN 内对各表自动加条件(复杂 SQL 已验)。`result &gt;= 3` = DUPLICATE/FORMAT_ERROR。

- [ ] **Step 4: DbTest**(batchInsert + selectPage JOIN + selectFailed 只出 result≥3)

- [ ] **Step 5: 红→绿;Commit**

### Task 3.3: FileLinesExtractor(TXT/CSV/Excel → List<String>)

**Files:** Create: `group/service/FileLinesExtractor.java`;Test: `group/service/FileLinesExtractorTest.java`

- [ ] **Step 1: 测试**(TXT 字节、CSV 取首列、Excel 用 FastExcel 读首列;+ text 合并)

```java
class FileLinesExtractorTest {
    private final FileLinesExtractor ex = new FileLinesExtractor();
    @Test void txtSplitsLines() throws Exception {
        var f = new MockMultipartFile("f","a.txt","text/plain","l1\nl2\n".getBytes(UTF_8));
        assertEquals(List.of("l1","l2"), ex.extract(f, null));
    }
    @Test void mergesTextAndFile() throws Exception {
        var f = new MockMultipartFile("f","a.txt","text/plain","l1".getBytes(UTF_8));
        assertEquals(List.of("t1","l1"), ex.extract(f, "t1"));
    }
    @Test void bothNull_returnsEmpty() throws Exception { assertTrue(ex.extract(null, null).isEmpty()); }
    @Test void csvTakesFirstColumn() throws Exception {
        var f = new MockMultipartFile("f","a.csv","text/csv","url1,name1\nurl2,name2".getBytes(UTF_8));
        assertEquals(List.of("url1","url2"), ex.extract(f, null));
    }
    // excelReadsFirstColumn: 用 FastExcel 写一个临时 xlsx 再读(或 resources 放样例)
}
```

- [ ] **Step 2: 红 → 实现**(按扩展名分派;Excel/CSV 走 FastExcel 读首列;TXT 纯文本)

```java
package com.armada.group.service;
import cn.idev.excel.FastExcel;
import cn.idev.excel.read.listener.ReadListener;
import cn.idev.excel.context.AnalysisContext;
import java.io.IOException; import java.nio.charset.StandardCharsets;
import java.util.ArrayList; import java.util.List; import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import com.armada.shared.exception.BusinessException; import com.armada.shared.exception.ErrorCode;

/** 把手填 text 与上传文件(TXT/CSV/Excel)统一抽成行列表;CSV/Excel 取首列。 */
@Component
public class FileLinesExtractor {
    public List<String> extract(MultipartFile file, String text) {
        List<String> lines = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            for (String l : text.split("\\R", -1)) { if (!l.isBlank()) lines.add(l.trim()); }
        }
        if (file != null && !file.isEmpty()) { lines.addAll(parseFile(file)); }
        return lines;
    }
    private List<String> parseFile(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".txt")) {
                List<String> out = new ArrayList<>();
                for (String l : new String(file.getBytes(), StandardCharsets.UTF_8).split("\\R", -1))
                    if (!l.isBlank()) out.add(l.trim());
                return out;
            }
            if (name.endsWith(".csv") || name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return readFirstColumn(file);
            }
            throw new BusinessException(ErrorCode.VALIDATION, "仅支持 TXT/CSV/Excel 文件");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "文件读取失败");
        }
    }
    private List<String> readFirstColumn(MultipartFile file) throws IOException {
        List<String> out = new ArrayList<>();
        FastExcel.read(file.getInputStream(), new ReadListener<Map<Integer, String>>() {
            @Override public void invoke(Map<Integer, String> row, AnalysisContext ctx) {
                String first = row.get(0);
                if (first != null && !first.isBlank()) out.add(first.trim());
            }
            @Override public void doAfterAllAnalysed(AnalysisContext ctx) { }
        }).sheet().doRead();
        return out;
    }
}
```

- [ ] **Step 3: 绿;Commit**  `git commit -m "feat(group): FileLinesExtractor for TXT/CSV/Excel via FastExcel"`

### Task 3.4: 链接归一化工具

**Files:** Create: `group/service/GroupLinkUrls.java`;Test: `group/service/GroupLinkUrlsTest.java`

- [ ] **Step 1: 测试**(去 scheme/末尾斜杠/host 小写,邀请码保留大小写;非法链接抛 ImportLineException)

```java
class GroupLinkUrlsTest {
    @Test void normalize() {
        assertEquals("chat.whatsapp.com/AbC123", GroupLinkUrls.normalize("https://Chat.WhatsApp.com/AbC123/"));
    }
    @Test void rejectsNonInvite() {
        assertThrows(ImportLineException.class, () -> GroupLinkUrls.normalize("hello world"));
    }
}
```

- [ ] **Step 2: 红→实现**(正则校验 `chat.whatsapp.com/<code>`;返回归一串)

```java
package com.armada.group.service;
import java.util.Locale; import java.util.regex.Pattern;
import com.armada.shared.util.ImportLineException;
/** 群邀请链接归一化 + 格式校验。 */
public final class GroupLinkUrls {
    private GroupLinkUrls() {}
    private static final Pattern INVITE = Pattern.compile("(?:https?://)?(chat\\.whatsapp\\.com)/([A-Za-z0-9]+)/?",
            Pattern.CASE_INSENSITIVE);
    /** 归一化为 host(小写)/邀请码(原样);不合法抛 {@link ImportLineException}。 */
    public static String normalize(String raw) {
        var m = INVITE.matcher(raw.trim());
        if (!m.matches()) throw new ImportLineException("格式错误");
        return m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2);
    }
}
```

- [ ] **Step 3: 绿;Commit**

### Task 3.5: GroupLinkImportService.importLinks(同步 upsert + batch/detail 回写)

**Files:** Create: `group/service/GroupLinkImportService.java`、`group/service/impl/GroupLinkImportServiceImpl.java`、`group/model/dto/GroupLinkImportDTO.java`、`group/model/vo/GroupLinkImportResultVO.java`;Test: `group/service/impl/GroupLinkImportServiceImplTest.java`(mock mappers + FileLinesExtractor)

- [ ] **Step 1: DTO/VO**

```java
// GroupLinkImportDTO:multipart 控制器组装(file 单独传,这里持解析后用的字段)
public record GroupLinkImportDTO(Long labelId, String batchName, String text,
    java.util.List<String> lines) {}   // lines = FileLinesExtractor 结果(controller 填)
public record GroupLinkImportResultVO(Long batchId, int total, int inserted, int adopted,
    int duplicated, int failed, java.util.List<String> errors) {}
```

- [ ] **Step 2: Service 测试**(关键四态)

```java
// import_newUrl_inserts_andDetailSuccess
// import_existingUrl_adopts_changesLabel_andDetailAdopted
// import_batchDuplicate_skipped_andDetailDuplicate
// import_badFormat_failed_andDetailFormatError
// counts 回写 batch;validateLabelExists;text/file 都空 → VALIDATION
```

- [ ] **Step 3: 红→实现**(复用 LineImporter;persist 返回 GroupLinkImportResult 的 SUCCESS/ADOPTED)

```java
@Service
public class GroupLinkImportServiceImpl implements GroupLinkImportService {
    // 注入:GroupLinkLabelMapper, GroupLinkMapper, GroupLinkImportBatchMapper, GroupLinkImportDetailMapper
    @Override @Transactional(rollbackFor = Exception.class)
    public GroupLinkImportResultVO importLinks(GroupLinkImportDTO dto) {
        if (dto.labelId() == null || labelMapper.selectById(dto.labelId()) == null)
            throw new BusinessException(ErrorCode.VALIDATION, "目标分组不存在");
        String joined = String.join("\n", dto.lines() == null ? List.of() : dto.lines());
        if (!StringUtils.hasText(joined)) throw new BusinessException(ErrorCode.VALIDATION, "群链接内容与上传文件不可为空");
        GroupLinkImportBatch batch = new GroupLinkImportBatch();
        batch.setLabelId(dto.labelId()); batch.setBatchName(dto.batchName());
        importBatchMapper.insert(batch);   // 取 batch.id

        var outcomes = LineImporter.run(joined,
            GroupLinkUrls::normalize,                  // parser:归一化(失败抛 ImportLineException)
            url -> url,                                // dedupKey:归一化 url
            url -> persist(dto.labelId(), batch.getId(), url));  // 返回 GroupLinkImportResult(SUCCESS/ADOPTED)
        List<GroupLinkImportDetail> details = new ArrayList<>();
        int inserted = 0, adopted = 0, duplicated = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (var o : outcomes) {
            GroupLinkImportDetail d = new GroupLinkImportDetail();
            d.setBatchId(batch.getId()); d.setLineNo(o.lineNo()); d.setRawUrl(o.raw());
            if (o.kind() == LineImporter.Kind.FAILED) {
                d.setResult(GroupLinkImportResult.FORMAT_ERROR.code()); d.setFailReason(o.reason());
                failed++; errors.add("第 " + o.lineNo() + " 行：" + o.reason());
            } else if (o.kind() == LineImporter.Kind.DUPLICATE) {
                d.setResult(GroupLinkImportResult.DUPLICATE.code()); d.setFailReason("重复"); duplicated++;
            } else {
                Persisted p = o.persistResult();
                d.setResult(p.result().code()); d.setGroupLinkId(p.linkId());
                if (p.result() == GroupLinkImportResult.SUCCESS) inserted++; else adopted++;
            }
            details.add(d);
        }
        if (!details.isEmpty()) detailMapper.batchInsert(details);
        batch.setTotalRows(outcomes.size()); batch.setInsertedRows(inserted); batch.setAdoptedRows(adopted);
        batch.setSkippedRows(duplicated); batch.setFailedRows(failed);
        importBatchMapper.updateCounts(batch);
        log.info("群链接导入 labelId={} total={} inserted={} adopted={} dup={} failed={}",
            dto.labelId(), outcomes.size(), inserted, adopted, duplicated, failed);
        return new GroupLinkImportResultVO(batch.getId(), outcomes.size(), inserted, adopted, duplicated, failed, errors);
    }

    /** upsert:新 url→插(SUCCESS);已存在/软删→复活+收编到本分组(ADOPTED)。 */
    private Persisted persist(Long labelId, Long batchId, String url) {
        GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
        if (existing == null) {
            GroupLink row = new GroupLink();
            row.setLinkUrl(url); row.setLabelId(labelId); row.setImportBatchId(batchId);
            groupLinkMapper.insert(row);
            return new Persisted(GroupLinkImportResult.SUCCESS, row.getId());
        }
        groupLinkMapper.adoptToLabel(existing.getId(), labelId, batchId, null);
        return new Persisted(GroupLinkImportResult.ADOPTED, existing.getId());
    }
    private record Persisted(GroupLinkImportResult result, Long linkId) {}
}
```

- [ ] **Step 4: 绿(service 单测)+ 真库集成测**(`GroupLinkImportServiceDbTest`:跑一次真导入,验 group_link/batch/detail 三表落库 + 计数)

- [ ] **Step 5: Commit**  `git commit -m "feat(group): synchronous link import with upsert-adopt + batch/detail"`

### Task 3.6: 导入 / 列表 / 迁移 / 删除 端点 + GroupLinkService

**Files:** Create: `group/service/GroupLinkService.java`(+impl)、`group/model/dto/{GroupLinkQuery.java,GroupLinkMigrateDTO.java}`、`group/model/vo/{GroupLinkVO.java,GroupLinkVoRow.java}`、`group/controller/GroupLinkController.java`;扩 `GroupConverter`

- [ ] **Step 1: GroupLinkService(listByLabel/migrate/batchDelete)+ 测试**

```java
// migrate: countActiveByIds != ids.size → VALIDATION;目标分组存在校验;migrateToLabel
// listByLabel: countByLabel + selectPageByLabel → PageResult
```

- [ ] **Step 2: Controller(B1 import multipart + B2 list + B3 migrate + B4 delete)**

```java
@RestController @RequestMapping("/api/group-links")
public class GroupLinkController {
    private final GroupLinkService linkService; private final GroupLinkImportService importService;
    private final FileLinesExtractor extractor;
    @PostMapping("/import")
    public ApiResponse<GroupLinkImportResultVO> importLinks(
            @RequestParam("label_id") Long labelId, @RequestParam("batch_name") String batchName,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        List<String> lines = extractor.extract(file, text);
        return ApiResponse.ok(importService.importLinks(new GroupLinkImportDTO(labelId, batchName, text, lines)));
    }
    @GetMapping public ApiResponse<PageResult<GroupLinkVO>> list(@ModelAttribute GroupLinkQuery q) { return ApiResponse.ok(linkService.listByLabel(q)); }
    @PostMapping("/migrate") public ApiResponse<Integer> migrate(@RequestBody GroupLinkMigrateDTO d) { return ApiResponse.ok(linkService.migrate(d.linkIds(), d.targetLabelId())); }
    @PostMapping("/batch-delete") public ApiResponse<Void> batchDelete(@RequestBody GroupIdsDTO r) { linkService.batchDelete(r.ids()); return ApiResponse.ok(); }
}
```

- [ ] **Step 3: 编译 + 真库集成冒烟(import→list→migrate);Commit**

---

## Phase 4 — 明细 + 导出失败

### Task 4.1: 明细列表 + 导出失败端点

**Files:** Create: `group/model/dto/GroupLinkImportDetailQuery.java`、`group/model/vo/{GroupLinkImportDetailVO.java,GroupLinkImportDetailVoRow.java}`、`group/controller/GroupLinkImportController.java`;扩 `GroupLinkImportService`(listDetails/exportFailed)+ `GroupConverter`

- [ ] **Step 1: Query/VO**(result 出参可转中文标签;时间 epoch 毫秒)+ converter 测试

- [ ] **Step 2: service listDetails/exportFailed + 测试**

```java
// listDetails: countByQuery + selectPage → PageResult
// exportFailed: selectFailed → List<String[]>(行号,群名,链接,失败原因,导入时间)
```

- [ ] **Step 3: Controller(C1 details + C2 export CSV)**

```java
@RestController @RequestMapping("/api/group-link-imports")
public class GroupLinkImportController {
    private final GroupLinkImportService service;
    @GetMapping("/details") public ApiResponse<PageResult<GroupLinkImportDetailVO>> details(@ModelAttribute GroupLinkImportDetailQuery q) { return ApiResponse.ok(service.listDetails(q)); }
    @GetMapping("/failed/export")
    public void exportFailed(@RequestParam(value="label_id",required=false) Long labelId,
            @RequestParam(value="batch_id",required=false) Long batchId, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=failed.csv");
        // 写 BOM + 表头 + service.exportFailed(...) 行(CSV 转义)
    }
}
```

- [ ] **Step 4: 真库集成验明细分页/result 筛/导出失败只出 result≥3;Commit**

---

## Phase 5 — 收尾(ArchUnit + 验收)

### Task 5.1: ArchUnit 守护(可选,若 marketing/resource 已引入则补 group 规则)
- [ ] 确认跨域不串门、controller 不直连 mapper;`mvn -q -Dtest=ArchRulesTest test` 绿。

### Task 5.2: 端到端验收 + summary
- [ ] **Step 1:** 起 app,真库走一遍:建分组 → 导入(TXT+text 混)→ 看 import 统计 → 明细分页/筛 → 导出失败 → 迁移 → 批量删(级联)。
- [ ] **Step 2:** 写 `.harness/changes/group-import-links/summary.md`(对照 `_TEMPLATE.md`:背景/改动/验证/遗留 seam)。
- [ ] **Step 3:** 重跑/适配 `wiki/gen_datamodel.py` 刷新 `数据模型.md`(生成器需先改 armada)。
- [ ] **Step 4:** `mvn -q test` 全绿;Commit `chore(group): import-links acceptance + summary`。

---

## Self-Review(已核)

- **Spec 覆盖**:§需求对照 8 项 → A1-A4/B1-B4/C1-C2 全覆盖;检测/origin/membership/history/preview/health 明确延后(Phase 不含,设计 §9 记)。
- **占位符**:无 TBD;DbTest 步骤给了用例名+断言意图(真测在执行时按名补真实 insert/断言,参照 marketing DbTest)。
- **类型一致**:`GroupLinkImportResult.code()`、`LineImporter.run/LineOutcome/Kind`、service 签名、`*VoRow`→`*VO` converter、mapper 方法名 跨 task 对齐(见「关键接口契约」)。
- **顺序/依赖**:Phase 0(枚举/迁移/依赖)→ 1(LineImporter 重构,解锁导入)→ 2(label,被 3 的级联删依赖)→ 3(group_link/import 核心)→ 4(明细/导出)→ 5(验收)。Task 2.3 依赖 GroupLinkMapper.softDeleteByLabelIds(Task 3.1 提供;若并行,先加桩)。

## 待确认(执行前,对应设计 §13 开放点)
1. `label_id` 命名保留(默认)vs 改名。
2. `group_link_history` 延后(默认)vs 本期建。
3. B4 链接级删除本期建(默认)。
