# 变更记录：拉群允许在线抢登账号

- 日期 / 分支：2026-09-13，1.0.3-snapshot，现有工作区。
- 需求来源：用户明确要求拉群所有相关环节允许在线的“被抢登 / 抢登中”账号，而非只放开补充拉手。
- 状态：已提交、push 并部署 test1；实际补充拉手业务流程未验收。

## 目标与边界

拉群任务的生命周期准入统一为 NORMAL、LOGIN_REPLACED、TAKING_OVER，仍要求 ONLINE。
保留封禁/解绑等不可用状态、协议身份、拉人限制、风险及任务占用原有校验。
独立“新建普群”、历史群同步和其他营销选号继续使用原生命周期规则。
群主退群是拉群调用的共享能力，其在线抢登账号准入同步放开；原先允许离线正常接管者的规则保留。

## 覆盖

- 普通群链接管理员首次选号、补充管理员、补充拉手、补充站台。
- 新建群模式建群号、站台容量校验。
- 拉手初选、粘性复用、等待资源恢复、最终拉人命令前复核。
- 候选群统计、管理员筛选和账号列表；正常离线管理员仍可加入等待池，抢登类仅在线允许。
- 群主退群时的群主/继任管理员/可提升成员判断。
- 前端拉群专用在线数量、站台容量及补充资源文案。

## 数据与 API

无表结构、Flyway、Redis、任务配置或凭据变更。
账号分组响应新增 pullTaskOnlineCount 派生字段，保留 executableOnlineCount 的正常账号口径。
前端只在拉群设置中读取新字段；限制与占用仍由各角色候选接口最终裁定。

## 验证

- 新 H2 回归先红：在线状态 6/7 被旧查询排除，4 用例中 1 个失败。
- 修改后初轮真实 H2 和 SQL 测试通过；Mockito 初轮受 JVM self-attach 限制，使用本机已有 Byte Buddy agent 重跑。
- 扩大测试时发现外部数据库依赖，已中止；后续限定本地 H2 和纯单测。
- Java 17 + 现有 Byte Buddy agent：19 个核心测试类最终合计 183/183 通过；另 5 个复用、批量派单、站台配置及群设置测试类 29/29 通过。合计 212，0 failure/error/skip。
- 核心测试包含 AccountPullerEligibilityMapperH2Test、AccountGroupMapperH2Test、PullTaskGroupMarketingCandidateMapperH2Test；均执行真实 Mapper 和租户插件。候选群测试用仅测试环境的别名补足 H2 不支持的 SUBSTRING_INDEX。
- PullTaskStationSupplementServiceTest 旧 fixture 使用 PULLER_INVITE 阶段，无法进入站台补充；改为 PULL_EXECUTION 枚举，并新增错误阶段仍拒绝的回归。未放宽生产阶段限制。
- 前端命令：`node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test 'src/api/account-group.test.ts' 'src/views/task/pull-task/**/*.test.ts'`，142/142 通过。
- `tsc --noEmit`、`vue-tsc --noEmit --skipLibCheck`、本次 7 个前端文件 ESLint、Vite build 全部退出 0；XML 校验、两仓 `git diff --check` 通过。
- 运行日志：`/tmp/pull-focused-final.log`（核心初跑，站台 fixture 修正前）、`/tmp/pull-station-final.log`（站台修正后通过）、`/tmp/pull-dispatch-final.log`、`/tmp/pull-ui-tests3.log`、`/tmp/pull-ui-build.log`。
- 扩大回归未全绿：旧 PullTaskMapperInMemoryTest fixture 缺 creation_mode；旧 PullTaskGroupMarketingGroupMapperInMemoryTest fixture 缺 group_classification；状态 SQL 结构旧断言、过期邀请链接回归亦失败。Java 23 扩跑还出现 JVM 134 退出。上述不能当作全量验收通过；本次改动已使用 Java 17 聚焦回归验证。
- 未手动执行真实账号补充或发消息。远程发布结果见下。

## 部署与回滚

- 主仓库修改：后端 `420fefce`、前端 `c7485f57`，均已 push 到 `origin/1.0.3-snapshot`。
- 用户明确授权第一套环境，执行 `deploy-test.sh --env test1 --all --branch 1.0.3-snapshot -y`；仅后端和前端，协议未发布。
- 从以上提交的干净目录构建，未包含主仓库其他代理恢复未提交修改。前端使用部署脚本现有 node_modules 的 npm fallback；本机 pnpm 11 自动安装问题未带入发布。
- test1 后端/前端均 SUCCESS，运行容器 JAR SHA-256 与构建一致，前端可访问、环境标题及 API 路由通过。
- 公开资源 `static/js/account-group-DWoWoknS.js` 与本次构建 SHA-256 相同，含 `pullTaskOnlineCount`。
- 浏览器借用被取消，已结束会话；没有把部署成功当作真实补充流程验收。
- 部署日志 `/tmp/pull-online-test1-deploy.log`，部署前检查 `/tmp/pull-before-deploy-check.log`。
- 回滚可从上一版后端 `38ae36ee`、前端 `c576395a` 构建发布；不覆盖其他工作区改动。
