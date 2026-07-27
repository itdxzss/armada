# 变更记录：拉群营销逐个拉料与随机间隔

- 日期 / 分支 / worktree: 2026-07-27 / `1.0.2-snapshot` / 未创建 worktree，直接修改当前 checkout
- 需求来源: 本轮用户确认；首个料子也等待，前端配置 1～60 分钟，实际固定按上下 20% 随机
- 状态: 已部署第一套测试环境

## 目标（一句话）

将拉群营销的整批拉料改为逐个拉料，并通过数据库排期实现首个料子、后续料子及失败重试的随机等待。

## 缺口拆解 / 任务清单

- [x] 任务增加 `material_entry_interval_seconds`，默认 300 秒，限制为整分钟的 60～3600 秒。
- [x] 每次协议请求只添加一个料子，`ALREADY_IN` 按成功处理。
- [x] 首个料子、相邻料子和普通失败重试均按基准间隔上下 20% 独立随机排期。
- [x] 普通失败最多重试两次；第三次失败后继续处理下一个料子或进入后置清理。
- [x] 明确群封禁立即终止当前执行，不进入普通重试。
- [x] 暂停不继续拉料；恢复时为待拉料执行重新独立随机排期。
- [x] 手动结束、超时或资源释放后不再拉料，待处理料子置失败并进入后置清理。
- [x] 复用 `next_execute_at` 和 `stage_retry_count` 持久化进度，服务重启后继续按数据库排期执行。
- [x] 日志只记录任务、执行、分配序号、尝试次数、结果和下次时间，不记录手机号或 JID。

## 关键设计决策

- 不使用 `Thread.sleep`；调度线程仅领取已到期执行，协议调用结束后持久化下一次执行时间。
- 随机窗口为闭区间 `[base * 80%, base * 120%]`，每次调度重新采样。
- 阶段 5 只读取一条待处理关系并发起一个 participant 请求，避免一批料子瞬间进入群组。
- 暂停期间调度 SQL 排除阶段 5，但允许释放中的任务继续后置清理，防止资源无法收敛。
- 结束条件在协议调用前再次读取任务运行态，避免已结束任务继续产生 WhatsApp 副作用。
- 数据库迁移使用 `information_schema` 守卫，兼容测试环境曾手工补列的情况。

## 数据库变更

- Flyway: `armada-api/src/main/resources/db/migration/V080__group_pull_material_entry_interval.sql`
- `group_pull_marketing_task` 新增 `material_entry_interval_seconds INT NOT NULL DEFAULT 300`。
- 运维审查副本见 `db-migrations.sql`，回退脚本见 `rollback.sql`。
- `.harness/wiki/数据模型.md` 是真库生成物，本轮不连接真实数据库，未手工修改。

## 验证（evidence-before-done）

- 12 个相关测试类共 48 条测试通过，覆盖配置边界、随机端点、首条等待、单条协议请求、
  `ALREADY_IN`、两次重试、封群、暂停、结束清理、恢复重排、安全日志和 Mapper 内存执行。
- `mvn -q -DskipTests package`：通过。
- `xmllint --noout armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`：通过。
- 本地非真库全量测试共运行 1326 条，存在 3 条与本次文件无关的既有失败：
  `HistoricalGroupPullWorkerImplTest` 两条旧协议账号参数断言，以及
  `GroupCreationMarketingTaskMapperSqlShapeTest` 一条旧 SQL 参数前缀断言。
- 真实 MySQL DbTest 未运行；执行前需用户确认具体目标环境。

## 部署

- 2026-07-27 使用当前未提交工作区部署到 `test1 / 第一套环境`，范围为 Armada 后端和前端；
  Baileys 协议层与 Zhuan 均未部署。
- 前端通过临时发布目录只带入本功能文件，未携带本地既有的
  `src/utils/http/index.ts` 修改。
- `deploy-test.sh --env test1 --all -y` 退出码为 0；`armada-backend`、
  `armada-nginx` 均已重建并运行，内置检查确认前端可访问、环境标识正确且 API 已转发到后端。
- 部署时修复了脚本构建完成后未解析实际 JAR 路径的问题；当前修改仍未 commit。

## 回滚

- 先回退应用代码，再执行 `rollback.sql` 删除新增配置列。
- 删除列会丢失任务自定义间隔；回退前应确认不再需要该配置。

## 遗留 / 跟进

- 上线到明确环境后，应执行 Flyway 并按仓库生成流程刷新数据模型文档。
