# 营销首次发送延迟修复

- 日期 / 分支 / worktree：2026-10-05，`1.0.3-snapshot`，后端与前端主仓库。
- 需求来源：用户确认已有群从任务启动计时，新群从任务启动后检测计时，首次等满配置时长，后续跟任务普通轮次；在主仓库修改，不做过度设计。
- 状态：代码与本地验证完成，2026-10-05 已按用户后续授权部署第二套 perf2 后端和前端。

## 行为与实现

- 立即创建、手动启动、定时启动：已有群首轮时间为实际首次启动时间加配置延迟。
- 暂停恢复：保留首次启动时间；尚未等满不能提前发送，已经等满不重新等待。
- 新群复用第 0 轮 WAITING；首发完成后进入后续普通轮次。重复事件不重置等待。
- 普通轮次选群、新群登记和到期首发共用现有任务行锁，防止并发读取绕过等待或同轮重复首发。
- 补齐自身入群事件和成员新增事件的登记；事实与 WAITING 在同一事务内写入。快照检测时间取后台处理时间，避免旧协议快照时间提前首发。
- 前端更新为“首次发送延迟”，说明已有群与新群的不同起算点。

## 范围

- 复用现有延迟配置、任务调度、等待记录及 Outbox；无新表、字段、API、Redis 或协议格式变更。
- 仅改变普通营销首次发送，关闭延迟保持现有行为。
- 实施阶段保留其他会话的拉群和部署文件；用户随后授权发布当前主仓库，发布包含主目录当时全部生产源码，不提交、不修改远程业务数据。

## 验证

- 回归测试先红：4 项业务断言失败，覆盖立即启动、提前轮次、等待登记锁、快照检测时间。
- 初次沙箱测试被 Mockito JVM 附加机制阻断；相同已编译测试在本机沙箱外运行后确认上述 4 项为业务失败。
- 后端 21 个相关测试类/选定方法共 235 项通过，0 失败、0 错误、0 跳过。使用 Java 17，执行 `mvn -q -Dtest='<相关测试>' test`；主运行日志 `/private/tmp/marketing-first-delay-verified.log`，更新旧恢复调度断言后 `MarketingTaskMapperSqlShapeTest` 的 25 项在 `/private/tmp/marketing-first-delay-sql-shape.log` 复验通过。最终数量按各类最新 Surefire XML 汇总。
- 新增 `MarketingFirstSendDelayMapperH2Test` 8 项使用真实 Mapper XML、生产租户插件和 Spring 事务，覆盖立即/预约启动相关 SQL、分钟/小时、暂停恢复、租户隔离、17:30 检测后 18:30 首发且 19:00 归队、CAS 防重，以及两个事务任务行锁等待后可见 WAITING。
- 其他回归覆盖普通轮次、到期首发、自动重试、任务生命周期、自身/受控成员入群、PN/LID 身份回退、缺失 envelope 时间、乱序事件、进群任务结果和 Kafka 消费。
- `xmllint --noout armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml` 与本次文件 `git diff --check` 通过。
- 前端 2 项延迟相关测试、组件 ESLint、Prettier 检查通过。

## 范围外测试问题

- 扩大检查时，未修改的 `GroupMembershipCountSemanticsMapperH2Test.accountListReadsCurrentBindingWhileMarketingTreeKeepsItsOwnCountSemantics` 因旧 H2 account fixture 缺少 `declared_account_type` 列失败。最终对该类只运行本次相关的 6 项动态选群/普通发送覆盖方法，全部通过，没有改账号列表业务或夹具。
- 前端完整 `GroupMarketingCreateDrawer.test.ts` 为 15/16 通过；既有账号分组标签断言仍期待内联标签，而实际已使用 `formatAccountGroupLabel`，与延迟文案无关。本次未修改此断言。

## 回滚与限制

- 回滚本次指定文件的修改即可，无数据库迁移。
- 已部署 perf2；本地 H2 不等于 MySQL InnoDB 的全部锁行为，真实营销业务验收未执行。
- 现有 baseline / 手工历史群刷新不触发即时营销的范围保持不变，不扩大历史群发送范围。

## 部署

- 2026-10-05 使用主仓库实际文件执行 `deploy-test.sh --env perf2 --all -y`，后端和前端 SUCCESS。
- 运行中 JAR、前端首页及营销配置 chunk 与本次制品哈希一致，重启次数 0；Flyway V210，无新迁移，发布后只读深度检查通过。
- 旧制品与镜像已备份，未 commit/push；详见 [发布证据](../../docs/operations/evidence/marketing-first-delay-perf2-20261005/summary.md)。
