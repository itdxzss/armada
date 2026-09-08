# 2026-08-31 动态发布任务闭环

## 目标
- 衔接前端动态发布任务页面，后台通过 `status.publish.requested` 协议命令完成 Web 与 Android 账号的 WhatsApp Status 发布。

## 影响模块
- `armada-api`：动态任务 API、调度器、任务账号明细、协议命令构造、结果回写。
- `armada-protocol`：Web 协议层消费 `status.publish.requested` 并发送到 `status@broadcast`。
- `whatsapp-server-feature-android-zhuan`：Android 协议层消费同一命令并使用 `StatusBroadcastJID`。

## 数据库变更
- 新增 `feed_task` 动态发布任务主表。
- 新增 `feed_task_account` 动态发布任务账号明细表。
- 新增动态营销菜单与 `tenant:feed_task:view/create/edit/operate` 权限节点。

## API 变更
- 新增 `/api/feed-tasks` 任务列表、详情、创建、编辑、操作接口。
- 新增 `/api/feed-tasks/{id}/data` 明细接口。

## 关键约束
- 控端不直接逐账号调用 Web HTTP 接口。
- 调度器只生成 `status.publish.requested` 并写入 `protocol_command_outbox`。
- Web 与 Android 协议层共用消息命令结构和统一结果事件。

## 回滚方案
- 回退本功能代码与 `V172__feed_task.sql`。
- 如迁移已执行，先删除菜单权限节点，再删除 `feed_task_account`、`feed_task` 表。
