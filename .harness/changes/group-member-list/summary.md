# 变更记录：群组成员实时列表

- 日期 / 分支 / worktree: 2026-06-29 / main / armada
- 需求来源: 群组列表明细需要查看成员列表；实时 HTTP 查询协议层，不做成员持久化
- 状态: 已完成

## 目标（一句话）

在群组列表明细提供实时成员列表接口，由 armada 选择一个已在群内且在线的账号，经协议层查询 WhatsApp 群成员。

## 缺口拆解 / 任务清单
- [x] 新增 `GET /api/group-links/{id}/members` 后端接口。
- [x] 从群链接预览数据取 `group_jid`，从 `account_group_membership` 选择在线群内账号。
- [x] 新增 `GroupParticipantPort` 与 HTTP adapter，对接协议层 `GET /v1/groups/{groupJid}/participants?accountId=...`。
- [x] 返回成员 `jid/phone/admin/owner/role`，不新增成员表、不落库。
- [x] 补 controller、service、HTTP adapter、配置测试。

## 关键设计决策

- 成员列表采用实时查询，不持久化成员明细。原因是当前页面只需要明细查看，落库会引入同步频率、成员变更覆盖、历史审计等额外语义。
- 查询账号优先使用 `account_group_membership` 中当前群内且在线的账号，避免随机拿账号导致协议层无权限读取成员。
- 没有 `group_jid` 时直接提示先预览或等待账号群同步；没有在线群内账号时返回业务异常，由前端展示不可查询状态。

## 验证（evidence-before-done）

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn test -Dtest=GroupLinkServiceImplTest,GroupLinkControllerTest,HttpGroupParticipantAdapterTest,ProtocolConfigurationTest
```

关键输出：

```text
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dmaven.test.skip=true compile
```

关键输出：

```text
BUILD SUCCESS
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
xmllint --noout armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml
```

关键输出：退出码 0。

## 部署
- commit / 环境 / 部署后验证结果: 未提交，未部署。

## 遗留 / 跟进
- 前端群组列表明细页需要接入 `GET /api/group-links/{id}/members`。
- 后续如要展示成员数和成员列表共用同一协议返回，可再统一群成员检测与实时列表的协议调用口径。
