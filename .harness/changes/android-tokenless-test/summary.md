# 安卓 CP-18 免配置测试（2026-09-16）

## 用户目标
安卓测试 APK 固定连接第一套 test1，无配置弹窗，无客户端注册令牌。

## 设计与范围
复用 device-registrations status/start/result 与现有单号许可、采购、短信、结果链。独立环境变量 ARMADA_DEVICE_REGISTRATION_TEST_CLIENTS_JSON 默认 []；显式映射 deviceId -> tenantId，仅完全不带 X-Registration-Token 的请求可用。正常令牌链保持不变，错误令牌、重复 header、重叠或多租户设备映射拒绝。设备 UUID 不是秘密或设备认证；用户明确要求私有自测，无令牌模式不得当通用分发认证方案。

客户端固定 https://ingest.65.2.123.53.nip.io，删除配置 UI、地址与 token 存储；保留本机 deviceID 和请求状态；start 补 providerId，与当前接口契约一致。沿用原生手机 execution_mode=2（历史名称 IOS_DEVICE），不触发 Cobalt；本轮不扩展平台统计类型。

## 影响
后端 DeviceRegistrationTokens / Config / AuthenticationFilter 与 compose 环境变量，客户端 RegistrationBridge 和构建输出 r2。无 DB/Redis/schema/API body 新增；现有 start 字段补齐。没有修改其他在途功能。

## 验证
新免令牌测试先因缺少构造参数编译失败，再补实现；聚焦鉴权、许可、服务、worker、H2 Mapper 共76项测试通过（包括15项真实H2 Mapper测试）；初次运行因沙箱无法动态attach Mockito agent失败，显式加载项目ByteBuddy 1.14.19 agent后全部通过。部署脚本语法及 deploy-test.test.sh 通过。APK 构建、签名、zipalign、classes9.dex + classes13.dex 差异检查和 22 项状态测试通过。

## 部署计划
第一套 test1，ubuntu@65.2.123.53:/home/app/armada-deploy；分支 1.0.3-snapshot，基线 3493794f + 当前未提交注册改动。只后端，需实际核对远程当前制品与本地差异。计划仅配置 CP-18 设备 3de41b90-4c72-4f90-99f3-50c9825d56b8。租户与单号许可需从获准的第一套当前配置/API核实，不能从示例值推断。没有执行采购、SMS或真实注册。

## 回滚
移除测试设备配置即可关闭免令牌入口；必要时回滚后端制品。APK 可重新安装 r1 同签名包，保留任务。r2 尚未远程部署或安装，服务端和 CP-18 的最终联调仍待执行。

## 本轮评审
按 expert-reviewer 检查专用身份解析、重复Header、未知设备、租户上下文清理与旧令牌兼容；没有新增全局 permitAll 或采购绕过。API start商家字段已修复，未涉及Mapper/SQL改动。测试设备UUID可被仿冒属用户要求的免令牌自测边界；线上配置与单号许可尚未验证，不声称远程已生效。

## 已部署（2026-09-16 18:19 上海）
用户明确确认部署。正式脚本 `--env test1 --be -y` 返回成功；运行 JAR SHA256 5d6c947a3632ed3a7526d5f48a6e2352e41db35c850712315dd0f5ac0e87878a，与上传制品一致。与部署前JAR对比仅注册配置/过滤器/身份映射相关4个class不同。旧JAR SHA256 0812009b755728379ff6ca37d5f762330faf93a11fc99343c092bd46e9efb17d，回滚备份 /home/app/armada-android-r2-backups/20260916-101623。

仅添加 CP-18 -> tenant 1 的免令牌身份，原IPA身份不变。通过已登录后台保存新许可 c24f0f38-8dd2-472c-9c3e-12e6b83adfea（美国187，0.88 USD，按当前价档商家，有效至19:19:27上海）；未点击取号。公网 status：Android 200/NOT_STARTED/无号码，IPA携原令牌200（旧任务SMS_NO_NUMBERS），未知设备401，Cache-Control=no-store。容器running，RestartCount=0。重启期间后台页面短暂502/500，重新加载后正常。

生产打包测试仍因既有缺失 prod/scripts/inspect-production-host.sh 失败，本次test1部署脚本测试通过；不把生产打包声称为通过。无commit/push，无采购或真实短信。APK上传/安装验证继续进行。

CP-18已通过MoreLogin上传r2并覆盖更新，系统显示“已安装应用”，启动到WhatsApp欢迎页。未点击“同意并继续”，按电脑操作工具法律协议确认要求等待用户确认。未清除应用数据、未卸载、未触发采购；不能将后端HTTP200等同手机端端到端成功。上传面板首次未显示进度导致重复提交同一r2文件，存在两次上传；内容一致，安装通过文件管理器精确选择 WA-personal-r2-test1.apk。

## 用户确认取号后的结果
用户在手机点击确认，CP-18创建任务14，请求c24f0f38-8dd2-472c-9c3e-12e6b83adfea。只读轮询观测6/15/22/34/46/50次，终态FAILED（state=8），failure_code=SMS_NO_NUMBERS_EXHAUSTED，phone/order均无，actual_cost为空。证明Android start已到控端并执行供应商采购请求；未取得号码，尚无短信或WhatsApp注册验收。未自动更换价档、新建许可或另购。

## r5 终态替换规则

任务16已取到号码并在25分钟后进入UNKNOWN / REGISTRATION_TIMEOUT。该状态不再自动执行，用户确认其应作为本次任务结束。后端允许控端为该设备签发精确关联旧requestId的新许可；旧任务、号码、订单、费用和失败原因原样保留。FAILED、CANCELLED同样允许显式新建；PURCHASE_RESULT_UNKNOWN等结果不明确状态继续阻止替换，避免重复采购。APK r5接受服务端关联的NOT_STARTED新许可，即使旧任务已有号码或已提交手机号；存在待回报注册结果时仍拒绝切换。新许可只在手机再次确认价格后开始，不自动补购。

## r5 部署与无采购验收（2026-09-16）

后端已用 `deploy-test.sh --env test1 --be -y` 部署到第一套环境，运行 JAR SHA256 为 `4b7b6eb9f1e6ba5132cf473b6a652f4d3b87edf95453f2fd91ac3d820023843f`。前端补齐相同终态判断后，用 `--env test1 --fe -y` 部署成功，环境标识保持“第一套环境”。

CP-18已上传并覆盖安装 `WA-personal-r5-test1.apk`，SHA256 `5964518b8cd40b0656df44d3779eaa16ea49ade465b5174bce40add4229630be`；客户端实际显示“新号注册 · 安卓测试版 r5”。管理端对任务16（UNKNOWN / REGISTRATION_TIMEOUT，已有号码、订单和0.88 USD费用）成功保存新许可 `feade18e-09f1-437f-b9d2-942cf92f9354`，`replaces_request_id` 精确指向旧请求 `d651415d-3273-428f-8c3e-90eb9fa14563`。r5领取后显示“确认切换新许可”，国家187、单价0.88 USD、仅购买1个号码。

停在最终“确认开始”之前，未点击采购确认。只读数据库复核仍只有任务16和14，新许可为NOT_STARTED、无号码、无订单，因此本轮未创建新注册任务、未产生新购号费用。后端注册相关102项测试、前端类型检查/构建与终态规则3项测试、APK状态41项及手机号回归13项均通过。未commit/push。
