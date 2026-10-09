# 2026-10-09 前后端当前代码发布

## 授权与范围

用户明确要求将其他会话剩余业务改动一起 commit、push，并部署第一、第二套环境。
开发及构建均在主仓 `1.0.3-snapshot`，未新建 worktree、未切换分支。
仅发布 Armada 后端与 wheel-saas-pure-web 前端；协议层没有发布。
后台工具状态文件、旧 worktree、历史现场材料及原始测试日志保留本地，未混入业务提交。
前端仓库既有 pre-commit hook 自动创建并清理了 lint-staged 临时备份，提交完成后前端工作区干净。

## 提交

- 后端 `322c9cae`：终态注销预留释放、V217、批量上下线/抢登拒绝隔离，53 files changed, 2091 insertions(+), 90 deletions(-)。
- 前端 `6a52b03e`：部分抢登反馈、已释放预留状态，6 files changed, 240 insertions(+), 35 deletions(-)。
- 后端 `b7a25cb9`：调度唤醒依赖环修复，2 files changed, 48 insertions(+), 2 deletions(-)。
- 以上提交均已 push 到各自 origin/1.0.3-snapshot；逐项暂存并检查 `git diff --cached --stat`。

## 本地验证

- 后端发布专项：319 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS。
- 前端 typecheck、构建、六个变更文件 ESLint 均退出 0；两个专项测试类 21/21 通过。
- 三个变更 Mapper XML 的 xmllint 及源码 git diff --check 通过。
- deploy-test.sh 脚本测试通过。package-prod.test.sh 失败：缺少 prod/scripts/inspect-production-host.sh；本次使用测试环境发布脚本。
- 不宣称全量全绿：前端全量 802 tests / 727 pass / 75 fail，其中存在 CSS loader 错误；全仓 ESLint 在未改动文件中有 30 个格式错误。
- 后端全量失败明细保留在 creator-reservation-release change 记录；本次未扩大范围修复这些失败。

## 第一套首次启动失败与修复

首次 test1 发布前端成功，Flyway 从 V213 成功应用 V214–V217，但后端因以下构造依赖环启动失败，脚本验活退出 1：

`PullerAccountStateService → DispatchTrigger → DispatchScheduler → DispatchCoordinator → ResourceRecovery → PullerAccountStateService`。

暂停 perf2 发布，新增禁用循环引用的 Spring 容器回归测试，先得到 1 test / 1 failure，根因 BeanCurrentlyInCreationException。
在 DispatchTrigger 的 scheduler 构造参数使用项目已有的 @Lazy 注入方式，将调度器解析推迟至运行期唤醒，保持 afterCommit 时序。
首次修复验证中的测试误把 Spring 调用 mock 的 @PostConstruct 当作业务交互，改为准确断言提交前 trigger 未调用；未改动生产逻辑规避测试。
最终运行：

```bash
JAVA_HOME=<JDK17> mvn -f armada-api/pom.xml \
  -DargLine='<local Byte Buddy javaagent> -Djava.awt.headless=true' \
  -Dtest=PullTaskExecutionDispatchTriggerContextTest,PullTaskOfflineResourceRecoveryH2Test,PullTaskCreatorDeletionReleaseH2Test test
```

真实结果：72 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS。
提交修复后重新发布 test1 后端，脚本退出 0，API 就绪、容器重启次数 0。

## 发布命令

```bash
bash armada-deploy/deploy-test.sh --env test1 --all --yes
bash armada-deploy/deploy-test.sh --env test1 --be --yes
bash armada-deploy/deploy-test.sh --env perf2 --all --yes
```

## 验证边界

核对运行中 JAR 和前端 index.html 摘要、容器稳定性、Flyway 状态、前端环境标题及 API 未登录响应。
未主动发起真实账号上线、抢登或拉群；部署验活不等于 WhatsApp 业务验收。
本机原始日志在 `/private/tmp/armada-release-20261009-*`，不包含在 Git 提交中。

## 最终结果（2026-10-09，Asia/Shanghai）

| 环境 | 部署结果 | 迁移 | 容器 | 页面 / API |
|---|---|---|---|---|
| 第一套 test1 | 修复后后端发布退出 0，前端首次发布成功 | V217；本轮成功应用 V214–V217 | backend/nginx running，重启 0；复查无 ERROR | 首页 200、标题第一套环境；API 401 / code 40104 |
| 第二套 perf2 | --all 退出 0 | V217；本轮成功应用 V215–V217 | backend/nginx running，重启 0 | 首页 200、标题第二套环境；API 401 / code 40104 |

运行中 JAR 与每次本地构建 SHA-256 一致（分别构建，归档时间导致制品摘要不同；源码同为 b7a25cb9）：

- test1：`ab16248e857fb07b56131200b7eb45d761475c44f947b05db19239ab0743bf4a`
- perf2：`8bb2fa1d8f42890ce539c194a45e6a2d2ce2ac68232b1e65fdfe6ac358e8bfdd`
- 两套前端运行中 index.html 均与本地一致：`0cf0165a03ac631627fcf6223890f1acefedd908ea55dc16ebc43654d2433928`

perf2 启动后出现一次 Kafka ERROR，根因是 AccountTakeoverBreaker.recordKick 首次写入时的 MySQL 死锁。
同一事件收到两次，错误后约 0.5 秒该账号已按生命周期收敛，后续继续处理；复查 ERROR 总数保持 1，未出现持续重启或持续消费错误。
当前 Kafka DefaultErrorHandler 对该数据库异常启用重试；本轮不更改重试策略或计数 SQL。
保留 MySQL 首次并发插入的死锁风险，H2 测试无法替代 InnoDB 间隙锁验证，不把短期观察描述成彻底消除死锁。

验证摘要位于本机 `/private/tmp/armada-release-20261009-test1-verify-final.txt` 与 `...-perf2-verify-final.txt`。
