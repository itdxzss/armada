# 变更记录：账号删除与业务风控手动解除

- 日期 / 分支 / worktree: 2026-09-01 / 1.0.3-snapshot / 主检出
- 需求来源: 用户确认“被抢登行内与批量可删除”、“批量手动移除超链发送和拉手拉人风控时间”、“列表分项展示两类业务风控”
- 状态: 已完成，已获授权提交、推送并部署测试环境

## 目标（一句话）

在不改动旧 `risk_status/risk_end_time` 账号级风控的前提下，支持被抢登账号删除，并为超链发送/拉手拉人两类业务风控提供分项展示和批量人工解除。

## 缺口拆解 / 任务清单

- [x] 删除闸门加入被抢登状态，保留“未进入任务”和整批全或无语义。
- [x] 账号列表返回超链发送、拉手拉人各自的限制截止时间。
- [x] 增加租户隔离的批量手动解除接口，同时清除两类本地业务限制。
- [x] 增加人工解除事实水位，阻止解除前延迟/重放事件重新限制，但允许解除后新风控事实生效。
- [x] 前端增加“业务风控”分项展示和“手动移除风控时间限制”批量操作。

## 关键设计决策

- 新增 `account_state.manual_restriction_cleared_at`。不复用全局 `restriction_reported_at`，因为它是跨能力的最新原因投影，不能安全充当消息和拉人两个来源的人工解除水位。
- 手动解除清理 `fallback_message_restriction_until`、平台消息限制投影、`pulling_restriction_until`及兼容列 `mute_status/cooldown_until`；不修改账号生命周期、登录状态和旧账号级风控。
- 列表使用两个独立截止时间，不用统一 `cooldown_until` 代替，避免两类限制截止时间不同时展示失真。

## 验证（evidence-before-done）

- `mvn -Dtest='AccountPullerRestrictionServiceH2Test,AccountServiceImplTest,AccountControllerTest,AccountConverterTest,AccountOperationRestrictionManualClearMigrationSqlTest,AccountOperationRestrictionListProjectionSqlTest' test`
  - `Tests run: 52, Failures: 0, Errors: 0, Skipped: 0`
- `xmllint --noout src/main/resources/mapper/account/AccountMapper.xml src/main/resources/mapper/account/AccountStateMapper.xml`
  - 退出码 0。
- `python3 .harness/wiki/test_api_docs.py`
  - `Ran 1 test ... OK`，检出 52 个 Controller / 288 个端点。
- 前端 `account-display.test.ts` + `AccountListTable.test.ts`
  - 19 个测试全部通过。
- 前端 `account.test.ts`（使用测试 double 加载器，避免 Node 直读 Vite CSS/env）
  - 14 个测试全部通过。
- `./node_modules/.bin/tsc --noEmit` 和 `./node_modules/.bin/vue-tsc --noEmit --skipLibCheck`
  - 均退出码 0。
- `./node_modules/.bin/vite build`
  - 生产构建成功，`built in 13.91s`。
- `mvn test`
  - 全量套件进入仓库既有真库 `PromotionCapiEventOutboxSchemaDbTest` 后持续重试外部数据源；在未授权连真库的前提下人工中止，不计作全量通过。

## 部署

- commit / 环境 / 部署后验证结果: 由本次交付记录回填；部署仅包含后端与前端，不包含协议层。

## 遗留 / 跟进

- MySQL `information_schema` 守卫迁移使用脚本结构测试；未授权连接真库验证。
- `.harness/wiki/数据模型.md` 是禁止手改的真库 TSV 生成物；当前无 `/tmp/wheel_columns.tsv` 等真库输入，本地未伪造刷新。实际环境执行 V178 后需重跑 `gen_datamodel.py` 刷新。
