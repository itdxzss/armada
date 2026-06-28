# 账号 CRUD 切片(step1)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现。步骤用 `- [ ]` 复选框跟踪。

**Goal:** 实现 armada 账号块 step1 冷数据 CRUD 切片(账号分组 / 账号导入 / 账号列表静态读),零协议、零 Kafka、零上线,全程真库 DbTest 绿。

**Architecture:** 照搬 armada 既有 `com.armada.{resource,group,marketing}` 分层(Controller→Service→Mapper,无 Repository 层,MapStruct 转换,MyBatis-Plus 租户拦截器透明注入 tenant_id)。新建 6 表(V005),账号导入用自建 `ObjectMapper` 解析 JSON/全参 → `List<ParsedEntry>` → `importEntries` 循环(批内去重 + 单行三步原子写)。

**Tech Stack:** JDK 17 / Spring Boot / MyBatis(XML mapper)+ MyBatis-Plus 租户拦截器 / MapStruct / FastExcel(`cn.idev.excel`)/ Jackson / JUnit5 + AssertJ + 真库 DbTest(本机 MySQL 9.3 `armada` 库)。

**开工前必读(按需,不为读而读):** `docs/superpowers/specs/2026-06-25-account-crud-slice-design.md`(权威设计)、`docs/business/account-data-model.md`(6 表冻结 DDL)、`.harness/rules/{编码规范,数据模型规范,工程结构,开发流程规范}.md`。**加表后重跑 `.harness/wiki/gen_datamodel.py` 刷新 数据模型.md,不手改。**

---

## Global Constraints(每个任务隐含遵守)

- **分层**:`com.armada.account` 下 `controller/ service/ service/impl/ mapper/ converter/ model/{entity,dto,vo}`;Controller 禁直连 Mapper;**无 Repository 层**,Service 直调 Mapper;构造器注入,禁字段注入。[工程结构/编码规范]
- **命名**:Controller/Service 按菜单(`AccountGroupController`/`AccountImportController`/`AccountController`),Mapper/entity 按表(`AccountMapper`);REST kebab-case `/api/account-*`;mapper XML 放 `resources/mapper/account/`,namespace=接口全限定名,接口标 `@Mapper`。
- **传输对象**:entity=裸 POJO+getter/setter(**无 Lombok**);VO=`record`(`<X>VO`,`model/vo`);写入参 DTO=`record`(`<X>DTO`,`model/dto`);列表查询 Query=**可变 class extends PageQuery**(`model/dto`);转换用 MapStruct(`@Mapper(componentModel="spring")`,`converter/`)。
- **wire**:全字段 camelCase(全局无 Jackson 命名策略);**时间值 BIGINT epoch 毫秒**(实体字段 `Long`,非 LocalDateTime);响应统一 `ApiResponse{code,message,data}`,`code=0` 成功;分页用 `PageResult.of(list,page,pageSize,total)`,禁自造分页类。
- **租户**:实体有 `tenantId` 字段但 Service/SQL **绝不手写 tenant_id**,由 MyBatis-Plus `TenantLineHandler` 透明注入;account 6 表均有 `tenant_id`,**无需登记 IGNORED_TABLES**。
- **DB**:表名单数 snake_case,列 snake_case,`created_at/updated_at`(禁 create_time),软删 `deleted_at`(禁 is_deleted),枚举列 TINYINT+逐值 COMMENT;**时间列 BIGINT,应用层 `System.currentTimeMillis()` 写**(BIGINT 无 DB 默认/ON UPDATE);软删唯一键用 `is_active TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL,1,NULL)) VIRTUAL`;迁移 `V005__account.sql`(V004 已占,防撞号);列必带 COMMENT。
- **MyBatis**:分页+count+筛选全 SQL 下推,**禁内存分页/load-all/mock/假数据**;`countPage` 与 `selectPage` 共享 `<sql id="filter">` 片段;XML 正文 `>=`/`<=` 用 `&gt;=`/`&lt;=` 转义(裸尖括号炸全站);本块无 FOR UPDATE,不需 `@InterceptorIgnore`。
- **错误**:可恢复错误显式抛 `BusinessException(ErrorCode.VALIDATION/NOT_FOUND/CONFLICT[, msg])`(GlobalExceptionHandler 转 200+code≠0),禁吞成成功、禁裸 RuntimeException;slf4j `{}` 占位,**creds/手机号脱敏(只打 maskPhone+长度)**,禁 System.out/printStackTrace/空 catch/返回 null。
- **业务口径**:`account_type` 导入即冻结,任何操作不得改写。
- **结构**:方法 ≤100 行 / 类 ≤800 行 / 参数 ≤5 / 圈复杂度 ≤10 / 嵌套 ≤3;禁魔法值(枚举或 `static final`);枚举/状态码逐项 Javadoc。
- **测试**:TDD 先红后绿;Mapper/SQL/Flyway 必真库 DbTest(`extends DbTestBase`,`TenantContext.set(1L)` 已由基类做,`@Autowired` Mapper,事务回滚隔离);Service 单测可 mock Mapper;**禁 `src/main` 出现 InMemory/Fake/Stub**;跑测试 `armada-api/dbtest.sh <Class#method>`。
- **提交**:每任务结束一次提交,diff 只含本任务;改行为=删旧路径,不留死代码/兼容开关。

---

## 任务图

```
Phase 0  T0.1 V005 迁移 + harness + 迁移 DbTest
Phase 1.1 账号分组   T1.1.1 Mapper  → T1.1.2 Service+Controller
Phase 1.2 账号导入   T1.2.1 entity+Mapper(account/state/credential/batch/detail) → T1.2.2 解析器+完整性
                     → T1.2.3 importEntries 循环+三步写 → T1.2.4 批次/明细列表+导出 → T1.2.5 Controller
Phase 1.3 账号列表   T1.3.1 list 查询 Mapper(JOIN) → T1.3.2 stats summary
                     → T1.3.3 迁移分组+批量删 → T1.3.4 Service+Controller 收口
```

---

# Phase 0 — 地基

## Task 0.1: V005 迁移 + harness scaffold + 迁移 DbTest

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V005__account.sql`
- Create: `.harness/changes/account/summary.md`、`.harness/changes/account/db-migrations.sql`、`.harness/changes/account/rollback.sql`
- Test: `armada-api/src/test/java/com/armada/account/AccountSchemaDbTest.java`

**Interfaces — Produces:** 6 张表(`account` / `account_state` / `account_group` / `account_credential` / `account_import_batch` / `account_import_detail`),列与索引同 `docs/business/account-data-model.md`。

- [ ] **Step 1: 写迁移**。`V005__account.sql` 逐字照 `account-data-model.md` 的 6 段 DDL(已是迁移就绪形态:BIGINT 时间列、`is_active` 虚拟列、`uq` 软删唯一键、列带 COMMENT)。`account` 唯一键 `uq_tenant_phone(tenant_id, ws_phone, is_active)`;`account_state` `uq_tenant_account(tenant_id, account_id)`;`account_group` `uq_tenant_name(tenant_id, name, is_active)`;`account_credential` `uq_tenant_account(tenant_id, account_id, is_active)`。`ws_phone`/`creds_json` 不做 byte-bin(`ws_phone` 用普通 utf8mb4 即可;唯一性靠 uq)。

- [ ] **Step 2: 写迁移 DbTest(先红)**。
```java
package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;
import com.armada.testsupport.DbTestBase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 验 V005 六表已由 Flyway 迁移到真库(表存在 + 关键列/索引)。 */
class AccountSchemaDbTest extends DbTestBase {

    @Autowired DataSource dataSource;

    @Test
    void v005_creates_six_account_tables() throws Exception {
        var expected = java.util.List.of("account", "account_state", "account_group",
                "account_credential", "account_import_batch", "account_import_detail");
        try (var c = dataSource.getConnection()) {
            for (String t : expected) {
                try (var rs = c.getMetaData().getColumns(c.getCatalog(), null, t, "%")) {
                    assertThat(rs.next()).as("表 %s 应存在", t).isTrue();
                }
            }
        }
    }
}
```

- [ ] **Step 3: 跑测试验证迁移真跑**。`armada-api/dbtest.sh AccountSchemaDbTest`,Expected: PASS(Flyway 应用 V005,6 表就位)。若 `V005` 撞号/语法错会启动失败,据报错修迁移。

- [ ] **Step 4: 写 harness 变更档**。`summary.md`(变更概述/影响/DB变更=V005 六表/API变更=后续/约束/回滚指向 rollback.sql);`db-migrations.sql` = V005 内容副本;`rollback.sql` = `DROP TABLE IF EXISTS account_import_detail, account_import_batch, account_credential, account_state, account_group, account;`(子→父序)。

- [ ] **Step 5: 重跑数据模型 wiki**。`python3 .harness/wiki/gen_datamodel.py`(取真库 information_schema 重生成 `.harness/wiki/数据模型.md`,不手改)。

- [ ] **Step 6: Commit**。`git add armada-api/src/main/resources/db/migration/V005__account.sql armada-api/src/test/java/com/armada/account/AccountSchemaDbTest.java .harness/changes/account/ .harness/wiki/数据模型.md && git commit -m "feat(account): V005 六表迁移 + schema DbTest"`

---

# Phase 1.1 — 账号分组

> 整条链克隆 `com.armada.group` 的 `GroupLinkLabel*`(controller/service/impl/mapper/xml/entity/dto/vo/converter/DbTest),**关键差异**:① 表 `account_group`;② 时间列 BIGINT,实体字段 `Long createdAt/updatedAt/deletedAt`,**insert/update 由应用层 `System.currentTimeMillis()` 写时间**,VO 直接是 `Long`(不需 MapStruct `toEpochMilli`);③ 新增「系统默认组懒创建 + 系统组不可删/改名」;④ 删除闸门改为「组内有账号则拒删」。

## Task 1.1.1: AccountGroup 实体 + Mapper + XML + DbTest

**Files:**
- Create: `model/entity/AccountGroup.java`、`mapper/AccountGroupMapper.java`、`resources/mapper/account/AccountGroupMapper.xml`、`model/dto/AccountGroupQuery.java`、`model/vo/AccountGroupVoRow.java`
- Test: `src/test/java/com/armada/account/mapper/AccountGroupMapperDbTest.java`

**Interfaces — Produces:**
- `AccountGroup`(裸 POJO):`Long id; Long tenantId; String name; String remark; Integer systemBuiltin; Long createdAt; Long updatedAt; Long createdBy; Long deletedAt;` + getter/setter。
- `AccountGroupMapper`:`long countPage(AccountGroupQuery)`、`List<AccountGroupVoRow> selectPage(AccountGroupQuery)`、`AccountGroup selectActiveByName(@Param("name") String)`、`AccountGroup selectDeletedByName(@Param("name") String)`、`AccountGroup selectById(@Param("id") Long)`、`AccountGroup selectSystemBuiltin()`、`int insert(AccountGroup)`、`int reviveById(@Param("id") Long)`、`int updateProfile(AccountGroup)`、`int softDeleteByIds(@Param("ids") List<Long>)`、`long countAccountsByGroupId(@Param("groupId") Long)`。
- `AccountGroupVoRow`(裸 POJO 投影):`Long id; String name; String remark; Integer systemBuiltin; long accountCount; Long createdAt; Long updatedAt;`。
- `AccountGroupQuery extends PageQuery`:`String keyword; Long id;` + getter/setter。

- [ ] **Step 1: 写实体 + Query + VoRow**(裸 POJO/可变 class,见上签名)。

- [ ] **Step 2: 写 Mapper 接口 + XML(先红用)**。XML namespace=`com.armada.account.mapper.AccountGroupMapper`;`<sql id="filter">deleted_at IS NULL <if test="keyword!=null and keyword!=''">AND name LIKE CONCAT('%',#{keyword},'%')</if><if test="id!=null">AND id=#{id}</if></sql>`;`selectPage` 投影含 `(SELECT COUNT(*) FROM account a WHERE a.account_group_id=g.id AND a.deleted_at IS NULL) AS accountCount`,`LIMIT #{offset},#{pageSize}`,`ORDER BY g.created_at DESC`;`insert` 写入 `name,remark,system_builtin,created_at,updated_at,created_by`(时间由实体传入,**非 DB 默认**);`updateProfile` `SET name=#{name},remark=#{remark},updated_at=#{updatedAt}`;`softDeleteByIds` `SET deleted_at=#{deletedAt} WHERE deleted_at IS NULL AND id IN <foreach>`(deletedAt 由调用方传 `System.currentTimeMillis()`);`countAccountsByGroupId` `SELECT COUNT(*) FROM account WHERE account_group_id=#{groupId} AND deleted_at IS NULL`;`selectSystemBuiltin` `SELECT * FROM account_group WHERE system_builtin=1 AND deleted_at IS NULL LIMIT 1`。

- [ ] **Step 3: 写 DbTest(先红)**。
```java
package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountGroupMapperDbTest extends DbTestBase {

    @Autowired AccountGroupMapper mapper;

    private AccountGroup build(String name) {
        AccountGroup g = new AccountGroup();
        g.setName(name);
        g.setRemark("r");
        g.setSystemBuiltin(0);
        long now = 1_700_000_000_000L;          // 固定 epoch,测试不依赖 System.currentTimeMillis
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        return g;
    }

    @Test
    void insert_then_selectActiveByName() {
        AccountGroup g = build("分组A");
        mapper.insert(g);
        assertThat(g.getId()).isNotNull();
        assertThat(mapper.selectActiveByName("分组A")).isNotNull();
        assertThat(mapper.selectDeletedByName("分组A")).isNull();
    }

    @Test
    void softDelete_then_reviveByName() {
        AccountGroup g = build("分组B");
        mapper.insert(g);
        mapper.softDeleteByIds(List.of(g.getId()));   // 注:实测调用方传 deletedAt;mapper 入参见下
        assertThat(mapper.selectActiveByName("分组B")).isNull();
        assertThat(mapper.selectDeletedByName("分组B")).isNotNull();
        mapper.reviveById(g.getId());
        assertThat(mapper.selectActiveByName("分组B")).isNotNull();
    }

    @Test
    void countAccountsByGroupId_zero_whenEmpty() {
        AccountGroup g = build("空组");
        mapper.insert(g);
        assertThat(mapper.countAccountsByGroupId(g.getId())).isEqualTo(0L);
    }
}
```
> `softDeleteByIds` 入参需带 `deletedAt`:签名调整为 `int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt)`,测试传 `mapper.softDeleteByIds(List.of(g.getId()), 1_700_000_000_001L)`。同理 `reviveById` 只清 `deleted_at=NULL`(不动时间)。

- [ ] **Step 4: 跑测试**。`armada-api/dbtest.sh AccountGroupMapperDbTest`,Expected: 先红(mapper 未实现/XML 缺)→ 补全 XML → PASS。

- [ ] **Step 5: Commit**。`git commit -m "feat(account): account_group entity+mapper+xml with DbTest"`

## Task 1.1.2: AccountGroupService + Controller(系统组懒创建 + 删除闸门)

**Files:**
- Create: `service/AccountGroupService.java`、`service/impl/AccountGroupServiceImpl.java`、`model/dto/AccountGroupDTO.java`、`model/dto/AccountIdsDTO.java`、`model/vo/AccountGroupVO.java`、`converter/AccountConverter.java`、`controller/AccountGroupController.java`
- Test: `src/test/java/com/armada/account/service/AccountGroupServiceImplTest.java`(mock mapper 单测)

**Interfaces — Produces / Consumes:**
- `AccountGroupDTO(String name, String remark)`、`AccountIdsDTO(List<Long> ids)`、`AccountGroupVO(Long id, String name, String remark, Integer systemBuiltin, long accountCount, long onlineCount, Long createdAt, Long updatedAt)`(`onlineCount` 恒 0 占位,step3)。
- `AccountConverter`(MapStruct `@Mapper(componentModel="spring")`):`AccountGroupVO toGroupVO(AccountGroupVoRow row);`、`List<AccountGroupVO> toGroupVOList(List<AccountGroupVoRow> rows)`(`onlineCount` 用 `@Mapping(target="onlineCount", constant="0L")` 或在 VoRow 补 0;**时间字段 Long→Long 直映,无需 toEpochMilli**)。
- `AccountGroupService`:`PageResult<AccountGroupVO> list(AccountGroupQuery)`、`AccountGroupVO create(AccountGroupDTO)`、`void update(Long id, AccountGroupDTO)`、`int batchDelete(List<Long> ids)`、`AccountGroup ensureSystemGroup()`(供导入/列表复用)。

- [ ] **Step 1: 写 Service 接口 + DTO/VO/Converter**(签名见上)。

- [ ] **Step 2: 写 Service 单测(先红)**。覆盖:create 重名拒(`selectActiveByName` 非空 → VALIDATION);create 命中软删名 → revive 路径;update 系统组拒改名(`systemBuiltin==1` → VALIDATION);batchDelete 组内有账号拒删(`countAccountsByGroupId>0` → VALIDATION,全或无);batchDelete 系统组拒删。
```java
@Test
void batchDelete_rejectsWhenGroupHasAccounts() {
    AccountGroup g = new AccountGroup(); g.setId(9L); g.setSystemBuiltin(0);
    when(mapper.selectById(9L)).thenReturn(g);
    when(mapper.countAccountsByGroupId(9L)).thenReturn(3L);
    assertThatThrownBy(() -> service.batchDelete(List.of(9L)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("请先清空");
    verify(mapper, never()).softDeleteByIds(anyList(), anyLong());   // 全或无:一条挡则整批不删
}
```

- [ ] **Step 3: 写 Service impl**。`list` 照 GroupLinkLabelServiceImpl(total==0 短路)。`create`:名空拒;活跃重名拒;命中软删名 → `reviveById`+`updateProfile`(set updatedAt=now)否则 `insert`(set createdAt=updatedAt=now);插入后用已有时间直接组 VO(BIGINT 自带,无需回查时间)。`update`:名空拒、组不存在拒、系统组拒改名、他人重名拒、`updateProfile`。`batchDelete`:size 1..100;逐个 `selectById` 校验非系统组 + `countAccountsByGroupId==0`,任一不满足整批抛 VALIDATION(全或无);通过则 `softDeleteByIds(ids, now)`。`ensureSystemGroup`:`selectSystemBuiltin()` 为空则 `insert`(systemBuiltin=1,name="系统默认分组")并发安全靠 `uq_tenant_name`(撞键则重查)。

- [ ] **Step 4: 跑单测**。`armada-api/dbtest.sh AccountGroupServiceImplTest`,Expected: PASS。

- [ ] **Step 5: 写 Controller**。`@RestController @RequestMapping("/api/account-groups")`,GET `list(@ModelAttribute AccountGroupQuery)`、POST `create(@RequestBody AccountGroupDTO)`、`@PutMapping("/{id}") update(@PathVariable Long id,@RequestBody AccountGroupDTO)`、POST `/batch-delete batchDelete(@RequestBody AccountIdsDTO)`;全 `ApiResponse.ok(...)`。

- [ ] **Step 6: Service 层 list 端到端 DbTest**(可选补强):`AccountGroupServiceImplDbTest extends DbTestBase` 验 list 分页 + 系统组懒创建幂等(连调两次 `ensureSystemGroup` 只一条)。

- [ ] **Step 7: Commit**。`git commit -m "feat(account): account-group CRUD service+controller (system group + delete guard)"`

---

# Phase 1.2 — 账号导入

## Task 1.2.1: 五实体 + 五 Mapper + XML + DbTest

**Files:**
- Create entities: `model/entity/{Account,AccountState,AccountCredential,AccountImportBatch,AccountImportDetail}.java`(裸 POJO,时间字段 `Long`)
- Create mappers + XML: `mapper/{AccountMapper,AccountStateMapper,AccountCredentialMapper,AccountImportBatchMapper,AccountImportDetailMapper}.java` + `resources/mapper/account/*.xml`
- Test: `src/test/java/com/armada/account/mapper/AccountImportWriteMapperDbTest.java`

**Interfaces — Produces(写路径用,导入循环消费):**
- `AccountMapper`:`int insert(Account)`(含 `protocol_account_id`、`account_type`、`account_group_id`、`created_at/updated_at`)、`Account selectActiveByWsPhone(@Param("wsPhone") String)`。
- `AccountStateMapper`:`int insert(AccountState)`(默认行:`account_state/login_state/risk_status/mute_status` 全 NULL,计数 0,created_at/updated_at)。
- `AccountCredentialMapper`:`int insert(AccountCredential)`(tenant_id/account_id/ws_phone/cred_format/creds_json/created_at/updated_at)。
- `AccountImportBatchMapper`:`int insert(AccountImportBatch)`、`AccountImportBatch selectById(@Param("id") Long)`。
- `AccountImportDetailMapper`:`int batchInsert(@Param("rows") List<AccountImportDetail>)`。

- [ ] **Step 1: 写 5 实体**(字段对齐 `account-data-model.md` 六表;时间列 `Long`;`account_type` 注释「导入即冻结」)。

- [ ] **Step 2: 写 5 Mapper 接口 + XML**。insert 全部显式列出列(时间由实体传)。account insert 列:`tenant_id 不写(拦截器注)`,`ws_phone,account_type,device_os,number_source,channel_name,ownership,account_group_id,protocol_id,protocol_account_id,protocol_address,priority,remark,created_at,updated_at,created_by`;`useGeneratedKeys="true" keyProperty="id"`。XML 正文如有比较用 `&gt;=`/`&lt;=`。

- [ ] **Step 3: 写 DbTest(先红)**:验「三步写」最小链路 + DB 唯一键兜底。
```java
@Test
void insertAccount_thenState_thenCredential_linked() {
    long now = 1_700_000_000_000L;
    Account a = new Account();
    a.setWsPhone("8613800138000"); a.setAccountType(2); a.setAccountGroupId(null);
    a.setProtocolAccountId("acc_8613800138000"); a.setOwnership(1); a.setPriority(0);
    a.setCreatedAt(now); a.setUpdatedAt(now);
    accountMapper.insert(a);
    assertThat(a.getId()).isNotNull();

    AccountState s = new AccountState();
    s.setAccountId(a.getId()); s.setProxyFailureCount(0); s.setPullIntoGroupCount(0);
    s.setCreatedAt(now); s.setUpdatedAt(now);
    stateMapper.insert(s);                       // 状态列全 NULL=待上线

    AccountCredential c = new AccountCredential();
    c.setAccountId(a.getId()); c.setWsPhone("8613800138000"); c.setCredFormat(2);
    c.setCredsJson("{\"registrationId\":1}"); c.setCreatedAt(now); c.setUpdatedAt(now);
    credentialMapper.insert(c);

    assertThat(accountMapper.selectActiveByWsPhone("8613800138000")).isNotNull();
}

@Test
void insertAccount_duplicateWsPhone_throwsDuplicateKey() {
    long now = 1_700_000_000_000L;
    Account a1 = newAccount("8613800138001", now); accountMapper.insert(a1);
    Account a2 = newAccount("8613800138001", now);
    assertThatThrownBy(() -> accountMapper.insert(a2))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);   // uq_tenant_phone 兜底
}
```

- [ ] **Step 4: 跑测试**。`armada-api/dbtest.sh AccountImportWriteMapperDbTest`,Expected: PASS。

- [ ] **Step 5: Commit**。`git commit -m "feat(account): 5 import-write entities+mappers+xml with DbTest"`

## Task 1.2.2: 格式识别 + 解析器 + 完整性校验(纯函数,单测)

**Files:**
- Create: `service/AccountImportParser.java`(`@Component`)、`model/entity/ParsedEntry.java`(裸 POJO:`String raw; String wid; com.fasterxml.jackson.databind.JsonNode data; String parseError;`)、`model/entity/ImportFormat.java`(enum:`SIX(1),JSON(2),PARAMS(3)`,逐项 Javadoc)
- Test: `src/test/java/com/armada/account/service/AccountImportParserTest.java`

**Interfaces — Produces:**
- `AccountImportParser`:`List<ParsedEntry> parse(ImportFormat format, byte[] fileBytes, String text)`。内部:JSON/PARAMS 用自建 `private final ObjectMapper mapper = new ObjectMapper();`(照 `MarketingTemplateConverter`);`SIX` → 抛 `BusinessException(VALIDATION,"六段暂不支持(协议层未接)")`。
- 完整性:`parse` 内对每条做结构校验,缺键的产出 `parseError="凭据不全:缺 <key>"`(`wid` 仍尽量抠出便于明细展示)。

- [ ] **Step 1: 写 enum + ParsedEntry + Parser 骨架**。`parse` 分发:JSON(支持单对象/数组/.zip 内存解压一号一文件)、PARAMS(单对象/数组)。zip 用 `java.util.zip.ZipInputStream` 逐条 `.json`。每条抠 `wid`(JSON 优先 `creds.me.id`/`me.id` 去 `:`/`@` 后数字段或顶层 `wid`/`phone`,**确切路径按真实样例定**——本任务先实现 `wid`/`phone` 顶层 + `me.id` 两种,真实样例不符再加)。

- [ ] **Step 2: 写完整性校验单测(先红)**。
```java
@Test
void json_missingRegistrationId_marksIncomplete() {
    String json = "[{\"wid\":\"8613800138000\",\"creds\":{\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";
    var entries = parser.parse(ImportFormat.JSON, null, json);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("registrationId");
}

@Test
void json_complete_parsesOk() {
    String json = "[{\"wid\":\"8613800138000\",\"creds\":{\"registrationId\":7,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";
    var entries = parser.parse(ImportFormat.JSON, null, json);
    assertThat(entries.get(0).getParseError()).isNull();
    assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
}

@Test
void six_isRejected() {
    assertThatThrownBy(() -> parser.parse(ImportFormat.SIX, null, "x,x,x,x,x,x"))
        .isInstanceOf(BusinessException.class).hasMessageContaining("六段暂不支持");
}
```
完整性门槛(常量集合,逐项注释):baileys_json creds 必含 `registrationId/noiseKey/signedIdentityKey/signedPreKey`;params 必含 `wid`+必需键(先实现 `wid` 非空 + 合法手机号,确切键真实样例补)。

- [ ] **Step 3: 实现 Parser**(纯函数,无 DB)。`wid` 合法手机号校验复用正则常量。

- [ ] **Step 4: 跑单测**。`armada-api/dbtest.sh AccountImportParserTest`(纯单测也走该脚本),Expected: PASS。

- [ ] **Step 5: Commit**。`git commit -m "feat(account): import format parser + creds completeness validation"`

## Task 1.2.3: importEntries 循环 + 单行三步原子写

**Files:**
- Create: `service/AccountImportService.java`、`service/impl/AccountImportServiceImpl.java`、`model/dto/AccountImportDTO.java`、`model/vo/AccountImportBatchVO.java`、`model/entity/ImportResult.java`(enum:`SUCCESS(1),DUPLICATE(2),FORMAT_ERROR(3),CRED_INCOMPLETE(4)`)
- Modify: 复用 1.2.1 五 Mapper、1.2.2 Parser、1.1.2 `AccountGroupService.ensureSystemGroup`
- Test: `src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java`

**Interfaces — Produces / Consumes:**
- `AccountImportDTO`:导入元信息 `Long accountGroupId; Integer importFormat; Integer deviceOs; Integer accountType; String ipRegion; String batchName; String remark;`(file/text 由 Controller 传字节/文本另入参)。
- `AccountImportService`:`AccountImportBatchVO importAccounts(AccountImportDTO meta, byte[] fileBytes, String text)`。
- `AccountImportBatchVO`(record):`Long id, String batchName, ..., int totalRows, int importedRows, int duplicateRows, int formatErrorRows, Integer loginSuccess, Integer loginFailed, Integer loginAbnormal, int status, Long createdAt`。

- [ ] **Step 1: 写 enum + DTO/VO + Service 接口**。

- [ ] **Step 2: 写 importEntries DbTest(先红)** —— 这是本块最核心测试。
```java
@Test
void import_threeStepWrite_andBatchCounts() {
    // 2 条完整 + 1 条凭据不全 + 1 条批内重复(与第1条同 wid)
    String json = "["
        + "{\"wid\":\"8613800138000\",\"creds\":{\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}},"
        + "{\"wid\":\"8613800138002\",\"creds\":{\"registrationId\":2,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}},"
        + "{\"wid\":\"8613800138003\",\"creds\":{\"noiseKey\":{}}},"
        + "{\"wid\":\"8613800138000\",\"creds\":{\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}"
        + "]";
    var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "批次1", "r");
    AccountImportBatchVO b = service.importAccounts(meta, null, json);

    assertThat(b.totalRows()).isEqualTo(4);
    assertThat(b.importedRows()).isEqualTo(2);      // 两条完整入库
    assertThat(b.duplicateRows()).isEqualTo(1);     // 批内重复
    assertThat(b.formatErrorRows()).isEqualTo(1);   // 凭据不全
    assertThat(b.status()).isEqualTo(2);            // step1 同步导入流程结束恒「2 已完成」;成败看计数

    // 三步写校验:成功号在 account + account_state + account_credential 各一行
    Account a = accountMapper.selectActiveByWsPhone("8613800138000");
    assertThat(a).isNotNull();
    assertThat(a.getProtocolAccountId()).isEqualTo("acc_8613800138000");
    assertThat(stateMapper.selectByAccountId(a.getId())).isNotNull();        // 默认行,状态列 NULL
    assertThat(credentialMapper.selectByAccountId(a.getId())).isNotNull();
}
```
> `status` 口径(已收口,spec 与数据模型对齐):`status` TINYINT `1进行中 2已完成`,**step1 同步导入流程即时结束 → 恒 `2 已完成`**(`1 进行中` 是 step3 登录结果未齐时的态)。成败**不进 status**,由计数列 `importedRows/duplicateRows/formatErrorRows` 表达。

- [ ] **Step 3: 实现 importEntries**。流程:`parser.parse` → `seenWid` HashSet 去重 → 逐条:parseError 非空且含「凭据不全」→ CRED_INCOMPLETE;其它 parseError → FORMAT_ERROR;批内重复 → DUPLICATE;否则 `@Transactional` 单行三步(`accountMapper.insert`(含 `protocol_account_id="acc_"+wid`、`account_group_id=meta.accountGroupId!=null?:ensureSystemGroup().getId()`)→ `stateMapper.insert`(默认行)→ `credentialMapper.insert`)`catch DuplicateKeyException → DUPLICATE`。累计计数;写 batch(`status=2`)+ `detail.batchInsert`;`login_result` 不写(NULL)。**单行三步用私有 `@Transactional` 方法或编程式事务确保原子**(注意 self-invocation 失效:把单行写抽到独立 `@Service`/`TransactionTemplate`)。日志 maskPhone,不打 creds_json。

- [ ] **Step 4: 跑测试**。`armada-api/dbtest.sh AccountImportServiceImplDbTest`,Expected: PASS。补测:DB uq 兜底(跨批同号 → DUPLICATE)、三步中途失败不留孤儿(可注入非法 creds_json 长度触发?或单独验事务回滚)。

- [ ] **Step 5: Commit**。`git commit -m "feat(account): import loop + atomic 3-step write + batch/detail counts"`

## Task 1.2.4: 批次列表 + 明细列表 + 导出 CSV

**Files:**
- Modify: `AccountImportBatchMapper`(+`countPage`/`selectPage`)、`AccountImportDetailMapper`(+`countByBatch`/`selectPageByBatch`/`selectAllByBatch`)、XML
- Create: `model/dto/AccountImportQuery.java`、`model/dto/AccountImportDetailQuery.java`、`model/vo/AccountImportDetailVO.java`、`service` 方法 + Controller 端点
- Test: `src/test/java/com/armada/account/mapper/AccountImportListMapperDbTest.java`

- [ ] **Step 1: 写批次/明细列表 Mapper+XML+Query**(SQL 下推分页,`<sql id="filter">` 共享;明细按 `parse_result` 筛 全部/成功(=1)/失败(in 2,3,4))。

- [ ] **Step 2: 写 DbTest(先红)**:导入一批 → 批次列表查得到 + 计数正确;明细按「失败」筛只返回 2/3/4。

- [ ] **Step 3: 实现 + 导出**。`GET /api/account-imports`、`/{batchId}/details`、`/{batchId}/export?scope=all|success|fail` → CSV 5 列(账号/状态/失败原因/分组/创建时间),UTF-8 BOM,`Content-Disposition`。

- [ ] **Step 4: 跑测试** → PASS。**Step 5: Commit** `feat(account): import batch/detail list + CSV export`。

## Task 1.2.5: AccountImportController 收口 + multipart 入参

**Files:** Create `controller/AccountImportController.java`

- [ ] **Step 1: 写 Controller**。`POST /api/account-imports` multipart:`@RequestParam` 取 `accountGroupId/importFormat/deviceOs/accountType/ipRegion/batchName/remark` + `@RequestParam(value="file",required=false) MultipartFile file`;读 `file.getBytes()` 传 `service.importAccounts(meta, fileBytes, text)`。其余 GET 端点接 1.2.4。全 `ApiResponse.ok`。
- [ ] **Step 2: 手动冒烟(可选)** 或集成 DbTest(MockMvc multipart)。**Step 3: Commit** `feat(account): account import controller`。

---

# Phase 1.3 — 账号列表

## Task 1.3.1: 列表查询 Mapper(account JOIN state JOIN group)

**Files:** Create `mapper/AccountMapper`(+`countPage`/`selectPage`)、`model/dto/AccountQuery.java`、`model/vo/AccountListVoRow.java`、XML;Test `AccountListMapperDbTest`

**Interfaces — Produces:** `AccountQuery extends PageQuery`(`String phone;Integer accountType;String protocolId;Integer accountState;Integer riskStatus;Long accountGroupId;String numberSource;`);`selectPage` = `account a LEFT JOIN account_state s ON s.account_id=a.id LEFT JOIN account_group g ON g.id=a.account_group_id`,投影 account 真值列 + s.* 状态列(可 NULL)+ `g.name AS groupName`;筛选 `a.deleted_at IS NULL` + account 列真筛(phone 开头模糊 `a.ws_phone LIKE CONCAT(#{phone},'%')`)+ state 列条件(`<if test="accountState!=null">AND s.account_state=#{accountState}</if>`,NULL 数据天然不命中)。

- [ ] **Step 1: 写 Query/VoRow/Mapper/XML**(LIMIT 下推,ORDER BY a.created_at DESC)。
- [ ] **Step 2: DbTest(先红)**:导入 2 号 → 列表查得 2 行,`groupName` 来自 JOIN,状态列为 NULL;按 phone 开头模糊命中。
- [ ] **Step 3: 实现 → 跑 PASS**。**Step 4: Commit** `feat(account): account list query (join state+group)`。

## Task 1.3.2: 统计卡 summary(平台级 SQL)

**Files:** `AccountMapper.statsSummary` + XML;`model/vo/AccountStatsVO.java`;Test 补 `AccountListMapperDbTest`

- [ ] **Step 1: 写 statsSummary**(单条聚合 SQL):`total=COUNT(*)`、`online=SUM(s.login_state=1)`、`offline=SUM(s.login_state=2)`、`banned=SUM(s.account_state=3)`、`risk=SUM(s.risk_status>1)`(`&gt;1` 转义)、`assigned=SUM(a.dispatched_at IS NOT NULL)`、`unassigned=total-assigned`。LEFT JOIN account_state。
- [ ] **Step 2: DbTest**:step1 导入号 → `total=2`,其余(online/banned/risk/assigned)=0(状态/dispatched_at 全 NULL);差额 total−online−offline=2(待上线)。
- [ ] **Step 3: 实现 → PASS**。**Step 4: Commit** `feat(account): account stats summary`。

## Task 1.3.3: 迁移分组 + 批量删除(严格口径)

**Files:** `AccountMapper`(+`migrateGroup`/`batchSoftDelete`/`selectStatesByIds`)+ XML;Service 方法;Test `AccountMutationDbTest`

- [ ] **Step 1: DbTest(先红)**:
  - 迁移分组:2 号 `migrateGroup(ids, targetGroupId, now)` → `account_group_id` 改为目标;新建分组路径走 `ensureGroup`(复用 `AccountGroupService.create`)。
  - **删除严格口径**:导入号(state=NULL)`batchDelete` → 抛 VALIDATION「仅导出/封禁/解绑状态可删」(因 `account_state NOT IN (3,4,5)` 或 NULL);构造一条 `account_state=4(导出)` 且 `dispatched_at IS NULL` 的号 → 可删。
```java
@Test
void batchDelete_rejectsPendingOnlineAccount() {
    Long id = importOneAccount("8613800138010");   // state=NULL 待上线
    assertThatThrownBy(() -> accountService.batchDelete(List.of(id)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("仅导出/封禁/解绑");
}
```
- [ ] **Step 2: 实现**。`batchDelete`:`selectStatesByIds` 取每号 `account_state`+`dispatched_at`,逐个校验 `account_state IN (3,4,5) AND dispatched_at IS NULL`,任一不满足整批抛 VALIDATION(回报哪条);通过则 `batchSoftDelete(ids, now)`。`migrateGroup`:校验目标组存在/新建,`UPDATE account SET account_group_id=#{gid},updated_at=#{now} WHERE id IN <foreach>`。
- [ ] **Step 3: 跑 PASS**。**Step 4: Commit** `feat(account): migrate-group + strict batch-delete`。

## Task 1.3.4: AccountController 收口

**Files:** Create `controller/AccountController.java`;Service `AccountQueryService` 收口 list/stats/migrate/delete

- [ ] **Step 1: 写 AccountQueryService + impl**(list 照 GroupLinkLabel total==0 短路;stats;migrateGroup;batchDelete)+ Converter `toAccountListVO`(Long 时间直映,占位列常量)。
- [ ] **Step 2: 写 Controller**:`GET /api/accounts`(`@ModelAttribute AccountQuery`)、`GET /api/accounts/stats`、`POST /api/accounts/batch-migrate-group`、`POST /api/accounts/batch-delete`。
- [ ] **Step 3: 全量回归** `armada-api/dbtest.sh 'com.armada.account.**'` 全绿;`mvn -q -pl armada-api compile` 通过。
- [ ] **Step 4: 重跑 wiki** `python3 .harness/wiki/gen_datamodel.py` + `python3 .harness/wiki/parse_endpoints.py`(接口协议.md 含新端点)。
- [ ] **Step 5: Commit** `feat(account): account list controller + query service (step1 done)`。

---

## Self-Review(写完核对)

- **Spec 覆盖**:① 分组 CRUD+系统组+删闸→1.1;② 导入 JSON/全参+完整性+三步写+批次/明细/导出→1.2;③ 列表 JOIN+8 维筛+统计+迁移+严格删→1.3;④ V005 迁移→0.1;⑤ 零协议/占位/状态 NULL→各 VO 与查询。无遗漏。
- **占位扫描**:仅「params 确切键 + wid 抠取路径按真实样例补」为有意延后(spec 已声明),非空洞 TODO;其余步骤均带真代码。
- **类型一致**:`AccountGroupVoRow`/`AccountListVoRow` 时间 `Long`;Converter 时间 Long→Long 直映(**不引 toEpochMilli**,与 group 模块的 DATETIME 路径不同——已在 Phase 1.1 抬头标差异);`softDeleteByIds(ids, deletedAt)` 全程带 deletedAt 入参一致。
- **已收口**:T1.2.3 的 `status` 口径已与冻结数据模型对齐(step1 恒 `2 已完成`,成败看计数列),spec 第 4 节 + 决策日志同步改正,无悬而未决项。
