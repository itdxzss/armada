# 变更记录：测试环境超链零计费闭环

- 日期 / 分支 / worktree: 2026-08-31 / `1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户明确“钱包先不接入”，确认第一套环境先采用计费为 0 并要求快速改造
- 状态: 已部署 test1，待页面业务验收

## 目标（一句话）

第一套测试环境在不接真实钱包时以明确的零计费提供方跑通超链任务创建、启用、发送与收口，默认和生产路径继续失败关闭。

## 缺口拆解 / 任务清单

- [x] 增加仅接受零金额的 `ZERO_TEST` 钱包适配器
- [x] 默认未配置时继续使用失败关闭钱包适配器
- [x] 零计费未配置外部报价密钥时使用进程内临时密钥
- [x] 增加租户隔离、事件幂等的数据库审计表与适配器
- [x] 测试环境 Compose 默认启用 `ZERO_TEST`，生产 Compose 不启用
- [x] 补聚焦单测、H2 真实 Mapper 测试和 Flyway 结构合同

## 关键设计决策

- 不在核心任务逻辑里硬编码假余额；零计费是显式钱包提供方，报价、冻结、调整、结算和释放金额只能为零。
- 任一非零金额进入零计费适配器立即按计费不可用失败关闭，避免未来真实价码误走测试实现。
- 外部预约号复用稳定的 operationKey；零计费没有外部副作用，因此重放天然幂等。
- 审计使用独立 `hyperlink_task_audit_event` 聚合；同租户 `event_id` 唯一，任务事务内写入，计费恢复可以安全重放。
- 零计费进程内临时签名密钥只影响重启前尚未确认的短期报价；后端重启后页面重新报价即可。

## 验证（evidence-before-done）

- `mvn -DskipTests compile`: `BUILD SUCCESS`。
- `mvn -Dtest='Hyperlink*Test,!HyperlinkRuntimeConcurrencyMySqlTest,ZeroBillingHyperlinkWalletPortTest' test`:
  305 tests，0 failures，0 errors，0 skipped。
- 聚焦零计费、真实 Mapper、计费 Saga、报价恢复、任务生命周期 9 类回归:
  39 tests，0 failures，0 errors，0 skipped。
- `mvn -Dtest='FlywayMigrationSqlContractTest,HyperlinkZeroBillingMigrationSqlTest' test`:
  2 tests，0 failures，0 errors。
- `bash armada-deploy/deploy-test.test.sh`: 通过。
- `docker compose --env-file armada-deploy/.env.example -f armada-deploy/docker-compose.rds.yml config --quiet`:
  通过。
- 新增 Mapper XML 经 `xmllint --noout` 校验通过；`git diff --check` 通过。
- 直接执行无筛选 `mvn test` 会进入仓库原有真库 `DbTestBase` 用例；未通过 `dbtest.sh`
  注入获准测试库配置时持续连接外部 MySQL，因此中止。该阻塞发生在本次相关测试之前，未作为本次
  改造失败处理，也没有为验证而连接或迁移远程数据库。

## 部署

- 环境: `test1`，范围: 仅后端，命令:
  `bash deploy-test.sh --env test1 --be -y`。
- 来源: 当前 `1.0.3-snapshot@9727a5d6` 脏工作区；未提交、未推送。
- 最终本地、远端暂存、运行容器 JAR SHA-256:
  `7b96e4d8be277fa83488ab0138ce707fedc3093310d200a759d7fcb3eaab1b0b`。
- 运行镜像: `sha256:28cbe63e231c8b883019cbea32de65d90fa3450054c157ff70657fa83622cf58`；
  `status=running`、`restart=0`。
- Flyway: schema 已在首次部署从 170 迁移至 171；最终复部署确认 171 为最新，无待执行迁移。
- 运行配置: `ARMADA_HYPERLINK_BILLING_MODE=ZERO_TEST`；应用约 20 秒启动完成。
- 同步修复 Bash 部署脚本: 后端部署强制重建容器，并在远端暂存和容器运行两个阶段校验
  本次构建 JAR SHA-256；不一致即部署失败。不带 `--branch` 时新增明确警告：不会自动
  fetch/pull，而是部署当前工作区（包括未提交改动）。

## 遗留 / 跟进

- 真实钱包接入后提供自己的 `HyperlinkWalletPort` Bean，并将测试环境配置切回真实模式。
- `.harness/wiki/数据模型.md` 由目标 MySQL `information_schema` 生成；V171 部署后再刷新，当前禁止手工修改。
