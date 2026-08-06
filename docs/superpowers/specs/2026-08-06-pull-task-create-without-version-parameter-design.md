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
- 创建事务仅使用 `draftTaskId + status = DRAFT` 执行 `DRAFT -> WAIT_START` 条件更新。
- 重复提交继续返回既有任务，不创建第二个任务。

## 方案比较

### 方案一：只删除前端校验

后端仍要求 `version`，请求继续失败，不能解决问题。

### 方案二：删除对外参数，后端读取内部版本

从前后端 HTTP 合同删除 `version`，后端读取草稿后把 `task.getVersion()` 传给 Mapper。
这仍在创建路径保留了一个草稿内容变更不会维护的版本条件，增加复杂度但没有形成完整的草稿并发控制。

### 方案三：创建接口只用状态守卫（采用）

从前后端 HTTP 合同删除 `version`，创建 Mapper 也删除 `expectedVersion` 参数和版本条件。
`status = DRAFT` 已保证只有一次创建状态转换能够成功；失败事务整体回滚，不会生成第二个任务。
创建更新仍把数据库 `version` 加一，使正式任务进入后续生命周期时拥有新的内部版本，但创建请求不读取、
传递或校验它。

## API 与数据流

1. `GET /api/pull-tasks/standard/draft`、预检、移除执行行和清空接口返回的
   `PullTaskStandardDraftVO` 删除 `version`。
2. `POST /api/pull-tasks/standard` 的 `PullTaskStandardCreateDTO` 删除 `version`。
3. 前端 `PullTaskStandardDraft`、`PullTaskStandardCreateRequest`、空草稿和创建 Payload 同步删除
   `version`，创建前只校验 `draftTaskId` 和至少一条执行行。
4. 后端按 `draftTaskId + 当前用户 + 当前租户` 读取任务，`PullTaskMapper.submitDraft` 只用
   `id + taskType + status = DRAFT + deletedAt IS NULL` 作为条件，更新成功后把数据库版本加一。
5. 数据库列和正式任务生命周期接口的乐观锁保持不变；只有创建 Mapper 删除
   `expectedVersion` 参数。

## 错误与并发语义

- 草稿不存在或不属于当前用户：保持现有错误。
- 草稿没有执行行：保持现有错误。
- 已进入 `WAIT_START` 或 `EXECUTING` 的重复创建请求：返回既有任务。
- 后端读取后任务状态并发变化：创建更新返回 0，保持现有冲突错误，事务整体回滚。
- 不新增兼容字段或双合同；旧的 `version` JSON 字段按现有未知字段拒绝规则处理。

## 测试

- 前端 API 合同测试断言创建 Payload 不含 `version`。
- 前端 composable 测试断言无版本字段仍能提交有效执行计划。
- 后端 DTO JSON 合同测试断言序列化和反序列化均不含 `version`。
- 后端 H2 Mapper 测试断言创建提交不接收期望版本，并且状态非 `DRAFT` 时更新返回 0。
- 后端 H2 创建集成测试断言 `DRAFT -> WAIT_START` 成功，数据库版本仍从 1 增加到 2。
- 保留重复提交、整单回滚、租户隔离和正式生命周期乐观锁测试。

## 影响与回滚

- 不改数据库，不需要 Flyway。
- 前后端必须同时发布，避免合同窗口不一致。
- 回滚时前后端同时恢复 `version` 合同；数据库无需回滚。
