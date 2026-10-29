# 第二套超链两分钟超时与补位发布

2026-09-18 用户明确要求部署到第二套环境（perf2，3.110.124.52），范围为本次后端修改。主仓基线 6aa319cd，分支 1.0.3-snapshot，工作区有其他会话未完成修改。

## 制品范围

为避免混入其他改动，使用第二套正在运行且本地留存的既有 JAR 为基线，以 JDK 17 对该 JAR 的实际类和依赖重新编译本次 9 个 Java 源文件，替换 11 个 class 条目和 3 个 Mapper XML；其余每个 ZIP 条目内容均与基线相同。不是从干净 worktree 全量构建，也未发布当前脏工作区全量代码。

- 旧 JAR SHA256：1abc308dc1a35b4c5c15b084f425e4312533970269ca8e9b4e9fa8ccc8ea5e92。
- 新 JAR SHA256：874186af06db2d0e137ed0d91e8a918cb72086d23142adad88467202833f4c3a。
- 源文件、精确条目清单、构建脚本、制品和日志：/private/tmp/hyperlink-two-minute-release/。
- 字节码检查 MAX_WAIT_MS=120000，BANNED_GRACE_MS=120000。
- 仅后端；无数据库迁移、前端或协议层更新；未 commit/push。

## 发布与验证

- 已读取 deploy-test.sh --help，并执行 --env perf2 --be --dry-run。脚本语法及 deploy-test.test.sh 通过。既有 package-prod.test.sh 仍因缺少 prod/scripts/inspect-production-host.sh 失败；不涉及本次测试环境发布路径。
- expert-reviewer 检查本次状态 CAS、锁顺序、租户隔离、补位占用、迟到回执、防重复计数，无本次部署阻断项。
- 新 class 直接以旧部署 JAR 依赖编译成功；针对重组制品的 7 个相关测试类共 69 例全部通过（恢复、真实 H2、轮次、选号、协议未知结果、统计投影、租户行锁）。Mapper XML 校验通过。
- 上传前再次验证线上旧 SHA；在远端保留 armada-api/target/armada-api-before-hl-timeout-20260918.jar。按当前仓库 armada_start 的 perf2 参数执行 docker compose up -d --build --force-recreate --no-deps backend，退出 0。未同步其他部署配置。
- 远端磁盘及运行容器 /app/app.jar 与新 JAR SHA 一致。容器启动 2026-09-18T09:53:38.572105492Z（北京时间17:53:38），running，RestartCount=0；应用09:53:57 UTC启动完成。
- /api/account-groups 未登录 HTTP 401，业务码40104，路由正常。
- Flyway验证208个迁移条目，schema up to date，无新迁移；只读查最新版本205 success=1。
- 任务14原两条未知已被调度自动收口为 BANNED_SEND_RESULT_TIMEOUT，两个账号 in_flight_count 均为0。任务保持停止，未人为恢复或重发。等待指标投影后，任务 success_num=63、fail_num=3936，总数3999，未知为0。

## 既有问题与验收边界

营销小时统计仍存在 Unknown column task.task_type 报错；日志证据早于本次发布（2026-09-18T00:05:00.074Z），对应 Mapper 不在本次替换范围。此问题需另行修复，不能宣称所有调度无错误。

未创建真实 WhatsApp 发信任务。并发2/总使用10的补位在制品测试中通过，历史两条未知在线自动收口已验证；不把服务健康等同新任务端到端发送验收。
