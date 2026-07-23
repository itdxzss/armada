# 变更记录：账号分组拆分与合并

- 日期 / 分支 / worktree: 2026-07-22 / dev_hhw / rich
- 需求来源: 用户本次需求
- 状态: 已完成

## 目标（一句话）

支持账号分组按账号数平均拆分，以及按用户勾选顺序合并，并保证事务内账号不丢失、不重复。

## 缺口拆解 / 任务清单
- [x] 后端拆分、合并事务接口与校验
- [x] Mapper 账号稳定排序迁移及分组软删
- [x] 前端操作入口、勾选顺序、二次确认与刷新
- [x] 前后端编译、类型检查与人工验收

## 关键设计决策
- 不变更表结构，继续以 `account.account_group_id` 作为唯一归属事实。
- 系统默认分组沿用现有不可选择规则，不参与拆分或合并。
- 拆分按账号 ID 升序轮转分配，余数依次进入前几个新组。
- 合并请求中的首个 ID 是主分组，后续分组迁移后软删除。

## 验证（evidence-before-done）

- `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home mvn -q -DskipTests compile`: 通过。
- `pnpm typecheck`: 通过。
- 人工验收:10 个账号拆 5 组得到 2/2/2/2/2，拆 3 组得到 4/3/3；5 组合并后主组保留 10 个账号。
- `AccountGroupServiceImplTest`: 本机 Mockito/Byte Buddy 无法 attach，25 个测试均在初始化 MockMaker 时阻塞，未进入业务断言。

## 部署
- 未部署。

## 遗留 / 跟进
- 无。
