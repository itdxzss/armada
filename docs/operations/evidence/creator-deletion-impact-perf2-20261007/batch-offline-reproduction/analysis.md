# 批量上下线是否连带健康账号：accounts[] 兼容入口复核

结论：**当前工作区补丁已经保护当前前端使用的 ids/query 编排入口，但 accounts[] / protocolBackend 入口仍绕过隔离编排。用真实 Mapper + Spring 事务 + H2 已复现：1 个注销受限账号足以使另 1 个正常账号的下线一并未受理。** 三种账本状态 RESERVED、DELETING、DELETED 均成立。不是受限账号自身不能下线这个问题。

本轮没有修改仓库业务代码/测试代码，没有访问现场或执行真实上下线。测试只写 `/private/tmp/creator-deletion-legacy-batch-h2`，H2 为本机纯合成数据。当前服务源码被编译到此目录；运行 Mapper XML 使用源码目录优先，不依赖target缓存版本。

## 真实调用链

- Controller 的 `/api/accounts/batch-offline`：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/controller/AccountController.java:245-251`。`request.hasAccounts()` 为true直接调 `accountOnlineCommandService.offlineBatchWithProtocolBackends`；false才进入 `accountBatchLifecycleService.offlineByIds`。上线相同结构在159-165。
- DTO：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/dto/AccountLifecycleBatchDTO.java:22-24,32-38`。非空accounts即true，items保留每号protocolBackend。该DTO旧注释还称accounts是“新前端”形式；Controller后来将其作为兼容分支，两者注释口径漂移。
- 直接命令方法：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java:378-404`。整个批次在 `@Transactional(rollbackFor=Exception.class)` 内，先loadAccounts，再全批desired=OFFLINE、取消旧pending ONLINE，最后批量入outbox。
- RESERVED：账号lookup仍可见 → 先写desired并取消旧上线 → outbox `ProtocolCommandOutboxServiceImpl.java:812-835` 收集受限ID并抛 `ProtocolAccountCommandRejectedException` → 整个直接命令事务回滚。健康账号的desired和旧pending ONLINE全部恢复，健康OFFLINE outbox不存在。
- DELETING/DELETED：`AccountMapper.xml:653-659`已隐藏身份 → `AccountOnlineCommandServiceImpl.java:444-458` `loadAccounts`识别缺失但仍属于列表目标者后抛同类拒绝。健康账号在写入前就被整批拦下。
- 正确隔离目前只在 `AccountBatchLifecycleServiceImpl.java:406-438`：捕获受限ID，等命令事务回滚后，剔除受限ID，重新提交其余账号。`accounts[]`分支没有进入该层。

## 隔离复现

运行：

```sh
python3 /private/tmp/creator-deletion-legacy-batch-h2/run-probe.py
```

- 驱动：`LegacyBatchOfflineProbe.java`，复用仓库 `AccountBatchLifecycleTransactionH2Test` schema/setup与事务代理代码。
- 真实组件：当前源码编译的 `AccountOnlineCommandServiceImpl`、`AccountBatchLifecycleServiceImpl`、`ProtocolCommandOutboxServiceImpl`、拒绝异常；真实Account/State/Credential/Outbox Mapper XML；生产租户插件；Spring DataSourceTransactionManager + TransactionInterceptor。
- 只有外部dispatcher、proxy等协作者为mock，以防真网络；**Mapper、SQL、事务未mock**。
- 两个本租户账号：1为受限、2为健康，另有外租户3作未变更对照。都有旧pending ONLINE。
- 正向对照的afterCommit dispatcher用**另一物理DB连接**观察outbox，证明健康命令在事务提交后可见。

精简实际输出（完整日志见 `result.txt`）：

```
legacyAccounts phase=RESERVED exception=ProtocolAccountCommandRejectedException
legacyAccounts protectedDesired=1 healthyDesired=1 healthyOldOnlineStatus=0 offlineOutbox=0 dispatches=0 foreignDesired=1
idsOrchestrator phase=RESERVED accepted=1 failed=1 healthyDesired=2 healthyOldOnlineStatus=4 offlineOutboxAccount=2 dispatchedAfterCommit=2
legacyAccounts phase=DELETING exception=ProtocolAccountCommandRejectedException
legacyAccounts protectedDesired=1 healthyDesired=1 healthyOldOnlineStatus=0 offlineOutbox=0 dispatches=0 foreignDesired=1
idsOrchestrator phase=DELETING accepted=1 failed=1 healthyDesired=2 healthyOldOnlineStatus=4 offlineOutboxAccount=2 dispatchedAfterCommit=2
legacyAccounts phase=DELETED exception=ProtocolAccountCommandRejectedException
legacyAccounts protectedDesired=1 healthyDesired=1 healthyOldOnlineStatus=0 offlineOutbox=0 dispatches=0 foreignDesired=1
idsOrchestrator phase=DELETED accepted=1 failed=1 healthyDesired=2 healthyOldOnlineStatus=4 offlineOutboxAccount=2 dispatchedAfterCommit=2
PASS
```

值：desired1=ONLINE，2=OFFLINE；outbox0=PENDING，4=CANCELED。这里只证明业务命令受理及事务隔离，不声称真实网络连接下线成功。

## 当前页面与测试覆盖

- 当前前端 `src/api/account.ts:420-458` 批量上下线传 `{ids}` 或query；未找到该页面用accounts形式。**因此这份复现不是当前页面混批仍普遍失败的证明，是兼容API的确定遗漏。**
- 现有 `AccountBatchLifecycleTransactionH2Test.java:119-148` 真实H2测试3种状态仅调用 `service.offlineByIds`，没有accounts路径。
- `AccountControllerTest.java:420-454` 对accounts离线路由只mock命令service并检查委派；上线228-262相同，无法覆盖真实事务连带失败。
- `AccountOnlineCommandServiceImplTest.java:941-980` 兼容离线测试主要证明protocolBackend透传和1000账号能力，Mapper为mock，不是受限+健康混批事务验收。

## 最小修复方案（未实施）

1. 为公共批量编排增加携带 `AccountLifecycleCommandItem` 的路径，accounts/ids/query均经过同一拒绝隔离流程；保留每个健康号的请求protocolBackend，不能简单丢弃传入协议映射。
2. 原命令事务仍保持先全批校验、原子回滚；编排只对明确“尚未入队”的 `ProtocolAccountCommandRejectedException`排除受限后再提交，其他未知错误不盲目重发。
3. Controller两个accounts分支接入编排，返回统一requested/accepted/failed/每项原因。受限账号仍可拒绝，健康账号必须独立入队。
4. 加对应真实H2用例覆盖三阶段 × accounts混批上/下线、显式WEB/ANDROID保持、全拒绝、拒绝账号顺序、并发新增预留；Controller测试验证不再绕编排。当前这次已验证的是下线，**兼容上线存在相同分流结构，但未用本驱动执行代理分配和上线全链路，不额外宣称上线H2已复现**。
5. 不需动历史注销状态、Redis、数据库schema，也不需放开被注销账号；仅后端路由/批量编排修复。现场部署版本和当前接口使用量由主审计确认。
