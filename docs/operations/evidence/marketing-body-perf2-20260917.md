# 第二套图片链接卡片正文修复发布

- 2026-09-17，用户授权部署第二套，仅后端。
- 主仓库修复提交：41f5c464。未 push。
- 发布提交：04159e57，基于第二套原运行版本 fcc9e117，仅加入消息组装修复和两份回归测试。
- 发布前运行 JAR 与本地 fcc9e117 JAR SHA-256 一致：f952025a887cd547fcd35cdd89953dd3c5bb5cd4c0a1a403029833decaeac5ab。
- 新 JAR SHA-256：a258304817e9aef14b813aeb03554150d4cda2ea1332c0f28548e3753006b023；部署脚本已验证运行容器内一致。
- JAR 条目逐项比较：无增删，仅 MarketingMessageComposer 及其内部类的字节变化。
- 6 个相关测试类共 84 项，0 failure/error/skipped，包含 H2 真实 Mapper。测试部署脚本检查通过。
- 生产离线包测试既有缺文件失败：prod/scripts/inspect-production-host.sh；不涉及本次测试环境部署。
- 从干净发布 worktree 执行 deploy-test.sh --env perf2 --be -y，exit 0，Backend SUCCESS，其余组件 SKIPPED。
- 容器于 11:09:13 UTC 启动；Application 17.602 秒启动成功；运行状态 running、restarts=0。API 路由与环境运行配置检查通过。
- 修复行为：IMAGE_LINK 完整 content/bodyText/URL 放正文；卡片元数据使用域名、description 为空，图片链接和 mentionAll 保留。
- 未主动发送真实消息。仅新生成 payload 生效；已有排队 payload 不追溯改写，手机显示待验收。
- 本机发布日志：/private/tmp/marketing-body-perf2-deploy.log；测试日志：/private/tmp/marketing-body-perf2-tests.log。
