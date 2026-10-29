# 累计分配拉手：perf2 发布结果

- 用户授权：2026-10-08 要求主仓库修改后 commit、push、部署；目标沿用截图第二套测试环境 perf2。
- 后端提交并推送：`4ec5e2bb`，前端提交并推送：`934c8be4`；均为 `origin/1.0.3-snapshot`。
- 发布从上述固定提交的 git archive 构建；主仓库其他会话的未提交文件未进入提交或发布包。
- 发布前按 expert-reviewer 复核字段、SQL、租户隔离、空值和调用方，无阻断发现。后端固定提交重新通过 34 个聚焦测试并 package；前端固定提交 build 通过。本次实现阶段另有前端 190 tests、typecheck、改动文件 ESLint/Prettier 与组件 SSR 通过。
- deploy-test.sh 的帮助、perf2 --all --branch 1.0.3-snapshot --dry-run、bash -n、部署脚本测试均通过。线上 6 个部署编排/镜像资源文件哈希与仓库一致。
- 实际发布使用固定提交内现有 `lib/{common,artifact,armada}.sh` 的制品同步、启动和验证函数；从干净快照产物同步 JAR/dist，使用 `--no-deps backend nginx` 仅重建前后端。部署驱动及构建产物保存在 `/tmp/cumulative-puller-release-20261008/`。
- 发布退出 0。北京时间 15:53:39/40 前后端启动，两个容器 running、RestartCount=0。Spring 于 15:54:00 完成启动；Flyway 验证 217 个迁移，`armada_perf` 仍为 V214，无需迁移。
- JAR 本地/部署目录/运行容器 SHA-256：`77acb96f3e9a0f7f2e5598466cf1955d4162c65dc5920ec69eaa8b8d8418e9b8`。
- 前端构建 index 与容器 SHA-256：`c60a4b666ac3d7b49a0b045a028f58a6dee1ed10c480d030420f0d600e1524c9`。首页 HTTP 200，脚本验证 API 代理、perf2 环境配置及前端标题通过。
- 已使用现有登录浏览器只读打开截图对应任务 79 / 执行 321，确认实际显示：`管理员：1/1 当前拉手：0/2（缺 2） 累计分配拉手：6 个 站台：0/0`。初始旧页面缓存仍显示旧文案，通过发布版本查询参数重新加载后看到新版本；未操作暂停、结束或补充拉手。
- 浏览器验证会话已关闭，用户原标签页未改变。
- 回滚材料：`/home/app/armada-deploy/release-backups/cumulative-puller-20261008-4ec5e2bb`，含旧 JAR、dist 压缩包、原镜像 ID；原镜像保留 `cumulative-puller-backup-20261008` 标签。
- 协议服务与设备上传网关未变更；未执行新的 WhatsApp 拉人业务动作。
- 本地日志：`/tmp/cumulative-puller-release-backend.log`、`/tmp/cumulative-puller-release-frontend.log`、`/tmp/cumulative-puller-perf2-deploy.log`、`/tmp/cumulative-puller-ui-verified.txt`。
