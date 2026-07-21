# 变更记录：账号动态营销新群即时发送

- 日期 / 分支 / worktree: 2026-07-20 / `1.0.1-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户本次需求；设计文档 `docs/superpowers/specs/2026-07-20-account-dynamic-new-group-immediate-marketing-design.md`；实施计划 `docs/superpowers/plans/2026-07-20-account-dynamic-new-group-immediate-marketing.md`
- 状态: 进行中（本地实现和非数据库验证已完成，真库 DbTest 待确认目标库）

## 目标（一句话）

发送中的 `ACCOUNT_DYNAMIC` 普通营销任务在账号新加入群组时立即补发一次，并保持任务原有轮次、时间和协议路由不变。

## 缺口拆解 / 任务清单

- [x] 对账 Web、Android Zhuan 群变化上报链路。
- [x] 对账 Armada membership、动态目标解析、attempt 和 outbox 链路。
- [x] 确认按任务 + 账号 + 群 JID 幂等以及多账号分别触发。
- [x] 完成轻量方案设计。
- [x] 用户复核书面设计。
- [x] 编写并自检实施计划。
- [x] TDD 实现群快照差量、baseline 抑制和群回报触发。
- [x] 抽取普通轮次与即时发送共享的消息命令工厂。
- [x] 实现 `round_no=0` 首次 attempt 抢占、现有 outbox 分批入队和账号内发送间隔。
- [x] 实现 `attemptId + commandId` 结果幂等和一次业务重试。
- [x] 补充 V059、前滚/回滚 SQL、单元测试和真库 DbTest 用例。
- [x] 完成非数据库编译、XML、聚焦单测和静态差异检查。
- [ ] 在确认的隔离测试库执行 Flyway 与真库 DbTest，并据真实 schema 更新数据模型 wiki。
- [ ] 在测试环境完成 Web/Zhuan 实际进群发送验收。

## 关键设计决策

- 不扫描全部任务或 membership，不新增即时发送任务表和专用调度器。
- 在现有 `account.groups_reported` 事务中计算账号群差量，只处理真新增群。
- 首次 baseline 不触发即时营销，避免把上控前已有群当成新增群。
- 使用现有 `marketing_task_send_attempt`，保留 `round_no=0` 表示新群即时发送；正常轮次保持 `round_no>=1`。
- 复用现有 target + round + group 唯一键承担任务 + 账号 + 群 JID 幂等，不修改唯一索引。
- 业务重试复用同一 attempt 行，将 `attempt_no` 从 1 更新到 2，并以 commandId 条件拦截迟到结果。
- 复用 `MessageSendPort`、`protocol_command_outbox` 和 afterCommit dispatcher；Web、Android Zhuan 协议层均不改。
- 即时发送不更新任务 `current_round_no`、`next_round_at` 等正常轮次字段。
- 同次上报超过现有 outbox 单批上限时，沿用普通营销文本/图片批大小拆分；跨批保持全局 `notBeforeAt` 顺序。
- 调度批大小参数改由非 profile 限定的通用配置注册，避免新群检测服务在非 `kafka` profile 下缺少配置 Bean。

否决方案：

- 周期扫描 `joined_at`：持续全局扫描、需要游标且并发幂等更复杂。
- 独立即时任务表：增加表、状态机、worker 和恢复链路，本期现有 attempt/outbox 足够。

## 验证（evidence-before-done）

- 分支确认：`git branch --show-current` 输出 `1.0.1-snapshot`。
- Mapper XML：`xmllint --noout` 校验群 membership 和营销 Mapper，exit 0。
- 全量测试源码编译：Java 17 下 `mvn -q -DskipTests test`，exit 0；新增 DbTest 已通过编译。
- 聚焦非数据库测试：Java 17 + Byte Buddy agent 下执行 12 个相关测试类，86 tests / 0 failures / 0 errors / 0 skipped。
- 聚焦范围覆盖：群差量、baseline 抑制、消息工厂、正常轮次回归、即时幂等/间隔/分批、一次重试、旧 command 迟到结果、SQL 形态、非 Kafka 配置装配及 Web/Android 路由后端。
- 静态检查：`git diff --check`、`TODO/FIXME/System.out/printStackTrace` 扫描通过；未修改 Web、Android Zhuan 或前端项目。
- 真库 DbTest **已编写但未执行**：当前尚未确认 `.env` 指向可迁移、可回滚的隔离测试库，按仓库红线不擅自连接数据库。
- `.harness/wiki/数据模型.md` **尚未更新**：必须等 V059 在确认的测试库迁移后从真实 schema 重新生成。

## 部署

- commit / 环境 / 部署后验证结果: 按用户要求只保留 `1.0.1-snapshot` 当前 worktree 本地修改，未 commit、未部署、未访问远程环境。

## 遗留 / 跟进

- 用户已明确要求在当前 `1.0.1-snapshot` worktree 内联修改且不 commit，覆盖原计划中的隔离 worktree/逐任务 commit 步骤。
- 下一步需由用户确认 DbTest 数据库目标是可执行 Flyway 的隔离本地/测试库，再运行迁移、Mapper、结果重试和端到端 DbTest。
- 真库通过后重新生成 `.harness/wiki/数据模型.md`，随后安排 Web/Zhuan 测试环境实际进群验收。
