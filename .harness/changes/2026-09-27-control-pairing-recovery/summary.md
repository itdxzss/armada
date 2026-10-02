# 控台认证码配对恢复

## 范围
第二套环境 perf2 的控台认证码登录导号。前端和 Java 业务接口不变，无数据库结构、Redis 格式变化。

## 已定位问题
- Web 协议发布到 armada.perf.protocol.pairing.events.v1；Java 容器未注入配对 Topic/消费组，实际监听默认 protocol.pairing.events.v1。
- 协议生成配对码后仍保留普通上线 30 秒计时器，覆盖了等待用户确认的 90 秒期限。2026-09-27 14:04:25 建连接，14:04:27 出码，14:04:55 被 verify timeout 关闭（北京时间）。

## 调整
- Compose 传入配对 Topic/消费组；环境档案明确两者，部署启动从档案注入，并验证容器实际配置。perf2 使用专用 Topic 和 armada-perf-api-pairing-events 消费组；test1 默认值保持原值。
- Web 协议 armPromotionPairingTimeout 在出码后取消当前 socket 的 verify timer；生成码之前和后续重新建立连接仍有握手超时保护。
- 保留协议仓库原有未提交的 Athena 配对 ID 修复及后端其他在途改动。

## 验证
- 新增计时器回归：修复前 2 个用例失败、握手保护用例通过；修复后配对/心跳/事件/路由 4 套共 112 个测试通过。
- Node 24 TypeScript 构建通过。
- 部署 shell 语法、deploy-test.test.sh 通过；修复前新增 Topic 注入断言失败。
- package-prod.test.sh 因仓库缺少 prod/scripts/inspect-production-host.sh 失败；不属于本次修改，不影响 perf2 测试环境定向发布。
- 已人工按 expert-reviewer 维度核对：环境隔离、原有业务语义、状态计时交接和 diff 范围。

## 发布与回滚
2026-09-27 14:37（北京时间）已用现有 PEM 发布至 perf2。用户切换网络后 SSH 恢复。
- Java：备份目录 /home/app/armada-deploy/backups/20260927-control-pairing；仅在远端 Compose 与 .env 加入两项配对配置。保留原有 AUTH_SESSION_KEY_PREFIX，使用备份目录的 runtime-override.json 重建 backend。
- Java 镜像保持 sha256:77c4180b92215eed2b305c65360bce61881d49b768eb50c35f998f10dec3783a；程序断言运行环境与原容器相比只变更两项配对变量。运行状态 running，RestartCount=0。
- 14:36:59 启动日志确认订阅 armada.perf.protocol.pairing.events.v1；14:37:04 确认 armada-perf-api-pairing-events 获得 12 个分区。未登录 API 请求返回正常鉴权响应 40104。
- Web 协议：保留远端 a770ad065098f50b18137853476bca3c20b2a36f 基线及其现有其他改动，只替换 armPromotionPairingTimeout 的计时交接。远端 TypeScript 构建通过，仅激活 account-manager.js 及其 sourcemap。
- 协议源文件 SHA-256：9e7b4e04581c5416bbbe1de4ba1e94d1e87d160e9d1ed8e0d63a90cba788b05e。
- 协议运行 JS SHA-256：ca6f08e24882820e8597791d32612694142fb2f06bad2d0137d9fdb22a04a66a。
- protocol-master、protocol-worker-1 至 4 全部重载并 online，Node 24.16.0，8080 至 8084 的 /readyz 全部 HTTP 200。
- 协议备份目录 /home/ec2-user/armada-protocol/deploy-backups/20260927-control-pairing；包含原源文件、原 JS/map 和本次隔离构建物。回滚恢复这些原文件并重载对应 PM2 进程。
- 后端回滚恢复备份的 Compose/.env；继续保留原运行认证前缀和同一镜像，重建 backend。
真实主设备确认登录尚未验收，不以测试和构建替代。
