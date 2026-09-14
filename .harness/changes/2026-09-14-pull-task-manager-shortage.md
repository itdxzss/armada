# 拉手替换后误报管理员不足

## 范围与现场

- 用户要求修复已定位的缺陷，随后明确授权部署第一套环境；修复在前后端主仓库 `1.0.3-snapshot` 分支提交，已配套发布 test1。未推送远端 Git，未修改或恢复远端任务。
- 第一套环境任务 #211「任务_61464707」，执行行 #532 / #534：被替换拉手仍有待执行联系人动作；目标缺失导致发起管理员被误标 OFFLINE。管理员被资源恢复流程恢复后，等待父任务并发名额仍保留旧缺口原因。
- 定位时只读核对相关线上类 SHA256 与当时本地版本一致；发布后的制品证据见下文。

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

## 第一套环境发布（2026-09-14 15:30 CST）

- 用户授权范围：第一套 test1，后端和前端；协议层未发布。部署脚本退出码 0，Backend / Frontend SUCCESS。
- 后端代码提交：`4ccab6b20b086054ca5e39961315fb8d77bc7617`；前端代码提交：`1e2203723c0072026a07c5374822bd795ed49c68`。主仓库其他 group 域在途改动未纳入构建。
- 从上述提交创建 detached 发布目录 `/private/tmp/manager-shortage-release-20260914/{armada,frontend}`；显式设置 `ARMADA_FRONTEND_DIR`，执行 `bash armada-deploy/deploy-test.sh --env test1 --all -y`，部署密钥通过环境变量指定，未复制或记录密钥内容。
- 发布目录仅叠加一项当前线上配置：`SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS`。发布前线上 compose、该叠加文件及发布后线上 compose SHA256 均为 `399568e50710efacc3ec7db1e46182c029c4f0a797e7daf709494494990236f5`；运行值为 500。
- 使用部署脚本的 npm 回退路径复用既有 node_modules；未改依赖或 lockfile。全局 pnpm 11 与既有安装布局不兼容，前端提交在手动 ESLint / Prettier / typecheck / 148 项测试 / build 通过后用 `HUSKY=0` 跳过会触发该 pnpm 问题的 hook。
- 发布目录独立重跑后端 63 项测试全通过。部署脚本语法及 `deploy-test.test.sh` 通过。无关生产离线包测试 `package-prod.test.sh` 因已有文件 `prod/scripts/inspect-production-host.sh` 缺失而失败；本次未改生产打包脚本或使用该路径。
- 本地、远端上传及运行中 `/app/app.jar` SHA256 一致：`a3b77a8061294c409f6da1a836dccbadbfd6b684fb317bf880b2f0f4c8e4cf06`。
- backend 于 `2026-09-14T07:30:19.882870109Z` 启动，nginx 于 `07:30:20.057959219Z` 启动；复核均 running、restartCount=0。后端有 Started Application，启动采样 ERROR 行为 0，无启动失败标识；采样存在业务异常日志，未将其宣称为零业务异常。
- 本地构建、nginx 容器及实际 HTTP 200 响应的以下产物哈希一致：
  - `index.html`: `75bd2017cbabc7aab2196ca1d41fa95a7dcc5855c4fc31075df4582aa18ae3a9`
  - `constants-D3W9Uq1p.js`: `ede2d4abda00fad16d60d6f8fe5a4a83a1a821d98be0c101800ab4443184bfb0`
  - `index-D07paxqu.js`: `fd80ce903b0469d02d124b33c109736e49d50d11480743224b3489d093a41e8a`
  - `standard-execution-display-B_QleLe2.js`: `8a94a611a7b7bc914ae5e4f37bf3eac23f554482921cc740dedb7fbdebf25896`
- 已登录浏览器刷新后显示“第一套环境”；打开 #211 明细正常，新增“等待并发名额”筛选可用，查询完成返回 0 条，重置可恢复列表。当前任务没有新原因码记录，未把空结果视为真实并发等待业务验收。
- 15:33 CST 只读查询：#211 仍 PAUSED、version=10、concurrent_group_count=1；#532 / #534 保留暂停前 MANAGER_UNAVAILABLE，updated_at 仍为 13:04:34.976 CST。页面对应“已暂停推进”；没有恢复、重发或手工改库。
- 本地证据：`/private/tmp/manager-release-verify.log`、`/private/tmp/manager-shortage-test1-deploy.log`、`/private/tmp/manager-release-runtime-check.log`、`/private/tmp/manager-release-task211.log`。

## 业务验收边界与回滚

- 已部署，尚未完成暂停任务恢复后的真实业务验收。#532 / #534 的新调度结果、页面状态和管理员可用性需在任务恢复后复核；发布不会自行恢复人工暂停的任务。
- 旧行会在下次资源复核时更新原因；不需手工改库。仅确认群可继续执行后开展真实业务验收，不能以本地测试代替群入群/拉人结果。
- 回滚仅回退本次 task 域与前端对应改动并重新部署，不回退其他会话的 group 域或部署配置改动。
