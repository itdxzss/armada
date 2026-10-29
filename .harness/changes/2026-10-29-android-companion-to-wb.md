# 变更记录：安卓号登录到 WB 分身

- 日期 / 分支 / worktree: 2026-10-29 / armada `1.0.3-snapshot`、wheel-saas-pure-web `1.0.3-snapshot` / 主工作区（按用户约定不另建分支）
- 需求来源: 用户 2026-10-29 会话原文——"armada 的安卓协议登录的六段号，能把分身设备登录到我们 wbpro 的 web 协议上""可以指定 wbpro 的用户，页面可以看到安卓协议登录上来的设备""支持多分身设备……最多支持四个分身""armada 编排""按推荐定（多环境）""写完任务书就开始开发"
- 对端任务书: WB v2 `docs/切片/46-安卓导入分身.md`（集成接口、签名、错误码以它为准）
- 原型: `docs/prototypes/安卓号登录WB分身.html`
- 状态: 已提交 `c67518f2`（前端 `d8022462`）并推送；已部署 test1、perf2（2026-10-29）

## 目标（一句话）

Armada 选一个在线的安卓号和一个 WB 会员，串行发起 1–4 条关联设备配对，让 WB 的 Web 协议以"主设备 + 影子"的形式挂上这个号，并在 Armada 页面看到这些连接。

## 已定规则（用户 10-29 确认）

- 导出后 Armada 继续正常使用这个安卓号（不停派任务）。
- WB 侧按现有规则计酬，收益归指定会员；会员可以删除导入的设备。
- Armada 编排，WB 不回调 Armada，Armada 轮询。
- 多环境：Armada 每套环境配置可导入的 WB 目标；本机 ↔ WB 本机，test1/perf2 → WB test1，prod → wb/customer1/customer2 中按部署逐个配置。

## 协议依据（已读代码确认）

- 安卓层 `POST /ws/v1/auth/paircode/input {phone, pairingCode, pairingRef?}`：账号必须在线；`pairingRef` 省略时取 120 秒内唯一未开始的配对请求。返回原因：`ACCOUNT_OFFLINE`、`EXPIRED`、`NO_PENDING`（通知未到，可短暂重试）、`AMBIGUOUS`（重叠，停止）、`SEND_FAILED`（不可重提）。fleet 下 coordinator 按 `phone` 路由。
- Web 层 `promotion-pairing-code` 已支持 `customPairingCode`，WB 透传，不改协议。
- 配对码派生两边都用原始字符串做 PBKDF2，统一生成大写，字母表 `123456789ABCDEFGHJKLMNPQRSTVWXYZ`。

## 缺口拆解 / 任务清单
- [x] 配置 `armada.wbpro.targets`（环境变量 `WB_TARGETS`，`envId|显示名|https://域名|clientId|secret`，分号分隔）与 `armada.wbpro.timeout`；启动时校验格式，配错即启动失败。
- [x] `platform/wbpro/`：`WbproIntegrationClient` + `HttpWbproIntegrationClient`（HMAC 签名、每个响应核对 envId、错误码透传）。
- [x] `platform/protocol/`：`CompanionPairCodePort` + `AndroidNativeCompanionPairCodeAdapter`，`AndroidNativeClient.pairCodeInput` → `POST /ws/v1/auth/paircode/input`。
- [x] Flyway `V219__wb_companion.sql`：`wb_companion_task`、`wb_companion_binding`（含租户）。
- [x] `account/wbcompanion/`：Controller（`/api/account/wb-companion/**`）、`WbCompanionService`、`WbCompanionTaskRunner`（按步推进的状态机）、`WbCompanionBindingService`（快照同步）、`WbCompanionScheduler`（每 2 秒，跨租户扫描后逐条切租户）。
- [x] 部署：`docker-compose.rds.yml`、`prod/app/docker-compose.yml` 透传 `WB_TARGETS`；`.env.example` 两份加说明；`lib/armada.sh` 新增 `armada_verify_wb_targets`：容器里的 WB 目标 envId 必须与档案 `EXPECTED_WB_TARGET_ENVS` 完全一致，档案未登记时必须为空。
- [x] 前端（wheel-saas-pure-web）：`src/api/wb-companion.ts`；账号列表"WB 分身"列、行操作"登录到WB / WB 分身"、批量菜单"登录到 WB 分身"；`WbCompanionDialog`（选环境、查用户、选数量、进度）、`WbCompanionDrawer`（连接、登录记录、补齐、停止、全部解绑）、`WbCompanionCell`；下线、删除挂着分身的账号前多确认一次（单个和批量）。
- [x] 单测：Mapper H2（V219 原样在 H2 执行）、状态机 10 项、客户端/签名/配置 5 项、安卓适配器 2 项。
- [ ] `数据模型.md` 重新生成：脚本读真库 information_schema 导出，需迁移落到测试库后再跑。
- [ ] 浏览器验收、真实协议联调。

## 关键设计决策

- **状态机持久化，不在请求线程里跑长流程**：一条连接 30–180 秒，4 条最长十几分钟；放到定时任务按步推进，服务重启后从表里继续。被否决：`@Async` 线程内循环（重启即丢，且占线程）。
- **每条连接三步**：`WB_STARTED`（WB 已受理配对）→ `CODE_SUBMITTED`（安卓已发送 primary_hello）→ `CONNECTED`/`FAILED`。`NO_PENDING` 在 20 秒内重试，超时判失败。
- **失败停整批 + 同号冷却 120 秒**：安卓层在 120 秒窗口内只认唯一未开始的请求，前一条失败留下的请求会让下一条 `AMBIGUOUS`。
- **WB 配对记录 180 秒后过期**：查到 404 时改用 WB 设备查询判断结果（连接数增加即成功）。
- **列表不实时调 WB**：列表"WB 分身"列读本地快照（任务结束、详情页刷新、解绑时更新），避免列表页每行打一次跨环境 HTTP。
- **同一账号只绑一个 WB 环境 + 一个会员**：换会员或换环境先全部解绑。

- **两张表而不是三张**：原计划另建每条连接一行的明细表。因为每号严格串行、失败即停，进度可以从"已有 + 已成功 + 当前步骤"推出来，所以只保留任务表（含当前这一条的步骤字段）和绑定表（每账号一行的当前事实，列表列只读它）。被否决：明细表（多一张表、多一处写入，没有新信息）。
- **配对码落库**：`wb_companion_task.pair_code` 只在当前这一条进行中保存（NO_PENDING 重试要用同一个码），这一条结束、任务结束、停止时都清空。
- **同一账号同时只有一个进行中任务**：生成列 `active_account_id` + 唯一键，并发创建时第二个直接冲突。
- **跨环境防误连**三道：Armada 只能选配置里的目标；WB 只认登记过的 clientId 和签名；Armada 每个响应核对 envId；部署时再核对容器里的目标与档案一致。

## 验证（evidence-before-done）
> 命令 + 真实输出。数据相关默认使用 test scope 的 H2 内存数据库、真实 Mapper XML、MyBatis-Plus 插件和 Spring 事务；MySQL 专有语法补结构/解析测试。

- `mvn -o test -Dtest='AndroidNativeCompanionPairCodeAdapterTest,WbCompanion*,HttpWbproIntegrationClientTest,HttpAndroidNativeClient*,ProtocolConfiguration*'` → `Tests run: 26, Failures: 0, Errors: 0`（JDK 21）。
- `mvn -o test -Dtest='Flyway*ContractTest,FlywayAppliedMigrationCompatibilityTest,...'` → Flyway 四个契约测试全部通过；同批的 6 个 `*DbTest` 报错，原因是本机无真库（`Access denied for user 'root'@'localhost'`），与本次改动无关。
- `bash armada-deploy/deploy-test.test.sh` → `OK deploy-test.sh protocol and zhuan tests passed`；`node armada-deploy/verify-config.mjs` → passed。
- `bash armada-deploy/package-prod.test.sh` → 失败：`prod/scripts/inspect-production-host.sh` 不在仓库里（`git ls-files` 确认从未提交），早于本次改动。
- 前端：`pnpm typecheck` 通过；改动文件 eslint、prettier、stylelint 通过；`pnpm build` 通过；`AccountListTable.test.ts`、`useAccountListPage.test.ts` 11 项通过。`src/api/*.test.ts` 在 Node 下因 `nprogress.css` 导入报 `ERR_UNKNOWN_FILE_EXTENSION`，与本次改动无关。
- 跨仓签名一致性：Armada `HttpWbproIntegrationClientTest.signatureMatchesWbVector` 与 WB `DeviceImportIntegrationTest.signatureMatchesArmadaVector` 使用同一组固定向量，两边均通过。

## 部署
- 2026-10-29 用户授权"armada 部署到一二套测试环境"。命令：`JAVA21_HOME=<JDK21> ./armada-deploy/deploy-test.sh --env <test1|perf2> --all --branch 1.0.3-snapshot --yes`，后端 `c67518f2`、前端 `d8022462`。
  - test1：退出码 0，Backend/Frontend SUCCESS；容器 running、restarts=0；Flyway `Successfully applied 1 migration ... now at version v219`；启动后 10 分钟 ERROR/Exception 计数 0；`WB_TARGETS` 已透传（为空，功能未接通）。
  - perf2：退出码 0，Backend/Frontend SUCCESS；容器 running、restarts=0；`armada_perf` 到 v219；ERROR/Exception 计数 0。
  - 未做：登录后的页面验收、真实协议联调（两套都还没有配置 WB 目标）。
- 上线顺序（备忘）：上线顺序：WB 先发版（V35）、配 `WBPRO_ENV_ID` 和 `WBPRO_INTEGRATION_CLIENTS`（含本 Armada 环境的 clientId 与密钥）、打开 `importEnabled` → Armada 发版（V219）、在 `.env` 写 `WB_TARGETS`、在环境档案 `envs/<env>.conf` 写 `EXPECTED_WB_TARGET_ENVS`（逗号分隔 envId）。两边密钥必须一致，只放各自 `.env`，不进仓库。
- 推荐对应（用户 10-29 定）：test1、perf2 → WB test1；prod → wb-prod / customer1 / customer2 中逐个指定。

## 遗留 / 跟进

- 真实协议联调需要 WB test1 先发版（WB 环境现状页记录其落后十几个切片）和指定测试号。
- 安卓层暂无"列出已关联设备"接口，号码在别处已关联时最后几条会失败，只能提示。
