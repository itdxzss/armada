# 批量上下线的正常账号隔离：聚焦补充

日期：2026-10-07。用户明确的验收边界是：注销相关账号自身可以受限，但不能拖累同批其他正常身份账号。本轮仍只分析，未修改业务代码、未部署、未修改现场数据、未执行真实上下线。四仓 HEAD 和 tracked diff 与本次审计基线一致。

## 当前结论

原来的“单个注销受限账号拖垮页面整批上下线”事故已修复，且本轮 perf2 运行制品核验确认隔离补丁已部署。依据本轮 07:23—07:30 的现场观察，没有证据显示注销保护仍在阻碍其他正常账号通过当前页面批量上下线。

此前用“仍有两个缺口”直接回答当前阻碍，混淆了已修事故与条件性代码缺口，现更正：下文 F1/F2 保留为潜在缺口，不列为当前上下线阻碍项。当前页面不走 accounts[] 兼容分支；代理不足场景虽已本地复现，但尚未证实在当前现场触发。两条 RESERVED 残留本身也不能证明其他正常账号受阻。

本轮现场快照中，2755 已软删；另一个未软删的 RESERVED 账号 2757 在数据库中为 ONLINE，页面上线编排会按已在线跳过，并不满足 F1 的“离线 RESERVED 进入代理分配”前提。其协议缓存与数据库在线状态不同是另一问题，不能混入正常账号批量受阻的归因。

| 路径 | 已验证的行为或条件性结果 | 证据性质 |
|---|---|---|
| 当前页面 ids 批量下线 | RESERVED / DELETING / DELETED 拒绝能单独隔离，正常账号命令提交 | 本地真实 Mapper、租户插件、Spring 事务、H2 三阶段对照 |
| 页面 ids 批量上线的特定条件 | 离线 RESERVED 被计入代理需求；代理只够正常账号时，正常账号一起失败 | 本地复现，见下面 F1；未证实当前现场触发，不列为当前阻碍 |
| accounts[] / protocolBackend 批量下线 | 三个阶段都会使健康账号一起回滚 | 本地真实 Mapper/H2 三阶段复现，见 F2 |
| accounts[] / protocolBackend 批量上线 | 同样绕过隔离编排，仍存在整批拒绝路径 | 当前源码调用链；本补充未执行该入口的完整上线测试 |
| 其他内部批次及无关账号 | 无全局注销开关；前序已提交内部批次不会被后续批次回滚 | 当前控制流；不承诺数据库、代理或网络自身故障不会影响账号 |

现场版本依据沿用本轮 07:23—07:30 北京时间的只读制品核验，见 [主报告](impact-analysis.md) 和 [后端制品证据](backend-runtime.json)。其中已有批量隔离补丁在 perf2 运行产物中，但它仍是工作区未提交补丁，不能等同 Git HEAD。此补充没有再次连接现场，也没有把本地复现表述为现场新事故。

## F1：上线前代理需求包含应被剔除的 RESERVED 账号

- 分类：条件性代码缺口，未确认当前现场触发；触发后的影响级别为 P1，不列为当前上下线阻碍项。
- 触发条件：受限账号仍处于 RESERVED、当前离线、具有凭据且通过普通前置条件；与健康账号混批，空闲代理够健康账号但不够混批全部账号。
- 预期：先单独报告受限账号，代理仅分配给健康账号，健康账号命令正常入队。
- 实际：受限账号未被提前识别，代理不足先抛普通业务异常，整段剩余内部批次计失败，无法进入专用注销拒绝的剔除重提分支。
- 因果链：`onlineSkipReason` 不查 RESERVED → `enqueueOnlineBatch` 加载账号/凭据并抢占 PENDING → 第 747 行分配整批代理 → 代理分配器报告不足 → `executeChunk` 的普通异常分支把剩余批次都计失败。最终注销门禁位于第 784 行调用的 outbox 路径，尚未执行。
- 文件：[候选分类](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java:346)、[代理分配顺序](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java:728)、[普通异常整段失败](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java:439)、[真实代理不足异常](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/resource/service/impl/IpProxyOptimisticAllocator.java:94)。
- 本地样本：2 号为健康账号，1 号为 RESERVED。只有 1 个代理时，2 号单独提交 accepted=1；两号混批的正反顺序均 accepted=0、failed=2，错误为 requested=2 / allocated=1，outbox=0。增加到 2 个代理后，混批 accepted=1、failed=1，只有健康账号入队并绑定代理。
- 范围：同一内部上线批次；当前线上样本是否满足触发条件须另行现场确认，不能据此认定所有当前批量上线均故障。
- 最小建议：受限身份识别前移到其他准备校验和代理分配之前，复用现有按账号拒绝异常及编排隔离；保留最终门禁。

## F2：accounts 请求绕过隔离编排

- 分类：当前页面未使用的 API 路径遗漏；触发后的影响级别为 P1，不列为当前页面上下线阻碍项。
- 触发条件：批量请求包含 `accounts[]`，其中至少一个身份受限，至少一个身份正常。
- 预期：与 ids / query 请求同样，单独报告受限项并提交健康项。
- 实际：Controller 直接调用事务命令服务；任一受限项导致整个事务回滚，外层没有剔除后重提健康账号。
- 因果链：`hasAccounts()` → `offlineBatchWithProtocolBackends` → `loadAccounts` 或最终 outbox 门禁抛专用账号拒绝 → 事务回滚 → Controller 不经过 `AccountBatchLifecycleServiceImpl.executeChunk`。
- 文件：[上线入口分流](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/controller/AccountController.java:160)、[下线入口分流](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/controller/AccountController.java:246)、[已有隔离循环](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java:406)。
- 本地样本：三阶段下线混批均抛 `ProtocolAccountCommandRejectedException`；健康账号 desired 仍 ONLINE，旧上线命令仍 PENDING，下线 outbox=0。相同数据走 ids 编排均 accepted=1 / failed=1，健康 desired=OFFLINE，旧上线命令 CANCELED，只有健康账号命令提交后触发测试派发器。
- 范围：兼容 API；当前前端实际发送 ids / query，不能说当前页面下线普遍有此问题。
- 最小建议：accounts 也走同一个分片、逐账号拒绝、重提编排，保留每号 `protocolBackend`，不能丢弃协议覆盖后简单转 ids。外层保持无事务，健康账号使用新事务重提。

## 修复与验收范围

1. 先补失败回归：1 受限 + 1 健康、三阶段、上线/下线、ids/query/accounts、两种顺序、正常账号所需代理刚好够。断言健康项 outbox、desired 状态和代理绑定，以及受限项无新副作用。
2. 只调整后端批量编排与早期资格判断；前端已有部分受理提示。该目标不需要清除注销记录、修改历史任务、清 Redis 或放开受限身份。
3. 保留最终 outbox 校验和现有专用拒绝后的安全重提。并发“早查后新预留、代理不足先抛错”仍须补测：对确认尚未入队且事务已回滚的准备失败，重新检查受限 ID，只有发现新增受限项才剔除重提。不得对发送结果不明的异常无条件重试；无需新增全局锁。
4. 后续若授权实施，部署范围为 Armada 后端；业务验收须确认混批中健康账号实际上下线，不能只凭 accepted 入队数量宣称成功。回滚代码保留所有必要注销门禁，历史状态本轮无需治理。

## 复现边界

驱动只在 `/private/tmp` 编译当前业务服务源码并运行合成 H2 数据，生产 Mapper 与租户插件参与真实 SQL/事务；真实协议、Kafka 和现场账号均未操作。

兼容下线的 6 组用例全部使用原始 Mapper XML。上线成功对照中，H2 不支持生产账号代理快照的 MySQL UPDATE JOIN，测试仅在临时 Mapper 副本将该语句改为等价 CASE UPDATE；代理分配器和其他 Mapper 保持原始实现。混批代理不足发生在该语句之前。此适配不证明 MySQL InnoDB 的锁等待、死锁或全部方言兼容性。

已补跑完全原始 XML 的上线混批两种入参顺序，34 项断言通过；确认代理不足发生前既未调用快照更新，也未到达 outbox 注销检查。方言适配的 4 组对照有 58 项断言通过。生产目标查询会按 ID 排序，因此反序入参并不改变内部处理顺序。这里的 PASS 表示成功复现现有行为，不是缺陷已修复。

材料存档：

- [下线复现输出](batch-offline-reproduction/result.txt)、[Java 驱动](batch-offline-reproduction/LegacyBatchOfflineProbe.java)、[执行脚本](batch-offline-reproduction/run-probe.py)。
- [上线原始 XML 输出](batch-online-reproduction/raw-xml-result.log)、[成功与失败对照](batch-online-reproduction/adapted-result.log)、[驱动](batch-online-reproduction/src/BatchProxyShortageProbe.java)、[执行脚本](batch-online-reproduction/run.sh)、[组件及方言说明](batch-online-reproduction/README.md)。

归档保留实际运行脚本，默认目录仍为其记录的 `/private/tmp` 路径，重跑时应保留该路径布局；依赖来自本地已有 Surefire 缓存，不安装或访问网络。未复制编译 class。两套复现脚本退出码均为 0。
