# 变更记录：营销任务当前群同步与发送时间边界

- 日期 / 分支 / worktree: 2026-07-16 / 1.0.1-snapshot / 主工作区
- 需求来源: `docs/superpowers/specs/2026-07-16-marketing-current-group-send-design.md`
- 状态: 已完成（未部署；真库验证受本地连接环境阻塞）

## 目标（一句话）

动态营销任务每轮向账号当前未软删、且符合账号群组发送时间边界的群生成发送尝试。

## 缺口拆解 / 任务清单

- [x] 当前群回报全量写入 membership
- [x] 动态目标 SQL 移除 baseline/账号状态/群健康等准入过滤，保留发送时间边界
- [x] Worker 继续传递 `accountGroupSendAt`
- [x] 后端保留默认开始前 72 小时和最多追溯 72 小时校验
- [x] 前端保留输入、校验、请求字段和详情展示
- [x] 完成本地可执行的后端与前端验证

## 关键设计决策

- 采用现有 membership 快照保存当前全量群；不在每轮同步调用协议层。
- baseline 保留作历史事实，但不再影响 membership 和营销目标。
- 不删历史数据库列、不新增 Flyway；`accountGroupSendAt` 继续参与创建和动态目标查询。
- 不部署、不操作远程或共享数据库。

## 验证（evidence-before-done）

- `mvn -Dtest=MarketingTaskMapperSqlShapeTest test`: 12/12 通过。
- `mvn -DskipTests package`: BUILD SUCCESS，生成 Spring Boot jar。
- `AccountGroupMembershipReportServiceImplTest`: TDD 红灯 2 个失败后，修改实现首次复跑 3/3 通过。
- `MarketingTaskMapperSqlShapeTest,MarketingRoundWorkerTest,MarketingAccountTreeRealtimeServiceTest`: 修改后首次复跑 35/35 通过。
- 后续重复 Mockito 套件被当前 macOS 的 Byte Buddy self-attach 权限阻断；JDK 17/23 均复现，属于测试运行环境错误，不计为通过。
- `MarketingTaskCreateReadDbTest` 与 Mapper 真库测试：本地 MySQL 连接持续重试，已中止，未执行成功。
- 前端目标 Node 测试：先有 5 个失败，实现后 27/27 通过。
- `./node_modules/.bin/tsc --noEmit && ./node_modules/.bin/vue-tsc --noEmit --skipLibCheck`: 通过（零输出）。
- `./node_modules/.bin/vite build`: 构建成功，2240 modules transformed。
- 两个仓库 `git diff --check`: 通过（零输出）。
- 独立代码评审未发现 Critical 生产逻辑问题；指出的旧 membership DbTest、动态 Mapper 边界用例和两处过期注释已修正。

## 部署

- commit / 环境 / 部署后验证结果: 未提交，未部署。

## 遗留 / 跟进

- 本地 DbTest 需在确认 `.env` 指向的测试数据库后重跑，不得对未确认的共享库执行。
