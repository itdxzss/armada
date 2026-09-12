# 回复引用 test1 发布核验

2026-09-11，用户明确确认第一套 test1 后，四个主仓库当前 `1.0.3-snapshot` 工作目录配套发布完成。部署命令与随后只读深度检查均退出 0。未 commit/push，未创建真实发送任务。

## 发布范围与执行

使用 `armada-deploy/deploy-test.sh --env test1 --full -y`，未传 `--branch`，因此包含本次未提交实现。发布前后均核对 [release-source-manifest.json](release-source-manifest.json)：后端 2362、前端 797、Web 277、Android 703 个本地文件均未偏离评审版本。保留原有在途修改。

| 仓库 | 基线 commit | 结果 |
|---|---|---|
| armada | 9531fa12 | SUCCESS |
| wheel-saas-pure-web | 4ad4fc66 | SUCCESS |
| armada-protocol | d957f617 | SUCCESS |
| whatsapp-server-feature-android-zhuan | b1759daf | SUCCESS |

发布顺序：Web → Android coordinator + 3 nodes → 后端/前端。Android 先并行同步、预构建四机，全部构建成功后并发停止旧 node/callback，等待 16 秒 lease 释放，再并发启动并验证 3/3 节点注册 online。四机阶段共 123 秒。旧 lifecycle 排空检查通过，未跳过。

本地后端使用 Java 17；协议构建使用 Node 24；前端通过临时 PATH 选择 `npm run build`，沿用已验证的 node_modules，未使用 pnpm 11 重装。脚本语法、deploy-test.test.sh 与 dry-run 在发布前通过。生产离线包测试因既有 inspect-production-host.sh 缺失失败，未涉及本次 test1 路径。

## 实际核验

- **后端**：本地 JAR、远端 JAR 与运行容器 `/app/app.jar` 的 SHA-256 均为 `3ceafd4ca3038e2e3093e20ad08eee031a94c5fc18893ad638aa30c268fc9dbc`。后端与 Nginx running，重启计数 0；启动以来 ERROR 行 0，无启动/迁移/连接失败特征。
- **数据库**：Flyway V188 `script quoted reply`，success=1，installed_on 为 `2026-09-11 11:18:17`（UTC，即北京时间 19:18:17）；失败迁移数 0。运行容器所连 armada 库通过只读事务查询，确认 `quote_context_json JSON NULL`、`reply_fallback_reason VARCHAR(64) NULL`。未手工 ALTER。
- **前端**：运行 Nginx 内 553 个静态文件逐项摘要与本地 dist 一致；单独渲染环境标题的 platform-config.json 不参与原始摘要比较，标题已由部署脚本和真实页面验证为“第一套环境”。
- **Web**：远端 277 个源码/构建输入、137 个编译后 JS 摘要全部一致；master + 4 workers 全部 online、Node 24.16.0。间隔数分钟的两次观察 PID、启动时间与重启计数不变，readyz 和流量看板检查通过。
- **Android**：四机各核验 692 个源码/构建文件，0 差异（排除 fleet 编排与各机保留配置目录）。所有主容器 healthy、重启计数 0；记录了新镜像与运行二进制摘要，coordinator 验证 3/3 nodes online。
- **环境联通**：`deploy-test.sh --env test1 --check` 退出 0，Armada、Baileys、Zhuan、跨组件访问通过；message events、DLT、contact-sync events、DLT 四个 Kafka topic 均为预期 12 分区。
- **真实页面**：在已登录浏览器中新开独立窗口进入养群剧本；新建表单第一句显示无前文，第二句下拉包含“第 1 句 · 管理员：发布验证原句（未保存）”，选中后时间线和预览均显示正确原句及回复正文。页面提示原句失败仍按普通消息发送。草稿未保存，验证窗口已关闭，未发送任何 WhatsApp 消息。

核验数据：[后端/前端/数据库](test1-backend-verification.json)、[Web](test1-web-verification.json)、[Android](test1-android-verification.json)、[深度检查](test1-deep-check.log)。这些记录不包含凭据或业务消息内容。

## 实际限制

Android 各机缓存的浮动 `golang:1.26-bookworm` 基础镜像版本不同：coordinator/node2 为 Go 1.26.8，node1 为 1.26.5，node3 为 1.26.6。因此源码相同但二进制摘要并非全机一致；均已从本次源码重新构建且健康。未在本次发布额外改动基础镜像策略。

Compose 提示已有独立 device-ingest Nginx orphan，未清理；Kafka 检查器输出 TimeoutNegativeWarning，但四个 topic 的实际元数据与所有检查通过。

本轮验证发布、配置与页面，不代表成员手机收件验收。Web/Android 四种跨账号组合的真实引用呈现、点击定位和原句失败后的普通发送，仍待真实任务实测。回退必须先收敛含引用的任务及 outbox，并保留新增列及发送事实，见 [rollback.sql](rollback.sql)。
