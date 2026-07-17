# 群详情后端注释与日志补强设计

## 目标

按 Armada `.harness/rules/编码规范.md` 补齐群详情新增代码的业务 Javadoc 和关键日志，不改变接口、协议 wire、选号、回读确认或异常语义。

## 注释范围

- `GroupParticipantPort.updateParticipants` 与 `HttpGroupParticipantAdapter.updateParticipants`：说明批量动作、30 秒协议等待、逐 JID 回执、部分成功和异常语义。
- `GroupDetailServiceImpl`：类、构造器和全部公开方法使用完整 Javadoc；关键私有方法说明自动选号、群主保护、超时后同账号回读、部分成功汇总及协议错误映射等“为什么”。
- `GroupDetailProtocolPorts`：说明四个端口的职责、组合目的和依赖边界。
- `HttpGroupSettingsAdapter`：补齐类、构造器、五项设置方法、wire mode 映射及参数校验说明。

## 日志策略

- Service 层使用 INFO 记录已确认成功的写操作和批量结果汇总，字段只包含 `groupLinkId`、Armada 本地 `accountId`、动作/设置枚举、布尔状态和数量。
- Service 层使用 WARN 记录协议失败、超时回读失败和降级原因；超时后回读确认成功使用 INFO。
- HTTP Adapter 只使用 DEBUG 记录协议动作、设置类型和目标数量，避免与 Service 的业务成功日志重复。
- 禁止记录头像 base64、群名称正文、完整成员 JID、协议账号句柄、token、creds 或代理信息。

## 验证

- 运行群详情、协议适配器和配置相关单元测试。
- 运行 Maven compile 和 `git diff --check`。
- 人工检查新增 Javadoc 与实现一致，日志字段无敏感数据。

