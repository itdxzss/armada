# 拉群营销 V1.2 实施设计

## 1. 目标与范围

以 `拉群营销_PRD需求文档_V1.2_修订标红版_结构调整版.docx` 为需求事实，
以 `group-marketing-standalone.html` 为页面交互参考，在以下三个仓库形成可验收闭环：

- `armada`：任务、群组候选、资源占用、状态机、协议调度、审计与 Flyway。
- `wheel-saas-pure-web`：创建页、任务详情、群组明细和人工操作。
- `armada-protocol`：只补 Armada 已确认需要且现有 Adapter 缺失的协议原子能力。

本设计不把现有 `group-pull-marketing`（创建新群营销）计入本需求完成度；V1.2 的任务主键和统一列表
统一落在 `pull_task`，任务类型固定为 `GROUP_MARKETING`。

## 2. 已确认边界

- 可执行来源仅为历史老群和自收群；普通成员群组不可选择。
- 同一群 JID 全租户范围去重，同一时刻只能被一个拉群营销任务软占用或硬占用。
- 账号不按任务独占，协议操作受账号并发槽位限制。
- 全局营销静默、群组封控、单群营销账号上限在建任务时保存不可变快照。
- 目标号码成功入群或已经在群内后全业务唯一使用；结果未知继续占用且不可重新分配。
- 父任务状态、单群当前步骤、转移状态、营销状态分开存储和聚合。
- 暂停保留资源；永久停止等待已提交操作收口后释放未使用资源。

## 3. 数据归属与复用

### 3.1 直接复用

- `account_group_baseline`：识别历史老群范围。
- `join_task` / `join_task_result`：识别自收群、来源任务、进群和成为管理员时间。
- `group_link` / `group_link_preview` / `group_link_health`：群资料、JID、人数、权限和健康状态。
- `account_group_membership` / `account` / `account_state`：聚合同群全部可操作账号及实时资格。
- `pull_task` / `pull_task_group_marketing_summary` / `pull_task_group_marketing_setting`：统一任务列表和全局设置。
- `protocol_command_outbox`：异步协议命令、幂等和重试基础设施。

### 3.2 必须新增的聚合

- 等待池与群锁：一行表示一个群当前的软占用或硬占用，不在 `group_link` 上堆任务状态。
- 任务配置快照：保存结构化任务参数、模板快照和全局设置快照。
- 任务群组：保存单群独立步骤、转移状态、营销状态、检查点、时间和最终快照。
- 任务角色账号：保存管理员、拉手、水军、营销账号的分配、状态、并发占用和结果。
- 目标号码：保存清洗、预占、分配和全局唯一使用结果。
- 营销发送：保存业务消息、每次尝试和最终 SUCCESS/FAILED/RESULT_UNKNOWN。
- 异常与审计：异常事件聚合重试，关键人工/自动操作记录 requestId 和前后状态。

每个事实只保存在一个聚合中；一级列表统计由生产者从任务群组、号码和发送结果聚合写入
`pull_task_group_marketing_summary`，不由页面临时扫描全量明细计算。

## 4. API 分片

1. 候选与等待池
   - `GET /api/pull-tasks/group-marketing/candidate-groups`
   - `POST /api/pull-tasks/group-marketing/waiting-pool`
   - `POST /api/pull-tasks/group-marketing/waiting-pool/remove`
   - `DELETE /api/pull-tasks/group-marketing/waiting-pool`
   - `GET /api/pull-tasks/group-marketing/waiting-pool`
2. 任务创建
   - `POST /api/pull-tasks/group-marketing/drafts`
   - `PUT /api/pull-tasks/group-marketing/{id}`
   - `POST /api/pull-tasks/group-marketing/{id}/start`
3. 详情与生命周期
   - `GET /api/pull-tasks/group-marketing/{id}`
   - `GET /api/pull-tasks/group-marketing/{id}/groups`
   - `GET /api/pull-tasks/group-marketing/{id}/groups/{groupId}`
   - `POST /api/pull-tasks/group-marketing/{id}/pause|resume|stop`
4. 单群人工操作
   - `POST .../supplement-pullers`
   - `POST .../force-marketing`
   - `POST .../pause|resume|release`

写接口必须重复做租户、状态和占用校验；页面隐藏按钮不是权限和并发保证。

## 5. 执行状态机

单群顺序检查点为：实时复核 → 可选清理 → 获取/验证邀请链接 → 新管理员进群 → 晋升并复核 →
群资料与权限 → 拉手进群 → 水军/目标号码分批添加 → 营销账号进群并复核 → 最低标准 →
原管理账号退出 → 转移结果 → 营销静默 → 营销发送 → 群组封控 → 最终快照与释放。

每个不可逆协议动作使用稳定 `requestId`，提交前写 outbox，结果回写只允许命中当前尝试。服务重启后读取数据库检查点，
并在重试前重新读取群真实状态，禁止重复执行已成功动作。强制营销只提前营销分支，不终止拉人分支。

## 6. 增量交付清单

1. 候选群组去重聚合、可选原因、等待池软占用。
2. 真实资源选项、目标 TXT 清洗预览、模板与账号分组接口。
3. 草稿创建、完整配置和模板/全局参数快照。
4. 启动前二次校验、群硬锁、目标号码预占和资源计划。
5. 单群接管状态机与管理员交接。
6. 拉手、水军、目标号码分批执行及唯一使用。
7. 营销账号首次资格、静默、发送重试、未知结果和封控。
8. 暂停、恢复、永久停止、补充拉手、强制营销和资源释放。
9. 任务详情、群组明细、四个单群页签、统计生产者和审计。
10. 全量测试、跨仓联调、测试环境迁移与验收。

每一项必须满足：失败测试先行、生产实现、前端接入、针对性测试、change 记录更新；未达到不得标记完成。

## 7. 未确认项处理

以下项目保留为显式配置或功能关闭，不在代码中猜默认值：

- 清理失败容忍人数/比例和白名单。
- 默认营销开始方式。
- 是否强制重置邀请链接及失败后是否阻止原管理账号退出。
- 任务名称是否租户内唯一。
- 自收群历史来源信息缺失时是否允许补录。
- 单账号和单任务默认最大并发数。

已确认功能可以继续实施；涉及上述默认值的启动路径在口径确认前必须返回明确校验提示。

## 8. 验证与发布

- Mapper、租户隔离、锁和事务使用 H2 MySQL 模式执行真实 XML；MySQL 方言补结构测试。
- Service 状态机覆盖正常、部分失败、重复回调、暂停恢复、停止收口和重启恢复。
- 前端覆盖 API 契约、空态、错误态、分页选择保持和二次确认。
- 协议层使用契约测试验证请求/回调映射，不连接真实账号作为本地完成门禁。
- 未经目标环境确认不执行真实数据库迁移、远程协议操作或部署。
