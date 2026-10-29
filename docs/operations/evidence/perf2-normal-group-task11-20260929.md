# perf2 普通建群任务 11 只读诊断

查询时间：2026-09-29 北京时间约 23:09–23:14。用户明确确认 perf2 测试环境。

## 已确认事实

只读连接 perf2 后端并查询 `armada_perf`，数据库名有脚本断言，查询使用只读事务。任务归属 tenant_id=1。

- 任务 11 创建于北京时间 22:59:51.742，23:03:34.567 最终 FAILED，成功 0、失败 5。截图是尚未结束的中间状态。
- 5 个群明细 ID 为 45–49，均停在 PREPARING_CONTACTS，错误 NO_ELIGIBLE_PARTICIPANTS；create_command_id 和 group_jid 均为空，尚未下发建群命令。
- 每群冻结 40 个普通成员和 1 个次管理员，全部使用 Android 协议。
- 200 条普通成员关系对应 400 次双向联系人准备结果：

| 方向 | APP_STATE_NOT_READY | PROTOCOL_RESULT_UNCONFIRMED | ACCOUNT_NOT_ONLINE | SUCCESS |
|---|---:|---:|---:|---:|
| 群主保存普通成员 | 197 | 2 | 1 | 0 |
| 普通成员保存群主 | 195 | 5 | 0 | 0 |

- 每群双向成功的普通成员均为 0。
- 5 个次管理员与群主也均未双向成功；其 20 个方向状态为 18 FAILED、2 UNKNOWN，各行最后错误均为 APP_STATE_NOT_READY。次管理员仅保存最后错误，不能将其直接等同于每个方向的原始错误码。

## 代码对应关系

- 后端 `NormalGroupCreationProtocolResultService.contactSettled` 等所有联系人方向落定，调用 `hasEligibleParticipants`；普通成员或次管理员必须与群主双向 SUCCESS。零符合者直接失败，不创建 GROUP_CREATE 命令。
- Android `internal/armada/contact_save_sender.go:220` 在 `SendCreateContact` 前检查 `AppStateKeyAvailable`，ErrAppStateKeyNotFound 被映射为 APP_STATE_NOT_READY。
- `internal/service/app/appstate.go:62` 检查凭据归属并读取最新 AppState 同步密钥。`internal/service/appstate/store.go` 中密钥列表为空或凭据归属指纹不匹配均可返回该错误。

结论：主要直接故障是 Android 联系人同步 AppState 不可用，互加好友未成功，随后被后端参与者准入规则拦截。该错误不证明 WhatsApp 已拒绝建群，也不单独证明导入文件缺字段。

## 证据边界与处理方向

- Android 节点公网 SSH 被关闭，通过 perf2 后端访问该节点私网 SSH 也超时，未取得协议节点原始日志或持久化 AppState 现场证据。
- 尚不能区分导入缺失、状态未同步/未持久化、凭据归属变更隔离等更底层原因。页面建议重新导入完整凭据是通用错误文案，不是已经验证的修复方案。
- 先验证这些账号当前凭据对应的 AppState owner 和可用同步密钥，再据此恢复；恢复后先验证一对账号双向保存联系人成功，再重试小规模建群。
- UI 应显示主要原始错误和计数，避免仅呈现“没有可靠参与者”的汇总结果。

本次未修改业务代码、任务或账号状态，未重试任务、未部署。

## 第二轮深入诊断（北京时间约 23:24–23:29）

### Redis 现场已查实

通过已授权的 perf2 后端进程读取现有 Android Redis 配置，在服务器内对 task 11 的 54 个去重账号执行 GET/TTL。目标命名空间断言为 `android-zhuan-perf:`，不输出手机号、密钥内容、凭据、密码。

- 54/54 账号 AppState owner 存在，TTL=-1。
- 54/54 owner 与当前数据库六段凭据计算出的指纹一致。计算严格复用协议口径：33 字节公钥去首字节，identity public key + static public key + 大端 uint32(device=0)，再 SHA-256。最初诊断计算未去掉 33 字节公钥前缀，曾出现 5 条不匹配；规范化重算后全部匹配，因此不存在这 5 条凭据不一致的结论。
- 54/54 synckey 不存在，TTL=-2；并非仅空列表。
- 49 个普通成员/次管理员留有 critical_unblock_low version=1 的 hashstate 和 mutationMAC，TTL 均为 -1；5 个群主连这两类状态也不存在。历史 hashstate 的存在不能替代缺失的密钥。
- 54 个账号的 cred_format 均为 1（六段）；5 个群主于 9 月 18 日北京时间导入，其余于 8 月 10/14 日导入。抽查凭据 JSON 只有六段约定字段，不包含 AppState。

脱敏现场逐账号结果见同目录 `perf2-normal-group-task11-appstate-20260929.json`。该文件仅含账号 ID、布尔值、计数、TTL 和集合版本。

### 当前能力缺口与代码演变

1. `api/service/login.go:439` 六段登录固定为主设备 device=0，只映射六段字段。
2. `internal/service/app/appstate.go:680` 的缺密钥恢复分支对主设备直接返回并记录 recovery unavailable；不存在从同一份六段凭据恢复同步密钥的能力。
3. 当前生产代码写入 `StoreAppStateSyncKey` 的有效调用在收到 APP_STATE_SYNC_KEY_SHARE 的事件处理；登录 DTO/生命周期导入命令没有 AppState 导入字段。因而“重新导入完整凭据”是泛化提示，不能理解成当前界面重新上传同一份六段文件就能修复。
4. 本地提交 `0b1b36c`（2026-08-26）把 AppState 由 14 天 TTL 改为按凭据生命周期持久保存，删除缺 key 时本地随机生成并分享的旧逻辑，新增 APP_STATE_NOT_READY 前置检查。旧联系人发送器只检查 promise error，未完整检查 IQ 内错误。
5. 8 月历史任务 3–6 有建群成功和联系人 SUCCESS 记录，但这些旧记录不能单独证明真实联系人同步成功，也不能证明当前部署版本何时生效。

确定结论：本次阻断是当前持久化 AppState 同步密钥确实缺失；当前六段主设备路径不能补回这些密钥，而普通建群又要求双向联系人成功。恢复同一份六段文件或反复重试不解决该缺口。不能把缺失时刻或具体删除原因推断成已经证实。

### 同步观察到的新重试

第二轮只读查询中 task 11 已被其他操作重试：item 45 prepare_attempt_count=3，46–49=2，最后更新时间为 UTC 15:19:36–15:20:51；所有 create_attempt_count 仍为 0。最新 200 条普通成员关系的两个方向全部 FAILED/APP_STATE_NOT_READY（400 个方向）。此前 392/7/1 是首轮快照，二者对应不同尝试。本会话没有执行重试。

### 仍需补齐的历史证据

- 需要 Android 持久日志区分从未获得密钥、旧 TTL 到期、终态清理、换凭据隔离等历史事件。当前 Redis 快照本身不能回溯删除时间。
- 同一实例已知 SSM ID 为 `i-09bfc4466f34dca63`；本机配置的 AWS 会话均已过期，perf2 后端实例角色没有 SSM DescribeInstanceInformation 权限。没有修改 IAM、SSH 或网络。
- 按 signing-in-to-aws 技能要求，已向用户请求确认 `aws login --profile armada-rds-diag`，尚未得到回答，因此尚未发起登录。
- 已准备 `/private/tmp/task11-protocol-log-diagnostic.py`，用于 SSM 只读解析目标账号的 AppState 生命周期日志，仅输出白名单元数据。

## 第三轮：SSM 历史日志已完成（北京时间约 23:33–23:38）

用户授权并完成 AWS 登录后，身份验证成功；SSM 实例 `i-09bfc4466f34dca63` 为 Online，IP 172.31.40.84，与 perf2 目标一致。已解除上一节的认证阻塞。

- 当前 Android 容器 running，自 UTC 2026-09-19 12:22:58 持续运行，镜像 ID `sha256:c3da4a28a68e28858a9f65c60b7087429ada27ac66480801e02228fd157ad94c`。
- 持久化日志实际覆盖 2026-09-16 至 2026-09-29；源码轮转 MaxAge=14。以下日志时间为 UTC。
- 第一轮全量扫描匹配 81,596 条目标账号相关记录；第二轮兼容无 JSON 字段日志并按号码 SHA-256/accountRef 关联，输出仅含账号 ID、时间和白名单统计，不输出号码或密钥。

### 确定的历史事实

1. 49 个普通成员/次管理员自 UTC 9 月 17 日 08:14:11.936 起已出现 `app_state_key_missing`，现场日志含 device=0、recordCount=1、decodedCount=0、storedKeyIds 数量=0。
2. 第二轮累计 25427 次 missing_key，每条已持有密钥数量均为 0；一一对应 25427 次 `AppState key recovery is unavailable for primary-device credentials`。因此后台持续请求集合并没有恢复密钥。
3. 9 月 17 日这批次管理员已经发生 APP_STATE_NOT_READY 加好友失败（当天 69 次），9 月 18 日也有 46 次。这排除“9 月 28 日掉线事件或 9 月 29 日发布才引入本批旧成员缺密钥”的解释。
4. 5 个群主的首次 native login 日志都在 UTC 9 月 17 日 17:01:30，与导入时间吻合。当前日志没有其密钥分享/落库成功记录。本次加好友失败原始 error 全部归类为 `didn't find app state sync key`，未见 owner_changed 错误。
5. 9 月 29 日目标账号共记录 888 次 contact_not_ready，原始错误全部是 no_sync_key；这是含重试的日志次数，不是 400 个当前数据库方向的独立计数。
6. 扫描范围内未匹配到 APP_STATE_SYNC_KEY_SHARE / StoreAppStateSyncKey / handleAppStateSyncKeyShare 事件，也未匹配到目标账号 owner_replaced/clear_data/state_clear_failed 事件。缺少日志不能证明历史上从未发生，尤其 Debug 级别可能未开启。

### 最终原因与边界

当前真实故障链已经闭合：六段导入不含 AppState → 实际 Redis 缺 sync key → 主设备模式的恢复分支明确返回，反复集合拉取不能补齐 → 写联系人前被协议层确定拒绝 → 双向成功参与者为零 → 后端不下发建群。

这是当前 Android 六段主设备路径的联系人状态恢复能力缺口与普通建群前置条件冲突，不能归结为用户简单选错成员、前端显示错误或 WhatsApp 拒绝建群。至少 49 个旧成员的问题在 9 月 17 日就已存在。

8 月旧版本 key 固定 TTL=14 天、缺 key 时本地随机生成等是已验证代码历史；由于早期日志已不在保留窗口，无法把其中任一机制认定为这批 key 最初丢失的确定原因。也不能仅凭当前 owner 匹配就证明过去从未换过凭据。

恢复业务需要可靠取得并持久化与当前凭据匹配的 AppState 状态，或者在业务明确允许的前提下调整建群对通讯录写入成功的强制依赖。单纯放宽在线判断、重试、重传同一份六段文件或改提示都不能补回密钥；当前导入接口也没有 AppState 状态导入字段，应避免继续把“重导完整凭据”当成已支持且已验证的修复方案。

SSM command IDs：
- 目录/容器：`b8a5303a-0ccc-433f-8939-a60f3faabbba`
- 日志聚合：`d6c9b5c4-c0ca-47fd-a179-4d53dcdea70c`
- 密钥/清理精查：`46e3ab0a-a2a4-4bf4-aee3-76bf72e64469`

证据文件：`perf2-normal-group-task11-log-history-20260929.json`、`perf2-normal-group-task11-log-focus-20260929.json`。本轮仍未修改服务、权限、业务代码或业务状态，未发起账号/建群重试。

## 第四轮：用户指出曾有密钥更新逻辑，核对历史与线上实现

用户记忆成立：旧版确有自动生成并保存/分享 AppState key 的路径，不应将现状解释成“系统从来没有实现过补钥”。

- `git log --all -S generateAndShareNewKey -- internal/service/app/appstate.go` 定位到基线 `5a68ec3` 存在此路径，`0b1b36c698aca8d9c411f4c1051aafdc1aeef243`（2026-08-26 23:50:32 +08:00）删除。
- 原调用链：`generatePatch` 读取最新 key → ErrKeyNotFound → `generateAndShareNewKey` → `GenerateNewKey` → `StoreAppStateSyncKey` 保存 → `SendShareAppStateSyncKey` 分享 → 继续生成 patch。
- 同一提交删掉两个生成函数，并给 `requestMissAppStateSyncKeys` 加上 device=0 直接返回的条件。提交注释声称随机生成导致 409；本轮没有复现旧路径，不能仅凭该注释断言任何正确主设备密钥初始化都不可行。
- 保留的“更新”是收到 `APP_STATE_SYNC_KEY_SHARE` 后按 key ID upsert 并永久保存，并非主动恢复缺失 key。
- 保留的“持久化修复”是 `persistLegacyAppStatePayload` 只对仍存在且非空的 key 列表执行 `SetPermanent`，不会创建或补回缺失的 key；`EnsureAppStateOwner` 在 owner 已匹配时直接返回，也不会检查/补齐 key。
- perf2 正在运行的 76,766,744 字节程序中未检出 `generateAndShareNewKey`、`GenerateNewKey` 名称，检出 `AppStateKeyAvailable`、`StoreAppStateSyncKey`、`persistLegacyAppStatePayload` 及主设备无法恢复的固定文案。这与源码删除及已采集运行日志一致；未找到嵌入的 vcs.revision，不能给该二进制声称完整 git revision。

因此，已明确为什么“曾有更新逻辑”仍持续缺 key：原主动补钥路径被删除，剩余接收更新/旧 key 持久化分支无法覆盖当前空 key 的六段主设备账号。5 个群主在删除后导入，现有链路没有为其建立 key 的成功证据；49 个旧账号在可见日志最早时已缺 key，过去究竟何时因 TTL/清理丢失仍无可验证的完整历史。

这属于后续修改未覆盖缺 key 账号恢复的能力缺口，不能简单推给用户凭据不完整，也不能未经真实协议验证就把旧生成代码直接恢复并宣称修复。

本轮 SSM ID：`4bce1eeb-e4ca-422f-9388-820c651ad36e`。
