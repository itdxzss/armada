# 进群待审核自动处理

- 日期：2026-09-18。
- 用户授权：分析之后明确“开始调整吧，在主仓库做”。
- 工作位置：Armada、wheel-saas-pure-web、Android 协议各自主工作区，保留已有在途修改。Web 协议复用现有 HTTP 接口，无源码改动。
- 状态：实现和离线定向验证完成；未提交、推送、部署或执行真实群操作。

## 行为

普通进群保持原路径。仅 `PENDING_APPROVAL` 将当前明细置为 `PENDING + APPROVAL`，同事务登记恢复子记录；不计失败、不重复普通进群、不提前推进同账号下一条。

恢复步骤：解析群身份 → 按原有租户/用户归属规则选择本群在线管理员 → 关闭审核 → 确认目标成员/申请 → 必要时只批准当前账号，或在明确无成员/申请时执行一次续进群 → 确认成员存在 → 原进群成功状态机 → 按已有设置进入提权及清理。

- 关闭成功不能当作入群成功。提权、群关系、延迟营销仍在确认入群之后，由已有代码执行。
- 保存阶段、操作身份、版本和截止时间。整体上限 5 分钟；已知权限/群异常立即失败，未知结果如实标注。没有新增运营配置项。
- 关闭请求中断/超时停止后续；批准/续进群在重启或结果未知后只读核实，不重复发送。
- 原管理员身份与 `adminActorAccountId` 分开，避免改变后续原号退出对象。新任务无论是否开启提权都记录可信归属用户；历史归属缺失明确失败，不扩大账号权限。
- 仅能返回 LID 且无法与目标关联时不猜号码、不重复进群，限时核实后明确结束。
- 前端显示“正在关闭审核 / 正在处理进群申请 / 正在确认进群结果”等状态及明确原因。

## 影响范围

- 后端：JoinTaskResultServiceImpl、恢复 Facts / Protocol / Transactions / Scheduler、Mapper、明细 VO、可信任务归属、双协议 GroupApprovalPort 适配。
- 前端：进群详情处理状态、处理说明、接口字段和阶段测试。
- Android：`/ws/v1/groups/preview/:key` 调用原有 w:g2 invite GET，只在待审后解析群身份；原生待审列表显式区分缺失与空列表。Swagger 契约同步。
- Web：复用现有 preview、pending、pending/approve、settings/join-approval；无新增 Kafka source、topic 或队列。
- Redis：无变更。

## 数据库与 API

- Flyway `V206__join_task_pending_approval.sql` 增加 `join_task_approval`，属于进群明细的恢复子记录。只在待审发生时创建。
- 该表不是新的群事实表；不复制群设置快照。原表已包含约 30 个字段，且 `admin_*` 专门表示入群后提权，不能挪用，因此恢复执行状态独立存储。
- 保持进群结果 `status=PENDING/SUCCESS/FAILED` 契约，增加 `dispatch_state=APPROVAL`；详情新增 `approvalStatus/approvalReason/approvalActorAccountId`。
- 原 `PENDING` 计数与账号串行机制覆盖新阶段，不另造并行计数。
- 数据模型文档通过正式生成器和 V206 离线元数据更新，不表示远程库已迁移。

## 验证

- 先红：修改待审核回归测试后运行，旧实现失败，明确复现“待审核直接 markTerminalFailure”。
- 后端：JDK 17、显式 Byte Buddy agent，使用 `/tmp/join-approval-pom.xml` 构建至 `/tmp/join-approval-java-build`，不覆盖共享 target。24 个测试类累计 **161 项，0 失败/错误/跳过**。
- 范围包括新恢复状态机、真实 Mapper + H2 + 生产租户插件、同事务回滚、双线程抢占、重复/迟到回执、超时、普通进群无额外操作、管理员身份、单目标申请、成员 PN/LID、现有提权/清理/排期与消费者、双协议 HTTP 请求及业务信封。
- Mapper XML 经 xmllint 检查。
- 前端：15 项进群任务测试通过；tsc、vue-tsc、相关 ESLint/Prettier 通过；Vite 构建输出 `/tmp/join-approval-frontend-dist` 成功。
- Android：相关 api/service、api/controller、api/router、IQ processor/nodes、doc 测试通过；go build ./...、go vet ./... 通过。协调器测试需要本地 miniredis 监听端口，沙箱首次阻止后通过审批在本地执行成功。
- 扩大 Java 回归时曾用通配符误选真库测试，在 Hikari 建连阶段停止并终止进程；未见连接建立或迁移完成证据。最终验证改用明确的离线类名单，并在临时 POM 排除 DbTest/MySqlTest；不将这次中止当作真库验收。
- 日志：`/tmp/join-approval-red.log`、`/tmp/join-approval-final-java.log`、`/tmp/join-approval-boundaries.log`、`/tmp/join-approval-go-final.log`、`/tmp/join-approval-coordinator-test.log`、`/tmp/join-approval-frontend-build.log`。

## 发布与回滚

- 当前只是主工作区修改，不等于已提交或已部署；未操作第二套环境。
- 发布时先包含 Android preview 接口及完整/缺失待审列表契约，再发布后端（V206）与前端。现有提权/清理也是在途基线，必须一起确认实际部署内容。
- 旧版不认识 APPROVAL，回滚前需排空或由新版正常终结恢复记录。保留表和历史原因，不自动重放历史失败任务。
- `rollback.sql` 仅提供在途状态只读检查；代码回滚不会重新打开真实群审核开关。
- 尚未完成真实 WhatsApp 的 Web/Android/混合协议验收；尤其关闭审核后已有申请的服务端行为仍需指定环境验证。
