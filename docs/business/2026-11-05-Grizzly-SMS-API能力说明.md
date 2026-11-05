# Grizzly SMS 接码 API 能力说明

日期：2026-11-05。用途：说明接码平台的接口职责、参数、状态、费用语义，以及 Armada 当前客户端覆盖情况。

Grizzly SMS 提供号码采购、短信接收查询和订单管理。取得号码或验证码不代表目标应用接受注册；接码订单完成也不是目标账号注册完成的证明。当前 Armada 已有可复用的 Java HTTP 客户端，账号余额、实时库存与运行环境启用情况需要实际查询确认。

## 1. 接入形式与鉴权

| 项目 | 内容 |
| --- | --- |
| 官方 API 主机 | `https://api.grizzlysms.com` |
| 当前客户端使用路径 | `/stubs/handler_api.php` |
| HTTP 方法 | GET；其中购号和状态变更具有副作用 |
| 操作选择 | 查询参数 `action` |
| 鉴权 | 服务端提供查询参数 `api_key` |
| 返回形式 | 按 action 返回文本或 JSON；不能仅凭 HTTP 200 判断业务成功 |
| 密钥归属 | 当前为服务端平台级配置，不由浏览器或订单请求传入 |

官方文档声明与 sms-activate API 兼容，并列出了号码、激活状态、报价和活动订单等能力。兼容性声明不能代替具体 action 的参数与响应校验。[官方 API 文档](https://grizzlysms.com/cn/docs)

当前专用 HTTP 客户端固定官方 HTTPS 地址，关闭自动重试、重定向、Cookie 和 HTTP 认证重发。完整 URL 含密钥，不能写入访问日志、异常 cause 或 HTTP wire 日志。[GrizzlySmsConfiguration.java](../../armada-api/src/main/java/com/armada/boot/config/GrizzlySmsConfiguration.java)

## 2. 能力与现有适配覆盖

下表“已实现”表示当前 Java 客户端存在真实 HTTP 调用和解析代码，不表示已经核验某个充值账户或完成本次真实采购。

| 能力 | 供应商 action | 当前 Java 方法 | 状态与使用边界 |
| --- | --- | --- | --- |
| 余额 | `getBalance` | `getBalance()` | 已实现；返回 `BigDecimal`，不自行推定币种 |
| 服务目录 | `getServicesList` | `getServices()` | 已实现；按实时目录确定服务 code |
| 国家目录 | `getCountries` | `getCountries()` | 已实现；目录 ID 与国际电话区号分开 |
| 基础报价 | `getPrices` | `getPrices(service, country)` | 已实现；查询明确服务与国家的报价/库存 |
| 价格档位 | `getPricesV2` | `getPriceTiers(service, country)` | 已实现；提供档位价格及库存 |
| 商家与价格匹配 | `getPricesV3` | `getPriceTiers(service, country)` | 已实现；与 V2 结果组合，不构成原子库存快照 |
| 购买号码 | `getNumberV2` | `acquireNumber(request)` | 已实现；可能产生费用，要求明确最高单价 |
| 查询验证码 | `getStatus` | `getStatus(activationId)` | 已实现；一次查询，上层控制轮询 |
| 修改订单状态 | `setStatus` | `setStatus(activationId, update)` | 已实现；精确校验对应成功 ACK |
| 文本版取号 | `getNumber` | 无公开适配方法 | 官方文档列出；当前代码选择 V2 |
| 扩展短信状态 | 具体 action 待核实 | 无 | 官方文档列出“获取激活状态 v2”；现有代码未解析其详细结构 |
| 活动订单列表 | 具体 action 待核实 | 无 | 官方列出活动订单能力；当前客户端未接入，使用前须核实完整契约 |

覆盖事实以 [GrizzlySmsClient.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/GrizzlySmsClient.java) 的方法和 `Action` 枚举为准；状态与数值解析见 [GrizzlySmsResponseParser.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/GrizzlySmsResponseParser.java)。

## 3. 购号请求与结果

### 3.1 参数含义

| 参数 | 当前客户端要求 | 含义与校验 |
| --- | --- | --- |
| `service` | 必填 | 供应商服务 code；当前业务按实时目录名称识别 WhatsApp，不以历史 code 代替目录 |
| `country` | 必填 | 供应商国家 ID；底层购号接受 `any`，查询报价要求明确 ID |
| `maxPrice` | 必填 | 允许的最高单价，必须为正数，按十进制金额传递 |
| `minPrice` | 可选 | 最低单价，可为零且不能大于最高价 |
| `providerIds` | 可选 | 纳入采购的数字商家 ID 列表 |
| `exceptProviderIds` | 可选 | 排除的数字商家 ID 列表 |
| `phoneException` | 可选 | 排除的号码前缀列表；按客户端格式限制校验 |

官方取号页说明了价格和商家筛选参数。[购号参数说明](https://grizzlysms.com/docs/activation) 当前请求模型与实际序列化见 [GrizzlyNumberRequest.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/model/GrizzlyNumberRequest.java) 和客户端 `purchaseParameters` / `appendOptions`。

当前上层注册目录只开放美国渠道；这是 Armada 的现行业务过滤，不是 Grizzly 平台国家能力的上限。不能把底层支持 `any` 理解为当前管理页面已经开放所有国家。[AccountRegistrationServiceImpl.java](../../armada-api/src/main/java/com/armada/account/service/impl/AccountRegistrationServiceImpl.java)

### 3.2 购号结果

当前 V2 解析器要求取得 `activationId`、`phoneNumber`、`activationCost` 和 `currency`。应用归一化后分别保存订单标识、号码、实际费用和数字币种；号码不能作为唯一的供应商订单标识。

可选元数据包括 `countryCode`、`activationTime`、`activationEnd`、`activationCancel`、`canGetAnotherSms`。未标注时区的时间保留原文；后续收码标记不能凭字段名称自动触发任何操作。[GrizzlyActivation.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/model/GrizzlyActivation.java)

现有采购请求没有供应商侧请求幂等键。连接中断或响应解析失败时，即使本地尚未取得订单 ID，也不能认定供应商没有创建订单；该情况应保留结果未知，禁止盲目再次购号。

## 4. 验证码查询

查询输入为已确认归属的 `activationId`。`getStatus()` 不自动循环、不提交验证码，也不申请目标应用重新发码。

| 原始响应 | 归一化状态 | 可交付内容 |
| --- | --- | --- |
| `STATUS_WAIT_CODE` | `WAITING_CODE` | 当前无验证码 |
| `STATUS_WAIT_RETRY:旧码` | `WAITING_RETRY` | 旧码仅为 `previousCode`，不能当作新验证码 |
| `STATUS_WAIT_RESEND` | `WAITING_RESEND` | 当前无新码，不自动执行重发 |
| `STATUS_OK:验证码` | `RECEIVED` | `code` 为本次可交付验证码 |
| `STATUS_CANCEL` | `CANCELLED` | 供应商订单已取消 |
| 未知或畸形文本 | 错误 | 不归类为等待，也不制造验证码 |

例如测试输入 `STATUS_OK:004271` 的验证码应保持字符串 `004271`，不能转换成整数。该值为说明编码规则的合成数据，不是实际订单验证码。

普通任务列表和日志不返回 OTP。需要交付验证码的入口应单独校验调用权限与订单归属，设置短期有效期和禁止缓存；新码、旧码与交付 ACK 分开记录。[GrizzlySmsStatus.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/model/GrizzlySmsStatus.java)

## 5. 订单状态变更

下表对应当前客户端的精确操作和 ACK；异常 ACK 不能当作成功。

| `status` | 操作 | 预期 ACK | 语义 |
| --- | --- | --- | --- |
| `1` | READY | `ACCESS_READY` | 通知接码平台目标服务已申请短信；不代替目标服务发码 |
| `3` | REQUEST_ANOTHER_SMS | `ACCESS_RETRY_GET` | 等待后续短信；须先确认订单支持及明确动作意图 |
| `6` | COMPLETE | `ACCESS_ACTIVATION` | 结束接码订单 |
| `8` | CANCEL | `ACCESS_CANCEL` | 申请取消；费用结算按供应商订单与账户记录核对 |

定义见 [GrizzlyStatusUpdate.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/model/GrizzlyStatusUpdate.java)。官方维护的 SDK 仓库也列出查询状态、完成和取消能力。[官方 Grizzly SMS MCP 仓库](https://github.com/GrizzlySMS-Git/grizzly-sms-mcp)

接码完成、调用方已接收验证码、取消确认、退款确认是不同事实。不能因完成 ACK 推断目标账号已经注册，也不能因取消 ACK 推断余额已恢复。

## 6. 费用、库存与结果未知

- 金额使用 `BigDecimal`；余额和报价缺少明确币种时保留“币种未确认”，不得根据网页显示符号自行换算。
- V2 档位和 V3 商家信息来自两次查询。报价不预留库存，商家总库存不等于某个价档可买数量。
- 当前 worker 会比较实际成交价和任务选择价；不一致时进入订单处理流程。这是上层策略，不能用来证明供应商报价永不变化。
- `NO_NUMBERS` 是明确拒绝，与购号响应丢失不同。是否再次请求应由有次数上限的业务策略决定。
- `HTTP_ERROR`、`TRANSPORT_ERROR`、`INVALID_RESPONSE` 在副作用操作中可能表示结果未知；不得交给通用 HTTP 重试器。
- 未来使用活动订单列表核对时，须校验账户、时间、订单和业务归属。并发场景下不能凭“新出现的一条订单”认领号码。

## 7. 错误分类

| 类别 | 当前错误码举例 | 处理要求 |
| --- | --- | --- |
| 配置与凭据 | `DISABLED`、`CREDENTIALS_MISSING`、`MUTATIONS_DISABLED`、`BAD_KEY`、`NO_KEY` | 修正服务端配置，不回显密钥 |
| 明确业务拒绝 | `NO_BALANCE`、`NO_NUMBERS`、`BAD_SERVICE`、`BAD_STATUS`、`NO_ACTIVATION` | 保留明确失败分类，不伪报成功 |
| 服务限制 | `REGION_RESTRICTED`、`SERVICE_PROHIBITED` | 停止该操作，返回可理解的原因 |
| 外部异常 | `PROVIDER_ERROR`、`HTTP_ERROR`、`TRANSPORT_ERROR`、`INVALID_RESPONSE` | 结合 `isOutcomeUnknown()` 判断是否需要核对 |

错误正文只使用固定分类和安全描述，不把原始响应、号码、验证码或含密钥 URL 直接拼入错误消息。[GrizzlySmsFailure.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/exception/GrizzlySmsFailure.java)

## 8. 当前运行配置

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `GRIZZLY_SMS_API_KEY` | 空 | 服务端注入；配置即启用查询与取号，留空则功能不可用（无其他开关） |
| `GRIZZLY_SMS_CONNECT_TIMEOUT` | `5s` | 连接与连接池等待上限 |
| `GRIZZLY_SMS_READ_TIMEOUT` | `20s` | 响应与 socket 读取上限 |

超时配置允许 1 毫秒至 60 秒，属于分阶段超时，不是整个订单的总期限。当前关闭采购开关也会阻止旧订单完成/取消，应在停用安排中明确如何处理在途订单。[GrizzlySmsProperties.java](../../armada-api/src/main/java/com/armada/platform/sms/grizzly/GrizzlySmsProperties.java)

## 9. 证据与验证定位

| 证据 | 可确认的事实 | 使用位置 |
| --- | --- | --- |
| 官方 API 文档，2026-11-05 查阅 | 平台接口类别、公共鉴权形式与取号参数 | 确认供应商能力，不作为某账户实时可用性证明 |
| `GrizzlySmsClient` / Parser / models | 当前实现的 action、参数、结果、精度和错误语义 | 确定可直接复用的方法及尚缺适配 |
| `GrizzlySmsConfiguration` / Properties | 默认开关、超时和专用 HTTP 客户端行为 | 明确密钥注入、费用操作和日志边界 |
| 现有 Grizzly 测试源码 | 已编写的协议、错误、精度和禁重发断言 | 为后续改动选择回归范围 |

本地代码基线：`armada/1.0.3-snapshot@71721bfc6ae495386e2b61e13ae65dcce1cf40b3`。既有文档保留了早期“价格 V2/V3 未实现”的叙述，当前能力以上述源码为准。

适配器离线回归入口如下，执行时使用测试专属配置及本机 HTTP 替身，不提供真实密钥：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='Grizzly*Test' test
```

本次仅核查文档与源码，未执行该命令，也未调用付费账户。实时余额、可购渠道、成交价格、短信到达和退款结果均须分别以实际授权查询或订单验证。
