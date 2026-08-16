# 变更记录：群权限按钮统一使用本地管理员执行

- 日期 / 分支 / worktree: 2026-08-17 / `1.0.3-group` / `D:\idea_project\armada`
- 关联协议分支: Web `armada-protocol/1.0.3-snapshot`；Android `whatsapp-server-feature-android-zhuan/1.0.3-snapshot`
- 状态: 已完成（未部署）

## 目标

群详情的五个权限开关统一使用最短执行链路：从本地数据库选择在线、正常、仍在群内的管理员或群主，调用对应 WhatsApp 设置接口，再用同一账号读取一次 metadata 确认结果。

## 实现

- Armada 的 `updateSetting` 统一调用 `selector.requireAdmin(groupLinkId)`，不再先用任意成员读取 metadata 后重新发现管理员。
- 删除“通过链接邀请”的写前 capability metadata 探测；五个权限开关不再有额外前置协议调用。
- 写入超时仍使用同一执行账号做唯一一次 metadata 确认，不换号、不重试。
- Web metadata 保留缺失的 `joinApprovalMode` 为 `null`，不再用 `Boolean(undefined)` 误报为 `false`；同步更新 OpenAPI 和生成类型。
- Android 单群与群列表解析在缺少 `membership_approval_mode` 时返回空状态，Armada 映射为 `null`；显式 `on/off` 仍分别映射为 `true/false`。

## 按钮映射

- 编辑群组设置：`EDIT_GROUP_SETTINGS`
- 发送新消息：`SEND_MESSAGES`
- 添加其他成员：`ADD_MEMBERS`
- 通过链接邀请：`INVITE_VIA_LINK`
- 管理员可以批准新成员：`ADMIN_APPROVE_NEW_MEMBERS`

以上五项共享相同的账号选择、写入和单次回读流程；Web/Android 由所选账号的协议类型路由到对应 adapter。

## 验证

- TDD 红灯：Armada 新测试在旧实现上因仍调用 `selector.require` 前置读取而失败；Web 新测试显示缺失审批字段被错误映射为 `false`。
- Armada：`GroupDetailServiceImplTest`、Web/Android metadata adapter 与 settings adapter 组合回归通过。
- Web：群详情与群设置 2 个 suite、34 项测试通过；TypeScript `tsc --noEmit` 通过；OpenAPI 类型重新生成且引用校验通过。
- Android：`internal/service/node/nodes` 包测试通过，覆盖单群显式 `on`、单群缺失和群列表缺失三种审批状态。
- Android 全仓 `go vet/build/test ./...` 在 Windows 被既有 `syscall.Statfs_t`、`syscall.Kill` 等平台限定代码阻断；全仓测试还存在既有 Noise 向量/fixture 失败，与本次解析改动无关。
- 三个仓库 `git diff --check` 通过。

## 部署

- commit / 环境 / 部署后验证结果: 未部署。
