# 手机上传到控端：选择分组联调契约

2026-09-07 用户确认的新流程：点击“上传到控端” → 查询分组 → 选择分组 → 上传账号全参 → 控端导入到所选分组。本文覆盖旧提示词中“令牌固定分组、请求仅含 phone/payload、只开放一条路径”的规定。

范围：Armada 接口与手机调用约定。手机实现继续在 `wa-biz-compat-v8-extension` 的独立工作树完成，不覆盖该仓其他 agent 的在途修改。

## 入口域名

手机配置一个 HTTPS 基础地址，例如 `https://ingest.example.com`，两个接口共用它，默认端口 443。

自己的域名：在 DNS 服务商添加 A 记录，主机名 `ingest` 指向 test1 公网 IP `65.2.123.53`；服务器安装覆盖该子域名的可信 TLS 证书，再启用专用 nginx。不要为设备入口向公网增加 80、8080 或管理端口。

联调候选域名：`ingest.65.2.123.53.nip.io`。它使用 [nip.io 的 IP 域名解析服务](https://nip.io/)，仍须部署可信证书和 HTTPS 服务，不能仅凭域名可解析就视为接口可用。证书可通过 DNS 验证或 [TLS-ALPN-01 的 443 验证](https://go-acme.github.io/lego/obtain/tlsalpn01/index.html)申请。

当前状态及提交以本次 [变更记录](../.harness/changes/2026-09-07-device-import-group-selection.md) 为准；文中的候选地址不表示已启用。

## 1. 查询分组

```http
GET /api/device-imports/groups
X-Ingest-Token: <运行环境安全配置的令牌>
Accept: application/json
```

无 query、无请求体，不需要管理员 Bearer、Cookie 或 `X-Tenant-Code`。

成功返回 **HTTP 200，直接 JSON 数组**，不套 `{code,message,data}`：

```json
[
  {"id": 123, "name": "手机上传组"},
  {"id": 456, "name": "测试账号组"}
]
```

上面的 ID 和名称仅为结构示例。只返回令牌所属启用租户的未删除分组，每项只有 `id`、`name`，按名称和 ID 排序；空列表为 `[]`，查询不会创建分组。

手机显示名称、保存所选 `id`。空列表提示到控端创建分组；查询失败允许重试；用户取消时不上传、不登出。

## 2. 上传所选分组和账号全参

```http
POST /api/device-imports
X-Ingest-Token: <与查询分组相同的令牌>
Content-Type: application/json
Accept: application/json
```

请求体必须**恰好三个字段**：

```json
{
  "accountGroupId": 123,
  "phone": "<7至15位纯数字手机号>",
  "payload": "<手机复制全参产生的完整 JSON 单行原文>"
}
```

- `accountGroupId`：分组列表选中的正整数 ID，不能用名称、字符串 ID、数组或整个 list 代替；必填，不自动回退分组。
- `phone`：纯数字字符串，必须与全参中的纯数字 `jid` 一致。
- `payload`：全参 JSON 的原始单行**字符串**，不能传嵌套对象或先转六段；外层用 JSON 序列化器正确转义。
- 请求总大小上限 128 KiB；拒绝未知字段、重复 key、尾随对象和多行全参。旧的两字段请求返回 400。
- 租户只能来自令牌；机型、申报账号类型和 IP 策略仍由服务端令牌配置提供，手机不能覆盖。

成功只有 **HTTP 200** 且响应匹配下面结构才视为受理：

```json
{"batchId": 123, "onlinePhase": "QUEUED"}
```

`batchId` 为数字。这表示事务已提交、进入现有自动上线队列，不表示 WhatsApp 已上线。复用现有全参转六段、原文保存和约 10 秒间隔的调度，不新增同步登录或额外队列。

分组在上传时重新验证；已删除、不存在或属于其他租户均拒绝，账号、凭据、批次和明细不落库。同租户已存在或正在上线的号码返回 409，不覆盖凭据。

## 3. 失败与手机行为

两个接口的失败统一为实际 **4xx/5xx**，body 仅 `{ "message": "可读原因" }`，不解析管理员响应信封。响应均 `Cache-Control: no-store`。

| HTTP | 含义 / 手机处理 |
| --- | --- |
| 400 | 请求或选择无效；上传失败可刷新分组并检查全参，不能自动换组 |
| 401 | 令牌缺失或无效；检查安全配置 |
| 404 / 405 | 路径或方法不正确 |
| 406 / 415 | Accept 或 Content-Type 不支持 |
| 409 | 账号已导入或正在上线；到控端查看，不当作新上传成功 |
| 413 | 请求超过上限 |
| 5xx | 配置或服务不可用；稍后重试，超时先核对控端受理结果 |

查询成功、用户选组、网络发送完成都不能触发官方登出。只有上传 200 且合法受理响应后才沿用手机既有成功后登出流程。查询失败、上传失败、超时或响应不合法时保留登录状态并提示重试。

手机应在后台执行网络请求，查询使用无缓存会话；选择后上传到同一基础地址并使用同一令牌。不要把令牌、全参、私钥、请求头或 body 写进源码、IPA 固定常量、日志或 Git；通过受保护的运行配置与 iOS Keychain 等安全存储提供令牌。原先 URL/token 占位常量不能替换成提交到代码的真实秘密。

## 4. 服务端与网关约束

`ARMADA_DEVICE_INGEST_CLIENTS_JSON` 每项只允许 `token`、`tenantId`、`deviceOs`、`accountType`、`ipRegion`、`ipAllocationMode`。删除旧配置中的 `accountGroupId`；它已由上传请求必填提供。租户停用时两个接口都不可用。

专用 nginx 仅代理 `GET /api/device-imports/groups` 和 `POST /api/device-imports`；各自 OPTIONS 返回 204 与对应 Allow，不启用 CORS。拒绝 query、尾斜杠、编码别名、子路径和其他 API，清除管理员身份头；保持令牌鉴权、128 KiB 限额、无缓存和无凭据日志。

## 5. 交付与验收

- 控端：接口、安全链、严格 DTO、复用分组 Service/Mapper 与现有导入事务，无 schema 或协议改造。
- 手机端：获取分组、选择交互、三个字段上传、受理响应校验、失败保留登录、成功后登出。
- 配置：确认 test1 域名、可信证书和令牌对应租户；秘密只写受保护的运行环境。
- 验收分层记录：本地测试、主仓提交、测试服部署、公网 HTTPS 查询/错误请求、真实账号落组、自动上线、手机登出。每层没有实际证据就标为未验证。
