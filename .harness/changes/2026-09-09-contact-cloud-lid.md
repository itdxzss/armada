# 变更记录：通讯录任务自动准备云端 LID

- 分支 / 工作区：`1.0.3-snapshot`，主仓库 `armada`、`wheel-saas-pure-web`。
- 需求：用户要求通讯录营销通过账号自己的云端 LID 名单发送，不再被旧通讯录快照阻塞；最初仅本地修改供复核；用户随后明确授权 commit/push 并部署第一套测试环境。
- 状态：本地实现及验证完成，用户已授权提交推送及 test1 前后端部署，发布验证进行中。

## 设计与范围

Android 账号启用时固定账号范围并进入 PREPARING。轮次事务内通过账号域 Service 申请云端名单，复用现有 CloudStatusAudienceService/Collector 的完整分页、代次、租约、过期和提交后异步执行机制。其缓存表 account_status_audience 保存的是原始云端 LID 候选集合，可复用于本任务，不执行 Status 隐私求交，也不据此推断双向好友关系。

采集复用当前账号域 ContactPort.cloudPage 查询接口（已有 Android HTTP 协议适配器）；本次不新建采集命令或改协议契约。发送继续走 outbox/Kafka。此选择取代旧 contact-lid 分析文档中尚未落地的 Kafka 刷新建议，复用当前已存在的正式采集能力。沿用完整采集最多 5000 个 LID、缓存有效期 24 小时的现有边界；不读取半份名单，不静默截断超过采集上限的结果。取得完整名单后才应用用户配置的任务每号发送上限。

完整名单就绪后按本任务每号上限固化收件人，手机号可空。准备中不增加发送轮次/重试，不被判为空任务完成；失败、空名单或账号不可用落终态并说明原因。旧任务的 SKIPPED 行不自动恢复，不扩大已固定的收件人范围。Web 账号沿用已有通讯录来源。

## 数据、API 与并发

复用已有表和 API，无新增列、Redis key 或依赖；账号 state 增加 PREPARING（现有 VARCHAR(16) 可容纳）。接口已有 state/stopReason，前端补展示。任务轮次持有任务行锁，名单固化与汇总在同一事务中完成，重复调度不重复展开；失败回滚不启动云端采集。

## 验证与待办

- [x] 无快照 Android 不再 SKIPPED 的回归测试先红后绿：旧编译产物实际失败 `expected PREPARING but was SKIPPED`。
- [x] 云端准备、失败、空集、去重、每号上限及名单冻结验证。
- [x] H2 真实 Mapper/租户插件/事务覆盖准备状态、幂等、回滚、任务收尾与并发。8 个新 H2 用例，包括账号域真实采集服务的提交后异步执行、回滚不触发查询，以及两个独立事务的行锁等待与去重。
- [x] 后端 20 类 / 152 项测试通过，0 失败、错误、跳过。范围包含 ContactTask*、ContactCloudAudienceResolutionTest、CloudStatusAudienceCollectorTest、AccountStatusAudienceH2Test 和 feed.task 下全部 3 类测试。未执行全仓测试。
- [x] 前端相关 116 项测试通过，tsc / vue-tsc、定向 ESLint、Vite 构建通过；Mapper xmllint 与两仓 git diff --check 通过。

后端命令使用本机 Maven 3.9.16，`-DargLine=-javaagent:<本机 byte-buddy-agent-1.14.19.jar>`，避免本机 Mockito 自附加不可用；没有修改项目构建参数。最终测试命令：`mvn -q -DargLine=... '-Dtest=ContactTask*Test,ContactCloudAudienceResolutionTest,CloudStatusAudienceCollectorTest,AccountStatusAudienceH2Test,com.armada.feed.task.**.*Test,com.armada.account.service.*Audience*Test' test`。

前端命令：`pnpm exec node --import tsx --test 'src/views/contact/hyperlink/**/*.test.ts'`、`pnpm exec tsc --noEmit`、`pnpm exec vue-tsc --noEmit --skipLibCheck`、对本次 3 个 Vue 文件执行 ESLint、`pnpm exec vite build --outDir /private/tmp/contact-cloud-web-dist`。

本地证据：`/private/tmp/contact-cloud-red.log`、`/private/tmp/contact-cloud-final-tests.log`、`/private/tmp/contact-cloud-web-tests.log`、`/private/tmp/contact-cloud-tsc.log`、`/private/tmp/contact-cloud-vue-tsc.log`、`/private/tmp/contact-cloud-lint.log`、`/private/tmp/contact-cloud-build.log`。

为执行真实 H2 收尾验证，原 Mapper 的 MySQL `IF` 改成等价 `CASE WHEN`；H2 与 MySQL InnoDB 的锁实现差异仍需部署前按环境验证。前端原有一项文案断言与页面不一致，本次统一为“0 表示发给全部联系人”后全绿。

## 复核入口

- `ContactTaskExpansionService`：Android 账号进入 PREPARING，Web 保持既有来源。
- `ContactTaskCloudPreparationService`：完整云端名单只固化一次，并持久化失败/空名单原因。
- `AccountMessagingAudienceServiceImpl.resolveContactAudience`：复用原始 LID 采集，不依赖具名联系人；新任务可以重新准备早于自身创建时间的历史采集失败，当次失败不循环重试。
- `ContactTaskRoundWorker` / `ContactTaskLifecycleWorker`：任务锁与准备阶段的收尾保护。
- 前端账号数据展示 PREPARING，且不把准备中的账号显示成无效。

## 部署与回滚

按用户后续授权提交推送并部署 test1；不主动创建真实发送任务。现有任务不修改。回退代码前应处理新 PREPARING 账号；旧版本不认识准备状态，不能直接用旧版本继续这些任务。

## 发布前复核

用户已授权 `1.0.3-snapshot` 当前前后端变更提交并部署 test1。复核任务锁、租户过滤、完整名单与一次性固化、失败终态及旧任务不自动重发，未发现阻断项。测试部署脚本首次回归被本地缺 JDK17 提前截断，补齐临时 JDK17 后重验；离线生产包测试缺少既有 prod/protocol/.env.example，与本次测试环境 --all 路径无关。
