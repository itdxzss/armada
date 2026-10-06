# 新群模式自动追加群名序号

- 日期 / 分支 / 工作目录：2026-10-05 / `1.0.3-snapshot` / 主仓库 `armada`。
- 用户要求：前端不增加配置或预览，后台自动追加数字；主仓库最小改动，遵守 AGENTS 与编码规范。
- 状态：本地实现与定向验证完成；相关回归存在已复现的两项原有错误；未提交、未部署。

## 设计与边界

- 首次正式提交 NEW_GROUP 时，用基础群名加 `-` 加执行行 `seq`，在同一事务保存到现有 `group_subject`。不增加配置、表、列、API 字段或 Redis 计数器。
- 编号依赖已有执行行顺序，允许缺号；不按成功数量、并发完成顺序或剩余行数重编号。
- 建群准备优先复用已冻结名称；资料命令和真实元数据核验继续使用同一名称。
- 历史已提交任务不补编号，尚未建群且没有冻结名称时仍使用原基础名；历史草稿首次正式提交适用新规则。
- 总长沿用现有 Java/前端字符串长度 100 限制，包含分隔符和实际序号；超长抛业务错误并回滚，禁止截断。群链接和资源池模式不变。
- 新命名只落在持有父任务锁的提交事务中；重复提交提前返回，草稿增删共用父任务锁。重试和恢复不重算名称。
- 旧执行器会覆盖或拒绝新带编号名称，回滚应用前应暂停受影响新任务；保留群 JID 和冻结名称，不重建群。

## 验证

- [x] 定向测试先红后绿：新群提交、重复提交、十群编号、带数字基础名、缺号长度边界和事务回滚。
- [x] H2 真实 Mapper 与 Spring 事务验证名称持久化和租户/状态边界。
- [x] 建群重试保持名称；模拟元数据缺编号不能过门禁，匹配编号后可继续。
- [x] Web / Android 资料命令均使用冻结名称；历史任务与普通模式回归。
- [x] XML、差异检查与独立只读审查。

Java 17，Maven 离线执行，测试 JVM 参数：
`-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true`。
首次直接运行因本机 Mockito 自附加失败产生 8 个环境错误；显式加载已有 agent 后测试正常执行，没有安装依赖或修改 POM。

| 检查 | 结果 | 日志 |
| --- | --- | --- |
| 修改前新增定向场景 | 8 项，6 失败、2 通过，退出码 1；明确复现未编号、覆盖冻结名称及超长未拒绝 | `/private/tmp/new-group-name-sequence-red-agent.log` |
| 最小实现后同组场景 | 8 项全部通过，退出码 0 | `/private/tmp/new-group-name-sequence-green.log` |
| 6 类相关回归，含追加的 Mapper 守卫场景 | 94 项，92 通过、2 错误、0 跳过，退出码 1；本次新增/更新的 9 项定向场景全部通过 | `/private/tmp/new-group-name-sequence-regression.log` |
| 修改前源文件的隔离基线复核 | 同样两项错误，退出码 1 | `/private/tmp/new-group-name-sequence-baseline-check.log` |
| `xmllint --noout`、本次文件 `git diff --check` | 退出码 0 | 本地工具输出 |

回归选择器：`PullTaskStandardCreateServiceTest,PullTaskGroupCreateTransactionIntegrationTest,PullTaskGroupProfileCommandContractTest,PullTaskGroupCreateProcessorTest,PullTaskStandardDraftServicePlanTest,PullTaskGroupSettingsApplyPayloadTest`。
命令为 `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o -q '-DargLine=<上述测试 JVM 参数>' '-Dtest=<上述选择器>' test`。

两项原有错误均属于 `PullTaskStandardCreateServiceTest`，以修改前保存的文件覆盖临时源码副本后独立复现，未回退主仓库文件：
- `newGroupModeAllowsNoManagerGroupAndPersistsNullSnapshot`：生产校验仍要求管理分组，抛出“管理、拉手账号分组不能为空”。
- `linkModesStillRejectNoManagerGroupWithoutFreezingDraft`：同一用例二次建草稿的重复序号触发 H2 唯一键冲突。

隔离基线：`/private/tmp/new-group-name-sequence-baseline-check-20261005/armada-api`；本次涉及文件的修改前快照：`/private/tmp/new-group-name-sequence-baseline-20261005`。
以上两项不属于编号逻辑，本次保留原有在途改动，不扩大修复范围。

## 发布

未提交、未推送、未部署；未连接远程数据库或 WhatsApp。协议命令契约测试使用本地替身，不代表真实业务验收。
