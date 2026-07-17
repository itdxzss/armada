# 历史群拉群营销 baseline 群名快照

## 变更概述

- 首次处理 `account.groups_reported` 轻量载荷时,在原有群 JID 历史范围旁保存 JID 到群名的静态映射。
- 后续回报和直接 capture 均不得覆盖首次 JID 数组或群名映射。

## 影响模块

- `armada-api` 群组域的账号群回报 Service、Mapper、MyBatis XML 与真库 DbTest。
- `account_group_baseline` 聚合增加一个可空 JSON 展示快照列。

## 数据库变更

- `account_group_baseline.baseline_group_subjects JSON NULL`。
- 该列属于账号登录前群基线聚合;现有 `baseline_group_jids` 只表达唯一历史范围,不能承载 JID 到展示名的映射。
- 没有新建并行关系表;群名映射不表达当前成员关系,不会与 `account_group_membership` 的当前事实重复。

## API 变更

- 无 HTTP API 变更。
- 兼容旧协议只带 JID、subject 缺失的载荷,稳定写入空 JSON 对象。

## Redis 变更

- 无。

## 关键约束

- 只使用首次回报轻量载荷已有 subject,不额外调用 metadata。
- JID 去空白、去重;subject 空白不落映射;重复 JID 保留首次非空 subject。
- 历史范围唯一事实仍为 `baseline_group_jids`;历史行 `baseline_group_subjects IS NULL` 可读取。

## 回滚方案

- 停止依赖静态群名展示后执行同目录 `rollback.sql`,删除新增列。
- 回滚不改 `baseline_group_jids`,不会改变营销历史群过滤范围。

## Task 8：历史群拉人执行持久化

- 新增 `historical_group_pull_execution`，保存独立一次性拉人/营销执行、创建幂等键、终态统计和原始错误详情。
- 新增 `historical_group_pull_member`，按输入行保存联系人预存、拉人和营销发送状态；命令 ID 与结果事件 ID 用于幂等回写。
- 失败执行只落终态，不在本执行域自动重试；需要再次执行时由上层以新幂等键明确创建。
- 两表都受 `tenant_id` 隔离；没有 HTTP API 或 Redis 变更。
- 回滚时先删除成员表，再删除执行表，不影响本文件前述 baseline 群名快照变更。

## Task 9：料子解析与幂等创建

- 创建接口支持 TXT、CSV、XLSX、XLS；复用统一文件提取和 WhatsApp 号码归一规则。
- 末尾 `A/a` 识别为营销账号，同号普通与营销冲突时营销优先；执行明细按营销在前、类型内首次源行排序。
- 创建前校验当前租户操作账号、拉手账号分组和 baseline，并通过实时详情重新取得非空邀请链接；不接受前端链接。
- `(tenant_id, idempotency_key)` 顺序重复或并发冲突都返回已有执行，不重复解析和插入成员。
- 新增 multipart `POST /api/historical-group-pull-executions`、按 ID 查询和最近执行查询；无数据库结构、Redis 变更。

## Task 10-13：执行、全部 A 发送与结果闭环

- `start` 在 fresh 链接门禁后原子认领，事务提交后进入有界线程池；拉手只踩固化邀请链接，不重试、不换号。
- 联系人保存失败仍继续 ADD；营销料子优先，`singleAddCount` 包含普通与营销成员；逐成员保存完整错误。
- 拉手暂只选择在线正常 Web 账号；Android 缺联系人/ADD 路由时在进群前明确失败，避免半执行。
- `marketing-send` 只校验邀请链接，不要求管理员或禁言预检；全部 Web A 账号各下发一次所选完整模板。
- Android worker 暂不支持历史 correlation，Android A 账号记录 `MARKETING_BACKEND_UNSUPPORTED` 后继续，避免执行永久停在发送中。
- 发送结果按租户、执行、成员、命令和群 JID 校验；锁定执行行串行聚合，首个结果冻结，重复事件不重试。
- 全部 A 成员进入成功/失败终态后聚合 `SUCCESS/PARTIAL_SUCCESS/FAILED`；服务重启把遗留处理中状态置为 `SERVICE_INTERRUPTED`，不续跑。
