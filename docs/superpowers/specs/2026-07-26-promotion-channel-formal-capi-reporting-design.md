# 推广渠道 Facebook CAPI 正式业务上报设计

## 目标与范围

- 移除渠道列表的 Facebook CAPI 测试探测入口和弹窗，渠道事件改为随真实业务状态异步上报。
- 三个渠道事件字段只能选择 Meta 的 18 个标准事件，前端选项统一来自后端目录接口。
- 在不修改协议层代码的前提下，复用现有配对会话创建、协议受理和 Kafka 登录完成流程作为三个触发点。
- Meta 故障、超时或限流不得阻塞配对会话、协议登录或账号落库。
- 本期只实现 Facebook；TikTok、快手和 MGSKY Ads 的现有行为保持不变。

## 标准事件目录

后端以 `FacebookStandardEvent` 枚举作为唯一事实源，包含：

1. `PageView`
2. `ViewContent`
3. `Search`
4. `AddToCart`
5. `AddToWishlist`
6. `InitiateCheckout`
7. `AddPaymentInfo`
8. `Purchase`
9. `Lead`
10. `CompleteRegistration`
11. `Contact`
12. `CustomizeProduct`
13. `Donate`
14. `FindLocation`
15. `Schedule`
16. `StartTrial`
17. `SubmitApplication`
18. `Subscribe`

目录接口返回事件代码、中文名称和英文名称。渠道新增、编辑在 Service 层校验三个事件名必须属于该枚举，禁止任意自定义字符串。历史非标准值可继续读取，但再次保存时必须改为标准事件。

默认映射保持为：

- 意向用户：`Lead`
- 请求登录：`InitiateCheckout`
- 登录成功：`CompleteRegistration`

## 业务触发点

### 意向用户

配对会话成功创建后产生 `LEAD` 事件。只有手机号、渠道和会话均通过后端校验并成功落库时才记录，不在手机号输入、失焦或前端点击阶段上报。

### 请求登录

协议层成功受理配对请求，后端成功将会话更新为已受理状态后产生 `LOGIN_REQUEST` 事件。协议请求失败或本地状态更新失败不产生该事件。

### 登录成功

后端消费现有协议层 Kafka 登录完成事件，并在账号落库、代理确认和配对会话成功状态全部提交后产生 `LOGIN_SUCCESS` 事件。协议层无需增加字段、接口或事件。

创建配对会话时一次性读取渠道配置，并为三个阶段各创建一条事件快照：`LEAD` 立即进入待发送，`LOGIN_REQUEST` 和 `LOGIN_SUCCESS` 先处于等待业务触发状态。后续运营修改渠道映射不会改变该次配对已经生成的三个事件；协议受理和登录完成只负责激活对应快照，不再依赖浏览器或协议层补传归因数据。

## 前端数据流

- 渠道表单通过 `src/api/buyer-channel.ts` 调用后端目录接口，三个 `ElSelect` 复用同一组选项。
- 目录加载失败时显示可见错误并禁止提交，不使用生产假数据兜底。
- 删除渠道列表“探测”按钮、`ChannelDetectDialog` 组件及其 API 调用，不保留死代码。
- 公共推广页创建配对会话时附带可选 `fbp`、`fbc` 和当前页面 URL。
- `fbp` 读取 `_fbp` Cookie；`fbc` 优先读取 `_fbc` Cookie，缺失且 URL 存在合法 `fbclid` 时按 Meta 约定构造。浏览器无法获得这些值时保持为空，不影响配对。
- 一次性配对令牌、Access Token 和手机号不得写入 URL、日志或本地存储。

## 后端分层

- Controller 只接收目录查询和配对归因字段，解析请求上下文后调用 Service。
- 配对业务 Service 在现有事务边界内调用 CAPI 事件记录 Service，不直接访问跨域 Mapper。
- CAPI 事件记录 Service 负责标准事件映射、手机号 SHA-256、幂等事件 ID、三个阶段快照和 Outbox 状态转换。
- Outbox Mapper 只负责事件插入、待发送领取和状态更新。
- Facebook CAPI 客户端复用现有官方 Graph API 根地址、Token 解密和脱敏错误映射，但正式事件不携带 `test_event_code`。
- 后台调度器领取待发送事件并在数据库事务外调用 Meta，避免持锁等待外部网络。

保持 `Controller -> Service -> Mapper`，不引入 Repository；配对域调用渠道域的公开 Service，不跨域访问渠道 Mapper 或实体。

## 数据模型

新增租户级表 `promotion_capi_event_outbox`，作为推广配对聚合向 Meta 投递的可靠事件队列。该事实与渠道配置、配对会话和协议命令 Outbox 均不重复：渠道配置描述“应该上报什么”，配对会话描述“业务进行到哪里”，本表描述“某个已发生业务事件向 Meta 的投递生命周期”。

核心字段：

- `tenant_id`、`promotion_channel_id`、`pairing_session_id`
- `event_stage`：`LEAD`、`LOGIN_REQUEST`、`LOGIN_SUCCESS`
- `event_name`、`event_id`、`event_time`；等待阶段的 `event_time` 为空，激活时写入真实业务时间
- `phone_sha256`、`client_ip`、`client_user_agent`、`fbp`、`fbc`、`event_source_url`
- `status`：等待触发、待发送、发送中、成功、永久失败、已取消
- `retry_count`、`next_retry_at`、`locked_by`、`locked_at`
- `last_error_code`、`last_error_message`、`sent_at`
- `created_at`、`updated_at`

约束和索引：

- `(tenant_id, pairing_session_id, event_stage)` 唯一，防止 Kafka 重投或接口重试导致重复事件。
- `event_id` 全局唯一，并在所有重试中保持不变，供 Meta 去重。
- `(status, next_retry_at, id)` 支持后台按状态和到期时间领取，只有待发送状态会被领取。
- `(status, locked_at, id)` 支持失效锁恢复。
- `(sensitive_expires_at, id)` 支持七天隐私硬期限扫描。
- `(tenant_id, promotion_channel_id, created_at)` 支持渠道级排查。

结构变更只通过全局唯一版本的 Flyway 脚本执行，并同步更新 Harness 数据模型文档和变更记录。迁移随附评审副本与回滚脚本；回滚只允许在确认没有待发送事件时删除本表。

## Meta 正式事件负载

单次请求发送一条事件：

- `event_name`：渠道触发时的标准事件快照
- `event_time`：业务状态发生的 Unix 秒
- `event_id`：稳定幂等 ID
- `action_source=website`
- `event_source_url`：与当前渠道域名一致、移除查询参数和片段后的 HTTP/HTTPS 来源页面
- `user_data.ph`：E.164 归一化手机号的 SHA-256，不发送明文
- `user_data.client_ip_address`
- `user_data.client_user_agent`
- `user_data.fbp`、`user_data.fbc`

没有真实订单金额或币种时不伪造 `custom_data`。事件目录为后续按标准事件补充真实业务参数预留明确的事件代码入口，但本次不提前增加无调用方的参数模型。

## 安全与隐私

- Pixel ID 和 Access Token 只从当前租户、当前渠道的有效追踪配置读取。
- Access Token 只在发送调用期间解密，通过 Bearer Header 发送；数据库 Outbox、API、日志和错误信息均不保存或输出 Token。
- 手机号只以 SHA-256 写入 Outbox；日志不得记录手机号摘要、IP、User-Agent、`fbp`、`fbc` 或完整 Meta 响应体。
- `fbp`、`fbc`、来源 URL、IP 和 User-Agent 逐项校验；非法可选归因直接丢弃，不得阻断配对主业务。
- 来源 URL 只接受 HTTP/HTTPS、拒绝用户信息，必须与渠道域名一致，并移除查询参数与片段。
- 只有请求真实远端为私网或回环可信反代时采用其覆盖写入的 `X-Real-IP`；公开直连请求忽略该头。
- 成功或永久失败后立即清理不再需要的匹配字段；任何未完成行达到七天硬期限时由独立任务终止并清理，不受投递开关影响。

## 领取、重试与失败处理

1. 创建配对会话的事务同时创建三条快照：`LEAD=PENDING`，其余两条为 `WAITING`；唯一约束保证同一会话的重复状态转换不会产生重复阶段事件，既有活动手机号唯一键阻止并发创建第二个活动会话。
2. 协议受理状态提交时，把 `LOGIN_REQUEST` 从 `WAITING` 原子更新为 `PENDING`；登录成功事务把 `LOGIN_SUCCESS` 原子激活。重复回调不会重复激活。
3. 会话失败、终止或过期时，把尚未触发的 `WAITING` 记录改为 `CANCELED` 并清除匹配字段。
4. 调度器先跨租户扫描候选，并在每条发送前单独将该行 CAS 标记为 `LOCKED`；并发实例通过条件更新跳过已被其他实例领取的记录，避免批量预锁在串行 HTTP 等待期间提前过期。
5. 提交领取事务后读取当前有效 Facebook 配置并调用 Meta。
6. Meta 返回 `events_received > 0` 时标记成功。
7. 网络异常、超时、HTTP 429 和 5xx 使用有上限的退避重试。
8. 缺少配置、Token 无法解密、标准事件非法及不可恢复的 4xx 标记永久失败。
9. 发送中实例异常退出时，超过锁超时的记录可重新领取，仍复用原 `event_id`。
10. `SENT`、`DEAD` 或 `CANCELED` 都清除手机号摘要、IP、User-Agent、`fbp`、`fbc` 和来源 URL；七天硬期限清理同样执行该动作；所有错误只保存稳定错误码和截断后的脱敏摘要。

后台发送失败不会反向修改配对会话、账号或协议状态。

## 兼容与旧探测路径

- 前端删除旧探测入口、弹窗、类型和请求函数。
- 旧后端测试探测接口暂时保留，并继续受现有默认关闭开关保护，避免影响可能存在的外部调用；正式上报不调用该接口，也不携带测试事件码。
- 现有最近探测字段保持不变，本次不删除列或索引。
- 其他推广平台和现有渠道分页、新增、编辑、删除逻辑保持不变。

## 验证

- 后端目录测试：18 个事件完整、顺序稳定、三个字段拒绝非标准事件。
- Flyway/H2 测试：表、唯一约束、领取索引和租户隔离真实执行。
- 触发测试：三个业务节点各插入一次，失败路径不插入，重复回调不重复。
- 客户端测试：使用本地 HTTP Server 验证正式负载、Bearer Header、无 `test_event_code`、无明文手机号和错误映射。
- 调度测试：成功、可重试、永久失败、锁超时恢复和匹配字段清理。
- 前端测试：18 个选项来自 API，三个下拉框共用目录，旧探测入口消失，配对请求携带可选归因参数。
- 运行定向 Maven 测试、H2 Mapper/Flyway 测试、前端单测、typecheck、lint/build 和 `git diff --check`。
- 本地自动化测试不向真实 Meta Pixel 发事件；部署到明确的测试渠道后，通过 Meta Events Manager 核对正式业务事件。

## 非目标

- 不修改协议层代码或 Kafka 协议。
- 不实现 TikTok、快手或 MGSKY Ads 的服务端事件 API。
- 不伪造订单、金额、币种或其他业务不存在的数据。
- 不在本任务中删除旧探测数据库列或执行线上部署。
