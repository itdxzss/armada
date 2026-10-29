# 任务书：1.0.3-snapshot 合入 Java 21 升级后的测试与测试环境部署

- 日期：2026-10-29
- 执行方：Codex
- 分支：`1.0.3-snapshot`（**只在这个分支上工作，不得新建分支**）
- 范围：只做测试和**测试环境**部署；不部署生产，不改业务代码

## 1. 背景

`1.0.3-snapshot` 是当前主分支。本次把 `1.0.4-snapshot` 合并进来，带入后端 **Java 17 → Java 21** 升级（提交 `7e53ef5e`，合并提交为 `merge: bring 1.0.4-snapshot Java 21 upgrade into 1.0.3-snapshot`）。

本次需要上线的改动：

| 改动 | 提交 | 说明 |
|------|------|------|
| Java 21 升级 | `7e53ef5e` | `pom.xml` 的 `java.version` 改为 21；后端镜像改为 `eclipse-temurin:21-jre`；部署/打包脚本改为查找 JDK 21，环境变量 `JAVA17_HOME` 改名为 `JAVA21_HOME`。不改业务代码 |
| 互存联系人调整 | `2ee5b9e7` | 结果"待确认"（UNKNOWN）不再阻塞账号；单任务同时在途上限由 8 提升到 100 |
| 过期客户端判定 | `c72ddf66` | `fix(account): settle outdated clients as login failures` |

升级的详细记录与已知失败清单见 `.harness/changes/2026-10-25-java21-upgrade.md`，开工前先读这份记录和 `AGENTS.md`、`.harness/skills/deploy-verify/SKILL.md`。

## 2. 前置条件

- 拉取最新 `1.0.3-snapshot`，确认 `git log -1` 是本次合并之后的提交。
- 本机 JDK：默认的 JDK 26 跑不了 Mockito（Byte Buddy 不支持），所有 Maven 命令都要显式指定 JDK 21：
  `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/temurin-21.0.12.1/Contents/Home`
- 部署脚本需要 `JAVA21_HOME`（旧的 `JAVA17_HOME` 不再生效）。

## 3. 测试（部署前必须完成）

### 3.1 后端非真库全量测试

```bash
cd armada-api
JAVA_HOME=<JDK21> mvn clean test -Dtest='!*DbTest,!*MySqlTest,!*MysqlTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- 必须用 `clean test`，否则可能跑的是旧的 Java 17 class。
- 跑完确认 class 版本是 Java 21：`javap -v target/classes/com/armada/boot/Application.class | grep major`，应为 `65`。
- 验收标准：失败名单与升级记录里的 **35 项既有失败** 逐条对比，**不得出现新增失败**。测试总数应在 5527 左右（之后新增的测试会让总数略多）。有新增失败时停下来报告，不要自行修改业务代码。

### 3.2 重点测试（必须全部通过）

```bash
JAVA_HOME=<JDK21> mvn -Dtest='AccountMutualContact*,ProtocolGroupEventConsumerTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`AccountMutualContactH2Test` 9 个、`AccountMutualContactPolicyTest` 2 个、`ProtocolGroupEventConsumerTest` 38 个，全部通过。（Claude 合并后已在本机 JDK 21 上跑过，全部通过。）

### 3.3 部署脚本测试

```bash
bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
JAVA21_HOME=<JDK21> bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

`deploy-test.test.sh` 在本机已通过。`package-prod.test.sh` 有升级前就存在的失败（见升级记录），对比是否一致即可。

## 4. 测试环境部署

### 4.1 部署前确认（硬门槛）

部署是外部状态变更。**执行前必须先向用户确认**以下三项，用户明确回复后才能部署：

1. 目标环境：`test1` 还是 `perf2`；
2. 分支和 commit：`1.0.3-snapshot` 的具体 commit hash；
3. 范围：本次只改了后端，建议只部署后端 `--be`；如果用户要求一起部署前端或协议层，按用户说的来。

**生产环境不在本任务范围内，不要部署生产。**

### 4.2 部署步骤

```bash
./armada-deploy/deploy-test.sh --help
./armada-deploy/deploy-test.sh --env <test1|perf2> --be --dry-run
# dry-run 输出核对无误、用户确认后再执行：
./armada-deploy/deploy-test.sh --env <test1|perf2> --be
```

参数以 `--help` 的当前输出为准，不要照抄历史命令。如果需要用 `--branch`，先 `--dry-run` 核对，不要切换或污染当前工作目录。

### 4.3 部署后验证（部署成功不等于已经生效）

1. 部署脚本退出码为 0，部署的 commit 与确认的一致。
2. **确认后端确实运行在 Java 21 上**：容器内 `java -version` 为 21，镜像基于 `eclipse-temurin:21-jre`。升级记录里本机 Docker 构建因 apt 源 403 失败，应用从未在 Java 21 容器里真正启动过，**这一步是本次部署的重点**。
3. 容器稳定，没有反复重启；启动日志里没有类版本、Flyway 迁移、配置或连接错误。
4. Flyway 状态正常，没有失败的迁移。
5. 对关键接口发真实请求（登录、账号列表、互存任务列表 `GET /api/accounts/mutual-contact-tasks`），核对状态码和响应体。

### 4.4 互存联系人冒烟（需用户单独同意）

这一步会让真实账号保存联系人，**必须先征得用户同意**，并由用户指定使用哪两个分组。

- 建议用小分组，两组各 10 个号左右，间隔保持默认。
- 观察项：任务能正常跑完；同一时间在途的数量超过 8；出现"待确认"的账号还能继续派下一条；协议层日志没有大量报错或超时；有没有账号出现风控、禁言或掉线。
- 出现大量"待确认"或账号风控时立即停止任务（`POST /api/accounts/mutual-contact-tasks/{id}/stop`），并报告给用户。

## 5. 红线

- 只在 `1.0.3-snapshot` 上工作，不新建分支。
- 私钥（`dev-1.pem` / `xieyi.pem`）、`.env`、token、数据库连接串不回显、不提交、不写进报告。
- 不部署生产；不手工修改数据库结构；不做批量数据修改。
- 测试出现新增失败时，只报告，不要自行改业务代码修。

## 6. 交付物

在 `.harness/changes/` 下补一份执行记录（或直接追加到本文件末尾），至少包含：

- 测试：命令、JDK 版本、测试总数、失败数，以及和 35 项既有失败的对比结论；
- 部署：环境、commit、范围、脚本退出码；
- 生效验证：容器内 Java 版本、启动日志结论、Flyway 状态、接口检查结果；
- 互存冒烟（如果执行了）：分组规模、成功/失败/待确认数量，以及有没有账号出现风控；
- 遗留问题和建议。

敏感值一律脱敏。
