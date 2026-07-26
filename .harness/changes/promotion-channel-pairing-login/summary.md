# promotion-channel-pairing-login

## 业务范围

推广落地页通过 WhatsApp“关联新设备 -> 使用手机号关联”完成随机配对码登录。所有模板页面复用同一接口，渠道码和访问域名共同确定真实租户与渠道。

## 代码影响

- `promotion/pairing`：新增公共会话接口、会话状态机、Mapper 和 Kafka 事件落地。
- `platform/protocol`：新增只读 Web 配对 adapter，复用现有 HTTP executor。
- `platform/kafka`：新增配对事件 consumer 和配置，使用每次尝试唯一的 `protocolAccountId` 精确关联。
- `account`：新增配对成功账号落库服务，并补齐已有 `promotion_channel_id` 映射。
- `resource`：新增独立的“配对占用”状态、会话归属列、预留与成功转绑方法，不改变现有账号上线分配方法。
- `db/migration/V067-V069`：分别增加账号手机号索引、代理配对占用列和最小会话表；每个版本仅一条 DDL，避免 MySQL 部分提交导致同一版本无法安全重跑。
- `armada-protocol`：新增独立 `/v1/auth/promotion-pairing-code` 路由、配对初始化阶段的专用 Socket 读取方法和 master 转发规则；旧配对接口、旧 `getSocket` 和原事件载荷不变。

## 兼容说明

- 现有 Controller、Service 方法签名和业务 SQL 不变。
- 不改变协议层既有 HTTP 路径、二维码登录、普通账号上线和普通 `getSocket` 行为；新方法只允许一次性 `acc_pair_` 账号在 `IMPORTED/VERIFYING` 阶段获取 Socket 生成随机码。
- 一次性协议账号使用进程内并发 claim、运行上下文与严格凭据存在性检查共同防重；失败时仅在 owner worker 确认清理完成后释放 claim，避免重试跨 worker 串会话。
- `account` 和 `account_credential` 的普通 `insert` SQL 保持原样；推广配对使用新增专用 insert 写入渠道归因和代理会话字段。
- `ip_proxy.bound_account_id` 只保存正式账号 ID；配对期间使用新增 `pairing_session_id` 和状态 `PAIRING_RESERVED`，不会污染存量账号/IP 统计。
- 数据库迁移不修改历史 Flyway 文件。
- 会话令牌只通过响应返回一次，状态查询改用 Header 传递，响应禁止缓存。
- 账号创建和完成阶段均执行跨租户手机号存在性保护；活动配对唯一键串行化同一手机号的配对尝试，
  不改变现有账号导入允许跨租户同号的业务规则。
- 定时扫描过期会话并复用现有代理释放服务；被动状态查询仍保留过期兜底。

## 验证

- `armada-api` 执行 `mvn -DskipTests compile`，编译通过。
- `armada-protocol/protocol-layer` 执行 `npm run build`，TypeScript 编译通过。
- 协议层推广配对入口执行 1 个最小定向测试，结果 `1 passed`。
- 协议层 OpenAPI 类型重新生成并完成引用检查。
- 按当前要求未执行完整测试集和真库 DbTest；部署前仍需在已确认的测试环境完成联调验证。

## 环境约束

- 未确认真实测试数据库，未执行 DbTest/Flyway 真库迁移；部署前需在确认的 MySQL 8 测试库验证迁移、
  索引构建和事务锁行为，并按规范重新生成 `.harness/wiki/数据模型.md`。
- 当前测试环境允许 HTTP 联调，生产必须由边缘代理启用 HTTPS。
- 创建配对会话会占用协议 Worker 和代理资源，生产必须在 Nginx/API Gateway 对创建接口按来源 IP 限流，
  且不得将后端端口直接暴露到公网；本次未复用 Android 图片专用 Redis，避免把两个独立业务的连接与可用性耦合。
- 生产环境必须配置协议层 API Key，并禁止使用明文协议层地址；否则不能视为满足生产安全条件。
- 配对成功沿用现有 `account_credential.creds_json` 凭据存储契约；本次不改动既有账号上线读取链路。
  生产需确认数据库与备份加密、账号最小权限等风险控制，后续凭据加密应作为独立兼容迁移实施。
