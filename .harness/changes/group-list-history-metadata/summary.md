# 群组列表历史分类与详情快照

## 目标

统一群组列表中的历史群和上控后群口径，并为列表、详情抽屉和筛选保存可重复读取的群元数据与最后一次完整成员快照。

## 固定业务口径

- `group_link.is_historical` 表示该群曾出现在任一账号首次上线基线中。
- `group_link.is_post_control` 表示该群曾在账号基线固化后被可靠观察到加入。
- 两个标记允许同时为 `1`，落库后只升不降。
- 群创建时间只接受 WhatsApp metadata 的 Unix 秒 `creation`，不使用本地建群成功时间或 `group_link.created_at` 兜底。
- 群主国家只从协议确认的 PN JID 或显式 `phoneNumber` 解析；LID、执行账号国家和代理国家都不作为群主国家。
- 历史群详情通过耐久任务异步补齐；失败不得清空最后一次成功快照。

## 数据结构

- `group_link` 新增历史群、上控后群固化标记及查询索引。
- `country` 新增六大洲代码与筛选索引；`AQ/BV/HM/TF` 可保持大洲为空。
- `group_link_preview` 新增描述、权限、限时消息、创建者地区和元数据观察时间。
- `whatsapp_group_member_snapshot` 保存每群最后一次完整成员快照。
- `group_metadata_sync_task` 保存每租户每群一行的同步状态、租约、执行账号、重跑标记及错误摘要。

## 实施与验证

- 正式迁移：`armada-api/src/main/resources/db/migration/V096__group_list_history_metadata.sql`。
- 协议层在成员/群资料变更后发布 `account.group_metadata_sync_requested`，相同账号与群的并发 HTTP metadata 读取合并，并在 2 秒窗口内复用结果。
- 后端耐久任务按租户 3、账号 1 的上限领取任务；失败按 1/5/30 分钟重试，第四次进入失败终态，无可用账号进入延期状态。
- 群详情和成员 GET 只读最后成功本地快照；`POST /api/group-links/{id}/metadata-sync` 负责手动排队刷新。
- 统一群组列表支持历史群、上控后群、重叠分类、运营分组、可用管理员、成员范围、六大洲、国家和群龄组合筛选；count/list 共用同一 SQL 口径。
- 前端主筛选、历史群抽屉、核心列表列和详情刷新入口已对齐原型范围；群所属国家读取全部真实国家并排除虚拟代理地区，原型其余未来按钮未实现。
- SQL 契约测试：`GroupListHistoryMetadataMigrationSqlTest`。
- H2 覆盖任务状态机 Mapper、metadata 旧响应保护和完整成员快照替换；协议层 Jest/TypeScript build、前端类型检查/ESLint/Stylelint/Vite build 均通过。
- 当前本地未提供可连接的 MySQL `armada` 测试库，真库 Flyway 与完整 `GroupLinkMapperDbTest` 仍需在具备测试库配置后补跑。

## 回滚原则

应用代码可先回退并暂时保留新增结构。删除快照表会永久失去最后一次完整成员数据，删除任务表会失去排队与重试状态；分类标记属于历史事实，不提供批量从 `1` 重置为 `0` 的数据回滚。
