# 变更记录：iOS 原生全参登录

- 日期 / 分支 / worktree: 2026-08-31 / `1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户要求保留全参导入的完整 iOS JSON，通过现有 Android/Zhuan 原生主设备服务登录；不新增 Kafka topic
- 状态: 本地实现与定向验证完成，待测试环境实号验收

## 目标（一句话）

让苹果个人/商业全参凭据以独立运行时格式 `IOS_NATIVE_FULL(4)` 保存，并通过现有账号生命周期 topic 以 `format=ios_native_full` 交给 Zhuan 原生登录链路。

## 缺口拆解 / 任务清单

- [x] Armada 按设备系统识别 iOS 全参，完整保存，不转换为六段
- [x] Armada 在现有 lifecycle topic 下发 `ios_native_full`，不复制凭据到 outbox 引用载荷
- [x] Zhuan 解码并校验 iOS 原生凭据，复用现有原生主设备登录服务
- [x] Zhuan 放行 `IOS` / `PLATFORM_11` 实例创建并使用传入 registration ID、signed pre-key、routing info
- [x] 定向测试、编译与跨仓契约核对通过

## 关键设计决策

- 不新增 Kafka topic：继续使用既有账号生命周期命令 topic，以 payload `format` 做凭据分派。
- `import_format=PARAMS(3)` 是用户导入形式；`cred_format=IOS_NATIVE_FULL(4)` 是运行时凭据语义，两者分离。
- `protocol_id=ANDROID` 在当前系统表示原生主设备服务技术路由，不代表导入设备一定是 Android。
- iOS JSON 必须完整保存与传递；禁止降级转换成六段，避免丢失 registration ID、signed pre-key 与 routing info。
- `smb_ios` 只表示商业客户端；蓝标仍由独立 verified-level 证据识别。
- 滚动发布必须先升级全部 Zhuan fleet，再升级 Armada；旧 Zhuan 会把未知 `ios_native_full` 当永久错误提交，不能先让上游产生新格式命令。

## 验证（evidence-before-done）

- Armada `mvn -DskipTests test-compile`：通过。
- Armada `mvn -Dtest=AccountImportParserTest,AccountImportRowWriterTest,AccountOnlineCommandServiceImplTest,ProtocolCommandPublisherTest,AccountHyperlinkCandidateMapperH2Test test`：113 tests，0 failures/errors。
- Armada `xmllint --noout armada-api/src/main/resources/mapper/account/AccountMapper.xml`：通过。
- Zhuan iOS native/六段定向测试：`internal/armada`、`api/service`、`internal/service/app`、`internal/service/axolotl`、`internal/service/axolotl/store` 全部通过。
- Zhuan 上述 5 个改动包全量测试：通过。Signal 凭据轮换的 SQLite 成功/回滚用例通过。
- Zhuan `go vet ./...`、`go build ./...`、`go test ./... -run '^$'`：通过；Axolotl/store `-race` 通过。
- Zhuan `go test ./... -count=1`：本次改动包及其他包通过，仅既有 `pkg/noise` 基线失败（固定 vector 不一致且缺 `vectors.txt`）。
- 两仓 `git diff --check`：通过。用户样例的手机号、LID、UUID、数字 ID、姓名和密钥片段在改动文件中均无命中，测试只使用合成 fixture。
- 只读复审：无 P0/P1 阻断；初始化 panic 明确返错，Signal 身份轮换在单事务内清理/重建，提交成功后才刷新缓存，联系人保留。

## 部署

- commit / 环境 / 部署后验证结果: 未提交、未部署

## 遗留 / 跟进

- 使用测试环境实号验证苹果个人与苹果商业登录；蓝标识别另按独立字段观察。
- `AccountImportServiceImplDbTest` 本机真库连接不可用，仅完成 testCompile；待可用测试库补跑。
- Signal 轮换已有 SQLite 事务回滚测试，待测试环境补做真实 MySQL 表引擎/提交故障验证。
