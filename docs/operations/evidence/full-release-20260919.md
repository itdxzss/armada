# 四仓双环境发布记录（2026-09-19）

用户授权：当前前后端、Web/安卓协议代码 commit、push，部署第一套 test1 和第二套 perf2。四个既有 GitHub origin 地址已获得再次明确授权。

## 提交和推送

| 仓库 | 分支 | Commit |
|---|---|---|
| armada | 1.0.3-snapshot | 0bb0a688623ffe8bcdf3e10f2c80978132a35351 |
| wheel-saas-pure-web | 1.0.3-snapshot | b96bdd906946798c78620f0c4ec9d729996c1391 |
| armada-protocol | 1.0.3-snapshot | 40a041ade335c4c199080b15767df27832410fda |
| whatsapp-server-feature-android-zhuan | 1.0.3-snapshot | e406294c41ec58d1a552cc99ab79b263ede04a1c |

四个 git push 均返回 0。仅提交当前业务源码、测试、Swagger 和相关数据模型文件；本地 PID、其他 worktree、未提交分析记录、取证产物和凭据未纳入。发布使用上述提交创建的干净 detached worktree。

## 部署结果

| 环境 | 范围 | 部署退出码 | 深度检查退出码 | Flyway |
|---|---|---|---|---|
| test1 | 后端、前端、Web、安卓 coordinator + 3 nodes | 0 | 0 | V200 → V207 |
| perf2 | 后端、前端、Web、安卓单机 | 0 | 0 | V205 → V207 |

执行 `deploy-test.sh --env test1 --full -y`、`deploy-test.sh --env perf2 --full -y`，随后分别执行 `--check`。test1 使用原有 fleet 清单覆盖参数，凭据仅通过原有本地路径读取。

- 两套部署均核对了运行中容器 JAR SHA-256、前端环境标识和 API 代理响应。
- 两套 Web 协议各 1 master + 4 workers 在线，安卓容器健康检查通过。
- 两套只读深度检查覆盖 Armada、Baileys、Kafka、Zhuan、Cross-component，全部通过。
- Web 本次变更的 7 个生产源码文件、安卓本次变更的 18 个生产源码文件逐项比对 SHA-256，两套全部目标均无差异。
- 后端启动日志确认 Flyway 迁移成功、Application 启动成功；检查时两套前后端容器 restart=0。

运行 JAR 哈希：
- test1: `6bfbb4532a73cb833d14057def96df04b38552df1014b119dc857c12ead81d96`
- perf2: `2a756bd6070f8492206b03375457b6e4230e5f8a8b58b6a50208a1bd9a24bbf3`

两个环境各自从同一源码提交重新打包，构建产物哈希分别匹配各自运行容器。

## 本地验证与边界

- 后端 105 个聚焦测试类：1131 个用例，1120 通过、11 失败。失败均在干净 HEAD 复现，涉及三个既有 H2 测试类缺字段或 MySQL FORCE INDEX 语法兼容。未将全量后端测试视为通过；需外部数据库的全量测试已停止。
- 前端类型检查、生产构建、49 个聚焦用例全部通过；91 个暂存文件提交钩子的 Prettier、ESLint、Stylelint 通过。全仓 lint 31 个错误，干净 HEAD 为 35；直接 Node 全量测试存在 CSS 加载及旧断言失败（当前 692/765 通过，HEAD 673/743），不报告全量通过。
- Web 协议构建与全部 1405 个单测通过。
- 安卓 gofmt 无差异、go vet、go build 通过；改动业务包及 race 测试通过。完整 go test 的 pkg/noise 8 个失败在干净 HEAD 复现。归档快照缺少 .git 导致的 fleet 测试失败已在有效发布 worktree 复测通过。
- deploy-test.test.sh 通过；package-prod.test.sh 因已有缺失的 prod/protocol/.env.example 未通过，本次未执行生产离线打包。

本记录证明提交、推送和两套环境部署完成，不代表账号导出、互加通讯录、进群管理、超链发送等新增业务已逐项进行真实操作验收。

临时日志目录：`/private/tmp/armada-release-20260919/logs`。本报告不包含运行配置或凭据。
