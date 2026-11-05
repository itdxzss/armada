# 个人 / WhatsApp Business 双类型注册

- 日期：2026-11-05；分支：codex/business-registration-20261105；基线 f9f42a5f2。
- 用户要求：核对 Business 包，保留个人版，单独补商业版，让 Armada 选择个人或商业后走对应注册逻辑。
- 状态：本地实现完成，验证记录见下；未迁移、未部署、未取号或真实发码。

## 需求与设计

现有个人注册由接码订单建立 SmsRegistration，再由 worker 调 Go 发码和验码。扩展同一业务入口，冻结 account_type=1（个人）或 2（商业），对应两个独立 profile ID；用户省略 body/accountType 时仍为个人，旧订单由迁移默认补 1。不增加兼容重载或重复的 worker/状态机。

Go 原生分析确认两种客户端都使用 /v2/code 和 /v2/register，差异为独立版本、SMB UA、token 与 vname。Armada 向统一 Go code-requests 传 is_business，由 Go 分流组装客户端材料。验码只传事务 ID，不能另换类型。此功能针对 Business 手机 App 账号，不是 Cloud API，也不代表官方认证商业号。

## 已完成

- [x] Business 26.34.72 包静态核对（见同级 Go doc/business-registration-evidence.md）。
- [x] Go 保留个人 2.26.36.74；Business 2.26.34.72 独立 profile、token/UA/证书；证书加密保存并跨重发/重启复用。
- [x] Armada 类型选择、配置、落库、worker 传参、旧订单缺省、同单改类型冲突。
- [x] 前端逐单选择，办理后展示固定类型，不展示未配置类型。
- [x] 新增 H2 迁移/冻结/业务流程、HTTP 合同和前端交互测试；个人路径回归。

## API / 配置 / 数据

- POST `/api/sms-activations/{id}/registration` 可选 body `{"accountType":1}` 或 `{"accountType":2}`。非 1/2 拒绝，已有订单换类型返回业务冲突，不占新代理。
- GET options 增加 `registrationAccountTypes: [1,2]`（根据租户配置过滤）；`registrationEnabled` 表示至少一种可用。
- 订单视图增加 `registrationAccountType`，未办理 null，办理后从记录读固定值。
- 原环境变量 `ARMADA_ACCOUNT_SMSREGISTRATION_TENANTS_<id>_PROFILEID` 保留个人版；增加 `..._BUSINESSPROFILEID`。允许只配一种，不允许同一 ID 同时配置两种。
- Go 请求 record 将原 5 参数调用收敛为 tenant/key/request，request.toString 脱敏。
- V228__sms_registration_account_type.sql：仅 sms_registration 加 NOT NULL DEFAULT 1 的 TINYINT。有 information_schema 防重入。Mapper 仅 insert 类型，transition 不更新类型或 profile。
- Redis：无变更。账号导入、首次登录和 ONLINE 承接：无变更。

## 验证

- TDD：Go TestBusinessPrepareUsesSeparateProfile 先因个人版限制失败，新增支持后通过；Java HTTP 测试先因缺 is_business 字段失败，新增合同后通过。
- 聚焦回归：注册 / 接码 / Go client / 配置 / 菜单共 69 tests，0 failures/errors（包含真实 H2 Mapper、租户插件、事务管理器、迁移 DDL）。真实 WhatsApp 与号商只在测试中替换为合成回执。
- API 文档生成测试通过。
- 全量离线回归：5688 tests，10 failures + 13 errors；同一基线 f9f42a5f 上复跑 13 个失败类（104 tests），相同 23 个失败方法全部复现，无新增失败。
- 默认全量命令会启动可选真库上下文，已停止（本地 localhost 连接未成功）；最终按源码排除 DbTestBase 派生类、SpringBootTest 和 MySQL 专用类共 95 个，未运行真库迁移。
- MySQL PREPARE/执行器与 InnoDB 锁行为未真库验收；H2 提取真实 V228 DDL 执行，并校验 guard 结构。
- `.harness/wiki/数据模型.md` 是真库元数据生成文件，本次不手工篡改；部署迁移后按原生成脚本刷新。API 新字段合同已在本记录维护，生成器测试通过。

## 人工评审

已检查依赖方向、Controller→Service→Mapper、租户过滤、字段冻结、重复点击和重放、独立配置、日志脱敏。未新增生产 mock、依赖、重试循环或任意平台回退；类型合法性在占代理前检查。跨租户、代理/验证码保护沿用已有测试。

## 发布与回滚边界

尚未发布。部署时需先执行 V228、更新 Go 与全部 Armada worker，再更新前端和商业 profile 配置，避免旧 worker 办理新商业任务。Go 商业示例默认关闭/UNVERIFIED，维护者核对后才显式启用。

回滚不删列或数据。先停自动注册推进，完成或明确处置在途商业事务，再回退应用；不能让不识别 account_type 的旧 worker 接手商业记录。保留 V228、profile 快照及 Go 身份密文，不能通过新建事务掩盖未知发码结果。

## 尚未验收

真实环境迁移、配置导入、真实发码/验码、动态抓包、注册后首登/重连。Business 证书内部字段采用参考实现和现有 protobuf，已验签/篡改测试，但原生生成函数全部条件未逐条还原。不得把离线合成 REGISTERED 当作真实注册成功。

## 合入前复核（2026-11-05）

按 expert-reviewer 检查完整生产 diff、调用方、H2 与 HTTP 测试证据；未发现阻断合入问题。Mapper XML 的 xmllint 校验通过。真实 MySQL 与 WhatsApp 接受性尚未验收，沿用上述发布边界。用户已要求将本次变更提交并推送到 origin/1.0.3-snapshot。
