# 普通拉群任务开启成员添加权限

## 变更概述

- 普通拉群任务在进入管理—拉手联系人阶段后，先由已确认的任务管理员检查群成员添加权限。
- 当前不是 `all_member_add` 时开启“普通成员可添加群成员”，并通过实时群详情再次确认。
- 未确认或权限不足时保留当前阶段延迟重试，不占用新拉手、不提交联系人或批量拉人动作。
- Android Zhuan 复用既有 `join-mode` 协议能力，并向 Armada 群详情返回 `MemberAddMode`。

## 影响模块

- `armada-api/platform/protocol/backend/android`：Android 设置接口、回读映射和错误归类。
- `armada-api/task/scheduler`：联系人阶段前置权限门禁。
- `whatsapp-server-feature-android-zhuan/api/service`：群成员详情增加成员添加模式。

## 数据库变更

- 无表、字段或数据迁移。
- 复用现有执行阶段、原因码和重试时间。

## API 变更

- Android Zhuan `POST /ws/v1/groups/members/{key}` 的成功响应 `Data` 新增
  `MemberAddMode`，值为 `all_member_add`、`admin_add` 或空字符串。
- Armada 对外业务 API 无新增路径或入参。

## Redis 变更

- 无。

## 关键约束

- 设置和回读协议调用在数据库事务外执行。
- 只有回读到 `MemberAddMode=all_member_add` 才允许继续分配拉手。
- 设置动作幂等；请求超时后下一轮先回读，避免重复动作影响状态。
- 现有已越过联系人阶段的执行行不会自动回退。

## 回滚方案

- 回退 Armada 本次 Android adapter 和联系人阶段权限门禁代码。
- 回退 Android Zhuan 群成员详情新增字段；该字段为增量响应，旧调用方忽略时不受影响。
- 无数据库回滚操作。
