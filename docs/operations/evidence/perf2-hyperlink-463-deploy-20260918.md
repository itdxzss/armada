# 第二套环境 463 换号重试发布

2026-09-18 用户明确要求部署到第二套环境。使用后端与前端主仓 1.0.3-snapshot 当前脏工作区（含未提交改动），后端HEAD 6aa319cdd4f072eb45a48a61c58a4fc0cb6aab3d，前端HEAD 23f8476c。HEAD仅为基线，制品SHA代表本次构建。没有commit/push。

## 范围和结果

命令：`CI=true bash armada-deploy/deploy-test.sh --env perf2 --all -y`。
目标：perf2，3.110.124.52。脚本退出0，Backend SUCCESS、Frontend SUCCESS；Web和Android协议层均SKIPPED。
部署前完成expert-reviewer范围复核、dry-run、脚本语法及deploy-test.test.sh（通过）；沿用本次修改已完成的409项后端、14项前端、typecheck/lint/build验证。
既有package-prod.test.sh因缺少prod/scripts/inspect-production-host.sh失败；本次是测试环境发布，不使用该生产离线包路径。

## 线上生效证据

- 本地、远端磁盘和运行容器/app/app.jar的SHA256一致：1abc308dc1a35b4c5c15b084f425e4312533970269ca8e9b4e9fa8ccc8ea5e92。
- 后端启动2026-09-18T08:26:05.221474398Z，nginx启动08:26:05.541588473Z；均running、RestartCount=0。应用08:26:24 UTC启动完成。
- Flyway日志验证208个迁移条目，成功应用1个新增迁移至v205。只读查询flyway_schema_history版本205 success=1；hyperlink_recipient_sender_rejection表存在。
- 实际HTTP返回recipient-stats-DO-bHbaj.js与本地SHA256一致：9ce64d82bb17d153272317e998f98ebeee2be4f20765885dd2c205d04ec10008；包含等待换号重试文案。
- 前端HTML及第二套环境标识通过脚本检查；/api/account-groups未登录返回预期code=40104，证明API路由正常。
- 任务13只读查询为run_status=4（停止），success_num=155、fail_num=3844。审计STOP actor_user_id=11，发生在07:59:36 UTC（15:59:36北京时间），早于本次部署；本次没有停止、恢复或重发任务。

部署用时181秒；日志本机/tmp/perf2-hl463-deploy-20260918.log。未进行真实WhatsApp发信验收；本记录确认发布、迁移、运行制品、静态资源和服务健康生效。
