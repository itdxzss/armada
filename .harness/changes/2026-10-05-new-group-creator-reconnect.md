# 变更记录：建群人重连后继续群资料步骤

- 日期 / 分支 / worktree: 2026-10-05 / 1.0.3-snapshot / armada、whatsapp-server-feature-android-zhuan 主仓库
- 需求来源: 用户要求“等待建群人上线后继续后续任务”，在主仓实施，不要过度设计。
- 状态: 已完成（本地实现与验证）

## 目标（一句话）

建群成功后短暂断线，保留同一群、角色和当前步骤；上线后核验并补齐群资料，再继续原任务。

## 缺口拆解 / 任务清单

- [x] 群资料步骤在建群人离线时使用已有调度延后，不提交命令、不置人工暂停。
- [x] 资料读取异常继续等待；可靠读回名称、简介一致才写入现有核验证据并推进。
- [x] UNKNOWN 读回缺项后复用动作、生成新命令，仅补名称/简介缺项，沿用三次上限。
- [x] 明确失败仍完整重试，以覆盖执行前离线造成所有配置均未应用的情况。
- [x] Android 群资料入口拒绝重连中实例，返回已有可重试离线结果。
- [x] 聚焦回归与独立复核。

## 关键设计决策

- 复用 next_run_at、动作、Outbox 与现有步骤，不增加表、状态或前端按钮。
- Outbox 引用只增加可选补写范围；实际设置值继续来自任务配置，wire 仍使用缺省字段不操作的契约。
- completeProfile 在补发前重新锁执行行，校验版本、租约、步骤、人工暂停和终态；补发后的执行行 CAS 失败抛异常回滚。
- 在途命令不补发，旧 commandId / attemptNo 不得覆盖新尝试；核验完成前不会放开料子拉取。
- 不恢复已结束的历史任务；本次只修改代码，未修改 perf2 数据。

## 验证（evidence-before-done）

- Java 17、显式 Byte Buddy agent：新增离线与读取失败场景在修改前 2 项断言失败，分别复现离线被放行和读取超时误置人工暂停。日志 `/private/tmp/newgroup-reconnect-red.log`。
- Java 最终回归 159 项通过，0 失败、0 错误、0 跳过，退出码 0；含真实 H2、Mapper XML、MyBatis-Plus 与 Spring 事务。日志 `/private/tmp/newgroup-reconnect-regression.log`。
- 首轮扩展检查额外发现 `PullTaskLifecycleMapperInMemoryTest.lifecycleMapperDoesNotUseExplicitForUpdate` 失败：原始 HEAD 的 Mapper 已含 `FOR UPDATE`，对应测试却断言全文不包含它。两文件与 HEAD 字节相同，本次未修改；证据 `/private/tmp/newgroup-reconnect-baseline-check.log`，原始测试日志 `/private/tmp/newgroup-reconnect-tests.log`（111 项，1 失败）。
- Android gofmt、go vet ./...、go build ./...、群资料定向测试和 internal/armada 全包通过。
- Android 全量仅 pkg/noise 8 项固定向量失败；将 HEAD 导出到临时目录也复现相同失败，属于现有基线问题。本机 Go 1.26.5，项目声明 1.25。日志 `/tmp/armada-group-profile-go-test.log`、`/tmp/armada-group-profile-noise-baseline.log`。
- 独立复核未发现阻塞问题；两仓 `git diff --check` 通过。

后端最终命令（在 `armada-api/` 执行）：

```sh
env JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
  mvn -o \
  -Dtest='PullTaskGroupCreateTransactionIntegrationTest,PullTaskGroupCreateProcessorTest,PullTaskGroupProfileDispatcherTest,PullTaskGroupProfileCommandContractTest,PullTaskGroupSettingsApplyPayloadTest,ProtocolCommandOutboxServiceImplTest,PullTaskGroupProfileVerificationMapperTest,ProtocolPullTaskGroupProfilePayloadSerializationTest,PullTaskGroupSettingsApplyResultTest,PullTaskGroupSettingsApplyTimingIntegrationTest,PullTaskBatchAddProcessorTest,PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest' \
  -DargLine='-Djava.awt.headless=true -javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

## 部署

- 未提交、未部署。未运行真实 WhatsApp 业务验收。

## 遗留 / 跟进

- 本地测试不能替代上线后的断线重连业务验证；数据库测试使用 H2，不代表已验证 MySQL 并发锁行为。
