# 拉群执行只读观察信息

## 目标与范围

在主仓库 1.0.3-snapshot 增强现有群列表和单群明细。保留任务状态、阶段、异常原因与最近业务执行时间，在独立 observation 字段补充运行说明、当前波次/批次、可证明的等待起点、下一次可调度时间和下一步说明。

## 设计

- 复用 group_execution、pull_wave、pull_call、account_action、material_member 事实；当前页批量读取，不逐群查询，不改表、不新增 Redis 数据。
- 观察数据仅经现有只读 Service 返回；不改变调度、回调、重试或资源补充规则。
- 区分父任务暂停/结束、资源等待、批次数据不一致、等待结果、正常派发间隔、结果收口和待调度。
- 等待反馈只能说明后台尚无结果，不能归因为 WhatsApp 响应慢。后台异常只在存在具体数据矛盾时展示；历史日志异常次数本期没有结构化事实，不推算。
- 资源等待/暂停缺少可靠进入时间时 waitStartedAt 留空；不得使用不断变化的 updated_at 假装等待起点。
- 前端保留原业务标签，补充说明；明确观察快照时间，刷新失败保留旧数据并标注过期。

## 验证计划

- H2 执行真实 Mapper XML，验证租户隔离、活动波次/批次选择和只读性。
- 运行说明覆盖暂停/终态优先级、缺资源、正常间隔、无反馈、数据矛盾与无时间证据。
- 前端展示/刷新边界测试、typecheck、针对变更文件 lint、生产构建。

## 进度

- [x] 核对主仓库和现有事实，保留其他任务在途改动。
- [x] 后台实现及验证。
- [x] 页面实现及验证。

## API 与实现

现有 executions 分页行和 execution 明细摘要新增 `observation`：state、label、detail、nextStep、observedAt、waitStartedAt、nextCheckAt、nextDispatchAt、waveNo、callSeq、plannedCallCount。原 executionStatus、stage、reasonCode/reasonMessage、lastBusinessExecutedAt 保持原含义。没有新写接口、业务状态、表或迁移。

`PullTaskStandardReadMapper.selectExecutionObservations` 仅查询当前页 ID，关联活动波次和最早未完成调用（优先已提交调用），读取尚待结果的账号动作；不把非阻断的群资料设置/关闭审批动作误认为当前等待。租户插件和显式租户关联同时生效。

`PullTaskExecutionObservation` 无写入依赖。只在尚无命令/提交证据的计划批次上比较计划人数与绑定人数。父任务和群暂停/终态优先，权限阻塞不建议盲目补号，已有命令证据不再显示未提交。

## 验证结果

- Microsoft JDK 17.0.19，显式 Byte Buddy javaagent；4 个聚焦类共 38 项通过：解释规则 10、真实 Mapper/H2 9、读取 Service 4、Controller 15，0 失败/异常/跳过。
- 首轮默认 JDK 23 的 Mockito 自挂载受限制；改用项目既有 JDK 17 + javaagent 后通过，未改变依赖。
- XML 校验通过；API 文档生成回归 1 项通过。
- 前端 28 项测试通过；TypeScript/Vue 类型检查、修改文件 ESLint/Stylelint/Prettier、生产构建通过。
- 隔离本地 Chrome 加载真实 Drawer 和子组件、仅使用临时测试数据，验证原阶段、新批次/等待说明、自动刷新及停用、请求处理中不叠加刷新、失败后保留数据和过期提示。截图 `/private/tmp/pull-observation-ui/groups.png`，测试日志 `/private/tmp/pull-observation-ui/verification.log`。未连接真实后端或协议。

## 验证边界

未部署、未查询真库或真实 WhatsApp；没有对线上查询延迟作性能承诺。H2 不替代 MySQL 运行时执行计划。本期不补写历史阶段开始时间，也不从日志推算历史异常次数；缺少起点时明确显示未记录。完整事件时间线/协议分段耗时仍不在本次四列增强范围。

## 发布与回滚

用户已授权提交、推送并部署第一套环境 test1；发布结果另行记录。回滚仅撤销新增只读字段、查询及展示，不回写业务数据。
