# Web 协议 Outbox 默认路由修复设计

- 日期: 2026-07-11
- 项目: `armada`
- 模块: `armada-api`
- 状态: 已确认采用方案 1

## 1. 背景与根因

`V046__protocol_command_outbox_backend.sql` 为 `protocol_command_outbox` 增加了非空字段
`protocol_backend`,用于记录协议命令应路由到 `WEB` 还是 `ANDROID`。

账号上线、下线 outbox 已显式写入协议后端,但以下当前只支持 Web 协议 master 的命令构造路径没有赋值:

- 群健康检查;
- 账号群列表同步;
- 普通营销消息;
- 建群营销消息。

Mapper 显式插入 `protocol_backend`,因此 Java 字段为空时会向 MySQL 传入 `NULL`,不会使用列默认值
`WEB`。营销轮次因此在写 outbox 时触发 `Column 'protocol_backend' cannot be null`,整个事务回滚,
无法生成 attempt 和 outbox。

## 2. 目标

1. 恢复当前 Web 协议账号的普通营销和建群营销消息发送。
2. 让所有已明确固定走 Web master topic 的 outbox 行都显式记录 `protocol_backend=WEB`。
3. 保持数据库非空约束,继续用约束暴露遗漏的协议路由。
4. 不提前实现 Android 营销、Android 群健康检查或 Android 群同步。

## 3. 设计方案

在 `ProtocolCommandOutboxServiceImpl` 的 Web-only outbox 行构造方法中显式设置:

```java
row.setProtocolBackend(ProtocolBackend.WEB.name());
```

涉及三个构造方法:

- `toGroupHealthCheckOutboxRow`;
- `toAccountGroupSyncOutboxRow`;
- `toMarketingMessageOutboxRow`。

普通营销与建群营销共用 `toMarketingMessageOutboxRow`,因此只需一处生产代码修改即可覆盖两条营销路径。

这些方法当前已经固定使用 `masterCommandProperties.getTopic()`,所以写入 `WEB` 是对现有真实路由的明确记录,
不是新增路由行为。账号上线、下线继续使用命令自身的 `protocolBackend` 选择 Web/Android topic,不做调整。

## 4. 明确不采用的方案

### 4.1 只修普通营销

仅在营销方法补 `WEB` 能恢复当前任务,但群健康检查和账号群同步仍会因同一非空字段失败,留下已知回归。

### 4.2 在 Mapper 中使用 `COALESCE(NULL, 'WEB')`

这会把未来真正遗漏的 Android 路由静默降级到 Web,不利于发现跨协议错误。协议后端应由命令构造代码明确赋值。

### 4.3 本次直接支持 Android 营销

完整方案需要营销命令携带协议后端、按账号后端选择 topic,并要求 Android 协议服务具备对应消息消费和结果回写能力。
当前目标只是恢复现有 Web 营销,本次不扩大到该范围。

## 5. 测试设计

采用测试先行方式调整 `ProtocolCommandOutboxServiceImplTest`:

1. 普通营销 outbox 断言 `protocolBackend == "WEB"` 且 topic 仍为 Web master topic。
2. 建群营销 outbox 断言 `protocolBackend == "WEB"`。
3. 群健康检查 outbox 断言 `protocolBackend == "WEB"`。
4. 账号群同步 outbox 断言 `protocolBackend == "WEB"`。
5. 保留并运行现有上线、下线 Web/Android 路由测试,确保动态路由没有回退。

验证命令以 `armada/armada-api` 为工作目录,运行目标测试类,并补充编译与 `git diff --check`。

## 6. 上线与验收

本轮先完成本地代码和测试,不自动部署测试环境。测试环境部署需再次确认。

部署后使用新营销任务或仍在有效时间窗口内的任务验证:

1. 后端不再出现 `Column 'protocol_backend' cannot be null`。
2. `marketing_task_send_attempt` 生成新记录。
3. `protocol_command_outbox` 生成 `message.send.requested`,且 `protocol_backend=WEB`。
4. outbox 提交后投递到 Web master topic。
5. 协议层返回 `message.send_result_reported`,Armada 正确结算成功或失败。

账号真实离线、没有符合条件的群或 WhatsApp 拒绝消息仍按原有业务规则处理,不属于本修复范围。
