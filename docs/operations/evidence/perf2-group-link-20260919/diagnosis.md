# 指定历史群邀请链接读取失败

核验日期：2026-09-19，约北京时间 20:04。用户明确目标为第二套 perf2，并要求搜索和更新群链接。

目标群：120363407504822137@g.us。业务库 armada_perf，tenant_id=1，wa_group.id=2612，group_link.id=10561。执行账号 account.id=2081，号码 917492853060，协议 ANDROID。

## 实际结果

- 数据库 profile.current_invite_id 与 group_link.group_invite_id 均为空。
- 最新历史刷新任务 84 / 明细 2628，执行账号 2081，attempt_count=1，失败码 GROUP_INVITE_LINK_UNAVAILABLE，说明“当前群没有可用邀请链接”。群信息刷新任务 82 / 明细 2137 成功。
- 本轮从第二套 Android 服务执行一次 POST /ws/v1/groups/qrcode/{phone}，请求字段 group_id 为目标群。返回 Code=1003，Msg="获取群二维码失败, bad-request, Code: 400"，未返回邀请链接。
- 本轮随后执行一次 POST /ws/v1/groups/members/{phone}，返回 Code=0、Msg=ok，3 名成员；执行账号对应 participant.type=admin，其他两名成员 type 为空。由实时协议查询确认账号可用且确为管理员。
- 协议持久日志中该群 2026-09-19 11:52:35.540 与 11:59:01.479 的 IQ 原始错误同样为 code=400、text=bad-request。日志时间沿用服务原始值。

## 判定边界

当前失败点是邀请码协议读取；不是未选中账号，也没有证据指向账号离线或无管理员身份。400 / bad-request 未提供更细原因，不能据此认定群封禁、链接已撤销或请求参数错误。

当前源码 HTTP 与 Kafka 邀请码读取均调用 GetGroupInviteCode，构造 w:g2 invite GET；Kafka 对 IQ 400/410 归一为 GROUP_INVITE_LINK_UNAVAILABLE。这是现有错误分类，并不证明该群永久不存在邀请码。

本轮链接仍未更新成功，没有写入数据库、重置/撤销邀请码、修改成员/群设置或部署。浏览器新页面未登录，现有标签页借用被取消；已关闭本轮临时浏览器会话，随后通过已确认目标的后台和协议接口核验。

## 进一步诊断

用户追问具体原因后，继续只读排查。

- 同账号当前本地管理员关系中，有群名且已保存邀请链接 37 群；空群名且无链接 5 群。此为相关性，不是“空群名导致邀请码失败”的因果证明。
- 对照群 120363425108597710@g.us（bravely70692）使用相同账号和相同 qrcode 接口实时获取链接成功；随后成员查询确认当前角色仍为 admin，成员数 2。对照不保存或输出邀请码。
- 目标群实时 MemberLinkMode=all_member_link、Locked=true。Locked 的代码语义是仅管理员可编辑群资料，不等于群封禁；目标执行账号仍为 admin。
- 目标群 09-17 已出现同样 IQ 400；还曾有一次 IQ 429。09-19 本轮错误仍为 400，不能拿旧 429 解释当前失败。
- marketing_task_send_attempt 和 protocol_risk_event 均无该群的状态证据。数据库 health_status=1、banned=0，但 last_checked_at=1787377678534，即北京时间 2026-08-22 13:47:58.534，不能作为当前健康证明。
- 当前 Android 解析器 handleGroup 读取 suspended、terminated、parent、linked_parent、default_sub_group；GroupInfo 将这些字段设为 json:"-"，HTTP groupMemberResponse 也未输出。协议群资料事件未带 suspended/terminated。因此当前 HTTP/数据库无法证明这些标记的实际值。
- 运行容器为 prod 模式；原始收发 XML 只在 Debug 记录。现存协议日志包含归一错误和 HTTP 响应，无本次原始群状态标记。

下一步的最小诊断方案：在既有 groupMemberResponse 中只增加 Suspended、Terminated、IsParentGroup、ParentGroupID、IsDefaultSubgroup 五个读取结果字段，不增加 WhatsApp 请求、不修改群状态。候选差异见 diagnostic-fields.patch；没有应用到主仓库，也没有部署。该诊断可确认群终态/社区类型是否存在，不能预先保证这就是邀请码 400 的唯一成因。

## 获准部署诊断字段

用户明确回复“部署核实吧”，批准第二套 Android 诊断补丁部署及服务重启。应用到 Android 主仓库分支 1.0.3-snapshot，基线 e406294c41ec58d1a552cc99ab79b263ede04a1c；只修改 api/service/group.go 的五个返回字段，未提交或推送。

部署前，远端与本地原始 group.go SHA-256 均为 a3cebeebdf623c06c4dd3bac17b489fec41efeba014082c98387cb9dc447e301，entity.go SHA-256 均为 6e84690106423a77cc952d0ef6549a078996a3800a6d59eb0280a56eeb691ba6。新增诊断后 group.go SHA-256 为 a0a054bdbb7834ab4b1b9ade90180c645abe070ce2d1845207f70145ccc95fe2。

验证：gofmt、git diff --check、go vet ./...、go build ./... 通过；go test ./... 的 API、Armada、协议处理等包通过，只有未改动的 pkg/noise 8 项既有加密测试失败。已确认 pkg/noise、go.mod、go.sum 均与 HEAD 一致，且 Noise 不依赖此次修改的 api/service 包，不将其归为本次回归。本机 Go 版本 1.26.5。部署脚本语法及 deploy-test.test.sh 通过。

部署命令：CI=true bash armada-deploy/deploy-test.sh --env perf2 --zhuan -y。部署脚本按现有流程构建镜像、运行迁移检查、重建 Android 服务及辅助服务。部署前为旧镜像 a574bda2c99dc08ab080b4e888b795b0836856746e2eeb27bb6ad78f6753af6d 保留 rollback-group-diagnostic-20260919 标签。

## 部署后业务核验

部署脚本退出 0，耗时约 105 秒；后端、前端、Web 协议未部署。Android、callback、trafficdashboard 使用新镜像 sha256:c3da4a28a68e28858a9f65c60b7087429ada27ac66480801e02228fd157ad94c，部署后检查健康、重启次数 0。此次为本地未提交补丁的明确范围部署，没有 commit 或 push。

服务重启后，账号 2081 未自动恢复连接，业务库仍显示在线。使用该账号原始上线命令中的凭据和代理恢复同一个账号；敏感数据仅在进程内和 SSH 加密通道使用，没有落盘或输出。上线返回 Code=0，随后实时群资料查询成功，确认实际在线。

2026-09-19T12:31:56Z（北京时间 20:31:56）实时核验：

- 目标群返回 Code=0，Subject 为空，Count=3，自身角色 admin。
- 新增五字段均实际出现：Suspended=false、Terminated=false、IsParentGroup=false、ParentGroupID=""、IsDefaultSubgroup=false。
- MemberLinkMode=all_member_link、Locked=true。
- 对照群实时返回相同的五个否定/空标记，Subject=bravely70692、Count=2、自身角色 admin；MemberLinkMode=admin_link、Locked=false。
- 部署后重新读取目标群邀请码，仍返回 Code=1003，Msg="获取群二维码失败, bad-request, Code: 400"，hasInviteUrl=false。随后再次读取群资料成功。

结论：诊断补丁已部署并通过真实群查询验证；目标群没有返回暂停、终止或社区类型标记，先前相关猜测未得到支持。当前账号实际在线且有管理员身份，邀请链接仍无法获取。已确认失败发生在 WhatsApp 邀请码读取返回 400 的环节；远端未给出更细拒绝原因。空群名与缺失链接仅有样本相关性，不能作为已确认根因。没有修改群名、成员、群权限或重置/撤销链接，也没有将部署健康等同于链接修复成功。
