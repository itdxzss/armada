# NEW_GROUP 群资料成功前置与随机拉人配置

- 日期：2026-10-03；分支：1.0.3-snapshot；主 checkout。
- 来源：用户要求先完整分析并确认故障，随后授权按方案修改代码。
- 状态：本地实现与本轮验证完成；用户已授权四仓提交并推送至origin/1.0.3-snapshot；未部署、未连接真库或 WhatsApp。

## 范围与契约

- NEW_GROUP 必填完整群名/简介，名称100内拒绝截断，禁文件名回退，开启资料并固定 BEFORE_PULL。
- 新群冻结 group_subject，完整资料走 group.profile.apply / pull_task_group_profile。
- Web/Android 使用 subject/avatar/description，相同 source/operation/failedItem；结果只代表协议执行事实。
- Android实际群资料接口补回Desc=GroupInfo.Description，修复“已查到简介但HTTP响应丢失”的问题。Web/Android回读均沿真实IQ查询路径；保留完整简介而不截断。
- 建群步骤4及6在收到结果后或等待超时后读取固定建群账号的实时 metadata；群名、简介、JID相符才CAS推进。查询在数据库事务外，完成时校验动作身份/执行版本/租约。UNKNOWN可以用真实资料确认业务前置，但不会伪造动作SUCCESS。
- 明确失败命令新群最多3次；未知不重发。核验超时不一致保留JID暂停，恢复后核验原群。
- 取消/暂停与资料入队通过执行行锁串行，禁止旧调度入队逃逸。
- V209以profile_verified_at/profile_verified_command_id记录必填资料回读事实，与步骤CAS一起提交；不从旧步骤伪造证据。历史REGISTER_GROUP缺证明回到原群资料核验步骤；料子命令提交前再次检查证明，缺失时暂停。
- NEW_GROUP单次料子1–3，early_call_count=0；interval_min沿用pull_interval_seconds字段，新增pull_interval_max_seconds；新群10–15秒，旧请求缺max按固定min。随机只在下一调用deadline冻结时采样。
- 间隔约束是同一群命令提交节奏；队列延迟及跨worker执行仍不保证实际WhatsApp两次调用必在10–15秒内，未引入分布式按群限流。
- 群链接模式保留可选资料与时机，不混入速拉或建群营销。

## API / 数据 / 发布

- 新增可选pullIntervalMaxSeconds，旧请求兼容；资料结果增加failedItem。
- V208仅新增max列并将历史值回填为min；保留NULL兼容切换期旧写入。无新表，不重复保存区间下限。
- V209新增两个nullable证据列，无历史成功回填；应用发布前需要在确认的目标环境执行正式迁移。本地只新增迁移文件。
- 协议与结果消费契约先就绪再启用新生产者；历史已拉人任务不回退、不重建。回滚暂停新任务，保留数据与新增列；旧执行器不能被视为支持随机区间。
- 本地未改动Web协议仓已有auth/account-manager/Baileys在途修改。

## 验证

| 层次 | 本轮结果 | 证据边界 |
| --- | --- | --- |
| 前端 | 157/157测试；tsc、vue-tsc、变更文件ESLint、Vite build通过 | 构建产物/tmp/armada-newgroup-frontend-dist；未做线上浏览器验收 |
| 后端聚焦 | 23类、238 tests、0失败/错误/跳过 | 真实H2 Mapper/事务覆盖gate、proof、取消/租约、旧回调、历史任务、随机区间与波次；协议边界使用替身 |
| 后端扩大回归 | 169类、1101 tests；1086通过、4失败、11错误、0跳过 | 排除DbTest/MySqlTest；15个失败/错误全部在独立未修改HEAD中复现；不是全仓绿灯 |
| Web协议 | 4 suites、111测试通过；生产tsc通过 | 实际生产parser/registry/executor与事件契约测试，不连接WhatsApp |
| Android协议 | internal/armada全包测试、profile race、go vet ./...、go build ./...通过；新增IQ→HTTP简介契约测试通过 | 本地loopback替身；全仓go test ./...仍受已有环境/无关noise测试问题影响 |

后端最终聚焦日志：`/tmp/armada-newgroup-final-focused.log`，结构化结果：
`/tmp/armada-newgroup-final-focused-results.json`。最终扩大回归日志：
`/tmp/armada-newgroup-final-regression.log`，结果：`/tmp/armada-newgroup-final-regression-results.json`。
独立HEAD基线证据：`/tmp/armada-newgroup-head-baseline.log`、
`/tmp/armada-newgroup-baseline-comparison.json`。

扩大回归遗留问题：GroupMarketingGroupMapperInMemory缺group_classification（1失败/7错误）、
PullTaskMapperInMemory缺creation_mode（4错误），LifecycleMapperInMemory旧FOR UPDATE断言（1失败）、
MapperBusinessCondition已有状态条件断言（1失败）、NormalLinkSchemaSelfTest旧表数量断言（1失败）。
与此次相关的资料设置Timing测试补齐两个测试Bean后8项已通过。最初图片测试在非headless JVM异常退出，
最终指定headless后7项通过且完整扩大回归已完成。

后端使用现有Maven3.9.16/JDK21，以`-o`离线运行，JVM参数为：
`-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true`。
扩大回归选择器：`PullTask*Test,!*DbTest,!*MySqlTest,ProtocolCommandOutboxServiceImplTest,ProtocolGroupEventConsumerTest,ProtocolGroupActionResultAdapterTest`。

前端完整命令、替身loader边界、日志见相邻前端仓`.harness/changes/new-group-profile-gate/summary.md`。
Stylelint因本地缺stylelint-config-standard未进入规则检查；没有修改CSS或安装依赖。
Web单独对既有master-consumer测试做严格类型检查仍有原有mock类型问题；该测试的运行结果通过，生产tsc通过。

H2仅证明本地Mapper/事务，协议stub不代表WhatsApp验收；真环境需要确认目标后执行。

## 目标环境确认后的业务验收

1. 先确认环境、协议版本和测试账号，再按实际account.protocol_id路由各验证一个Web、Android新群；不以浏览器标签猜协议。
2. 页面路径：任务中心 → 拉群任务 → 新建拉群任务 → 新群模式。填写可识别的完整群名及含中文、换行的简介；人数1–3，间隔10–15。检查保存后回显、DTO和配置行一致，旧群链接模式仍保留原可选设置。
3. 关联taskId/executionId/groupJid/actionId/commandId，保存资料outbox、协议执行结果和固定账号实时metadata证据。必须核对WhatsApp实际群名/简介，不能以HTTP成功或命令入队替代。
4. 验证profile_verified_at/profile_verified_command_id与建群步骤同事务写入；首个料子命令submitted_at必须晚于核验时间。注入资料失败、超时、错误简介、取消、旧回调和历史无证据任务，确认没有料子命令逃逸，也未自动重建群。
5. 对数个料子批次核对material数量1–3、early_call_count=0，以及同群命令提交与持久化next_dispatch_at/next_run_at；观察随机值覆盖不同整数间隔。另记录协议实际调用时间，区分调度抖动、队列等待与配置随机间隔；当前不承诺跨worker实际WhatsApp调用严格10–15秒。
6. 回归暂停/恢复、明确失败最多三次、UNKNOWN回读确认且不改写协议结果；验证隔离租户与旧群链接模式。失败时暂停新任务并保留群JID与证据，避免重新建群/盲目重发。

## 未完成的环境证据

- 未部署，未连接远程、真实数据库或WhatsApp；未做浏览器线上验收。
- Flyway V208/V209未在MySQL实际执行；H2 Mapper及迁移结构测试不能替代该层验证。
- 数据模型生成文档缺少本地/tmp/wheel_tables.tsv输入，尚未重新生成。
- Android metadata adapter当前stateAbnormal固定false，与Web异常群标记存在既有差异；本次对必填名称/简介/JID仍逐项比对，未扩大到异常群模型重构。

## 修改入口

- 前端仓：`wheel-saas-pure-web`，新群模式界面、表单策略、DTO及配置回显。
- 后端仓：`armada`，建群事务与Processor、专用profile outbox、结果消费、proof Mapper/V209、最终料子提交保护、区间调度/V208。
- Web协议仓：`armada-protocol`，group-profile executor、worker/master注册与失败结果契约。
- Android协议仓：`whatsapp-server-feature-android-zhuan`，profile命令注册/native sender/IQ结果，以及api/service/group.go的Desc返回。
- 本轮均在原1.0.3-snapshot分支主checkout中工作，没有切分支或清理其他在途修改。
