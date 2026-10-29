# 第二套群链接新模式 UI 发布

用户授权：在前端主仓库调整 UI 后，部署第二套 perf2。本次仅前端/nginx，未提交或推送，未更新后端或协议。

来源：主仓库 1.0.3-snapshot / b96bdd90 加现有工作区覆盖。固定发布目录 `/private/tmp/perf2-direct-ui-release.Rxe164/frontend`。以此前 DIRECT_LINK 已发布快照为基础，保留最新 join-reason 发布中的 JoinTaskDetailDrawer.vue 和 admin-reason.ts，再叠加本轮 3 个 UI 组件；发布快照 src 与主仓库逐文件校验一致。

发布前发现第二套当前首页哈希 fdd203477f67ab837b49d762476c5c664c5380f929d221cfed214cdc994a5e4a，对应 `/private/tmp/join-reason-release-20260919`；该版遗漏 DIRECT_LINK 源文件。本次保留其管理员说明改进，并重新包含已授权的新模式及 UI。

执行当前 `deploy-test.sh --env perf2 --fe --dry-run` 后执行 `--env perf2 --fe -y`，ARMADA_FRONTEND_DIR 指向固定快照。使用现有依赖及脚本 npm fallback。退出码 0，Frontend SUCCESS，Backend/Protocol/Zhuan SKIPPED。编排文件同步 changed=0。

验证：
- 本轮 UI 修改已通过类型检查、生产构建、目标 lint、17 项 Node 回归、3 项本地合成浏览器测试。
- 部署脚本回归通过；生产离线包测试仍因已有缺失 prod/scripts/inspect-production-host.sh 失败，本次不走该路径。
- nginx running / restart=0，nginx -t 通过，环境标识第二套环境。
- 后端启动时间保持 2026-09-19T08:16:00.413116794Z，本次未重启。
- 本地和运行容器 index.html SHA256 一致：788f02101d9380c57a7c97e9af7c435c7bd4598c1c1c6d0af086fe1476fa8421。
- 新模式 CSS SHA256：41d94b254a644ca75dcf512234b53199f0cf0154febd689ae0bf95c59739021f。
- 新模式 JS SHA256：65c9c72967a2614e876f2e619a70262a15e5d51ebde56071bd65762b58912ec3。
- 料子资源 JS SHA256：7d1e4fc4af005db7c05646f0b465cd2f3eca15dc82b46632cbe7e074fac908e5。

远端备份 `/home/app/armada-deploy/backups/direct-ui-20260919/` 含旧前端 tar、nginx 和 compose 配置。日志 `/private/tmp/perf2-direct-ui-release.Rxe164/deploy.log`。未创建真实任务或进行 WhatsApp 业务操作；本次结果为 UI 制品发布验证。
