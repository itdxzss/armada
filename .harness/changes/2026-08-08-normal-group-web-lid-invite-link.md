# 变更记录：Web 普群 LID 误判与邀请链接缺失修复

- 日期 / 分支 / worktree: 2026-08-08 / `1.0.2-snapshot` / `D:/idea_project/armada`
- 需求来源: 第一套测试环境中新建普群后，Web 自动群名没有拼接群 JID 后五位，且 Web/Android 群创建后邀请链接为空
- 状态: 已完成，待部署验证

## 目标（一句话）

让 Web 协议正确识别 Baileys 返回的 LID 成员，并让 Web/Android 新建普群完成后可靠触发 metadata 同步，由新鲜管理员读取真实邀请码。

## 缺口拆解 / 任务清单

- [x] 复现 Web 建群返回 LID 时被误判为 `GROUP_CREATE_PARTIAL`
- [x] 协议层使用 participant 的 `phoneNumber` 校验 LID 返回项对应的请求手机号
- [x] 验证建群成功后继续执行群名后五位拼接与群设置
- [x] 复现缓存 `is_admin` 为空时跳过邀请链接读取
- [x] 按新鲜 metadata 管理员手机号选择在线在群账号
- [x] 按任务尝试次数轮换有限候选，拒绝用 LID 猜测手机号
- [x] 确认 Android 群名已正常拼接 JID 后五位，不修改该逻辑
- [x] 确认 Android 原生邀请码接口与 Armada 适配器响应契约一致
- [x] 新建普群完成并登记成员关系后，幂等入队 metadata/邀请码同步任务
- [x] 自建内部群仍缺邀请码时保留 metadata 快照，并让耐久任务按现有退避策略重试
- [x] 运行新增聚焦单测、真实 Mapper 测试、XML 解析与差异检查

## 关键设计决策

- Web 建群结果只在确实缺少请求手机号的 PN 别名时判定部分失败；LID 本身不能与手机号强行等同。
- 邀请码读取权限以本次完整 metadata 的 OWNER/ADMIN 为准，不依赖可能尚未回填的缓存 `account_group_membership.is_admin`。
- 只从在线、正常、仍在群内且手机号命中新鲜管理员快照的账号中选择邀请码读取账号。
- metadata 与邀请码读取失败重试时按 `attempt_count` 稳定轮换最多 4 个候选，避免始终命中同一异常会话。
- 普群完成点统一触发耐久同步，Web/Android 复用同一业务链路；不修改 Android 群名或 JID 处理。
- `inviteRequired` 是调度查询动态字段，不新增数据库列；仅 `origin=SELF_BUILT` 且仍缺真实邀请码的内部群需要重试。
- 本次不伪造邀请链接、不手工修改共享数据库，也不自动部署。

## 验证（evidence-before-done）

- 协议 Jest：`normal-group-creation-executor.test.ts`，10/10 通过。
- 协议构建：`npm.cmd run build`，TypeScript 编译通过。
- 后端邀请链接相关测试：19/19 通过。
- 后端建群结果与群名后缀测试：12/12 通过。
- Android 普群完成触发、邀请码重试、管理员选择和任务调度聚焦测试：27/27 通过。
- `GroupMetadataSyncTaskMapper.xml` 已通过 XML 解析，且真实 Mapper 在 H2 MySQL 模式下验证缺邀请码动态判定。
- `GroupExecutionAccountSelectorDbTest` 需要本地真 MySQL 测试库；当前连接被拒绝，未将该环境失败计作代码通过。

## 部署

- commit / 环境 / 部署后验证结果: 本次未提交、未部署；需同时发布 `armada-protocol/protocol-layer` 和 `armada-api` 后验证。

## 遗留 / 跟进

- 已经停在 `CREATED_PARTIAL` 的历史任务不会自动倒退重放；部署后新建任务按修复逻辑执行。
- 已存在但缺少邀请链接的群不会因新完成点自动重放，可在部署后触发一次“刷新群信息”或 metadata 同步回补真实邀请码；新任务会自动入队。
