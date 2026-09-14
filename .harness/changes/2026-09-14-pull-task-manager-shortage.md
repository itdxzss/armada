# 拉手替换后误报管理员不足

## 范围与现场

- 用户要求修复已定位的缺陷；本次仅本地代码与验证，未提交、推送、部署或修改远端任务。
- 第一套环境任务 #211「任务_61464707」，执行行 #532 / #534：被替换拉手仍有待执行联系人动作；目标缺失导致发起管理员被误标 OFFLINE。管理员被资源恢复流程恢复后，等待父任务并发名额仍保留旧缺口原因。
- 前轮只读核对相关线上类 SHA256 与本地一致；本次不再操作远端。

## 修复

- 联系人待执行动作区分发起账号不可用与目标角色退出：只有前者可以将发起账号标记不可用。目标退出只收口旧动作，记录 CONTACT_TARGET_UNAVAILABLE。
- 保留已提交动作与结果，不重发、不覆盖在途结果。新拉手的联系人方向继续调度。
- 资源复核通过但尚未取得父任务执行名额时，写入 EXECUTION_SLOT_UNAVAILABLE；保留 wait_resource_type 供后续重新复核。资源再次失效则恢复真实缺口原因，获得名额则按原检查点推进。
- 运行观察展示 WAIT_CONCURRENCY；前端展示“等待并发名额”并隐藏补充入口。
- 单群列表增加可选 reasonCode 精确筛选；按资源不足筛选时排除并发等待行，count 和分页使用同一 SQL 条件。

## 数据与接口

- 无数据库结构、Flyway 或 Redis 变更。使用现有 reason_code / reason_message 列。
- GET /api/pull-tasks/standard/{taskId}/executions 增加可选 reasonCode 查询参数。
- 前端联动在 wheel-saas-pure-web；应配套发布以保证状态标签与入口一致。
- 人工暂停、并发上限、真实缺口校验和补充管理员容量限制保持原约束。

## 验证

- JDK 17 + 本机 Byte Buddy 1.14.19 agent；无需真实数据库。
- 修复前新增/增强回归测试复现 4 处失败：管理员被误标离线、并发等待沿用旧原因、运行观察提示资源不足、资源不足筛选包含并发等待；0 测试执行错误。
- 修复后 63 项通过，0 失败 / 0 错误 / 0 跳过：PullTaskManagerPullerContactTransactionIntegrationTest、PullTaskResourceRecoveryTransactionIntegrationTest、PullTaskExecutionObservationTest、PullTaskStandardReadMapperInMemoryTest、PullTaskStandardReadServiceTest、PullTaskManagerSupplementServiceTest、PullTaskManagerPullerContactProcessorTest。
- H2 使用真实 Mapper XML、租户插件及 Spring 事务；覆盖替换目标、保留在途动作、真正失效发起人、并发等待后资源再失效及名额释放恢复、分页筛选。
- XML 校验及 git diff --check 通过。前端 148 项相关测试、typecheck、构建及本次文件 lint/格式检查通过。
- 原始日志：/private/tmp/manager-fix-red-jdk17.log、/private/tmp/manager-fix-green.log、/private/tmp/manager-fix-frontend-registered.log。

## 发布后验收与回滚

- 尚未部署或完成真实任务验收。授权发布后需验证 #532 / #534 的新调度结果、页面状态和管理员可用性。暂停任务必须在用户恢复后才推进；本地修复不会自行恢复任务。
- 旧行会在下次资源复核时更新原因；不需手工改库。仅确认群可继续执行后开展真实业务验收，不能以本地测试代替群入群/拉人结果。
- 回滚仅回退本次 task 域与前端对应改动并重新部署，不回退其他会话的 group 域或部署配置改动。
