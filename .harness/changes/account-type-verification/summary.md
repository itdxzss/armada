# 变更记录：WhatsApp 账号类型上线校验

- 日期 / 分支 / worktree: 2026-08-30 / `1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户确认采用“导入申报 + ONLINE 后轻量校验”的混合方案
- 状态: 代码完成，待部署与实号灰度验证

## 目标（一句话）

首次上线使用导入申报的设备系统与账号类型，ONLINE 后由协议一次性确认或纠正个人号/商业号；蓝标独立识别。

## 缺口拆解 / 任务清单

- [x] 增加账号申报类型和校验状态字段并迁移存量数据
- [x] 上线命令增加检测开关和凭据版本
- [x] Armada 消费并幂等落库 `account.type_detected`
- [x] Web 补齐可靠事件和错误不误判规则
- [x] Android 补 IQ 响应解析、有界异步检测、持久去重和事件回传
- [x] 前端取消个人号默认值并展示校验状态
- [x] 上线命令透传 `deviceOs`，实验开放 Android/iOS × 个人/商业四种主设备组合
- [x] 独立解析 `verified_level=high`，不再把认证名称误当蓝标
- [x] 完成聚焦测试、编译门禁和脱敏实号验证说明

## 关键设计决策

- 导入申报不能取消：Android 首次握手前已经需要个人/商业客户端参数。
- `declared_account_type` 保存来源事实，`account_type` 作为当前有效业务类型；协议只在可靠结果下纠正后者。
- 检测失败返回 UNKNOWN，不得按个人号处理。
- 类型事件复用账号状态 Topic，并作为可靠事件消费，避免为一个事件引入未被 Armada 管理的通用 Topic。
- 存量账号不自动批量检测，防止部署后产生突发查询。
- Android `w:biz v=116` 增加 `accounttypedetectionenabled` 灰度开关，默认关闭；Web 不受该开关影响。
- iOS 六段/全参采用 `IOS` / `PLATFORM_11` 原生枚举与集中维护的实验机型画像；只先部署测试环境实号验证。
- 蓝标只认明确的 `verified_level=high`；`verifiedName`、商业名称和 profile 存在性都不能作为蓝标证据。
- 商业认证级别使用独立列，`NULL` 表示未确认，1 表示 HIGH 蓝标，2 表示明确非 HIGH。
- `UNKNOWN` 的原子 SQL 不更新 `account_type`，避免并发时覆盖另一条可靠纠正结果。

## 验证（evidence-before-done）

- Armada 聚焦测试通过：`mvn -q -Dtest=AccountTypeVerificationMigrationSqlTest,AccountImportRowWriterTest,PromotionAccountProvisionServiceImplTest,AccountOnlineCommandServiceImplTest,ProtocolCommandOutboxServiceImplTest,ProtocolCommandPublisherTest,ProtocolAccountEventConsumerTest,AccountTypeVerificationServiceImplTest,AccountTypeVerificationMapperH2Test test`。
- Armada XML 与编译打包通过：`xmllint --noout .../AccountMapper.xml`、`mvn -q -DskipTests package`。
- Web 聚焦 Jest 通过：4 suites / 126 tests；`npm run lint`、`npm run build` 通过。
- Android `go test -race ./internal/armada ./internal/service/node/nodes ./internal/configs`、`go vet ./...`、`go build ./...` 通过。
- iOS/蓝标增量验证：Android 平台矩阵、IQ 响应解析与 `go build ./...` 通过；Web 3 suites / 120 tests 与 TypeScript build 通过。
- Armada 增量聚焦测试与 `mvn -q -DskipTests package` 通过；前端 26 tests、typecheck、生产 build 通过。
- 前端聚焦测试通过：3 suites / 26 tests；`pnpm typecheck`、`pnpm build` 通过。
- Web 全量 Jest 的本次相关 103 个 suites / 1223 tests 通过；仅现有 traffic-dashboard 6 条监听测试因沙箱禁止绑定 `127.0.0.1` 报 `EPERM`。
- Android 全量 `go test ./...` 仅现有 `pkg/noise` 向量夹具/断言失败；本次涉及包全部通过。
- Armada 全量 `mvn test` 被现有真库 `PromotionCapiEventOutboxSchemaDbTest` 的不可用 MySQL 重试阻塞；聚焦测试和打包门禁均已完成。

## 提交与部署

- commit: 见 Armada、Web 协议、Android 协议和前端四仓本次 `feat` 提交。
- test1 部署预检在 Android fleet SSH banner 阶段超时后终止；未同步文件、未执行迁移、未重启任何远端服务。

## 遗留 / 跟进

- Android 响应形状需在测试协议机把 `accounttypedetectionenabled=true`，用已知个人号和商业号各验证一次；确认后再扩大节点范围。
- iOS 个人/商业当前仍是实验实现：需分别用一只真实 iOS 个人凭据和商业凭据验证握手成功率，再决定是否调整版本/机型画像。
- `.harness/wiki/数据模型.md` 由真实 MySQL `information_schema` 自动生成；当前未连接目标数据库，需在 V167 部署后运行生成器刷新，禁止手工改生成文件。
