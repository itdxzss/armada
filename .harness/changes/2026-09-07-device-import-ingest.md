# 变更记录：手机直传凭据控端入口

- 日期 / 分支：2026-09-07 / `codex/device-import-ingest-integration`。
- worktree：`/Users/daishuaishuai/IdeaProjects/armada/.worktrees/device-import-ingest-main`。
- 来源：用户要求执行 `wa-biz-compat-v8-extension/docs/2026-09-07-control-side-ingest-agent-prompt.md`。
- 状态：用户已授权提交到主仓库及部署验证；已在 b68890ed 当前主线完成集成，130 项定向测试、9 项网关测试和打包通过；test1 已确认并完成制品暂存和专用 443 安全组放行；域名/证书与令牌默认值待确认，运行服务未切换。

## 目标

通过唯一公网 HTTPS 入口 `POST /api/device-imports`，用静态令牌选择服务端租户和默认配置，复用全参→六段→QUEUED→既有 10 秒调度自动上线。

## 设计与依赖

- 设计及实施计划：[2026-09-07-device-import-ingest-design.md](../../docs/superpowers/specs/2026-09-07-device-import-ingest-design.md)。
- 依赖快照：[dependency-manifest.json](2026-09-07-device-import-ingest/dependency-manifest.json)。
- 基点 `39e22427e7c68906c7bdf7aa6e3ee5051e6d109c`；复制指定 full-params 工作树的 14 个未提交文件并逐文件校验 SHA-256。源工作树未更改，未复制 .env、导出凭据、构建产物或私钥。
- 准备时主线 `6b763f1f` 比该基点多 594 个提交；交付核查时主线已推进到 `b68890ed`（595 个提交）。必须在确认的当前发布基线上集成整个依赖，禁止部署旧树整包覆盖运行版本。
- 部署与回滚：[device-ingest/README.md](../../armada-deploy/device-ingest/README.md)。交付与评审：[delivery.md](2026-09-07-device-import-ingest/delivery.md)。

## 任务清单

- [x] 阅读指定提示词、手机冻结设计、全参工作树 diff、Harness 规范及现有导入/安全/部署链路。
- [x] 建独立工作树并承接未提交依赖，保存内容哈希清单。
- [x] 完成接口、令牌默认值、重复语义、原子事务、租户隔离、日志和最小 TLS 网关设计。
- [x] 编制 TDD/本地 H2/真库/部署/真机验收计划及回滚边界。
- [x] 完成继承链路当前环境定向基线验证并记录最终输出。
- [x] 用户复核控端设计，明确回复“开始编码吧”。
- [x] 新接口和安全链 TDD 实现。
- [x] H2 真实 Mapper/事务与并发重复验证。
- [x] nginx/Compose、部署说明及配置验证。
- [x] 本任务定向门禁、打包和交付自查。
- [x] 当前发布基线集成及相关定向回归；旧基线全库结果单独归档。
- [x] 确认 test1，完成只读预检、候选镜像暂存与校验、专用 443 安全组放行。
- [ ] 确认实际默认配置和 TLS 配置，切换后端及网关。
- [ ] 指定测试 MySQL DbTest、公网路由/端口验收、既有调度及手机实机交接验证。

## 关键设计决策

- 冻结裸 JSON 和真实 HTTP 状态；不沿用管理员错误信封/HTTP 200 错误语义。
- 同租户已有未删除账号或同号导入仍 QUEUED/DISPATCHED 返回 409，不覆盖凭据。
- 新服务外层事务包住既有批次/账号/状态/凭据/明细写入，全部成功才返回 200。
- 环境变量必填，缺失启动失败；完整映射和令牌不进 git。
- 不新增调度、凭据格式、业务表或 Redis 锁；IP 复用已有地区/分配策略。
- 单独 443 网关，不把现有代理全部 `/api/` 的 nginx 暴露到公网。
- 保留网络超时后重传 409、手机登出与调度先后不确定的契约边界，不能宣称真实交接已通过。

## 当前主线集成验证

基于 `b68890ed`，130 项 Java 定向测试、9 项本地网关测试、API 文档、XML 与打包通过。保留管理端原生 iOS 凭据格式 4，新入口固定六段格式 1。详见 [交付记录](2026-09-07-device-import-ingest/delivery.md) 和 [verification.json](2026-09-07-device-import-ingest/verification.json)。

## 初始旧工作树验证（历史记录）

- 复制校验：14 个依赖文件 SHA-256 一致。
- 初次基线：终端默认 JDK 23，81 个测试中 42 通过、39 个 Mockito 初始化错误，退出码 1；根因为测试 agent 无法动态挂载，不是 39 个业务断言失败。
- JDK 17.0.19 重跑命令（工作目录 `armada-api`）：

  ```bash
  env JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
    mvn -q \
    -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
    -Dtest=FullParamsToSixConverterTest,AccountImportParserTest,AccountImportRowWriterTest,AccountOnlineCommandServiceImplTest,AccountImportExportFilenameTest,AccountImportLoginResultSettlerTest \
    test
  ```

  退出码 0，81 个测试，0 failures、0 errors、0 skipped。独立工作树新生成的 Surefire XML 已逐套核对 Java 版本和测试数；脱敏汇总见 [baseline-test-evidence.json](2026-09-07-device-import-ingest/baseline-test-evidence.json)。这仅证明继承实现的定向单测，不是新接口、H2 事务或真实协议上线验收。
- 新入口及继承链路定向回归：112 项通过（新增 30 项），0 failures/errors/skipped；退出码 0。命令与脱敏统计见交付记录。
- 本地网关：`python3 -B armada-deploy/device-ingest.test.py`，9 项通过，包含真实 TLS、精确路径/方法、原始请求体转发、去除管理员身份头、端口叠加、日志及临时目录检查；退出码 0。
- `xmllint --noout armada-api/src/main/resources/mapper/account/AccountImportDetailMapper.xml`、API 文档测试、`git diff --check` 均退出码 0。
- JDK 17 `mvn -q -DskipTests package` 退出码 0，产物包含设备入口且没有 H2/testsupport/.env；仅证明编译和打包，不等于测试全库通过或可直接部署旧基线。
- 全库本地回归 332 项，首次完整轮次 2 failures/3 errors：其中 2 个回环 socket 环境错误在授权本地端口后单独重跑通过；剩余 2 个历史拉群调用断言失败、1 个旧 H2 fixture 缺 `group_created_at` 错误，均在没有本任务实现的基线副本复现。没有修改这些无关模块。
- 旧部署脚本测试仍失败：嵌套工作树下硬编码的协议兄弟路径缺失，以及缺少生产 `.env.example` 文件。新网关配置独立验证通过，不宣称旧发布脚本全绿。
- 真实 MySQL、远程入口、当前调度周期、协议握手和手机登出：NOT_RUN。

## 部署

- 用户已授权提交到主仓库及部署验证；本记录随主线集成提交，提交号以 Git 日志为准。push：未执行。test1 已执行 SSH/只读数据库预检、候选镜像暂存及专用 443 安全组变更；运行后端和网关尚未切换。
- 一轮扩大回归误纳入两个不以 DbTest 命名但继承 DbTestBase 的旧测试，已立即终止；连接池未启动成功、迁移未开始。后续明确排除这些测试，并设置无效 DB_URL 防止连接真实数据库。
- 当前无可交付的已上线 HTTPS 入口、域名或令牌。安全组放行不等于接口上线。代码和部署完成前不得标记本任务已完成。

## 遗留

当前主线集成已完成。test1 已确认，等待域名/证书及令牌默认值，再执行运行服务切换和公网验收。已通过异步问题请求环境信息；未提供前不操作远程或真库。不得将本地测试、模拟上游和打包结果当成真实手机交接验收。

## test1 发布准备（当前结果）

- 重部署配置补丁 `d96ea1e0` 已合入本地主仓库，确保基础 Compose 继续注入令牌、关闭请求和 SQL 参数日志、读取管理端绑定。先红后绿的 Compose 回归及全部 9 项网关测试通过。
- 当前主仓库部署脚本测试退出码 0；生产打包脚本因既有缺失 `prod/scripts/inspect-production-host.sh` 失败，与此次 test1 制品暂存分开记录。
- 已恢复 AWS 会话，创建只允许 IPv4 TCP 443 的专用安全组，仅挂载 test1；没有修改原安全组或新增出站许可。
- test1 只读迁移核对无失败、无校验和冲突；本地缺失的历史 V172 沿用现有 missing 忽略策略，待执行 V179。V179 结构测试通过；未手动 ALTER 或执行 Flyway 迁移。
- 已上传测试通过的 JAR，并在隔离目录构建独立镜像；上传文件与镜像内 JAR 哈希一致。旧容器仍运行且未重启。
- 脱敏证据：[test1-staging.json](2026-09-07-device-import-ingest/test1-staging.json)。未将服务器环境变量、令牌映射或 TLS 私钥写进此记录。
