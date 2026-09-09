# 手机上传到控端：手动输入分组联调契约

当前流程：点击“上传到控端” → 手动输入控端已有分组名称 → 确认 → 上传账号全参 → 控端按名称导入。设备端不查询、显示或缓存租户的分组列表。

范围：Armada 接口与手机调用约定。手机实现继续在 `wa-biz-compat-v8-extension` 的独立工作树完成，不覆盖该仓其他 agent 的在途修改。

## 入口域名

手机配置一个 HTTPS 基础地址，例如 `https://ingest.example.com`，上传和退出确认两个接口共用它，默认端口 443。

自己的域名：在 DNS 服务商添加 A 记录，主机名 `ingest` 指向 test1 公网 IP `65.2.123.53`；服务器安装覆盖该子域名的可信 TLS 证书，再启用专用 nginx。不要为设备入口向公网增加 80、8080 或管理端口。

联调域名：`ingest.65.2.123.53.nip.io`。它使用 [nip.io 的 IP 域名解析服务](https://nip.io/)；2026-09-07 已通过 [TLS-ALPN-01 的 443 验证](https://go-acme.github.io/lego/obtain/tlsalpn01/index.html)申请、安装 Let’s Encrypt 正式证书并配置续期。公网证书校验通过，但临时验证服务已清理，正式 HTTPS 网关和新后端仍待令牌配置后启用，不能仅凭证书就绪视为业务可用。详见 [证书验收记录](../.harness/changes/2026-09-07-device-ingest-certificate.md)。

当前手输分组名契约以 [2026-09-09 变更记录](../.harness/changes/2026-09-09-device-import-group-name.md) 为准；旧选组记录只保留历史证据，文中的候选地址不表示已启用。

## 1. 输入分组名称

测试人员在 V8 输入控端预先创建的分组名称。输入去除首尾空白后不能为空，最长 100 个 Unicode 字符。V8 不提供分组列表、联想、自动完成或分组创建能力；取消时不上传、不登出。

## 2. 上传分组名称和账号全参

```http
POST /api/device-imports
X-Ingest-Token: <运行环境安全配置的令牌>
Content-Type: application/json
Accept: application/json
```

请求体必须**恰好三个字段**：

```json
{
  "groupName": "手机上传组",
  "phone": "<7至15位纯数字手机号>",
  "payload": "<手机复制全参产生的完整 JSON 单行原文>"
}
```

- `groupName`：手工输入的非空字符串，服务端去除首尾空白后，在令牌所属租户内按名称查找未删除分组；最长 100 个 Unicode 字符。不能传 ID、数组或对象，必填且不自动回退分组。
- `phone`：纯数字字符串，必须与全参 `phone` 一致；`jid` 必须为同一手机号或 `<phone>@s.whatsapp.net`。
- `payload`：全参 JSON 的原始单行**字符串**，不能传嵌套对象或先转六段；外层用 JSON 序列化器正确转义。
- 请求总大小上限 128 KiB；拒绝未知字段、重复 key、尾随对象和多行全参。旧的两字段请求返回 400。
- 租户只能来自令牌；机型、申报账号类型和 IP 策略仍由服务端令牌配置提供，手机不能覆盖。

成功只有 **HTTP 200** 且响应匹配下面结构才视为受理：

```json
{"batchId": 123, "onlinePhase": "WAITING_LOGOUT"}
```

`batchId` 为数字。这表示事务已提交、正在等待手机退出，不表示 WhatsApp 已上线。复用现有全参转六段、原文保存和约 10 秒间隔的调度，不新增同步登录或额外队列。

分组在上传时验证；已删除、不存在或属于其他租户时返回 HTTP 404 `{"message":"分组不存在"}`，账号、凭据、批次和明细不落库。同租户已存在或正在上线的号码返回 409，不覆盖凭据。

### 退出完成确认

手机将待交接 batchId、phone 与连接保存在本扩展 Keychain，不保存全参。固定宿主的原账号 provider.isUserLoggedIn 明确为 false，且官方欢迎页可见、该页 provider 也为 false，连续观察三次后才确认；官方退出方法返回或 completion 回调不能单独作证。

```http
POST /api/device-imports/logout-confirmed
X-Ingest-Token: <本机钥匙串中的上传令牌>
Content-Type: application/json
```

```json
{"batchId":123}
```

合法响应为 HTTP 200 `{"batchId":123,"onlinePhase":"QUEUED"}`。服务端行锁校验同租户、未删除、来源 device-import 的单条成功批次；仅 WAITING_LOGOUT→QUEUED。重复确认在已派发/已结算时仍返回 QUEUED 表示先前已放行，不重新上线。

没有确认时，自动调度、单个/批量手工上线都不允许提前接管。确认失败只重试该批次确认，不重传全参。手机重启后必须重新观察官方欢迎页及其 provider 的明确未登录状态，稳定后保存确认并重试；不能仅凭待办记录推断已退出。响应丢失可幂等重试。旧客户端不认识 WAITING_LOGOUT 时保持手机登录、控端等待，需更新客户端才能交接。

## 3. 失败与手机行为

两个接口的失败统一为实际 **4xx/5xx**，body 仅 `{ "message": "可读原因" }`，不解析管理员响应信封。响应均 `Cache-Control: no-store`。

| HTTP | 含义 / 手机处理 |
| --- | --- |
| 400 | 请求、分组名称格式或全参无效；不能自动换组 |
| 401 | 令牌缺失或无效；检查安全配置 |
| 404 | 分组不存在时 V8 显示“分组不存在”；其他 404 为路径不正确 |
| 405 | 请求方法不正确 |
| 406 / 415 | Accept 或 Content-Type 不支持 |
| 409 | 账号已导入或正在上线；到控端查看，不当作新上传成功 |
| 413 | 请求超过上限 |
| 5xx | 配置或服务不可用；稍后重试，超时先核对控端受理结果 |

输入分组名、确认分组名、网络发送完成都不能触发官方登出。只有上传 200 且合法 WAITING_LOGOUT 响应后才提供“退出并交接”。用户暂不退出时控端不得上线。分组不存在、上传失败、超时或响应不合法时保留登录状态并提示重试。

手机应在后台执行网络请求，使用无缓存会话上传。不要把令牌、全参、私钥、请求头或 body 写进源码、IPA 固定常量、日志或 Git；通过受保护的运行配置与 iOS Keychain 等安全存储提供令牌。原先 URL/token 占位常量不能替换成提交到代码的真实秘密。

## 4. 服务端与网关约束

`ARMADA_DEVICE_INGEST_CLIENTS_JSON` 每项只允许 `token`、`tenantId`、`deviceOs`、`accountType`、`ipRegion`、`ipAllocationMode`。删除旧配置中的 `accountGroupId`；分组名由上传请求必填提供。租户停用时两个接口都不可用。

专用 nginx 仅代理 `POST /api/device-imports` 和 `POST /api/device-imports/logout-confirmed`；各自 OPTIONS 返回 204 与对应 Allow，不启用 CORS。旧 `/api/device-imports/groups` 及其他 API 均返回 404。拒绝 query、尾斜杠、编码别名和子路径，清除管理员身份头；保持令牌鉴权、128 KiB 限额、无缓存和无凭据日志。

## 5. 交付与验收

- 控端：接口、安全链、严格 DTO、按租户和名称查询现有分组并复用现有导入事务，无 schema 或协议改造。
- 手机端：手动输入并确认分组名、三个字段上传、404“分组不存在”提示、失败保留登录、成功后退出并确认交接。
- 配置：确认 test1 域名、可信证书和令牌对应租户；秘密只写受保护的运行环境。
- 验收分层记录：本地测试、主仓改动、测试服部署、公网 HTTPS 错误请求、真实账号按名称落组、自动上线、手机登出。每层没有实际证据就标为未验证。
