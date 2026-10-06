# NEW_GROUP 管理员接管后永久注销建群账号

## 范围与状态

本变更实现任务级开关、数据库冻结、一次性账号预留、Android 永久删除接入和独立实时验证。业务开关默认关闭，旧任务不启用。建群账号仅支持 Android 主设备；管理号支持 Web 和 Android。为确保 Web 管理号的核验可靠，额外在 `armada-protocol` 增加只读证明接口，没有增加 Web 删除能力。

所有改动位于 `IdeaProjects/.codex-worktrees/new-group-creator-delete/{armada,web,android,protocol}`，分支均为 `codex/new-group-creator-delete-20261006`。未提交、未部署，未连接真实数据库，未挑选或注销真实账号。本地测试结果见 `verification.md`；第二套环境尚未业务验收。

## 配置与兼容

- wire 字段 `creatorDeleteAfterTakeover`，与 `creatorLeaveAfterPull` 完全独立。省略按关闭处理。
- `pull_task.is_creator_delete_after_takeover` 是唯一配置事实；草稿、提交和待启动详情使用同一列。冻结设置查询只投影该值，不重复保存第二份。
- `PUT /api/pull-tasks/standard/{taskId}/creator-deletion` 只允许当前租户、任务创建人编辑 DRAFT 或从未启动的 WAIT_START 任务。启动与编辑共用父任务行锁；暂停、停止、恢复都不能改开关。
- 开启时只允许 NEW_GROUP 且必须选管理组。创建/编辑校验 Android 主设备分组；实际选号和注销前再次检查真实账号。
- UI 位于新群模式的建群人分组下方；草稿保存、回显、详情与逐执行行进度均贯通。不可撤销说明没有承诺所有客户端的欢迎卡立即刷新。

## 执行状态与证据

旧阶段编号保持 1–10；仅追加 `CREATOR_DELETE=11` 和 `CREATOR_DELETE_VERIFY=12`。

`建群 → 群资料设置并验证 → MANAGER_ADMIN → CREATOR_DELETE → CREATOR_DELETE_VERIFY → MANAGER_PULLER_CONTACT → 原邀请及拉人流程`

正常管理员确认和数据库已有管理员 SUCCESS 的快捷分支共用后继阶段门槛；后续阶段路由再次检查账本 COMPLETE，恢复、重试或补资源不能绕过。管理号已作为初始成员在群的路径继续使用原有分支，不重复发起入群。

任务账本状态：RESERVED → SUBMITTED → ACCEPTED/UNKNOWN/FAILED → COMPLETE。操作 ID 从执行行稳定派生，建群时同时冻结真实 creator accountId、规范化身份哈希、协议路由和 createOperationId；后续 PROMOTER 角色不能改变注销目标。

注销前以选中的管理号发起新协议查询，核对同群、创建者身份、管理号在群且具有管理员权限，并保留 Creation 基线；数据库在线条件和协议在线实例条件都要通过。群资料验证命令、邀请链接及账号依赖必须先持久化且完成。

发送意图先提交数据库，再做唯一一次 POST。最终发送事务与任务停止共享父任务/执行行锁，只在有界 HTTP 请求期间持锁；等待远端清理不持锁、不 sleep。停止先取得锁则不发请求，已发出的在途请求可能完成，证据通过原操作 GET 恢复。

以下五项同时成立才更新 COMPLETE：匹配账号与 operationId 的 ACCEPTED/IQ result；独立实时号码查询明确未注册；同群成员中原建群者已消失；Creator/CreatorPN 清空且 Creation 未变；管理号仍在群且是管理员。协议结构不完整、号码查询没有明确结果、群异常都失败关闭。

ACCEPTED 等待清理使用持久化 attempts、deadlineAt、执行行 nextRunAt：15 秒起指数退避，上限 120 秒；默认验证窗口 30 分钟，超时暂停执行行并给出原因；UNKNOWN 立即暂停，恢复时只查询原操作。实验中的约 6 分钟仅为观察结果，不是固定等待或放行规则。恢复仍使用原账本和 operationId，仅查询/对账；明确拒绝不重发。

GET 删除状态返回的 cleanupComplete=false 是旧接口固定响应语义，完全不作为注销失败或重发依据。

## 一次性账号与生命周期

账号域 `account_creator_deletion` 保存全局身份唯一预留及 RESERVED/DELETING/DELETED 生命周期。真实账号行和规范化号码别名行锁串行化预留、角色分配、命令入队与注销。新候选不再选取已预留账号；已有同执行行的建群/群资料动作可在 RESERVED 状态完成。

beginDeletion 再次核对其他拉群角色、营销占用、进群、超链、动态、通讯录、普通建群及未完成 Outbox 依赖。DELETING/DELETED 拦截上线恢复、身份查询的业务使用及新命令派发。完成后置离线并释放本执行行建群角色，永久保留注销生命周期和历史关联；不物理删除后台账号、不以单群退群替代注销。旧的末尾群主退群在新开关启用时跳过。

活动依赖子查询各自使用当前读，并覆盖尚未发送但已冻结的剧本账号角色；恢复旧拉手占用也须经过身份锁与生命周期检查。当前读设计依据 [MySQL 8.0 Locking Reads](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)，真实 MySQL 隔离级别及锁范围仍需第二套环境验证。

规范化身份的索引属于 account 身份聚合，是源号码的派生索引而非第二份可写身份；用于使同号码别名行锁可按索引定位，避免全表扫描锁扩大。

## 协议授权

Android 复用 `/ws/v1/account/delete/:key`、`/ws/v1/account/deletion/:key` 和 `internal/accountdelete` 持久化、主设备验证、一次发送能力。实验哈希白名单路径保留；正常任务走独立短期 HMAC 授权。

Java 服务端配置 `armada.protocol.account-deletion-task-secret`（对应环境名 `ARMADA_PROTOCOL_ACCOUNT_DELETION_TASK_SECRET`）；Android 配置 `WA_ACCOUNT_DELETE_TASK_SECRET`，值通过服务端密钥管理提供，不写文档、前端或普通任务参数。双方必须相同且至少 32 字节。签名绑定 tenantId、taskId、executionId、accountId、accountHash、createOperationId、operationId、method、path、iat/exp。普通 API 鉴权仍保留，授权过期只重签 GET 授权，不变更或重发注销操作。

Android 新增 `POST /ws/v1/account/deletion-proof/:managerPhone`，Web 新增 `POST /v1/accounts/:accountId/deletion-proof`。均只读，使用接管管理账号新发群 IQ 和号码 usync；校验当前会话身份、群结构、明确注册状态与成员身份，返回 source=LIVE_IQ。缺字段绝不补为“已清理”。

## 数据库与回滚

Flyway V211 增加任务布尔列及两张持久化账本；V212 为规范化账号身份增加派生索引。ADD COLUMN/INDEX 有 information_schema 守卫。任务账本受租户插件隔离；全局身份占用表由专用 Mapper 明确校验 tenantId，跨租户别名只返回“占用/不可用”布尔结果，不能读出其他租户数据。

回滚优先暂停已启用任务并保留账本/协议删除记录；删除是不可逆操作，回滚代码不能恢复账号。不可删除 UNKNOWN/DELETING 或已注销记录后重跑任务。必须保留协议防重发与禁重连能力，不能让旧工作进程恢复已注销账号。`rollback.sql` 仅解释本次采用非破坏回滚，不包含清空记录或反向修改已启动配置的语句。
