# 同租户用户数据隔离 —— 独立设计一致性代码审核报告

| 项目 | 内容 |
|---|---|
| 审核日期 | 2026-08-29 |
| 审核性质 | 独立、只读（未修改代码、未提交、未推送） |
| 后端仓库 | `itdxzss/armada` |
| 后端审核分支 / 提交 | `codex/user-data-isolation` / `0cfc9b70c88514ebd3a3aa85801e2a12a5dc3a3d` |
| 后端对比基线 | `1.0.3-snapshot` / `64b1b938` |
| 前端仓库 | `itdxzss/wheel-saas-pure-web` |
| 前端审核分支 / 提交 | `codex/user-data-isolation` / `800c20b6816dc250ce9c9ab6d107ed0be1ac21c8` |
| 前端对比基线 | `1.0.3-snapshot` / `3ee41d9` |
| 审核范围 | `git diff origin/1.0.3-snapshot...origin/codex/user-data-isolation` 全量差异（后端 428 文件 / +16302 −2523，前端 14 文件 / +287 −45） |
| 审核依据 | `docs/superpowers/specs/2026-08-26-user-data-isolation-design.md`（设计）；summary 仅作历史记录，全部完成声明均独立复核 |

**合并结论：不建议直接合并。** 后端主体隔离实现质量高，但存在 3 个 P1 与 6 个 P2 阻塞项，详见文末。

---

## 目录

- [一、Findings](#一findings)
- [二、设计验收矩阵](#二设计验收矩阵)
- [三、独立验证记录](#三独立验证记录)
- [四、结论](#四结论)

---

## 一、Findings

### P0

未发现 P0 级可确认缺陷。

---

### P1-1　超链营销（hyperlink）整域零用户隔离，由 1.0.3-snapshot 合并引入

- **严重程度**：P1
- **仓库**：armada（后端）
- **文件与行号**：
  - `armada-api/src/main/resources/db/migration/V153__hyperlink_data_package.sql:3`（`data_package` 建表，只有 `tenant_id`）
  - `armada-api/src/main/resources/db/migration/V154__hyperlink_template.sql:1`（`hyperlink_template` 建表，只有 `tenant_id`）
  - `armada-api/src/main/java/com/armada/hyperlink/data/controller/DataPackageController.java:45,60,73,88,97,109,117,124,135,146,157`
  - `armada-api/src/main/java/com/armada/hyperlink/data/service/impl/DataPackageServiceImpl.java:87,113,121,146,168,178,199,218,234,261`
  - `armada-api/src/main/java/com/armada/hyperlink/data/model/entity/DataPackage.java:9`（只有 `tenantId`，无 `ownerUserId`）
- **违反的设计条款**：§1「普通用户只访问自己归属的数据」；§3.3「需要独立授权和生命周期的聚合根保存 owner」；§6「每个切片必须同时覆盖列表、详情、创建、修改、删除、批量、导出/下载、异步回调」
- **触发方式**：同租户任意被授予 `tenant:hyperlink_data:*` / `tenant:hyperlink_template:*` 的普通用户 U2，直接调用 `GET /api/data-packages`、`GET /api/data-packages/{id}`、`GET /api/data-packages/{id}/phones`、`POST /api/data-packages/export`、`PUT /api/data-packages/{id}`、`DELETE /api/data-packages/{id}`，即可读取、导出、修改、删除 U1 创建的数据包及其全部手机号明细。超链模板同理。
- **实际影响**：一个完整的用户私有业务域（号码资源池 + 营销模板）在同租户内完全共享且互相可写。该域是 `origin/1.0.3-snapshot` 在隔离分支第一次合并（`e37e1bd4`，08-27 11:26）之后、最终合并（`0cfc9b70`，08-29 10:41）之前新增的，隔离设计与实施从未覆盖它。合并后分支上「同租户用户数据隔离已完成」的结论不再成立。
- **代码 / 测试证据**：

  ```
  $ grep -rn "owner_user_id\|ownerUserId" src/main/java/com/armada/hyperlink/ src/main/resources/mapper/hyperlink/
  (无输出)

  $ 域级 DataScope 覆盖统计
  hyperlink: files-using-scope=0  services=10

  $ git log --oneline e37e1bd4..64b1b938 | grep hyperlink
  64b1b938 fix(hyperlink): align data package txt validation
  25117939 feat(hyperlink): add click analysis contracts and batch assignment
  e46ba0b8 feat(hyperlink): complete data package replication
  500930bd feat(hyperlink): implement phase one templates
  c959ee5d feat(hyperlink): implement phase one data packages
  ```

  V155 为**所有租户**种下 `/hyperlink` 菜单和 `tenant:hyperlink_data:*` 权限（`V155__hyperlink_marketing_menu_rbac.sql:28-71`），普通角色可被授予，因此路径真实可达。
- **建议修复方向**：新增 V15x 给 `data_package` / `hyperlink_template` 增加 nullable `owner_user_id`，并按既有 `active_name_key` / `unowned_name_key` 模式改写 `uq_data_package_name`、`uq_hyperlink_template_name`；`DataPackageMapper` / `HyperlinkTemplateMapper` 全部语句接入 `dataScope` fail-closed 片段；`data_package_phone` / `data_package_import` / `data_package_stat` 通过 `data_package_id` 继承；`hyperlink_template` 的 `link_preview_asset_id` / `body_main_asset_id` 复用 `DataScopeAccess.requireSameOwner`（`MarketingTemplateServiceImpl.java:254-264` 是现成范式）。若本轮不做，必须在设计文档和 summary 中显式声明超链域不在隔离范围内，并阻止把它当作"已完成隔离"上线。

---

### P1-2　删除 IP 代理会跨 owner 强制重登其他用户的在线账号

- **严重程度**：P1
- **仓库**：armada（后端）
- **文件与行号**：
  - `armada-api/src/main/java/com/armada/resource/controller/IpProxyController.java:113-116`（`POST /api/ip-proxies/batch-delete`）
  - `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyDeletionServiceImpl.java:54`
  - `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java:354-374`
  - `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java:488-494`
- **违反的设计条款**：§4「`ip_proxy` 不增加 owner……**分配、解绑和检测操作以目标账号的 owner 为授权入口**」；summary「IP 资源保持平台/租户共享模型……但账号绑定、解绑等操作必须校验账号 owner」
- **触发方式**：U2 调用 `POST /api/ip-proxies/batch-delete`，`ids` 传入当前绑定着 U1 在线账号的代理 ID（代理列表对全租户可见，`validAccountCount` 直接暴露哪些代理被占用）。
- **实际影响**：`reloginOnlineAccountsByProxyIds` 沿绑定关系找出**任意 owner** 的在线账号，投递上线/重登协议命令并重新分配 IP。U2 因此可以对 U1 的账号触发真实协议操作、更换出口 IP、造成掉线窗口。这是设计里明确列出的授权入口之一，实现完全缺失。
- **代码 / 测试证据**：

  ```java
  // AccountOnlineCommandServiceImpl.java:354-371
  public AccountBatchOnlineVO reloginOnlineAccountsByProxyIds(List<Long> proxyIds) {
      ...
      List<Long> boundAccountIds = ipProxyService.findBoundAccountIdsByProxyIds(normalizedProxyIds); // 无 owner 过滤
      List<Long> onlineAccountIds = selectOnlineAccountIds(boundAccountIds);                          // 无 owner 过滤
      ...
      AccountBatchOnlineVO vo = enqueueOnlineBatch(ids, SOURCE_IP_DELETE_RELOGIN, ...);
  ```

  对比同类入口 `offlineBatch`（`:384-390`）和 `offlineBatchWithProtocolBackends`（`:395-399`）都调用了 `requireCanAccess(ids, accountsById)`；`enqueueOnlineBatch`（`:754-761`）只调用了 `requireAssignedOwner`，**不校验 owner 是否属于当前范围**。

  ```
  $ 域级 DataScope 覆盖统计
  resource: files-using-scope=0  services=6
  ```
- **建议修复方向**：`reloginOnlineAccountsByProxyIds` 在 `selectOnlineAccountIds` 之后、`enqueueOnlineBatch` 之前加入 `DataScopeAccess.requireCanAccess(scope, account.getOwnerUserId(), "账号")`；SELF 范围应整批拒绝（与"混入不可见 ID 整批拒绝"一致），或至少把当前范围外的账号从代理删除链路中剔除并拒绝删除该代理。同时在 `enqueueOnlineBatch` 内统一补 `requireCanAccess`，避免后续再出现同类旁路。

---

### P1-3　IP 代理列表 / 明细 / 导出向所有普通用户返回代理鉴权凭据

- **严重程度**：P1
- **仓库**：armada（后端）
- **文件与行号**：
  - `armada-api/src/main/java/com/armada/resource/model/vo/IpProxyVO.java:41,45`（`username` / `password`）
  - `armada-api/src/main/java/com/armada/resource/controller/IpProxyController.java:30,47-49`（类级仅 `@PreAuthorize("hasAuthority('tenant:resource:ips:list')")`）
  - `armada-api/src/main/java/com/armada/resource/controller/IpProxyStatsController.java:34,70-88`
- **违反的设计条款**：§4「**普通用户不能枚举代理敏感信息**」
- **触发方式**：任意持 `tenant:resource:ips:list` 的普通用户调用 `GET /api/ip-proxies`（或 `GET /api/ip-proxies/stats/countries/{region}/export`），分页拉取全租户代理及其 `username` / `password`。
- **实际影响**：租户内全部代理凭据对普通用户可枚举、可导出。summary 的 test1 验收记录（"U1/U2/管理员 IP 列表均为 3010"）恰恰证明普通用户确实拿得到全量列表，即该条设计要求在生产路径上未实现。
- **代码 / 测试证据**：`com.armada.resource` 包内 `DataScopeAccess` / `DataScopeContext` 出现次数为 0；`IpProxyVO` 是列表接口的直接出参且含明文 `password`。
- **建议修复方向**：本条不需要给 `ip_proxy` 加 owner。可选：(a) 拆分 VO，非 `TENANT_ADMIN` 的 `DataScope` 下对 `username` / `password`（以及 `outboundIp` 等出口特征）脱敏或不下发；(b) 把凭据下发和导出收敛到独立权限位（如 `tenant:resource:ips:credential`）并只授予管理员角色。设计文档同时应把"敏感信息"的字段边界写死。

---

### P2-1　审核提交上 `GroupDetailMemberRemovalIdentityTest` 7/7 报错（基线通过），群成员移除路径失去全部测试覆盖

- **严重程度**：P2（本分支引入的测试回归，非产品缺陷）
- **仓库**：armada（后端）
- **文件与行号**：`armada-api/src/test/java/com/armada/group/service/impl/GroupDetailMemberRemovalIdentityTest.java:76,244-249`；被测生产逻辑 `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java:1106`（`requireLiveTarget` → `requireAssignedOwner`）
- **违反的设计条款**：§7「唯一键冲突、并发更新、软删复活和管理员视图均有 H2/SQL 契约测试」的证据要求；AGENTS.md「没有真实输出不得声称通过」
- **触发方式**：`mvn -Dtest=GroupDetailMemberRemovalIdentityTest test`
- **实际影响**：`mvn test` 在审核提交上是红的，分支在"CI 全绿"门禁下不可合并。更重要的是，该测试类覆盖的 7 个群成员移除身份识别场景（PN/LID 匹配、超时、确认移除）在本分支上**全部在断言之前就抛异常**，等于这条会产生真实协议命令的路径失去了回归保护。
- **代码 / 测试证据**：

  ```
  # 审核提交 0cfc9b70
  [ERROR] Tests run: 7, Failures: 0, Errors: 7 -- in GroupDetailMemberRemovalIdentityTest
  [ERROR] GroupDetailMemberRemovalIdentityTest.kickMatchesPnRequestToLidMetadataByPhone:109
          » Business 历史无归属群链接不能执行，请重新创建或等待归属分配功能上线

  # 基线 origin/1.0.3-snapshot (64b1b938)
  [INFO]  Tests run: 7, Failures: 0, Errors: 0 -- in GroupDetailMemberRemovalIdentityTest
  ```

  分支已为该测试补了 `DataScopeContext.open(DataScope.all(1L))`（:76）和 mapper 签名（:244-249），但 fixture 里的 `GroupLink` 仍未 `setOwnerUserId(...)`，撞上新增的 `requireAssignedOwner` 门禁。
- **建议修复方向**：在 fixture 中给 `link` 设置有效 `ownerUserId`，并**额外补一个用例**断言"历史 NULL owner 群链接的踢人请求被 fail-closed 拒绝"——这正是本次新增的安全行为，目前没有任何用例正向覆盖它。

---

### P2-2　拉群营销候选群的 3 个新增 owner 隔离用例从未真正执行

- **严重程度**：P2（验证缺口，根因是基线问题）
- **仓库**：armada（后端）
- **文件与行号**：
  - `armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupMarketingGroupMapperInMemoryTest.java:219`（`candidateMapperSeparatesOwnersAndFailsClosed`）、`:257`（`serviceUsesAllForAdminListingButSelfForAdminWaitingCreation`）、`:279`（`ordinaryUserCannotSeeAnotherUsersOccupancyTaskName`）
  - 被测 SQL：`armada-api/src/main/resources/mapper/task/PullTaskGroupMarketingCandidateMapper.xml:5-13,134-137`
- **违反的设计条款**：§7 验证矩阵；§6「未完整覆盖前不得打开该业务的隔离开关」
- **触发方式**：`mvn -Dtest=PullTaskGroupMarketingGroupMapperInMemoryTest test`
- **实际影响**：候选群列表的 owner 隔离（`handleDataScope` + `a.owner_user_id = handle.owner_user_id`）代码看起来正确，但它的三个专项用例在 H2 上直接 `BadSqlGrammar`，没有一次断言被执行。summary 中"拉群营销……H2 真 Mapper 边界"的完成声明在当前代码上无法复现。
- **代码 / 测试证据**：

  ```
  Cause: org.h2.jdbc.JdbcSQLSyntaxErrorException:
         Column "current_group.group_classification" not found

  # 基线 origin/1.0.3-snapshot：Tests run: 11, Failures: 1, Errors: 7
  # 审核提交       0cfc9b70：Tests run: 14, Failures: 1, Errors: 10   ← 新增 3 个用例全部 error
  ```

  `group_classification` 由 1.0.3 的 V140 引入，该测试类的内存 schema 未同步——**基线就已损坏**，本分支只是把新用例挂在了一个跑不起来的类上。
- **建议修复方向**：不属于本分支的产品缺陷，但本分支不应把安全断言放在已知红的测试类里。要么先修内存 schema（补 `group_classification` 列），要么把这三个 owner 隔离用例迁到能真实执行的 H2 用例类（例如 `GroupUserDataScopeMapperH2Test` 的写法）。在此之前，拉群营销候选群的隔离属于"缺少证据"。

---

### P2-3　`rollback.sql` 漏掉 V150 删除的 `uq_hgpe_tenant_idempotency`

- **严重程度**：P2
- **仓库**：armada（后端）
- **文件与行号**：
  - `.harness/changes/2026-08-26-user-data-isolation/rollback.sql:12`（声称"V150 的历史群 owner/幂等结构刻意保留；恢复旧幂等键前同样必须确认无跨 owner 冲突"），全文无 `historical_group_pull_execution` 的 `ADD UNIQUE KEY`
  - `armada-api/src/main/resources/db/migration/V150__historical_group_pull_user_data_ownership.sql:134`（`ALTER TABLE historical_group_pull_execution DROP INDEX uq_hgpe_tenant_idempotency`）
- **违反的设计条款**：§8「只有在业务写入持续暂停、确认没有冲突，并**恢复旧唯一键**后，才允许切回旧应用」
- **触发方式**：按 `rollback.sql` 执行结构回滚后切回旧应用。
- **实际影响**：`historical_group_pull_execution` 的租户级幂等键不会被恢复，旧应用的"按 `(tenant_id, idempotency_key)` 幂等"假设失效，历史群拉取执行可能重复创建。这是 rollback 文件里唯一遗漏的一处——V141/V142/V148/V149 的 7 个被删索引都有对应的守卫 + 恢复语句。
- **代码 / 测试证据**：

  ```
  各迁移 DROP 的索引 → rollback.sql 恢复情况
  V141: uq_tenant_name                      → rollback.sql:213 已恢复
  V142: uq_tenant_name                      → rollback.sql:45  已恢复
  V148: uq_url / uq_group_folder_name /
        uq_name / uq_group_batch_task_request → rollback.sql:109,120,131,142 已恢复
  V149: uq_normal_group_creation_task_idem  → rollback.sql:174 已恢复
  V150: uq_hgpe_tenant_idempotency          → 未恢复  ✗
  ```
- **建议修复方向**：在 `rollback.sql` 中补一段与 V149 同构的冲突守卫 + `ALTER TABLE historical_group_pull_execution ADD UNIQUE KEY uq_hgpe_tenant_idempotency (tenant_id, idempotency_key)`。

---

### P2-4　管理员拆分历史 NULL owner 分组会创建新的 NULL owner 分组

- **严重程度**：P2
- **仓库**：armada（后端）
- **文件与行号**：`armada-api/src/main/java/com/armada/account/service/impl/AccountGroupServiceImpl.java:343-372`，关键行 `:348`（`requireSameOwner(List.of(source))`）与 `:359-362`（`createOwned(..., source.getOwnerUserId())`）
- **违反的设计条款**：§3.2「所有用户创建入口由后端写 `owner_user_id = AuthPrincipal.userId`」；§5「**迁移期间新写入必须非空**」；summary「管理员只可查看、停止或清理这类历史聚合」
- **触发方式**：`TENANT_ADMIN` 对一个 `owner_user_id IS NULL` 的历史账号分组调用拆分接口。
- **实际影响**：`requireCanAccess(ALL, null)` 通过 → `createOwned(dto, null)` 连续创建 N 个 `owner_user_id = NULL` 的新分组。迁移后本应只减不增的"历史无归属"数据集反而增长，且这些新分组对所有普通用户不可见、只能等未来的归属分配功能处理。批量删除、合并路径没有这个问题（不产生新行）。
- **代码 / 测试证据**：

  ```java
  // :180  private AccountGroupVO createOwned(AccountGroupDTO dto, Long ownerUserId)
  // :186  row.setOwnerUserId(ownerUserId);      // 直接落 null
  // :359  targetIds.add(createOwned(new AccountGroupDTO(source.getName()+"-"+i, ...),
  //                                 source.getOwnerUserId()));   // null 继承
  ```

  对照 `create()`（`:176`）走的是 `scope.ownerUserIdForCreate()`，永不为 null。
- **建议修复方向**：在 `split`（以及任何会继承 owner 新建行的路径）加 `DataScopeAccess.requireAssignedOwner(source.getOwnerUserId(), "分组")`，把历史无归属分组的拆分显式拒绝，与群链接/任务侧的"历史无归属不能执行"口径统一。

---

### P2-5　1.0.3-snapshot 已单独携带 V141–V152，设计 §8 的"迁移与 owner-aware 应用原子切换"前提不成立

- **严重程度**：P2（发布风险，非代码缺陷）
- **仓库**：armada（后端）
- **文件与行号**：`origin/1.0.3-snapshot` 上的 `armada-api/src/main/resources/db/migration/V141__*.sql` … `V152__*.sql`（来源提交 `76a72de3 fix(db): reconcile test1 flyway lineage`）
- **违反的设计条款**：§8「上线时必须进入维护窗口，停止旧版本写流量，执行迁移并**一次性切换到 owner-aware 应用**」
- **触发方式**：任何基于 `1.0.3-snapshot`（含 hyperlink、`feat/contact-marketing` 等分支）的发布都会执行 V141–V152。
- **实际影响**：1.0.3-snapshot 上有全部 12 个迁移文件，但**没有** `DataScope` / `DataScopeAccess` / `DataScopeContext`（该分支 `shared/security/` 下只有 `AuthPrincipal.java`）。schema 与 owner-aware 代码已经解耦发布，任何 1.0.3 发布都会持续产生 `owner_user_id IS NULL` 的新数据；等隔离分支上线后，这些数据对普通用户直接消失。
- **代码 / 测试证据**：

  ```
  $ git ls-tree -r --name-only origin/1.0.3-snapshot -- .../shared/security/
  armada-api/src/main/java/com/armada/shared/security/AuthPrincipal.java     ← 仅此一个

  $ 12 个 V141-V152 文件在两个分支上逐一 diff：全部 identical
  ```

  缓解事实：V141/V142/V148/V149/V150 都建了 `unowned_*_key` 生成列 + 独立唯一索引，所以旧应用写 NULL owner 时**租户级唯一性仍然成立**，不会出现重名 / 重 URL / 重幂等键。风险只在"数据归属"层面。
- **建议修复方向**：上线前统计各表 `owner_user_id IS NULL` 且 `created_at > 迁移时间` 的行数，明确这批数据的处置方案（管理员分配或批量回填到实际创建人），并在 summary 的部署章节把这一前提差异写清楚——当前 summary 的"不允许旧、新应用滚动混跑"已经被现实打破了。

---

### P2-6　`POST /api/protocol/restart` 可由任意持 `tenant:account:view` 的用户触发协议进程重启

- **严重程度**：P2（既有问题，设计未覆盖）
- **仓库**：armada（后端）
- **文件与行号**：`armada-api/src/main/java/com/armada/platform/protocol/controller/ProtocolProcessController.java:12-24`
- **违反的设计条款**：设计未涉及。属于 §7「验证矩阵」意义上的隔离目标被绕过的旁路。
- **触发方式**：普通用户（`tenant:account:view` 是账号列表的基础权限）`POST /api/protocol/restart`。
- **实际影响**：重启协议层 master/worker 进程，影响该协议节点上**所有 owner、所有租户**的在线账号。用户数据隔离在"读写"层面做得很细，但这个入口可以一键把别人的账号全部打断。
- **代码 / 测试证据**：

  ```java
  @RequestMapping("/api/protocol")
  @PreAuthorize("hasAuthority('tenant:account:view')")   // 只读权限守护一个全局破坏性操作
  public class ProtocolProcessController {
      @PostMapping("/restart")
      public ApiResponse<ProtocolRestartVO> restart() { ... }
  ```
- **建议修复方向**：不属于本需求范围，但建议单独收口：改为平台级权限（或 `TENANT_ADMIN` 专属），并记录审计。至少应在设计文档的"特殊资源"一节把它列为已知平台级旁路。

---

### P3-1　设计文档 §8 的迁移编号与实际 Flyway 不一致

- **严重程度**：P3（文档）
- **仓库**：armada
- **文件**：`docs/superpowers/specs/2026-08-26-user-data-isolation-design.md` §8
- **问题**：设计写"账号切片的 **V140**……模板切片的 **V141**……**V142-V145** 增加任务 owner，**V146** 新增头像 owner 元数据，**V147** 改写群运营唯一键，**V148** 改写新建普群幂等键"。实际是 V141=账号、V142=模板、V143–V146=任务、V147=头像、V148=群运营、V149=新建普群，整体错位一位；V149–V152（新建普群、历史群、CAPI Outbox、导出 scope）在设计文档中完全没有记录。`.harness/changes/2026-08-26-user-data-isolation/summary.md` 的部署章节是正确的。
- **建议**：把 §8 更新到 V141–V152 的真实编号与内容，否则回滚演练会按错误编号定位文件。

### P3-2　`promotion_landing_template` 无 owner 且可被任意用户修改

- **严重程度**：P3
- **仓库**：armada
- **文件与行号**：`armada-api/src/main/java/com/armada/promotion/template/controller/PromotionTemplateController.java:36,49`（`GET /api/promotion-templates/query`、`PATCH /{id}/remark`）；`armada-api/src/main/java/com/armada/promotion/stats/BuyerChannelStatsService.java:58-59`（筛选项无 owner 过滤）
- **问题**：推广渠道已按 owner 隔离，但它引用的落地页模板是租户共享且任意用户可改备注。设计 §4 只谈了渠道和统计，未定义落地页模板的归属语义。
- **建议**：在设计文档中明确落地页模板是"租户共享配置"（类比 IP）还是用户私有；若是共享，把 `PATCH remark` 收敛到管理员权限。

### P3-3　前端全量 Node 测试在本机无法运行，无法复现 summary 的基线

- **严重程度**：P3（验证缺口，环境原因）
- **仓库**：wheel-saas-pure-web
- **问题**：`pnpm test` 在 Node v24.13.0 + tsx 4.23.12 下，50 个测试套件因 `ERR_UNKNOWN_FILE_EXTENSION: Unknown file extension ".css" for .../nprogress/nprogress.css` 直接崩溃。summary 声称"全量 Node 回归仅保留既有 5 个未修改测试套件失败"，本机无法复现该基线。这是模块加载器行为，与本分支改动无关。
- **建议**：在仓库里锁定 Node 版本（`.nvmrc` / `engines`），或给 `node --test` 加 CSS stub loader，让 summary 的"全量回归"结论可被独立复现。

---

## 二、设计验收矩阵

> 验收标准直接从 `2026-08-26-user-data-isolation-design.md` 推导，未使用任何外部安全检查清单。

| # | 设计要求 / 安全不变量 | 业务域 | 实现文件 | 迁移 | 测试 | 结论 |
|---|---|---|---|---|---|---|
| R1 | 普通用户只访问自己归属的数据 | 账号/群/任务/营销/推广 | 各域 `*ServiceImpl` + Mapper XML `dataScope` 片段 | V141–V152 | `*UserDataScopeMapperH2Test`（74 tests 通过） | **部分满足**：账号、群运营、五类任务、模板、推广渠道满足；**hyperlink 全域不满足（P1-1）**；IP 凭据枚举不满足（P1-3） |
| R2 | `TENANT_ADMIN` 访问当前租户全部数据 | 全域 | `DataScope.fromPrincipal`（`DataScope.java:52-58`） | — | `DataScopeTest`、各 `*MapperH2Test` 的 ALL 分支 | 满足 |
| R3 | IP 作为平台/租户共享资源，不做用户私有化 | resource | `com.armada.resource`（无 owner 列） | 无 | test1 实测 U1/U2/admin 均 3010 | 满足 |
| R4 | DataScope 由可信 `AuthPrincipal` 构造；缺 scope / 未知 mode / owner 不匹配 fail-closed | shared/security | `DataScope.java:24-42`、`DataScopeContext.java:36-41`、`DataScopeAccess.java:15-47`、`TokenAuthenticationFilter.java:56,75,92` | — | `DataScopeTest`(3)、`DataScopeContextTest`(2)、`DataScopeAccessTest`(7) 全通过 | 满足。Mapper 层统一 `<otherwise>AND 1 = 0</otherwise>`，SYSTEM 也落入 fail-closed |
| R5 | 所有创建入口由后端写 owner，忽略前端 owner 入参 | 全域 | `scope.ownerUserIdForCreate()`（`DataScope.java:76-81`）；推广渠道见 `PromotionChannelServiceImpl` | V141–V151 | `PromotionChannelServiceImplTest`（伪造 owner 被拒） | **部分满足**：`AccountGroupServiceImpl.split` 会写 NULL owner（P2-4）；hyperlink 无 owner 概念（P1-1） |
| R6 | 管理员新建数据归管理员本人 | 全域 | `ownerUserIdForCreate()` 恒返回 `actorUserId` | — | 各域 Service 单测 | 满足 |
| R7 | 新聚合引用的账号和分组必须同 owner | 任务/营销/群 | `DataScopeAccess.requireOwnedByActorForCreate` / `requireSameOwner`（`:97-125`） | — | `JoinTaskUserDataScopeServiceTest`、`GroupPullMarketingTaskOwnershipTest`(6 通过) | 满足 |
| R8 | 模板只能引用同 owner 图片；拒绝管理员复制他人模板 | 营销模板 | `MarketingTemplateServiceImpl.java:142-148`（复制拒绝）、`:254-264`（图片同 owner） | V142 | `MarketingTemplateServiceImplTest`、`MarketingTemplateFileServiceImplTest` | 满足（营销模板）。**超链模板的 copy 无同类约束**，见 P1-1 |
| R9 | `created_by` / `updated_by` 独立记录操作者 | 全域 | 各 Mapper 保留审计列 | V141–V152 均未改写 | 迁移 SQL 契约测试 | 满足 |
| R10 | 9 个聚合根 + 明细继承链 | 全域 | 20 个 `owner_user_id` 列 | V141–V151 | 12 个 `*MigrationSqlTest` 全通过 | 满足（设计列出的 9 条链全部落地；hyperlink 是设计外新增域） |
| R11 | 按 ID 的详情/更新/批量必须在同一 SQL 或同一事务内校验根 owner | 全域 | `*ForScope` 语句 + Service 端 `requireCanAccess` | — | `PullTaskControllerUserDataScopeH2Test`(3)、`JoinTaskUserDataScopeMapperH2Test` 等 | 满足。已核对全部 87 个 Mapper XML（`<include>` 递归展开后）与其调用方，未发现可被用户 ID 直达的未授权写 |
| R12 | ip_proxy 不加 owner；普通用户不能枚举敏感信息；分配/解绑/检测以目标账号 owner 授权 | resource + account | `IpProxyController`、`IpProxyDeletionServiceImpl`、`AccountOnlineCommandServiceImpl` | 无 | 无 | **不满足**：凭据可枚举（P1-3）、删除代理跨 owner 重登（P1-2）。仅"不加 owner"这一半满足 |
| R13 | 群 canonical 事实租户级；句柄/标签/文件夹/导入批次归 owner | group | `GroupLinkMapper.xml` `directScopeFilter`、`GroupFolderMapper.xml`、`GroupLinkLabelMapper.xml` | V148 | `GroupUserDataScopeMapperH2Test`(6)、`GroupUserDataIsolationMigrationSqlTest` | 满足 |
| R14 | 头像元数据保存 owner；普通用户只能用本人头像；历史无元数据仅管理员和已授权执行链可读 | task | `PullTaskGroupAvatarServiceImpl.java:98-110,122-128,154-158,232-239` | V147 | `PullTaskGroupAvatarFileMapperH2Test`、`PullTaskGroupAvatarServiceTest` | 满足 |
| R15 | 推广渠道服务端写 owner、编辑不改 owner；公开落地页/配对与 CAPI 正式投递不依赖登录态 scope | promotion | `PromotionChannelServiceImpl.java:410,441`、`PromotionCapiEventDispatcher.java:104`、`PromotionChannelPublicController` | V151 | `PromotionChannelUserDataScopeMapperH2Test`(3)、`PromotionCapiOutboxUserDataIsolationMigrationSqlTest` | 满足 |
| R16 | 渠道统计经 channel_id 继承；直接 JDBC 先读范围内渠道；SELF 解绑统计过滤 `account.owner_user_id` | promotion/stats | `BuyerChannelStatsService.java:44-53,144-176,244-255,260-266` | — | `BuyerChannelStatsUserDataScopeH2Test`(3 通过) | 满足（`promotion_landing_template` 筛选项例外，见 P3-2） |
| R17 | 物理唯一键仍按租户唯一；冲突返回通用业务冲突不泄露对方信息 | 全域 | `unowned_*_key` 生成列 + 独立唯一索引 | V141/V142/V148/V149/V150 | 各 `*MigrationSqlTest` | 满足 |
| R18 | owner 列允许 NULL；不按 `created_by` 猜；SELF 不匹配 NULL；ALL 可见；**迁移期间新写入必须非空** | 全域 | `DataScopeAccess.requireCanAccess:41-46`、`requireAssignedOwner:62-70` | V141–V151 全部 `DEFAULT NULL`、无回填 | `DataScopeAccessTest`、各 H2 用例的历史 NULL 分支 | **部分满足**：前四条满足；"新写入必须非空"被 `AccountGroupServiceImpl.split` 破坏（P2-4），并被 1.0.3-snapshot 单独发布迁移的现状系统性破坏（P2-5） |
| R19 | 每切片覆盖列表/详情/创建/修改/删除/批量/导出下载/异步回调 | 全域 | 见各域 | — | 见各域 | **部分满足**：设计列出的 5 个切片满足；hyperlink 为 0 覆盖（P1-1） |
| R20 | U1/U2/管理员/跨租户/混合批量整批拒绝/缺 scope/后台恢复 owner 并清理上下文 | 全域 | 60 处 `DataScopeContext.open` 全部 try-with-resources；`TokenAuthenticationFilter` 首尾双清 | — | 74 个隔离专项测试通过 | 满足，但**群成员移除（P2-1）与拉群营销候选群（P2-2）两处证据缺失** |
| R21 | 迁移与回滚脚本与实际 Flyway 一致；回滚不删 owner 列 | — | `.harness/changes/2026-08-26-user-data-isolation/db-migrations.sql`、`rollback.sql` | V141–V152 | Flyway 版本契约测试通过 | **部分满足**：不删列、不改 flyway 历史满足；V150 索引恢复缺失（P2-3）；设计 §8 编号错位（P3-1） |

---

## 三、独立验证记录

> 全部为本次实际执行的只读验证，未因 summary 写"测试通过"而跳过。

| 验证项 | 命令 | 结果 |
|---|---|---|
| 后端编译 | `mvn -o -DskipTests test-compile` | ✅ EXIT=0 |
| 隔离专项测试 | `mvn -o -Dtest='*UserDataScope*,*UserDataIsolation*,DataScope*,...' test` | ✅ **74 tests, 0 failures, 0 errors, 0 skipped** |
| 本需求全部改动测试（173 类，排除 `*DbTest`） | `mvn -o -Dtest=<173 classes> test` | ⚠️ **1285 tests, 1 failure, 29 errors** |
| ├ 环境类（Docker / Testcontainers / `@SpringBootTest` 缺真库） | `GroupCurrentLocalWriteMySqlTest`、`GroupLinkRegistryBatchMySqlTest`、`GroupListCurrentMapperMySqlTest`、`GroupLinkRegistryServiceImplTest`、`GroupCreationMarketingTaskServiceImplTest` | 21 errors — **测试基建问题**，与分支无关 |
| ├ 基线问题 | `PullTaskGroupMarketingGroupMapperInMemoryTest` | 基线 7 errors → 分支 10 errors — **基线损坏 + 新增用例失效（P2-2）** |
| └ 分支回归 | `GroupDetailMemberRemovalIdentityTest` | 基线 0 errors → 分支 **7 errors** — **本分支引入（P2-1）** |
| 基线对照 | 在 `origin/1.0.3-snapshot` 独立 worktree 跑同两个类 | ✅ 已确认上述分类 |
| 前端类型检查 | `pnpm typecheck` | ✅ EXIT=0 |
| 前端生产构建 | `pnpm build` | ✅ EXIT=0（4.33 MB） |
| 前端隔离专项测试 | `node --import tsx --test src/utils/user-data-storage-key.test.ts src/views/UserDataStorageIsolationContract.test.ts src/router/auth-access.test.ts` | ✅ **7 tests, 0 fail** |
| 前端全量测试 | `pnpm test` | ❌ 50 套件因 Node 24 + tsx 无法加载 `.css` 崩溃 — **本机环境限制（P3-3）**，无法复现 summary 基线 |
| 迁移一致性 | V141–V152 在两分支逐文件 diff | ✅ 完全一致 |
| 直接 SQL 旁路 | `grep -rl JdbcTemplate src/main/java` | ✅ 仅 `PullTaskController`、`BuyerChannelStatsService`，两者均已接 `DataScope` 且有 H2 越权测试 — summary 该条声明属实 |
| Mapper 全量扫描 | 87 个 XML，`<include>` 递归展开后按 19 张 owner 表交叉比对 | ✅ 未发现可被用户可控 ID 直达的未授权读写；未标注 scope 的语句均由 Service 层根授权覆盖（逐条核对了账号分组、导入批次、模板、任务、群链接、渠道、导出、头像） |

### 前后端契约一致性核对

| 契约点 | 后端 | 前端 | 结论 |
|---|---|---|---|
| 登录失效 | `AUTH_INVALID=40104`、`TENANT_MISSING=40101`；`BusinessException` 走 HTTP 200 + code | `auth-access.ts` 仅 401 / 40101 / 40104 触发登出 | 一致 |
| 无权访问 | `ACCESS_DENIED=40302`；`@PreAuthorize` 拒绝走 `JsonAccessDeniedHandler` → HTTP 403 + 40302 | 403 与 40302 均保留登录态，交业务页展示 | 一致 |
| 资源不可见 | SELF 不匹配 owner 抛 `NOT_FOUND=40401`（不泄露存在性） | 延续既有 404 业务错误处理，不登出 | 一致 |
| 可信身份 | 登录响应返回 `user.id` / `tenant.id` | `user.ts:81-87` 保存，`user-data-storage-key.ts` 拼 `:tenant-{id}:user-{id}`，身份缺失返回 `null` 失败关闭 | 一致 |
| 浏览器私有状态 | — | 账号上线冷却、拉群草稿/计划、拉群营销等待池 token、普群任务 ID 四处均已分区 | 一致 |

---

## 四、结论

### 4.1 是否建议合并

**不建议直接合并。**

后端主体隔离实现质量很高——`DataScope` 三态模型、Mapper 层 `AND 1 = 0` fail-closed、60 处后台 owner 恢复全部 try-with-resources、混合 ID 整批拒绝、历史 NULL owner 执行门禁，都与设计一致且有真实 H2 测试支撑。但存在三类阻塞：

1. **合并把一个零隔离的新业务域（hyperlink）带进了"已完成隔离"的分支**（P1-1）——这是"最新 1.0.3 合并导致原设计覆盖失效"的具体实例。
2. **设计 §4 关于 IP 的两条明确要求完全未实现**（P1-2、P1-3），其中 P1-2 是可被普通用户触发的跨 owner 协议写。
3. **审核提交上 `mvn test` 是红的**（P2-1），且两处安全断言从未真正执行（P2-1 新行为无正向用例、P2-2 三个用例 SQL 报错）。

### 4.2 合并前必须修复

| 项 | 内容 |
|---|---|
| 必修 1 | **P1-2**：`reloginOnlineAccountsByProxyIds` 补 `requireCanAccess`，SELF 范围整批拒绝 |
| 必修 2 | **P1-3**：非管理员范围下不下发 `IpProxyVO.username` / `password`（或收敛到独立权限位） |
| 必修 3 | **P2-1**：修复 `GroupDetailMemberRemovalIdentityTest`，并补"历史 NULL owner 群链接踢人被拒"正向用例 |
| 必修 4 | **P1-1**：二选一 —— 给 hyperlink 域补 owner 隔离切片；或在设计文档 + summary 中显式声明超链域不在本轮范围，并给出后续切片计划与上线约束（不能以"隔离已完成"的口径发布） |
| 建议同批 | **P2-3**（rollback 补 V150 索引恢复）、**P2-4**（split 拒绝历史无归属分组）、**P3-1**（设计 §8 编号修正）——成本极低，且都直接影响回滚演练和数据正确性 |

### 4.3 仍缺少的验证

1. **拉群营销候选群 owner 隔离**：三个专项 H2 用例因基线 `group_classification` schema 缺失从未执行（P2-2）。
2. **群成员移除 / 实时协议入口**：7 个用例在本分支全部报错，该路径当前无任何有效回归（P2-1）。
3. **真实 MySQL Mapper 验证**：`*DbTest` / `*MySqlTest` / `@SpringBootTest` 类因本机无 Docker、无真库全部跳过或报错。summary 中依赖真库的结论（Flyway 152、20 个 owner 列生效、test1 U1/U2/管理员越权矩阵）**无法独立复现**，只能作为开发方的历史记录，不构成本次审核的证据。
4. **前端全量回归基线**：本机 Node 24 无法运行（P3-3）。前端隔离本身的 7 个契约测试 + typecheck + build 已独立验证通过。
5. **1.0.3-snapshot 已产生的 NULL owner 存量数据**：无任何测量。上线前需实测各表 `owner_user_id IS NULL` 行数与创建时间分布（P2-5）。
6. **hyperlink 域**：零测试、零验证（P1-1）。

### 4.4 对设计文档本身的遗漏与歧义

1. **§8 迁移编号整体错位一位**，且未记录 V149–V152（P3-1）。设计是回滚演练的第一手依据，必须与 Flyway 对齐。
2. **§4「普通用户不能枚举代理敏感信息」没有定义"敏感信息"的字段边界**，也没有指定实现机制（脱敏？独立权限位？），导致该条在实现中被整体跳过（P1-3）。
3. **§4 未定义 `promotion_landing_template` 的归属语义**（P3-2）——推广域里唯一一张既非 owner 私有、又非只读平台数据的表。
4. **设计没有考虑"迁移文件先于 owner-aware 代码单独发布"的场景**（P2-5）。§8 假设了原子切换，现实是 1.0.3-snapshot 已独立携带 V141–V152。设计需要补一节"迁移已提前生效时的存量 NULL owner 处置"。
5. **设计没有覆盖平台级破坏性入口**（P2-6 的 `/api/protocol/restart`）。数据读写隔离做到位了，但"能不能打断别人的账号"这一维度不在设计的安全边界内。
6. **§3.2 与 §5 的"继承 owner"语义存在冲突**：§3.2 要求所有创建入口写 `AuthPrincipal.userId`，但拆分/合并这类"结构变更继承来源 owner"的操作在 §5 的"新写入必须非空"下没有明确规则，直接导致了 P2-4。设计应显式规定：继承路径遇到 NULL owner 来源时必须拒绝，而不是继承 NULL。
