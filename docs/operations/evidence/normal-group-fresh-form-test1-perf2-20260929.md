# 新建普群入口修复：两套环境前端发布

用户授权：将本次前端修复部署第一套 test1 和第二套 perf2。

## 来源与范围

- 前端当前工作区，分支 `1.0.3-snapshot`，HEAD 基线 `b96bdd906946798c78620f0c4ec9d729996c1391`。包含未提交修改，HEAD 不代表完整发布内容；本次未 commit/push。
- 使用本轮通过生产构建的 dist 快照：`/tmp/normal-group-frontend-release/dist`，609 个文件。未重新安装依赖。
- 入口每次重置配置；删除旧任务/旧提交的 sessionStorage 恢复与自动重放路径。
- 复用当前环境档案、`lib/common.sh`、`lib/armada.sh` 的同步/验证函数；只同步前端 dist，沿用远端已有编排及 nginx 配置，仅 `up -d --build --no-deps nginx`。
- 后端容器 ID 和 StartedAt 发布前后一致；协议层未执行部署操作。

## 本地验证

22 个相关用例、tsc、vue-tsc、修改文件 ESLint、生产构建均通过。
部署脚本测试通过；生产离线包测试因缺少 `prod/scripts/inspect-production-host.sh` 失败，该路径未参与本次测试环境前端发布。

## 生效证据

两套部署进程均退出 0；nginx running、RestartCount=0。
远端 dist 全部文件及容器内全部静态文件 SHA-256 校验通过；运行时生成的 platform-config.json 单独核验环境标题。
两套公网首页与建群 chunk HTTP 200，且 SHA-256 均等于本地：

- index.html：`5ce0fbafa50f82ef75be072225d0a2c10530174da016f23e9986a069d6f9143a`
- static/js/CommonGroupCreateFlow-Ctg_eACp.js：`1f728eb045819b6ac10dc3a344d7967e42b0dff4cb2586e9b81caa18d28caaba`

未执行真实建群操作；业务点击行为由本轮 composable 回归测试验证，线上核验范围为已发布制品与可访问性。

## 回滚备份

- test1：`/home/app/armada-deploy/frontend-backups/normal-group-fresh-form-20260929T142513Z`
- perf2：`/home/app/armada-deploy/frontend-backups/normal-group-fresh-form-20260929T142600Z`

每份含原 dist.tar.gz、原 nginx 镜像 ID、前后端容器标识核对记录和本次制品 SHA256SUMS；原镜像另保留对应 `armada-nginx-rollback:<release-id>` 标签。
本地部署脚本、日志、制品清单：`/tmp/normal-group-frontend-release/`。
