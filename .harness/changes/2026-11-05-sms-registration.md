# 变更记录：接码订单对接 Go 注册

- 日期 / 分支 / worktree: 2026-11-05 / armada `1.0.3-snapshot`、wheel-saas-pure-web `1.0.3-snapshot` / 主 checkout
- 需求来源: 用户要求把 Armada 的接码平台调用与 Go 协议的注册三接口打通，自动完成发码、等码、验码，可同时办理多个号码；任务书 [接码注册对接 Go 开发任务书](../../docs/handoff/2026-11-05-接码注册对接Go-开发任务书.md)，方案复核背景见 [简版方案](../../docs/business/2026-11-05-ARMADA接码与Go注册对接方案-简版.md)
- 状态: Armada 离线实现完成，代码与测试纳入本次提交；**未部署，真实注册未验收**；默认 `enabled=false`，页面按钮禁用。前端、Go 的在途改动不在本次 Armada 提交内。

## 目标（一句话）

运营在手动接码页面对等码订单点一次“开始注册”：后台自动占一个代理 IP → 让 Go 发码 → 等号商出码 → 把码交给 Go 验证 → 显示结果；关页面也继续，可同时办理多个。

## 缺口拆解 / 任务清单
- [x] Flyway `V223__sms_registration.sql`：新表 `sms_registration`（一订单最多一条办理记录）
- [x] Flyway `V224__ip_proxy_registration_reservation.sql`：`ip_proxy.registration_activation_id` 独立归属列 + 唯一键，状态注释改为“4=占用”
- [x] resource 域：`IpProxyService.allocateRegistrationEndpoint / releaseRegistrationAllocation / findEndpoint`；配对占用里“选候选并抢占”的循环抽成 `reserveIdleProxy`，配对与注册共用，不复制
- [x] `platform/registration/go`：`GoRegistrationClient`（三个方法，每个只发一次 HTTP）、`GoRegistrationProperties`、响应分类 `GoRegistrationResponse.Kind`；`GoRegistrationConfiguration` 装配禁重试、禁重定向的 HTTP 客户端
- [x] account 域：`SmsRegistrationService(Impl)`（开始/占用判断/附加状态）、`SmsRegistrationWorker`（后台推进）、`AccountSmsRegistrationScheduler`；`SmsActivationService` 增加 `pollProvider`，`complete/cancel` 在订单被占用时拒绝，`refresh` 占用时只读本地
- [x] 接口：`POST /api/sms-activations/{id}/registration`；`SmsActivationVO` 增 `registrationState/registrationFailure`，`options` 增 `registrationEnabled/registrationDisabledReason`
- [x] 前端：`api/sms-activation.ts`、显示辅助、composable（`register`、办理中的订单继续刷新）、卡片“开始注册”按钮与办理状态、记录表“注册”列
- [x] `.harness/wiki/数据模型.md` 与 `gen_datamodel.py` 分组：手工补 `sms_registration` 与 `ip_proxy` 新列（生成脚本需要真库 information_schema 转储，本机没有，沿用上午接码那次的手工补法）
- [x] Go 侧 `code-requests` 的 `proxy` 字段（首次必填、重发不得带、加密保存、纳入幂等指纹、交给 Transport 入参；Go 仓库已实现并测试，未提交）
- [x] Go 侧真实发送器已写（字段组装、ENC、经代理发 HTTPS、响应归类、`cmd/registration-profile` 导入设备配置），**未对真实服务器验证**
- [ ] Go 侧其余：部署时把 `max_concurrent` 配成 64、同号占用释放；联调后按服务端 `reason` 调整字段
- [ ] 真实单号及并发验收（需指定环境、号码数、次数）

## 关键设计决策
- **不加任何锁。** 部署只有一个后端容器；调度用 `fixedDelay`，上一轮（含其中并发步骤）结束才开始下一轮，同一条记录不会被两处同时处理。防重复发码靠稳定的 Idempotency-Key，不靠互斥。否决了简版方案里的每记录租约、`next_run_at`、“取消中”状态、释放到人工等。
- **重驱动**：申请中与验证中每轮用同一把键、同样参数重新调 Go（键 `sms-go-v1:{租户}:{办理ID}:send|verify`）。Go 对同键同参返回原操作的结果，超时、重启、处理中都用同一个动作覆盖；只有 Go 明确说 `OUTCOME_UNKNOWN` 才停，绝不换键重发。
- **`go_version` 只写一次**（发码成功时），验码永远用它；SQL 用 `COALESCE` 保证迟到的写入覆盖不了。
- **订单占用 = 办理状态为申请中/等码/验证中**。占用期间旧的完成/取消拒绝、页面刷新只读；进入已注册或需处理后自动恢复，旧按钮就是运营的出口，不新增动作。
- **代理占用用独立归属列**，不复用 `pairing_session_id`：它与配对会话表是不同自增序列且有全局唯一键，混用会撞号。状态复用 4，继承删除保护与检测不覆盖。国家按号码区号解析，**不回退其它国家**；无库存直接失败，不创建办理记录、不调 Go。
- 发码、等码、验码期间保留同一条代理分配，连接串由 `ProxyResolver` 生成（与协议层登录同一口径）。接受供应商粘性会话到期后的出口 IP 变化；因此无法继续时按现有失败流程结束，不增加保持旧出口的机制。
- **成功和失败都归还代理池**，取消、过期、代理不可用等结束路径同样释放。释放成功后清空 `sms_registration.proxy_id`；终态仍有 `proxy_id` 的记录由原定时任务继续释放，进程重启后也可恢复。补偿只做代理释放，不重新发码、验码或通知号商；沿用代理 ID、订单 ID、租户的归属校验，避免旧订单误释放已重新分配的代理。无新表、新状态或独立定时任务。
- COMPLETE 只在进入已注册的那一轮调用一次，失败不重试：订单保持已收码，运营用旧“完成”按钮收尾。
- 一轮内用固定大小线程池并发（默认 32，上限 64，须不超过 Go 的 `max_concurrent`）；线程里按记录恢复租户上下文，结束后清理。
- 与任务书的差异：Go 对同号冲突与其它冲突都返回 409/`CONFLICT`，无法区分，所以没有 `PHONE_CLAIMED`，统一为 `GO_CONFLICT`；新增 `PROXY_UNAVAILABLE`。

## 验证（evidence-before-done）

后端（`JAVA_HOME=temurin-21`，离线 Maven）：

```
mvn -o test -Dtest='Flyway*Test,BusinessControllerAuthorizationContractTest,SmsActivation*Test,SmsRegistration*Test,
  GoRegistration*Test,IpProxy*Test,!*DbTest'
→ 157 个，仅 1 个失败：BusinessControllerAuthorizationContractTest.permitsOnlyBusinessMenusThatActuallyUseSharedReadEndpoints
  （MarketingTemplateController.list 缺 script_marketing:view，上午变更记录已写明的已有失败，与本次无关）
mvn -o test -Dtest='com.armada.account.**.*Test,com.armada.resource.**.*Test,com.armada.platform.registration.**.*Test,
  MysqlModeMapperInMemoryTest,!*DbTest'
→ 1089 个，2 个错误均在 MysqlModeMapperInMemoryTest 的群文件夹用例（测试自带建表缺 group_folder.system_builtin），
  GroupFolderMapper.xml 未被本次改动，非本次引入
```

新增测试：`GoRegistrationClientTest` 18、`SmsRegistrationFlowH2Test` 19（真实 H2 Mapper + 真实订单/办理服务 + 真实 worker，替身掉 Go/号商/代理池）、`SmsRegistrationMapperH2Test` 3、`IpProxyRegistrationMigrationH2Test` 2、`GoRegistrationConfigurationTest` 2（证明 yml 里记录的环境变量名真的能绑定，原始版本包含租户令牌；当前无令牌合同见 2026-11-05-remove-registration-token.md）、`IpProxyMapperH2Test` +1、`IpProxyServiceImplTest` +3、`SmsActivationServiceTest` +2。

“改坏”抽查（改完已还原）：让发码键每轮变化 → 流程测试 2 个失败；让占用检查失效 → 占用测试 2 个失败。

前端：

```
node --import ./src/api/__tests__/node-test-alias.mjs --test --experimental-strip-types
  src/views/account/sms-activation/composables/useSmsActivation.test.ts → tests 6, pass 6
eslint（改动文件）→ 0；prettier --check → 干净；vue-tsc --noEmit --skipLibCheck → 0 错误
```

### 2026-11-05 代理释放调整

按用户确认，直接在 Armada `1.0.3-snapshot` 主 checkout 修改，没有创建 worktree。此次只调整注册收尾与代理释放补偿，保留多号码并发；Go、前端、数据库结构及手动取消/开始的并发控制均未改动。

- 先红：新增/更新回归断言后，26 个测试中 11 个失败、0 错误，复现成功不释放和终态不补偿；日志 `/private/tmp/sms-proxy-release-rc_0n4ly/red-behavior.log`。
- 再绿：以下聚焦测试 **123 个通过，0 失败、0 错误、0 跳过，退出码 0**。其中注册流程 23 个、注册 Mapper 5 个、代理 Mapper 5 个；真实 H2 SQL 验证终态扫描、清空标记、租户隔离和旧订单释放保护。覆盖释放失败、清空标记失败、重启补偿、多号码各自归还和号商完成失败时仍归还代理。

```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/temurin-21.0.12.1/Contents/Home \
  /opt/homebrew/bin/mvn -o -q \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  '-Dtest=SmsRegistration*Test,SmsActivationServiceTest,SmsActivationMapperH2Test,IpProxyMapperH2Test,IpProxyServiceImplTest,GoRegistration*Test' test
```

最终日志 `/private/tmp/sms-proxy-release-rc_0n4ly/final-test.log`。预加载本地 Byte Buddy agent 解决测试 mock 的动态挂载限制；HTTP 客户端测试仅使用 `127.0.0.1` 替身，解除沙箱本地监听限制后通过。Mapper XML 校验及差异空白检查通过。未连接真实数据库、Go、号商或 WhatsApp，未部署。

提交前补验：同一 Java 21 / 离线 Maven 环境运行 `-Dtest=IpProxyRegistrationMigrationH2Test,Flyway*Test`，7 个通过，0 失败、0 错误、0 跳过，退出码 0；日志 `/private/tmp/sms-proxy-release-rc_0n4ly/precommit-migrations.log`。验证 V224 结构/唯一归属及迁移版本、历史兼容性，不代表在真实 MySQL 执行过迁移。

## 未验证（如实）
- 没有连接真实 Go、真实号商、真实 WhatsApp；本次验证不代表真实注册成功。
- 没有在真实 MySQL 上执行 V223/V224。H2 不接受 `MODIFY COLUMN … COMMENT`，V224 采用“结构断言 + 剥掉 MySQL 专有子句后执行”；部署时需在真库验证。
- 前端没有在浏览器里实际点过（需要后端与账号环境），仅有单元测试、类型检查与 lint。
- 号商调用频率上限无资料；默认每 5 秒一轮、每条等码记录查一次，批量放量前先向号商确认。
- `vite build` 未跑。

## 部署
- 未部署。上线顺序：Go 网络层与 `proxy` 字段就绪 → 配置 `SMS_REGISTRATION_ENABLED/GO_BASE_URL` 与各租户 `ARMADA_ACCOUNT_SMSREGISTRATION_TENANTS_<租户ID>_PROFILEID`（商业版使用 `_BUSINESSPROFILEID`） → 先单号实测再放量。回滚：关 `SMS_REGISTRATION_ENABLED`，停止新办理与自动推进，已有办理记录与订单保留，进行中的订单转人工用旧按钮处理。

## 遗留 / 跟进
- 注册成功后的导入、上线（本次不实现，注册结束即归还代理）。
- Go 的同号占用永久不释放，而号商会复用同一号码 → 第二次发码永远 409；需要 Go 提供失败且未注册事务的释放能力。
- 发码出口 IP 的国家与号码国家是否需严格一致、同一出口并发注册个数的风控，属 Go 网络层规格，需有人拍板。
- `IpProxyStatus.PAIRING_RESERVED` 的展示名仍是“配对占用”，注册占用的 IP 在 IP 管理页也会显示这个名字；未改标签以免影响现有页面。
