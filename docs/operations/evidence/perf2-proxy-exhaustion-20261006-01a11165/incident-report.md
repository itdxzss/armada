# perf2 批量登录代理耗尽排查

日期：2026-10-06。首次排查只读查询 perf2 的 Armada MySQL、后端日志、Android 协议日志与 Redis 注销记录。用户随后明确授权删除两账号并恢复代理，紧急修复见文末。用户截图对应浏览器“账号列表 | 第二套环境”，地址 3.110.124.52，与 perf2 环境配置一致。

## 结论

账号 1038、1736 已有协议侧注销 ACCEPTED 记录，受到持久化注销保护，登录实例创建被阻断。但 Armada 中两账号仍为正常账号，期望状态为在线，且没有 account_creator_deletion 对应记录。登录启动返回系统错误 1001 后，Android 生命周期执行器把它转换成 Code=302 / PROXY_FAILED。后端据此标记代理不可用并自动换代理重登，重复消耗代理池，最终导致截图中的批量登录整批分配失败。

## 现场证据

- 截图请求时间：2026-10-06 13:26:13.536 UTC（北京时间 21:26:13.536）。后端日志 traceId=c5655965264e4d78a1d6a460a688eb43，requested=282、submitted=265、accepted=0、skipped=17、failed=265，错误为 requested=16 allocated=7。
- 13:31:24 UTC 附近代理库存：总计 1000，均为“混合（不限国家）”，空闲 271、使用中 2、不可用 727。不可用中 723 条原因为“协议层上报当前代理不可用: PROXY_FAILED”，状态更新时间集中在 13:05:04—13:25:18 UTC；另 4 条为检测超时。
- 后续 13:35:04 UTC 快照：使用中 240、不可用 760、空闲 0。两次快照之间有其他现场操作及状态变化，不能视为静止库存。
- 13:00 UTC 后账号 1038、1736 共记录 918 次 PROXY_FAILED/rawCode=1001，涉及 770 个不同 proxy_id。不同账号的代理集合存在重叠，不能把分账号去重数直接相加。此数量也不代表 770 个代理经过独立网络检测失败。
- outbox 中两账号大量上线命令 source=proxy_failed_reonline，证实是后端自动恢复重复下发，而非仅重复显示诊断日志。
- 两账号 Armada 状态：account_state=2（正常）、login_state=2（离线）、desired_login_state=1（期望在线）、state_source=PROXY_FAILED、proxy_failure_count=0；account_creator_deletion 关联结果为空。
- Android 日志：13:34:00.384 UTC 账号 1736 完成 native login client profile resolved，13:34:00.385 即 lifecycle login start rejected，responseCode=1001；账号 1038 在 13:34:25.569—13:34:25.570 UTC 出现同样路径。凭据格式均为 six，platform=android。
- 精确查询 Redis `android-zhuan-perf:account-delete:<账号身份哈希>`：1038 的 operationId=perf2-account-delete-1038-20261005-ca6670c，1736 的 operationId=perf2-group-creator-delete-1736-20261006-ca6670c；两者 state=ACCEPTED、responseType=result、errorCode 为空、taskBinding=null。未读取或删除任何注销保护键。

## 代码因果链

1. Android `internal/accountdelete/store.go` 的 Blocked：存在记录且状态不是 REJECTED/NOT_SENT 时禁止登录，因此 ACCEPTED 必然阻断。
2. `internal/service/app/cache.go:150` 在创建实例时调用 checkPersistedAccountDeletion，返回 ErrAccountDeletionBlocked；`api/service/login.go:310` 将实例创建错误转换为 IncompleteSys，数值为 1001。这发生在 SetNetWorkProxy 和 WALogin 之前。
3. `internal/armada/executor.go:255` 将除参数错误外的登录启动失败统一转换为 Code=302，ReasonCode 保留 1001。`event.go` 将 302 映射为 PROXY_FAILED。原始 response.Msg 被丢弃，导致诊断表 raw_reason 为空。
4. Armada `ProxyFailedRecoveryCoordinator` 先 markFailedProxyUnavailable，再 reonlineAfterProxyFailure；状态事件及补偿扫描持续触发。当前恢复条件没有阻止这两个有协议注销记录、但无 Armada 注销账本的账号。
5. 代理不足使当前 onlineBatch 事务回滚，265 个账号的登录命令都未受理。requested=16/allocated=7 是内部国家策略分组的分配进度，不是全池容量。实时库存全部为混合池，本次不是某国代理配置不足。

## 处理方向与边界

### 为什么页面仍为正常，以及旧密钥为何被拒绝

继续核对代码确认：独立注销路径 `api/service/account_delete.go#persistDeletionResult` 只保存协议 Redis 结果并调用 CompleteAccountDeletion。后者停网、设置协议内存离线状态、清理协议凭据/会话/AppState 和缓存，但没有发布供 Armada 收敛账号生命周期的专用注销终态。两个现场记录 taskBinding=null，Armada 的 account_creator_deletion 也没有记录，因此业务侧注销保护未覆盖它们。

后续错误又被转换成 PROXY_FAILED；`AccountStateEventServiceImpl` 对这类事件只更新 login_state/state_source，不修改 account_state，因此原有 account_state=2（正常）一直保留。任务注销路径的 `AccountCreatorDeletionMapper.markOffline` 同样只写离线、停止上线意图和失效时间，没有更新 account_state；账号状态枚举也未定义独立“已注销”状态，展示与生命周期存在缺口。

旧密钥此次重登被拒绝的直接原因已明确：CreateWSApp 先检查按账号身份保存的注销标记，ACCEPTED 会返回 ErrAccountDeletionBlocked，发生在配置代理和调用 WALogin 之前。本次日志不是 WhatsApp 重新校验旧密钥后的结果，不能把该本地阻断当成对远端密钥有效性的独立验证。注销代码另有清理协议持久化身份、会话和 AppState 的步骤；本次没有删除注销标记、重新登录或重新注册。

先通过正常账号控制接口停止 1038、1736 的重登意图，再修正登录启动错误分类与跨层注销状态一致性；系统错误或注销拦截不能禁用代理。补充有界自动恢复和可脱敏的具体错误原因。历史被误标的代理须在保留注销保护的前提下检测并有控制地恢复，不能直接把所有不可用代理重置为空闲。

Redis ACCEPTED 与 result 证明存在成功受理的注销保护记录。本次未重新执行注销，也未重新验证 WhatsApp 账号删除后的外部行为。其余 rawCode=302 的网络故障不能一并判为误报。

## 用户授权后的紧急修复

- 2026-10-06 13:49:35.617 UTC（北京时间 21:49:35.617）在 armada_perf 执行一个事务：1038、1736 设置 desired_login_state=2、login_state=2、account_state=5、state_source=ACCOUNT_DELETED，写失效时间，并按现有 batchSoftDelete 语义设置 account.deleted_at。未物理删除审计或凭据行，未改动协议注销保护键。
- 两账号提交前无 dispatched_at，占用检查与状态复核使用行锁；待取消的未发布上线 outbox 为 0。第一轮执行因现场手动下线改变状态而被保护条件完全拦住，没有写入；重新核对离线状态后第二轮成功提交。
- 恢复 692 个代理。条件：tenant=1、未删除、当前不可用、未绑定账号或配对会话、last_check_error 是协议 PROXY_FAILED，且该代理最新 PROXY_FAILED 诊断来自 1038/1736、raw_code=1001。恢复为 IDLE 并清除本次错误检测状态，不伪造网络检测成功。
- 事务后库存：空闲 957、使用中 1、不可用 42。保留的 42 条中 34 条为其他协议失败、1 条代理检测超时、7 条 Read timed out。
- 13:50:34 UTC 独立连接回查：两个账号均已软删除、停止上线；修复时间之后新上线命令 0、新代理失败 0；库存保持 957/1/42。
- 修改前快照及结果：`emergency-repair-before-after-1791294590196622000.tsv`。该快照只含必要状态字段，不含密钥、代理密码或完整号码。

## 防复发代码补丁

Android 当前分支 1.0.3-snapshot，基线 commit=8d05bb1。只改动 5 个文件，补丁保存为 `proxy-failure-classification.patch`。注销保护错误通过专用 1011 分类进入 NEED_REAUTH，由 Armada 既有逻辑收敛为不可登录的解绑状态；普通登录准备系统错误进入 OFFLINE，不再虚构代理失败。真实网络 302 分类保持原逻辑。

验证：1001 回归用例在修复前实际失败（PROXY_FAILED，预期 OFFLINE），修复后通过；go vet ./...、go build ./... 通过；api/service 与 internal/armada 全包及 -race 通过。go test ./... 仅 pkg/noise 的 8 个向量用例失败，已从未修改 HEAD 导出对应包独立复现相同失败。部署脚本测试通过；生产离线打包测试因仓库缺少 inspect-production-host.sh 失败，与本次 perf2 Android 部署范围无关。

发布前核对远端三个生产源码 SHA256 与本地 HEAD 一致，保留旧镜像标签 `whatsapp-server-feature-android-zhuan:before-proxy-fix-20261006-01a11165` 及远端 `deploy/rollback/proxy-fix-20261006-01a11165/source-before.tar`。部署命令：`bash armada-deploy/deploy-test.sh --env perf2 --zhuan --yes`。同步统计仅传输本次 5 个文件；脚本退出码 0，Zhuan deployment 142 秒 SUCCESS，容器健康及 API 检查通过。后端、前端均 SKIPPED，test1 未操作；迁移器仅检查并跳过既有两项迁移，无新增 schema 变更。

发布镜像 SHA256：`cb33c778ce6f3d8d2b7abb89334df54307b2c9b673d6fa5e62fda1e4edf0fc59`。容器内 `/app/whatsapp-server` SHA256：`2f9b0943ddeeac87dbab7a5dac86e177ca3866acbf3cf1e2ada52f48216ca4be`。远端三处生产源码与本地补丁后文件哈希一致；协议容器于 13:54:53.074 UTC 启动，发布验证时 restartCount=0。

13:56:03 UTC（北京时间 21:56:03）发布后独立回查：1038、1736 仍为已软删、离线、desired_login_state=2，修复提交以来新上线命令 0、新代理失败 0。最新库存为空闲 954、使用中 1、不可用 44；不可用原因是其他协议 PROXY_FAILED 34、代理检测超时 1、Read timed out 8、WhatsApp CONNECT HTTP 504 1。库存总数和检测结果受其他现场操作继续影响；本次未将这类独立故障强行改为检测成功。

本次止损与协议分类补丁已落地，未执行真实账号重登验收，也未承诺恢复已注销的 WhatsApp 身份。通用的注销成功即时回写专用“已注销”展示状态仍属于后续生命周期完善范围；本次通过移除两个遗留账号及注销拦截终态分类封住此次故障链路。代码补丁未提交或推送 Git。
