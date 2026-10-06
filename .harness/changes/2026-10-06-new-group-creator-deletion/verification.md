# 本地验证与待验收事项

验证日期：2026-10-06。四个仓库均在独立 worktree，未提交、未推送、未部署；没有调用真实账号的删除、建群或查询接口，没有连接真实数据库。

## 真实执行结果

| 范围 | 结果 | 证据 |
|---|---|---|
| 后端最终专项回归 | 14 类、93 项通过，0 失败/错误/跳过；退出码 0 | `/private/tmp/creator-delete-final-focused-tests.log`，`focused-tests.txt` |
| 后端扩展回归 | 42 类共 413 项；最新逐类报告合并后 401 项通过、12 项既有基线失败，无跳过 | `test-results.json`，`/private/tmp/creator-delete-consolidated-tests.log` |
| 后端打包 | Java 17，`mvn -q -f armada-api/pom.xml -DskipTests package` 退出码 0 | `/private/tmp/creator-delete-backend-package.log` |
| 前端 | 72 项相关测试通过；tsc、vue-tsc、修改文件 ESLint、生产构建通过 | `/private/tmp/creator-delete-frontend-tests.log`、`creator-delete-web-build.log` |
| Android | gofmt、`go vet ./...`、`go build ./...` 通过；最终 9 个相关包的 `-race` 通过（controller 包没有测试） | `/private/tmp/armada-creator-delete-go-race-final.log` |
| Android 全量测试 | 仅 `pkg/noise` 的 8 项既有失败；未修改 HEAD 已复现 | `/private/tmp/armada-creator-delete-go-test.log`、`armada-creator-delete-noise-baseline.log` |
| Web 协议 | 全量 125 suites / 1,475 tests 通过；最后成员数量及会话保护修订后 3 suites / 42 tests 和 build 再次通过 | `/private/tmp/armada-creator-delete-web-tests.log`，协议代理最终运行结果 |
| 静态检查 | 四仓 `git diff --check` 通过；后端 127 个 Mapper XML 解析通过 | 本地工具结果 |

后端扩展命令原始退出码为 1：除 12 项基线失败外，当时还发现旧 `PullTaskMapperInMemoryTest` 的 `creation_mode` 测试列缺失。已补齐测试结构且保留原断言，随后 6 项全部通过，计入最终 93 项专项回归。上表 413 项是最新逐类报告的汇总，不宣称原始组合命令退出码为 0。

未修改后端基线 `2a69df1d` 位于 `/private/tmp/armada-creator-delete-baseline`。以下失败名称已逐项与基线报告集合比较一致：

- `PullTaskStandardCreateServiceTest`：2 项（旧测试期待无管理分组；另一个测试重复插入同一执行行唯一键）。
- `PullTaskStandardSettingWriterTest`：1 项（旧测试期待无管理分组）。
- `PullTaskExecutionEndToEndIntegrationTest`：3 个参数化方法、共 9 项旧断言失败。当前与基线均为 9 failures / 0 errors。

基线日志：`/private/tmp/creator-delete-baseline-tests.log`、`creator-delete-baseline-e2e-tests.log`。没有修改生产逻辑以迁就这些基线断言，也没有把它们计为通过。

## 关键覆盖

| 要求 | 已执行的主要测试 |
|---|---|
| 默认关闭、旧请求兼容、模式约束、独立旧退群配置 | `PullTaskStandardCreateDTOTest`、`PullTaskNewGroupModeValidatorTest`、`PullTaskStandardCreateServiceTest.creatorDeletionFlagPersistsIndependentlyAndCannotBeChangedByResubmission` |
| 草稿保存、回显、详情、启动冻结及并发启动 | 前端 72 项；`PullTaskCreatorDeletionConfigH2Test`；创建、草稿与详情原有回归 |
| 实时权限、账号在线、已有 SUCCESS 不绕过 | `PullTaskCreatorDeletionManagerGateTest`、`PullTaskCreatorDeletionProofTest`、`PullTaskCreatorDeletionProcessorTest` |
| 真创建者冻结、账号换号、管理号变更 | `PullTaskCreatorDeletionTransactionServiceTest` |
| 同号/跨租户原子预留、其他业务依赖、别名状态与恢复 | `AccountCreatorDeletionServiceInMemoryTest` 12 项；`PullTaskGroupAccountMapperInMemoryTest` |
| 命令入队、已入队发布与旧角色重新占用阻断 | `ProtocolCommandOutboxServiceImplTest`、`ProtocolCommandPublisherTest`、真实角色 Mapper/H2 测试 |
| 并发/重启/超时/重复调度不重发；UNKNOWN立即暂停；停止后禁止 POST | 流程测试 29 项；Android 持久化、授权与服务测试；`PullTaskCreatorDeletionDispatchServiceTest` |
| ACCEPTED 未清理不拉人；证据齐全才放行 | `PullTaskCreatorDeletionProofTest`、事务/处理器/账本 H2 测试；下游 4/5/6/7/8/10 阶段网关测试 |
| GET cleanupComplete=false 不当失败；身份/operationId/响应严格匹配 | `CreatorAccountDeletionAdapterTest` 10 项；Android/Web proof 测试 |
| 注销后不重连、不复用、不末尾退群 | 账号生命周期 H2、状态事件回归、`PullTaskCreatorLeaveProcessorTest` |
| 租户隔离、迁移默认值、唯一约束、规范化索引 | 配置与生命周期真实租户 Mapper/H2 测试；`PullTaskCreatorDeletionMigrationTest` 2 项 |

后端测试使用真实 H2/MyBatis/事务验证持久化和并发边界；协议网络用本地 HTTP 测试服务器或测试替身，不发送真实删除 IQ。模拟证明只能验证代码门槛，不能替代真实业务验收。

## 重跑后端专项

在后端 worktree，使用本机 Java 17 和已有依赖运行：

```sh
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
mvn -q -f armada-api/pom.xml \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  -Dtest="$(cat .harness/changes/2026-10-06-new-group-creator-deletion/focused-tests.txt)" test
```

这里显式加载已安装的 Byte Buddy agent，解决本机沙箱禁止 JVM 动态 attach 的限制。不要直接无筛选运行现有全部 `*DbTest`：部分旧测试会加载真实数据源；本轮只执行明确的本地测试类。

## 尚未验证与待部署

- 未实际运行 MySQL Flyway V211/V212。迁移测试已执行 DDL/默认值/唯一键/派生号码表达式的 H2 等价验证，并检查 MySQL 动态 DDL 守卫；这不是 MySQL 迁移验收。
- 未实测 MySQL InnoDB 的隔离级别、锁范围、索引执行计划和部署时加列/建索引耗时。上线前需在第二套环境验证同号并发及跨任务占用。
- 未进行浏览器到后端的真实联调，未执行新的真实账号注销、创建者清理或后续拉人业务验收。
- 服务端任务签名密钥尚未在环境配置；四个服务版本及数据库迁移均待部署。
- 第二套环境操作顺序与验收证据要求见 `perf2-acceptance.md`。真实永久注销账号须由业务人员事先明确批准；本次未替用户选择。
