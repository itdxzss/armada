# 拉群任务创建取消版本参数设计

## 背景

普通群链接任务的创建页没有面向用户的“保存草稿”能力。当前后端仍用一条
`pull_task.status = DRAFT` 的内部任务记录承载预检生成的执行计划，并把该记录的
`version` 暴露给前端，要求最终创建请求原样回传。第一套测试环境出现
“缺少草稿任务版本号”，说明这个内部乐观锁字段已经成为不必要且脆弱的前后端合同。

## 目标

- 前端草稿响应类型和创建请求不再包含 `version`。
- 后端草稿响应和创建 DTO 不再暴露或校验 `version`。
- `pull_task.version` 数据库字段保留，正式任务生命周期继续使用乐观锁。
- 创建事务仍使用后端刚读取到的任务版本执行 `DRAFT -> WAIT_START` 条件更新。
- 重复提交继续返回既有任务，不创建第二个任务。

## 方案比较

### 方案一：只删除前端校验

后端仍要求 `version`，请求继续失败，不能解决问题。

### 方案二：创建接口只用状态守卫

从创建 DTO 删除 `version`，Mapper 仅用 `status = DRAFT` 约束提交。合同最简单，但放弃了
读取任务到提交任务之间的内部并发版本校验。

### 方案三：删除对外参数，保留内部乐观锁（采用）

从前后端 HTTP 合同删除 `version`。创建事务读取当前草稿后，使用
`task.getVersion()` 调用现有 `submitDraft`。这样用户和前端无需感知版本号，数据库仍能检测
事务读取后发生的并发状态变化，也不影响其他生命周期操作。

## API 与数据流

1. `GET /api/pull-tasks/standard/draft`、预检、移除执行行和清空接口返回的
   `PullTaskStandardDraftVO` 删除 `version`。
2. `POST /api/pull-tasks/standard` 的 `PullTaskStandardCreateDTO` 删除 `version`。
3. 前端 `PullTaskStandardDraft`、`PullTaskStandardCreateRequest`、空草稿和创建 Payload 同步删除
   `version`，创建前只校验 `draftTaskId` 和至少一条执行行。
4. 后端按 `draftTaskId + 当前用户 + 当前租户` 读取任务，在事务内把读到的内部版本传给
   `PullTaskMapper.submitDraft`。
5. 数据库列、Mapper 的 `expectedVersion` 参数及正式任务生命周期接口保持不变。

## 错误与并发语义

- 草稿不存在或不属于当前用户：保持现有错误。
- 草稿没有执行行：保持现有错误。
- 已进入 `WAIT_START` 或 `EXECUTING` 的重复创建请求：返回既有任务。
- 后端读取后任务状态或版本并发变化：保持现有冲突错误，事务整体回滚。
- 不新增兼容字段或双合同；旧的 `version` JSON 字段按现有未知字段拒绝规则处理。

## 测试

- 前端 API 合同测试断言创建 Payload 不含 `version`。
- 前端 composable 测试断言无版本字段仍能提交有效执行计划。
- 后端 DTO JSON 合同测试断言序列化和反序列化均不含 `version`。
- 后端 H2 创建集成测试断言服务端读取内部版本并完成 `DRAFT -> WAIT_START`，版本仍从 1
  增加到 2。
- 保留重复提交、整单回滚、租户隔离和正式生命周期乐观锁测试。

## 影响与回滚

- 不改数据库，不需要 Flyway。
- 前后端必须同时发布，避免合同窗口不一致。
- 回滚时前后端同时恢复 `version` 合同；数据库无需回滚。
