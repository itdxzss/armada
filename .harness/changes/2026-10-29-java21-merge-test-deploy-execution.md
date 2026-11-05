# Java 21 合并后测试与测试环境部署执行记录

- 日期：2026-10-29（Asia/Shanghai）。
- 任务来源：`2026-10-29-java21-merge-test-deploy-task.md`。
- 工作目录：`armada/`；分支始终为 `1.0.3-snapshot`，未新建分支。
- 被测提交：`5d415cafd1c943199178f467639fbc26ddb1450e`。
- 状态：`test1`、`perf2` 均已按用户确认的 commit 仅部署后端，部署及容器/Flyway/稳定性验证完成；成功登录及认证后接口验收待测试凭据。真实账号互存冒烟未授权、未执行。

## 范围与源码确认

已执行 `git fetch origin 1.0.3-snapshot`、`git merge --ff-only origin/1.0.3-snapshot`；结果为 Already up to date，HEAD 与远端的左右差异为 `0 / 0`。已验证 `7e53ef5e`、`2ee5b9e7`、`c72ddf66` 均为 HEAD 的祖先。

开工时已有未跟踪项 `.codegraph/daemon.pid`、`docs/prototypes/`，保留原样。未修改业务代码、数据库结构或部署脚本；本次只新增执行记录与脱敏证据。

## 执行环境与命令

- JDK：Temurin `21.0.12.1+1-LTS`，Maven `3.9.9`。
- `JDK21=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/temurin-21.0.12.1/Contents/Home`。
- 所有 Maven 命令均显式指定 `JAVA_HOME="$JDK21"`；部署脚本使用 `JAVA21_HOME="$JDK21"`。
- 原始日志只保留在本机临时目录 `/private/tmp/armada-java21-20261029-Czr9lE/`；不将包含系统属性、日志正文的 Surefire XML 原件复制进仓库。

### 全量测试

```bash
cd armada-api
DB_URL='jdbc:mysql://127.0.0.1:1/armada_test_disabled?connectTimeout=1000&socketTimeout=1000' \
JAVA_HOME="$JDK21" mvn clean test \
  -Dtest='!*DbTest,!*MySqlTest,!*MysqlTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

最终运行用时 5 分 14 秒，退出码 1（仍有既有失败）。Maven 汇总与 854 份 Surefire XML 的合计一致：

| 运行 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| 升级记录基线 | 5527 | 11 | 24 | 未记录 |
| 本轮 `clean test` | 5550 | 10 | 24 | 0 |

本轮通过 5516 项，失败/错误合计 34 项。按测试类、方法与 failure/error 类型逐项比较：**34 项匹配、0 项新增、0 项类型变化、1 项既有失败未复现**。未复现项为 `com.armada.marketing.mapper.MarketingGroupBanMapperH2Test#detailReadsCurrentBanSeparatelyFromSuccessfulAttempt`；不将一次通过解释为问题已经修复。

完整 35 行基线比较、当前失败的异常类型与测试计数见 [脱敏证据 JSON](./2026-10-29-java21-merge-test-deploy-evidence.json)。历史记录未保留每项异常类型，因此本次比较键为测试类、方法和 failure/error 类型，不声称完成历史异常类型逐项匹配。最终完整运行的 Mockito 附加错误数为 0。

前两次运行未作为验收证据：

1. 受限沙箱阻止 Mockito/Byte Buddy JVM 附加，出现 `Could not self-attach to current VM using external process`；主动终止，退出码 143。
2. 沙箱外同命令重跑，Mockito 正常；发现 `GroupLinkRegistryServiceImplTest`、`GroupCreationMarketingTaskServiceImplTest` 虽未以 `DbTest` 结尾，却继承真库 `DbTestBase`。运行停在 Spring 上下文的数据源初始化阶段，主动终止，退出码 143。

最终全量命令保留原有测试选择，并将数据源明确固定为不可连接的本机占位地址，不加载 `.env`，避免接触真实数据库。线程栈确认遗漏真库类的启动等待发生在 Hikari/Flyway 初始化路径。

### 重点测试

```bash
cd armada-api
JAVA_HOME="$JDK21" mvn \
  -Dtest='AccountMutualContact*,ProtocolGroupEventConsumerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsurefire.reportsDirectory=/private/tmp/armada-java21-20261029-Czr9lE/focused-reports test
```

退出码 0，49 项全部通过，无失败、错误或跳过：

| 测试类 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `AccountMutualContactH2Test` | 9 | 0 | 0 | 0 |
| `AccountMutualContactPolicyTest` | 2 | 0 | 0 | 0 |
| `ProtocolGroupEventConsumerTest` | 38 | 0 | 0 | 0 |

当前 Surefire 未采用命令中的 `surefire.reportsDirectory` 属性，报告实际仍生成在 `target/surefire-reports`；已将这三个报告快照保存到临时 `focused-reports/`。重点测试没有使用 `clean`，直接复用本轮已编译的 Java 21 class；全量运行后将以完整全量报告生成最终基线比较。

### 脚本与静态检查

| 检查 | 实测结果 |
|---|---|
| `bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh` | 通过，退出码 0 |
| `JAVA21_HOME="$JDK21" bash armada-deploy/deploy-test.test.sh` | 通过，退出码 0；脚本包含 78 个顶层测试调用 |
| `bash armada-deploy/package-prod.test.sh` | 退出码 1，缺少 `armada-deploy/prod/scripts/inspect-production-host.sh`，与既有失败一致 |
| 打包脚本四项独立执行 | 3 通过 / 1 失败；仅 `test_runtime_scripts_are_offline_only` 失败，无新增失败 |
| `xmllint --noout armada-api/src/main/resources/mapper/account/AccountMutualContactMapper.xml` | 通过，退出码 0 |
| 指定 JDK 的 `javap -v .../Application.class` | 最终全量完成后再次核对为 `major version: 65`，退出码 0 |

独立执行打包脚本测试时，仅在临时副本中固定原脚本的 `SCRIPT_DIR` 并移除末尾自动调用，再分别执行原有四个函数。未修改仓库脚本。历史记录的协议 `.env.example` 缺失问题在当前工作区未复现，因此结果优于历史记录的 2 通过 / 2 失败。

## 部署前专家核对

依据仓库 `expert-reviewer` 技能读取三项变更的完整 diff 及相关调用方，核对内容包括：Java 编译版本与容器基础镜像、JDK 查找与拒绝非 21 版本路径、互存任务与账号行锁、跨任务账号串行、UNKNOWN 的晚到回执、租户 Mapper 插件、405 事件归一化与导入登录结果冻结。互存 Mapper XML 解析通过，重点 H2 测试使用真实 Mapper、租户插件和 Spring 事务；未发现这三项 diff 额外引入的阻断问题。此为本次改动范围核对，不代替全量失败比较及容器运行验收。

## 部署准备与生效验证

已读取当前 `deploy-test.sh --help`。以下仅打印本地计划，未连接远程、构建、同步或重启；两个命令均退出 0：

```bash
JAVA21_HOME="$JDK21" ./armada-deploy/deploy-test.sh --env test1 --be --dry-run
JAVA21_HOME="$JDK21" ./armada-deploy/deploy-test.sh --env perf2 --be --dry-run
```

两份计划均指向 `1.0.3-snapshot / 5d415caf`、JDK 21，仅重建并重启 `backend`（`--no-deps`）。dry-run 执行时显示工作区 dirty，原因为开工时已有的未跟踪项；之后本任务另新增这份记录及脱敏证据。被测业务源码没有修改。

用户在收到包含完整 commit 和仅后端 `--be` 范围的确认请求后，明确回复“两个环境都部署”。据此部署 `test1` 和 `perf2`，不扩大到前端、协议层或生产。

部署前通过各环境档案建立 SSH，核对 `.env` 与运行容器的数据库 schema、Compose project、Android base URL 和 Kafka topic 前缀，全部匹配。首次远程检查发现两个环境的原容器本来已运行 Java 21，与任务书对更早时间点的描述不同；本次仍独立核对新制品和运行镜像。

正式部署命令（确认后追加 `--yes` 以执行用户已授权的操作）：

```bash
JAVA21_HOME="$JDK21" ./armada-deploy/deploy-test.sh --env test1 --be --yes
JAVA21_HOME="$JDK21" ./armada-deploy/deploy-test.sh --env perf2 --be --yes
```

### test1 生效验证

- 部署脚本退出码 0；部署日志中源码为 `1.0.3-snapshot / 5d415caf`。
- 本地 JAR、远端暂存 JAR、运行容器内 JAR SHA-256 一致：`74893e584635cf0aa3746eaf090a7cbe0d7dfb83650e2b67bd19c8c9226beb1f`。
- 制品 manifest `Build-Jdk-Spec: 21`，JAR 内 `Application.class` major version 65。
- 容器内 Temurin `21.0.12+8-LTS`；实际镜像层的前缀与远端 `eclipse-temurin:21-jre` 基础镜像全部匹配。
- 新容器 running，restart count 0，无 OOM；日志包含 `Started Application`，ERROR 行数 0，无类版本、Flyway、配置、数据库或 Kafka 连接失败。
- 只读事务查询 `flyway_schema_history`：220 条记录，失败 0，最新版本 218；本地所有迁移均已应用。启动日志同时显示验证 220 条迁移且 schema 已是最新。
- Nginx 镜像与启动时间保持部署前的值。
- 首次验证后再次观察 81 秒，容器 ID、启动时间均未变化，restart count 仍为 0，无新增 ERROR 日志。
- 未登录接口真实请求：空登录请求 HTTP 200 / 业务码 40103；账号列表和互存任务列表均 HTTP 401 / 业务码 40104，均为结构化 JSON。**这些仅证明未认证请求正确处理，不代表成功登录或有权限读取列表。**
- 已向用户请求测试凭据文件路径；成功登录及认证后列表检查尚未执行。

### perf2 生效验证

- 部署脚本退出码 0；源码仍为 `1.0.3-snapshot / 5d415caf`，Frontend 为 SKIPPED，仅 Backend 为 SUCCESS。
- 本地 JAR、远端暂存 JAR、运行容器内 JAR SHA-256 一致：`bd1dec74b130edff00e6a409b5ee567bddff50665c82546c288dc80ec6484f3d`。两个环境的 JAR 各自单独构建，构建时间不同，记录并核对各自哈希，不声称二进制完全相同。
- 制品 manifest `Build-Jdk-Spec: 21`，JAR 内 `Application.class` major version 65。
- 容器内 Temurin `21.0.12.1+1-LTS`；BuildKit 构建日志明确解析并使用 `docker.io/library/eclipse-temurin:21-jre@sha256:f37956012e0cb88400e5b3689aeae2d2d735364c1922ae2225536163b5a99f56`。
- 该服务器镜像存储中没有独立的 `eclipse-temurin:21-jre` 标签，不能像 test1 一样直接比较基础镜像层前缀；上述基础镜像结论以实际构建日志的 FROM digest、当前 Dockerfile 和容器 Java 版本为证据。
- 新容器 running，restart count 0，无 OOM；日志包含 `Started Application`，ERROR 行数 0，无类版本、Flyway、配置、数据库或 Kafka 连接失败。
- 服务器没有 `mysql` / `mariadb` CLI。使用本机 JDK 21 编译临时只读核验 class，在容器中加载现有应用 JAR 内的 MySQL JDBC 驱动；凭据只在容器内从现有环境读取，不输出、不复制。事务设置为 READ ONLY，执行 SELECT 后 ROLLBACK，临时文件执行后删除，未安装工具或修改应用。
- JDBC 只读查询 `flyway_schema_history`：221 条记录，失败 0，最新版本 218；本地所有迁移均已应用。启动日志同时显示验证 221 条迁移且 schema 已是最新。
- 首次验证后再次观察 192 秒，容器 ID、启动时间均未变化，restart count 仍为 0，无新增 ERROR 日志；Nginx 镜像与启动时间保持部署前的值。
- 未登录接口响应与 test1 一致：空登录请求 HTTP 200 / 40103，账号列表和互存任务列表 HTTP 401 / 40104，均为结构化 JSON。成功登录及认证后列表检查待用户提供测试凭据。

### 两环境结果汇总

| 验收项 | 状态 |
|---|---|
| 部署环境 / 范围 / 退出码 | test1 `--be` / 0；perf2 `--be` / 0 |
| 源码 commit / 运行制品校验 | 两环境均通过，源码 commit 均为 `5d415cafd1c943199178f467639fbc26ddb1450e` |
| 容器内 Java 21、镜像基于 `eclipse-temurin:21-jre` | 两环境均确认；test1 以镜像层前缀核对，perf2 以实际构建 FROM digest 核对 |
| 容器稳定性与启动日志 | 两环境均通过，无重启、OOM 或新增 ERROR 日志 |
| Flyway 状态 | test1：220 条；perf2：221 条；均最新 218、失败 0、无待执行源码迁移 |
| 登录、账号列表、互存任务列表真实接口 | 两环境未认证响应均通过；成功登录及认证后读取验收待测试凭据，不能视为完整接口验收通过 |
| 两组真实账号互存冒烟 | 未执行；需另行授权并指定两个分组 |

## 遗留

- 已满足“无新增失败”门槛，但全量测试不是全绿，仍有 34 项既有失败/错误；其中两类未按 `DbTest` 命名的真库测试需后续规范命名或选择器。本次未修改测试或业务代码。
- 打包脚本存在一项已知缺失文件失败，本任务范围内不修改脚本或业务代码。
- 两环境部署完成。成功登录及认证后账号/互存任务列表验收需要可用测试凭据；已请求用户提供凭据文件路径，尚未收到，不猜测密码、不重置用户或绕过认证。
- 不部署生产，不执行真实账号互存，不手工修改数据库。
