# 变更记录：新群拉人参数可配置

- 日期 / 分支 / worktree：2026-10-07 / 1.0.3-snapshot / 主 checkout。
- 需求来源：用户要求“不能限制死，拉人限制最大数在50以内即可”。
- 状态：本地实现与聚焦验证完成；扩大回归存在两项已复现的基线错误。

## 目标

NEW_GROUP 与 SIMPLE_NEW_GROUP 单次拉人数允许 1–50 人；拉人间隔允许自定义非负整数秒范围，不再强制 10–15 秒。

## 缺口拆解 / 任务清单

- [x] 前端两个新群入口的人数控件、间隔控件与提交校验同步调整。
- [x] 后端共享新群创建校验同步调整；人数不超过 50，上下限有序。
- [x] 默认 1–3 人、10–15 秒保留；原群链接模式不受影响。
- [x] 核对设置写入及调度：已有逻辑使用保存的范围，无需改动。
- [x] 完成创建、落库、调度与前端验证。

## 关键设计决策

- API 字段不变；没有 DB、Redis、协议层或存量任务数据变更。
- 最大间隔缺省继续按固定下限处理；上下限相同表示固定人数或固定间隔。
- 不增加锁或新的调度路径。
- 回滚仅撤销本次校验及控件差异；已保存的新范围由现有执行逻辑支持。

## 验证

- 前端新增用例已在旧实现上失败：合法 10–15 人被旧 1–3 限制拒绝。修改后两个 composable 的 43 条测试通过。
- 后端新增合法范围用例已在旧实现上失败。首次 Maven 使用主机默认 Java 27，H2 用例中的 Mockito 自附加失败；改用项目要求的已安装 JDK 17，并显式加载现有 Byte Buddy agent 重跑。
- JDK 17 下执行 `mvn -q -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest=PullTaskNewGroupModeValidatorTest,PullTaskSimpleNewGroupContractTest,PullTaskDirectLinkCreateInMemoryTest,PullTaskStandardCreateServiceTest,PullTaskBatchSizeSelectorTest,PullTaskPullIntervalPolicyTest test`：58 条，56 通过、2 错误，无跳过，退出码 1。与本次变更直接相关的其余 5 类共 28 条全部通过；H2 验证新群 10–50 人、20–30 秒原样落库，调度测试覆盖 50 人、末批不足和自定义间隔。
- 两项错误来自未修改的 `PullTaskStandardCreateServiceTest`：`newGroupModeAllowsNoManagerGroupAndPersistsNullSnapshot` 触发管理分组校验，`linkModesStillRejectNoManagerGroupWithoutFreezingDraft` 的夹具触发唯一键冲突。使用 `git archive c9a59b24 armada-api` 导出的独立基线 `/tmp/new-group-limits-baseline.BjvPgF`，只跑两方法，完全复现 2/2 错误；未扩展修改范围。日志 `/tmp/new-group-limits-backend-baseline.log`。
- 前端使用 `node --import tsx` 后注册现有 `src/api/__tests__/node-test-loader.mjs`，执行 `src/views/task/pull-task/**/*.test.ts` 与 `src/api/pull-task.test.ts`：189/189 通过，退出码 0。
- `pnpm typecheck`、本次 7 个 TS/Vue 文件的只读 ESLint 和 Prettier 检查、`pnpm exec vite build --outDir /tmp/new-group-pull-limits-dist` 均退出 0。
- 两仓 `git diff --check` 通过；旧人数及间隔硬校验已移除，现有账号批量操作等其他在途修改未改动。

## 部署

- 用户已授权在主仓库提交、推送，并部署至第二套环境 perf2；仅发布本次前后端范围。
- 发布前复核未发现阻断项：人数、间隔、缺省上限及两模式调用链一致；无数据库迁移或调度路径改动。真实 WhatsApp 拉人验收仍未执行。
- 发布前 `deploy-test.sh --env perf2 --all --branch 1.0.3-snapshot --dry-run`、部署脚本语法及回归测试通过。目标为 `armada-perf`，运行配置与制品需在部署后核验。
