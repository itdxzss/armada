# 第二套拉群未知结果恢复发布

2026-09-19，用户在主仓实现完成后要求部署。目标为截图对应第二套 perf2，后端单组件。

## 来源和范围

- 实现位置：主仓 `/Users/daishuaishuai/IdeaProjects/armada`，分支 `1.0.3-snapshot`，HEAD `0bb0a688` 加本次工作区改动。未提交、未推送。
- 发布前运行容器 JAR 实测 SHA256：`c2edc971ada322de488b406f51e8368cafc6752a333d1134469dbd964d0e3026`，匹配此前 DIRECT_LINK 已发布源码快照。
- 以已发布源码为底，只叠加本次 19 个生产文件变更和相关测试；保留线上既有代码，排除主仓并行的审批停止等未发布变更。唯一混合修改文件 `PullTaskUnknownResultReconciliationService` 按本次精确 diff 应用，不整文件覆盖。
- 固定发布目录：`/private/tmp/armada-unknown-perf2-release-20260919/`，保存 `unknown-only.patch`、`changed-files.json`、`source-manifest.json`、测试与发布日志。发布后源码清单复核无漂移。

## 评审与验证

- 按仓库 expert-reviewer 检查逐成员回执、条件更新、一次补拉授权、同租户事实窗口、迟到成功、补拉排除原账号和暂停/结束边界；本次范围未发现部署阻断项。
- 发布快照真实回归：20 个测试类，174 项，0 失败、0 错误、0 跳过。包含 H2 MyBatis、租户插件和事务；测试报告在发布目录 `test-reports/`。
- `deploy-test.test.sh`、脚本语法、变更 XML 校验和 `git diff --check` 通过。
- `package-prod.test.sh` 仍因既有 `prod/scripts/inspect-production-host.sh` 缺失失败，属于生产离线包检查，未使用该路径执行本次测试环境发布。

## 部署结果

- 从固定快照执行当前脚本 `deploy-test.sh --env perf2 --be --dry-run`，随后 `--env perf2 --be -y`。
- 发布退出 0，Backend SUCCESS；Frontend、Protocol、Zhuan SKIPPED。部署编排同步 changed=0，仅后端制品发生变化。
- 本地、远端和运行容器 JAR SHA256 一致：`ca1a33cf626942a2ee71303d0cabb9c6017f748abc453a093d376d43bc4c9094`。
- 运行容器 `running`，restart=0，启动时间 `2026-09-19T13:57:51.619405113Z`；脚本验证 API 请求可达后端。
- 旧制品和编排文件备份：`/home/app/armada-deploy/backups/unknown-retry-20260919/`。保留 DIRECT_LINK 已有迁移，本次无新迁移。

## 实际业务验收

通过既有只读 JDBC 验证器检查 `armada_perf`，事务只读并回滚，SQL 限定 tenant=1、execution=215，单条查询设置超时。未手工更新数据库或发起补拉。

- 截图 task=48、execution=215 的五条未知已自动变为成功；五条 attempt 均为第 1 次、CLOSED/SUCCESS，原因 `GROUP_JOIN_FACT_CONFIRMED`。
- 该执行行料子汇总：161 成功、40 失败、0 未知。执行行仍为 COMPLETED / stage=8，没有重启历史任务。
- 尾号 8145 的后来退群事实仍保留：入群 `1789813179881`、退群 `1789813786426`；历史入群成功不因后续退群回退。
- 本次实际验证了线上“已有入群事实自动纠正”路径。无事实时换拉手补一次由上述 H2/单测覆盖，未额外创建真实任务或执行补拉来验收该分支。未进行登录后的浏览器页面验收。
