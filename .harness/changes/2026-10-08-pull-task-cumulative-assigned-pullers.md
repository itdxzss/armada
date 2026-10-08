# 变更记录：群执行累计分配拉手数量

- 日期 / 分支 / worktree：2026-10-08；前后端 `1.0.3-snapshot` 主仓库。
- 需求来源：用户确认统计“累计分配过的”，并要求“在主仓库改吧”。
- 状态：本地实现与验证完成，未部署。

## 目标

在拉群任务的群执行列表资源列中展示“累计分配拉手”，便于同时查看当前缺口与历史分配账号数量。

## 口径与实现

- 以当前租户下单个 `group_execution_id` 为统计范围，仅统计 PULLER 角色，按 `account_id` 去重。
- 包含当前使用及已释放、替换、封禁、解绑的账号；尚未入群或尚未发起调用也计入分配数。
- 同账号恢复占用不重复累计；管理员、站台、建群者不计入。
- 复用 `pull_task_group_account` 与当前页批量聚合查询；不依赖账号现状表，不逐行查询详情。
- 执行摘要新增 `cumulativeAssignedPullerCount`，列表与单执行详情共用。真实空集为 0，聚合事实缺失为 null。
- 前端资源列标题调整为“执行资源”，展示“当前拉手”和“累计分配拉手”；字段缺失显示破折号。
- 数据库结构、Redis 和协议调度无变更。

## 任务清单

- [x] 后端聚合、摘要字段及真实 Mapper H2 测试。
- [x] 前端字段透传、资源展示与适当验证。
- [x] 差异复核和结果记录。

## 验证

- 后端 TDD：新增 H2 用例在 SQL 未返回新字段时 2 failures / 0 errors；实现后完整聚焦测试 34 passed（H2 Mapper 13、Service 5、Controller 16），0 failures / errors / skipped。
- 后端成功命令（`armada-api/`）：`JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='PullTaskStandardReadMapperInMemoryTest,PullTaskStandardReadServiceTest,PullTaskStandardControllerTest' test`。
- 本机默认 Java 27 及 Java 17 动态附加 agent 初始化 Mockito 失败；使用项目要求的 Java 17 并显式加载本机已有 Byte Buddy agent 后通过，未修改依赖或全局配置。
- H2 验证未入群即计入、释放/封禁后保留、恢复不重复、新增账号增加、空集为 0、其他角色/执行行/租户隔离，及不依赖现存账号表；Service 验证列表与详情透传和 0/null 区分。
- `xmllint --noout armada-api/src/main/resources/mapper/task/PullTaskStandardReadMapper.xml`、前后端 `git diff --check` 均通过。
- 前端测试（前端主仓）：`node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test 'src/views/task/pull-task/**/*.test.ts' src/api/pull-task.test.ts`，190 passed，0 failed。
- 前端 `pnpm typecheck`、5 个改动文件的 `pnpm exec eslint --max-warnings 0` / `pnpm exec prettier --check`、`pnpm build` 均通过；构建耗时 39.78 秒。
- 真实 Vue 资源组件的临时 SSR 渲染验证覆盖 6、0、null、未返回与 DIRECT_LINK 模式，通过；未添加测试依赖。
- 诊断日志：`/tmp/puller-cumulative-backend-red.log`、`/tmp/puller-cumulative-backend-jdk17-agent.log`、`/tmp/cumulative-puller-frontend-{tests,typecheck,eslint,prettier,build}.log`。
- 未连接远程环境，未完成真实接口与部署页面的端到端验收。

## 部署与回滚

- 本次范围为本地主仓库修改和验证；未提交、未推送、未部署。
- 回滚仅撤销本次字段、聚合和展示改动；保留其他会话的在途修改，无数据迁移。
