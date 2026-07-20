# 变更记录：普通营销任务允许在线抢登账号

- 日期 / 分支 / worktree: 2026-07-17 至 2026-07-18 / `1.0.1-snapshot` / 主 worktree
- 需求来源: 用户要求“抢登、抢登中也可以”，并确认离线仍不可选择
- 设计文档: `docs/superpowers/specs/2026-07-17-marketing-takeover-account-selection-design.md`
- 状态: 已完成（已部署第二套性能环境）

## 目标（一句话）

让普通营销任务允许选择并创建在线的正常、被抢登、抢登中账号目标，同时保持离线和其他现有异常门禁。

## 缺口拆解 / 任务清单

- [x] 核对账号树、前端禁用逻辑和创建候选 SQL。
- [x] 确认被抢登/抢登中仍必须在线。
- [x] 先补账号树与创建校验失败测试。
- [x] 放开普通营销任务允许的账号状态集合。
- [x] 运行单元测试、定向真实 MySQL DbTest 和相关质量门禁。

## 关键设计决策

- 只调整普通营销任务，不修改建群营销、历史群营销或其他账号选择器。
- 允许状态为 `正常(2)`、`被抢登(6)`、`抢登中(7)`；三者均继续要求 `login_state=1`。
- 前端已消费后端 `selectable`，本次不修改前端生产代码或 API 结构。
- 固定群组和账号动态两种创建目标必须与账号树使用相同状态口径。
- 不增加抢登状态展示文案，避免扩大本次范围。

## 验证（evidence-before-done）

- 基线：`mvn -Dtest=MarketingAccountTreeRealtimeServiceTest,MarketingTaskMapperSqlShapeTest,MarketingTaskServiceImplLifecycleTest test`
  - 修改前 `27` 个测试通过，`BUILD SUCCESS`。
- 账号树 TDD：
  - RED：新增用例后 `8` 个测试中 `1` 个失败，在线 `account_state=6/7` 实际不可选。
  - GREEN：`mvn -Dtest=MarketingAccountTreeRealtimeServiceTest test`，`8/8` 通过。
- 创建候选 SQL TDD：
  - RED：SQL 形状测试确认两个创建候选查询仍写死 `s.account_state = 2`。
  - GREEN：`mvn -Dtest=MarketingTaskMapperSqlShapeTest,MarketingTaskServiceImplLifecycleTest test`，`20/20` 通过。
- 定向真实 MySQL DbTest：
  - RED：临时恢复旧 `account_state = 2` 条件后执行新增三条用例，在线抢登的动态账号和固定群创建均失败，离线拒绝用例通过。
  - GREEN：恢复状态集合条件后，动态账号、固定群和离线拒绝共 `3/3` 通过；测试连接本机真实 MySQL，事务回滚。
  - 本地库存在与本次无关的 Flyway `V055/V056` 校验和漂移，且缺少后续表/列；为避免修改本地库，定向验证关闭 Flyway，并仅在验证期间 mock 了无关的启动恢复组件，相关临时代码未提交。
- 最终聚焦回归（Java 17、沙箱外运行以允许 Mockito JVM attach）：
  - `mvn -Dtest=MarketingAccountTreeRealtimeServiceTest,MarketingTaskMapperSqlShapeTest,MarketingTaskServiceImplLifecycleTest test`
  - `28/28` 通过，`BUILD SUCCESS`。
- 全量 `mvn test`：
  - 未通过环境门禁；未加载 `.env` 时，现有 Spring 集成测试使用无密码 `root@localhost` 连接 MySQL，被拒绝。
  - 加载项目 `.env` 的标准 DbTest 又被现有 Flyway `V055/V056` 校验和不一致阻断。未执行 `repair`、迁移或其他数据库修改。
- 静态检查：`git diff --check` 无输出；无临时 Mock、Flyway 绕过代码或 XML 回退残留。

## 部署

- 来源：`1.0.1-snapshot` 当前未提交工作区，基线 commit `d46a307`。
- 范围：仅 Armada 后端，未部署前端、Baileys 协议层或 Zhuan 协议。
- 环境：第二套性能环境，Compose project `armada-deploy`。
- 命令：`armada-deploy/deploy-test.sh --be -y`，退出码 `0`。
- 制品：本地与远端 jar SHA-256 一致；旧 jar 已在远端部署目录备份。
- 部署后验证：
  - `armada-backend` 为 `running`，重启次数 `0`。
  - `armada-nginx` 启动时间未变化，确认本次未重建前端容器。
  - Flyway 报告 `armada_perf` schema 已是最新，无迁移执行。
  - Kafka 三个 perf 事件 consumer 均成功订阅并分配分区。
  - 目标机 API 代理请求返回 HTTP `200`；启动日志未发现启动失败、Flyway 校验错误、连接拒绝或内存溢出。

## 遗留 / 跟进

- 标准全量测试需先由项目维护者处理本地测试库 Flyway 校验和漂移，或提供干净的测试库后重跑。
