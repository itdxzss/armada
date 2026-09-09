# 变更记录：超链任务测试、验收与压测启动

- 日期 / 分支 / worktree: 2026-09-03 / `1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户要求“测试方案、验收清单、压测用例都要出的。然后要开始测试的”，并给出 L0～L4、零差错、1×/2×/soak、60%～70% 利用率及六段统计原则；后续明确云控账号池可用于压测，但必须阻断真实 WhatsApp 触达，同时保留实例真实 Signal/Noise 加解密以观测 CPU、内存
- 状态: 进行中

## 目标（一句话）

冻结超链正式业务量和验收口径，交付可执行的分层测试与压测基线，并先启动无真实发送副作用的 L0 回归。

## 缺口拆解 / 任务清单

- [x] 核对业务后端、管理前端、Web 协议、Android 协议四仓边界与候选 SHA。
- [x] 输出正式业务量冻结表与 L0～L4 测试方案。
- [x] 输出上线验收清单、阻断项和证据要求。
- [x] 输出 1×、2×、soak、故障恢复、公平性、计费、漏斗和 canary 压测用例。
- [x] 启动 Armada 超链 L0 聚焦回归。
- [x] 启动前端超链 L0 定向回归和只读类型检查。
- [ ] 在项目基线 Java 17 CI 复跑 Armada 聚焦套件及真 MySQL/InnoDB 门禁。
- [x] 准备校验后的临时 Go 1.25.1 工具链并执行 Android 7 个相关包完整 `-race` 回归。
- [x] 执行 Android 全仓 vet/build。
- [x] 尝试 Android 全仓 test 并记录既有/非超链失败。
- [ ] 处理全仓 15 个失败断言，并在正式 CI 执行全仓 test/race/vet/build。
- [x] 在同候选源码临时副本按锁定依赖转换执行 Web 协议 Jest 聚焦测试。
- [x] 在临时 npm-compatible 布局执行 Web 协议全量、typecheck 和 build。
- [x] 在候选仓按正式 `package-lock.json` 执行 `npm ci`、`npm test`、typecheck 和 build。
- [x] 连接 test1 执行服务探活、`runner-deep-check` 和远端持久化 UI smoke。
- [x] 通过 browser skill 使用具备权限登录态进入 test1 超链任务页，核对列表、汇总与只读 API。
- [x] 对 test1 数据库执行只读租户、幂等、计数和零计费不变量快照。
- [x] 将第一阶段收敛为进程内 Crypto Loopback，独立完整 WhatsApp Mock 延后到确有双向 Signal/设备发现需求时。
- [x] 实现 Android TCP + Noise XX 加密回环、配置保护的 dialer 注入、密文 ACK 闭环和诊断计数；本地聚焦与 race 测试通过。
- [x] 实现 Web 独立 Signal+Noise 双向加密回环和密文 ACK，并在 test1 Web 协议机执行 1000 条校准。
- [ ] Android 增加 Signal 测试 recipient/pre-key 与 inbound echo；Web 全链隔离 worker 后续按需实现。
- [ ] 完成 1 账号 0.1× 校准，证明真实 WhatsApp egress=0 且实例真实执行双向加解密。
- [ ] 冻结 CAP-01～CAP-19、CAP-21 正式业务量、SLO 与幂等保留策略。
- [ ] 补齐协议接收/实际提交事实、HIGH 选号、真实钱包、owner 缺失收口和无账号终态。
- [ ] 建设 L1 全链 Stub/故障注入器并执行容量用例。
- [ ] L0～L2 PASS 且获得书面授权后执行 L3/L4。

## 关键设计决策

- 当前代码常量只作为实现事实，不自动升级为生产业务上限。正式 1× 必须是任务、账号、协议、消息和回调的联合负载向量。
- L0 只运行 H2、fake、miniredis 和 HTTP double 范围，不连接真实 MySQL、Redis、Kafka、钱包或 WhatsApp。
- 不向候选仓安装工具链或依赖。获得网络授权后，只在 `/private/tmp` 下载经官方/go.sum 校验的 Go 工具链/模块，并在同源 Web 临时副本安装由 `package-lock` 转换的依赖；环境启动失败与业务断言失败分开记录。
- 后端 `submitted_at` 当前代表业务侧本地 enqueue/outbox 接受，不能作为协议 consumer 接收或物理提交的证据。
- HIGH/蓝标若为强制要求，必须进入上游候选筛选、运行快照和实际账号核对；`accountType=2` 不能替代 HIGH。
- 真实钱包当前没有生产适配器；ZERO_TEST 只能验证 Saga 结构，不能形成正式账务验收结论。
- Web/Android owner 缺失当前都可能让消息结果无法收敛；必须在 L2 中证明明确失败或可靠补偿，禁止跨协议猜账号重发。
- ROLLING/CYCLE 无账号会重复等待；在业务冻结等待时限和终态前，不开始 L1 容量结论。
- 蓝标 L4 只验证小流量真实闭环，禁止运行 ramp、2×、soak、故障注入或容量搜索。
- L1 全链依赖可信的协议 Stub，故实际顺序采用 L0 → L2 smoke/合同 → L1 → L3 → L4。
- L1/L2 第一阶段使用进程内 Crypto Loopback：Android 生产实例走真实消息构造、Signal 出站加密、双向 Noise 和 ACK 状态写回；Web 与高级回执按正式 mix 补齐，禁止在发送函数处短路成功。
- Web 仓库规则禁止把 mock/fake 返回放进生产 socket，因此 Web 第一阶段采用独立 crypto harness：复用 Baileys 的 protobuf、Signal repository、BinaryNode、Curve25519、HKDF、AES-GCM 和 Noise handler，不创建 WASocket、不重启共享 worker。
- 云控账号按真实池规模参与，但 auth/Signal/app-state 复制到 runId 隔离命名空间；网络同时用配置、egress firewall 和旁路连接审计三层阻断真实 WhatsApp/Meta 出口。
- Web、Android 和业务侧幂等/ACK 保留窗口必须统一验证；soak 允许合规 tombstone 按模型增长，不能简单要求 Redis key 总量不增长。
- 前端 copy 调用 create API 但当前可能绕过 pure-create 的 7 秒报价复核；必须以行为测试证明后端对空/过期 quoteToken 失败关闭，不能把静态源码合同测试当真实交互证据。

## 验证（evidence-before-done）

运行号：`HL-20260903-L0-001`。

### Armada 业务后端

本机无 PATH 内 Maven/Java，使用 IntelliJ Maven 3.9.16 与 JBR 25。Mockito/Byte Buddy 1.14.19 在 Java 25 下需显式 agent 与 experimental 开关；第一次未加兼容参数的错误属于测试运行环境错误，不是业务断言失败。

```bash
env JAVA_HOME='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home' \
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn' \
-B \
-Dtest='Hyperlink*Test,!HyperlinkRuntimeConcurrencyMySqlTest,ZeroBillingHyperlinkWalletPortTest' \
'-DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dnet.bytebuddy.experimental=true' \
test
```

真实结果：`BUILD SUCCESS`；324 tests，0 failures，0 errors，0 skipped；总耗时 5.354 秒。覆盖 H2 Mapper、状态机、派发并发、UNKNOWN 恢复、ACK 路由、计费 Saga、租户锁和指标投影。该结果仍须在 Java 17 CI 复跑。

### 管理前端

任务定向套件：

```bash
/Users/daishuaishuai/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node \
  --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types --test-concurrency=1 \
  src/api/hyperlink-task*.test.ts \
  src/router/hyperlink-route.test.ts \
  src/views/hyperlink/task/**/*.test.ts
```

真实结果：85 tests / 20 suites，85 pass，0 fail。

扩展到数据包、模板与素材库的全超链依赖套件：

```bash
/Users/daishuaishuai/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node \
  --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types --test-concurrency=1 \
  src/api/hyperlink-*.test.ts \
  src/router/hyperlink-route.test.ts \
  src/views/hyperlink/**/*.test.ts
```

真实结果：132 tests / 31 suites，132 pass，0 fail；使用 alias loader 和 HTTP test double，无真实 API 调用。

```bash
/Users/daishuaishuai/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node \
  node_modules/typescript/bin/tsc --noEmit

/Users/daishuaishuai/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node \
  node_modules/vue-tsc/index.js --noEmit --skipLibCheck
```

真实结果：两条命令均 exit 0。

直接执行仓库 `pnpm test` 曾产生 CSS loader/alias 运行器假红，并包含一个与本范围无关的既有源码合同断言失败；该次运行未作为超链验收证据。定向命令显式加载仓库测试 double 后全绿。

### Android 协议

机器起初没有 Go。依据 Go 官方下载清单获取 `go1.25.1.darwin-arm64.tar.gz`，文件大小 57,906,702 bytes，实际 SHA-256 与官方值均为 `68deebb214f39d542e518ebb0598a406ab1b5a22bba8ec9ade9f55fb4dd94a6c`；工具链只解压到 `/private/tmp/hl-go-toolchain.s09sbk`。模块按仓库 `go.sum` 与 Go checksum database 校验后下载到同一临时缓存；官方 module proxy 长时间无数据后中止，改用 `goproxy.cn`，仍由 go.sum/sumdb 校验。

首次在默认沙箱运行时，miniredis 因不能监听 `127.0.0.1:0` 产生环境失败；允许本机 loopback 后原命令通过。聚焦三组命令均 PASS，随后扩到 7 个相关包的完整 race 回归：

```bash
env GOCACHE='<temp>/gocache' GOMODCACHE='<temp>/gomodcache' \
  GOPATH='<temp>/gopath' GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off \
  '<temp>/go/bin/go' test -race \
  ./internal/armada \
  ./internal/service/app \
  ./internal/service/node \
  ./internal/service/node/nodes \
  ./internal/external \
  ./internal/coordinator \
  ./internal/fleet -count=1
```

真实结果：7/7 packages PASS；`internal/armada` 10.052 秒，其余包均通过。链接器输出 macOS `LC_DYSYMTAB` warning，但退出码为 0，race 未报告数据竞争。未连接外部 Kafka、Redis、WhatsApp 或真实账号；原仓保持未修改。

继续执行全仓 `go vet ./...` 与 `go build ./...`，两者均 exit 0。全仓 `go test ./... -count=1` 未全绿：`deploy/coordinator` 2 项、`deploy/multinode` 2 项、`deploy/node` 3 项失败，涉及本机缺少 `envsubst` 及部署文件内容期望；`pkg/noise` 8 项失败，包含缺少 `vectors.txt` 和多个 Noise 向量不匹配。合计 15 个失败断言，均不在超链相关 7 包内，但发布总门禁仍不能标绿。正式 CI 需复核并处置这些失败，再运行全仓 race。

### Web 协议

早期先在临时副本按锁定依赖完成基线。随后已在候选仓通过 npm 11 严格执行原 `package-lock.json` 的 `npm ci`，postinstall 成功应用仓库现有 `baileys+7.0.0-rc13.patch`，再执行正式 `npm test` 入口。

```bash
/Users/daishuaishuai/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node \
  --experimental-vm-modules ./node_modules/jest/bin/jest.js \
  --runInBand --runTestsByPath \
  src/commands/master-consumer.test.ts \
  src/commands/master-router.test.ts \
  src/commands/message-send-failure.test.ts \
  src/commands/message-send-peer.test.ts \
  src/commands/message-send-state.test.ts \
  src/commands/worker-consumer.test.ts \
  src/events/publisher.test.ts \
  src/events/subjects.test.ts \
  src/events/required-ack-dlq.test.ts \
  src/worker/event-bridge.test.ts \
  src/routes/messages.link-button-card.test.ts
```

聚焦真实结果：11 suites / 161 tests，全部通过，0 snapshot，耗时 4.788 秒；未连接 Kafka、Redis、WhatsApp 或真实账号。该结果覆盖发送、owner 路由、命令幂等、结果/ACK 桥与 link/button card，但现有测试也固化了 owner-missing 的不收敛行为，故对应产品缺口仍为 P1。

首次全量运行在严格 pnpm 布局/部分仓库副本/沙箱回环下得到 107 suites 通过、7 suites 环境失败：5 个 suite 无法从根看到 npm 通常会 hoist 的 `@hapi/boom`，1 个制品测试缺父目录 `.dockerignore`，dashboard 的 6 个断言不能监听 `127.0.0.1`。这些不是超链业务断言失败。随后补入候选父目录 `openapi/` 与 `.dockerignore`，把 lock 中已有的 `@hapi/boom` 以 npm-compatible 根可见方式链接，并仅为 loopback 测试解除沙箱后全量复跑：

```text
Test Suites: 114 passed, 114 total
Tests:       1324 passed, 1324 total
Snapshots:   0 total
Time:        15.31 s
```

候选仓正式测试入口结果：114 Jest suites / 1324 tests 全绿，维护 CLI 9 tests 全绿，新 Web crypto loopback 3 tests 全绿。`tsc --noEmit` 与 `tsc -p tsconfig.json` 均 exit 0。

Web crypto loopback 本地 1000 条校准为 1000 ACK、0 error、约 3109 msg/s。随后只把两个独立测试脚本同步到第一套 Web 协议机；现场 Node 24.16.0、Baileys 7.0.0-rc13，正确性测试 3/3 PASS，最初 1000 条、8 lanes、512-byte payload 得到 1000 ACK、0 error、约 3236 msg/s。确认 test1 当时无人使用后，又直接执行 10,000 条/8 lanes、20,000 条/32 lanes、100,000 条/16 lanes 三档，分别约 3906/4307/4756 msg/s，全部 ACK、0 error；对应峰值 RSS 分别为 330,235,904/391,667,712/389,222,400 bytes。执行后 8080～8084 的 `/readyz` 均为 HTTP 200，master、4 worker、runtime collector 和 traffic dashboard 全部 online；未同步生产 `src`/`.env`/PM2 配置，未创建 WASocket 或真实 WhatsApp 连接。该结果仍只用于密码学和实例资源校准，正式业务 1× 未冻结，不能据此宣称全链容量通过。

### 文档校验

- [测试方案](../../docs/superpowers/specs/2026-09-03-hyperlink-task-test-plan.md)
- [验收清单](../../docs/superpowers/specs/2026-09-03-hyperlink-task-acceptance-checklist.md)
- [压测用例](../../docs/superpowers/specs/2026-09-03-hyperlink-task-load-test-cases.md)

文档验证已完成：6 个本轮交付文件（含 test1 现场报告和 Crypto Mock 设计）分别执行 `git diff --no-index --check /dev/null <file>`，均无 whitespace 错误输出；相对目标文件均存在；行尾空白扫描为 0；`PERF-HL-*` 详细用例标题无重复。原有未跟踪评审/验收资产保持未修改。

## 部署

- commit / 环境 / 部署后验证结果: 未部署业务服务；已连接 `test1 / 第一套环境` 验证现有部署，并把 Web 独立 crypto harness 同步到协议机执行低流量 canary。`runner-deep-check` PASS；远端 UI smoke runId=`20260903T062332Z-25867866` PASS；browser skill 实际进入 `#/hyperlink/tasks` 并成功读取 9 条任务，`GET /api/hyperlink-tasks` 与 `GET /api/hyperlink-tasks/create-context` 均 HTTP 200。数据库只读不变量快照未发现跨租户关联、重复 command/ACK、计数漂移或终态账务差额。
- 状态边界: test1 当前为 `ZERO_TEST`，运行 manifest 证据早于容器重建，Web 在线账号 0、eligible HIGH 0；本轮未新建/恢复/停止/复制任务，未发送真实消息或触发钱包动作。有状态 E2E 与容量测试仍为 BLOCKED。
- 现场报告: [test1 真实环境执行结果](../../docs/superpowers/specs/2026-09-03-hyperlink-task-test1-live-execution-result.md)。

## 遗留 / 跟进

- 业务、产品、运营、财务、风控和技术共同完成容量合同签署。
- 为 C2 协议接收和 C3 实际提交增加独立权威事实与自动对账，修正前端发送总数口径。
- 实现 HIGH 账号筛选与启用前复核，补真实钱包适配器。
- 修复 Web/Android owner 缺失的可收敛结果路径，冻结并实现无账号终态。
- 建设普通队列并发流量，量化验证超链满载时不饥饿。
- 固定 Java 17、Go 1.25 和 Web npm lock 工具链后补齐 L0/L2 结果。
