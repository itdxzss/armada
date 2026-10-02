# 控台认证码异步创建与会话恢复

## 问题与范围
perf2 的配对创建实测超过 11 秒，前端默认 HTTP 超时为 10 秒。旧创建接口同步等待代理和 WhatsApp 出码；重试插入会话触发 active_phone 唯一约束，并被转成通用失败提示。
本次修改控台创建服务、会话查询 Mapper、独立后台执行器与账号导入弹窗。公开推广入口和 Web 协议实现不变。

## 行为
- 创建事务提交后提交有界后台线程池，立即返回 REQUESTING/sessionId；原状态轮询与 Kafka 出码/完成事件继续使用。
- 原租户、原用户、原号码的活动会话直接恢复。并发插入败方在事务回滚后查询胜方会话；其他用户/场景冲突不泄露会话，也不取消原会话。活动会话不能被重试静默更改账号分组。
- 后台恢复并清理租户线程上下文。队列拒绝明确结束会话，排队超期不再申请代理。协议 HTTP 超时/断连保留会话等待事件或到期回收；已受理后的本地写入失败不取消有效配对。
- 前端创建响应丢失后只查询原会话；状态查询连续失败显示“状态待确认”，保留原会话供继续查询。出码等候文案不再承诺几秒内完成。

## 数据与 API
- 无 schema、Flyway、Redis 变化，保留跨租户 active_phone 唯一约束。
- POST /api/account-pairing-sessions 返回结构不变，改为后台出码。
- 新增 GET /api/account-pairing-sessions?phone=...，返回原用户最近一次控台会话列表（0 或 1 条），同原接口权限并禁止缓存。
- 后台队列是进程内执行器，平滑关闭最多等待 70 秒；进程异常退出未执行的会话由已有 3 分钟初始期限与过期扫描回收，不盲目重放不确定的协议请求。

## 验证
- Java 17 配对聚焦测试，包含 H2 MySQL 模式真实 Mapper、生产租户插件、事务回滚、并发唯一约束等待与恢复；13 个测试类共 52 个测试通过，0 失败、0 错误。
- 前端类型检查、定向 ESLint、Vite 构建通过。
- 本机 Chrome、构建产物及本地 API 夹具，6 个浏览器场景通过，含超过 10 秒等待、创建响应丢失后只发一次 POST、轮询断网恢复、切换号码及旧响应隔离。
- Mapper XML 校验、接口文档测试通过。
- 最初 Mockito 自附加受环境限制，改用已安装的 Byte Buddy agent 显式启动测试，未安装依赖。

## 发布与回滚
2026-09-27 15:21（北京时间）已发布到 perf2，未 commit/push。
- 以线上 JAR 为基线，仅替换 5 个配对类和 1 个 Mapper；JDK 17 对线上依赖编译，字节码与本地测试版本一致。
- 后端镜像 armada-perf-backend:pairing-async-20260927，ID a0a0a00d68327f792480766d39f3d495a7bdfc785eebcb9f454d0bc7a1cf606b。
- 前端镜像 armada-perf-nginx:pairing-async-20260927，ID 08fc63c847c44b10d26f60aee74263535110a7443e932752f5e31decb2de79ab。
- 运行 JAR SHA-256：5cc008de3f21d284d5ace4f4ef1b2b82f4ae71f835a1d7b11ba036d5da4664cc。
- HTTP index SHA-256：4f4e6808ffdf168f2f83d139df6007abfd4c56f4a412129309b541d46d892646，与本地制品一致。
- 两个容器 RestartCount=0；全部运行环境变量、端口、数据挂载逐项一致。挂载列表按 Destination 排序后比较，避免将 Docker 返回顺序变化误报成配置变化。
- 初始发布探针误将正常 HTTP 401 判为失败；在确认接口返回 40104 后停止错误探针，修正后执行 finalize.py 完成制品及运行验证，未触发回滚。
- 使用真实浏览器登录态通过新 GET 恢复接口查询到原用户会话 6，状态 WAITING_CONFIRMATION；15:23:02 后台 control-pairing-1 线程确认该会话受理，说明异步出码路径实际生效。
- 备份 /home/app/armada-deploy/backups/20260927-pairing-async；发布 /home/app/armada-deploy/releases/20260927-pairing-async，各含 compose-image.json。备份包含原镜像身份及旧 JAR，敏感运行快照仅保留远端受限权限文件。

### 验收中新发现的后续阻塞
15:23:16 收到会话 6 的 pairing.completed，消费因 BusinessException「协议层未明确识别账号类型」失败；对应 armada.perf.protocol.pairing.events.v1.DLT 不存在，错误投递超时。本次未修改账号类型识别/配对完成落库或 Kafka Topic。不能将出码成功、部署成功等同于账号成功导入，需单独修复此完成事件契约问题。
前后端需配套发布以启用恢复查询。回滚仅还原本次列明文件中的改动，不覆盖工作区已有修改。

### 发布检查补充
- deploy-test.sh 的 perf2 --all --dry-run、shell 语法与 deploy-test.test.sh 通过；标准脚本会包含工作区所有在途改动，因此本次采用线上制品基线的定向增量，并保留当前 Compose 环境。
- package-prod.test.sh 仍因原仓库缺少 prod/scripts/inspect-production-host.sh 失败，本次不发布生产离线包。
- 线上静态资源浏览器回归的远程访问出现导航及按钮等待超时；本地同一制品 6/6 通过、真实登录态页面与新恢复 API 已另行验证，不能把远程回归超时报告成全部通过。
- 回滚命令（在远端 /home/app/armada-deploy）：docker compose --env-file .env -p armada-perf -f docker-compose.rds.yml -f backups/20260927-pairing-async/compose-image.json up -d --no-deps --no-build backend nginx。运行基线回滚后同步恢复备份 JAR 与 dist；不回退数据库结构。
