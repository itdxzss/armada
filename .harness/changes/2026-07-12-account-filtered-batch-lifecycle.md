# 变更记录：账号按已生效筛选条件批量登录与离线

- 日期 / 分支 / worktree: 2026-07-12 / `1.0.1-snapshot` / 当前工作区直接修改
- 需求来源: Armada 账号列表批量登录、批量离线需求
- 状态: 已完成

## 目标（一句话）

保留选中账号 ID 接口，并新增以后端查询条件为准的全匹配账号批量登录、离线、预估和安全分片编排。

## 缺口拆解 / 任务清单

- [x] 增加显式 `IDS` / `QUERY` 操作范围与 `ONLINE` / `OFFLINE` 类型。
- [x] 增加不含分页字段的批量筛选 DTO，并复用账号列表 SQL 筛选片段。
- [x] 增加后端预估接口，返回匹配、可执行、跳过及互斥跳过原因数量。
- [x] 保留原 ID URL，单次最多接收 2,000 个 ID。
- [x] 增加按查询条件执行的批量登录、批量离线接口，使用稳定 ID 游标扫描全部匹配数据。
- [x] 登录跳过封禁、解绑、抢登中、缺凭据账号；在线账号不在 Armada 过滤。
- [x] 登录按 500、离线按 1,000 个账号拆分，单批失败后继续并汇总有限错误摘要。
- [x] 补齐 DTO、Service、Controller 与真库 Mapper 测试。

## 关键设计决策

- 已勾选账号继续调用 `/batch-online`、`/batch-offline` 的 ID 分支；未勾选账号调用独立 query 接口，避免改变现有调用方语义。
- 外部 ID 容量与内部安全批次分离：接口允许 2,000，登录仍保持代理分配事务每批 500，离线每批 1,000。
- 查询批量不接收分页参数，也不返回无界单账号明细；目标由后端按当前租户、共享筛选条件和 ID 游标扫描。
- 预估是确认前数据库快照，执行阶段重新查询并返回实际汇总，不尝试跨用户确认过程锁定全部账号。
- 登录跳过原因按封禁、解绑、抢登中、缺凭据互斥归类；登录态不参与分类，在线账号仍进入协议命令链路。
- 失败隔离在内部批次级别，最多返回 20 条、每条最多 200 字符的错误摘要，日志不输出凭据正文。
- outbox 写入是上线命令受理成功边界；其后的待上线列表状态更新采用 best-effort，失败记录 error 日志但不把已入队命令改判为失败。

## 验证（evidence-before-done）

- `mvn -q -Dtest=AccountBatchQueryDTOTest,AccountBatchLifecycleServiceImplTest,AccountControllerTest test`：通过。
- `armada-api/dbtest.sh AccountBatchTargetMapperDbTest`：连接本地 Armada 测试库通过，Flyway 50 个迁移校验通过。
- `armada-api/dbtest.sh AccountListMapperDbTest`：原账号列表真库回归通过。
- `mvn -q -DskipTests compile`：通过。
- `xmllint --noout armada-api/src/main/resources/mapper/account/AccountMapper.xml`：通过。
- `git diff --check`：通过。
- `mvn -q -Dtest=AccountOnlineCommandServiceImplTest,AccountBatchLifecycleServiceImplTest test`：通过，覆盖待上线状态更新失败后仍返回 accepted。

## 部署

- commit / 环境 / 部署后验证结果: 未提交、未部署；按要求保留在本地 `1.0.1-snapshot` 供 IDEA 检查。

## 遗留 / 跟进

- 需要在前后端联调环境人工核对大批量操作期间的代理容量与协议 outbox 监控。
