# 分组素材数量 test1 发布记录

2026-09-12 21:01（Asia/Shanghai），第一套 test1，65.2.123.53。

- 后端代码：428b7873；前端代码：f708d008；分支 1.0.3-snapshot，均已推送。
- 后端从远端提交创建临时 worktree 构建；前端从 f708d008 的独立 worktree 构建，排除主目录其他在途工作。
- 发布：deploy-test.sh --env test1 --all --branch 1.0.3-snapshot -y，Backend / Frontend SUCCESS；协议层未发布。
- 运行中 JAR SHA-256：516d0fc00bd08bc367e932b7d468c444d4282b4cf65ffc7bc9bc6f0e8d12f74f。
- 前端 554 个文件 SHA-256 与构建产物全部一致，标题“第一套环境”。后端和 nginx 均 running、重启次数 0，启动 ERROR 数 0；发布脚本验活通过。
- 只读数据库核对：当前超链分组 11、22 均 0 张；Flyway V190/V189/V188 均成功，本次无迁移。
- 页面行为通过 4 项本地 Playwright 夹具回归；后端聚焦测试 37 项及前端类型/静态检查通过。本次未借用用户登录态执行测试环境 API 写入联调。

证据：/tmp/test1-asset-count-deploy.log、/tmp/test1-asset-count-artifacts.json、/tmp/test1-asset-count-artifact-verification.json、/tmp/test1-asset-count-db.log、/tmp/asset-count-backend-tests.log、/tmp/asset-count-e2e.log、/tmp/asset-count-typecheck.log。

回滚可重新发布前一后端 7130b386 与前端 37dd646e，仅移除数量展示，不需要回退数据库结构。
