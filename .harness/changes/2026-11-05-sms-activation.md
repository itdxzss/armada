# 变更记录：后台手动接码

- 日期 / 分支 / worktree: 2026-11-05 / armada `1.0.3-snapshot`、wheel-saas-pure-web `1.0.3-snapshot` / 主 checkout
- 需求来源: 用户要求运营在后台手动取号、看到号码和验证码；任务书 [2026-11-05-手动接码开发任务书](../../docs/handoff/2026-11-05-手动接码开发任务书.md)
- 状态: 已完成（离线），已推送、未部署、真实 API 验收未执行

## 目标（一句话）

账号管理 → 手动接码：选国家/价格 → 取号 → 显示号码 → 轮询出码 → 完成 / 取消。

## 缺口拆解 / 任务清单
- [x] Flyway `V221__sms_activation.sql`：新表 `sms_activation`（一行 = 一次取号）
- [x] Flyway `V222__sms_activation_menu.sql`：账号管理下菜单 `AccountSmsActivation`，`sort_no=45`，权限 `tenant:account:edit`
- [x] 后端 `account` 域：`SmsActivationController` / `SmsActivationService(Impl)` / `SmsActivationMapper(.xml)` / entity / DTO / VO / `SmsActivationConverter` / `SmsActivationState`
- [x] 前端：`src/api/sms-activation.ts`、`src/views/account/sms-activation/`（页面 + 3 个组件 + composable + 测试）、菜单管理组件下拉与 mock 菜单补 `account/sms-activation/index`
- [x] 刷新 `.harness/wiki/数据模型.md`：perf2（第二套环境）尚未执行 V221，库里没有该表；改用生成脚本的离线迁移元数据模式（`ARMADA_MODEL_*` 指向由 V221 解析出的 TSV）生成 `sms_activation` 段落并合入；`gen_datamodel.py` 把该表归入「账号与归属」
- [ ] 真实 API 验收

## 关键设计决策
- 不复用 `account_registration_item`：那张表会被注册 worker 的 `nextWork` 扫走。
- 国家/服务目录与价格档复用 `AccountRegistrationService.catalog()` / `priceTiers()`，只取 `serviceCode` 与 `countries`；取号开关只看 `GrizzlySmsProperties.enabled && purchasesEnabled`，不依赖 Cobalt、Kafka、注册调度开关。
- 取号在请求线程同步完成；`NO_NUMBERS` / `NO_BALANCE` 转为中文提示，其他异常（含结果不明）原样返回，不写表、不重试。
- 不做后台定时任务：前端每 4 秒对本页"等码"记录调 `GET /{id}`，后端查一次平台并用 `WHERE state=?` 条件更新回写；过期由本地 `expires_at` 判定，不调平台。
- 验证码按用户确认存明文，但号码和验证码不进日志；实体 `toString` 已脱敏。
- 否决：Codex 版任务书的预算预留、幂等键、交付 ACK、OTP 加密、对账入口（人工逐个取号场景用不上）。
- 首版未做：列表状态筛选、"再收一条短信"（`setStatus 3`）、美国以外国家。

## 验证（evidence-before-done）

后端（JDK 21：`JAVA_HOME=temurin-21`；本机默认 Maven 跑在 JDK 27 上会让 Mockito inline mock 全部报错，与代码无关）：

```
mvn -o -Dtest='SmsActivation*Test,AccountRegistration*Test,Grizzly*Test,DeviceRegistration*Test,Flyway*Test' test
→ exit 0；SmsActivationServiceTest 10、SmsActivationMapperH2Test 2、SmsActivationMenuMigrationTest 1、
  AccountRegistration*/DeviceRegistration*/Grizzly*/Flyway* 全部 0 失败
```

`BusinessControllerAuthorizationContractTest` 有 1 个已有失败：`MarketingTemplateController.list` 的权限断言与实际不一致，与本次改动无关，未处理。

前端：

```
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/account/sms-activation/composables/useSmsActivation.test.ts src/views/account/registration/composables/useAccountRegistration.test.ts
→ tests 15, pass 15
eslint（改动文件）→ 0；tsc --noEmit → 0；vue-tsc --noEmit --skipLibCheck → 0；vite build → 成功
```

## 部署
- 已提交并推送到 1.0.3-snapshot（armada + wheel-saas-pure-web），未部署。

## 遗留 / 跟进
- 真实验收：取号 → 页面看到号码 → 发起 WhatsApp 注册 → 页面出码 → 完成；再取一个号 → 到可取消时间后取消 → Grizzly 后台确认已退款；确认结果不明的号码到期后平台是否自动退款。
- 部署到 perf2 后可从真库再导出一次核对 `sms_activation` 段落。
