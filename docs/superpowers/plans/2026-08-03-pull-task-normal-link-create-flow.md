# 普通群链接任务创建链路 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让运营能在后端完成一条普通群链接拉群任务的创建：粘贴群链接、上传 TXT 料子、由服务端随机一对一冻结匹配计划，并提交为 `WAIT_START` 任务。

**Architecture:** `Controller → Service → Mapper` 三层，不引入 Repository。创建页的中间态用一条 `pull_task` 草稿行承载（ADR-0007），执行行与料子成员全程挂在草稿任务下。链接解析、TXT 解析、随机匹配三块做成无 Spring 依赖的纯函数，便于密集单测；外部 HTTP 预检在事务外完成，事务只包裹数据库写入。

**Tech Stack:** Java 17 · Spring Boot 3.3.5 · MyBatis-Plus（含租户行隔离拦截器）· Flyway · H2（MySQL 模式，测试）· JUnit 5 · AssertJ · Mockito（仅 `src/test`）

## Global Constraints

以下逐条摘自 `.harness/rules/` 与设计文档，**每个任务都隐含包含本节**。

- 包根固定 `com.armada`；新增代码落 `com/armada/task/`，跨业务域只调对方 **Service**，禁碰对方 controller / mapper / entity。
- 分层不可绕过：`Controller → Service → Mapper`；Controller 禁直连 Mapper；**无 Repository 层**。
- 传输对象：entity = 普通类 + getter/setter（无 Lombok）；**DTO / VO 必须是 `record`**；`Query` 必须是可变 class 且 `extends PageQuery`。
- Mapper XML 放 `armada-api/src/main/resources/mapper/task/`，namespace 指接口全限定名。
- Service 构造器注入；事务边界在 Service 层，显式标 `@Transactional(rollbackFor = Exception.class)`。
- 异常一律 `BusinessException` + `ErrorCode`；**禁止返回 null**（用 `Optional` 或空集合）；禁止空 catch。
- **禁止魔法值**：数字与字符串常量必须提为 `private static final` 或枚举。
- 全仓**没有** Bean Validation（`jakarta.validation` 零使用）；参数校验在 Service 手写并抛 `BusinessException(ErrorCode.VALIDATION, ...)`。
- 日志用 slf4j 占位符 `{}`；群链接与手机号必须脱敏；禁 `System.out.println` / `printStackTrace`。
- **审计口径偏差（已知）**：设计文档 6.6 要求写操作记录 `requestId`，但全仓目前没有 requestId 基础设施（无 MDC 过滤器、无 trace id 透传）。本切片按现有能力记录**租户、操作者、动作与结果**，不为此引入一套新的链路追踪机制；`requestId` 作为独立事项在 change 记录里登记。
- 方法 ≤100 行、类 ≤800 行、方法参数 ≤5 个、圈复杂度 ≤10、嵌套 ≤3 层。
- 类、字段、公开方法、接口方法必须有 Javadoc；枚举逐值注释业务含义。
- 缩进 4 空格；import 不用通配符。
- 测试：Mockito 仅限 `src/test`；Mapper / SQL / 租户隔离用 test scope 的 H2 MySQL 模式真跑，加载真实 Mapper XML 与生产 `MyBatisConfig` 拦截器，**禁止用 mock Mapper 冒充 SQL 验证**。
- 覆盖率 ≥80%，核心逻辑（解析、匹配、状态迁移）100%。
- 单次改动 diff 只含本任务内容；不顺手重构无关文件；不整文件重写。
- 验证命令：`cd armada-api && mvn -Dtest='<TestClass>' test`；全量 `cd armada-api && mvn test`。

**本切片的固定数值常量**（设计文档 5.1 / 5.3，禁止散落成魔法值）：

| 常量 | 值 |
|---|---|
| 单次有效链接上限 | 200 |
| 邀请页抓取并发 | 16 |
| 单次上传 TXT 文件数上限 | 50 |
| 单文件字节上限 | 2 MB（2 \* 1024 \* 1024） |
| 单文件行数上限 | 20000 |
| 号码位数区间 | 7–15 位纯数字 |

---

## 文件结构

### 新建（`armada-api/src/main/java/com/armada/task/`）

| 文件 | 职责 |
|---|---|
| `model/enums/PullTaskStandardLinkLineStatus.java` | 链接逐行六态 |
| `model/vo/PullTaskStandardLinkLineVO.java` | 链接逐行结果 |
| `model/vo/PullTaskStandardMaterialLineErrorVO.java` | TXT 非法行明细 |
| `model/vo/PullTaskStandardFileResultVO.java` | TXT 逐文件结果 |
| `model/vo/PullTaskStandardExecutionRowVO.java` | 执行行 |
| `model/vo/PullTaskStandardDraftVO.java` | 草稿视图（聚合上面四个） |
| `model/dto/PullTaskStandardCreateDTO.java` | 提交冻结入参 |
| `model/vo/PullTaskStandardCreatedVO.java` | 提交冻结出参 |
| `service/PullTaskMaterialTxtParser.java` | TXT → 号码 + 非法行（纯函数） |
| `service/PullTaskLinkMatcher.java` | 不放回随机匹配（纯函数） |
| `service/PullTaskLinkProbeService.java` | 链接六态判定 + 有界并发 |
| `service/PullTaskStandardDraftService.java` + `impl/` | 草稿增删改查编排 |
| `service/PullTaskStandardCreateService.java` + `impl/` | 提交冻结事务 |
| `config/PullTaskLinkProbeExecutorConfig.java` | 预检有界线程池 |
| `controller/PullTaskStandardController.java` | 五个端点 |

### 新建（`group` 域，为支撑三态预检）

| 文件 | 职责 |
|---|---|
| `group/service/GroupInvitePageProbe.java` | 抓取结果 + 可达性 |

### 修改

| 文件 | 改动 |
|---|---|
| `group/service/GroupInvitePageFetcher.java` | 新增 `probe` 方法 |
| `group/service/impl/HttpGroupInvitePageFetcher.java` | `fetch` 委托给 `probe` |
| `group/service/GroupLinkRegistryService.java` | 新增 `registerPullTaskTargets` |
| `group/service/impl/GroupLinkRegistryServiceImpl.java` | 实现上述方法 |
| `task/mapper/PullTaskMapper.java` + XML | 新增 3 个方法 |
| `task/mapper/PullTaskGroupExecutionMapper.java` + XML | 新增 2 个方法 |
| `task/mapper/PullTaskMaterialMemberMapper.java` + XML | 新增 1 个方法 |
| `test/.../PullTaskNormalLinkSchema.java` | 包级私有 → `public` |
| `test/.../PullTaskNormalLinkH2Support.java` | 包级私有 → `public` |

---

## 任务清单

13 个任务。Task 1、2、3、5、6、7 之间没有依赖，可并行；Task 4 依赖 3。

| # | 交付物 | 依赖 |
|---|---|---|
| 1 | `PullTaskMaterialTxtParser` —— TXT 解析（纯函数） | — |
| 2 | `PullTaskLinkMatcher` —— 不放回随机匹配（纯函数） | — |
| 3 | `group` 域邀请页端口扩展（`probe` 区分可达性） | — |
| 4 | `PullTaskLinkProbeService` —— 六态判定 + 有界并发 | 3 |
| 5 | `PullTaskMapper` 草稿三方法 + 测试基座提升 public | — |
| 6 | 执行行与料子成员的草稿编辑 Mapper 三方法 | — |
| 7 | `GroupLinkRegistryService.registerPullTaskTargets` | — |
| 8 | `PullTaskStandardDraftWriter` —— 事务写入组件 | 5,6 |
| 9 | 草稿 VO + 回读／编辑编排 | 8 |
| 10 | `plan` —— 上传校验、预检、增量匹配与追加 | 1,2,4,6,9 |
| 11 | `PullTaskStandardCreateService` —— 提交冻结 | 5,6,7,8 |
| 12 | `PullTaskStandardController` —— 五个端点 | 10,11 |
| 13 | 全量回归、change 记录与数据模型文档 | 1–12 |

各任务完整步骤在下方分节展开。

---

## Task 1: `PullTaskMaterialTxtParser` —— TXT 料子解析（纯函数）

把一个 TXT 的文本内容解析成「去重后的号码清单 + 逐行错误明细 + 统计」。无 Spring 依赖、不碰数据库、不碰文件流——文件读字节交给调用方，本类只吃字符串，这样单测不需要构造 `MultipartFile`。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskMaterialTxtParser.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskMaterialTxtParserTest.java`

**Interfaces:**
- Consumes: `com.armada.shared.util.LineImporter`（逐行骨架，保真物理行号）、`com.armada.shared.util.ImportLineException`（行级失败原因）、`com.armada.shared.exception.BusinessException` / `ErrorCode`
- Produces: 供 Task 7 使用
  - `PullTaskMaterialTxtParser#parse(String fileName, String content) -> ParseResult`
  - `record ParsedMember(int memberSeq, int sourceLineNo, String normalizedPhone, boolean adminRequired)`
  - `record LineError(int lineNo, String reason)`
  - `record ParseResult(String fileName, int totalLineCount, int invalidLineCount, int duplicateLineCount, List<ParsedMember> members, List<LineError> errors)`

### 为什么不复用 `HistoricalGroupMaterialParser`

实现前必须能回答"现有的为什么不能用"（编码规范反屎山第 1 条）。三条硬冲突：

1. 它把末尾 `A/a` 解释为**营销账号**，本合同解释为**需设群管理员**；
2. 它在 `result()` 里把营销号重排到列表最前，本合同要求保留首次出现顺序；
3. 它只回聚合统计，不回逐行错误明细（文件名 + 原始行号 + 原因）。

`FileLinesExtractor` 同样不能用：`parseTxt` 里 `if (!l.isBlank())` 在读行阶段就丢掉空行，物理行号随之丢失。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/service/PullTaskMaterialTxtParserTest.java`：

```java
package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.task.service.PullTaskMaterialTxtParser.ParseResult;
import org.junit.jupiter.api.Test;

/** 普通群链接任务 TXT 料子解析器测试。 */
class PullTaskMaterialTxtParserTest {

    private final PullTaskMaterialTxtParser parser = new PullTaskMaterialTxtParser();

    @Test
    void keepsFirstOccurrenceOrderAndPhysicalLineNumbers() {
        // 第 2 行空行必须被忽略且不占行号，第 3 行的物理行号仍是 3。
        ParseResult result = parser.parse("a.txt", "8613800138001\n\n8613800138002\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::memberSeq,
                        PullTaskMaterialTxtParser.ParsedMember::sourceLineNo,
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 1, "8613800138001"),
                        org.assertj.core.groups.Tuple.tuple(2, 3, "8613800138002"));
        assertThat(result.errors()).isEmpty();
        assertThat(result.invalidLineCount()).isZero();
        assertThat(result.duplicateLineCount()).isZero();
    }

    @Test
    void stripsAdminMarkerAndMarksAdminRequired() {
        ParseResult result = parser.parse("a.txt", "8613800138001A\n8613800138002a\n8613800138003\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone,
                        PullTaskMaterialTxtParser.ParsedMember::adminRequired)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("8613800138001", true),
                        org.assertj.core.groups.Tuple.tuple("8613800138002", true),
                        org.assertj.core.groups.Tuple.tuple("8613800138003", false));
    }

    @Test
    void promotesFirstRecordWhenAnyDuplicateCarriesAdminMarker() {
        // 首次出现是普通号，后续重复行带 A：唯一记录必须被提升为需设管理员。
        ParseResult result = parser.parse("a.txt", "8613800138001\n8613800138001A\n");

        assertThat(result.members()).hasSize(1);
        assertThat(result.members().get(0).sourceLineNo()).isEqualTo(1);
        assertThat(result.members().get(0).adminRequired()).isTrue();
        assertThat(result.duplicateLineCount()).isEqualTo(1);
    }

    @Test
    void removesDisplayCharactersBeforeValidating() {
        ParseResult result = parser.parse("a.txt", "+86 138-0013-8001\n(86)13800138002\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone)
                .containsExactly("8613800138001", "8613800138002");
    }

    @Test
    void acceptsSevenAndFifteenDigitsAndRejectsOutsideRange() {
        ParseResult result = parser.parse("a.txt", "1234567\n123456789012345\n123456\n1234567890123456\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone)
                .containsExactly("1234567", "123456789012345");
        assertThat(result.errors()).extracting(PullTaskMaterialTxtParser.LineError::lineNo)
                .containsExactly(3, 4);
        assertThat(result.invalidLineCount()).isEqualTo(2);
    }

    @Test
    void rejectsFullUserJid() {
        ParseResult result = parser.parse("a.txt", "8613800138001@s.whatsapp.net\n");

        assertThat(result.members()).isEmpty();
        assertThat(result.errors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.lineNo()).isEqualTo(1);
                    assertThat(error.reason()).contains("手机号");
                });
    }

    @Test
    void reportsTotalPhysicalLineCountIgnoringTrailingNewline() {
        assertThat(parser.parse("a.txt", "8613800138001\n8613800138002\n").totalLineCount()).isEqualTo(2);
        assertThat(parser.parse("a.txt", "8613800138001\n8613800138002").totalLineCount()).isEqualTo(2);
        assertThat(parser.parse("a.txt", "").totalLineCount()).isZero();
    }

    @Test
    void rejectsFileExceedingMaxLineCount() {
        String content = "8613800138001\n".repeat(20001);

        assertThatThrownBy(() -> parser.parse("big.txt", content))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("20000");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskMaterialTxtParserTest' test
```

预期：编译失败，`PullTaskMaterialTxtParser` 不存在。

- [ ] **Step 3: 写实现**

创建 `armada-api/src/main/java/com/armada/task/service/PullTaskMaterialTxtParser.java`：

```java
package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.util.ImportLineException;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 普通群链接任务的 TXT 料子解析器。
 *
 * <p>与 {@code HistoricalGroupMaterialParser} 的合同不同，故独立实现：本类把末尾
 * {@code A/a} 解释为"需设群管理员"而非营销账号，保留首次出现顺序而不重排，
 * 并返回逐行错误明细而不只是聚合统计。</p>
 *
 * <p>本类只吃字符串、不读文件流、不碰数据库，物理行号由 {@link LineImporter} 保真。</p>
 */
@Component
public class PullTaskMaterialTxtParser {

    /** 归一化号码允许的最短位数。 */
    private static final int PHONE_MIN_LENGTH = 7;

    /** 归一化号码允许的最长位数。 */
    private static final int PHONE_MAX_LENGTH = 15;

    /** 单个 TXT 允许的最大物理行数。 */
    public static final int MAX_LINE_COUNT = 20000;

    /** 号码中允许出现并在归一化时移除的展示字符：加号、空白、圆括号、短横线。 */
    private static final Pattern DISPLAY_CHARS = Pattern.compile("[+\\s()\\-]");

    /** 行分隔符，与 {@link LineImporter} 保持一致。 */
    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R");

    /**
     * 解析单个 TXT 的文本内容。
     *
     * @param fileName TXT 原始文件名，用于错误定位
     * @param content  TXT 全文；null 或空串返回空结果
     * @return 去重后的号码清单、逐行错误明细与统计
     * @throws BusinessException 物理行数超过 {@link #MAX_LINE_COUNT} 时
     */
    public ParseResult parse(String fileName, String content) {
        int totalLineCount = countPhysicalLines(content);
        if (totalLineCount > MAX_LINE_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "文件 " + fileName + " 行数超过 " + MAX_LINE_COUNT + " 行，请拆分后重新上传");
        }

        Map<String, MutableMember> unique = new LinkedHashMap<>();
        List<LineError> errors = new ArrayList<>();
        int duplicateLineCount = 0;

        List<LineOutcome<ParsedLine, Void>> outcomes = LineImporter.run(
                content, PullTaskMaterialTxtParser::parseLine, ParsedLine::phone, record -> null);
        for (LineOutcome<ParsedLine, Void> outcome : outcomes) {
            if (outcome.kind() == Kind.FAILED) {
                errors.add(new LineError(outcome.lineNo(), outcome.reason()));
                continue;
            }
            if (outcome.kind() == Kind.DUPLICATE) {
                duplicateLineCount++;
                // 同号任一重复行带 A/a 时，唯一记录整体提升为需设管理员。
                if (outcome.record().adminRequired()) {
                    unique.get(outcome.record().phone()).promoteToAdmin();
                }
                continue;
            }
            unique.put(outcome.record().phone(),
                    new MutableMember(outcome.lineNo(), outcome.record()));
        }

        return new ParseResult(fileName, totalLineCount, errors.size(), duplicateLineCount,
                toMembers(unique), List.copyOf(errors));
    }

    /**
     * 统计物理行数；末尾换行不额外计一行。
     *
     * @param content TXT 全文
     * @return 物理行数
     */
    private static int countPhysicalLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String[] lines = LINE_SEPARATOR.split(content, -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) {
            count--;
        }
        return count;
    }

    /**
     * 按固定顺序清洗一行：剥离管理员标识 → 拒绝 JID → 移除展示字符 → 校验位数。
     *
     * @param line 已 trim 且非空的行原文
     * @return 清洗后的号码与管理员标识
     * @throws ImportLineException 行不合格时，消息即前端展示的失败原因
     */
    private static ParsedLine parseLine(String line) {
        boolean adminRequired = endsWithAdminMarker(line);
        String phoneToken = adminRequired ? line.substring(0, line.length() - 1).trim() : line;
        if (phoneToken.indexOf('@') >= 0) {
            throw new ImportLineException("不支持完整用户 JID，请只填手机号");
        }
        String phone = DISPLAY_CHARS.matcher(phoneToken).replaceAll("");
        if (phone.isEmpty() || !phone.chars().allMatch(Character::isDigit)) {
            throw new ImportLineException("号码含非法字符");
        }
        if (phone.length() < PHONE_MIN_LENGTH || phone.length() > PHONE_MAX_LENGTH) {
            throw new ImportLineException(
                    "号码必须是 " + PHONE_MIN_LENGTH + "-" + PHONE_MAX_LENGTH + " 位纯数字并包含国家码");
        }
        return new ParsedLine(phone, adminRequired);
    }

    /**
     * 判断行尾是否是管理员标识。
     *
     * @param line 行原文
     * @return 末尾为 {@code A} 或 {@code a} 时为真
     */
    private static boolean endsWithAdminMarker(String line) {
        char last = line.charAt(line.length() - 1);
        return last == 'A' || last == 'a';
    }

    /**
     * 按插入顺序编号并冻结为不可变清单。
     *
     * @param unique 首次出现顺序的唯一号码
     * @return 带连续 memberSeq 的成员清单
     */
    private static List<ParsedMember> toMembers(Map<String, MutableMember> unique) {
        List<ParsedMember> members = new ArrayList<>(unique.size());
        int seq = 1;
        for (MutableMember value : unique.values()) {
            members.add(value.toParsedMember(seq++));
        }
        return List.copyOf(members);
    }

    /** 清洗后的单行结果。 */
    private record ParsedLine(String phone, boolean adminRequired) {
    }

    /** 去重后的唯一号码，管理员标识可被后续重复行提升。 */
    private static final class MutableMember {

        private final int sourceLineNo;
        private final String phone;
        private boolean adminRequired;

        private MutableMember(int sourceLineNo, ParsedLine parsed) {
            this.sourceLineNo = sourceLineNo;
            this.phone = parsed.phone();
            this.adminRequired = parsed.adminRequired();
        }

        private void promoteToAdmin() {
            this.adminRequired = true;
        }

        private ParsedMember toParsedMember(int memberSeq) {
            return new ParsedMember(memberSeq, sourceLineNo, phone, adminRequired);
        }
    }

    /**
     * 去重后的料子成员。
     *
     * @param memberSeq       文件内去重后稳定顺序，从 1 起
     * @param sourceLineNo    首次有效出现的原始物理行号
     * @param normalizedPhone 归一化后的纯数字号码
     * @param adminRequired   是否需要在入群后设为群管理员
     */
    public record ParsedMember(int memberSeq, int sourceLineNo, String normalizedPhone,
                               boolean adminRequired) {
    }

    /**
     * 单行失败明细。
     *
     * @param lineNo 原始物理行号
     * @param reason 失败原因，直接展示给运营
     */
    public record LineError(int lineNo, String reason) {
    }

    /**
     * 单个 TXT 的解析结果。
     *
     * @param fileName           原始文件名
     * @param totalLineCount     物理行数
     * @param invalidLineCount   非法行数
     * @param duplicateLineCount 文件内重复号码行数
     * @param members            去重后的号码清单，保留首次出现顺序
     * @param errors             逐行失败明细
     */
    public record ParseResult(String fileName, int totalLineCount, int invalidLineCount,
                              int duplicateLineCount, List<ParsedMember> members,
                              List<LineError> errors) {

        /** 是否有至少一个有效号码；零有效号码的文件不得进入匹配池。 */
        public boolean hasValidMember() {
            return !members.isEmpty();
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskMaterialTxtParserTest' test
```

预期：8 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/service/PullTaskMaterialTxtParser.java \
        armada-api/src/test/java/com/armada/task/service/PullTaskMaterialTxtParserTest.java
git commit -m "feat: 新增普通群链接任务 TXT 料子解析器

按新合同独立实现:末尾 A/a 表示需设群管理员、保留首次出现顺序、
返回逐行错误明细。复用 LineImporter 保真物理行号,不走
FileLinesExtractor(它在读行阶段就丢掉空行)。"
```

---

## Task 2: `PullTaskLinkMatcher` —— 不放回随机匹配（纯函数）

在**剩余链接池**与**本次有效 TXT**之间做不放回一对一随机匹配。增量语义由调用方保证——调用方只传"尚未成行"的链接，因此本类天然不会扰动已有执行行。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskLinkMatcher.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskLinkMatcherTest.java`

**Interfaces:**
- Consumes: 无（只依赖 JDK）
- Produces: 供 Task 7 使用
  - `PullTaskLinkMatcher.match(List<String> remainingLinks, List<String> incomingFileKeys, int nextSeq, Random random) -> MatchResult`
  - `record Pairing(int seq, String normalizedLink, String fileKey)`
  - `record MatchResult(List<Pairing> pairings, List<String> unmatchedLinks, List<String> unmatchedFileKeys)`

### 关键语义

- 新增行数 = `min(剩余链接数, 本次有效 TXT 数)`。
- 随机只作用在**链接侧**：打乱链接后取前 k 个，与保持上传顺序的前 k 个文件配对。这已经构成一个均匀随机的一对一映射，不需要两侧都打乱。
- 多出的文件是**尾部**那些，进 `unmatchedFileKeys`，由调用方回 `ignoredFileCount`。
- `seq` 从调用方给的 `nextSeq` 开始连续递增。
- `Random` 由调用方注入，生产用 `ThreadLocalRandom.current()`，测试用固定种子。这不是安全边界，配对由服务端生成本身已满足 ADR-0005。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/service/PullTaskLinkMatcherTest.java`：

```java
package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.service.PullTaskLinkMatcher.MatchResult;
import com.armada.task.service.PullTaskLinkMatcher.Pairing;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 群链接与 TXT 不放回随机匹配测试。 */
class PullTaskLinkMatcherTest {

    private static final List<String> FOUR_LINKS = List.of(
            "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA",
            "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB",
            "chat.whatsapp.com/CCCCCCCCCCCCCCCCCCCCCC",
            "chat.whatsapp.com/DDDDDDDDDDDDDDDDDDDDDD");

    @Test
    void pairsMinimumOfBothSidesAndLeavesTheRestUnmatched() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt"), 1, new Random(42L));

        assertThat(result.pairings()).hasSize(2);
        assertThat(result.unmatchedLinks()).hasSize(2);
        assertThat(result.unmatchedFileKeys()).isEmpty();
    }

    @Test
    void ignoresTrailingFilesWhenLinksRunOut() {
        MatchResult result = PullTaskLinkMatcher.match(
                List.of(FOUR_LINKS.get(0)), List.of("a.txt", "b.txt", "c.txt"), 1, new Random(42L));

        assertThat(result.pairings()).hasSize(1);
        assertThat(result.pairings().get(0).fileKey()).isEqualTo("a.txt");
        // 被忽略的是尾部文件，顺序稳定，前端据此提示重发。
        assertThat(result.unmatchedFileKeys()).containsExactly("b.txt", "c.txt");
        assertThat(result.unmatchedLinks()).isEmpty();
    }

    @Test
    void assignsContinuousSeqStartingFromNextSeq() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt", "c.txt"), 8, new Random(42L));

        assertThat(result.pairings()).extracting(Pairing::seq).containsExactly(8, 9, 10);
    }

    @Test
    void neverReusesALinkOrAFile() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt", "c.txt", "d.txt"), 1, new Random(7L));

        assertThat(result.pairings()).extracting(Pairing::normalizedLink)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(FOUR_LINKS);
        assertThat(result.pairings()).extracting(Pairing::fileKey)
                .containsExactlyInAnyOrder("a.txt", "b.txt", "c.txt", "d.txt");
    }

    @Test
    void unmatchedLinksAreExactlyThoseNotPaired() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt"), 1, new Random(7L));

        String paired = result.pairings().get(0).normalizedLink();
        assertThat(result.unmatchedLinks()).hasSize(3).doesNotContain(paired);
        assertThat(result.unmatchedLinks()).allMatch(FOUR_LINKS::contains);
    }

    @Test
    void isReproducibleForTheSameSeed() {
        MatchResult first = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt"), 1, new Random(2026L));
        MatchResult second = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt"), 1, new Random(2026L));

        assertThat(first.pairings()).isEqualTo(second.pairings());
    }

    @Test
    void returnsEmptyResultWhenEitherSideIsEmpty() {
        assertThat(PullTaskLinkMatcher.match(List.of(), List.of("a.txt"), 1, new Random(1L))
                .pairings()).isEmpty();
        assertThat(PullTaskLinkMatcher.match(FOUR_LINKS, List.of(), 1, new Random(1L))
                .pairings()).isEmpty();
        assertThat(PullTaskLinkMatcher.match(FOUR_LINKS, List.of(), 1, new Random(1L))
                .unmatchedLinks()).hasSize(4);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskLinkMatcherTest' test
```

预期：编译失败，`PullTaskLinkMatcher` 不存在。

- [ ] **Step 3: 写实现**

创建 `armada-api/src/main/java/com/armada/task/service/PullTaskLinkMatcher.java`：

```java
package com.armada.task.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 群链接与 TXT 的不放回一对一随机匹配。
 *
 * <p>纯函数、无 Spring 依赖、无状态。增量语义由调用方保证：调用方只传尚未成行的链接，
 * 因此本类不会扰动已经落库的执行行。</p>
 *
 * <p>随机只作用在链接侧——打乱链接后取前 k 个，与保持上传顺序的前 k 个文件配对，
 * 这已经是一个均匀随机的一对一映射；两侧都打乱不会提高随机性，只会让"被忽略的是哪些文件"
 * 变得不可预期。</p>
 */
public final class PullTaskLinkMatcher {

    private PullTaskLinkMatcher() {
    }

    /**
     * 在剩余链接与本次有效 TXT 之间做不放回一对一随机匹配。
     *
     * @param remainingLinks  尚未成行的归一化链接，调用方保证已去重且顺序稳定
     * @param incomingFileKeys 本次有效 TXT 的标识，按上传顺序
     * @param nextSeq         本批第一条执行行的 seq
     * @param random          随机源；生产传 {@code ThreadLocalRandom.current()}，测试传固定种子
     * @return 本批配对、未匹配链接与被忽略的尾部文件
     */
    public static MatchResult match(List<String> remainingLinks, List<String> incomingFileKeys,
                                    int nextSeq, Random random) {
        List<String> shuffledLinks = new ArrayList<>(remainingLinks);
        Collections.shuffle(shuffledLinks, random);

        int pairCount = Math.min(shuffledLinks.size(), incomingFileKeys.size());
        List<Pairing> pairings = new ArrayList<>(pairCount);
        for (int index = 0; index < pairCount; index++) {
            pairings.add(new Pairing(
                    nextSeq + index, shuffledLinks.get(index), incomingFileKeys.get(index)));
        }

        List<String> unmatchedLinks = List.copyOf(
                shuffledLinks.subList(pairCount, shuffledLinks.size()));
        List<String> unmatchedFileKeys = List.copyOf(
                incomingFileKeys.subList(pairCount, incomingFileKeys.size()));
        return new MatchResult(List.copyOf(pairings), unmatchedLinks, unmatchedFileKeys);
    }

    /**
     * 一条冻结的群链接与 TXT 配对。
     *
     * @param seq            任务内展示与执行顺序
     * @param normalizedLink 归一化群链接
     * @param fileKey        TXT 标识，由调用方决定（本切片用原始文件名 + 上传序号）
     */
    public record Pairing(int seq, String normalizedLink, String fileKey) {
    }

    /**
     * 一次匹配的完整产出。
     *
     * @param pairings          本批新增的配对
     * @param unmatchedLinks    未匹配的剩余链接
     * @param unmatchedFileKeys 因链接不足被忽略的尾部文件
     */
    public record MatchResult(List<Pairing> pairings, List<String> unmatchedLinks,
                              List<String> unmatchedFileKeys) {
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskLinkMatcherTest' test
```

预期：7 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/service/PullTaskLinkMatcher.java \
        armada-api/src/test/java/com/armada/task/service/PullTaskLinkMatcherTest.java
git commit -m "feat: 新增群链接与 TXT 不放回随机匹配器

新增行数取两侧最小值,随机只作用在链接侧,被忽略的是尾部文件。
seq 从调用方给的 nextSeq 连续递增,增量语义由调用方只传剩余链接保证。"
```

---

## Task 3: `group` 域邀请页端口扩展 —— 让"不可达"与"无群资料"可区分

`GroupInvitePageFetcher.fetch` 目前把 `IOException`、超时、中断、非 2xx **全部**吞成 `empty(normalizedUrl)`，与"页面可达但只有 WhatsApp 默认资料"返回完全相同的结果。调用方无法区分 `LINK_EXPIRED` 与 `PROBE_INCOMPLETE`，三态口径在现有端口上不可实现。

本任务在 `group` 域补上可达性信息。**不在 `task` 域另起一套 HTTP**——邀请页抓取的归属域是 `group`。

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/service/GroupInvitePageProbe.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupInvitePageFetcher.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HttpGroupInvitePageFetcher.java`
- Test: `armada-api/src/test/java/com/armada/group/service/impl/HttpGroupInvitePageFetcherProbeTest.java`

**Interfaces:**
- Consumes: 现有 `GroupInvitePageMetadata`
- Produces: 供 Task 4 使用
  - `record GroupInvitePageProbe(GroupInvitePageMetadata metadata, boolean reachable)`
  - `GroupInvitePageFetcher#probe(String normalizedUrl) -> GroupInvitePageProbe`

### 不破坏既有调用方

`fetch` **保持为接口抽象方法**（不改成 default），实现类里收敛为 `probe(normalizedUrl).metadata()`。这样：

- `GroupLinkPrecheckServiceImpl` 行为完全不变；
- `GroupLinkPrecheckServiceImplTest` 里 `when(invitePageFetcher.fetch(...))` 的 mock 桩不受影响；
- 是委托而不是并行路径，符合"改行为 = 删旧路径"。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/group/service/impl/HttpGroupInvitePageFetcherProbeTest.java`：

```java
package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupInvitePageProbe;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** 邀请页抓取可达性区分测试，不出网。 */
class HttpGroupInvitePageFetcherProbeTest {

    private static final String LINK = "chat.whatsapp.com/IIYjcDTmDtr5FPaU7yVoWJ";

    private static final String PROFILE_HTML = """
            <html><head>
            <meta property="og:title" content="真实群名" />
            </head></html>
            """;

    private static final String DEFAULT_ONLY_HTML = """
            <html><head>
            <meta property="og:title" content="WhatsApp" />
            <meta property="og:image" content="https://static.whatsapp.net/rsrc.php/v4/yR/r/y8-PTBaP90a.png" />
            </head></html>
            """;

    @Test
    void reachableWithProfileWhenPageReturnsRealSubject() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(200, PROFILE_HTML);

        GroupInvitePageProbe probe = fetcher.probe(LINK);

        assertThat(probe.reachable()).isTrue();
        assertThat(probe.metadata().hasProfile()).isTrue();
        assertThat(probe.metadata().waSubject()).isEqualTo("真实群名");
    }

    @Test
    void reachableWithoutProfileWhenPageOnlyHasWhatsappDefaults() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(200, DEFAULT_ONLY_HTML);

        GroupInvitePageProbe probe = fetcher.probe(LINK);

        // 页面能访问，只是链接已失效：这是 LINK_EXPIRED，不是检测未完成。
        assertThat(probe.reachable()).isTrue();
        assertThat(probe.metadata().hasProfile()).isFalse();
    }

    @Test
    void unreachableOnNonSuccessStatus() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(503, "");

        assertThat(fetcher.probe(LINK).reachable()).isFalse();
    }

    @Test
    void unreachableOnIoFailure() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("connect timed out"));

        assertThat(new HttpGroupInvitePageFetcher(httpClient).probe(LINK).reachable()).isFalse();
    }

    @Test
    void fetchDelegatesToProbeMetadata() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(200, PROFILE_HTML);

        assertThat(fetcher.fetch(LINK)).isEqualTo(fetcher.probe(LINK).metadata());
    }

    @SuppressWarnings("unchecked")
    private static HttpGroupInvitePageFetcher fetcherReturning(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(response);
        return new HttpGroupInvitePageFetcher(httpClient);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='HttpGroupInvitePageFetcherProbeTest' test
```

预期：编译失败，`GroupInvitePageProbe` 与 `probe` 方法不存在。

- [ ] **Step 3: 新增 `GroupInvitePageProbe`**

创建 `armada-api/src/main/java/com/armada/group/service/GroupInvitePageProbe.java`：

```java
package com.armada.group.service;

/**
 * 公开邀请页抓取结果，区分"页面不可达"与"页面可达但无群资料"。
 *
 * <p>两者对业务的含义完全不同：不可达是本系统侧的网络问题，链接可能仍然有效；
 * 可达但无群资料说明链接已被撤销或群已删除。调用方必须能分开处理，
 * 否则会把自身网络抖动当成用户链接失效。</p>
 *
 * @param metadata  页面可识别出的群资料；不可达时为空 profile
 * @param reachable 页面是否成功返回 2xx 并完成解析
 */
public record GroupInvitePageProbe(GroupInvitePageMetadata metadata, boolean reachable) {
}
```

- [ ] **Step 4: 给端口加 `probe` 方法**

修改 `armada-api/src/main/java/com/armada/group/service/GroupInvitePageFetcher.java`，在 `fetch` 之后追加：

```java
    /**
     * 抓取公开邀请页并区分可达性。
     *
     * <p>{@link #fetch(String)} 把所有失败都收敛成空 profile，无法分辨"抓不到"与"没群资料"。
     * 需要区分二者的调用方用本方法。</p>
     *
     * @param normalizedUrl {@code chat.whatsapp.com/<inviteCode>}
     * @return 群资料与可达性
     */
    GroupInvitePageProbe probe(String normalizedUrl);
```

- [ ] **Step 5: 把实现体搬进 `probe`，`fetch` 委托给它**

修改 `armada-api/src/main/java/com/armada/group/service/impl/HttpGroupInvitePageFetcher.java`：把现有 `fetch` 方法整体替换为下面两个方法（`empty` / `metadataFromHtml` 等私有方法保持不动），并补上 `import com.armada.group.service.GroupInvitePageProbe;`：

```java
    @Override
    public GroupInvitePageMetadata fetch(String normalizedUrl) {
        return probe(normalizedUrl).metadata();
    }

    @Override
    public GroupInvitePageProbe probe(String normalizedUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(inviteUri(normalizedUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", HTML_ACCEPT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < HTTP_OK || response.statusCode() >= HTTP_MULTIPLE_CHOICES) {
                log.debug("WhatsApp 邀请页返回非 2xx normalizedUrl={} status={}",
                        normalizedUrl, response.statusCode());
                return unreachable(normalizedUrl);
            }
            return new GroupInvitePageProbe(
                    metadataFromHtml(normalizedUrl, response.body()), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WhatsApp 邀请页抓取被中断 normalizedUrl={}", normalizedUrl);
            return unreachable(normalizedUrl);
        } catch (IllegalArgumentException | IOException e) {
            log.warn("WhatsApp 邀请页抓取失败 normalizedUrl={} error={}", normalizedUrl, e.getMessage());
            return unreachable(normalizedUrl);
        }
    }

    /**
     * 构造不可达结果。
     *
     * @param normalizedUrl 归一化群邀请链接
     * @return 空 profile 且 reachable 为 false
     */
    private static GroupInvitePageProbe unreachable(String normalizedUrl) {
        return new GroupInvitePageProbe(empty(normalizedUrl), false);
    }
```

同时把原来裸写的 `200` / `300` 提为常量（编码规范红线 #1 禁魔法值），加在 `AVATAR_MAX_LENGTH` 之后：

```java
    /** HTTP 成功状态码下界。 */
    private static final int HTTP_OK = 200;

    /** HTTP 成功状态码上界（不含）。 */
    private static final int HTTP_MULTIPLE_CHOICES = 300;
```

- [ ] **Step 6: 运行新测试与既有回归**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='HttpGroupInvitePageFetcherProbeTest+HttpGroupInvitePageFetcherTest+GroupLinkPrecheckServiceImplTest' test
```

预期：三个测试类全部 PASS。`GroupLinkPrecheckServiceImplTest` 必须仍然绿——它 mock 的是接口的 `fetch`，桩不受实现内部委托影响；若它变红说明委托改错了。

- [ ] **Step 7: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/group/service/GroupInvitePageProbe.java \
        armada-api/src/main/java/com/armada/group/service/GroupInvitePageFetcher.java \
        armada-api/src/main/java/com/armada/group/service/impl/HttpGroupInvitePageFetcher.java \
        armada-api/src/test/java/com/armada/group/service/impl/HttpGroupInvitePageFetcherProbeTest.java
git commit -m "feat: 邀请页抓取端口区分不可达与无群资料

fetch 把超时、非 2xx 与页面无群资料全部吞成空 profile,调用方无法
区分链接失效与检测未完成。新增 probe 返回可达性,fetch 委托给它,
既有调用方行为不变。"
```

---

## Task 4: `PullTaskLinkProbeService` —— 链接六态判定 + 有界并发

把一段粘贴文本判定成逐行六态结果，并给出进入匹配池的链接清单。**占用集合由调用方传入**，本服务不碰 Mapper——这样它只需要 mock 一个抓取端口就能测。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskStandardLinkLineStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/config/PullTaskLinkProbeExecutorConfig.java`
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskLinkProbeService.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskLinkProbeServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `GroupInvitePageFetcher#probe` / `GroupInvitePageProbe`；`com.armada.group.service.GroupLinkUrls#normalizeImportLine`；`com.armada.shared.util.LineImporter`
- Produces: 供 Task 8 使用
  - `PullTaskLinkProbeService#probe(String linksText, Set<String> occupiedLinks) -> ProbeResult`
  - `PullTaskLinkProbeService.candidateLinks(String linksText) -> Set<String>`（静态；只归一化与批内去重，供调用方先查占用）
  - `record LinkLine(int lineNo, String raw, String normalizedLink, PullTaskStandardLinkLineStatus status, String reason)`
  - `record ProbeResult(List<LinkLine> lines, List<String> poolLinks)`
  - `PullTaskLinkProbeService.MAX_VALID_LINK_COUNT = 200`

### 为什么要单独暴露 `candidateLinks`

占用查询要拿归一化后的链接去查库，而归一化必须走 `GroupLinkUrls.normalizeImportLine`（能从"1. https://chat.whatsapp.com/XXX 群名"这种运营文本里抽链接）。调用方不能改用 `tryNormalize`——那是整行严格匹配，会把带序号和说明文字的行全判成非法，导致占用漏查。所以把这一步作为静态方法暴露出来，保证全流程只有一套归一化实现。代价是同一段文本被切行两次，200 行以内纯 CPU，可忽略。

### 判定顺序（一行只落一个终态）

1. 格式不合法 → `INVALID_FORMAT`，**不抓页**
2. 批内归一化后重复 → `DUPLICATE`，保留自己的行号，**不重复抓页**
3. 已被其他任务占用 → `OCCUPIED`，**不抓页**（省掉无谓的外部请求）
4. 抓页：可达且有群资料 → `VALID`；可达无群资料 → `LINK_EXPIRED`；不可达 → `PROBE_INCOMPLETE`

`poolLinks` = 状态为 `VALID` 或 `PROBE_INCOMPLETE` 的链接，按首次出现顺序。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/service/PullTaskLinkProbeServiceTest.java`：

```java
package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageMetadata;
import com.armada.group.service.GroupInvitePageProbe;
import com.armada.shared.exception.BusinessException;
import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;
import com.armada.task.service.PullTaskLinkProbeService.LinkLine;
import com.armada.task.service.PullTaskLinkProbeService.ProbeResult;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 群链接六态判定测试；抓取端口全部 mock，不出网。 */
class PullTaskLinkProbeServiceTest {

    private static final String CODE_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String CODE_B = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final String LINK_A = "chat.whatsapp.com/" + CODE_A;
    private static final String LINK_B = "chat.whatsapp.com/" + CODE_B;

    private GroupInvitePageFetcher fetcher;
    private PullTaskLinkProbeService service;

    @BeforeEach
    void setUp() {
        fetcher = mock(GroupInvitePageFetcher.class);
        // 同步执行器让并发路径在测试里变确定；生产注入有界线程池。
        service = new PullTaskLinkProbeService(fetcher, Runnable::run);
    }

    @Test
    void marksValidWhenPageReachableWithProfile() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe("https://" + LINK_A, Set.of());

        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.lineNo()).isEqualTo(1);
            assertThat(line.normalizedLink()).isEqualTo(LINK_A);
            assertThat(line.status()).isEqualTo(PullTaskStandardLinkLineStatus.VALID);
        });
        assertThat(result.poolLinks()).containsExactly(LINK_A);
    }

    @Test
    void marksExpiredAndKeepsItOutOfThePool() {
        stubNoProfile(LINK_A);

        ProbeResult result = service.probe(LINK_A, Set.of());

        assertThat(result.lines().get(0).status())
                .isEqualTo(PullTaskStandardLinkLineStatus.LINK_EXPIRED);
        assertThat(result.poolLinks()).isEmpty();
    }

    @Test
    void marksProbeIncompleteButStillEntersThePool() {
        stubUnreachable(LINK_A);

        ProbeResult result = service.probe(LINK_A, Set.of());

        // 抓不到可能只是本系统网络抖动，启动时还会再校验一次，不能当成用户链接失效。
        assertThat(result.lines().get(0).status())
                .isEqualTo(PullTaskStandardLinkLineStatus.PROBE_INCOMPLETE);
        assertThat(result.poolLinks()).containsExactly(LINK_A);
    }

    @Test
    void reportsInvalidFormatWithOriginalLineNumberAndSkipsFetch() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe("不是链接\n" + LINK_A, Set.of());

        assertThat(result.lines()).extracting(LinkLine::lineNo, LinkLine::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, PullTaskStandardLinkLineStatus.INVALID_FORMAT),
                        org.assertj.core.groups.Tuple.tuple(
                                2, PullTaskStandardLinkLineStatus.VALID));
        assertThat(result.lines().get(0).reason()).isNotBlank();
        verify(fetcher, times(1)).probe(anyString());
    }

    @Test
    void ignoresBlankLinesWithoutConsumingLineNumbers() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe("\n\n" + LINK_A, Set.of());

        assertThat(result.lines()).singleElement()
                .satisfies(line -> assertThat(line.lineNo()).isEqualTo(3));
    }

    @Test
    void marksBatchDuplicateAndFetchesOnlyOnce() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe(LINK_A + "\nhttps://" + LINK_A + "/", Set.of());

        assertThat(result.lines()).extracting(LinkLine::lineNo, LinkLine::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, PullTaskStandardLinkLineStatus.VALID),
                        org.assertj.core.groups.Tuple.tuple(
                                2, PullTaskStandardLinkLineStatus.DUPLICATE));
        assertThat(result.poolLinks()).containsExactly(LINK_A);
        verify(fetcher, times(1)).probe(LINK_A);
    }

    @Test
    void marksOccupiedWithoutFetchingAtAll() {
        stubProfile(LINK_B);

        ProbeResult result = service.probe(LINK_A + "\n" + LINK_B, Set.of(LINK_A));

        assertThat(result.lines()).extracting(LinkLine::status)
                .containsExactly(PullTaskStandardLinkLineStatus.OCCUPIED,
                        PullTaskStandardLinkLineStatus.VALID);
        assertThat(result.poolLinks()).containsExactly(LINK_B);
        verify(fetcher, never()).probe(LINK_A);
    }

    @Test
    void rejectsMoreThanTwoHundredUniqueLinks() {
        String text = IntStream.range(0, PullTaskLinkProbeService.MAX_VALID_LINK_COUNT + 1)
                .mapToObj(index -> "chat.whatsapp.com/" + String.format("%022d", index))
                .collect(Collectors.joining("\n"));

        assertThatThrownBy(() -> service.probe(text, Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.valueOf(PullTaskLinkProbeService.MAX_VALID_LINK_COUNT));
        verify(fetcher, never()).probe(anyString());
    }

    @Test
    void returnsEmptyResultForBlankText() {
        ProbeResult result = service.probe("   ", Set.of());

        assertThat(result.lines()).isEmpty();
        assertThat(result.poolLinks()).isEmpty();
    }

    @Test
    void candidateLinksNormalizesOperationalTextAndDeduplicates() {
        // 带序号、说明文字和查询串的运营文本必须被识别；严格整行匹配会漏掉它们。
        String text = "1. https://" + LINK_A + "?x=1 群名\n" + LINK_A + "\n不是链接\n" + LINK_B;

        assertThat(PullTaskLinkProbeService.candidateLinks(text))
                .containsExactly(LINK_A, LINK_B);
    }

    @Test
    void candidateLinksIsEmptyForBlankText() {
        assertThat(PullTaskLinkProbeService.candidateLinks(null)).isEmpty();
        assertThat(PullTaskLinkProbeService.candidateLinks("  ")).isEmpty();
    }

    private void stubProfile(String normalizedLink) {
        when(fetcher.probe(normalizedLink)).thenReturn(new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(normalizedLink), "真实群名", null), true));
    }

    private void stubNoProfile(String normalizedLink) {
        when(fetcher.probe(normalizedLink)).thenReturn(new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(normalizedLink), null, null), true));
    }

    private void stubUnreachable(String normalizedLink) {
        when(fetcher.probe(normalizedLink)).thenReturn(new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(normalizedLink), null, null), false));
    }

    private static String inviteCode(String normalizedLink) {
        return normalizedLink.substring(normalizedLink.lastIndexOf('/') + 1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskLinkProbeServiceTest' test
```

预期：编译失败，枚举与服务不存在。

- [ ] **Step 3: 写六态枚举**

创建 `armada-api/src/main/java/com/armada/task/model/enums/PullTaskStandardLinkLineStatus.java`：

```java
package com.armada.task.model.enums;

/**
 * 普通群链接创建页里，粘贴文本每一行的判定结果。
 *
 * <p>一行只落一个终态。只有 {@link #VALID} 与 {@link #PROBE_INCOMPLETE} 进入随机匹配池。</p>
 */
public enum PullTaskStandardLinkLineStatus {

    /** 格式合法且公开邀请页识别出群名或真实头像，进入匹配池。 */
    VALID,

    /** 未提取到 22 位邀请码，或链接长度不足，不进入匹配池。 */
    INVALID_FORMAT,

    /** 本次粘贴内容里归一化后重复，保留首次出现的那一行，本行不进入匹配池。 */
    DUPLICATE,

    /** 公开邀请页可访问但只有 WhatsApp 默认资料，判定为链接已失效，不进入匹配池。 */
    LINK_EXPIRED,

    /** 抓取超时或网络错误，无法判定有效性；仍进入匹配池，由启动时重新校验兜底。 */
    PROBE_INCOMPLETE,

    /** 该链接已被本租户其他运行中的任务占用，不进入匹配池。 */
    OCCUPIED
}
```

- [ ] **Step 4: 写有界线程池配置**

创建 `armada-api/src/main/java/com/armada/task/config/PullTaskLinkProbeExecutorConfig.java`：

```java
package com.armada.task.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 普通群链接创建页公开邀请页预检的有界并发执行器配置。 */
@Configuration
public class PullTaskLinkProbeExecutorConfig {

    /**
     * 并发抓取线程数。
     *
     * <p>单条抓取最坏 3 秒（连接 2 秒 + 请求 3 秒超时），单次上限 200 条链接，
     * 16 并发下最坏约 38 秒。再往上对 {@code chat.whatsapp.com} 有被限流风险。</p>
     */
    private static final int POOL_SIZE = 16;

    /** 等待队列容量；单次上限 200 条，留一倍冗余应对并发请求。 */
    private static final int QUEUE_CAPACITY = 400;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建邀请页预检有界执行器。
     *
     * @return 支持优雅停机的 Spring 线程池
     */
    @Bean(name = "pullTaskLinkProbeExecutor")
    public Executor pullTaskLinkProbeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("pull-task-link-probe-");
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 5: 写判定服务**

创建 `armada-api/src/main/java/com/armada/task/service/PullTaskLinkProbeService.java`：

```java
package com.armada.task.service;

import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageProbe;
import com.armada.group.service.GroupLinkUrls;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 普通群链接创建页的粘贴文本判定服务。
 *
 * <p>只做逐行归一化、批内去重、占用比对和公开邀请页三态判定，不写库、不调协议层。
 * 占用集合由调用方查好后传入，本服务因此不依赖任何 Mapper。</p>
 *
 * <p>不复用 {@code GroupLinkPrecheckServiceImpl}：那是历史群导入弹窗的二态口径
 * （抓取异常与无群资料合并成"不可用"），改它会改变既有业务行为。</p>
 */
@Service
public class PullTaskLinkProbeService {

    /** 单次粘贴允许的最大唯一有效链接数。 */
    public static final int MAX_VALID_LINK_COUNT = 200;

    /** 公开页可访问但只有 WhatsApp 默认资料时的失败原因。 */
    private static final String EXPIRED_REASON = "链接已失效或群已删除";

    /** 抓取超时或网络错误时的提示。 */
    private static final String INCOMPLETE_REASON = "检测未完成，启动时将重新校验";

    /** 链接已被其他任务占用时的失败原因。 */
    private static final String OCCUPIED_REASON = "该链接已被其他任务占用";

    private final GroupInvitePageFetcher invitePageFetcher;
    private final Executor probeExecutor;

    /**
     * 创建链接判定服务。
     *
     * @param invitePageFetcher 公开邀请页抓取端口
     * @param probeExecutor     有界并发执行器
     */
    public PullTaskLinkProbeService(
            GroupInvitePageFetcher invitePageFetcher,
            @Qualifier("pullTaskLinkProbeExecutor") Executor probeExecutor) {
        this.invitePageFetcher = invitePageFetcher;
        this.probeExecutor = probeExecutor;
    }

    /**
     * 判定一段粘贴文本。
     *
     * @param linksText     创建页链接框的全量文本；null 或空白返回空结果
     * @param occupiedLinks 已被本租户其他运行中任务占用的归一化链接
     * @return 逐行结果与进入匹配池的链接
     * @throws BusinessException 唯一有效链接数超过 {@link #MAX_VALID_LINK_COUNT} 时
     */
    public ProbeResult probe(String linksText, Set<String> occupiedLinks) {
        List<LineOutcome<String, String>> outcomes = parseLines(linksText);

        Set<String> candidates = new LinkedHashSet<>();
        for (LineOutcome<String, String> outcome : outcomes) {
            if (outcome.kind() == Kind.PERSISTED && !occupiedLinks.contains(outcome.record())) {
                candidates.add(outcome.record());
            }
        }
        if (candidates.size() > MAX_VALID_LINK_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "单次最多粘贴 " + MAX_VALID_LINK_COUNT + " 条有效链接，请分批提交");
        }

        Map<String, PullTaskStandardLinkLineStatus> probed = probeAll(candidates);
        return buildResult(outcomes, occupiedLinks, probed);
    }

    /**
     * 只做归一化与批内去重，不抓页、不判占用。
     *
     * <p>调用方需要先拿归一化链接去查占用，再把占用集合传回 {@link #probe(String, Set)}。
     * 归一化必须统一走 {@code GroupLinkUrls.normalizeImportLine}——它能从带序号、说明文字和
     * 查询串的运营文本里抽出邀请链接；换成整行严格匹配的 {@code tryNormalize} 会漏查占用。</p>
     *
     * @param linksText 创建页链接框的全量文本
     * @return 按首次出现顺序去重的归一化链接
     */
    public static Set<String> candidateLinks(String linksText) {
        Set<String> candidates = new LinkedHashSet<>();
        for (LineOutcome<String, String> outcome : parseLines(linksText)) {
            if (outcome.kind() == Kind.PERSISTED) {
                candidates.add(outcome.record());
            }
        }
        return candidates;
    }

    /**
     * 逐行归一化并标出格式失败与批内重复。
     *
     * @param linksText 创建页链接框的全量文本
     * @return 逐行产出
     */
    private static List<LineOutcome<String, String>> parseLines(String linksText) {
        return LineImporter.run(
                linksText, GroupLinkUrls::normalizeImportLine, url -> url, url -> url);
    }

    /**
     * 并发抓取全部候选链接的公开邀请页。
     *
     * @param candidates 已去重且未被占用的归一化链接
     * @return 链接到三态判定的映射
     */
    private Map<String, PullTaskStandardLinkLineStatus> probeAll(Set<String> candidates) {
        Map<String, CompletableFuture<PullTaskStandardLinkLineStatus>> futures =
                new LinkedHashMap<>(candidates.size());
        for (String link : candidates) {
            futures.put(link, CompletableFuture.supplyAsync(
                    () -> classify(invitePageFetcher.probe(link)), probeExecutor));
        }
        Map<String, PullTaskStandardLinkLineStatus> probed = new LinkedHashMap<>(futures.size());
        futures.forEach((link, future) -> probed.put(link, future.join()));
        return probed;
    }

    /**
     * 把抓取结果映射成三态。
     *
     * @param probe 抓取结果
     * @return 判定结果
     */
    private static PullTaskStandardLinkLineStatus classify(GroupInvitePageProbe probe) {
        if (!probe.reachable()) {
            return PullTaskStandardLinkLineStatus.PROBE_INCOMPLETE;
        }
        return probe.metadata().hasProfile()
                ? PullTaskStandardLinkLineStatus.VALID
                : PullTaskStandardLinkLineStatus.LINK_EXPIRED;
    }

    /**
     * 按原始行顺序组装逐行结果，并收集进入匹配池的链接。
     *
     * @param outcomes      逐行产出
     * @param occupiedLinks 已占用链接
     * @param probed        抓取判定结果
     * @return 逐行结果与匹配池
     */
    private static ProbeResult buildResult(List<LineOutcome<String, String>> outcomes,
                                           Set<String> occupiedLinks,
                                           Map<String, PullTaskStandardLinkLineStatus> probed) {
        List<LinkLine> lines = new ArrayList<>(outcomes.size());
        List<String> poolLinks = new ArrayList<>();
        for (LineOutcome<String, String> outcome : outcomes) {
            lines.add(toLine(outcome, occupiedLinks, probed, poolLinks));
        }
        return new ProbeResult(List.copyOf(lines), List.copyOf(poolLinks));
    }

    /**
     * 判定单行终态；命中匹配池条件时顺带写入 {@code poolLinks}。
     *
     * @param outcome       单行产出
     * @param occupiedLinks 已占用链接
     * @param probed        抓取判定结果
     * @param poolLinks     匹配池收集器
     * @return 单行结果
     */
    private static LinkLine toLine(LineOutcome<String, String> outcome,
                                   Set<String> occupiedLinks,
                                   Map<String, PullTaskStandardLinkLineStatus> probed,
                                   List<String> poolLinks) {
        if (outcome.kind() == Kind.FAILED) {
            return new LinkLine(outcome.lineNo(), outcome.raw(), null,
                    PullTaskStandardLinkLineStatus.INVALID_FORMAT, outcome.reason());
        }
        String link = outcome.record();
        if (outcome.kind() == Kind.DUPLICATE) {
            return new LinkLine(outcome.lineNo(), outcome.raw(), link,
                    PullTaskStandardLinkLineStatus.DUPLICATE, null);
        }
        if (occupiedLinks.contains(link)) {
            return new LinkLine(outcome.lineNo(), outcome.raw(), link,
                    PullTaskStandardLinkLineStatus.OCCUPIED, OCCUPIED_REASON);
        }
        PullTaskStandardLinkLineStatus status = probed.get(link);
        if (status != PullTaskStandardLinkLineStatus.LINK_EXPIRED) {
            poolLinks.add(link);
        }
        return new LinkLine(outcome.lineNo(), outcome.raw(), link, status, reasonOf(status));
    }

    /**
     * 取状态对应的展示原因。
     *
     * @param status 判定状态
     * @return 原因文案；无需提示时为 null
     */
    private static String reasonOf(PullTaskStandardLinkLineStatus status) {
        if (status == PullTaskStandardLinkLineStatus.LINK_EXPIRED) {
            return EXPIRED_REASON;
        }
        return status == PullTaskStandardLinkLineStatus.PROBE_INCOMPLETE ? INCOMPLETE_REASON : null;
    }

    /**
     * 粘贴文本中的单行判定结果。
     *
     * @param lineNo         原始物理行号
     * @param raw            trim 后的行原文
     * @param normalizedLink 归一化链接；格式非法时为 null
     * @param status         终态
     * @param reason         失败或提示原因；无需提示时为 null
     */
    public record LinkLine(int lineNo, String raw, String normalizedLink,
                           PullTaskStandardLinkLineStatus status, String reason) {
    }

    /**
     * 一次判定的完整产出。
     *
     * @param lines     按原始行顺序的逐行结果
     * @param poolLinks 进入随机匹配池的链接，按首次出现顺序
     */
    public record ProbeResult(List<LinkLine> lines, List<String> poolLinks) {
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskLinkProbeServiceTest' test
```

预期：11 个测试全部 PASS。

- [ ] **Step 7: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskStandardLinkLineStatus.java \
        armada-api/src/main/java/com/armada/task/config/PullTaskLinkProbeExecutorConfig.java \
        armada-api/src/main/java/com/armada/task/service/PullTaskLinkProbeService.java \
        armada-api/src/test/java/com/armada/task/service/PullTaskLinkProbeServiceTest.java
git commit -m "feat: 新增普通群链接粘贴文本六态判定服务

判定顺序为格式、批内重复、占用、公开邀请页三态;失效不进池,
检测未完成仍进池由启动校验兜底。抓取走 16 并发有界线程池,
单次唯一有效链接上限 200。占用集合由调用方传入,服务不依赖 Mapper。"
```

---

## Task 5: `PullTaskMapper` 草稿三方法 + 测试基座提升 public

草稿任务行的建立、复用与提交冻结。顺带把 H2 测试基座从包级私有提升为 `public`，供 Task 9 / 10 的服务集成测试使用。

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTask.java`（补 2 个字段）
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java`（补 3 个方法）
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java`（→ `public`）
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java`（→ `public`）
- Test: `armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftMapperInMemoryTest.java`

**Interfaces:**
- Consumes: `com.armada.task.model.entity.PullTask`
- Produces: 供 Task 8 / 9 使用
  - `PullTaskMapper#insertDraft(PullTask row) -> int`（`useGeneratedKeys`，回填 `row.id`）
  - `PullTaskMapper#selectLatestDraftByCreator(long createdBy) -> PullTask`（无草稿时返回 `null`，由 Service 包成 `Optional`）
  - `PullTaskMapper#submitDraft(PullTask row, int expectedVersion, long now) -> int`（1 = 成功，0 = 状态或版本不符）

### 为什么 `PullTask` 要加两个字段

`created_by` 是"每用户一条草稿"的查询键，`config_json` 是 `NOT NULL` 列且提交时要写入配置快照。当前实体两者都没有——旧 `PullTaskController` 是用 `JdbcTemplate` 裸 SQL 绕过实体写的，那条路径不复用。

### 为什么不复用 `updateStatusWithVersion`

它只写 `status` / `version` / `started_at` / `finished_at`，不写任务名与计数列。拆成两条 UPDATE 会让"状态已推进但计数未写"成为可观测的中间态，且第二条没有乐观锁保护。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftMapperInMemoryTest.java`：

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.enums.PullTaskType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 普通群链接草稿任务行的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskDraftMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskDraftMapperInMemoryTest {

    private static final long CREATOR = 501L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insertDraftWritesStandardNormalLinkDraftAndFillsGeneratedId() {
        PullTask draft = draftRow();

        assertThat(mapper.insertDraft(draft)).isEqualTo(1);
        assertThat(draft.getId()).isNotNull();

        PullTask saved = mapper.selectLatestDraftByCreator(CREATOR);
        assertThat(saved.getId()).isEqualTo(draft.getId());
        assertThat(saved.getTenantId()).isEqualTo(7L);
        assertThat(saved.getTaskType()).isEqualTo(PullTaskType.STANDARD);
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        // mode 取新值 NORMAL_LINK，不复用已被 PRD 移除的 OLD_LINK。
        assertThat(saved.getMode()).isEqualTo("NORMAL_LINK");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getGroupCount()).isZero();
        assertThat(saved.getExpectedPullCount()).isZero();
    }

    @Test
    void selectLatestDraftByCreatorIsScopedToTheCreator() {
        mapper.insertDraft(draftRow());

        assertThat(mapper.selectLatestDraftByCreator(CREATOR)).isNotNull();
        assertThat(mapper.selectLatestDraftByCreator(999L)).isNull();
    }

    @Test
    void selectLatestDraftByCreatorReturnsNewestWhenDuplicatesLeakThrough() {
        PullTask first = draftRow();
        mapper.insertDraft(first);
        PullTask second = draftRow();
        mapper.insertDraft(second);

        // 同用户双击或多标签页可能漏出第二条草稿，取最新一条容忍它。
        assertThat(mapper.selectLatestDraftByCreator(CREATOR).getId()).isEqualTo(second.getId());
    }

    @Test
    void selectLatestDraftByCreatorIgnoresSubmittedAndMarketingRows() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);
        mapper.submitDraft(submitRow(draft.getId()), 1, 900L);

        assertThat(mapper.selectLatestDraftByCreator(CREATOR)).isNull();
    }

    @Test
    void submitDraftFlipsToWaitStartAndWritesNameRemarkConfigAndCounts() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        assertThat(mapper.submitDraft(submitRow(draft.getId()), 1, 900L)).isEqualTo(1);

        PullTask saved = mapper.selectLifecycle(draft.getId());
        assertThat(saved.getStatus()).isEqualTo("WAIT_START");
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getTaskName()).isEqualTo("正式任务名");
    }

    @Test
    void submitDraftReturnsZeroOnVersionMismatch() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        assertThat(mapper.submitDraft(submitRow(draft.getId()), 99, 900L)).isZero();
        assertThat(mapper.selectLifecycle(draft.getId()).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void submitDraftIsIdempotentOnRepeatedSubmission() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        assertThat(mapper.submitDraft(submitRow(draft.getId()), 1, 900L)).isEqualTo(1);
        // 第二次用同一版本号重放：状态与版本都已推进，必须 0 行而不是产生第二次副作用。
        assertThat(mapper.submitDraft(submitRow(draft.getId()), 1, 901L)).isZero();
        assertThat(mapper.selectLifecycle(draft.getId()).getVersion()).isEqualTo(2);
    }

    @Test
    void otherTenantCannotSeeOrSubmitTheDraft() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        TenantContext.set(8L);
        assertThat(mapper.selectLatestDraftByCreator(CREATOR)).isNull();
        assertThat(mapper.submitDraft(submitRow(draft.getId()), 1, 900L)).isZero();

        TenantContext.set(7L);
        assertThat(mapper.selectLifecycle(draft.getId()).getStatus()).isEqualTo("DRAFT");
    }

    private static PullTask draftRow() {
        PullTask row = new PullTask();
        row.setTaskName("未命名草稿");
        row.setOperatorName("运营甲");
        row.setCreatedBy(CREATOR);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static PullTask submitRow(Long id) {
        PullTask row = new PullTask();
        row.setId(id);
        row.setTaskName("正式任务名");
        row.setRemark("备注");
        row.setConfigJson("{\"autoStart\":1}");
        row.setGroupCount(3);
        row.setExpectedPullCount(120);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMapper.class);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskDraftMapperInMemoryTest' test
```

预期：编译失败，`setCreatedBy` / `setConfigJson` / `insertDraft` / `selectLatestDraftByCreator` / `submitDraft` 都不存在。

- [ ] **Step 3: 给 `PullTask` 补两个字段**

修改 `armada-api/src/main/java/com/armada/task/model/entity/PullTask.java`，在 `remark` 字段声明之后加两个字段：

```java
    /** 创建人用户 ID；"每用户一条草稿"的查询键。 */
    private Long createdBy;

    /** 任务配置快照 JSON；草稿期为 {@code {}}，提交时写入完整配置。 */
    private String configJson;
```

在 `remark` 的 getter/setter 之后加：

```java
    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }
```

- [ ] **Step 4: 给 `PullTaskMapper` 加三个方法**

在 `armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java` 的 `selectLifecycle` 之前插入：

```java
    /**
     * 插入一条普通群链接草稿任务行。
     *
     * <p>{@code task_type} 固定 {@code STANDARD}、{@code mode} 固定 {@code NORMAL_LINK}、
     * {@code status} 固定 {@code DRAFT}、{@code config_json} 先写空对象；
     * {@code tenant_id} 由租户拦截器注入。草稿不进任务列表与任何聚合统计（ADR-0007）。</p>
     *
     * @param row 只需设置 taskName、operatorName、createdBy、createdAt、updatedAt；执行后回填 id
     * @return 插入行数
     */
    int insertDraft(PullTask row);

    /**
     * 取该创建人最新的一条普通群链接草稿。
     *
     * <p>同用户双击或多标签页可能漏出多条草稿，取最新一条容忍；这比为此加一条唯一索引迁移划算，
     * 遗留草稿是每用户常量级而不是随预览次数增长（ADR-0007）。</p>
     *
     * @param createdBy 创建人用户 ID
     * @return 最新草稿；没有时为 null，调用方负责包成 Optional
     */
    PullTask selectLatestDraftByCreator(@Param("createdBy") long createdBy);

    /**
     * 把草稿提交为待启动任务。
     *
     * <p>状态迁移与任务名、备注、配置快照、计数列在同一条带守卫的 UPDATE 里原子完成；
     * 拆成两条会让"状态已推进但计数未写"成为可观测中间态，且第二条没有乐观锁保护。
     * 重复提交返回 0 行，调用方据此走幂等分支而不是报错。</p>
     *
     * @param row             需设置 id、taskName、remark、configJson、groupCount、expectedPullCount
     * @param expectedVersion 读取草稿时拿到的版本号
     * @param now             本次更新时间(epoch 毫秒)
     * @return 实际更新行数；1 表示提交成功，0 表示状态或版本不符
     */
    int submitDraft(@Param("row") PullTask row,
                    @Param("expectedVersion") int expectedVersion,
                    @Param("now") long now);
```

- [ ] **Step 5: 写 Mapper XML**

在 `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml` 的 `selectLifecycle` 之前插入：

```xml
  <!-- 草稿的 task_type / mode / status / config_json 是固定值，不接受调用方覆盖；
       tenant_id 由租户拦截器注入。 -->
  <insert id="insertDraft" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO pull_task (
      task_type, task_name, mode, status, group_count, expected_pull_count,
      config_json, operator_name, created_by, created_at, updated_at
    ) VALUES (
      'STANDARD', #{taskName}, 'NORMAL_LINK', 'DRAFT', 0, 0,
      '{}', #{operatorName}, #{createdBy}, #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectLatestDraftByCreator" resultType="com.armada.task.model.entity.PullTask">
    SELECT id, tenant_id, task_type, task_name, mode, status, version,
           group_count, expected_pull_count, created_by, remark,
           created_at, updated_at
    FROM pull_task
    WHERE deleted_at IS NULL
      AND task_type = 'STANDARD'
      AND status = 'DRAFT'
      AND created_by = #{createdBy}
    ORDER BY id DESC
    LIMIT 1
  </select>

  <!-- 状态守卫 + 乐观锁在数据库层复核；重复提交返回 0 行而不是产生第二个任务。 -->
  <update id="submitDraft">
    UPDATE pull_task
    SET status = 'WAIT_START',
        version = version + 1,
        task_name = #{row.taskName},
        remark = #{row.remark},
        config_json = #{row.configJson},
        group_count = #{row.groupCount},
        expected_pull_count = #{row.expectedPullCount},
        updated_at = #{now}
    WHERE id = #{row.id}
      AND deleted_at IS NULL
      AND task_type = 'STANDARD'
      AND status = 'DRAFT'
      AND version = #{expectedVersion}
  </update>
```

- [ ] **Step 6: 把测试基座提升为 public**

`PullTaskNormalLinkSchema` 与 `PullTaskNormalLinkH2Support` 现在是包级私有，Task 9 / 10 的服务集成测试在 `com.armada.task.service` 包，用不到。做最小放宽：

- `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java`：`final class` → `public final class`；`static String[] all()` → `public static String[] all()`（7 个 DDL 常量保持包级私有，外部只需要 `all()`）。
- `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java`：`final class` → `public final class`；`dataSource` / `sqlSessionFactory` / `resetSchema` 三个方法加 `public`。

这是纯测试代码改动，不影响 `src/main`。

- [ ] **Step 7: 校验 XML 并运行测试**

```bash
cd /mnt/d/ideaProject/armada
xmllint --noout armada-api/src/main/resources/mapper/task/PullTaskMapper.xml
cd armada-api && mvn -Dtest='PullTaskDraftMapperInMemoryTest+PullTaskMapperInMemoryTest+PullTaskLifecycleMapperInMemoryTest+PullTaskListServiceTest' test
```

预期：`xmllint` 无输出；四个测试类全部 PASS。后三个是回归——它们共用同一份 `PullTaskMapper.xml`，必须证明新增语句没破坏既有查询。

- [ ] **Step 8: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/model/entity/PullTask.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftMapperInMemoryTest.java
git commit -m "feat: 新增普通群链接草稿任务行的建立、复用与提交冻结 Mapper

submitDraft 在一条带状态守卫和乐观锁的 UPDATE 里同时完成
DRAFT->WAIT_START 与任务名、备注、配置快照、计数列写入,避免
拆两条 UPDATE 产生可观测中间态。PullTask 补 createdBy 与
configJson 两个字段。H2 测试基座提升为 public 供服务集成测试复用。"
```

---

## Task 6: 执行行与料子成员的草稿编辑 Mapper

单行移除、清除全部连带删料子，以及链接占用查询。

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java`（补 2 个方法）
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java`（补 1 个方法）
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml`
- Test: `armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftEditMapperInMemoryTest.java`

**Interfaces:**
- Produces: 供 Task 8 / 9 使用
  - `PullTaskGroupExecutionMapper#deleteDraftRow(long taskId, long rowId) -> int`
  - `PullTaskGroupExecutionMapper#selectOccupiedLinks(List<String> links) -> List<String>`
  - `PullTaskMaterialMemberMapper#deleteByExecution(long groupExecutionId) -> int`

### 关键约束

- `deleteDraftRow` 必须带 `execution_status = 0`，否则会误删已冻结的执行行。
- `selectOccupiedLinks` 的 `<foreach>` 遇空集合会生成非法 SQL，**调用方必须先判空**；Javadoc 里写死这条。
- 单行移除的调用顺序是**先删料子、再删执行行**：若执行行删除返回 0（说明已冻结），Service 抛异常回滚，料子随之恢复。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftEditMapperInMemoryTest.java`：

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 草稿执行行与料子成员编辑操作的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskDraftEditMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskDraftEditMapperInMemoryTest {

    private static final long TASK_A = 1L;
    private static final long TASK_B = 2L;
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskMaterialMemberMapper materialMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void deleteDraftRowRemovesOnlyTheTargetRow() {
        PullTaskGroupExecution first = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        insertRow(TASK_A, 2, LINK_B, "b.txt", 1);

        assertThat(executionMapper.deleteDraftRow(TASK_A, first.getId())).isEqualTo(1);

        assertThat(executionMapper.selectByTaskId(TASK_A))
                .extracting(PullTaskGroupExecution::getSeq).containsExactly(2);
    }

    @Test
    void deleteDraftRowRefusesFrozenRow() {
        PullTaskGroupExecution row = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        executionMapper.freezeDraftRows(TASK_A, 900L);

        assertThat(executionMapper.deleteDraftRow(TASK_A, row.getId())).isZero();
        assertThat(executionMapper.selectByTaskId(TASK_A)).hasSize(1);
    }

    @Test
    void deleteDraftRowRefusesRowOfAnotherTask() {
        PullTaskGroupExecution row = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);

        assertThat(executionMapper.deleteDraftRow(TASK_B, row.getId())).isZero();
    }

    @Test
    void selectOccupiedLinksReturnsOnlyFrozenOrRunningLinks() {
        insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        executionMapper.freezeDraftRows(TASK_A, 900L);
        insertRow(TASK_B, 1, LINK_B, "b.txt", 0);

        // LINK_A 已冻结(execution_status=1)进入占用；LINK_B 仍是草稿(0)不占用。
        assertThat(executionMapper.selectOccupiedLinks(List.of(LINK_A, LINK_B)))
                .containsExactly(LINK_A);
    }

    @Test
    void selectOccupiedLinksIsEmptyWhenNothingFrozen() {
        insertRow(TASK_A, 1, LINK_A, "a.txt", 0);

        assertThat(executionMapper.selectOccupiedLinks(List.of(LINK_A, LINK_B))).isEmpty();
    }

    @Test
    void deleteByExecutionRemovesOnlyThatExecutionMembers() {
        PullTaskGroupExecution kept = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        PullTaskGroupExecution removed = insertRow(TASK_A, 2, LINK_B, "b.txt", 1);
        materialMapper.batchInsert(List.of(member(kept.getId(), 1, "8613800138001")));
        materialMapper.batchInsert(List.of(member(removed.getId(), 1, "8613800138002")));

        assertThat(materialMapper.deleteByExecution(removed.getId())).isEqualTo(1);

        assertThat(materialMapper.selectByExecution(removed.getId())).isEmpty();
        assertThat(materialMapper.selectByExecution(kept.getId())).hasSize(1);
    }

    @Test
    void otherTenantCannotDeleteOrSeeOccupancy() {
        PullTaskGroupExecution row = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        executionMapper.freezeDraftRows(TASK_A, 900L);

        TenantContext.set(8L);
        assertThat(executionMapper.selectOccupiedLinks(List.of(LINK_A))).isEmpty();
        assertThat(executionMapper.deleteDraftRow(TASK_A, row.getId())).isZero();

        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(TASK_A)).hasSize(1);
    }

    private PullTaskGroupExecution insertRow(long taskId, int seq, String link,
                                             String fileName, int fileIndex) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setSourceFileIndex(fileIndex);
        row.setSourceFileName(fileName);
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        executionMapper.insertDraft(row);
        return row;
    }

    private static PullTaskMaterialMember member(long executionId, int seq, String phone) {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setGroupExecutionId(executionId);
        row.setMemberSeq(seq);
        row.setSourceLineNo(seq);
        row.setNormalizedPhone(phone);
        row.setAdminRequired(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_edit_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskDraftEditMapperInMemoryTest' test
```

预期：编译失败，三个方法不存在。

- [ ] **Step 3: 给执行行 Mapper 加两个方法**

在 `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java` 的 `deleteDraftByTaskId` 之后插入：

```java
    /**
     * 删除草稿任务下的单条执行行。
     *
     * <p>带 {@code execution_status = 0} 守卫，已冻结的执行行删不掉。
     * 调用方必须先删该行的料子成员：若本方法返回 0，事务回滚会把料子恢复。</p>
     *
     * @param taskId 草稿任务 ID
     * @param rowId  执行行 ID
     * @return 实际删除行数；0 表示行不存在、不属于该任务或已冻结
     */
    int deleteDraftRow(@Param("taskId") long taskId, @Param("rowId") long rowId);

    /**
     * 查出这批链接里已被本租户运行中任务占用的部分。
     *
     * <p>占用口径与生成列 {@code link_occupancy_key} 一致：{@code execution_status} 为
     * 1（待启动）、2（运行中）、3（暂停）时占用，草稿与终态不占用。这是创建页的软提示，
     * 硬互斥由唯一键在提交时承担。</p>
     *
     * <p><b>调用方必须保证 links 非空</b>：空集合会让 {@code foreach} 生成非法 SQL。</p>
     *
     * @param links 待检查的归一化链接，非空
     * @return 已被占用的归一化链接；无占用时为空列表
     */
    List<String> selectOccupiedLinks(@Param("links") List<String> links);
```

- [ ] **Step 4: 给料子成员 Mapper 加一个方法**

在 `armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java` 的 `selectByExecution` 之后插入：

```java
    /**
     * 删除某条执行行下的全部料子成员。
     *
     * <p>只在创建页的"单行移除"与"清除全部"里使用；执行行是否允许删除由
     * {@code PullTaskGroupExecutionMapper#deleteDraftRow} 的状态守卫把关，
     * 本方法不重复判断执行行状态。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @return 实际删除行数
     */
    int deleteByExecution(@Param("groupExecutionId") long groupExecutionId);
```

- [ ] **Step 5: 写两处 Mapper XML**

在 `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml` 的 `deleteDraftByTaskId` 之后插入：

```xml
  <delete id="deleteDraftRow">
    DELETE FROM pull_task_group_execution
    WHERE task_id = #{taskId}
      AND id = #{rowId}
      AND execution_status = 0
  </delete>

  <!-- 占用口径与生成列 link_occupancy_key 保持一致：1 待启动 / 2 运行中 / 3 暂停。 -->
  <select id="selectOccupiedLinks" resultType="java.lang.String">
    SELECT normalized_link
    FROM pull_task_group_execution
    WHERE execution_status IN (1, 2, 3)
      AND normalized_link IN
      <foreach collection="links" item="link" open="(" separator="," close=")">#{link}</foreach>
  </select>
```

在 `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml` 的 `selectByExecution` 之后插入：

```xml
  <delete id="deleteByExecution">
    DELETE FROM pull_task_material_member
    WHERE group_execution_id = #{groupExecutionId}
  </delete>
```

- [ ] **Step 6: 校验 XML 并运行测试**

```bash
cd /mnt/d/ideaProject/armada
xmllint --noout armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml \
               armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml
cd armada-api && mvn -Dtest='PullTaskDraftEditMapperInMemoryTest+PullTaskGroupExecutionMapperInMemoryTest+PullTaskMaterialMemberMapperInMemoryTest' test
```

预期：`xmllint` 无输出；三个测试类全部 PASS（后两个是共用 XML 的回归）。

- [ ] **Step 7: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml \
        armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftEditMapperInMemoryTest.java
git commit -m "feat: 新增草稿执行行单行移除、链接占用查询与料子清理 Mapper

deleteDraftRow 带 execution_status=0 守卫防止误删已冻结行;
selectOccupiedLinks 的占用口径与生成列 link_occupancy_key 一致,
只认待启动、运行中和暂停三种状态。"
```

---

## Task 7: `group` 域新增按链接回填 `group_link.id` 的登记方法

提交冻结时要给每条执行行回填 `group_link_id`。现有 `registerJoinTaskTargets` 返回 `void`，拿不到 ID；跨域规则又禁止 `task` 域直接碰 `GroupLinkMapper`。所以在 `group` 域的 Service 上开一个返回 ID 的方法。

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkRegistryPullTaskTargetTest.java`

**Interfaces:**
- Produces: 供 Task 9 使用
  - `GroupLinkRegistryService#registerPullTaskTargets(List<String> normalizedLinks, long now) -> Map<String, Long>`（归一化链接 → `group_link.id`，非法链接静默跳过）

### 不新增第二套登记逻辑

`registerOne` 现在写死 `GroupLinkOrigin.JOIN_TASK`。做法是把 `origin` 提为参数并**改掉唯一的既有调用点**，而不是复制一份 `registerOnePullTask`——编码规范第 2 条禁止"只增不删"式的并行路径。`GroupLinkOrigin.PULL_TASK`（枚举值 3）已经存在，不需要新增枚举值。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkRegistryPullTaskTargetTest.java`：

```java
package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 拉群任务群入口登记测试。 */
@ExtendWith(MockitoExtension.class)
class GroupLinkRegistryPullTaskTargetTest {

    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private AccountGroupMembershipMapper membershipMapper;

    @Test
    void insertsNewLinkAsPullTaskTargetAndReturnsGeneratedId() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(null);
        when(groupLinkMapper.insert(any(GroupLink.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, GroupLink.class).setId(77L);
            return 1;
        });

        Map<String, Long> ids = service().registerPullTaskTargets(List.of(LINK_A), 1000L);

        assertThat(ids).containsExactly(org.assertj.core.data.MapEntry.entry(LINK_A, 77L));
        ArgumentCaptor<GroupLink> captor = ArgumentCaptor.forClass(GroupLink.class);
        verify(groupLinkMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrigin()).isEqualTo(GroupLinkOrigin.PULL_TASK.code());
        assertThat(captor.getValue().getMembershipState())
                .isEqualTo(GroupMembershipState.TARGET.code());
    }

    @Test
    void reusesActiveLinkWithoutInsertingOrReviving() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(activeLink(55L));

        assertThat(service().registerPullTaskTargets(List.of(LINK_A), 1000L))
                .containsEntry(LINK_A, 55L);

        verify(groupLinkMapper, never()).insert(any(GroupLink.class));
        verify(groupLinkMapper, never()).reviveAsStandaloneTarget(anyLong(), anyLong());
    }

    @Test
    void revivesSoftDeletedLinkAndKeepsItsId() {
        GroupLink deleted = activeLink(66L);
        deleted.setDeletedAt(900L);
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(deleted);

        assertThat(service().registerPullTaskTargets(List.of(LINK_A), 1000L))
                .containsEntry(LINK_A, 66L);

        // 软删行仍占 link_url 唯一键，必须复活原行而不是插新行。
        verify(groupLinkMapper).reviveAsStandaloneTarget(66L, 1000L);
        verify(groupLinkMapper, never()).insert(any(GroupLink.class));
    }

    @Test
    void registersEachDistinctLinkOnlyOnce() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(activeLink(55L));

        Map<String, Long> ids = service().registerPullTaskTargets(
                List.of(LINK_A, LINK_A, LINK_A), 1000L);

        assertThat(ids).hasSize(1);
        verify(groupLinkMapper).selectAnyByUrl(LINK_A);
    }

    @Test
    void skipsUnparseableLinkWithoutFailingTheBatch() {
        when(groupLinkMapper.selectAnyByUrl(LINK_B)).thenReturn(activeLink(88L));

        Map<String, Long> ids = service().registerPullTaskTargets(
                List.of("不是链接", LINK_B), 1000L);

        assertThat(ids).containsOnlyKeys(LINK_B);
    }

    @Test
    void joinTaskPathStillRegistersWithJoinTaskOrigin() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(null);

        service().registerJoinTaskTargets(List.of(LINK_A));

        ArgumentCaptor<GroupLink> captor = ArgumentCaptor.forClass(GroupLink.class);
        verify(groupLinkMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrigin()).isEqualTo(GroupLinkOrigin.JOIN_TASK.code());
    }

    private GroupLinkRegistryServiceImpl service() {
        return new GroupLinkRegistryServiceImpl(groupLinkMapper, membershipMapper);
    }

    private static GroupLink activeLink(long id) {
        GroupLink link = new GroupLink();
        link.setId(id);
        link.setLinkUrl(LINK_A);
        return link;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='GroupLinkRegistryPullTaskTargetTest' test
```

预期：编译失败，`registerPullTaskTargets` 不存在。

- [ ] **Step 3: 给接口加方法**

在 `armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java` 的 `registerJoinTaskTargets` 之后插入（并补 `import java.util.Map;`）：

```java
    /**
     * 把拉群任务冻结的群邀请链接登记为群组池目标，并回填群入口 ID。
     *
     * <p>与 {@link #registerJoinTaskTargets(List)} 的差别只有两点：来源记为
     * {@code PULL_TASK}，以及返回每条链接对应的 {@code group_link.id}——拉群任务的执行行
     * 需要这个 ID 才能建立与群组池的关联。本方法只做本地登记/复活，不调用协议层；
     * 格式不合格的链接静默跳过，由调用方自己的逐行结果记录原因。</p>
     *
     * @param normalizedLinks 已冻结的群邀请链接
     * @param now             登记时间（epoch 毫秒）
     * @return 归一化链接到 {@code group_link.id} 的映射；非法链接不出现在结果里
     */
    Map<String, Long> registerPullTaskTargets(List<String> normalizedLinks, long now);
```

- [ ] **Step 4: 实现方法并把 `origin` 提为 `registerOne` 的参数**

修改 `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`：

补 import：`java.util.LinkedHashMap`、`java.util.Map`。

把 `registerJoinTaskTargets` 里的调用改成带 origin：

```java
        for (String url : urls) {
            registerOne(url, now, GroupLinkOrigin.JOIN_TASK);
        }
```

在其后新增：

```java
    /**
     * 把拉群任务冻结的群邀请链接登记为群组池目标，并回填群入口 ID。
     *
     * @param normalizedLinks 已冻结的群邀请链接
     * @param now             登记时间（epoch 毫秒）
     * @return 归一化链接到 {@code group_link.id} 的映射
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> registerPullTaskTargets(List<String> normalizedLinks, long now) {
        Map<String, Long> idsByUrl = new LinkedHashMap<>();
        for (String raw : normalizedLinks) {
            GroupLinkUrls.tryNormalize(raw).ifPresent(url ->
                    idsByUrl.computeIfAbsent(url, key -> registerOne(key, now,
                            GroupLinkOrigin.PULL_TASK)));
        }
        return idsByUrl;
    }
```

把 `registerOne` 改为带 origin 参数并返回 ID（替换原方法整体）：

```java
    /**
     * 登记或复活单个规范化邀请链接。
     *
     * @param url    已按统一规则规范化的群邀请链接
     * @param now    登记时间（epoch 毫秒）
     * @param origin 首次入池来源；仅在新建时写入，已存在的行不改写
     * @return 复用、复活或新建后的 {@code group_link.id}
     */
    private Long registerOne(String url, long now, GroupLinkOrigin origin) {
        GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
        if (existing == null) {
            // 全新链接:作为任务目标进入群组池,但不归入任何导入链接分组。
            GroupLink row = new GroupLink();
            row.setLinkUrl(url);
            row.setOrigin(origin.code());
            row.setMembershipState(GroupMembershipState.TARGET.code());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            groupLinkMapper.insert(row);
            return row.getId();
        }
        if (existing.getDeletedAt() != null) {
            // 软删行仍占唯一键,必须复活原行;不复活直接插入会撞唯一键。
            groupLinkMapper.reviveAsStandaloneTarget(existing.getId(), now);
        }
        // 已存在且活跃时故意不改:origin 是首次入池来源,membership_state 只能由后续状态回写升级。
        return existing.getId();
    }
```

- [ ] **Step 5: 运行新测试与既有回归**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='GroupLinkRegistryPullTaskTargetTest+GroupLinkRegistryServiceImplUnitTest+GroupLinkRegistryServiceImplTest' test
```

预期：三个测试类全部 PASS。后两个证明 `registerOne` 改签名没有改变进群任务路径的行为。

- [ ] **Step 6: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java \
        armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java \
        armada-api/src/test/java/com/armada/group/service/impl/GroupLinkRegistryPullTaskTargetTest.java
git commit -m "feat: 群组池登记支持拉群任务来源并回填 group_link.id

拉群任务执行行需要 group_link_id,而 registerJoinTaskTargets 返回
void。把 origin 提为 registerOne 的参数并改掉唯一既有调用点,不复制
第二套登记逻辑;新方法返回归一化链接到群入口 ID 的映射。"
```

---

## Task 8: `PullTaskStandardDraftWriter` —— 草稿的事务写入组件

把草稿的四种写操作收进一个 `@Transactional` 组件。**这个类必须独立存在**：编排服务里的链接预检是最坏 40 秒的外部 HTTP，绝不能被事务包住，否则数据库连接会被外部网络阻塞占用，并发创建时拖垮连接池。Spring 的自调用不走代理，所以事务边界必须落在另一个 bean 上。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftWriter.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftWriterTest.java`

**Interfaces:**
- Consumes: Task 5 的 `insertDraft` / `selectLatestDraftByCreator`；Task 6 的 `deleteDraftRow` / `deleteByExecution`；既有 `insertDraft`（执行行）/ `selectByTaskId` / `deleteDraftByTaskId` / `batchInsert`
- Produces: 供 Task 9 使用
  - `ensureDraft(long userId, String operatorName, long now) -> PullTask`
  - `append(long taskId, List<AppendRow> rows, long now) -> void`
  - `removeRow(long taskId, long rowId) -> void`
  - `clearAll(long taskId) -> void`
  - `record AppendRow(PullTaskGroupExecution execution, List<PullTaskMaterialMember> members)`

### 单行移除的删除顺序

先删料子、再删执行行。执行行删除返回 0 说明它已被冻结，此时抛 `BusinessException` 让事务回滚，料子随之恢复——顺序反过来就没法用一个状态守卫同时保护两张表。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftWriterTest.java`：

```java
package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.armada.task.service.impl.PullTaskStandardDraftWriter.AppendRow;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 草稿事务写入组件的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskStandardDraftWriterTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardDraftWriterTest {

    private static final long CREATOR = 501L;
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardDraftWriter writer;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskMaterialMemberMapper materialMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ensureDraftCreatesOnceAndReusesAfterwards() {
        PullTask first = writer.ensureDraft(CREATOR, "运营甲", 100L);
        PullTask second = writer.ensureDraft(CREATOR, "运营甲", 200L);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void ensureDraftIsPerCreator() {
        PullTask mine = writer.ensureDraft(CREATOR, "运营甲", 100L);
        PullTask others = writer.ensureDraft(602L, "运营乙", 100L);

        assertThat(others.getId()).isNotEqualTo(mine.getId());
    }

    @Test
    void appendWritesExecutionRowsAndTheirMembers() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();

        writer.append(taskId, List.of(appendRow(1, LINK_A, "a.txt", 0, "8613800138001")), 300L);

        List<PullTaskGroupExecution> rows = executionMapper.selectByTaskId(taskId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getSeq()).isEqualTo(1);
            assertThat(row.getNormalizedLink()).isEqualTo(LINK_A);
            // 草稿期不占链接：生成列在 execution_status=0 时为 NULL。
            assertThat(row.getExecutionStatus()).isZero();
        });
        assertThat(materialMapper.selectByExecution(rows.get(0).getId()))
                .extracting(PullTaskMaterialMember::getNormalizedPhone)
                .containsExactly("8613800138001");
    }

    @Test
    void appendIsIncrementalAndLeavesEarlierRowsUntouched() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(appendRow(1, LINK_A, "a.txt", 0, "8613800138001")), 300L);

        writer.append(taskId, List.of(appendRow(2, LINK_B, "b.txt", 1, "8613800138002")), 400L);

        assertThat(executionMapper.selectByTaskId(taskId))
                .extracting(PullTaskGroupExecution::getSeq,
                        PullTaskGroupExecution::getNormalizedLink)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, LINK_A),
                        org.assertj.core.groups.Tuple.tuple(2, LINK_B));
    }

    @Test
    void appendAcceptsEmptyBatchWithoutTouchingAnything() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();

        writer.append(taskId, List.of(), 300L);

        assertThat(executionMapper.selectByTaskId(taskId)).isEmpty();
    }

    @Test
    void removeRowDeletesTheRowAndItsMembers() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(
                appendRow(1, LINK_A, "a.txt", 0, "8613800138001"),
                appendRow(2, LINK_B, "b.txt", 1, "8613800138002")), 300L);
        long removedId = executionMapper.selectByTaskId(taskId).get(0).getId();

        writer.removeRow(taskId, removedId);

        assertThat(executionMapper.selectByTaskId(taskId))
                .extracting(PullTaskGroupExecution::getSeq).containsExactly(2);
        assertThat(materialMapper.selectByExecution(removedId)).isEmpty();
    }

    @Test
    void removeRowRollsBackWhenRowIsAlreadyFrozen() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(appendRow(1, LINK_A, "a.txt", 0, "8613800138001")), 300L);
        long rowId = executionMapper.selectByTaskId(taskId).get(0).getId();
        executionMapper.freezeDraftRows(taskId, 500L);

        assertThatThrownBy(() -> writer.removeRow(taskId, rowId))
                .isInstanceOf(BusinessException.class);

        // 料子先删、执行行后删；执行行删不掉时整笔回滚，料子必须还在。
        assertThat(materialMapper.selectByExecution(rowId)).hasSize(1);
        assertThat(executionMapper.selectByTaskId(taskId)).hasSize(1);
    }

    @Test
    void clearAllRemovesEveryRowAndMemberButKeepsTheDraftTask() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(
                appendRow(1, LINK_A, "a.txt", 0, "8613800138001"),
                appendRow(2, LINK_B, "b.txt", 1, "8613800138002")), 300L);
        List<Long> rowIds = executionMapper.selectByTaskId(taskId).stream()
                .map(PullTaskGroupExecution::getId).toList();

        writer.clearAll(taskId);

        assertThat(executionMapper.selectByTaskId(taskId)).isEmpty();
        rowIds.forEach(id -> assertThat(materialMapper.selectByExecution(id)).isEmpty());
        // 草稿任务行保留下来复用，不是每次清空都换一条。
        assertThat(writer.ensureDraft(CREATOR, "运营甲", 600L).getId()).isEqualTo(taskId);
    }

    private static AppendRow appendRow(int seq, String link, String fileName,
                                       int fileIndex, String phone) {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(seq);
        execution.setNormalizedLink(link);
        execution.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        execution.setSourceLinkLineNo(seq);
        execution.setSourceFileIndex(fileIndex);
        execution.setSourceFileName(fileName);
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);

        PullTaskMaterialMember member = new PullTaskMaterialMember();
        member.setMemberSeq(1);
        member.setSourceLineNo(1);
        member.setNormalizedPhone(phone);
        member.setAdminRequired(0);
        return new AppendRow(execution, List.of(member));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_writer_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean
        PullTaskStandardDraftWriter writer(PullTaskMapper pullTaskMapper,
                                           PullTaskGroupExecutionMapper executionMapper,
                                           PullTaskMaterialMemberMapper materialMapper) {
            return new PullTaskStandardDraftWriter(pullTaskMapper, executionMapper, materialMapper);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardDraftWriterTest' test
```

预期：编译失败，`PullTaskStandardDraftWriter` 不存在。

- [ ] **Step 3: 写实现**

创建 `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftWriter.java`：

```java
package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 普通群链接草稿的事务写入组件。
 *
 * <p>独立成一个 bean 而不是写在编排服务里，是因为编排服务要在事务外完成最坏 40 秒的
 * 公开邀请页预检；Spring 的自调用不走代理，事务边界只能落在另一个 bean 上。把这四个写操作
 * 收在这里，编排服务本身就不需要也不允许标 {@code @Transactional}。</p>
 */
@Component
public class PullTaskStandardDraftWriter {

    /** 草稿期的占位任务名；正式名称在提交时才写入。 */
    private static final String DRAFT_TASK_NAME = "未命名草稿";

    private final PullTaskMapper pullTaskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskMaterialMemberMapper materialMapper;

    /**
     * 创建草稿写入组件。
     *
     * @param pullTaskMapper  任务主表数据访问
     * @param executionMapper 执行行数据访问
     * @param materialMapper  料子成员数据访问
     */
    public PullTaskStandardDraftWriter(PullTaskMapper pullTaskMapper,
                                       PullTaskGroupExecutionMapper executionMapper,
                                       PullTaskMaterialMemberMapper materialMapper) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.materialMapper = materialMapper;
    }

    /**
     * 取当前用户的草稿，没有就建一条。
     *
     * @param userId       创建人用户 ID
     * @param operatorName 操作员展示名快照
     * @param now          当前时间(epoch 毫秒)
     * @return 复用或新建的草稿任务行
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTask ensureDraft(long userId, String operatorName, long now) {
        PullTask existing = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (existing != null) {
            return existing;
        }
        PullTask draft = new PullTask();
        draft.setTaskName(DRAFT_TASK_NAME);
        draft.setOperatorName(operatorName);
        draft.setCreatedBy(userId);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        pullTaskMapper.insertDraft(draft);
        return draft;
    }

    /**
     * 追加本批匹配好的执行行与其料子成员。
     *
     * @param taskId 草稿任务 ID
     * @param rows   本批执行行及各自的料子；空集合直接返回
     * @param now    当前时间(epoch 毫秒)
     */
    @Transactional(rollbackFor = Exception.class)
    public void append(long taskId, List<AppendRow> rows, long now) {
        for (AppendRow row : rows) {
            PullTaskGroupExecution execution = row.execution();
            execution.setTaskId(taskId);
            execution.setCreatedAt(now);
            execution.setUpdatedAt(now);
            executionMapper.insertDraft(execution);
            insertMembers(execution.getId(), row.members(), now);
        }
    }

    /**
     * 删除草稿下的单条执行行及其料子。
     *
     * <p>先删料子、再删执行行：执行行删除带 {@code execution_status = 0} 守卫，
     * 返回 0 说明已被冻结，此时抛业务异常让整笔回滚，料子随之恢复。</p>
     *
     * @param taskId 草稿任务 ID
     * @param rowId  执行行 ID
     * @throws BusinessException 执行行不存在、不属于该草稿或已冻结时
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeRow(long taskId, long rowId) {
        materialMapper.deleteByExecution(rowId);
        if (executionMapper.deleteDraftRow(taskId, rowId) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "该执行行不存在或已提交，无法移除");
        }
    }

    /**
     * 清空草稿下的全部执行行与料子，保留草稿任务行本身。
     *
     * @param taskId 草稿任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearAll(long taskId) {
        for (PullTaskGroupExecution row : executionMapper.selectByTaskId(taskId)) {
            materialMapper.deleteByExecution(row.getId());
        }
        executionMapper.deleteDraftByTaskId(taskId);
    }

    /**
     * 批量写入某条执行行的料子成员。
     *
     * @param executionId 执行行 ID
     * @param members     料子成员；空集合直接返回
     * @param now         当前时间(epoch 毫秒)
     */
    private void insertMembers(Long executionId, List<PullTaskMaterialMember> members, long now) {
        if (members.isEmpty()) {
            return;
        }
        for (PullTaskMaterialMember member : members) {
            member.setGroupExecutionId(executionId);
            member.setCreatedAt(now);
            member.setUpdatedAt(now);
        }
        materialMapper.batchInsert(members);
    }

    /**
     * 一条待写入的执行行及其料子成员。
     *
     * @param execution 执行行；taskId、createdAt、updatedAt 由本组件填写
     * @param members   该执行行的料子；groupExecutionId、createdAt、updatedAt 由本组件填写
     */
    public record AppendRow(PullTaskGroupExecution execution, List<PullTaskMaterialMember> members) {
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardDraftWriterTest' test
```

预期：8 个测试全部 PASS。特别确认 `removeRowRollsBackWhenRowIsAlreadyFrozen` 是绿的——它证明事务代理真的生效了；若它红了说明 `@EnableTransactionManagement` 或 bean 代理没配好，而不是业务逻辑错。

- [ ] **Step 5: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftWriter.java \
        armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftWriterTest.java
git commit -m "feat: 新增普通群链接草稿的事务写入组件

独立成 bean 是为了让编排服务能在事务外做最坏 40 秒的邀请页预检
(Spring 自调用不走代理)。单行移除先删料子再删执行行,执行行的
execution_status=0 守卫返回 0 时抛业务异常整笔回滚。"
```

---

## Task 9: 草稿 VO 与读取／编辑编排

定义创建页的全部出参 record，并实现草稿的**回读、单行移除、清除全部**。匹配追加（`plan`）留给 Task 10——它要引入上传校验、外部预检和随机匹配，独立成一个任务更容易评审。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardMaterialLineErrorVO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardFileResultVO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardLinkLineVO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardExecutionRowVO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardDraftVO.java`
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskStandardDraftService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServiceReadEditTest.java`

**Interfaces:**
- Consumes: Task 8 的 `PullTaskStandardDraftWriter`；Task 5 的 `selectLatestDraftByCreator`；既有 `PullTaskGroupExecutionMapper#selectByTaskId`
- Produces: 供 Task 10 / 11 使用
  - `PullTaskStandardDraftService#current(long userId) -> PullTaskStandardDraftVO`
  - `PullTaskStandardDraftService#removeRow(long rowId, long userId) -> PullTaskStandardDraftVO`
  - `PullTaskStandardDraftService#clear(long userId) -> PullTaskStandardDraftVO`
  - 五个 VO record（字段见下方代码）

### 空草稿的表示

用户还没建过草稿时 `current` 返回 `draftTaskId = null`、各列表为空的 VO，而不是抛 404——创建页首次打开是正常状态，不是错误。编码规范"禁止返回 null"针对的是方法返回值，record 的可空字段不在其列。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServiceReadEditTest.java`：

```java
package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionRowVO;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.armada.task.service.impl.PullTaskStandardDraftWriter.AppendRow;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 草稿回读与编辑编排的 H2 集成测试。 */
@SpringJUnitConfig(PullTaskStandardDraftServiceReadEditTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardDraftServiceReadEditTest {

    private static final long CREATOR = 501L;
    private static final long OTHER_CREATOR = 602L;
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardDraftService service;

    @Autowired
    private PullTaskStandardDraftWriter writer;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskMaterialMemberMapper materialMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void currentReturnsEmptyViewWhenUserHasNoDraftYet() {
        PullTaskStandardDraftVO view = service.current(CREATOR);

        // 首次打开创建页是正常状态，不是 404。
        assertThat(view.draftTaskId()).isNull();
        assertThat(view.rows()).isEmpty();
        assertThat(view.linkLines()).isEmpty();
        assertThat(view.fileResults()).isEmpty();
        assertThat(view.matchedCount()).isZero();
        assertThat(view.remainingLinkCount()).isZero();
        assertThat(view.ignoredFileCount()).isZero();
    }

    @Test
    void currentReturnsRowsWithStatisticsAndVersion() {
        long taskId = seedTwoRows();

        PullTaskStandardDraftVO view = service.current(CREATOR);

        assertThat(view.draftTaskId()).isEqualTo(taskId);
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.matchedCount()).isEqualTo(2);
        assertThat(view.rows()).extracting(
                        PullTaskStandardExecutionRowVO::seq,
                        PullTaskStandardExecutionRowVO::normalizedLink,
                        PullTaskStandardExecutionRowVO::sourceFileName,
                        PullTaskStandardExecutionRowVO::validMemberCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, LINK_A, "a.txt", 1),
                        org.assertj.core.groups.Tuple.tuple(2, LINK_B, "b.txt", 1));
        // 链接文本不落库，回读时逐行结果必然为空，由前端从 sessionStorage 恢复。
        assertThat(view.linkLines()).isEmpty();
    }

    @Test
    void currentIsScopedToTheCreator() {
        seedTwoRows();

        assertThat(service.current(OTHER_CREATOR).draftTaskId()).isNull();
    }

    @Test
    void removeRowDropsTheRowAndItsMembersAndReturnsFreshView() {
        long taskId = seedTwoRows();
        long removedId = executionMapper.selectByTaskId(taskId).get(0).getId();

        PullTaskStandardDraftVO view = service.removeRow(removedId, CREATOR);

        assertThat(view.matchedCount()).isEqualTo(1);
        assertThat(view.rows()).extracting(PullTaskStandardExecutionRowVO::seq).containsExactly(2);
        assertThat(materialMapper.selectByExecution(removedId)).isEmpty();
    }

    @Test
    void removeRowRejectsRowBelongingToAnotherUsersDraft() {
        long taskId = seedTwoRows();
        long rowId = executionMapper.selectByTaskId(taskId).get(0).getId();

        assertThatThrownBy(() -> service.removeRow(rowId, OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);
        assertThat(executionMapper.selectByTaskId(taskId)).hasSize(2);
    }

    @Test
    void clearRemovesEveryRowButKeepsTheDraftForReuse() {
        long taskId = seedTwoRows();

        PullTaskStandardDraftVO view = service.clear(CREATOR);

        assertThat(view.draftTaskId()).isEqualTo(taskId);
        assertThat(view.rows()).isEmpty();
        assertThat(view.matchedCount()).isZero();
    }

    @Test
    void clearOnMissingDraftIsRejected() {
        assertThatThrownBy(() -> service.clear(CREATOR)).isInstanceOf(BusinessException.class);
    }

    private long seedTwoRows() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(
                appendRow(1, LINK_A, "a.txt", 1, "8613800138001"),
                appendRow(2, LINK_B, "b.txt", 2, "8613800138002")), 200L);
        return taskId;
    }

    private static AppendRow appendRow(int seq, String link, String fileName,
                                       int fileIndex, String phone) {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(seq);
        execution.setNormalizedLink(link);
        execution.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        execution.setSourceLinkLineNo(seq);
        execution.setSourceFileIndex(fileIndex);
        execution.setSourceFileName(fileName);
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);

        PullTaskMaterialMember member = new PullTaskMaterialMember();
        member.setMemberSeq(1);
        member.setSourceLineNo(1);
        member.setNormalizedPhone(phone);
        member.setAdminRequired(0);
        return new AppendRow(execution, List.of(member));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_read_edit_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean
        PullTaskStandardDraftWriter writer(PullTaskMapper pullTaskMapper,
                                           PullTaskGroupExecutionMapper executionMapper,
                                           PullTaskMaterialMemberMapper materialMapper) {
            return new PullTaskStandardDraftWriter(pullTaskMapper, executionMapper, materialMapper);
        }

        @Bean
        PullTaskStandardDraftService draftService(PullTaskMapper pullTaskMapper,
                                                  PullTaskGroupExecutionMapper executionMapper,
                                                  PullTaskStandardDraftWriter writer) {
            return new PullTaskStandardDraftServiceImpl(pullTaskMapper, executionMapper, writer);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardDraftServiceReadEditTest' test
```

预期：编译失败，VO 与服务都不存在。

- [ ] **Step 3: 写五个 VO record**

`armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardMaterialLineErrorVO.java`：

```java
package com.armada.task.model.vo;

/**
 * TXT 料子文件里的单行失败明细。
 *
 * @param lineNo 文件内原始物理行号
 * @param reason 失败原因，直接展示给运营
 */
public record PullTaskStandardMaterialLineErrorVO(int lineNo, String reason) {
}
```

`armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardFileResultVO.java`：

```java
package com.armada.task.model.vo;

import java.util.List;

/**
 * 单个 TXT 料子文件的解析结果。
 *
 * @param fileName           原始文件名
 * @param accepted           是否进入随机匹配池；零有效号码的文件为 false
 * @param validMemberCount   去重后的有效号码数
 * @param invalidLineCount   非法行数
 * @param duplicateLineCount 文件内重复号码行数
 * @param rejectReason       未进入匹配池的原因；accepted 为 true 时为 null
 * @param lineErrors         逐行失败明细
 */
public record PullTaskStandardFileResultVO(String fileName, boolean accepted,
                                           int validMemberCount, int invalidLineCount,
                                           int duplicateLineCount, String rejectReason,
                                           List<PullTaskStandardMaterialLineErrorVO> lineErrors) {
}
```

`armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardLinkLineVO.java`：

```java
package com.armada.task.model.vo;

import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;

/**
 * 粘贴文本里单行群链接的判定结果。
 *
 * @param lineNo         原始物理行号
 * @param raw            trim 后的行原文
 * @param normalizedLink 归一化链接；格式非法时为 null
 * @param status         判定终态
 * @param reason         失败或提示原因；无需提示时为 null
 */
public record PullTaskStandardLinkLineVO(int lineNo, String raw, String normalizedLink,
                                         PullTaskStandardLinkLineStatus status, String reason) {
}
```

`armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardExecutionRowVO.java`：

```java
package com.armada.task.model.vo;

/**
 * 一条已冻结在草稿里的「群链接 ↔ TXT」执行行。
 *
 * @param rowId              执行行 ID，单行移除时回传
 * @param seq                任务内展示与执行顺序
 * @param normalizedLink     归一化群链接
 * @param sourceLinkLineNo   该链接在粘贴文本中的原始行号
 * @param sourceFileName     配对 TXT 的原始文件名
 * @param totalLineCount     TXT 物理行数
 * @param validMemberCount   去重后的有效料子数
 * @param invalidLineCount   非法行数
 * @param duplicateLineCount 文件内重复号码行数
 */
public record PullTaskStandardExecutionRowVO(Long rowId, int seq, String normalizedLink,
                                             int sourceLinkLineNo, String sourceFileName,
                                             int totalLineCount, int validMemberCount,
                                             int invalidLineCount, int duplicateLineCount) {
}
```

`armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardDraftVO.java`：

```java
package com.armada.task.model.vo;

import java.util.List;

/**
 * 创建页的完整草稿视图。
 *
 * <p>链接框文本不落库，因此回读草稿时 {@code linkLines} 与 {@code fileResults} 必然为空，
 * 只有本次请求才会带上它们；前端需要自行用 sessionStorage 恢复链接框内容。</p>
 *
 * @param draftTaskId        草稿任务 ID；用户还没有草稿时为 null
 * @param version            草稿任务乐观锁版本，提交时原样回传
 * @param rows               已冻结的执行行，按 seq 升序
 * @param linkLines          本次请求的链接逐行判定结果
 * @param fileResults        本次请求的 TXT 逐文件解析结果
 * @param matchedCount       已匹配执行行总数
 * @param remainingLinkCount 本次请求后仍未匹配的有效链接数
 * @param ignoredFileCount   本次因剩余链接不足被忽略的文件数
 */
public record PullTaskStandardDraftVO(Long draftTaskId, Integer version,
                                      List<PullTaskStandardExecutionRowVO> rows,
                                      List<PullTaskStandardLinkLineVO> linkLines,
                                      List<PullTaskStandardFileResultVO> fileResults,
                                      int matchedCount, int remainingLinkCount,
                                      int ignoredFileCount) {
}
```

- [ ] **Step 4: 写服务接口**

创建 `armada-api/src/main/java/com/armada/task/service/PullTaskStandardDraftService.java`：

```java
package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.task.model.vo.PullTaskStandardDraftVO;

/**
 * 普通群链接创建页的草稿编排服务。
 *
 * <p>每个用户同一时刻只保留一条 {@code STANDARD} 草稿（ADR-0007）。草稿不进任务列表、
 * 任务看板与任何聚合统计，只在创建页可见。</p>
 */
public interface PullTaskStandardDraftService {

    /**
     * 回读当前用户的草稿。
     *
     * @param userId 当前登录用户 ID
     * @return 草稿视图；用户还没有草稿时 {@code draftTaskId} 为 null，各列表为空
     */
    PullTaskStandardDraftVO current(long userId);

    /**
     * 移除草稿中的单条执行行，链接与 TXT 一并丢弃、不回匹配池。
     *
     * @param rowId  执行行 ID
     * @param userId 当前登录用户 ID
     * @return 移除后的草稿视图
     * @throws BusinessException 草稿不存在，或执行行不属于该草稿、已冻结时
     */
    PullTaskStandardDraftVO removeRow(long rowId, long userId);

    /**
     * 清空草稿中的全部执行行与料子，保留草稿任务行本身以供复用。
     *
     * @param userId 当前登录用户 ID
     * @return 清空后的草稿视图
     * @throws BusinessException 草稿不存在时
     */
    PullTaskStandardDraftVO clear(long userId);
}
```

- [ ] **Step 5: 写服务实现**

创建 `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java`：

```java
package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionRowVO;
import com.armada.task.model.vo.PullTaskStandardFileResultVO;
import com.armada.task.model.vo.PullTaskStandardLinkLineVO;
import com.armada.task.service.PullTaskStandardDraftService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 普通群链接创建页草稿编排实现。
 *
 * <p>本类<b>刻意不标 {@code @Transactional}</b>：匹配追加流程里包含最坏 40 秒的公开邀请页
 * 预检，事务包住外部 HTTP 会让数据库连接被网络阻塞占用。全部写操作委托给
 * {@link PullTaskStandardDraftWriter}，事务边界落在那个 bean 上。</p>
 */
@Service
public class PullTaskStandardDraftServiceImpl implements PullTaskStandardDraftService {

    private static final Logger log = LoggerFactory.getLogger(PullTaskStandardDraftServiceImpl.class);

    /** 用户还没有草稿时返回的空视图。 */
    private static final PullTaskStandardDraftVO EMPTY_VIEW = new PullTaskStandardDraftVO(
            null, null, List.of(), List.of(), List.of(), 0, 0, 0);

    private final PullTaskMapper pullTaskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStandardDraftWriter writer;

    /**
     * 创建草稿编排服务。
     *
     * @param pullTaskMapper  任务主表数据访问
     * @param executionMapper 执行行数据访问
     * @param writer          草稿事务写入组件
     */
    public PullTaskStandardDraftServiceImpl(PullTaskMapper pullTaskMapper,
                                            PullTaskGroupExecutionMapper executionMapper,
                                            PullTaskStandardDraftWriter writer) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.writer = writer;
    }

    @Override
    public PullTaskStandardDraftVO current(long userId) {
        PullTask draft = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (draft == null) {
            return EMPTY_VIEW;
        }
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    @Override
    public PullTaskStandardDraftVO removeRow(long rowId, long userId) {
        PullTask draft = requireDraft(userId);
        writer.removeRow(draft.getId(), rowId);
        log.info("创建页移除执行行 taskId={} rowId={} operatorId={}", draft.getId(), rowId, userId);
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    @Override
    public PullTaskStandardDraftVO clear(long userId) {
        PullTask draft = requireDraft(userId);
        writer.clearAll(draft.getId());
        log.info("创建页清除全部执行行 taskId={} operatorId={}", draft.getId(), userId);
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    /**
     * 取当前用户的草稿，没有则拒绝操作。
     *
     * @param userId 当前登录用户 ID
     * @return 草稿任务行
     * @throws BusinessException 草稿不存在时
     */
    private PullTask requireDraft(long userId) {
        PullTask draft = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (draft == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "当前没有可编辑的创建页草稿");
        }
        return draft;
    }

    /**
     * 读回执行行并组装草稿视图。
     *
     * @param draft              草稿任务行
     * @param linkLines          本次请求的链接逐行结果；回读与编辑场景为空
     * @param fileResults        本次请求的逐文件结果；回读与编辑场景为空
     * @param remainingLinkCount 本次请求后仍未匹配的有效链接数
     * @param ignoredFileCount   本次被忽略的文件数
     * @return 草稿视图
     */
    PullTaskStandardDraftVO toView(PullTask draft,
                                   List<PullTaskStandardLinkLineVO> linkLines,
                                   List<PullTaskStandardFileResultVO> fileResults,
                                   int remainingLinkCount,
                                   int ignoredFileCount) {
        List<PullTaskStandardExecutionRowVO> rows = executionMapper.selectByTaskId(draft.getId())
                .stream()
                .map(PullTaskStandardDraftServiceImpl::toRowView)
                .toList();
        return new PullTaskStandardDraftVO(draft.getId(), draft.getVersion(), rows,
                linkLines, fileResults, rows.size(), remainingLinkCount, ignoredFileCount);
    }

    /**
     * 执行行实体转出参。
     *
     * @param row 执行行实体
     * @return 执行行出参
     */
    private static PullTaskStandardExecutionRowVO toRowView(PullTaskGroupExecution row) {
        return new PullTaskStandardExecutionRowVO(row.getId(), row.getSeq(),
                row.getNormalizedLink(), row.getSourceLinkLineNo(), row.getSourceFileName(),
                row.getTotalLineCount(), row.getValidMemberCount(),
                row.getInvalidLineCount(), row.getDuplicateLineCount());
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardDraftServiceReadEditTest' test
```

预期：7 个测试全部 PASS。

- [ ] **Step 7: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandard*.java \
        armada-api/src/main/java/com/armada/task/service/PullTaskStandardDraftService.java \
        armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java \
        armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServiceReadEditTest.java
git commit -m "feat: 新增创建页草稿出参与回读、单行移除、清除全部编排

草稿不存在时 current 返回空视图而不是 404,首次打开创建页是正常状态。
编排服务不标 @Transactional,写操作全部委托给事务写入组件,为后续
在事务外做邀请页预检留出边界。"
```

---

## Task 10: `plan` —— 上传校验、预检、增量匹配与追加

创建页唯一的写资源入口。串起 Task 1（TXT 解析）、Task 2（匹配）、Task 4（六态判定）、Task 6（占用查询）、Task 8（事务写入）。

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/service/PullTaskStandardDraftService.java`（加 `plan`）
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServicePlanTest.java`

**Interfaces:**
- Produces: 供 Task 11 使用
  - `PullTaskStandardDraftService#plan(String linksText, List<MultipartFile> files, long userId, String operatorName) -> PullTaskStandardDraftVO`

### 执行顺序（顺序本身就是约束）

1. 校验并解析上传文件 —— **事务外**，纯 CPU
2. `ensureDraft` —— 小事务，在 writer 里
3. 读已有执行行，得到已成行链接与当前最大 seq —— 事务外只读
4. `candidateLinks` → `selectOccupiedLinks` —— 事务外只读
5. `probe` —— **事务外**，最坏 40 秒外部 HTTP
6. 剩余链接池 = 池内链接 − 已成行链接
7. `PullTaskLinkMatcher.match` —— 纯函数
8. `writer.append` —— 事务

第 5 步是整条链路里唯一的外部依赖，必须在事务之外。编排服务本身不标 `@Transactional`，就是为了让这条约束在结构上无法被违反。

### `source_file_index` 取 `seq`

`uq_pull_task_execution_file (tenant_id, task_id, source_file_index)` 要求它在任务内唯一。增量上传没有全局上传序号可用（第二次调用的"第 0 个文件"会和第一次撞车），所以取与 `seq` 相同的值——它表达的是"第几个进入计划的文件"。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServicePlanTest.java`：

```java
package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageMetadata;
import com.armada.group.service.GroupInvitePageProbe;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionRowVO;
import com.armada.task.model.vo.PullTaskStandardFileResultVO;
import com.armada.task.model.vo.PullTaskStandardLinkLineVO;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.multipart.MultipartFile;

/** 创建页匹配追加流程的 H2 集成测试；邀请页抓取全部 mock，不出网。 */
@SpringJUnitConfig(PullTaskStandardDraftServicePlanTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardDraftServicePlanTest {

    private static final long CREATOR = 501L;
    private static final String OPERATOR = "运营甲";
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";
    private static final String LINK_C = "chat.whatsapp.com/CCCCCCCCCCCCCCCCCCCCCC";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardDraftService service;

    @Autowired
    private GroupInvitePageFetcher fetcher;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskMaterialMemberMapper materialMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        when(fetcher.probe(anyString())).thenAnswer(invocation ->
                reachableWithProfile(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createsOneRowPerMatchAndReportsRemainingLinks() {
        PullTaskStandardDraftVO view = service.plan(
                LINK_A + "\n" + LINK_B, List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        assertThat(view.draftTaskId()).isNotNull();
        assertThat(view.matchedCount()).isEqualTo(1);
        assertThat(view.remainingLinkCount()).isEqualTo(1);
        assertThat(view.ignoredFileCount()).isZero();
        assertThat(view.rows()).singleElement()
                .satisfies(row -> assertThat(row.sourceFileName()).isEqualTo("a.txt"));
    }

    @Test
    void appendsIncrementallyWithoutDisturbingExistingRows() {
        service.plan(LINK_A, List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        PullTaskStandardDraftVO view = service.plan(
                LINK_A + "\n" + LINK_B, List.of(txt("b.txt", "8613800138002\n")), CREATOR, OPERATOR);

        // LINK_A 已成行，不参与第二轮随机；已有行的 seq 与文件都不变。
        assertThat(view.rows()).extracting(
                        PullTaskStandardExecutionRowVO::seq,
                        PullTaskStandardExecutionRowVO::normalizedLink,
                        PullTaskStandardExecutionRowVO::sourceFileName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, LINK_A, "a.txt"),
                        org.assertj.core.groups.Tuple.tuple(2, LINK_B, "b.txt"));
    }

    @Test
    void ignoresTrailingFilesWhenRemainingLinksRunOut() {
        PullTaskStandardDraftVO view = service.plan(LINK_A,
                List.of(txt("a.txt", "8613800138001\n"), txt("b.txt", "8613800138002\n")),
                CREATOR, OPERATOR);

        assertThat(view.matchedCount()).isEqualTo(1);
        assertThat(view.ignoredFileCount()).isEqualTo(1);
    }

    @Test
    void rejectsZeroValidFileFromThePoolButStillReportsIt() {
        PullTaskStandardDraftVO view = service.plan(LINK_A,
                List.of(txt("empty.txt", "abc\n\n")), CREATOR, OPERATOR);

        assertThat(view.matchedCount()).isZero();
        assertThat(view.fileResults()).singleElement().satisfies(file -> {
            assertThat(file.accepted()).isFalse();
            assertThat(file.rejectReason()).isNotBlank();
            assertThat(file.invalidLineCount()).isEqualTo(1);
        });
    }

    @Test
    void persistsMembersWithAdminFlagAndLineNumbers() {
        service.plan(LINK_A, List.of(txt("a.txt", "8613800138001\n8613800138002A\n")),
                CREATOR, OPERATOR);

        long rowId = executionMapper.selectByTaskId(
                service.current(CREATOR).draftTaskId()).get(0).getId();
        assertThat(materialMapper.selectByExecution(rowId)).extracting(
                        PullTaskMaterialMember::getMemberSeq,
                        PullTaskMaterialMember::getSourceLineNo,
                        PullTaskMaterialMember::getNormalizedPhone,
                        PullTaskMaterialMember::getAdminRequired)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 1, "8613800138001", 0),
                        org.assertj.core.groups.Tuple.tuple(2, 2, "8613800138002", 1));
    }

    @Test
    void keepsExpiredLinkOutOfThePool() {
        when(fetcher.probe(LINK_A)).thenReturn(reachableWithoutProfile(LINK_A));

        PullTaskStandardDraftVO view = service.plan(LINK_A + "\n" + LINK_B,
                List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        assertThat(view.linkLines()).extracting(PullTaskStandardLinkLineVO::status)
                .containsExactly(PullTaskStandardLinkLineStatus.LINK_EXPIRED,
                        PullTaskStandardLinkLineStatus.VALID);
        assertThat(view.rows()).singleElement()
                .satisfies(row -> assertThat(row.normalizedLink()).isEqualTo(LINK_B));
    }

    @Test
    void marksLinkOccupiedByAnotherRunningTask() {
        service.plan(LINK_A, List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);
        executionMapper.freezeDraftRows(service.current(CREATOR).draftTaskId(), 900L);

        PullTaskStandardDraftVO view = service.plan(LINK_A + "\n" + LINK_C,
                List.of(txt("c.txt", "8613800138003\n")), 602L, "运营乙");

        assertThat(view.linkLines()).extracting(PullTaskStandardLinkLineVO::status)
                .containsExactly(PullTaskStandardLinkLineStatus.OCCUPIED,
                        PullTaskStandardLinkLineStatus.VALID);
        assertThat(view.rows()).singleElement()
                .satisfies(row -> assertThat(row.normalizedLink()).isEqualTo(LINK_C));
    }

    @Test
    void rejectsNonTxtUpload() {
        assertThatThrownBy(() -> service.plan(LINK_A,
                List.of(new MockMultipartFile("files", "a.csv", "text/csv",
                        "8613800138001".getBytes(StandardCharsets.UTF_8))), CREATOR, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(".txt");
    }

    @Test
    void rejectsTooManyFiles() {
        List<MultipartFile> files = java.util.stream.IntStream.rangeClosed(0, 50)
                .mapToObj(index -> txt("f" + index + ".txt", "8613800138001\n"))
                .map(MultipartFile.class::cast)
                .toList();

        assertThatThrownBy(() -> service.plan(LINK_A, files, CREATOR, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50");
    }

    @Test
    void rejectsBinaryContentEvenWithTxtExtension() {
        MockMultipartFile binary = new MockMultipartFile("files", "a.txt", "text/plain",
                new byte[] {0x00, 0x01, 0x02});

        assertThatThrownBy(() -> service.plan(LINK_A, List.of(binary), CREATOR, OPERATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void planWithNoFilesOnlyReportsLinkJudgementWithoutCreatingRows() {
        PullTaskStandardDraftVO view = service.plan(LINK_A, List.of(), CREATOR, OPERATOR);

        assertThat(view.draftTaskId()).isNotNull();
        assertThat(view.rows()).isEmpty();
        assertThat(view.linkLines()).hasSize(1);
        assertThat(view.remainingLinkCount()).isEqualTo(1);
    }

    private static MockMultipartFile txt(String fileName, String content) {
        return new MockMultipartFile("files", fileName, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static GroupInvitePageProbe reachableWithProfile(String link) {
        return new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(link), "真实群名", null), true);
    }

    private static GroupInvitePageProbe reachableWithoutProfile(String link) {
        return new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(link), null, null), true);
    }

    private static String inviteCode(String link) {
        return link.substring(link.lastIndexOf('/') + 1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_plan_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean
        GroupInvitePageFetcher invitePageFetcher() {
            return mock(GroupInvitePageFetcher.class);
        }

        @Bean
        PullTaskLinkProbeService probeService(GroupInvitePageFetcher fetcher) {
            // 同步执行器让并发路径在测试里变确定。
            return new PullTaskLinkProbeService(fetcher, Runnable::run);
        }

        @Bean
        PullTaskMaterialTxtParser txtParser() {
            return new PullTaskMaterialTxtParser();
        }

        @Bean
        PullTaskStandardDraftWriter writer(PullTaskMapper pullTaskMapper,
                                           PullTaskGroupExecutionMapper executionMapper,
                                           PullTaskMaterialMemberMapper materialMapper) {
            return new PullTaskStandardDraftWriter(pullTaskMapper, executionMapper, materialMapper);
        }

        @Bean
        PullTaskStandardDraftService draftService(PullTaskMapper pullTaskMapper,
                                                  PullTaskGroupExecutionMapper executionMapper,
                                                  PullTaskStandardDraftWriter writer,
                                                  PullTaskMaterialTxtParser txtParser,
                                                  PullTaskLinkProbeService probeService) {
            return new PullTaskStandardDraftServiceImpl(
                    pullTaskMapper, executionMapper, writer, txtParser, probeService);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardDraftServicePlanTest' test
```

预期：编译失败，`plan` 方法与五参构造器不存在。

- [ ] **Step 3: 给接口加 `plan`**

在 `PullTaskStandardDraftService` 的 `current` 之前插入（并补 `import java.util.List;`、`import org.springframework.web.multipart.MultipartFile;`）：

```java
    /**
     * 解析本次粘贴的链接与上传的 TXT，把新配对增量追加到草稿。
     *
     * <p>链接框文本每次请求全量携带，服务端用"有效链接 − 已成行链接"得到剩余链接池；
     * 剩余链接不足时多出的 TXT 当场拒绝并计入 {@code ignoredFileCount}，由前端保留文件对象、
     * 待用户补粘链接后重发。已成行的执行行不参与重新随机。</p>
     *
     * @param linksText    创建页链接框的全量文本，允许为空
     * @param files        本次新增的 .txt 料子文件，允许为空
     * @param userId       当前登录用户 ID
     * @param operatorName 操作员展示名快照，建草稿时写入
     * @return 追加后的完整草稿视图
     * @throws BusinessException 文件数、大小、扩展名或有效链接数超限时
     */
    PullTaskStandardDraftVO plan(String linksText, List<MultipartFile> files,
                                 long userId, String operatorName);
```

- [ ] **Step 4: 实现 `plan`**

修改 `PullTaskStandardDraftServiceImpl`：构造器补两个依赖，并新增下列常量与方法。完整补丁内容如下。

新增 import：

```java
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.service.PullTaskLinkMatcher;
import com.armada.task.service.PullTaskLinkMatcher.MatchResult;
import com.armada.task.service.PullTaskLinkMatcher.Pairing;
import com.armada.task.service.PullTaskLinkProbeService;
import com.armada.task.service.PullTaskLinkProbeService.LinkLine;
import com.armada.task.service.PullTaskLinkProbeService.ProbeResult;
import com.armada.task.service.PullTaskMaterialTxtParser;
import com.armada.task.service.PullTaskMaterialTxtParser.ParseResult;
import com.armada.task.service.PullTaskMaterialTxtParser.ParsedMember;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.multipart.MultipartFile;
```

新增常量：

```java
    /** 单次上传允许的最大文件数。 */
    private static final int MAX_FILE_COUNT = 50;

    /** 单个文件允许的最大字节数。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** 唯一接受的料子文件扩展名。 */
    private static final String TXT_SUFFIX = ".txt";

    /** 零有效号码文件的拒绝原因。 */
    private static final String ZERO_VALID_REASON = "文件内没有有效号码，请修正后重新上传";
```

构造器改为五参：

```java
    public PullTaskStandardDraftServiceImpl(PullTaskMapper pullTaskMapper,
                                            PullTaskGroupExecutionMapper executionMapper,
                                            PullTaskStandardDraftWriter writer,
                                            PullTaskMaterialTxtParser txtParser,
                                            PullTaskLinkProbeService probeService) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.writer = writer;
        this.txtParser = txtParser;
        this.probeService = probeService;
    }
```

新增方法：

```java
    @Override
    public PullTaskStandardDraftVO plan(String linksText, List<MultipartFile> files,
                                        long userId, String operatorName) {
        // 1. 上传校验与解析：纯 CPU，事务外。
        List<ParsedUpload> uploads = parseUploads(files);

        long now = System.currentTimeMillis();
        PullTask draft = writer.ensureDraft(userId, operatorName, now);
        List<PullTaskGroupExecution> existingRows = executionMapper.selectByTaskId(draft.getId());

        // 2. 占用查询 + 公开邀请页抓取：最坏 40 秒外部 HTTP，绝不能被事务包住。
        ProbeResult probe = probeLinks(linksText);

        Set<String> usedLinks = new LinkedHashSet<>();
        int maxSeq = 0;
        for (PullTaskGroupExecution row : existingRows) {
            usedLinks.add(row.getNormalizedLink());
            maxSeq = Math.max(maxSeq, row.getSeq());
        }
        List<String> remainingLinks = probe.poolLinks().stream()
                .filter(link -> !usedLinks.contains(link))
                .toList();
        List<ParsedUpload> accepted = uploads.stream().filter(ParsedUpload::accepted).toList();

        // 3. 不放回随机匹配，已成行的链接不参与，因此已有执行行不会被扰动。
        MatchResult match = PullTaskLinkMatcher.match(
                remainingLinks, fileKeys(accepted.size()), maxSeq + 1, ThreadLocalRandom.current());
        writer.append(draft.getId(), toAppendRows(match, accepted, lineNoByLink(probe)), now);

        log.info("创建页追加执行行 taskId={} matched={} remainingLinks={} ignoredFiles={} operatorId={}",
                draft.getId(), match.pairings().size(), match.unmatchedLinks().size(),
                match.unmatchedFileKeys().size(), userId);
        return toView(draft, toLinkLineViews(probe), toFileResultViews(uploads),
                match.unmatchedLinks().size(), match.unmatchedFileKeys().size());
    }

    /**
     * 校验并解析本次上传的全部 TXT。
     *
     * @param files 上传文件；null 或空返回空列表
     * @return 逐文件解析结果
     * @throws BusinessException 文件数超限，或任一文件扩展名、大小、内容不合格时
     */
    private List<ParsedUpload> parseUploads(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (files.size() > MAX_FILE_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "单次最多上传 " + MAX_FILE_COUNT + " 个料子文件");
        }
        List<ParsedUpload> uploads = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            uploads.add(parseUpload(file));
        }
        return uploads;
    }

    /**
     * 校验并解析单个 TXT。
     *
     * @param file 上传文件
     * @return 解析结果；零有效号码时带拒绝原因
     * @throws BusinessException 扩展名、大小或内容不合格时
     */
    private ParsedUpload parseUpload(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(TXT_SUFFIX)) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子文件只支持 .txt 格式");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "文件 " + fileName + " 超过 2MB，请拆分后重新上传");
        }
        ParseResult parsed = txtParser.parse(fileName, readUtf8(file, fileName));
        return new ParsedUpload(parsed, parsed.hasValidMember() ? null : ZERO_VALID_REASON);
    }

    /**
     * 按 UTF-8 读出文件内容，并做二进制内容嗅探。
     *
     * <p>只看扩展名挡不住把二进制文件改名成 .txt 上传，用 NUL 字节做最轻量的嗅探。</p>
     *
     * @param file     上传文件
     * @param fileName 原始文件名，用于错误提示
     * @return 文件全文
     * @throws BusinessException 读取失败或内容不是文本时
     */
    private static String readUtf8(MultipartFile file, String fileName) {
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "文件 " + fileName + " 读取失败");
        }
        if (content.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "文件 " + fileName + " 不是纯文本，请上传 UTF-8 编码的 .txt");
        }
        return content;
    }

    /**
     * 先查占用再抓页，避免对已被占用的链接发无谓的外部请求。
     *
     * @param linksText 链接框全量文本
     * @return 逐行判定与匹配池
     */
    private ProbeResult probeLinks(String linksText) {
        Set<String> candidates = PullTaskLinkProbeService.candidateLinks(linksText);
        // foreach 遇空集合会生成非法 SQL，必须在这里判空。
        Set<String> occupied = candidates.isEmpty()
                ? Set.of()
                : Set.copyOf(executionMapper.selectOccupiedLinks(List.copyOf(candidates)));
        return probeService.probe(linksText, occupied);
    }

    /**
     * 生成匹配器用的文件键：已接受文件在列表中的下标。
     *
     * @param acceptedCount 已接受文件数
     * @return 下标字符串列表
     */
    private static List<String> fileKeys(int acceptedCount) {
        List<String> keys = new ArrayList<>(acceptedCount);
        for (int index = 0; index < acceptedCount; index++) {
            keys.add(String.valueOf(index));
        }
        return keys;
    }

    /**
     * 取每条归一化链接首次出现的行号。
     *
     * @param probe 逐行判定结果
     * @return 链接到原始行号的映射
     */
    private static Map<String, Integer> lineNoByLink(ProbeResult probe) {
        Map<String, Integer> lineNos = new LinkedHashMap<>();
        for (LinkLine line : probe.lines()) {
            if (line.normalizedLink() != null) {
                lineNos.putIfAbsent(line.normalizedLink(), line.lineNo());
            }
        }
        return lineNos;
    }

    /**
     * 把匹配结果转成待写入的执行行与料子。
     *
     * @param match        匹配结果
     * @param accepted     已接受的文件，下标与 fileKey 对应
     * @param lineNoByLink 链接到原始行号的映射
     * @return 待写入批次
     */
    private static List<PullTaskStandardDraftWriter.AppendRow> toAppendRows(
            MatchResult match, List<ParsedUpload> accepted, Map<String, Integer> lineNoByLink) {
        List<PullTaskStandardDraftWriter.AppendRow> rows = new ArrayList<>(match.pairings().size());
        for (Pairing pairing : match.pairings()) {
            ParseResult parsed = accepted.get(Integer.parseInt(pairing.fileKey())).parsed();
            rows.add(new PullTaskStandardDraftWriter.AppendRow(
                    toExecution(pairing, parsed, lineNoByLink), toMembers(parsed.members())));
        }
        return rows;
    }

    /**
     * 组装一条草稿执行行。
     *
     * @param pairing      配对结果
     * @param parsed       配对到的 TXT 解析结果
     * @param lineNoByLink 链接到原始行号的映射
     * @return 执行行实体；taskId 与时间戳由写入组件补齐
     */
    private static PullTaskGroupExecution toExecution(Pairing pairing, ParseResult parsed,
                                                      Map<String, Integer> lineNoByLink) {
        String link = pairing.normalizedLink();
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(pairing.seq());
        execution.setNormalizedLink(link);
        execution.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        execution.setSourceLinkLineNo(lineNoByLink.get(link));
        // source_file_index 在任务内唯一。增量上传没有全局上传序号可用，
        // 取与 seq 相同的值，语义是"第几个进入计划的文件"。
        execution.setSourceFileIndex(pairing.seq());
        execution.setSourceFileName(parsed.fileName());
        execution.setTotalLineCount(parsed.totalLineCount());
        execution.setValidMemberCount(parsed.members().size());
        execution.setInvalidLineCount(parsed.invalidLineCount());
        execution.setDuplicateLineCount(parsed.duplicateLineCount());
        return execution;
    }

    /**
     * 解析结果转料子成员实体。
     *
     * @param parsedMembers 去重后的号码
     * @return 料子成员实体；执行行 ID 与时间戳由写入组件补齐
     */
    private static List<PullTaskMaterialMember> toMembers(List<ParsedMember> parsedMembers) {
        List<PullTaskMaterialMember> members = new ArrayList<>(parsedMembers.size());
        for (ParsedMember parsed : parsedMembers) {
            PullTaskMaterialMember member = new PullTaskMaterialMember();
            member.setMemberSeq(parsed.memberSeq());
            member.setSourceLineNo(parsed.sourceLineNo());
            member.setNormalizedPhone(parsed.normalizedPhone());
            member.setAdminRequired(parsed.adminRequired() ? 1 : 0);
            members.add(member);
        }
        return members;
    }

    /**
     * 链接逐行判定转出参。
     *
     * @param probe 判定结果
     * @return 逐行出参
     */
    private static List<PullTaskStandardLinkLineVO> toLinkLineViews(ProbeResult probe) {
        return probe.lines().stream()
                .map(line -> new PullTaskStandardLinkLineVO(line.lineNo(), line.raw(),
                        line.normalizedLink(), line.status(), line.reason()))
                .toList();
    }

    /**
     * 逐文件解析结果转出参。
     *
     * @param uploads 本次上传的解析结果
     * @return 逐文件出参
     */
    private static List<PullTaskStandardFileResultVO> toFileResultViews(List<ParsedUpload> uploads) {
        return uploads.stream().map(upload -> {
            ParseResult parsed = upload.parsed();
            return new PullTaskStandardFileResultVO(parsed.fileName(), upload.accepted(),
                    parsed.members().size(), parsed.invalidLineCount(),
                    parsed.duplicateLineCount(), upload.rejectReason(),
                    parsed.errors().stream()
                            .map(error -> new PullTaskStandardMaterialLineErrorVO(
                                    error.lineNo(), error.reason()))
                            .toList());
        }).toList();
    }

    /**
     * 单个上传文件的解析结果与是否进入匹配池。
     *
     * @param parsed       TXT 解析结果
     * @param rejectReason 未进入匹配池的原因；进入时为 null
     */
    private record ParsedUpload(ParseResult parsed, String rejectReason) {

        /** 是否进入随机匹配池。 */
        private boolean accepted() {
            return rejectReason == null;
        }
    }
```

同时补上字段声明（与既有三个字段并列）：

```java
    private final PullTaskMaterialTxtParser txtParser;
    private final PullTaskLinkProbeService probeService;
```

以及 `import com.armada.task.model.vo.PullTaskStandardMaterialLineErrorVO;`。

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardDraftServicePlanTest+PullTaskStandardDraftServiceReadEditTest' test
```

预期：两个测试类全部 PASS。注意 `PullTaskStandardDraftServiceReadEditTest` 的 `TestConfig` 也要同步改成五参构造器，否则会编译失败——这是本步骤必须一并处理的。

- [ ] **Step 6: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/service/PullTaskStandardDraftService.java \
        armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java \
        armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServicePlanTest.java \
        armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServiceReadEditTest.java
git commit -m "feat: 实现创建页链接与料子的增量匹配追加

顺序固定为解析上传、建/取草稿、查占用、抓邀请页、剩余池匹配、
事务追加;抓页在事务外,编排服务因此不标 @Transactional。已成行
的链接不参与新一轮随机,已有执行行的 seq 与文件保持不变。"
```

---

## Task 11: `PullTaskStandardCreateService` —— 提交冻结

`DRAFT → WAIT_START` 的单事务迁移。这是整个切片最容易写错的地方：占用冲突必须整单回滚，重复提交必须幂等。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskStandardCreateDTO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardCreatedVO.java`
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskStandardCreateService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardCreateServiceTest.java`

**Interfaces:**
- Consumes: Task 5 的 `submitDraft` / `selectLatestDraftByCreator`；Task 7 的 `registerPullTaskTargets`；既有 `freezeDraftRows` / `selectByTaskId` / `PullTaskStandardSettingMapper#insert`；`AccountGroupService#requireExisting`
- Produces: 供 Task 12 使用
  - `PullTaskStandardCreateService#create(PullTaskStandardCreateDTO request, long userId) -> PullTaskStandardCreatedVO`
  - `record PullTaskStandardCreatedVO(Long id, String taskName, String status, Integer groupCount, Integer expectedPullCount)`

### 为什么不复用 `PullTaskListVO`

`PullTaskListVO` 是 21 个字段的一级列表行，含 `MarketingProgress` / `MessageStats` / `ExceptionStats` / `ResourceStats` 四个营销与执行统计嵌套 record。创建接口这一刻没有任何执行事实，填它只能塞 null 或假零值——PRD 第 8 条明确禁止假零值。所以给创建结果单独一个五字段 VO，创建完成后前端走列表接口拿完整行。

### 事务内顺序

1. 取草稿并校验归属、状态、至少一条执行行
2. 校验配置字段与三个账号分组
3. 插 `pull_task_standard_setting`（`required_manager_count` 写 0，启动时才冻结真值）
4. `registerPullTaskTargets` 回填 `group_link_id`
5. `freezeDraftRows` 把 `execution_status` 0→1 —— 生成列 `link_occupancy_key` 此刻生效
6. `submitDraft` 做 `DRAFT → WAIT_START`

### 占用冲突整单回滚

第 5 步撞 `uq_pull_task_execution_link_occupancy` 时抛 `DuplicateKeyException`，捕获后转 `BusinessException(CONFLICT)`，整个事务回滚。**不采用"跳过冲突行、其余继续"**：PRD 硬要求"落库计划与用户在创建页看到的完全一致"，偷偷少一行用户无法察觉。

### 幂等

第 6 步返回 0 说明状态或版本已推进，即已提交过。此时不报错、不建第二个任务，直接回既有任务行。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/service/PullTaskStandardCreateServiceTest.java`。

`TestConfig` 直接照抄 `PullTaskStandardDraftServicePlanTest.TestConfig`（Task 10），做四处改动：

1. 库名换成 `pull_task_create_test`；
2. Mapper XML 列表加上 `"mapper/task/PullTaskStandardSettingMapper.xml"`，并暴露 `PullTaskStandardSettingMapper` bean；
3. 新增两个 mock bean：`AccountGroupService` 与 `GroupLinkRegistryService`。`accountGroupService.requireExisting(anyLong())` 默认回一个带 `name` 的 `AccountGroup`；`groupLinkRegistryService.registerPullTaskTargets(anyList(), anyLong())` 默认按入参链接回自增 ID 映射；
4. 加 `PullTaskStandardCreateServiceImpl` bean。

核心用例：

```java
    @Test
    void submitFreezesRowsWritesSettingAndFlipsTaskToWaitStart() {
        long taskId = seedDraftWithTwoRows();

        service.create(validRequest(taskId, 1), CREATOR);

        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getStatus()).isEqualTo("WAIT_START");
        assertThat(task.getVersion()).isEqualTo(2);
        assertThat(task.getGroupCount()).isEqualTo(2);
        // expected_pull_count 是全部执行行 valid_member_count 之和。
        assertThat(task.getExpectedPullCount()).isEqualTo(2);
        assertThat(executionMapper.selectByTaskId(taskId))
                .allSatisfy(row -> {
                    assertThat(row.getExecutionStatus()).isEqualTo(1);
                    assertThat(row.getGroupLinkId()).isNotNull();
                });
        assertThat(settingMapper.selectByTaskId(taskId).getRequiredManagerCount()).isZero();
    }

    @Test
    void submitRollsBackEntirelyWhenAnyLinkIsAlreadyOccupied() {
        long occupiedTaskId = seedDraftWithTwoRows();
        executionMapper.freezeDraftRows(occupiedTaskId, 800L);
        long taskId = seedDraftWithSameLinksForAnotherUser();

        assertThatThrownBy(() -> service.create(validRequest(taskId, 1), OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);

        // 整单回滚：草稿完整保留，可继续编辑。
        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getStatus()).isEqualTo("DRAFT");
        assertThat(executionMapper.selectByTaskId(taskId))
                .allSatisfy(row -> assertThat(row.getExecutionStatus()).isZero());
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
    }

    @Test
    void repeatedSubmissionReturnsTheSameTaskWithoutCreatingASecondOne() {
        long taskId = seedDraftWithTwoRows();
        service.create(validRequest(taskId, 1), CREATOR);

        PullTaskStandardCreatedVO second = service.create(validRequest(taskId, 1), CREATOR);

        assertThat(second.id()).isEqualTo(taskId);
        assertThat(pullTaskMapper.selectLifecycle(taskId).getVersion()).isEqualTo(2);
    }

    @Test
    void submitIsRejectedWhenDraftHasNoExecutionRow() {
        long taskId = writer.ensureDraft(CREATOR, OPERATOR, 100L).getId();

        assertThatThrownBy(() -> service.create(validRequest(taskId, 1), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群链接");
    }

    @Test
    void submitIsRejectedForAnotherUsersDraft() {
        long taskId = seedDraftWithTwoRows();

        assertThatThrownBy(() -> service.create(validRequest(taskId, 1), OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPullCountRangeWithMinGreaterThanMax() {
        long taskId = seedDraftWithTwoRows();

        assertThatThrownBy(() -> service.create(
                validRequest(taskId, 1).withPullCount(9, 3), CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsGroupThatDoesNotBelongToTenant() {
        long taskId = seedDraftWithTwoRows();
        when(accountGroupService.requireExisting(999L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "账号分组不存在"));

        assertThatThrownBy(() -> service.create(
                validRequest(taskId, 1).withManagerGroup(999L), CREATOR))
                .isInstanceOf(BusinessException.class);
    }
```

`validRequest(taskId, version)` 返回一个填满合法值的 `PullTaskStandardCreateDTO`；`withPullCount` / `withManagerGroup` 是 record 的派生方法，在 DTO 上实现（见 Step 2）。

- [ ] **Step 2: 写 DTO**

创建 `armada-api/src/main/java/com/armada/task/model/dto/PullTaskStandardCreateDTO.java`：

```java
package com.armada.task.model.dto;

/**
 * 普通群链接任务的提交冻结入参。
 *
 * <p>字段口径见设计文档 4.1。审核模式、次管理、退群方式、群资料与权限设置、归档分组等
 * 本期排除项<b>不在本合同内</b>，出现即校验失败——静默忽略会让前端误以为配置已生效。</p>
 *
 * @param draftTaskId          草稿任务 ID
 * @param version              草稿任务乐观锁版本
 * @param taskName             任务名称，1-128 字符
 * @param remark               备注，可空，不超过 512 字符
 * @param autoStart            创建后是否自动启动：0 否 1 是
 * @param materialAdminTiming  料子内管理员设置时点：1 入群后立即 2 本群料子全部终态后
 * @param pullCountMin         单次拉人料子人数下限，不含站台
 * @param pullCountMax         单次拉人料子人数上限，不小于下限
 * @param pullIntervalSeconds  同一拉手连续拉人调用的最小间隔秒数
 * @param pullerCountPerGroup  每条执行行的计划拉手数
 * @param stationCountPerCall  每次拉人调用叠加的站台数
 * @param concurrentGroupCount 同一父任务最大同时运行执行行数
 * @param pullerRiskMinutes    拉手风控冷却分钟；0 表示不建立定时恢复
 * @param managerGroupId       管理账号分组 ID
 * @param pullerGroupId        拉手账号分组 ID
 * @param stationGroupId       站台账号分组 ID
 */
public record PullTaskStandardCreateDTO(Long draftTaskId, Integer version, String taskName,
                                        String remark, Integer autoStart,
                                        Integer materialAdminTiming, Integer pullCountMin,
                                        Integer pullCountMax, Integer pullIntervalSeconds,
                                        Integer pullerCountPerGroup, Integer stationCountPerCall,
                                        Integer concurrentGroupCount, Integer pullerRiskMinutes,
                                        Long managerGroupId, Long pullerGroupId,
                                        Long stationGroupId) {
}
```

> 测试里的 `withPullCount` / `withManagerGroup` 直接在测试类里写成静态工厂即可，**不要**为测试便利在生产 record 上加派生方法。

- [ ] **Step 3: 写服务接口**

创建 `armada-api/src/main/java/com/armada/task/service/PullTaskStandardCreateService.java`：

```java
package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;

/** 普通群链接任务的提交冻结服务。 */
public interface PullTaskStandardCreateService {

    /**
     * 把草稿冻结为待启动任务。
     *
     * <p>单事务内写冻结配置、回填群入口 ID、把执行行推进为待启动、把任务推进为
     * {@code WAIT_START}。不重新随机，落库计划与用户在创建页看到的完全一致。
     * 重复提交返回既有任务而不是报错。</p>
     *
     * @param request 提交入参
     * @param userId  当前登录用户 ID
     * @return 创建完成的任务行
     * @throws BusinessException 草稿不存在或不属于当前用户、无执行行、配置非法、
     *                           分组不存在，或任一链接已被其他任务占用时
     */
    PullTaskStandardCreatedVO create(PullTaskStandardCreateDTO request, long userId);
}
```

创建 `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardCreatedVO.java`：

```java
package com.armada.task.model.vo;

/**
 * 普通群链接任务提交冻结后的结果。
 *
 * <p>刻意不复用 {@code PullTaskListVO}：那是含营销与执行统计的一级列表行，
 * 创建这一刻没有任何执行事实，填它只能塞假零值。前端拿到 id 后走列表接口取完整行。</p>
 *
 * @param id                任务 ID
 * @param taskName          任务名称
 * @param status            当前状态，正常为 {@code WAIT_START}
 * @param groupCount        执行行数
 * @param expectedPullCount 全部执行行的有效料子数之和
 */
public record PullTaskStandardCreatedVO(Long id, String taskName, String status,
                                        Integer groupCount, Integer expectedPullCount) {
}
```

- [ ] **Step 4: 写实现**

创建 `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateServiceImpl.java`。要点（完整代码按下列骨架落地，每个方法都要写 Javadoc）：

```java
@Service
public class PullTaskStandardCreateServiceImpl implements PullTaskStandardCreateService {

    /** 启动时才按管理分组可用账号数冻结 N，创建时先写 0。 */
    private static final int REQUIRED_MANAGER_COUNT_PENDING = 0;

    /** 任务名最大长度。 */
    private static final int TASK_NAME_MAX_LENGTH = 128;

    /** 备注最大长度。 */
    private static final int REMARK_MAX_LENGTH = 512;

    /** 料子内管理员设置时点合法取值：入群后立即。 */
    private static final int ADMIN_TIMING_IMMEDIATE = 1;

    /** 料子内管理员设置时点合法取值：本群料子全部终态后。 */
    private static final int ADMIN_TIMING_AFTER_GROUP_DONE = 2;

    // 依赖：PullTaskMapper、PullTaskGroupExecutionMapper、PullTaskStandardSettingMapper、
    //       GroupLinkRegistryService、AccountGroupService、ObjectMapper

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PullTaskStandardCreatedVO create(PullTaskStandardCreateDTO request, long userId) {
        validate(request);
        PullTask draft = requireOwnDraft(request.draftTaskId(), userId);
        List<PullTaskGroupExecution> rows = executionMapper.selectByTaskId(draft.getId());
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少需要一条群链接与 TXT 的匹配");
        }

        settingMapper.insert(toSetting(request, draft.getId()));
        fillGroupLinkIds(rows);
        freezeRows(draft.getId());
        return submit(draft, request, rows);
    }

    /**
     * 推进执行行为待启动；此刻生成列 link_occupancy_key 生效，跨任务占用开始。
     *
     * @param taskId 草稿任务 ID
     * @throws BusinessException 任一链接已被其他任务占用时，整个事务回滚
     */
    private void freezeRows(long taskId) {
        try {
            executionMapper.freezeDraftRows(taskId, System.currentTimeMillis());
        } catch (DuplicateKeyException e) {
            // 不做"跳过冲突行、其余继续"：PRD 要求落库计划与创建页所见完全一致，
            // 偷偷少落一行用户无法察觉。
            log.warn("提交冻结时群链接被其他任务占用 taskId={}", taskId, e);
            throw new BusinessException(ErrorCode.CONFLICT,
                    "有群链接已被其他任务占用，请移除冲突行后重试");
        }
    }

    /**
     * 推进任务状态；影响行数为 0 说明已提交过，走幂等分支回既有任务。
     */
    private PullTaskStandardCreatedVO submit(PullTask draft, PullTaskStandardCreateDTO request,
                                             List<PullTaskGroupExecution> rows) {
        PullTask update = new PullTask();
        update.setId(draft.getId());
        update.setTaskName(request.taskName().trim());
        update.setRemark(request.remark());
        update.setConfigJson(toConfigJson(request));
        update.setGroupCount(rows.size());
        update.setExpectedPullCount(rows.stream()
                .mapToInt(PullTaskGroupExecution::getValidMemberCount).sum());
        if (pullTaskMapper.submitDraft(update, request.version(), System.currentTimeMillis()) == 0) {
            log.info("普通群链接任务重复提交，返回既有任务 taskId={}", draft.getId());
        }
        PullTask saved = pullTaskMapper.selectLifecycle(draft.getId());
        return new PullTaskStandardCreatedVO(saved.getId(), saved.getTaskName(),
                saved.getStatus(), update.getGroupCount(), update.getExpectedPullCount());
    }
}
```

其余方法：

- `validate(request)` —— 逐项手写校验，全部抛 `BusinessException(ErrorCode.VALIDATION, ...)`：`draftTaskId` / `version` 非空；`taskName` 去空后 1–128；`remark` ≤512；`autoStart` ∈ {0,1}；`materialAdminTiming` ∈ {1,2}；`pullCountMin` ≥1 且 ≤ `pullCountMax`；`pullIntervalSeconds` ≥0；`pullerCountPerGroup` ≥1；`stationCountPerCall` ≥0；`concurrentGroupCount` ≥1；`pullerRiskMinutes` ≥0；三个分组 ID 非空。方法超过 100 行就按"数值区间"和"枚举取值"拆两个私有方法。
- `requireOwnDraft(taskId, userId)` —— 走 `selectLatestDraftByCreator(userId)`，比对 `id` 是否等于 `taskId`，不等或为 null 抛 `NOT_FOUND`。这一步同时完成归属校验，不需要额外查询。
- `toSetting(request, taskId)` —— 组装 `PullTaskStandardSetting`；三个分组名称快照取自 `accountGroupService.requireExisting(id).getName()`，`requiredManagerCount` 写 `REQUIRED_MANAGER_COUNT_PENDING`。
- `fillGroupLinkIds(rows)` —— 调 `groupLinkRegistryService.registerPullTaskTargets(links, now)` 拿到映射后，逐行 `executionMapper.updateGroupLinkId(row.getId(), id)`。**这个 Mapper 方法当前不存在，本任务一并新增**（XML：`UPDATE pull_task_group_execution SET group_link_id = #{groupLinkId}, updated_at = #{now} WHERE id = #{id} AND execution_status = 0`）。
- `toConfigJson(request)` —— `objectMapper.writeValueAsString(request)`，`JsonProcessingException` 转 `BusinessException(VALIDATION, "任务配置序列化失败")`。

- [ ] **Step 5: 补 `updateGroupLinkId` Mapper 方法与 XML**

接口（`PullTaskGroupExecutionMapper`，放在 `freezeDraftRows` 之前）：

```java
    /**
     * 回填执行行的群入口 ID。
     *
     * <p>只在提交冻结的事务里调用，带 {@code execution_status = 0} 守卫保证不改已冻结行。</p>
     *
     * @param id          执行行 ID
     * @param groupLinkId 群入口 ID
     * @param now         更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int updateGroupLinkId(@Param("id") long id, @Param("groupLinkId") long groupLinkId,
                          @Param("now") long now);
```

XML：

```xml
  <update id="updateGroupLinkId">
    UPDATE pull_task_group_execution
    SET group_link_id = #{groupLinkId},
        updated_at = #{now}
    WHERE id = #{id}
      AND execution_status = 0
  </update>
```

- [ ] **Step 6: 校验 XML 并运行测试**

```bash
cd /mnt/d/ideaProject/armada
xmllint --noout armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml
cd armada-api && mvn -Dtest='PullTaskStandardCreateServiceTest+PullTaskDraftEditMapperInMemoryTest' test
```

预期：两个测试类全部 PASS。特别确认 `submitRollsBackEntirelyWhenAnyLinkIsAlreadyOccupied` 是绿的——它是本切片最关键的一条断言。

> H2 在 MySQL 模式下对生成列 + 唯一键的支持已由数据层切片的 `PullTaskGroupExecutionMapperInMemoryTest` 验证过；若本测试里唯一键没触发，先跑那个测试确认基座，再怀疑业务代码。

- [ ] **Step 7: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/model/dto/PullTaskStandardCreateDTO.java \
        armada-api/src/main/java/com/armada/task/service/PullTaskStandardCreateService.java \
        armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateServiceImpl.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml \
        armada-api/src/test/java/com/armada/task/service/PullTaskStandardCreateServiceTest.java
git commit -m "feat: 实现普通群链接任务的提交冻结

单事务完成配置落库、群入口回填、执行行冻结与 DRAFT->WAIT_START。
占用冲突整单回滚而不是跳过冲突行,保证落库计划与创建页所见一致;
重复提交由状态守卫和乐观锁挡住,返回既有任务而不是报错。"
```

---

## Task 12: `PullTaskStandardController` —— 五个端点

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/controller/PullTaskStandardController.java`
- Test: `armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java`

**Interfaces:**
- Consumes: Task 9/10 的 `PullTaskStandardDraftService`、Task 11 的 `PullTaskStandardCreateService`

Controller 只做参数接收、身份衔接与响应组装，业务规则全在 Service（编码规范 Spring 准则第 2 条）。多文件入参沿用 `GroupLinkController` 的写法：`@RequestParam(value = "files", required = false) MultipartFile[] files`。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java`，用 Mockito mock 两个 Service 直接调 Controller 方法（与既有 `PullTaskGroupMarketingSettingControllerTest` 同风格，不起 MockMvc）：

```java
package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.service.PullTaskStandardCreateService;
import com.armada.task.service.PullTaskStandardDraftService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** 普通群链接创建接口的参数衔接测试。 */
class PullTaskStandardControllerTest {

    private static final PullTaskStandardDraftVO EMPTY_VIEW = new PullTaskStandardDraftVO(
            1L, 1, List.of(), List.of(), List.of(), 0, 0, 0);

    private PullTaskStandardDraftService draftService;
    private PullTaskStandardCreateService createService;
    private PullTaskStandardController controller;

    @BeforeEach
    void setUp() {
        draftService = mock(PullTaskStandardDraftService.class);
        createService = mock(PullTaskStandardCreateService.class);
        controller = new PullTaskStandardController(draftService, createService);
    }

    @Test
    void planPassesEmptyListWhenNoFileUploaded() {
        when(draftService.plan(anyString(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan("chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA", null, principal("小王", "wang"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(draftService).plan(anyString(), captor.capture(), anyLong(), anyString());
        // 禁止把 null 透传进 Service，空列表才是"本次没传文件"的正确表达。
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void planForwardsUploadedFilesInOrder() {
        when(draftService.plan(any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);
        MultipartFile[] files = {txt("a.txt"), txt("b.txt")};

        controller.plan(null, files, principal("小王", "wang"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(draftService).plan(any(), captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue()).extracting(MultipartFile::getOriginalFilename)
                .containsExactly("a.txt", "b.txt");
    }

    @Test
    void planUsesNicknameAsOperatorName() {
        when(draftService.plan(any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan(null, null, principal("小王", "wang"));

        verify(draftService).plan(null, List.of(), 501L, "小王");
    }

    @Test
    void planFallsBackToUsernameWhenNicknameIsBlank() {
        when(draftService.plan(any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan(null, null, principal("  ", "wang"));

        verify(draftService).plan(null, List.of(), 501L, "wang");
    }

    @Test
    void draftRemoveRowAndClearDelegateWithCurrentUserId() {
        when(draftService.current(501L)).thenReturn(EMPTY_VIEW);
        when(draftService.removeRow(9L, 501L)).thenReturn(EMPTY_VIEW);
        when(draftService.clear(501L)).thenReturn(EMPTY_VIEW);

        assertThat(controller.draft(principal("小王", "wang")).data()).isEqualTo(EMPTY_VIEW);
        assertThat(controller.removeRow(9L, principal("小王", "wang")).data())
                .isEqualTo(EMPTY_VIEW);
        assertThat(controller.clear(principal("小王", "wang")).data()).isEqualTo(EMPTY_VIEW);
    }

    @Test
    void createDelegatesRequestAndUserId() {
        PullTaskStandardCreateDTO request = new PullTaskStandardCreateDTO(
                1L, 1, "任务", null, 0, 1, 3, 8, 30, 2, 2, 1, 0, 11L, 12L, 13L);
        PullTaskStandardCreatedVO created =
                new PullTaskStandardCreatedVO(1L, "任务", "WAIT_START", 2, 20);
        when(createService.create(request, 501L)).thenReturn(created);

        assertThat(controller.create(request, principal("小王", "wang")).data())
                .isEqualTo(created);
    }

    private static MockMultipartFile txt(String fileName) {
        return new MockMultipartFile("files", fileName, "text/plain",
                "8613800138001".getBytes(StandardCharsets.UTF_8));
    }

    private static AuthPrincipal principal(String nickname, String username) {
        return new AuthPrincipal(501L, 7L, username, nickname, "T001", "租户一",
                List.of(), List.of("tenant:pull_task:create"));
    }
}
```


- [ ] **Step 2: 运行测试确认失败**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardControllerTest' test
```

- [ ] **Step 3: 写 Controller**

```java
package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.service.PullTaskStandardCreateService;
import com.armada.task.service.PullTaskStandardDraftService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 普通群链接拉群任务的创建接口。
 *
 * <p>与旧 {@code POST /api/pull-tasks}（只保存不透明 JSON 快照的 OLD_LINK / CREATE_NEW）
 * 完全隔离；等前端切换完成后由单独一个变更下线旧接口。</p>
 */
@RestController
@RequestMapping("/api/pull-tasks/standard")
@PreAuthorize("hasAuthority('tenant:pull_task:view')")
public class PullTaskStandardController {

    private final PullTaskStandardDraftService draftService;
    private final PullTaskStandardCreateService createService;

    /**
     * 创建普通群链接任务创建接口。
     *
     * @param draftService  草稿编排服务
     * @param createService 提交冻结服务
     */
    public PullTaskStandardController(PullTaskStandardDraftService draftService,
                                      PullTaskStandardCreateService createService) {
        this.draftService = draftService;
        this.createService = createService;
    }

    /**
     * 解析本次粘贴的链接与上传的 TXT，把新配对增量追加到草稿。
     *
     * @param linksText 链接框全量文本，每次请求都要带
     * @param files     本次新增的 .txt 料子文件，可为空
     * @param principal 当前可信登录身份
     * @return 追加后的完整草稿视图
     */
    @PostMapping("/draft/plan")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardDraftVO> plan(
            @RequestParam(value = "linksText", required = false) String linksText,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.plan(linksText, toList(files),
                principal.userId(), displayName(principal)));
    }

    /**
     * 回读当前用户的草稿。
     *
     * @param principal 当前可信登录身份
     * @return 草稿视图；没有草稿时 draftTaskId 为 null
     */
    @GetMapping("/draft")
    public ApiResponse<PullTaskStandardDraftVO> draft(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.current(principal.userId()));
    }

    /**
     * 移除草稿中的单条执行行。
     *
     * @param rowId     执行行 ID
     * @param principal 当前可信登录身份
     * @return 移除后的草稿视图
     */
    @DeleteMapping("/draft/rows/{rowId}")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardDraftVO> removeRow(
            @PathVariable Long rowId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.removeRow(rowId, principal.userId()));
    }

    /**
     * 清空草稿中的全部执行行与料子。
     *
     * @param principal 当前可信登录身份
     * @return 清空后的草稿视图
     */
    @DeleteMapping("/draft")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardDraftVO> clear(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.clear(principal.userId()));
    }

    /**
     * 把草稿提交为待启动任务。
     *
     * @param request   提交入参
     * @param principal 当前可信登录身份
     * @return 创建完成的任务行
     */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardCreatedVO> create(
            @RequestBody PullTaskStandardCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(createService.create(request, principal.userId()));
    }

    /**
     * 把可空的文件数组收敛为不可变列表。
     *
     * @param files 上传文件数组，可为 null
     * @return 文件列表；无文件时为空列表而不是 null
     */
    private static List<MultipartFile> toList(MultipartFile[] files) {
        return files == null ? List.of() : List.of(files);
    }

    /**
     * 取操作员展示名快照。
     *
     * @param principal 当前可信登录身份
     * @return 昵称；昵称为空时退回登录名
     */
    private static String displayName(AuthPrincipal principal) {
        String nickname = principal.nickname();
        return nickname == null || nickname.isBlank() ? principal.username() : nickname;
    }
}
```

- [ ] **Step 4: 确认权限点已存在**

```bash
grep -rn "tenant:pull_task:create" armada-api/src/main/resources/db/migration/ | head
```

期望能查到该权限点的种子数据。若查不到，说明权限点尚未注册，需要在本任务补一条 Flyway 迁移插入权限点——**不要**改用已存在的其他权限点绕过。

- [ ] **Step 5: 运行测试**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn -Dtest='PullTaskStandardControllerTest+PullTaskPermissionContractTest' test
```

预期：两个测试类 PASS。`PullTaskPermissionContractTest` 是既有的权限契约回归。

- [ ] **Step 6: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add armada-api/src/main/java/com/armada/task/controller/PullTaskStandardController.java \
        armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java
git commit -m "feat: 新增普通群链接任务创建接口五个端点

走独立路径 /api/pull-tasks/standard,与旧 POST /api/pull-tasks 完全
隔离,前端切换完成后再单独下线旧接口。"
```

---

## Task 13: 全量回归、change 记录与数据模型文档

- [ ] **Step 1: 跑全量测试**

```bash
cd /mnt/d/ideaProject/armada/armada-api && mvn test
```

预期：BUILD SUCCESS。有失败先修，不得跳过。特别关注三类回归：`GroupLinkPrecheckServiceImplTest`（Task 3 改了抓取实现）、`GroupLinkRegistryServiceImplTest`（Task 7 改了 `registerOne` 签名）、`PullTaskListServiceTest` 与 `PullTaskMapperInMemoryTest`（Task 5 改了 `PullTaskMapper.xml` 与 `PullTask` 实体）。

- [ ] **Step 2: 确认数据模型文档无需重跑**

本切片没有 Flyway 迁移（除非 Task 12 Step 4 发现权限点缺失）。若确实加了迁移：

```bash
cd /mnt/d/ideaProject/armada && python3 .harness/wiki/gen_datamodel.py
```

否则跳过，并在 change 记录里写明"无 schema 变更"。

- [ ] **Step 3: 写 change 记录**

创建 `.harness/changes/pull-task-normal-link/2026-08-03-create-flow.md`（沿用 `_TEMPLATE.md` 结构），至少覆盖：

- **变更概述**：普通群链接任务创建链路（BE-01/04/05/06）。
- **影响模块**：`task` 域新增创建接口与四个服务；`group` 域扩展邀请页端口与群入口登记方法。
- **数据库变更**：无 schema 变更；`PullTask` 实体补 `createdBy` / `configJson` 两个字段映射既有列。
- **API 变更**：新增 `/api/pull-tasks/standard` 五个端点；旧 `POST /api/pull-tasks` 保持不变，待前端切换后单独下线。
- **关键约束**：邀请页预检必须在事务外；占用冲突整单回滚；重复提交幂等；`mode` 取新值 `NORMAL_LINK`，V078 的列注释（`OLD_LINK老群链接 CREATE_NEW自建群`）已过时。
- **回滚方案**：本切片纯代码，无数据变更，回滚 = 回退提交。
- **遗留**：单次粘贴链接量级待确认——现按几十条量级设计（上限 200、16 并发、同步等待最坏约 40 秒）；若实际经常上千条，需改为后台异步检测 + 前端轮询。

- [ ] **Step 4: 提交**

```bash
cd /mnt/d/ideaProject/armada
git add .harness/changes/pull-task-normal-link/2026-08-03-create-flow.md
git commit -m "docs: 补齐普通群链接创建链路的 change 记录"
```

- [ ] **Step 5: 向用户汇报**

汇报内容必须包含：全量测试的**真实输出**（用例数与结果）、新增端点清单、以及仍未确认的链接量级问题。没有真实输出不得声称通过。

---
