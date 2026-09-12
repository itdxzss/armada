# 群组分组数量 test1 发布验收

2026-09-12 21:35（Asia/Shanghai），第一套 test1：65.2.123.53。

- 后端代码 `fcce0cee`，前端代码 `2c27ce28`，均属于远端 `1.0.3-snapshot`。
- 后端按远端分支创建临时 worktree；前端使用 `2c27ce28` 的独立 worktree 构建。保留主目录账号列表等其他在途修改。
- 通过当前 `deploy-test.sh --env test1 --all --branch 1.0.3-snapshot -y` 发布，`ARMADA_FRONTEND_DIR` 指向本次前端 worktree；使用 Node 24 与已有 node_modules，走脚本支持的 npm 构建路径。
- 部署脚本退出 0，Backend / Frontend SUCCESS；协议层未发布。
- 后端运行 JAR SHA-256：`1542205cbda614904ba6df7b70bef1fbb14960f676da95820987438b05434af6`，与本次构建及远端文件一致。
- 后端、nginx 均 running，重启次数 0；后端于 21:32:08 启动，服务验活通过。
- 前端 554 个静态文件哈希与构建产物一致；第 555 个 `platform-config.json` 仅 Title 不同，是部署脚本生成的“第一套环境”标题，已核对。
- 线上入口 JS：`index-CqA8Ctfa.js`；群组页面 JS：`index-D2CJ5wwS.js`，包含总数、未分组数及分组数量字段。
- Chrome 新建独立验收页，复用当前登录态只读验证：下拉显示“全部分组（950）”“未分组（712）”“8-26分组（0）”；选择未分组并查询，列表显示“共 712 条”。验收后已重置为全部分组，列表恢复 950 条。
- 未在验收中创建、移动或删除群组，未发送协议消息。
- 发布脚本回归通过；生产离线打包脚本测试因仓库既有缺少 `prod/scripts/inspect-production-host.sh` 失败，本次不涉及生产离线包。

证据：`/tmp/group-count-test1-deploy.log`、`/tmp/group-count-deploy-dry-run.log`、`/tmp/group-count-test1-artifact-verification.json` 及本任务浏览器验收输出。

回滚：重新发布前一后端 `9eb52d95` 与前端 `df82f7fa`；本次无数据库迁移。
