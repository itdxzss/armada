# 变更记录：群管理员事件事实与拉群缺失兜底

- 日期 / 分支 / worktree：2026-08-14 / `1.0.3-snapshot` / `armada` 主工作树
- 需求来源：用户确认按“角色事件主链、任务结果不双写、本地缺失时异步点查”实施；设计见 `docs/superpowers/specs/2026-08-14-group-admin-event-and-pull-fallback-design.md`
- 状态：设计中

## 目标（一句话）

用 WhatsApp promote/demote 事件实时维护受控管理员事实，在普通拉群本地无管理员时异步查询一次当前成员角色后重新选号，并停止成功群的固定周期 metadata 轮询。

## 缺口拆解 / 任务清单

- [x] 核对 test1 任务 #122、群成员快照、账号群关系和管理员选号证据。
- [x] 核对协议 `group.participant_changed`、metadata 同步请求和后端未消费现状。
- [x] 核对现有异步 `group.members.query.requested/result_reported` 复用边界。
- [ ] 用户确认书面设计。
- [ ] 编写实施计划并按 TDD 实现协议事件载荷与 metadata 触发收窄。
- [ ] 按 TDD 删除 `SUCCEEDED` 群的周期候选，保留首次快照、事件、重试和手动刷新调度。
- [ ] 按 TDD 实现后端角色事实消费、成员状态与账号群关系对齐。
- [ ] 按 TDD 实现 `MANAGER_ADMIN_DISCOVERY` 异步兜底和历史等待行唤醒。
- [ ] 完成聚焦回归、构建、XML/Flyway 校验和 test1 验收准备。

## 关键设计决策

- `group.participant_changed` 是唯一实时管理员事实入口；任务动作回执不双写全局关系。
- 只取消 promote/demote 引发的完整 metadata 请求；add/remove 和 groups.update 保持原行为。
- 删除成功群默认 60 秒再次到期的后台查询；保留同步 Job 处理首次建档、事件、重试和手动刷新。
- 复用 `whatsapp_group_member_state` 和 `account_group_membership.is_admin`，不新增管理员镜像列。
- 拉群兜底复用现有异步成员查询框架，正常派发线程不等待网络。
- 不静态回填旧成员快照；Flyway 只唤醒符合条件的活动等待执行行。
- Android 没有同等角色事件时，由任务定点查询按业务需要补齐管理员事实，不依赖全群周期轮询。

## 验证（evidence-before-done）

待实现后补充命令与真实输出。

## 部署

- 未提交业务代码、未部署、未修改远程数据。

## 遗留 / 跟进

- 书面设计通过后进入实施计划与 TDD。
