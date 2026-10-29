# 第二套群链接新模式部署记录

2026-09-19，按用户“把主仓库代码部署到第二套环境”授权发布。

## 范围与来源

- 第二套 perf2：3.110.124.52，/home/app/armada-deploy，compose project armada-perf。
- 前后端主仓当前代码（含未提交改动），固定快照构建；未提交或推送。
- 包含 DIRECT_LINK 创建/执行/详情，以及主仓新增的进群管理操作账号手机号展示。
- 后端基线 0bb0a688623ffe8bcdf3e10f2c80978132a35351；前端基线 b96bdd90；均包含工作区覆盖。
- 固定快照 /private/tmp/armada-perf2-direct-link-release-20260919；source-manifest.json 记录 3484 个源文件哈希。
- deploy-test.sh --env perf2 --all -y：Backend SUCCESS，Frontend SUCCESS；Protocol/Zhuan SKIPPED。

## 验证

- 新增主仓进群管理展示相关后端测试 15 项通过；前端 tsc/vue-tsc 通过；前后端正式构建通过。
- deploy-test.test.sh 通过。package-prod.test.sh 因已有 prod/scripts/inspect-production-host.sh 缺失失败，与本次测试环境部署路径无关。
- 运行 JAR 与本地/远端制品 SHA256 一致：c2edc971ada322de488b406f51e8368cafc6752a333d1134469dbd964d0e3026。
- 前端本地/运行 index.html SHA256 一致：5fe806c02d18197f2decdd6af4deed6375a7e14b1aa7501be52c1c98977c539c。
- 运行静态包包含 DIRECT_LINK 创建、详情、列表资源。
- Flyway 校验 211 项迁移，成功应用 V208 pull task direct link，armada_perf 当前 v208。应用正常启动。
- backend/nginx 均 running，restart count 0；nginx -t 通过；实际根目录 /usr/share/nginx/html/saas。
- 本次补齐 /api/ client_max_body_size 110m，与应用 multipart 上限一致，运行配置已核验。
- 部署脚本确认 API 路径到达后端，前端环境标识为第二套环境。
- 浏览器初次访问处于重启窗口，后续重新进入并刷新正常显示“登录 | 第二套环境”。验证标签页无登录态，未完成登录后新 Tab 页面核验；没有创建真实任务或执行 WhatsApp 拉人。部署成功不等同业务验收通过。

## 备份与回滚边界

旧制品备份：/home/app/armada-deploy/backups/direct-link-20260919/，含 previous.jar、nginx.conf、docker-compose.rds.yml、frontend.tar.gz。
旧 JAR SHA256：2a756bd6070f8492206b03375457b6e4230e5f8a8b58b6a50208a1bd9a24bbf3。
存在 DIRECT_LINK 任务时禁止直接回滚到不识别阶段 10 的旧后端；先停止新建并处理在途任务。不能盲目还原管理字段 NOT NULL。

原始构建、测试和部署日志保存在上述本地固定快照目录。
