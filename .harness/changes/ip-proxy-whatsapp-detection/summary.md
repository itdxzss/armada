# IP 代理 WhatsApp 连通性检测

## 变更概述
- IP 代理导入后只做格式、行字段、批内/库内去重校验，新增代理保存后提交后台检测任务。
- 检测结果写入出口 IP、国家码、地理信息、WhatsApp 连通性状态和失败原因。
- 新增检测生命周期字段，避免后端把“检测中”和“真实不可用”混成同一个检测状态。

## 影响模块
- `resource` 域 IP 代理导入、手动检测、后台检测写回。
- `ip_proxy` 表新增检测生命周期与 WhatsApp 探测结果字段。

## 数据库变更
- 见 `armada-api/src/main/resources/db/migration/V026__ip_proxy_check_status.sql` 和
  `armada-api/src/main/resources/db/migration/V027__ip_proxy_check_status_backfill.sql`。
- 归档 SQL 见本目录 `db-migrations.sql`。

## API 变更
- 导入接口语义保持：立即返回导入统计。
- 手动检测接口会继续同步返回检测结果，后续结果语义改为 WhatsApp 连通性通过。

## Redis 变更
- 无。

## 关键约束
- 代理用户名、密码不得进入日志或错误展示。
- `status=IDLE` 才能进入分配池；检测失败写 `UNAVAILABLE`。
- `SMART` 分配检测成功后按检测国家更新 `region`；`MIXED` 保留混合分组。

## 回滚方案
- 停止发布新后端版本后执行 `rollback.sql` 删除新增列。
- 回滚前确认新版本代码不再读取 `check_status`、`whatsapp_check_status`、`whatsapp_http_status`、`whatsapp_check_error`。
