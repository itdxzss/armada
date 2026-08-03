# 群组列表运营分组 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Armada 群组列表增加与 WS 导入分组完全隔离的单一运营分组，支持筛选、批量绑定、取消绑定和安全管理分组。

**Architecture:** 后端新增 `group_folder` 字典表并在 `group_link` 增加可空 `folder_id`，沿用 `Controller -> Service -> Mapper`、MyBatis XML、租户拦截器和 Service 事务。前端在现有群组列表内增加分组筛选、批量分组弹窗和管理弹窗，API 契约统一使用 camelCase；`group_link.label_id` 及导入链路保持不变。

**Tech Stack:** Java 17、Spring Boot 3.3.5、plain MyBatis XML、MyBatis-Plus 租户插件、Flyway、MySQL 8、JUnit 5/AssertJ/Mockito、H2 MySQL mode、Vue 3、TypeScript、Element Plus、pure-admin-thin、Node test、pnpm。

---

## Source of Truth

- 设计：`docs/superpowers/specs/2026-08-03-group-list-folder-design.md`
- 变更记录：`.harness/changes/group-list-folder/summary.md`
- 后端规则：`AGENTS.md`、`.harness/rules/{编码规范,工程结构,数据模型规范,开发流程规范}.md`
- 前端规则：`../wheel-saas-pure-web/AGENTS.md`、`../wheel-saas-pure-web/.harness/rules/{前端架构,编码规范}.md`
- 当前群组池：`armada-api/src/main/java/com/armada/group/`、`armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- 当前群列表：`../wheel-saas-pure-web/src/views/group/list/`

## Scope Check

这是一个纵向完整、但不可拆成独立上线子项目的功能：后端模型/API 与前端入口缺一不可。按可单独验证的增量分为五个后端提交、三个前端提交和一个最终文档/门禁提交。协议层无改动。

## File Map

### Backend — Create

- `armada-api/src/main/resources/db/migration/V090__group_folder.sql` — 新表、关联列和索引。
- `.harness/changes/group-list-folder/db-migrations.sql` — V090 同内容副本。
- `.harness/changes/group-list-folder/rollback.sql` — 仅供永久撤销时评审的结构回滚脚本。
- `armada-api/src/main/java/com/armada/group/model/entity/GroupFolder.java` — `group_folder` 实体。
- `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderQuery.java` — 管理列表分页查询。
- `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderDTO.java` — 新增/改名请求。
- `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderBatchDeleteDTO.java` — 批量删除请求。
- `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderAssignDTO.java` — 批量设置/取消请求。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderVoRow.java` — Mapper 分页投影。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderVO.java` — 管理列表出参。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderOptionVO.java` — 选择器出参。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderDeleteResultVO.java` — 删除结果。
- `armada-api/src/main/java/com/armada/group/mapper/GroupFolderMapper.java` — 字典数据访问。
- `armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml` — 分页、选项、锁定、CRUD SQL。
- `armada-api/src/main/java/com/armada/group/service/GroupFolderService.java` — 字典业务接口。
- `armada-api/src/main/java/com/armada/group/service/impl/GroupFolderServiceImpl.java` — CRUD 与删除解除关系事务。
- `armada-api/src/main/java/com/armada/group/controller/GroupFolderController.java` — `/api/group-folders`。
- `armada-api/src/test/java/com/armada/group/GroupFolderMigrationSqlTest.java` — V090 结构契约。
- `armada-api/src/test/java/com/armada/group/service/GroupFolderServiceImplTest.java` — 字典业务规则单测。

### Backend — Modify

- `armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java` — `folderId`。
- `armada-api/src/main/java/com/armada/group/model/dto/GroupLinkQuery.java` — `folderId/withoutFolder`。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVoRow.java` — `folderId/folderName`。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java` — `folderId/folderName`。
- `armada-api/src/main/java/com/armada/group/converter/GroupConverter.java` — 字典和群列表投影转换。
- `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java` — 运营分组锁定、批量更新、解除关系。
- `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml` — 筛选、JOIN、批量更新 SQL。
- `armada-api/src/main/java/com/armada/group/service/GroupLinkService.java` — 批量设置接口。
- `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java` — 查询互斥校验与批量设置事务。
- `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java` — 批量设置端点。
- `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java` — H2 真跑新 Mapper/XML 和租户隔离。
- `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java` — 查询与批量设置规则。
- `armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperDbTest.java` — 真实 MySQL 可选补充回归。
- `.harness/wiki/数据模型.md` — 新表/列说明。

### Frontend — Create

- `../wheel-saas-pure-web/src/api/group-folder.ts` — 运营分组 API 与类型。
- `../wheel-saas-pure-web/src/api/group-folder.test.ts` — API 契约测试。
- `../wheel-saas-pure-web/src/views/group/list/components/BatchAssignFolderDialog.vue` — 批量分组弹窗。
- `../wheel-saas-pure-web/src/views/group/list/components/BatchAssignFolderDialog.test.ts` — 组件契约测试。
- `../wheel-saas-pure-web/src/views/group/list/components/GroupFolderManageDialog.vue` — 分组管理容器弹窗。
- `../wheel-saas-pure-web/src/views/group/list/components/GroupFolderManageDialog.test.ts` — 管理交互契约测试。
- `../wheel-saas-pure-web/src/views/group/list/GroupListFolderIntegration.test.ts` — 页面接线契约测试。

### Frontend — Modify

- `../wheel-saas-pure-web/src/api/group.ts` — 群列表分组字段/查询/批量接口。
- `../wheel-saas-pure-web/src/api/group.test.ts` — 查询和批量接口测试。
- `../wheel-saas-pure-web/src/views/group/list/index.vue` — 筛选与两个弹窗接线。
- `../wheel-saas-pure-web/src/views/group/list/components/GroupListTable.vue` — 工具栏事件和分组标签。
- `../wheel-saas-pure-web/src/views/group/list/components/GroupListTable.test.ts` — 表格契约。
- `../wheel-saas-pure-web/src/views/group/list/composables/useGroupListPage.ts` — 分组选项、筛选映射和批量操作状态。

---

### Task 1: 用红测锁定 V090 数据结构

**Files:**
- Create: `armada-api/src/test/java/com/armada/group/GroupFolderMigrationSqlTest.java`
- Create: `armada-api/src/main/resources/db/migration/V090__group_folder.sql`
- Create: `.harness/changes/group-list-folder/db-migrations.sql`
- Create: `.harness/changes/group-list-folder/rollback.sql`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java`

- [ ] **Step 1: 写迁移契约红测**

创建测试，读取 classpath 迁移并固定表、列和索引：

```java
package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class GroupFolderMigrationSqlTest {

    @Test
    void v090CreatesFolderAndAddsGroupLinkFolderIndex() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V090__group_folder.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql).contains("create table group_folder");
            assertThat(sql).contains("unique key uq_group_folder_name (tenant_id, name)");
            assertThat(sql).contains("add column folder_id bigint default null");
            assertThat(sql).contains("idx_group_link_folder (tenant_id, deleted_at, folder_id)");
        }
    }
}
```

- [ ] **Step 2: 运行红测**

Run：

```bash
cd armada-api
mvn -Dtest=GroupFolderMigrationSqlTest test
```

Expected：FAIL，`V090__group_folder.sql` 资源不存在。

- [ ] **Step 3: 写 V090 和 change 副本**

`V090__group_folder.sql` 与 `db-migrations.sql` 内容保持一致：

```sql
CREATE TABLE group_folder (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    name VARCHAR(64) NOT NULL COMMENT '群组运营分组名称',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒);NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_folder_name (tenant_id, name),
    KEY idx_group_folder_active (tenant_id, deleted_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='群组列表运营分组';

ALTER TABLE group_link
    ADD COLUMN folder_id BIGINT DEFAULT NULL
      COMMENT '群组运营分组(关联group_folder.id);NULL=未分组' AFTER label_id,
    ADD KEY idx_group_link_folder (tenant_id, deleted_at, folder_id);
```

`rollback.sql`：

```sql
ALTER TABLE group_link DROP INDEX idx_group_link_folder, DROP COLUMN folder_id;
DROP TABLE IF EXISTS group_folder;
```

在 `GroupLink` 的 `labelId` 后增加：

```java
/** 群组列表运营分组;NULL=未分组,不影响WS链接导入分组。 */
private Long folderId;

public Long getFolderId() {
    return folderId;
}

public void setFolderId(Long folderId) {
    this.folderId = folderId;
}
```

- [ ] **Step 4: 运行绿测和编译**

Run：

```bash
cd armada-api
mvn -Dtest=GroupFolderMigrationSqlTest test
mvn -DskipTests test-compile
```

Expected：迁移契约 1/1 PASS，主代码和测试代码编译成功。

- [ ] **Step 5: 提交 schema 增量**

```bash
git add armada-api/src/test/java/com/armada/group/GroupFolderMigrationSqlTest.java \
  armada-api/src/main/resources/db/migration/V090__group_folder.sql \
  armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java \
  .harness/changes/group-list-folder/db-migrations.sql \
  .harness/changes/group-list-folder/rollback.sql
git commit -m "feat(group): add group folder schema"
```

---

### Task 2: 实现运营分组模型和真实 Mapper XML

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/model/entity/GroupFolder.java`
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderQuery.java`
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderDTO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderVoRow.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderVO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderOptionVO.java`
- Create: `armada-api/src/main/java/com/armada/group/mapper/GroupFolderMapper.java`
- Create: `armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/converter/GroupConverter.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [ ] **Step 1: 先给 H2 Mapper 测试增加失败用例**

给 `MysqlModeMapperInMemoryTest` 增加 `GroupFolderMapper` 注入、测试 bean 和测试 schema；测试至少覆盖当前租户分页、跨租户隔离、群数和选项排序：

```java
@Autowired
private GroupFolderMapper groupFolderMapper;

@Test
void groupFolderMapperExecutesRealXmlAndKeepsTenantBoundary() throws SQLException {
    executeSql(
            "INSERT INTO group_folder (id, tenant_id, name, created_at, updated_at) "
                    + "VALUES (101, 7, '印度组', 100, 100)",
            "INSERT INTO group_folder (id, tenant_id, name, created_at, updated_at) "
                    + "VALUES (102, 8, '其他租户组', 100, 100)",
            "INSERT INTO group_link (id, tenant_id, link_url, folder_id, created_at, updated_at) "
                    + "VALUES (201, 7, 'chat.whatsapp.com/FolderA', 101, 100, 100)");

    GroupFolderQuery query = new GroupFolderQuery();
    query.setPage(1);
    query.setPageSize(10);

    assertThat(groupFolderMapper.countPage(query)).isEqualTo(1);
    assertThat(groupFolderMapper.selectPage(query))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.getName()).isEqualTo("印度组");
                assertThat(row.getGroupCount()).isEqualTo(1L);
            });
    assertThat(groupFolderMapper.selectOptions())
            .extracting(GroupFolder::getId)
            .containsExactly(101L);
}
```

在测试配置中增加：

```java
@Bean
GroupFolderMapper groupFolderMapper(SqlSessionTemplate sqlSessionTemplate) {
    return sqlSessionTemplate.getMapper(GroupFolderMapper.class);
}
```

并在 `createSchema()` 增加 `group_folder`，给测试 `group_link` 增加 `folder_id BIGINT`。

- [ ] **Step 2: 运行测试确认编译或运行失败**

```bash
cd armada-api
mvn -Dtest=MysqlModeMapperInMemoryTest test
```

Expected：FAIL，缺少 `GroupFolder*` 类型、Mapper 或 XML statement。

- [ ] **Step 3: 创建模型**

`GroupFolder` 使用普通类和以下精确字段：

```java
package com.armada.group.model.entity;

/** 群组列表运营分组,映射 group_folder 表。 */
public class GroupFolder {
    private Long id;
    private Long tenantId;
    private String name;
    private Long createdAt;
    private Long updatedAt;
    private Long createdBy;
    private Long deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
```

其他模型：

```java
public class GroupFolderQuery extends PageQuery {
    private String keyword;
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
}

public record GroupFolderDTO(String name) { }

public record GroupFolderVO(
        Long id, String name, Long groupCount, Long createdAt, Long updatedAt) { }

public record GroupFolderOptionVO(Long id, String name) { }
```

`GroupFolderVoRow` 使用普通类，字段为 `id/name/groupCount/createdAt/updatedAt`，逐项提供标准 getter/setter。

- [ ] **Step 4: 创建 Mapper 接口与 XML**

Mapper 接口固定为：

```java
@Mapper
public interface GroupFolderMapper {
    long countPage(GroupFolderQuery query);
    List<GroupFolderVoRow> selectPage(GroupFolderQuery query);
    List<GroupFolder> selectOptions();
    GroupFolder selectActiveByName(@Param("name") String name);
    GroupFolder selectDeletedByName(@Param("name") String name);
    GroupFolder selectAnyByName(@Param("name") String name);
    GroupFolder selectById(@Param("id") Long id);
    GroupFolder selectByIdForUpdate(@Param("id") Long id);
    List<GroupFolder> selectActiveByIdsForUpdate(@Param("ids") List<Long> ids);
    int insert(GroupFolder row);
    int reviveById(@Param("id") Long id, @Param("updatedAt") long updatedAt);
    int updateName(@Param("id") Long id, @Param("name") String name,
                   @Param("updatedAt") long updatedAt);
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
```

XML 核心 SQL：

```xml
<sql id="activeFilter">
  f.deleted_at IS NULL
  <if test="keyword != null and keyword != ''">
    AND f.name LIKE CONCAT('%', #{keyword}, '%')
  </if>
</sql>

<select id="selectPage" resultType="com.armada.group.model.vo.GroupFolderVoRow">
  SELECT f.id, f.name,
         (SELECT COUNT(*) FROM group_link g
           WHERE g.folder_id = f.id
             AND g.tenant_id = f.tenant_id
             AND g.deleted_at IS NULL) AS groupCount,
         f.created_at AS createdAt, f.updated_at AS updatedAt
  FROM group_folder f
  WHERE <include refid="activeFilter"/>
  ORDER BY f.created_at DESC, f.id DESC
  LIMIT #{offset}, #{pageSize}
</select>

<select id="selectOptions" resultType="com.armada.group.model.entity.GroupFolder">
  SELECT id, name FROM group_folder
  WHERE deleted_at IS NULL
  ORDER BY name ASC, id ASC
</select>

<select id="selectByIdForUpdate" resultType="com.armada.group.model.entity.GroupFolder">
  SELECT * FROM group_folder WHERE id = #{id} AND deleted_at IS NULL FOR UPDATE
</select>

<select id="selectActiveByIdsForUpdate" resultType="com.armada.group.model.entity.GroupFolder">
  SELECT * FROM group_folder WHERE deleted_at IS NULL AND id IN
  <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
  ORDER BY id FOR UPDATE
</select>
```

补齐 `countPage`、三种按名称查询、普通 `selectById`、`insert/revive/updateName/softDelete`；`insert` 使用 `useGeneratedKeys="true" keyProperty="id"`。所有顶层 SQL 不手写当前租户 ID，由插件注入；分页群数的相关子查询额外写 `g.tenant_id = f.tenant_id`，避免相关子查询脱离租户边界。

给 `GroupConverter` 增加：

```java
GroupFolderVO toFolderVO(GroupFolderVoRow row);
List<GroupFolderVO> toFolderVOList(List<GroupFolderVoRow> rows);

default GroupFolderOptionVO toFolderOptionVO(GroupFolder row) {
    return new GroupFolderOptionVO(row.getId(), row.getName());
}
```

- [ ] **Step 5: 运行 H2 真 Mapper 测试**

```bash
cd armada-api
mvn -Dtest=MysqlModeMapperInMemoryTest test
```

Expected：PASS；新增测试真实执行 `GroupFolderMapper.xml`，且只返回 tenant 7 数据。

- [ ] **Step 6: 提交数据访问增量**

```bash
git add armada-api/src/main/java/com/armada/group/model/entity/GroupFolder.java \
  armada-api/src/main/java/com/armada/group/model/dto/GroupFolderQuery.java \
  armada-api/src/main/java/com/armada/group/model/dto/GroupFolderDTO.java \
  armada-api/src/main/java/com/armada/group/model/vo/GroupFolderVoRow.java \
  armada-api/src/main/java/com/armada/group/model/vo/GroupFolderVO.java \
  armada-api/src/main/java/com/armada/group/model/vo/GroupFolderOptionVO.java \
  armada-api/src/main/java/com/armada/group/mapper/GroupFolderMapper.java \
  armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml \
  armada-api/src/main/java/com/armada/group/converter/GroupConverter.java \
  armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java
git commit -m "feat(group): add group folder data access"
```

---

### Task 3: 实现分组 CRUD 和安全删除事务

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderBatchDeleteDTO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderDeleteResultVO.java`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupFolderService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupFolderServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/group/controller/GroupFolderController.java`
- Create: `armada-api/src/test/java/com/armada/group/service/GroupFolderServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [ ] **Step 1: 写 Service 红测**

使用 Mockito 锁定以下规则：空名称拒绝、同名活跃拒绝、同名软删复活、改名命中另一软删 ID 拒绝、删除分组先清群关系再软删、任一 ID 缺失整批不更新。

删除成功测试核心：

```java
@Test
void batchDeleteClearsGroupsBeforeSoftDeletingFolders() {
    GroupFolder folder = new GroupFolder();
    folder.setId(10L);
    when(folderMapper.selectActiveByIdsForUpdate(List.of(10L)))
            .thenReturn(List.of(folder));
    when(groupLinkMapper.countActiveByFolderIds(List.of(10L))).thenReturn(3);
    when(groupLinkMapper.clearFolderByFolderIds(eq(List.of(10L)), anyLong()))
            .thenReturn(3);
    when(folderMapper.softDeleteByIds(eq(List.of(10L)), anyLong())).thenReturn(1);

    GroupFolderDeleteResultVO result = service.batchDelete(List.of(10L));

    assertThat(result.deletedFolderCount()).isEqualTo(1);
    assertThat(result.ungroupedGroupCount()).isEqualTo(3);
    InOrder order = inOrder(groupLinkMapper, folderMapper);
    order.verify(groupLinkMapper).clearFolderByFolderIds(eq(List.of(10L)), anyLong());
    order.verify(folderMapper).softDeleteByIds(eq(List.of(10L)), anyLong());
}
```

- [ ] **Step 2: 运行红测**

```bash
cd armada-api
mvn -Dtest=GroupFolderServiceImplTest test
```

Expected：FAIL，Service、DTO、VO 和 GroupLinkMapper 方法尚不存在。

- [ ] **Step 3: 扩展 GroupLinkMapper 的删除支撑 SQL**

接口：

```java
int countActiveByFolderIds(@Param("folderIds") List<Long> folderIds);

int clearFolderByFolderIds(@Param("folderIds") List<Long> folderIds,
                           @Param("updatedAt") long updatedAt);
```

XML：

```xml
<select id="countActiveByFolderIds" resultType="int">
  SELECT COUNT(*) FROM group_link
  WHERE deleted_at IS NULL AND folder_id IN
  <foreach collection="folderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
</select>

<update id="clearFolderByFolderIds">
  UPDATE group_link SET folder_id = NULL, updated_at = #{updatedAt}
  WHERE deleted_at IS NULL AND folder_id IN
  <foreach collection="folderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
</update>
```

- [ ] **Step 4: 实现 Service**

请求和结果：

```java
public record GroupFolderBatchDeleteDTO(List<Long> ids) { }

public record GroupFolderDeleteResultVO(
        int deletedFolderCount, int ungroupedGroupCount) { }
```

Service 接口：

```java
PageResult<GroupFolderVO> list(GroupFolderQuery query);
List<GroupFolderOptionVO> options();
GroupFolderVO create(GroupFolderDTO dto);
void update(Long id, GroupFolderDTO dto);
GroupFolderDeleteResultVO batchDelete(List<Long> ids);
```

实现必须包含：

```java
private static final int NAME_MAX_LENGTH = 64;
private static final int BATCH_MAX = 100;

private static String normalizeName(GroupFolderDTO dto) {
    if (dto == null || dto.name() == null || dto.name().trim().isEmpty()) {
        throw new BusinessException(ErrorCode.VALIDATION, "群组分组名称不能为空");
    }
    String name = dto.name().trim();
    if (name.length() > NAME_MAX_LENGTH) {
        throw new BusinessException(ErrorCode.VALIDATION, "群组分组名称不能超过64个字符");
    }
    return name;
}
```

`batchDelete` 使用 `TreeSet` 去重排序，锁定全部 folder，校验返回数量等于请求数量，再按 `clearFolderByFolderIds -> softDeleteByIds` 顺序执行。`create/update` 捕获 `DuplicateKeyException` 并转成 `BusinessException(ErrorCode.VALIDATION, "群组分组名称已存在")`。`create` 新插入或复活后直接构造 `GroupFolderVO(id, name, 0L, createdAt, updatedAt)` 返回，避免为刚创建的零群分组额外做一次分页投影查询。

- [ ] **Step 5: 实现 Controller**

```java
@RestController
@RequestMapping("/api/group-folders")
@PreAuthorize("hasAuthority('tenant:group_link:view')")
public class GroupFolderController {
    private final GroupFolderService service;

    public GroupFolderController(GroupFolderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<GroupFolderVO>> list(
            @ModelAttribute GroupFolderQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    @GetMapping("/options")
    public ApiResponse<List<GroupFolderOptionVO>> options() {
        return ApiResponse.ok(service.options());
    }

    @PostMapping
    public ApiResponse<GroupFolderVO> create(@RequestBody GroupFolderDTO dto) {
        return ApiResponse.ok(service.create(dto));
    }

    @PatchMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @RequestBody GroupFolderDTO dto) {
        service.update(id, dto);
        return ApiResponse.ok();
    }

    @PostMapping("/batch-delete")
    public ApiResponse<GroupFolderDeleteResultVO> batchDelete(
            @RequestBody GroupFolderBatchDeleteDTO dto) {
        return ApiResponse.ok(service.batchDelete(dto.ids()));
    }
}
```

- [ ] **Step 6: 给 H2 测试增加删除不删群用例并跑绿**

断言删除后 `group_link.deleted_at IS NULL`、`folder_id IS NULL`、原 `label_id` 不变。

```bash
cd armada-api
mvn -Dtest='GroupFolderServiceImplTest,MysqlModeMapperInMemoryTest' test
```

Expected：全部 PASS。

- [ ] **Step 7: 提交 CRUD 增量**

```bash
git add armada-api/src/main/java/com/armada/group/model/dto/GroupFolderBatchDeleteDTO.java \
  armada-api/src/main/java/com/armada/group/model/vo/GroupFolderDeleteResultVO.java \
  armada-api/src/main/java/com/armada/group/service/GroupFolderService.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupFolderServiceImpl.java \
  armada-api/src/main/java/com/armada/group/controller/GroupFolderController.java \
  armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java \
  armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml \
  armada-api/src/test/java/com/armada/group/service/GroupFolderServiceImplTest.java \
  armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java
git commit -m "feat(group): manage group folders"
```

---

### Task 4: 扩展群列表分组筛选与返回投影

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/model/dto/GroupLinkQuery.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVoRow.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java`
- Modify: `armada-api/src/main/java/com/armada/group/converter/GroupConverter.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [ ] **Step 1: 写查询红测**

Service 单测锁定互斥参数：

```java
@Test
void listRejectsFolderIdTogetherWithWithoutFolder() {
    GroupLinkQuery query = new GroupLinkQuery();
    query.setFolderId(10L);
    query.setWithoutFolder(true);

    assertThatThrownBy(() -> service.listByLabel(query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("folderId 与 withoutFolder 不能同时使用");
    verify(groupLinkMapper, never()).countByLabel(any());
}
```

H2 Mapper 测试插入一个有 `folder_id`、一个无 `folder_id` 的群组，分别断言 `folderId` 和 `withoutFolder` 查询的 count/list 都为 1，并且投影返回 `folderName`。

- [ ] **Step 2: 运行红测**

```bash
cd armada-api
mvn -Dtest='GroupLinkServiceImplTest,MysqlModeMapperInMemoryTest' test
```

Expected：FAIL，查询字段和 SQL 尚未实现。

- [ ] **Step 3: 扩展 Query、投影和 VO**

`GroupLinkQuery` 增加：

```java
private Long folderId;
private Boolean withoutFolder;

public Long getFolderId() { return folderId; }
public void setFolderId(Long folderId) { this.folderId = folderId; }
public Boolean getWithoutFolder() { return withoutFolder; }
public void setWithoutFolder(Boolean withoutFolder) { this.withoutFolder = withoutFolder; }
```

`GroupLinkVoRow` 增加 `Long folderId`、`String folderName` 及 getter/setter；`GroupLinkVO` 在 `sourceFileName` 后增加：

```java
Long folderId,
String folderName,
```

同步修改 `GroupConverter.toGroupLinkVO` 构造参数，传 `row.getFolderId()`、`row.getFolderName()`。

- [ ] **Step 4: 修改群列表 SQL**

筛选片段增加：

```xml
<if test="folderId != null">
  AND g.folder_id = #{folderId}
</if>
<if test="withoutFolder != null and withoutFolder">
  AND g.folder_id IS NULL
</if>
```

列表 FROM 增加：

```xml
LEFT JOIN group_folder f
  ON f.id = g.folder_id
 AND f.tenant_id = g.tenant_id
 AND f.deleted_at IS NULL
```

SELECT 增加：

```sql
g.folder_id AS folderId,
f.name AS folderName,
```

count 查询不为展示字段 JOIN `group_folder`，继续只从 `g.folder_id` 过滤，避免无意义聚合成本。

- [ ] **Step 5: 增加 Service 参数校验并跑绿**

```java
private static void validateFolderFilter(GroupLinkQuery query) {
    if (query.getFolderId() != null && Boolean.TRUE.equals(query.getWithoutFolder())) {
        throw new BusinessException(
                ErrorCode.VALIDATION,
                "folderId 与 withoutFolder 不能同时使用");
    }
}
```

在 `listByLabel` 查询 Mapper 前调用该方法。

```bash
cd armada-api
mvn -Dtest='GroupLinkServiceImplTest,MysqlModeMapperInMemoryTest' test
```

Expected：查询互斥、指定分组、未分组和投影测试全部 PASS。

- [ ] **Step 6: 提交查询增量**

```bash
git add armada-api/src/main/java/com/armada/group/model/dto/GroupLinkQuery.java \
  armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVoRow.java \
  armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java \
  armada-api/src/main/java/com/armada/group/converter/GroupConverter.java \
  armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml \
  armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java \
  armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java \
  armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java
git commit -m "feat(group): filter group list by folder"
```

---

### Task 5: 实现群组批量设置和取消分组

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderAssignDTO.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [ ] **Step 1: 写批量设置红测**

覆盖绑定、`folderId=null` 取消、重复 ID 去重、非法 ID、目标分组不存在、部分群组不存在整批失败。

成功用例：

```java
@Test
void assignFolderLocksFolderAndAllGroupsThenUpdates() {
    GroupFolder folder = new GroupFolder();
    folder.setId(10L);
    GroupLink first = new GroupLink();
    first.setId(101L);
    GroupLink second = new GroupLink();
    second.setId(102L);
    when(folderMapper.selectByIdForUpdate(10L)).thenReturn(folder);
    when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L, 102L)))
            .thenReturn(List.of(first, second));
    when(groupLinkMapper.assignFolder(eq(List.of(101L, 102L)), eq(10L), anyLong()))
            .thenReturn(2);

    int updated = service.assignFolder(List.of(102L, 101L, 101L), 10L);

    assertThat(updated).isEqualTo(2);
    verify(groupLinkMapper).assignFolder(eq(List.of(101L, 102L)), eq(10L), anyLong());
}
```

- [ ] **Step 2: 运行红测**

```bash
cd armada-api
mvn -Dtest=GroupLinkServiceImplTest test
```

Expected：FAIL，批量设置方法不存在。

- [ ] **Step 3: 增加 Mapper 锁定与更新**

```java
List<GroupLink> selectActiveByIdsForUpdate(@Param("ids") List<Long> ids);

int assignFolder(@Param("ids") List<Long> ids,
                 @Param("folderId") Long folderId,
                 @Param("updatedAt") long updatedAt);
```

```xml
<select id="selectActiveByIdsForUpdate"
        resultType="com.armada.group.model.entity.GroupLink">
  SELECT * FROM group_link
  WHERE deleted_at IS NULL AND id IN
  <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
  ORDER BY id FOR UPDATE
</select>

<update id="assignFolder">
  UPDATE group_link
  SET folder_id = #{folderId}, updated_at = #{updatedAt}
  WHERE deleted_at IS NULL AND id IN
  <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
</update>
```

- [ ] **Step 4: 实现 DTO、Service 和 Controller**

```java
public record GroupFolderAssignDTO(List<Long> ids, Long folderId) { }
```

Service 接口增加：

```java
int assignFolder(List<Long> ids, Long folderId);
```

Service 实现使用 `TreeSet` 去重排序，校验 1～100、非空正 ID；`folderId` 非空时先 `folderMapper.selectByIdForUpdate`，然后 `groupLinkMapper.selectActiveByIdsForUpdate` 并校验数量，最后调用 `assignFolder`。整个方法标注 `@Transactional(rollbackFor = Exception.class)`。同时在现有 `GroupLinkServiceImpl` 增加 `private final GroupFolderMapper folderMapper`，把它加入构造器参数并赋值，现有构造器依赖和顺序保持不丢失。

Controller：

```java
@PostMapping("/batch-assign-folder")
public ApiResponse<Integer> assignFolder(@RequestBody GroupFolderAssignDTO dto) {
    return ApiResponse.ok(groupLinkService.assignFolder(dto.ids(), dto.folderId()));
}
```

- [ ] **Step 5: 给 H2 真 Mapper 增加绑定/取消/租户隔离测试并跑绿**

```bash
cd armada-api
mvn -Dtest='GroupLinkServiceImplTest,MysqlModeMapperInMemoryTest' test
```

Expected：批量绑定、取消和跨租户隔离全部 PASS。

- [ ] **Step 6: 提交批量设置增量**

```bash
git add armada-api/src/main/java/com/armada/group/model/dto/GroupFolderAssignDTO.java \
  armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java \
  armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml \
  armada-api/src/main/java/com/armada/group/service/GroupLinkService.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java \
  armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java \
  armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java \
  armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java
git commit -m "feat(group): batch assign group folders"
```

---

### Task 6: 固定前端 API 契约

**Working directory:** `../wheel-saas-pure-web`

**Files:**
- Create: `src/api/group-folder.ts`
- Create: `src/api/group-folder.test.ts`
- Modify: `src/api/group.ts`
- Modify: `src/api/group.test.ts`

- [ ] **Step 1: 写 API 红测**

测试必须断言分页/选项/CRUD/删除 URL，以及群列表查询和批量绑定的 camelCase wire：

```ts
it("submits group folder management requests", async () => {
  resetArmadaMock({ deletedFolderCount: 1, ungroupedGroupCount: 3 });

  await listGroupFolders({ page: 2, pageSize: 20, keyword: "印度" });
  await listGroupFolderOptions();
  await createGroupFolder({ name: "印度组" });
  await updateGroupFolder(7, { name: "印度组-新" });
  await batchDeleteGroupFolders([7]);

  assert.deepEqual(
    armadaCalls().map(call => [call.method, call.url]),
    [
      ["get", "/api/group-folders"],
      ["get", "/api/group-folders/options"],
      ["post", "/api/group-folders"],
      ["patch", "/api/group-folders/7"],
      ["post", "/api/group-folders/batch-delete"]
    ]
  );
});

it("filters and assigns group folders with camelCase params", async () => {
  resetArmadaMock({ list: [], total: 0 });
  await listGroups({ folderId: 8, page: 1, pageSize: 10 });
  await listGroups({ withoutFolder: true, page: 1, pageSize: 10 });
  await batchAssignGroupFolder([101, 102], null);

  assert.deepEqual(armadaCalls()[2], {
    method: "post",
    url: "/api/group-links/batch-assign-folder",
    opts: { data: { ids: [101, 102], folderId: null } }
  });
});
```

- [ ] **Step 2: 运行红测**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/group-folder.test.ts src/api/group.test.ts
```

Expected：FAIL，API 函数和类型不存在。

- [ ] **Step 3: 实现 `group-folder.ts`**

```ts
import { armadaRequest } from "@/api/armada";
import type { PageResponse } from "@/api/account";

export interface GroupFolderRow {
  id: number;
  name: string;
  groupCount: number;
  createdAt: number;
  updatedAt: number;
}

export interface GroupFolderOption {
  id: number;
  name: string;
}

export interface GroupFolderWriteRequest {
  name: string;
}

export interface GroupFolderDeleteResult {
  deletedFolderCount: number;
  ungroupedGroupCount: number;
}

export function listGroupFolders(params: {
  page?: number;
  pageSize?: number;
  keyword?: string;
} = {}): Promise<PageResponse<GroupFolderRow>> {
  return armadaRequest<PageResponse<GroupFolderRow>>(
    "get",
    "/api/group-folders",
    { params }
  );
}

export function listGroupFolderOptions(): Promise<GroupFolderOption[]> {
  return armadaRequest<GroupFolderOption[]>(
    "get",
    "/api/group-folders/options"
  );
}

export function createGroupFolder(
  data: GroupFolderWriteRequest
): Promise<GroupFolderRow> {
  return armadaRequest<GroupFolderRow>("post", "/api/group-folders", {
    data
  });
}

export function updateGroupFolder(
  id: number,
  data: GroupFolderWriteRequest
): Promise<void> {
  return armadaRequest<void>("patch", `/api/group-folders/${id}`, { data });
}

export function batchDeleteGroupFolders(
  ids: number[]
): Promise<GroupFolderDeleteResult> {
  return armadaRequest<GroupFolderDeleteResult>(
    "post",
    "/api/group-folders/batch-delete",
    { data: { ids } }
  );
}
```

- [ ] **Step 4: 扩展 `group.ts`**

`GroupListRow` 增加 `folderId/folderName`，`GroupListQuery` 增加 `folderId/withoutFolder`，`toListParams` 直传两个 camelCase 字段；增加：

```ts
export function batchAssignGroupFolder(
  ids: number[],
  folderId: number | null
): Promise<number> {
  return armadaRequest<number>(
    "post",
    "/api/group-links/batch-assign-folder",
    { data: { ids, folderId } }
  );
}
```

- [ ] **Step 5: 运行 API 绿测并提交**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/group-folder.test.ts src/api/group.test.ts
git add src/api/group-folder.ts src/api/group-folder.test.ts \
  src/api/group.ts src/api/group.test.ts
git commit -m "feat(group): add group folder APIs"
```

Expected：API 测试全部 PASS。

---

### Task 7: 实现两个独立分组弹窗

**Working directory:** `../wheel-saas-pure-web`

**Files:**
- Create: `src/views/group/list/components/BatchAssignFolderDialog.vue`
- Create: `src/views/group/list/components/BatchAssignFolderDialog.test.ts`
- Create: `src/views/group/list/components/GroupFolderManageDialog.vue`
- Create: `src/views/group/list/components/GroupFolderManageDialog.test.ts`

- [ ] **Step 1: 写组件契约红测**

使用当前仓库的源码契约测试模式，断言批量弹窗存在“不绑定”、提交 `number | null`，管理弹窗调用五个管理 API、显示群组数量和危险删除提示。

```ts
const batchSource = readFileSync(
  new URL("./BatchAssignFolderDialog.vue", import.meta.url),
  "utf8"
);
assert.match(batchSource, /不绑定/);
assert.match(batchSource, /emit\("submit", selectedFolderId\.value\)/);

const manageSource = readFileSync(
  new URL("./GroupFolderManageDialog.vue", import.meta.url),
  "utf8"
);
assert.match(manageSource, /群组数量/);
assert.match(manageSource, /将进入未分组/);
assert.match(manageSource, /batchDeleteGroupFolders/);
```

- [ ] **Step 2: 运行红测**

```bash
node --test --experimental-strip-types \
  src/views/group/list/components/BatchAssignFolderDialog.test.ts \
  src/views/group/list/components/GroupFolderManageDialog.test.ts
```

Expected：FAIL，两个 Vue 文件不存在。

- [ ] **Step 3: 实现批量分组弹窗**

组件契约固定为：

```ts
const props = defineProps<{
  modelValue: boolean;
  loading: boolean;
  options: GroupFolderOption[];
  selectedCount: number;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "submit", folderId: number | null): void;
}>();

const selectedFolderId = ref<number | "UNASSIGNED" | "">("");

function submit(): void {
  if (selectedFolderId.value === "") {
    ElMessage.warning("请选择目标分组");
    return;
  }
  emit(
    "submit",
    selectedFolderId.value === "UNASSIGNED"
      ? null
      : selectedFolderId.value
  );
}
```

模板使用 `el-dialog/el-select/el-option`，显示“已选择 N 个群组”，并把 `UNASSIGNED` 显示为“不绑定”。关闭弹窗时只重置弹窗内选择，不自行清空父表格选择。

- [ ] **Step 4: 实现分组管理弹窗**

该组件是局部容器，内部调用 `group-folder.ts`，维护分页、关键字、新增/编辑小弹窗和删除 loading；对父组件暴露：

```ts
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "changed", deletedFolderIds: number[]): void;
}>();
```

删除前使用：

```ts
await ElMessageBox.confirm(
  `删除后，该分组下 ${row.groupCount} 个群组将进入未分组。确认删除吗？`,
  "删除群组分组确认",
  { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
);
```

网络失败不关闭任何弹窗；成功后刷新管理列表并 `emit("changed", deletedIds)`。

- [ ] **Step 5: 运行绿测和组件 lint**

```bash
node --test --experimental-strip-types \
  src/views/group/list/components/BatchAssignFolderDialog.test.ts \
  src/views/group/list/components/GroupFolderManageDialog.test.ts
pnpm exec eslint --max-warnings 0 \
  src/views/group/list/components/BatchAssignFolderDialog.vue \
  src/views/group/list/components/BatchAssignFolderDialog.test.ts \
  src/views/group/list/components/GroupFolderManageDialog.vue \
  src/views/group/list/components/GroupFolderManageDialog.test.ts
```

Expected：组件契约测试与 ESLint 全部 PASS。

- [ ] **Step 6: 提交弹窗增量**

```bash
git add src/views/group/list/components/BatchAssignFolderDialog.vue \
  src/views/group/list/components/BatchAssignFolderDialog.test.ts \
  src/views/group/list/components/GroupFolderManageDialog.vue \
  src/views/group/list/components/GroupFolderManageDialog.test.ts
git commit -m "feat(group): add group folder dialogs"
```

---

### Task 8: 接入群列表筛选、工具栏和刷新状态

**Working directory:** `../wheel-saas-pure-web`

**Files:**
- Create: `src/views/group/list/GroupListFolderIntegration.test.ts`
- Modify: `src/views/group/list/index.vue`
- Modify: `src/views/group/list/components/GroupListTable.vue`
- Modify: `src/views/group/list/components/GroupListTable.test.ts`
- Modify: `src/views/group/list/composables/useGroupListPage.ts`

- [ ] **Step 1: 写页面接线红测**

断言以下契约：筛选器包含全部/未分组/具体分组；表格暴露 `manage-folders/assign-folder`；页面挂载两个弹窗；群名称显示 `folderName` 标签；批量成功调用 `refreshGroups` 并清选择。

```ts
const indexSource = readFileSync(new URL("./index.vue", import.meta.url), "utf8");
assert.match(indexSource, /GroupFolderManageDialog/);
assert.match(indexSource, /BatchAssignFolderDialog/);
assert.match(indexSource, /未分组/);
assert.match(indexSource, /@manage-folders=/);
assert.match(indexSource, /@assign-folder=/);
```

- [ ] **Step 2: 运行红测**

```bash
node --test --experimental-strip-types \
  src/views/group/list/GroupListFolderIntegration.test.ts \
  src/views/group/list/components/GroupListTable.test.ts
```

Expected：FAIL，页面尚未接线。

- [ ] **Step 3: 扩展 `useGroupListPage`**

搜索模型增加：

```ts
folderFilter: "" | "UNASSIGNED" | number;
```

构造查询时映射为：

```ts
folderId:
  typeof searchForm.folderFilter === "number"
    ? searchForm.folderFilter
    : undefined,
withoutFolder:
  searchForm.folderFilter === "UNASSIGNED" ? true : undefined
```

Composable 增加 `folderOptions/folderOptionsLoading/loadFolderOptions`、批量弹窗状态和：

```ts
async function assignSelectedFolder(folderId: number | null): Promise<void> {
  if (selectedRows.value.length === 0) return;
  assigningFolder.value = true;
  try {
    await batchAssignGroupFolder(
      selectedRows.value.map(row => row.id),
      folderId
    );
    ElMessage.success(folderId == null ? "已取消群组分组" : "批量分组成功");
    assignFolderDialogOpen.value = false;
    await refreshGroups();
  } catch (error) {
    ElMessage.error(apiErrorMessage(error, "批量分组失败，请稍后重试"));
  } finally {
    assigningFolder.value = false;
  }
}
```

`onMounted` 同时加载群列表和分组选项；管理弹窗 changed 后重载选项，若当前筛选 ID 被删除则置 `folderFilter = ""`，再刷新群列表。

- [ ] **Step 4: 修改表格工具栏和名称标签**

`GroupListTable` emits 增加：

```ts
(event: "manage-folders"): void;
(event: "assign-folder"): void;
```

按钮顺序固定为“管理群组分组、批量分组、批量删除”；批量按钮使用 `selectedCount === 0` 禁用。群名称下增加：

```vue
<el-tag v-if="row.folderName" size="small" type="info">
  {{ row.folderName }}
</el-tag>
```

- [ ] **Step 5: 修改 `index.vue`**

搜索区新增 `el-select`，选项值分别为 `""`、`"UNASSIGNED"` 和分组 ID；挂载两个弹窗并接收表格事件。不要把 CRUD 状态堆入 `index.vue`。

- [ ] **Step 6: 运行页面测试、API 测试和 typecheck**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/group-folder.test.ts src/api/group.test.ts \
  src/views/group/list/GroupListFolderIntegration.test.ts \
  src/views/group/list/components/BatchAssignFolderDialog.test.ts \
  src/views/group/list/components/GroupFolderManageDialog.test.ts \
  src/views/group/list/components/GroupListTable.test.ts
pnpm typecheck
```

Expected：所有目标 Node tests PASS，TypeScript 与 Vue typecheck 无错误。

- [ ] **Step 7: 提交页面接线增量**

```bash
git add src/views/group/list/index.vue \
  src/views/group/list/GroupListFolderIntegration.test.ts \
  src/views/group/list/components/GroupListTable.vue \
  src/views/group/list/components/GroupListTable.test.ts \
  src/views/group/list/composables/useGroupListPage.ts
git commit -m "feat(group): integrate group list folders"
```

---

### Task 9: 全量门禁、文档收口与验收

**Files:**
- Modify: `.harness/changes/group-list-folder/summary.md`
- Modify: `.harness/wiki/数据模型.md`
- Modify if implementation differs: `docs/superpowers/specs/2026-08-03-group-list-folder-design.md`

- [ ] **Step 1: 后端目标测试与编译**

```bash
cd armada-api
mvn -Dtest='GroupFolderMigrationSqlTest,GroupFolderServiceImplTest,GroupLinkServiceImplTest,MysqlModeMapperInMemoryTest' test
mvn -DskipTests test-compile
```

Expected：目标测试全绿，编译成功。

- [ ] **Step 2: 后端扩大回归**

```bash
cd armada-api
mvn -Dtest='GroupLinkMapperDbTest,GroupLinkLabelMapperDbTest,GroupConverterTest' test
```

Expected：群组池、WS 导入分组和转换回归全绿；如本机未配置真库 DbTest 环境，保留 H2 门禁结果并在 change 记录中明确未运行真库测试，不伪报通过。

- [ ] **Step 3: 前端目标回归、lint、typecheck、build**

```bash
cd ../wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/group-folder.test.ts src/api/group.test.ts \
  src/views/group/list/GroupListFolderIntegration.test.ts \
  src/views/group/list/components/BatchAssignFolderDialog.test.ts \
  src/views/group/list/components/GroupFolderManageDialog.test.ts \
  src/views/group/list/components/GroupListTable.test.ts \
  src/views/group/list/components/GroupMemberDrawer.test.ts \
  src/views/group/list/composables/useGroupPermissions.test.ts \
  src/views/group/list/composables/useGroupProfileSaving.test.ts \
  src/views/group/list/composables/useGroupTimedMessage.test.ts
pnpm exec eslint --max-warnings 0 \
  src/api/group-folder.ts src/api/group-folder.test.ts \
  src/api/group.ts src/api/group.test.ts \
  src/views/group/list/index.vue \
  src/views/group/list/GroupListFolderIntegration.test.ts \
  src/views/group/list/components/BatchAssignFolderDialog.vue \
  src/views/group/list/components/BatchAssignFolderDialog.test.ts \
  src/views/group/list/components/GroupFolderManageDialog.vue \
  src/views/group/list/components/GroupFolderManageDialog.test.ts \
  src/views/group/list/components/GroupListTable.vue \
  src/views/group/list/components/GroupListTable.test.ts \
  src/views/group/list/composables/useGroupListPage.ts
pnpm typecheck
pnpm build
```

Expected：测试、ESLint、typecheck、生产构建全部成功。

- [ ] **Step 4: 人工本地冒烟**

按顺序验证：

1. 新建“印度90人群”，选项立即出现。
2. 勾选两个群组批量绑定，列表名称下显示分组标签。
3. 用具体分组筛选，只看到已绑定群组。
4. 选择“不绑定”，两个群组出现在“未分组”筛选。
5. 再次绑定后删除分组，确认提示显示群组数；删除后群组仍存在且进入未分组。
6. 打开“导入链接”，确认原 WS 链接分组统计、迁移和删除流程未改变。

- [ ] **Step 5: 更新数据模型 wiki 和 change 证据**

在 `.harness/wiki/数据模型.md` 增加 `group_folder` 表和 `group_link.folder_id`；在 `summary.md` 勾选已完成任务，逐条写入真实命令和输出，不写“应该通过”。

- [ ] **Step 6: 精确提交文档收口**

```bash
git add .harness/changes/group-list-folder/summary.md .harness/wiki/数据模型.md
git commit -m "docs(group): record group folder verification"
```

- [ ] **Step 7: 最终状态检查**

在两个仓库分别执行：

```bash
git status --short
git log -8 --oneline
```

Expected：仅保留任务开始前已存在的无关工作树状态；所有本功能文件均已按任务精确提交。

---

## Completion Evidence Checklist

- [ ] V090 与 change 副本一致，迁移版本未被并发提交占用。
- [ ] `label_id/import_batch_id` 在绑定、取消和删除运营分组后保持不变。
- [ ] 删除运营分组不删除 `group_link`。
- [ ] 指定分组、未分组的 count/list 口径一致。
- [ ] 跨租户 folder/group ID 不可观察、不可更新。
- [ ] 批量操作 1～100、去重、排序锁定、全有或全无。
- [ ] 前端失败时不关闭弹窗、不清空表格选择。
- [ ] 两仓目标测试、后端编译、前端 typecheck/build 有真实通过输出。
- [ ] change 记录写明未执行的可选验证和原因。
