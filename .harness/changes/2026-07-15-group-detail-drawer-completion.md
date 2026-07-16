# 变更记录：群详情抽屉现有入口补齐

- 日期 / 分支 / worktree: 2026-07-15 / 1.0.1-snapshot / armada
- 需求来源: 用户逐项确认；`docs/superpowers/specs/2026-07-15-group-detail-drawer-completion-design.md`
- 状态: 本地实施完成，待确认环境后执行 DbTest 与 WhatsApp 真群验收

## 目标（一句话）

只补齐当前前端群详情抽屉已有入口，纵向接通真实 WhatsApp 群资料、限时消息、五项权限和成员管理，不新增任何前端功能入口。

## 缺口拆解 / 任务清单

- [x] 盘点前端、Armada 后端和 armada-protocol 当前实现。
- [x] 逐项确认范围、执行账号、权限失败和部分成功语义。
- [x] 完成并自检设计文档。
- [x] 按 `docs/superpowers/plans/2026-07-15-group-detail-drawer-completion.md` 完成本地代码实施。
- [x] Slice 1：详情读取、自动选号、真实权限/限时消息/成员回显。
- [x] Slice 2：真实群名称、头像和本地备注。
- [x] Slice 3：四档限时消息。
- [x] Slice 4：五项群权限及“通过链接邀请”本地能力门禁。
- [x] Slice 5：批量升管理员、降管理员、踢出。
- [x] 三仓不依赖远程环境的自动化测试与编译构建。
- [ ] 确认测试环境后的 DbTest 与 WhatsApp 真群验收。

## 关键设计决策

- 采用纵向逐项打通，不做后端大爆炸式一次性交付。
- 产品范围严格以当前 `GroupMemberDrawer.vue` 已有入口为准；协议层已有但抽屉没有的能力不新增前端入口。
- 前端不选择执行账号；Armada 自动选择在线、仍在群内、优先管理员的账号。
- 权限不足时不换号重试；批量成员操作成功项不回滚，逐项返回失败原因。
- 群名称和头像修改真实 WhatsApp 后同步本地镜像；群备注仅本地保存。
- 权限、限时消息和成员为实时状态，不新增数据库持久化。
- “通过链接邀请”是独立 WhatsApp 权限，不与添加成员或入群审批混用；当前 Baileys 未暴露公共 API，必须先在确认的测试环境验证能力，不支持时前端禁用并显示原因。
- 不新增群描述、实际添加成员、复制/重置邀请链接、审批列表或退群入口。

## 验证（evidence-before-done）

设计阶段：

- 三仓当前代码和接口已完成只读对账。
- 设计文档已执行占位符、内部一致性、范围和歧义自检。

2026-07-16 本地实施验收：

- armada-protocol：OpenAPI 生成一致；群详情、设置、成员操作和 master 转发共 5 个 Jest suite、69/69 通过；lint、TypeScript build 通过。
- Armada：群详情/选号/Controller/四个协议适配器/配置/Mapper SQL/原服务共 10 个测试类、80/80 通过；`mvn -Dmaven.test.skip=true compile` 通过。
- 前端：群 API 6/6、抽屉与三个 composable 8/8 通过；`tsc`、`vue-tsc`、目标 ESLint、Prettier 和 `vite build` 通过；抽屉 597 行。
- 三仓 `git diff --check` 通过；未提交、未部署。
- `GroupExecutionAccountSelectorDbTest` 未连接数据库执行，因为尚未确认目标数据库环境。

2026-07-16 代码评审修正：

- 重新按 `.harness/rules/编码规范.md` 审查群详情新增后端代码，补齐 `updateParticipants`、
  `GroupDetailProtocolPorts`、`HttpGroupSettingsAdapter` 和 `GroupDetailServiceImpl` 的业务 Javadoc。
- `GroupDetailServiceImpl` 的公开方法完整说明参数、返回和异常；关键私有方法说明自动选号、
  群主保护、同账号超时回读、部分成功汇总和协议错误映射原因。
- 群名称、头像、限时消息、权限和成员操作增加 INFO/WARN 业务日志；HTTP Adapter 只增加
  DEBUG 协议摘要。日志不包含群名称正文、头像 base64、完整成员 JID或协议账号句柄。
- 收敛本次涉及文件中的群设置 mode、成员动作超时、批量上限和成员状态魔法值；
  `GroupDetailServiceImpl` 排除注释和空行后为 704 行，低于类 800 行限制。
- 相关 10 个测试类 80/80 通过，Maven compile 和 `git diff --check` 通过。
- 尝试执行全量 `mvn test` 时触发需要数据库的 `EpochMillisSchemaDbTest`；因目标数据库环境
  未确认而立即终止，未将其计入通过证据。

2026-07-16 本地能力门禁结果：

- armada-protocol 7.0.0-rc11 metadata 固定返回 `inviteViaLink=null`、`supported=false` 和明确原因。
- Armada 原样聚合能力状态；设置请求在协议能力不支持时返回 `GROUP_CAPABILITY_UNSUPPORTED`，不误用其它设置接口。
- 前端开关在值未知或 capability unsupported 时禁用并展示原因。
- 远程只读探测未执行：尚未取得明确目标环境、测试账号 Armada ID、协议账号 ID、测试群 JID、管理员身份和本次授权时间。取得这些信息前不得连接远程或操作真群。

## 部署

- commit / 环境 / 部署后验证结果: 未 commit、未部署、未连接远程环境。

## 遗留 / 跟进

- 执行数据库 DbTest 前确认目标数据库环境。
- 远程 WhatsApp 验收前确认目标环境、测试账号 Armada ID、协议账号 ID、测试群 JID、管理员身份和本次授权时间。
- 真群验收重点覆盖：真实详情、四档限时消息、四项稳定权限、邀请链接能力探测，以及升/降管理员和踢人的成功/部分成功/权限不足语义。
