# 分组自建群邀请链接自动回补设计

## 背景与现状

第一套测试环境的拉群任务“群链接模式”中，运营分组 `111` 和
`测试拉群链接` 都显示 `0 个群`。只读排查确认这不是前端统计错误：

- 两个分组共有 15 个有效群入口，全部保存为内部标识 `wa://group/{jid}`；
- 15 个群均有 `group_link_preview`，但 `invite_code` 为空；
- `GroupFolderMapper` 只把具有真实邀请链接、健康且未封禁的群计为可用，因此显示 0 符合当前安全口径；
- Web 账号同步事件没有携带当前账号管理员角色，导致
  `account_group_membership.is_admin` 不能可靠识别自建群管理员；
- Android 两个群已有回补任务，但固定选中的第一个账号在协议运行时返回“账号不在线”，
  后续重试仍会选中同一账号；
- 另外 11 个已进入运营分组的群没有触发 metadata 同步任务。

用户已确认采用“长期修复 + 现有数据自动回补”，先提交代码和测试，不部署、不直接修改远程数据。

## 目标

1. 部署后自动为现有已分组、使用内部群 JID 且缺少邀请码的群创建或重置回补任务。
2. 以后内部群进入运营分组时自动触发 metadata 与邀请码同步。
3. 不再只依赖可能过期的 `account_group_membership.is_admin` 判断邀请链接读取权限。
4. 协议账号实际离线时，后续尝试轮换其它在线候选账号。
5. metadata 已成功、但分组群所需邀请码暂未取得时保持可重试，而不是误报任务完全成功。
6. 保持“没有真实邀请码就不计为可用群”的现有安全口径。

## 非目标

- 不把群 JID 当成邀请链接，也不放宽 `GroupFolderMapper` 的可用群条件。
- 不手工填写或伪造邀请码。
- 不新增并行的回补任务表或定时全表扫描器。
- 不修改拉群任务 API、前端字段或协议层事件契约。
- 本次不部署，也不直接连接远程数据库执行数据修改。

## 方案选择

### 方案一：复用耐久任务并做一次性自动回补（采用）

复用现有 `group_metadata_sync_task`：分组操作负责触发未来任务，Flyway 数据迁移负责将
存量缺邀请码的分组群重新排队，metadata 执行器负责从新鲜成员角色中选择管理员读取邀请链接。

优点是没有第二套状态机，任务仍具有去重、租约、并发限制、失败重试和账号上线恢复能力；
缺点是需要同时调整任务取数、账号选择、快照执行和数据迁移。

### 方案二：增加周期性缺链接扫描器（不采用）

定期扫描全部 `group_link` 并反复创建同步任务。实现直观，但失败群会被持续重置，容易形成请求风暴，
也与现有耐久任务的终态和退避规则冲突。

### 方案三：只做一次性 SQL 或运维脚本（不采用）

只能修复当前 15 个群，未来新群仍会因为管理员角色缺失或未触发 metadata 任务再次显示 0。

## 总体流程

```text
内部群进入运营分组
  -> 复用 group_metadata_sync_task 幂等入队

应用升级执行一次性 Flyway 数据迁移
  -> 筛选 active + folder_id 非空 + wa://group/ + invite_code 为空
  -> 不覆盖 RUNNING 租约，其余任务重置为 PENDING/BACKFILL

metadata job
  -> 按 attempt_count 轮换当前在群、数据库标记在线的候选账号
  -> 读取完整 metadata 和 participants
  -> 从新鲜 participants 中识别我方在线管理员
  -> 管理员读取真实 invite_code
  -> 原子保存预览与成员快照
  -> 分组群仍缺邀请码时进入退避重试

invite_code 成功落库
  -> GroupFolderMapper 原有查询自动将该群计入可用数量
```

## 后端设计

### 1. 分组操作触发同步

`GroupLinkServiceImpl.assignFolder` 在成功绑定非空运营分组后，对本次选中的内部
`wa://group/{jid}` 群入口调用现有 `GroupMetadataSyncTaskService.enqueue`。

- 复用 `GroupMetadataSyncTrigger.BACKFILL`，不新增触发码和状态列；
- 外部 `chat.whatsapp.com/...` 链接不触发该回补；
- 取消分组不触发；
- `enqueue` 的唯一键和现有 `ON DUPLICATE KEY UPDATE` 继续保证每租户每群只有一行任务；
- 分组更新和任务入队处于同一事务，分组失败时不得留下孤立任务。

### 2. 任务携带“邀请码必需”运行态

不新增数据库列。`GroupMetadataSyncTaskMapper.selectDueCandidates` 在现有
`group_link`/`group_link_preview` JOIN 中计算只读字段 `inviteRequired`：

- 群入口未软删；
- `folder_id` 非空；
- `link_url` 为 `wa://group/%`；
- `group_link_preview.invite_code` 为空或空白。

该字段只描述本次执行目标，不形成第二份持久化事实。真实邀请码仍只存
`group_link_preview.invite_code`。

### 3. 使用新鲜 metadata 识别管理员

metadata 完整返回后，先规范化 participants，再从其中提取具有 `ADMIN/OWNER` 角色且拥有
确认手机号的成员。执行账号选择器根据这些手机号，在当前租户、当前群关系、正常且数据库标记在线的
Armada 账号中选择邀请链接读取账号。

- 新鲜 metadata 匹配成功时，以新鲜角色为准；
- participants 只有 LID、无法确认手机号时，不猜测身份；
- 无法从新鲜数据匹配时，只允许回退到原先已确认的 `groupAdmin=true` 候选；
- 不把群主号码、任意管理员号码或 LID 数字直接当成 Armada 账号。

这样 Web 轻量群列表无需扩充 participants，也能在独立 metadata 请求中修复自建群管理员判断。

### 4. 候选账号轮换

`GroupExecutionAccountSelector` 根据任务已经完成的 `attempt_count` 选择候选偏移，候选顺序仍保持：

1. 已确认管理员优先；
2. `last_seen_at` 较新优先；
3. 关系 ID 稳定排序。

偏移按当前候选数取模，使同一任务的第 1、2、3、4 次尝试尽量使用不同账号。群详情页等非任务调用
固定使用偏移 0，保持现有交互行为。

管理员邀请读取也使用相同轮换原则，避免多个管理员中始终命中协议运行时已离线的第一个账号。

### 5. 部分成功与重试语义

metadata 和完整成员快照仍可先成功落库，邀请链接读取失败不得清空最后一次快照。

- `inviteRequired=false`：邀请读取失败维持现有降级，可完成 metadata 任务；
- `inviteRequired=true` 且没有可用管理员、协议读取失败或返回空邀请码：保存已取得的 metadata 后，
  以安全错误码结束本次尝试，进入现有 1/5/30 分钟退避；
- 取得非空邀请码：随预览快照落库，任务成功；
- 达到 4 次后进入 `FAILED`，避免无限请求；
- 账号上线恢复逻辑同时允许相关 `DEFERRED` 和 `FAILED` 任务重新进入 `PENDING`，满足现有设计中
  “账号重新上线可开启新一轮尝试”的口径。

日志只记录租户、群入口 ID、账号 ID、错误码和错误类型，不输出完整邀请链接或成员数组。

## 存量自动回补

增加下一可用版本号的 Flyway 数据迁移，执行以下幂等数据操作：

1. 选择未软删、已绑定运营分组、内部 `wa://group/%` 且邀请码为空的群入口；
2. 不存在任务时插入 `PENDING`、`BACKFILL`、`attempt_count=0`、立即可执行的任务；
3. 已存在且非 `RUNNING` 时重置为 `PENDING`，清空租约、执行账号和旧错误；
4. 已是 `RUNNING` 时保留当前租约，只标记 `rerun_requested=1`，避免破坏并发状态；
5. 不在 Flyway 内调用 WhatsApp；远程读取仍由应用启动后的受限速 job 执行。

迁移不新增表或列，不改变 `group_link`、`group_link_preview` 的事实归属。回滚应用版本时可以保留已创建的
任务和已取得的邀请码；不删除真实回补结果。

## 数据、API 与跨仓影响

- 数据模型：无新表、无新列、无索引变更；仅新增一次性任务数据迁移。
- API：无请求或响应契约变更。
- 租户隔离：运行时代码继续依赖 `TenantContext`；Flyway 的 `INSERT ... SELECT` 显式按
  `tenant_id + group_link_id` 写入唯一任务。
- 前端：无需修改，继续直接显示后端 `groupCount`。
- 协议层：无需修改，复用现有 Web/Android metadata、participants 和 invite 能力。
- Redis/Kafka：无变更。

## 错误处理与边界

- 群没有任何当前在群账号：任务进入 `DEFERRED`；账号上线后恢复。
- 数据库在线状态与协议运行时不一致：当前尝试失败，下一次轮换候选；全部失败后等待后续上线事件重开。
- 完整 metadata 缺失 participants：不替换旧成员快照，按现有失败规则重试。
- participants 无可确认 PN：不猜管理员身份，缺邀请码的分组群保持不可用。
- 邀请链接接口临时失败：metadata 可保留，但任务继续退避回补邀请码。
- 群已取消分组或邀请码在领取前已补齐：`inviteRequired=false`，不再强制取得邀请码。
- 群被删除、封禁或健康状态异常：现有 Mapper 条件继续控制最终可用数量。

## 测试与验收

### TDD 单元与 Mapper 测试

1. 缓存 `groupAdmin=false`，但新鲜 metadata 显示当前我方账号为管理员时，能够读取并保存邀请码。
2. 新鲜 metadata 只有 LID 且无确认手机号时，不误认管理员、不调用邀请接口。
3. 分组内部群且邀请码为空时，`inviteRequired=true`；已有邀请码、外部链接或未分组时为 false。
4. 邀请读取失败时 metadata 快照保留，任务进入 `RETRY_WAIT` 而非 `SUCCEEDED`。
5. 相同群的连续尝试按 `attempt_count` 轮换候选账号，并在候选数不足时取模。
6. 账号上线可恢复相关 `DEFERRED` 和 `FAILED` 任务，但不改动 `RUNNING` 任务。
7. 绑定非空分组只为内部群幂等入队；取消分组和外部邀请链接不入队。
8. Flyway 数据迁移只覆盖已分组、内部且缺邀请码的活跃群；保护 `RUNNING` 租约并重置其它终态。
9. `GroupFolderMapper` 回归：邀请码落库后计数和可执行链接同时增加，缺邀请码仍不计数。

### 验证命令

- 先运行新增测试观察红灯，再做最小实现直到绿灯；
- 对 Mapper XML 执行 `xmllint --noout`；
- 使用 H2 MySQL 模式加载真实 Mapper XML 验证租户隔离和状态更新；
- Flyway 专有 SQL 无法由 H2 执行时，补迁移脚本结构与关键约束测试；
- 运行相关群组域测试和 `mvn test`，若仓库既有真库测试阻塞则记录真实输出并单独运行可重复的本地门禁；
- 执行 `git diff --check`。

### 第一套测试环境部署后验收（本次不执行）

1. 确认现有 15 个目标群产生或重置 metadata 同步任务；
2. 观察任务按账号轮换，无任务风暴；
3. 对协议实际在线且存在我方管理员的群，`group_link_preview.invite_code` 变为非空；
4. 拉群任务分组下拉数量与 `GroupFolderMapper.selectUsableLinks` 返回数量一致；
5. 无管理员、全部账号离线或邀请接口拒绝的群继续显示为不可用，并保留可诊断任务错误；
6. 不影响外部邀请链接、未分组群、健康/封禁过滤及其它租户数据。

## 事实、推断与未确认项

- 事实：第一套两个分组共 15 个内部群入口，均缺少邀请码。
- 事实：其中 11 个没有 metadata 同步任务；2 个 Web 任务曾成功但因缓存管理员标记为 false 未读邀请；
  2 个 Android 任务因固定候选账号在协议运行时离线而失败。
- 推断：代码修复部署后，协议实际在线且仍有我方管理员的群可以自动补齐邀请链接。
- 未确认：15 个群在部署验收时是否仍至少有一个协议实际在线管理员；没有在线管理员的群只能保持不可用，
  等账号重新上线后自动重开任务，代码不能伪造链接。
