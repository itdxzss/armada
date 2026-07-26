# 推广渠道 WhatsApp 配对登录实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增隔离的推广配对码登录链路，确保原手机号配对、二维码登录和原 Kafka 事件载荷完全不变。

**Architecture:** 协议层新增 `/v1/auth/promotion-pairing-code`，由后端传入每次会话唯一的 `acc_pair_*` 协议账号句柄。协议层继续复用现有 socket、代理、超时和原始 pairing 事件；后端直接按唯一 `accountId` 关联会话，不向公共 `AccountManager` 或旧事件增加业务字段。

**Tech Stack:** Java 17、Spring Boot、MyBatis、MySQL 8、Kafka、TypeScript、Fastify、Baileys。

## 全局约束

- 不修改原 `/v1/auth/pairing-code`、`/v1/auth/qrcode` 的请求、响应和事件载荷。
- 不修改 `AccountManager.armPairingTimeout` 签名及既有状态机。
- 新业务只增加独立路由，并复用现有底层公开能力。
- 账号仍落入现有 `account`、`account_state`、`account_credential`，不新增账号表。
- 不提交代码，只将本次文件放入暂存区供人工审核。

---

## 隔离改造任务

### Task 1：协议层独立推广配对入口

**Files:**
- Create: `armada-protocol/protocol-layer/src/routes/promotion-pairing.ts`
- Modify: `armada-protocol/protocol-layer/src/routes/index.ts`（仅注册新路由）
- Modify: `armada-protocol/openapi/protocol-v1.yaml`（仅新增 endpoint）
- Test: `armada-protocol/protocol-layer/src/routes/promotion-pairing.test.ts`

**Interfaces:**
- Consumes: `AccountManager.online(...)`、`armPairingTimeout(accountId, timeoutMs)`、现有 Registry 和 EventPublisher。
- Produces: `POST /v1/auth/promotion-pairing-code`，请求包含 `accountId`、`phone`、`proxy`，响应沿用配对受理结构。

- [ ] 先写测试，断言新入口按传入的唯一 `accountId` 发起配对，同时旧入口源码不再包含 `clientRefId` 事件透传。
- [ ] 运行专项测试，确认因新路由不存在而失败。
- [ ] 新增路由并恢复 `auth.ts`、`account-manager.ts`、原 webhook 契约。
- [ ] 运行专项测试和 `npm run build`，确认通过。

### Task 2：后端使用唯一协议账号句柄关联

**Files:**
- Modify: `armada-api/src/main/java/com/armada/promotion/pairing/service/impl/PromotionPairingServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/pairing/HttpPairingLoginAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/command/PairingCodeCommand.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/pairing/service/impl/PromotionPairingEventSinkAdapter.java`
- Add: `armada-api/src/main/resources/db/migration/V067__promotion_pairing_account_phone_index.sql`
- Add: `armada-api/src/main/resources/db/migration/V068__promotion_pairing_ip_reservation.sql`
- Add: `armada-api/src/main/resources/db/migration/V069__promotion_pairing_session.sql`
- Test: `PromotionPairingServiceImplTest`、`HttpPairingLoginAdapterTest`、`PromotionPairingEventSinkAdapterTest`

**Interfaces:**
- Consumes: 新协议接口及原 `pairing.code_generated/completed/failed` 事件中的 `accountId`。
- Produces: 每个会话唯一的 `acc_pair_<随机值>`，后端按该值精确关联 Kafka 回调。

- [ ] 先写测试，断言协议请求携带唯一 `accountId`，事件在没有 `clientRefId` 时仍能按该值命中会话。
- [ ] 运行定向测试并确认失败原因是仍依赖旧关联字段。
- [ ] 修改新功能内部实现；V067 增加仅约束活动会话的手机号唯一辅助列。
- [ ] 运行最小 Java 定向测试、SQL 契约和编译，确认通过。

## 目标

落地页按 `渠道码 + 实际访问域名` 发起 WhatsApp 随机配对码登录。Armada 复用现有 Web 协议 HTTP/Kafka 能力；协议确认成功后，才复用 `account`、`account_state`、`account_credential` 完成账号落库。

## 不变量

- 公共请求不能传入或决定 `tenant_id`、渠道 ID、模板 ID；这些值只能由渠道码和 Nginx 可信 `X-Forwarded-Host` 查询得到。
- 不向浏览器返回代理、凭据或协议 Token；会话 Token 只返回一次，数据库只保存 SHA-256 摘要。
- 协议层使用自身生成的随机配对码，不传 `customPairingCode`。
- 配对完成前不写账号三表，失败/过期必须释放本次预留代理。
- 多个模板页面共用同一组公共接口；模板差异不进入配对业务分支。
- 外部协议 HTTP 调用不放在数据库事务中；成功后的账号、代理、会话终态在一个短事务内提交。
- 不修改现有账号导入、账号上线和 Kafka 账号状态回填路径；协议层新增独立推广配对入口，原 pairing 事件载荷保持不变。

## 接口

1. `POST /api/public/promotion-channels/{channelCode}/pairing-sessions`
   - Header: `X-Forwarded-Host`
   - Body: `{ "phone": "919876543210" }`
   - 返回一次性 `sessionToken`、当前状态和过期时间。
2. `GET /api/public/promotion-pairing-sessions/status`
   - Header: `X-Pairing-Session-Token`
   - 返回状态；等待确认时返回配对码，成功时返回账号 ID，失败时返回脱敏错误。

## 状态机

`REQUESTING -> WAITING_CONFIRMATION -> FINALIZING -> SUCCEEDED`

任一等待状态可进入 `FAILED` 或 `EXPIRED`。Kafka 事件使用每次尝试唯一的 `protocolAccountId` 精确关联，重复消息通过条件更新幂等处理。

## 实施步骤

1. Flyway 新增最小会话表，保存渠道归属、协议关联、代理预留和终态；不另建账号表。
2. promotion 域新增公共 Controller、Service、Mapper 和会话状态模型。
3. platform/protocol 新增 Web 配对 HTTP adapter，复用 `ProtocolHttpExecutorRegistry`。
4. platform/kafka 新增配对事件 consumer/sink，复用现有 Kafka profile、错误处理器和配置风格。
5. resource 域新增配对代理预留、确认转绑、补偿释放三个窄接口，现有账号上线入口保持不变。
6. account 域新增“已配对账号入库”Service，复用账号三表 Mapper，不复用导入批次语义。
7. 增加过期会话扫描，复用本地终态事务释放临时代理。
8. 增加控制器、HTTP adapter、服务和 SQL 合同测试；未确认真实测试库时不执行 DbTest。

## 验收条件

- 错误域名或渠道码不能创建会话。
- 同一协议账号同时最多一个活跃会话。
- 本次公开配对入口在创建与完成阶段执行跨租户手机号存在性校验，并通过活动会话唯一键串行化同一手机号的配对尝试；
  既有账号导入仍保持租户隔离和跨租户同号兼容，不在本功能中修改其业务契约。
- 返回的原始会话 Token 不落库；日志不出现配对码、代理密码或账号凭据。
- 配对成功后账号处于 `NORMAL + ONLINE`，凭据格式为 Baileys JSON，且保存 `promotion_channel_id`。
- 协议调用失败、Kafka 失败事件或会话过期后代理可再次分配。
- 现有 promotion/account/resource/protocol 测试保持通过。

## 传输约束

- 当前测试环境可暂时使用 HTTP 便于联调，但请求包含手机号和一次性会话令牌，生产环境必须切换 HTTPS。
- 代码不固化协议，后续 Nginx 启用 HTTPS 不需要修改接口或数据模型。
- 生产入口必须在 Nginx/API Gateway 对 `POST /api/public/promotion-channels/{channelCode}/pairing-sessions`
  按来源 IP 限流，并禁止绕过边缘代理直连后端端口；避免恶意请求耗尽协议 Worker 与代理池。
- 生产环境必须启用协议层 API Key，并使用 HTTPS 访问协议层；测试环境的 HTTP 兼容不能带入生产。
