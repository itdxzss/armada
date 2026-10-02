# iOS 原生注册（2026-09-16）

- 当前状态：2026-09-16 r6 已部署第一套 test1，V197、运行制品和公网 HTTPS 验证通过；指定商家 222、美国 187、单价 0.88、数量 1，新许可为 NOT_STARTED，待用户签名更新手机并确认开始。r5 的任务 5 因 SMS_NO_NUMBERS 失败且未分配号码，旧记录保留。
- r6 交付：见 `provider-222-retry.md`、`provider-222-delivery.json`；Java 聚焦测试 169 项、iOS 模拟器 71 项通过。本次部署没有发起有效 start，实际取号、收码和注册仍待手机试验。

## 初版部署记录（r4/r5，以下为历史证据）

- 需求与设计：`docs/business/ios-native-registration-20260916.md`。
- 范围：个人 IPA 单号注册；注册成功即结束。
- 复用现有 Grizzly、采购 worker、注册聚合；独立设备鉴权与 API。
- 数据：V196 增加执行模式、设备绑定和采购截止时间，支持无需导入分组的设备任务。
- 验证：聚焦 Maven 套件 61 项，错误/失败/跳过均为 0，见 `test-results.json`；真实 Mapper XML/H2/MyBatis-Plus 租户插件覆盖并发租约、唯一请求键和单号事务。
- 命令：`mvn -q -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='DeviceRegistrationServiceTest,DeviceRegistrationTokensTest,AccountRegistrationMapperH2Test,AccountRegistrationWorkerTest,AccountRegistrationAvailabilityTest,AccountRegistrationStateTest,DeviceIngestTokensTest' test`，退出 0。未加 javaagent 的首轮因本机 attach 限制失败；显式 agent 后红测试复现 Cobalt 错误调用与过期许可仍采购，实现后转绿。
- `python3 .harness/wiki/test_api_docs.py`：1 项通过；Mapper `xmllint --noout` 与 `git diff --check` 通过。
- 数据文档：复用正式 `gen_datamodel.py` 与现有元数据生成两张表的 V196 待部署预览（`refresh-schema.py`），明确标注未迁移远程库。H2 不能验证 MySQL InnoDB 及 PREPARE 的线上执行。
- 手机端：同级扩展仓库 `PersonalRegistration/`，当前 r4 共存包使用 `net.whatsapp.WhatsApp.armadareg`，模拟器 30 项离线检查通过；用户截图已显示新号注册配置页。入口显示与模拟器替身不证明短信发送和注册结果。模拟器测试应用已清理，恢复测试前关机状态。
- 人工边界复核：Controller→Service→Mapper；普通 SQL 租户过滤；手机任务不会进入 Cobalt、导入和上线；重复请求不增加采购次数；OTP/令牌不进入日志；注册入口默认无许可且开关关闭。原有在途修改保留。
- test1：当前工作区基线 `3493794f` 的未提交注册改动构建，运行 JAR SHA-256 `2f6e06a46ba37a15a65c838d66363dc2cf070e235633fb7b4e82026ec788f93f`。`deploy-test.sh --env test1 --be -y` 退出 0，既有 TLS 网关验证后平滑重载。Java 61 项、TLS/Compose 13 项、部署脚本与 API 文档检查通过。
- 公网：`https://ingest.65.2.123.53.nip.io` 的绑定设备 status 返回 `200/NOT_STARTED`；错误设备与无令牌为 401，错误 body 为 400，非 POST 为 405，未知路由及管理入口为 404；全部 no-store。证据见 `test1-runtime-20260916.json`、`test1-public-20260916.json`。
- 未执行：commit、push、真实采购/收码；本次部署没有操作手机安装或已有账号。
