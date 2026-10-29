# 批量上线 RESERVED 混批真实代理不足复现

运行：`bash /private/tmp/armada-batch-proxy-audit/run.sh`

仅使用 Java 17、H2 MySQL 模式与本机已有 Surefire 依赖；无 Maven 联网、无 MySQL/Redis/协议访问，不启动 Spring Boot，不读取 .env，不改业务仓库。`ProtocolCommandDispatcherProperties.immediateEnabled=false`；dispatcher=null，执行器如果意外调用直接抛 AssertionError，不会派发。

## 结果

- `raw-xml-result.log`：加载全部当前原始 Mapper XML，1 个 RESERVED 账号 + 1 个健康账号 + 1 个空闲代理；入参 [1,2]、[2,1] 均 `accepted=0 failed=2`，真实 `IpProxyOptimisticAllocator` 抛 `暂无足够空闲代理: requested=2 allocated=1`；outbox0、代理最终仍空闲、两账号 login/desired 都 OFFLINE。两种入参均由当前生产 Mapper 按 id 排序，内部先处理1。
- trace证明真实 `IpProxyMapper.markUsingAndBind(accountId=1,proxyId=100)` 已尝试占用空闲代理；后续第二轮真实候选SELECT为空，异常发生于任何 `updateProxySnapshotsInternal` 和 `creatorDeletionCommandBlocked` 之前。缺陷关键是受限账号参与代理需求，尚未走到能逐项隔离的 outbox 门禁。
- `adapted-result.log`：健康2独立+1空闲代理 `accepted=1 failed=0`，DB仅2号入outbox并绑定；混批+1代理两种入参仍全失败；混批+2代理 `accepted=1 failed=1`，门禁识别1号并回滚后重提2号，最终仅2号入outbox并绑定。说明现有隔离依赖先有足够代理到达门禁。
- accepted只证明outbox本地入队，未执行真实上线，不证明WhatsApp在线。

## 真实组件与唯一SQL方言适配

当前源码重新编译到 `/private/tmp/.../classes`：`AccountBatchLifecycleServiceImpl`、`AccountOnlineCommandServiceImpl`、`IpProxyServiceImpl`、`IpProxyOptimisticAllocator`、`ProtocolCommandOutboxServiceImpl`、`ProtocolAccountCommandRejectedException`、`ProtocolCommandDispatchTrigger`、`MyBatisConfig`、`OnlineAttemptIdGenerator`，以及当前H2 schema/helpers。未修改的模型/接口依赖复用 target/classes，运行class路径把当前重编译class放在最前。

真实 `AccountMapper`、`AccountStateMapper`、`AccountCredentialMapper`、`IpProxyMapper`、`ProtocolCommandOutboxMapper`；生产 MyBatis-Plus tenant 插件；Spring `DataSourceTransactionManager` 和真实@Transactional代理。没有Mockito、没有mock Mapper、没有模拟代理不足返回值。Country/converter/detector/attempt-log等本路径不会调用的依赖为null，误调用会失败，未伪造行为。

**原始失败组完全不适配SQL。** 成功对照需要执行 MySQL `UPDATE ... JOIN` 快照语句，H2无法解析。仅对照组将 `AccountStateMapper.updateProxySnapshotsInternal` 在/tmp XML副本改写为按 account_id 的 CASE UPDATE，仍由真实 Mapper 和生产tenant插件执行。此语句仅更新 truth_ip、proxy_country、proxy_source、updated_at，账号集合与原语句一致。原文保存在 `snapshot-mapper-original.xml`，H2变体在 `snapshot-mapper-h2.xml`，完整副本在 `resources/audit/AccountStateMapper.xml`。`prepare-h2.py` 每次基于当前源码生成副本；其余语句保持原样。

原始不足组断言该快照方法根本没有执行，因此代理不足证据不依赖方言适配。成功组只证明此等价快照语义下完整服务链能入队；不声称生产 UPDATE JOIN 已在H2验证，更不声称真实MySQL锁/方言/协议业务验收。

## 断言

检查API受理/失败数量、真实不足错误文本、outbox目标、代理绑定/剩余空闲数、两账号 pending/desired 回滚、RESERVED不变、租户8账号不受影响、代理SELECT/CAS实际执行、异常前没有到达快照/outbox门禁。断言数以日志最后 `*_AUDIT_PASS` 为准。复现现有错误行为而通过的断言不是修复回归测试。

## 最小修复指向（未实施）

代理分配前统一识别所有不应上线身份（含 RESERVED）并返回typed逐项拒绝，外层现有隔离机制剔除它们后再提交健康账号；最终 outbox 门禁必须保留以防预检后竞态。当前预检只隐藏DELETING/DELETED，而RESERVED留到outbox再判断，导致此差距。可进一步让受限账号不进入desired/pending及代理副作用事务；不要用删门禁或忽略代理不足修复。
