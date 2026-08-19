# 群组检测后延迟发送

## 变更概述

- 普通营销 `ACCOUNT_DYNAMIC` 新群第 0 轮可按任务配置延迟发送。
- 延迟等待复用 `marketing_task_send_attempt`，到期后继续复用现有消息组装、Outbox、协议发送和结果回写。
- 普通轮次排除仍在等待的新群；任务关闭或结束时把等待记录收口为跳过。
- `group.participant_changed(action=add)` 在受控账号关系真正转为在群时，作为延迟开启任务的实时 WAITING 兜底；先判定状态跃迁，再复用原成员增量落库，原账号群全量快照发送入口保持不变。
- 进群任务首次收到有效的 `group.join_result_reported(outcome=JOINED)` 时，同样复用延迟登记入口，覆盖协议未立即上报账号全量群快照或成员增量的情况；`ALREADY_JOINED`、失败、迟到及重复结果不新增 WAITING。
- 前端只在现有任务表单中增加延迟配置，不展示检测时间和计划发送时间。

## 影响模块

- `marketing_task`：新增延迟开关、数值和单位。
- `marketing_task_send_attempt`：新增 `WAITING` 状态、检测时间、计划发送时间和 Outbox 接受时间。
- 新群首次发送服务：即时发送与到期发送共享正式提交逻辑。
- 普通营销轮次：动态群解析时排除等待记录。
- 生命周期：关闭、自动结束时跳过未提交等待记录。
- 前端任务创建表单：新增配置区；不新增任务编辑入口。

## 数据库变更

- Flyway `V130__marketing_new_group_delay_send.sql`。
- 不新增业务表，不修改 Outbox 表。
- 前滚和回滚说明分别见 `db-migrations.sql`、`rollback.sql`。

## API 变更

- `POST /api/marketing-tasks` 增加 `newGroupDelayEnabled/newGroupDelayValue/newGroupDelayUnit`。
- 现有列表和详情返回延迟配置；不返回检测时间和计划发送时间。

## Redis 变更

- 无。

## 关键约束

- 仅普通营销 `ACCOUNT_DYNAMIC` 的 `round_no=0` 使用业务延迟。
- 延迟开启时，暂停期间新群仍登记 WAITING，但不进入 Outbox；计划时间仍按检测时间计算，恢复后由既有到期 Worker 尽快处理。
- 延迟关闭时，暂停期间不创建第 0 轮记录，避免走即时发送。
- 实时成员增量只调用“仅延迟登记”入口：延迟关闭时不锁营销任务、不创建 attempt、不写 Outbox；remove、角色变化、旧事件和重复 add 均不触发营销登记。
- 实时事件按被新增的受控账号识别目标，不使用观察该事件的协议账号；后续全量快照与实时事件并发时继续由第 0 轮唯一键幂等去重。
- 进群任务成功结果、实时成员事实和账号全量群快照差集是同一 WAITING 服务的三个事实入口；不为进群结果额外触发大群快照，避免增加协议查询和 Kafka 负载。
- 实时增量创建的 WAITING 不伪造 `group_link_id`，按账号和群 JID 在到期时校验当前群关系；原全量快照链路仍按既有 `group_link_id` 校验，两条入口最终复用同一到期提交、Outbox 和协议结果链路。
- 到期重新校验任务、账号占用、账号状态和群关系，不满足时跳过且不写 Outbox。
- 到期读取任务当前关联的最新营销素材。
- 到期事务先锁任务、再锁 WAITING，使暂停/关闭/结束与提交串行化。
- 普通轮次被 Outbox 接受后保留不可变时间；后续协议失败也不会再次触发第 0 轮。
- V130 从既有 Outbox 按租户和 commandId 回填迁移前可验证的普通轮次接受事实。
- 同批新群计划时间一致，正式提交后继续使用现有账号群间隔错峰。
- 检测时间和计划发送时间只落库，不在页面展示。
- 群明细展示 WAITING 为“等待发送”并置顶；WAITING 不计成功、失败或跳过。

## 验证

- 已完成：后端聚焦测试 64 个（迁移、Mapper/H2、Service、普通轮次、调度和生命周期）、前端组件静态测试和 Vue 类型检查。
- 现有 composable Node 单测入口受仓库基线的 CSS/import.meta.env 加载问题阻断，本次静态组件测试及类型检查已通过。
- 2026-08-19 追加回归：暂停期间登记 WAITING、WAITING 明细置顶、PN JID 后缀兼容等 80 个后端聚焦测试通过；前端 WAITING 标签映射 2 个定点测试通过。当前前端工作区缺少 `node_modules` 中的 TypeScript/Vue 工具，因此本轮未能重新执行完整 typecheck。
- 2026-08-19 实时增量兜底最终回归：57 个聚焦用例全部通过，覆盖原即时发送、延迟 WAITING、延迟关闭隔离、受控目标账号识别、成员增量与营销登记顺序、重复/旧 add 幂等、remove 不触发营销、实时记录按群 JID 到期校验以及 Mapper SQL 形态；`mvn -Dmaven.test.skip=true package` 构建通过。
- 2026-08-19 进群结果兜底回归：72 个相关用例全部通过，覆盖首次有效 `JOINED` 登记、`ALREADY_JOINED`/失败/重复/迟到隔离、非法群 JID 不落成功终态、消费边界校验，以及既有快照差集和实时成员入口；主代码打包通过。当前合并基线另有 `MarketingTaskWhatsAppMemberProviderTest` 构造器参数不匹配，执行时通过 Maven test exclude 隔离该范围外已知破损测试。
- 本轮未连接共享测试库执行真实 MySQL 查询；新增按群 JID 校验 SQL 已通过 MyBatis SQL 形态、租户拦截解析和 Service 回归。极端并发下旧 add 与更新的 remove 同时到达，可能短暂多登记一条 WAITING，但到期关系复核会将其跳过，不会进入 Outbox 或误发。

## 回滚方案

- 未部署时只回退本变更相关代码和 V130 文件，保留其它会话在途修改。
- 已部署时首选停止新版本入口和到期扫描后只回退应用，保留 V130 向后兼容结构；物理删除结构必须另建正式 Flyway 前滚迁移。

## 发布顺序

- 先执行 Flyway 并完成全部后端节点升级，再发布前端创建入口。
- 延迟配置默认关闭；滚动发布期间不得让旧后端节点处理已开启延迟的新任务。
- V130 历史回填是一次性 UPDATE JOIN；生产前必须在近似数据量验证执行计划和耗时，大表需安排维护窗口或拆成按 Attempt ID 的批次迁移。

## 当前状态

- 已实施，待联调。
