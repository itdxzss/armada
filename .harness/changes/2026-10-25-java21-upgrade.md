# 变更记录：后端 Java 17 → Java 21 升级

- 日期 / 分支 / worktree: 2026-10-25 / `1.0.4-snapshot`（基于 `origin/1.0.3-snapshot` fae19e83）/ `arm/armada-1.0.4`
- 需求来源: 用户 2026-10-25 明确要求"把 Armada 后端从 Java 17 升级到 Java 21，只做升级，不改业务代码"（技术栈升级已由用户授权）
- 状态: 已完成（本地提交，未推送、未部署）

## 目标（一句话）

后端编译目标、运行镜像和打包/部署脚本统一切到 Java 21，非真库测试结果与升级前逐条一致。

## 缺口拆解 / 任务清单
- [x] `armada-api/pom.xml`：`java.version` 17 → 21（Spring Boot parent 以此设置 `maven.compiler.release`）
- [x] `armada-deploy/backend.prebuilt.Dockerfile`：`eclipse-temurin:17-jre` → `eclipse-temurin:21-jre`
- [x] `armada-deploy/lib/armada.sh`、`deploy-test.sh`、`deploy-test-win.sh`、`package-prod.sh`：JDK 查找函数、变量、提示文案、候选目录与 `javac` 版本匹配全部改为 21；环境变量 `JAVA17_HOME` → `JAVA21_HOME`
- [x] `armada-deploy/deploy-test.test.sh`：`find_jdk17()` 断言同步为 `find_jdk21()`
- [x] 现状文档：`AGENTS.md`、`.harness/rules/工程结构.md`、`.harness/rules/编码规范.md`、`.harness/skills/deploy-verify/SKILL.md`

## 关键设计决策
- 只做版本切换：不启用虚拟线程、不升级 Spring Boot（仍 3.3.5）、不改业务代码。
- 环境变量直接改名为 `JAVA21_HOME`，不保留 `JAVA17_HOME` 兼容：找到的 JDK 必须是 21，旧变量指向的 17 本来也会被版本校验拒绝。
- 历史记录（`docs/plans`、`docs/superpowers`、`docs/business`、`docs/operations`、`.harness/changes`、evidence）保留原文；`docs/design.md` 的"迁移不升级 Java 17"是当时的设计决策记录，`armada-deploy/device-ingest/README.md` 的 JDK 17 描述是该功能交付时的测试记录，均未改。
- `package-prod.sh` 的 `find_jdk21` 沿用原逻辑，只校验 `javac` 存在、不校验版本（原 `find_jdk17` 即如此）；因编译使用 `--release 21`，用更高版本 JDK 构建也能产出 21 字节码。

## 验证（evidence-before-done）

### Maven 非真库测试（JDK 21.0.13，Homebrew）

```bash
cd armada-api
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn clean test -Dtest='!*DbTest,!*MySqlTest,!*MysqlTest' -Dsurefire.failIfNoSpecifiedTests=false
```

| 运行 | 编译目标 | 测试数 | Failures | Errors | 合计 |
|------|---------|-------|----------|--------|------|
| 升级前基线（fae19e83，全新 worktree 首次编译） | release 17（class major 61） | 5527 | 11 | 24 | 35 |
| 升级后（`clean test`） | release 21（class major 65） | 5527 | 11 | 24 | 35 |

两次的失败名单按"测试类#方法 + failure/error + 异常类型"逐条对比，结果完全一致，**无新增失败**。

> 注意：改 `java.version` 后如果不加 `clean`，maven-compiler 会因源码未变跳过重新编译，测试跑的仍是 major 61 的旧 class。本次第一次复跑就是这种情况，已作废，以上数据来自 `clean test`。

<details>
<summary>既有失败 35 项（升级前后一致）</summary>

| 测试 | 类型 |
|------|------|
| `com.armada.group.HistoricalGroupPreviewSchemaSqlTest#previewUpsertPersistsCreatedAtWithoutErasingKnownValueWithNull` | failure |
| `com.armada.group.mapper.GroupMembershipCountSemanticsMapperH2Test#accountGroupSyncRotationAndWatermarkUseCurrentSyncState` | error |
| `com.armada.group.mapper.GroupMembershipCountSemanticsMapperH2Test#accountListReadsCurrentBindingWhileMarketingTreeKeepsItsOwnCountSemantics` | error |
| `com.armada.group.mapper.GroupParticipantRolePrecedenceSqlTest#lowerTrustSourceCannotDowngradeKnownRole` | failure |
| `com.armada.group.service.impl.GroupLinkRegistryServiceImplTest#registerSelfBuiltGroupCreatesWaGroupLinkAndOwnerMembership` | error |
| `com.armada.group.service.impl.HistoricalGroupPullWorkerImplTest#addBatchExceptionFailsThatBatchAndContinuesNextBatchWithoutRetry` | failure |
| `com.armada.group.service.impl.HistoricalGroupPullWorkerImplTest#usesOneRandomPullerPersistedLinkMarketingFirstBatchesAndAddBasedStatistics` | failure |
| `com.armada.marketing.grouppull.service.GroupPullMarketingMaterialEntryServiceTest#explicitGroupBanFailsExecutionWithoutSchedulingAnotherMaterialAttempt` | error |
| `com.armada.marketing.mapper.GroupCreationMarketingTaskMapperSqlShapeTest#accountRetryMapperStatementsKeepItemRetryScopedToOneItem` | failure |
| `com.armada.marketing.mapper.MarketingGroupBanMapperH2Test#detailReadsCurrentBanSeparatelyFromSuccessfulAttempt` | failure |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#createAllowsPullingRestrictionButRejectsMessageRestriction` | error |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#createIgnoresExtraAccountsAndReturnsMatchedCount` | error |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#createIgnoresExtraFilesAndReportsUnmatchedFileCount` | error |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#createPairsAccountsByIdAscendingWithUploadedFileOrder` | error |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#createRejectsWhenNoMatchedItemsExist` | error |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#createStoresSendIntervalAndFiltersInvalidAndDuplicatePhones` | error |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#exportRejectsEmptySelection` | error |
| `com.armada.marketing.service.GroupCreationMarketingTaskServiceImplTest#exportSelectedTasksExpandsItemsAndCalculatesCounts` | error |
| `com.armada.security.BusinessControllerAuthorizationContractTest#permitsOnlyBusinessMenusThatActuallyUseSharedReadEndpoints` | failure |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#concurrentServicesAllowExactlyOnePoolToOccupyTheSameGroup` | error |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#currentBindingsOverrideStaleLegacyMembershipForCandidateEligibility` | error |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#deduplicatesGroupAndAggregatesEligibleManagersAcrossAccounts` | error |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#filtersBySourceAndKeepsOtherTenantsOut` | error |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#managerPhoneFilterDoesNotMatchOrdinaryMemberRelations` | error |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#rollsBackInsertedWaitingWhenExternalCountryAssemblyFails` | failure |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#serviceRoundTripUsesRealTransactionsAndMockedCountryReference` | error |
| `com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest#showsMemberOnlyGroupOnlyWhenExplicitlyRequestedAndNeverMakesItEligible` | error |
| `com.armada.task.mapper.PullTaskLifecycleMapperInMemoryTest#lifecycleMapperDoesNotUseExplicitForUpdate` | failure |
| `com.armada.task.mapper.PullTaskMapperBusinessConditionTest#businessStatusConditionsMustComeFromJavaParameters` | failure |
| `com.armada.task.mapper.PullTaskNormalLinkSchemaSelfTest#allElevenTablesAreCreated` | failure |
| `com.armada.task.service.PullTaskStandardCreateServiceTest#linkModesStillRejectNoManagerGroupWithoutFreezingDraft` | error |
| `com.armada.task.service.PullTaskStandardCreateServiceTest#newGroupModeAllowsNoManagerGroupAndPersistsNullSnapshot` | error |
| `com.armada.task.service.PullTaskStandardSettingWriterTest#newGroupModeAllowsNoManagerButStillValidatesASelectedGroup` | error |
| `com.armada.testsupport.MysqlModeMapperInMemoryTest#groupFolderAssignmentLocksAndUpdatesOnlyCurrentTenantGroups` | error |
| `com.armada.testsupport.MysqlModeMapperInMemoryTest#groupFolderMapperExecutesRealXmlAndKeepsTenantBoundary` | error |

</details>

### 部署脚本测试

两份脚本测试在 `set -e` 下遇到首个失败即退出，且在升级前的 fae19e83 上就以相同断言失败，因此另用逐函数运行的方式在升级前后各跑一遍对比：

| 套件 | 升级前（fae19e83） | 升级后 |
|------|------------------|--------|
| `deploy-test.test.sh`（设置对应 `JAVA17_HOME` / `JAVA21_HOME`） | 77 通过 / 1 失败 | 77 通过 / 1 失败（同一用例） |
| `deploy-test.test.sh`（不设置 JDK 变量） | 70 / 8 | 70 / 8（同一组） |
| `package-prod.test.sh` | 2 / 2 | 2 / 2（同一组） |

既有失败（与本次无关）：
- `test_deep_check_preserves_unauthenticated_response_body`：`lib/deep-check.sh: line 73: ARMADA_HTTP_PROBE_SETUP: unbound variable`。
- `test_protocol_package_templates`：`armada-deploy/prod/protocol/.env.example` 被 `.gitignore` 的 `.env.*` 规则忽略，新检出的 worktree 中不存在。
- `test_runtime_scripts_are_offline_only`：`armada-deploy/prod/scripts/inspect-production-host.sh` 在仓库中不存在。
- 不设置 JDK 变量时另有 6 个 dry-run 用例因本机 `java_home` 未注册对应 JDK 而失败（升级前报"需要 JDK 17"，升级后报"需要 JDK 21"），设置变量后全部通过。

`armada_find_jdk21` 直接校验：`JAVA21_HOME` 指向 openjdk@21 时返回该目录；指向 openjdk@17、openjdk 27 时均拒绝。

### Docker

- `docker pull eclipse-temurin:21-jre` 成功（arm64，`sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99`，Ubuntu 26.04，Temurin 21.0.12.1）。
- 在临时构建上下文中执行 `backend.prebuilt.Dockerfile` 时失败，失败点是 `apt-get install less vim-tiny`：`ports.ubuntu.com` 下载 `less_668-1build1_arm64.deb` 返回 `403 Forbidden`，属于本机网络 / 源问题，与 JDK 无关，未重试。
- 替代检查：`JAVA_HOME=<jdk21> mvn -q -Dmaven.test.skip=true clean package` 成功，jar `Build-Jdk-Spec: 21`；在 `eclipse-temurin:21-jre` 中以 `--network none` 执行 `java -Djarmode=layertools -jar app.jar list` 正常输出 4 个分层（未启动应用、未连库）。

## 部署
- 未部署、未推送。

## 遗留 / 跟进
- 真库测试（`*DbTest`、`*MySqlTest`）未执行。
- 完整后端镜像未在本机构建成功（apt 源 403），应用未在 Java 21 容器内实际启动，需在测试服构建 / 部署时补验。
- 测试服 / 生产构建机需安装 JDK 21，或设置 `JAVA21_HOME`；原 `JAVA17_HOME` 不再生效。
- 上表既有脚本测试失败与 35 个既有 Maven 失败未在本次处理。
