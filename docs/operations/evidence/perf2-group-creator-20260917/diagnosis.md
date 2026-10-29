# 第二套历史群创建信息缺失排查（2026-09-17）

目标已由用户确认：perf2。后端 3.110.124.52 / armada_perf；Android 协议 3.111.245.182。账号 ID 2601，号码尾号 7499，protocol_id=ANDROID。时间在下文注明时区。

## 结论

截图样本的直接原因已确认：Android 协议发布的 group.profile_reported 没有 creatorPhone；后端按空值跳过创建者写入，国家/洲依赖该号码，因而一起为空。建群时间走独立字段，故仍能展示。不是这些样本的页面漏展示、国家字典解析失败或写库失败。

尚未取得首次同步时 WhatsApp 原始 IQ 的 creator/creator_pn 属性，不能把“协议没上报号码”进一步断言成“WhatsApp 必定没返回”或“创建者必定退群”。

## 证据链

1. 数据库账号入库：2026-09-17 12:53:55.793 UTC。首次同步 commandId=cmd_735692b9b0bb4cddba5620e8d3861a44。
2. 协议日志 12:55:19.606 UTC：includeParticipants=true，groupCount=67，skippedGroupCount=0，queryElapsedMs=8734，publishElapsedMs=68142。数据库 baseline_state=2，baseline_group_count=67。
3. 对 armada.perf.protocol.group.events.v1 按 12:53:55–12:58:00 UTC 的时间 offset 做只读 Fetch，未加入消费组或提交 offset。找到该账号首次同步 67 条资料事件：44 条有 creatorPhone，23 条无。
4. 随后 MANUAL_INFO_REFRESH 有 11 条群资料事件，仅 2 条带 creatorPhone，另外 9 条仍无。说明用户执行刷新后确实重新读取了，缺失并非只留在首次缓存。
5. 数据库该账号关联且未软删的群入口共 11 条；2 条有 group_link_preview 创建者兼容记录，9 条根本没有兼容记录，非仅国家字段空。Rachel link_id=13281、Heroe link_id=13258、W link_id=13234、Preys link_id=13249、Ijoba link_id=13226 均属后者。Ff ffezzzz I I love you（link_id=13252）创建者尾号 7499，国家 NG，洲 AFRICA，与截图一致。
6. Rachel 首次事件 partition=1 offset=2813，12:54:35.516286786 UTC，无 creatorPhone，4 名成员均非 owner；手动刷新 offset=2876，12:57:33.055 UTC，结果相同。Heroe offset=2801/2874，结果也相同。精简字段见 profile-evidence.json。
7. 后端持久化当日日志中“协议群资料上报写创建者失败”和“建群人号码国家解析失败”命中均为 0。资料消费日志可查到上述群事件。

## 对照代码

- Android internal/armada/groups_fetcher.go:446：依次取 Creator 的 PN、CreatorPN、owner 成员号码；全无则返回空。线上部署目录的该函数已核对与此一致，未据此宣称二进制逐字节一致。
- Android internal/armada/group_snapshot_client.go:68：手动详情刷新调用 GetGroupMember，复用同一 reportedGroupProfile 映射。
- Armada GroupProfileReportedSinkAdapter.java:179：creatorPhone=null 直接返回。
- Armada GroupCreatorCompatibilityWriter.java:44：号码非空才解析国家并保存兼容记录。
- 不把当前管理员当原创建者。对该账号首次同步的老格式 JID 交叉检查：20 群中 8 群带创建者号码，其中 2 群与 JID 号码前缀不同。因此，简单截取老 JID 前缀与当前现有字段口径存在冲突，不能直接回填。

## 补查边界与后续

23:08 北京时间（15:08 UTC）仅对 Rachel、Heroe、W 和正常对照群各发起一次只读成员详情查询。前两次返回 Code=1003 / iq processor closed，后两次明确账号不在线，未取得新 metadata。未重新上线、重试、修改群成员或写业务库。该实时故障发生在首次同步之后，不能拿它解释 20:54 的字段缺失。

要继续分清“原响应没有创建者”与“LID 号码映射未补齐”，需要账号在线时抓一条完整原始 creator/creator_pn 与成员身份的最小诊断信息，再对照已有可信身份映射。修复应在协议取号与缺失原因可观测性处开展；无法确认来源的创建者不能用管理员或数字型群 ID 冒充。

本次仅排查并新增本地证据文档；未修改应用代码、部署、回填数据库或操作账号上线。
