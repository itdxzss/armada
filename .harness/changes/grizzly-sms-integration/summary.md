# 变更记录：Grizzly SMS 接入 Armada

- 日期：2026-09-15
- 当前分支：`1.0.3-snapshot`
- 当前工作区：`/Users/daishuaishuai/IdeaProjects/armada`
- 迁入主工作区时的基线：`5ce49e3362b6801419f61584882f94a03cf1f4c5`
- 原开发分支：`codex/grizzly-sms-integration-20260915`，开发基线 `267bb14cfc610353231f6ce8a1cf6318cbb275e7`。
- 需求来源：用户要求将接码平台调用写入 Armada；Cobalt Java 25 Docker 部署由另一个任务执行。
- 状态：本地适配层已完成，未部署，注册联调尚未验收

## 目标

在 Armada 中提供真实、可注入、经过协议和传输测试的 Grizzly 客户端，为后续 Cobalt 注册编排提供接码能力。

## 任务清单

- [x] 核对当前供应商文档与 Cobalt 交付形态。
- [x] 读取项目规范、既有 HTTP 实现，建立隔离工作区。
- [x] 先写测试，首次编译因缺少新客户端/配置模型失败。
- [x] 完成客户端及严格响应解析。
- [x] 配置默认关闭；专用 HTTP transport 禁自动重试、重定向及认证重发。
- [x] 定向测试与已有 HTTP 回归。
- [x] 独立代码评审与修复。

## 设计与影响

- 设计：[grizzly-sms-integration.md](../../../docs/business/grizzly-sms-integration.md)。
- 影响：`platform/sms/grizzly`、`boot/config`、共享错误码、应用配置。
- DB / Redis / 外部管理 API：无变更。
- Maven 新增 Spring Boot BOM 管理的 `httpclient5`，用于禁止有副作用的 GET 被 transport 自动重试；不升级 JDK 或框架。既有协议和 Facebook 客户端显式使用原 request factory。
- 查询和订单变更分别启用；密钥仅从服务器环境注入。
- 不对 `activationCancel` 时区或 `canGetAnotherSms` 的冲突描述作自动业务判断。
- 只有客户端能力，本次没有注册任务持久化和调度；没有完成 Grizzly → Cobalt → WhatsApp 实际联调。

## 验证

- RED：`JAVA_HOME=<本机 JDK17> mvn -q -Dtest=GrizzlySmsPropertiesTest test`，退出 1，缺少新 client/model/properties 类型。
- 首轮行为 RED：62 项测试，5 failures / 2 errors，捕捉金额精度、小金额科学记数、尾随 JSON、NO_KEY、国家单对象解析等问题；随后修正解析与参数编码测试断言。
- 增量行为 RED：58 项客户端测试，2 failures / 2 errors，捕捉购买响应 ID 与后续查询的约束不一致、可选空字段导致已购订单被拒绝的问题；随后修复。
- 最终 GREEN：使用本机 JDK 17 执行 `mvn -q -Dtest='Grizzly*Test,ProtocolConfigurationTest,ProtocolHttpExecutorTest,HttpFacebookCapiClientTest' test`，退出 **0**。
- 结果：**81 tests / 0 failures / 0 errors / 0 skipped**。其中 Grizzly 客户端 58、配置属性 5、Spring 装配及真实回环 HTTP 5；已有协议装配 1、HTTP 执行器 8、Facebook CAPI 4。
- 普通 sandbox 阻止回环端口绑定及 Mockito JVM attach；该轮环境错误不算通过。最终 GREEN 已在获准的测试权限下完整重跑。
- 最终测试报告：`armada-api/target/surefire-reports/TEST-*.xml`；本机日志 `/private/tmp/grizzly-integration-final.log`。
- 独立评审：发现订单 ID 接受域不一致（P2）和文档错误；修复后复核通过，无剩余必须修复的问题。未进行真实供应商或数据库调用。
- `git diff --check` 通过。没有 SQL、Flyway 或 Mapper 变更。
- 迁入主工作区后，基于 `5ce49e3` 重跑同一组回归：退出 **0**，**81 tests / 0 failures / 0 errors / 0 skipped**，全源编译通过。日志 `/private/tmp/grizzly-main-transfer/tests.log`，报告在主仓 `armada-api/target/surefire-reports/`。
- 搬运后核验：除本记录外，20 个文件 SHA-256 与源工作区一致；原有无关文件状态、Git 暂存区和 HEAD 均未改变。

## 部署与回滚

- Armada：按用户要求已将 3 个已有文件的补丁及 18 个新增文件迁入主工作区；迁入前检查无冲突，迁入后逐文件 SHA-256 与源代码一致。仅本记录补充迁移信息。
- 用户要求保留未提交差异供复核：未暂存、未 commit、未 push、未部署；主工作区其他任务在途文件保持原样。
- Cobalt：另一个任务“查找安卓和Web注册功能”已完成 test1 部署，服务器端核验资源 2 CPU / 2 GiB、Java 25、启动/持久化检查通过；仍为按需 CLI，未进行实号注册。依据同级工程 `cobalt-registration-service/evidence/test1-deployment-20260915.md`（09:27 Asia/Shanghai）。
- 回滚：关闭 `GRIZZLY_SMS_ENABLED` 与 `GRIZZLY_SMS_PURCHASES_ENABLED` 或回退本次代码；已发生的外部订单需单独核对处理。

## 遗留

- Cobalt 当前为交互式 CLI，无 HTTP 接口；需要稳定会话、幂等和异步验证码合同。
- 公开注册入口前实现 MySQL 订单/租户/预算/幂等，禁止让浏览器按任意 Grizzly 激活 ID 操作。
- 真实余额查询、取号、收码、注册和新进程登录尚未验收。
