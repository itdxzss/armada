# 拉群任务分组邀请链接取数修复设计

## 问题

普通群链接拉群任务选择运营分组后，`GroupFolderMapper.selectUsableLinks` 直接返回
`group_link.link_url`。账号同步形成的群入口使用 `wa://group/{jid}`，这是群组池内部稳定标识，
不是可供外部账号加入的 WhatsApp 邀请链接，因此后续严格链接校验会将其判为“缺少群邀请链接”。

## 设计

- 已经以 `chat.whatsapp.com/...` 保存的群入口继续返回原链接。
- `wa://group/{jid}` 群入口关联 `group_link_preview.invite_code`，返回
  `chat.whatsapp.com/{inviteCode}`。
- 内部群入口没有邀请码时不进入可执行链接集合，也不计入分组的可用群数量。
- 保持现有健康、封禁、软删除和租户隔离条件不变，不修改表结构与 API。
- 不放宽拉群任务的邀请链接校验；群 JID 不能代替邀请链接。

## 验证

使用 H2 MySQL 模式加载真实 `GroupFolderMapper.xml`，覆盖内部入口转换、无邀请码过滤、
分组数量一致性和现有租户隔离；随后运行相关服务测试和后端全量测试。
