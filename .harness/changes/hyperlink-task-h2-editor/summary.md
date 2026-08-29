# 超链任务 H2 表单查询与账号筛选合同

- 日期 / 分支：2026-08-29 / `codex/hyperlink-task-h2-editor`
- 设计依据：`2026-08-28-hyperlink-task-shared-contract.md`、`2026-08-28-hyperlink-task-editor-design.md`、`hyperlink-marketing-data-model.md`
- 状态：H2 本地实现及审查修复完成；未部署、未连接真实数据库

## 本次范围

- `GET /api/hyperlink-tasks/{id}`：返回 create/edit/view/copy 共用表单所需的完整任务、内容、筛选、运行状态和乐观锁版本。
- `GET /api/hyperlink-tasks/create-context`：返回真实钱包价码、余额、PRIVATE 协议容量、账号组、国家、推广渠道、协议选项和默认业务组 ID。
- `POST /api/hyperlink-tasks/account-match-count`：与运行选号共用账号筛选归一化和 Mapper SQL，数据库直接 `COUNT(*)`。
- 手工合入 `account_profile` 前置提交的画像表、写入缝和筛选能力；没有重做协议侧画像采集。

## 审查项关闭情况

- `linkDescription` 服务端校验与 `hyperlink_task_content.link_description` 均为最多 512 字。
- 注册天数保持正整数合同；国家包含与排除可同时设置，只拒绝同一国家重叠。
- 大洲只接受 `ASIA`、`AFRICA`、`EUROPE`、`NORTH_AMERICA`、`SOUTH_AMERICA`、`OCEANIA`、`ANTARCTICA`。
- `rotationStatus`、`groupInviteAllowed`、`source`、好友数、注册天数由 `account_profile` 真实字段筛选；画像为 `NULL` 时不会伪装成 0 或 false 命中条件。
- 候选 select 与 account-match-count 保留 H2 的同源过滤片段和数据库 COUNT，并合入画像 JOIN/条件，没有机械选择冲突一侧。
- detail 读取 `accountFilter` 后强制经过同一 `HyperlinkAccountFilterNormalizer`；未知 schema、非法枚举、非法范围和损坏 JSON 均失败关闭。
- 历史 `messageType=2` 只允许保持类型编辑内容；新建双图文或修改既有消息类型继续由后端拒绝。
- `defaultAccountGroupIds` 缺少可证据化的 `public + hyperlink` 稳定业务代码时返回空数组，不按名称或猜测 ID 扩大范围。

## 数据、租户与性能

- Flyway `V158__account_profile.sql` 新增一对一账号画像表和账号组合索引；各画像事实按独立水位幂等更新，未知事实保留 `NULL`。
- 账号画像写入通过同租户、未删除的 `account` 行 `INSERT ... SELECT`，不生成跨租户孤儿画像。
- 账号候选派生表、画像过滤和 COUNT 使用同一 SQL 片段；筛选、协议选项和协议容量均下推数据库。
- 详情查询显式保持相同 `tenant_id` 连接；账号试算日志只记录租户、筛选哈希、数量和耗时。
- Redis、Kafka、协议命令无变更。

## 验证

- JDK 17 `mvn -q -DargLine=-javaagent:<byte-buddy-agent> -Dtest='*Hyperlink*Test,*AccountProfile*Test' test`：53 个测试类、221 个用例，0 failure / 0 error / 4 skipped。
- JDK 17 `mvn -q -DskipTests compile`：通过。
- H2 定向测试覆盖账号画像 SQL、COUNT/select 同源、国家交集、完整大洲、非法历史筛选快照与详情失败关闭：通过。
- Mapper XML `xmllint --noout`：通过。
- `git diff --check` / `git diff --cached --check`：通过。

## 硬依赖与边界

- 默认业务组前置尚未提供稳定 `public + hyperlink` 业务代码和租户映射；当前服务端明确返回空默认组，由前端阻止启用任务，不能静默放宽账号范围。
- Web/Android 协议侧好友数、拉群权限采集，以及轮号、号源、注册时间事件接线不在 H2；画像存储和过滤已就绪，但生产数据覆盖率仍依赖这些采集方。
- 历史双图文的真实发送命令与 Web/Android 协议适配属于 H3；H2 只负责正确回填、锁类型和保存内容。
- 钱包、MySQL、协议节点和远程接口未做真实环境联调；本变更不部署。

## 回滚

- H2 查询与筛选代码可按本提交回退。
- 若回滚画像前置，须先停止画像写入和画像筛选，再按 `.harness/changes/hyperlink-account-profile-foundation/rollback.sql` 删除组合索引与 `account_profile`；该操作会丢失已采集画像数据。
