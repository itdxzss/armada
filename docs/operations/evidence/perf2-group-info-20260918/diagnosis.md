# perf2 两个历史群缺少邀请链接

2026-09-18，只读排查。用户确认目标为第二套 perf2：业务库 armada_perf，后端 3.110.124.52，Android 协议 3.111.245.182。执行账号 ID 2624，号码尾号 2625，协议 ANDROID。

## 已确认的直接原因

| 群 | group_link.id | group JID | 最近链接读取结果（北京时间） |
|---|---:|---|---|
| zubleq laboratory | 13860 | 120363354764987181@g.us | 09-18 15:19:10.319，IQ 401 / not-authorized |
| Destincare pharmacy group | 13858 | 120363334534972769@g.us | 09-18 15:19:08.811，IQ 401 / not-authorized |
| Old med staff（对照） | 13869 | 120363418504107661@g.us | 09-18 15:19:28.945，邀请链接已更新 |

两个异常群的 wa_group_profile.current_invite_id 和 group_link.group_invite_id 都是 NULL。正常对照均为 684。本报告不保存邀请码。

失败命令分别为 cmd_9cb8e696741049319a18f7cb183b4f44、cmd_8fdf2fa250ae42239ea3a0a13887c3b8。协议持久日志记录 scope=INVITE_CODE、wsResponseSource=IQ、wsRawCode=401、wsRawReason=not-authorized；业务批量任务 67 明细 1990、1989 最终为 GROUP_PERMISSION_DENIED，说明为“获取群邀请链接失败，且没有其他可用账号可重试”。更早批次 54、46 也有同类失败，zubleq 另有批次 49 的失败。不是仅凭页面空链接推断权限问题。

这两个群已有群名、6/7 人以及完整成员快照头。metadata_observed_at 均为北京时间 09-18 01:01:33.017，成员快照分别为 01:02:12.102、01:02:15.121。zubleq 的建群时间另为 NULL，不能用邀请码权限错误解释该独立字段缺失。查询到的上述群批量任务全为 task_type=1（刷新链接），不是 task_type=2（最新群信息）。

## 页面为何显示可用管理员

**续查已确认历史角色误标。** 用户要求进一步核实后，对 Kafka topic `armada.perf.protocol.group.events.v1` 的首次同步时间窗口执行只读 Fetch，没有加入消费组或提交 offset。三个目标事件都位于 partition=3：

| 群 | offset | 原始上报的 self admin / role | 旧逻辑上报的 owner |
|---|---:|---|---|
| zubleq laboratory | 12632 | false / 空字符串 | true |
| Destincare pharmacy group | 12635 | false / 空字符串 | true |
| Old med staff | 12634 | true / superadmin | true |

三条 source 都是 android_group_sync_command，事件时间分别为北京时间 09-18 01:02:12、01:02:15、01:02:14，创建者号码均对应目标账号。Android 旧版将创建者匹配直接转成 owner=true；后端 AccountGroupCurrentSnapshotPersistenceImpl 优先依据 participant.owner() 写 OWNER，最终数据库 role=3。前两个群原始成员角色没有管理员权限，却因为创建者身份被误标为群主；正常对照则明确为 superadmin。这与之后邀请码查询的两次 401 和对照成功一致。精简证据见 role-evidence.json。

此证据确认的是首次同步时的误标以及页面旧记录的来源，不冒充当前实时权限验证。09-18 15:35:36 再次对三个群只读查询，仍全部返回账号不在线；业务库 login_state 仍为 1，显示状态与协议实际在线状态不一致。此次未发起重新上线或回填。

本次查询 wa_group_participant 中，目标账号在三个群均为 presence_status=1、role=3；account_state 为 account_state=6、login_state=1。GroupListCurrentMapper 的可用管理员探针依赖本地成员角色与账号状态，不代表 WhatsApp 此刻已验证邀请码读取权限。

Android groups_fetcher.go 的 reportedGroupProfile 旧逻辑将 participantMatchesCreator(...) 也当成 Owner=true；本地未提交修改及远端部署目录已经改为仅 role==superadmin 才上报 Owner=true。前次只检查代码时尚不能确认样本误标，本次上述 Kafka 事件已补齐历史角色证据。此次没有自行验证运行二进制与目录源码的哈希一致性；即便新版已经部署，也不会自动纠正这些尚未重新同步的历史成员行。

## 当前核验边界

排查时后端与 Android 容器刚被其他操作重建（后端启动 07:24:15 UTC，重启计数 0）。重建前证据来自数据库与 Android 持久日志，而非当前容器短日志。

随后对三个群各调用一次只读 /ws/v1/groups/members/{phone}（group_id），全部返回 Code=1003、账号不在线，未取得当前成员角色。这个较晚的离线状态不能倒推解释 15:19 的 IQ 401。没有重新上线账号、修改成员或群设置、回填数据库、触发刷新任务、修改应用代码或部署。

下一步应在账号恢复在线后，对照当前真实 participant.type 与本地 role：若为普通成员，应同步纠正管理员展示并使用实际管理员获取链接；若确为管理员，则继续检查该群的邀请码查询权限与协议兼容性。已有修改不能替代该样本的业务验收。

## 证据位置

- 数据库：wa_group / wa_group_profile / wa_group_participant / group_link / account_state / group_batch_task_item / group_metadata_sync_task。
- Android 持久日志：/home/ec2-user/whatsapp-android-zhuan/deploy/logs/whatsapp-server-2026-09-17.log 和 whatsapp-server-2026-09-18.log。
- 本地只读查询辅助文件：/tmp/perf2-group-info-diag/groups.sql、tasks.sql、query.py、live_members.py。
