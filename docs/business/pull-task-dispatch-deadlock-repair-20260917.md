# 普通拉群名额竞争死锁的最小修复

更新：2026-09-18。主仓库 `armada` / `1.0.3-snapshot`。根据用户对复杂度的反馈，本次采用下面的最小实现，替代此前扩大到统一锁协议、公平排队和界面的方案。

## 已实现

仅修改生产文件 PullTaskMapper.java、mapper/task/PullTaskMapper.xml。

既有 acquireExecutionSlot 调用接口不变，首次启动和资源恢复仍在原有 Spring 事务内调用。内部拆为两步：

1. countAvailableExecutionSlot：普通 SELECT，在同一读取快照内检查父任务状态/版本、候选执行的状态/阶段/版本/租约及当前运行数。
2. advanceExecutionSlotVersion：只按父任务主键、租户、状态及刚才检查的版本执行条件 UPDATE，递增版本号。

不再在 UPDATE 中读取执行表，移除本次复现中与封群后继 INSERT 冲突的共享范围锁来源。没有新增显式锁、Redis 锁、表、列、配置或第三方依赖。

## 为什么不会使两个自动调度事务同时取得同一个名额

两个事务可能都在普通快照中看到空名额，但它们绑定同一个父任务版本。只有一个版本 UPDATE 能成功，另一事务在前者提交后按旧版本更新会返回 0。父版本递增与对应执行进入 EXECUTING 必须在同一事务提交，因此成功启动会同时使其他旧授权失效。

普通 SELECT 必须同时校验父版本和运行数，不能先检查旧运行数，再拿一个更新的父版本去更新。现有启动/恢复路径的候选状态、执行版本与租约条件更新继续保留，防止检查后的并发变化被覆盖。检查、父版本更新和执行状态更新不能拆成不同事务；未来增加自动启动入口也必须遵循该入口约束。

在 REPEATABLE-READ 下，旧快照可能仍看到名额，但只能携带旧父版本，后续条件 UPDATE 会拒绝；并发结束使旧快照多算运行行时，只会保守地延迟到下一轮。

## 保留现有行为与边界

竞争失败仍由现有调度器重试；事务异常继续走原有日志和退避，不把数据库错误转换为群失败、换群或料子取消。本次没有改公平排队、前端观察状态、租约领取、封群业务口径或已结束任务。

这是针对已复现 SQL 组合的修复，不等于整个拉群系统已经不存在其他死锁。排查还发现人工补充管理员有直接将 WAIT_RESOURCE 推进到 EXECUTING 的既有路径，未经过当前名额方法；该路径的整体并发策略不在本次两个自动调度入口的验证结论内，后续应单独对账，不据本次测试宣称所有入口都受同一并发门禁保护。

## 本地验证

新增 PullTaskExecutionSlotMapperTest，真实 H2 MySQL 模式 + 生产 Mapper XML / MyBatis-Plus 租户插件 / Spring 事务。

覆盖首次启动和资源恢复、上限 1/2、检查通过后被另一个事务抢先启动、持锁事务提交前的等待和旧版本拒绝、回滚后以原执行身份重试、租户隔离、父任务暂停及候选状态/租约失效。另用 SQL 形状约束禁止父任务 UPDATE 再嵌入执行表查询；H2 不具备与 InnoDB 相同的范围锁行为，因此不冒充 MySQL 死锁回归。

先红：9 个用例，1 个失败，失败点为旧 UPDATE 跨表查询。首次编译时测试断言出现泛型重载歧义，修正测试代码后取得此有效红灯。

最终聚焦测试：57 tests，0 failures，0 errors，0 skipped。

- PullTaskExecutionSlotMapperTest：10。
- PullTaskExecutionTransactionServiceTest：11。
- PullTaskResourceRecoveryTransactionIntegrationTest：26。
- PullTaskExecutionDispatchCoordinatorTest：10。

运行使用 JDK 17、显式 Byte Buddy agent；命令见变更记录。Mapper XML 校验及本次生产 diff 的空白检查通过。

## 待后续阶段验证

用户明确要求测试环境验证放后面：本次没有远程访问、部署、业务任务重跑或真实 MySQL 8.4 修复后验证。既有本机 MySQL 9.3 两次复现是修复前证据，不能算修复后通过。后续用明确提交的构建在第二套核对实际制品，并验证封群与启动并发、两实例自动启动上限、回滚后继续推进。

未提交、未推送。主仓库其他 agent 的在途修改保留；回滚本次只需恢复上述两个生产文件的本次差异，不修改数据。
