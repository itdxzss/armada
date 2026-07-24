# 渠道 Facebook CAPI 探测设计

## 目标与范围

- 新增 `POST /api/promotion-channels/probe/{id}`，供渠道列表“探测”按钮调用。
- 功能默认关闭；部署环境只有在可信网络边界内显式设置 `ARMADA_PROMOTION_TRACKING_FACEBOOK_PROBE_ENABLED=true` 后才允许调用。
- 本期只实现 Facebook CAPI 测试事件探测；TikTok、快手、MGSKY Ads 返回平台不支持的业务错误。
- 请求只包含 Meta Events Manager 生成的 `testEventCode`，不得传 Pixel ID 或 Access Token。
- 探测必须使用测试事件码，禁止向正式广告事件流发送探测数据。
- 不修改公共 HTTP、异常、租户或响应逻辑，不新增数据库表和索引。

## 接口契约

请求：

```http
POST /api/promotion-channels/probe/51
Content-Type: application/json

{"testEventCode":"TEST12345"}
```

成功与平台失败均返回统一业务数据：

```json
{
  "success": true,
  "status": "NORMAL",
  "trackingId": "123456789012345",
  "accessTokenConfigured": true,
  "eventName": "PageView",
  "eventId": "probe_...",
  "errorCode": null,
  "errorMessage": null,
  "probedAt": 1784692800000
}
```

- `success=true` 表示 Meta 接受至少一条测试事件。
- 未配置 Pixel/Token、非 Facebook 平台、网络、Token、Pixel 权限或平台拒绝都属于探测结果，返回 `success=false/status=ABNORMAL` 和脱敏错误，使前端始终可以展示失败详情弹窗。
- 失败结果同时返回非敏感的 Pixel/追踪 ID（可空）、`accessTokenConfigured`、事件名和事件 ID（可空）；Access Token 本身永不返回。
- 渠道不存在、测试码非法、重复探测属于请求前置错误，抛项目统一 `BusinessException`。
- 响应、日志和异常均不得出现 Access Token、密文、指纹或完整平台原始响应。

## 组件边界

- `PromotionChannelController`：只接收渠道 ID 和 DTO，包装统一响应。
- `PromotionChannelService` / `PromotionChannelServiceImpl`：校验渠道与平台、抢占探测状态、解密 Token、调用客户端、回写结果。
- `FacebookCapiProbeClient`：渠道域内的 Facebook 出站能力接口。
- `HttpFacebookCapiProbeClient`：使用 Spring `RestClient` 发送测试事件，处理连接/读取超时并映射脱敏错误。
- `PromotionChannelMapper`：读取专用敏感配置投影，原子写入探测中状态，写入最终结果。
- `PromotionTokenCipher`：增加与现有 AES-256-GCM 密文格式严格对称的解密方法，并校验密钥版本。

## 数据流与并发

1. 按当前租户和渠道 ID 查询渠道、域名及有效追踪配置；SQL 显式列出字段，不使用 `SELECT *`。
2. 渠道未删除但平台不支持或配置不完整时直接生成 `ABNORMAL` 详情，不调用 Meta，也不把“未实际探测”误写成一次平台探测失败。
3. 配置完整时校验 `testEventCode`，再按平台、Pixel ID 和 Token 指纹条件更新 `last_probe_status=0` 与 `last_probed_at=now`；仍处于超时窗口内或距离上次完成不足 30 秒时不允许重复调用。
4. 解密 Token，在事务外调用 Meta Graph API，避免持有数据库锁等待外部网络。
5. 使用 `PageView`、唯一 `event_id`、渠道访问地址和合成 SHA-256 `external_id` 构造测试事件；不读取或上传真实用户信息。
6. Meta 接受至少一条事件时回写成功状态、事件名、事件 ID 和时间；否则回写失败状态、脱敏错误码/摘要和时间。最终回写再次校验平台、Pixel ID、Token 指纹、“探测中”状态及本次抢占开始时间；配置变化或新一轮探测已接管时，旧结果作废。
7. 配置编辑继续清空旧探测结果，确保页面不会展示过期结论。

## 安全与错误映射

- Token 仅在 Service 调用期间解密并传给出站客户端，不写日志、不进入 VO。
- Token 解密后以常量时间重新校验数据库指纹，防止合法密文被跨配置行移植；包含 Token 的内部命令字符串表示固定脱敏。
- `testEventCode` 只允许 `TEST` 前缀及 ASCII 字母、数字、下划线、连字符，最大 64 字符。
- 生产 Graph API 地址强制为 `https://graph.facebook.com` 官方根地址；只有包内测试构造器允许本地 HTTP Server，不存在生产配置旁路。连接和读取超时单项限制在 1–30000 毫秒、总和不超过 45000 毫秒；Pixel ID 先做字符白名单校验。
- HTTP 401/403 映射为 `TOKEN_INVALID_OR_FORBIDDEN`；404 映射为 `PIXEL_NOT_FOUND`；429 映射为 `RATE_LIMITED`；连接/读取超时映射为 `NETWORK_TIMEOUT`；其余平台错误返回稳定通用摘要。
- 不记录 Meta 原始响应体，避免平台错误回显敏感配置。

## 验证

- Token 加解密往返、密钥版本不匹配和密文篡改单测。
- Controller 路由、请求体和无 Token 出参测试。
- Service 成功、平台失败、配置缺失、非 Facebook、重复探测测试。
- HTTP 客户端请求结构、成功响应和常见 HTTP 错误映射测试。
- Mapper XML 契约测试锁定租户表、软删除、原子状态条件和敏感字段不进入普通查询。
- 定向 Maven 测试和 `mvn -DskipTests package`；本地数据库环境可确认时再运行真库 DbTest。

## 非目标

- 不实现 TikTok Events API。
- 不检测 DNS、落地页 HTTP 可用性、浏览器 Pixel 脚本或 Facebook 域名认证。
- 不创建独立操作日志表；最近探测字段和脱敏应用日志作为当前阶段诊断依据。
