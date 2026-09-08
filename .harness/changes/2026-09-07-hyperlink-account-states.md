# 变更记录：超链账号范围纳入被抢登和抢登中

- 日期 / 分支 / worktree：2026-09-07 / `1.0.3-snapshot` / `armada` 与同级 `wheel-saas-pure-web`
- 需求来源：用户要求超链分组账号纳入“被抢登、抢登中”。
- 状态：已部署第一套测试环境，部署与运行制品验证通过。

## 目标与设计

当前 `AccountMapper.xml` 的超链候选派生表和协议选项都限定 `account_state = 2`，导致状态 6、7 被排除。
将状态范围改为 `AccountStateCode.NORMAL / LOGIN_REPLACED / TAKING_OVER`，复用原有候选 select/count 共用 SQL，保证实时计数和任务选号一致。
前端共用筛选抽屉明确显示“正常 / 被抢登 / 抢登中”。

## 影响与约束

- 超链任务创建、策略筛选、运行选号和协议 ID 选项沿用既有接口，无请求字段、响应字段、数据库结构或 Redis 变更。
- 租户、分组、在线状态、协议能力、消息发送限制和软删除条件继续生效。
- 纳入候选不代表账号已在线或消息已送达；已有任务中被协议事件判定失效的 usage 不会因本次筛选调整自动恢复。
- 无账号上线、抢登操作或协议层状态流转变更。
- 回滚只撤销本次 SQL、注释、测试和前端文案改动，不涉及数据回滚。

## 验证

- 新增 H2 参数化用例覆盖正常、被抢登、抢登中，及新号、封禁、导出、解绑、账号受限的排除。
- 同时验证分组、租户、离线筛选、协议 ID 选项、候选分页、消息限制和软删除。
- 修改前 JDK 17 执行 `mvn -q -Dtest=AccountHyperlinkCandidateMapperH2Test test`：19 个用例，2 个失败，准确复现状态 6、7 未纳入候选。
- 修改后 `xmllint --noout src/main/resources/mapper/account/AccountMapper.xml` 通过。
- JDK 17 执行 `mvn -q -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest=AccountHyperlinkCandidateMapperH2Test,HyperlinkAccountCandidateSelectorTest,HyperlinkTaskAccountFilterHttpTest,HyperlinkStrategyAccountFilterHttpTest,HyperlinkRoundAccountSelectionServiceTest test`：退出码 0，35 个用例全部通过，无跳过（其中 H2 19 个）。显式加载已有 Byte Buddy agent 解决本机 Mockito 无法自附加的问题，无项目配置变更。
- 前端 `HyperlinkTaskEditorContract.test.ts`、`HyperlinkStrategyPage.test.ts`：14 个测试通过。
- 修改的 Vue 文件 ESLint、Prettier、Stylelint 校验通过；全项目 `tsc --noEmit` 和 `vue-tsc --noEmit --skipLibCheck` 通过；Vite 构建通过，产物在 `/tmp/hyperlink-account-states-build`。
- pnpm exec 首次尝试触发依赖检查和联网失败，后续直接调用现有 `node_modules/.bin` 完成校验，未安装或升级依赖。
- 两个仓库 `git diff --check` 通过。第一套环境浏览器与真实发送验收未执行。

## 部署

- 用户随后明确要求部署第一套环境。发布范围为 test1 前后端，不含协议层。
- 专家评审无阻断项；`bash -n` 与 `deploy-test.test.sh` 通过。
- 自动审批首次阻止从混有其他在途文件的后端主工作区部署；改用独立发布 worktree 后获准。
- 后端 `/tmp/hyperlink-test1-20260907-backend` 基于 `26fd1afb`，仅叠加本次 6 个超链源代码/测试文件；前端 `/tmp/hyperlink-test1-20260907-web` 基于 `9d26b4dd`，仅叠加筛选抽屉文案。未创建提交或推送。
- 已核对 `--env test1 --all --dry-run`，实际执行 `deploy-test.sh --env test1 --all -y`，使用已有 node_modules 的 npm 构建路径，部署日志 `/tmp/hyperlink-test1-deploy.log`。
- 发布退出码 0：Backend SUCCESS、Frontend SUCCESS；后端运行包 SHA-256 与本地构建一致，API 就绪与前端探针通过。
- 追加只读 SSH 验证退出码 0：两个容器 running、restarts=0；Spring 启动成功、无 APPLICATION FAILED TO START；远端 jar 中候选与协议选项均包含 LOGIN_REPLACED/TAKING_OVER；Nginx 实际提供的 `HyperlinkAccountFilterDrawer-DLk9zAl1.js` 包含“正常 / 被抢登 / 抢登中”。
- 未创建真实超链任务或发送消息，未验证特定分组的实际计数。
