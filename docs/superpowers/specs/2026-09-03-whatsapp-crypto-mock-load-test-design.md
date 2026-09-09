# WhatsApp 加密回环压测设计

> 日期：2026-09-03
>
> 状态：`ANDROID_LOCAL_PASS / WEB_TEST1_CRYPTO_CANARY_PASS / BUSINESS_CANARY_PENDING`
>
> 用户确认口径：云控账号池可以用于测试，但压测不能向真实 WhatsApp 发送；实例必须执行真实协议编码和加解密，以便测量 CPU、内存与积压。

## 1. 当前决定

第一阶段不建设独立的完整 WhatsApp Mock 服务。Android 使用进程内 Crypto Loopback；Web 使用与 PM2 生产进程隔离的 Baileys Crypto Loopback：

本决定仅针对密码学成本校准。拉群板块 v2 另建 `stateful-protocol-sim`，维护账号、群、成员角色、审批、邀请码、禁言、
封禁、消息和 operation 物理账本；它负责业务状态机和幂等验证。Crypto Loopback 不维护这些事实，不得作为普通拉群、
速拉群或建群营销业务 PASS 的依据；两者可在后续通过成本插件组合，但报告必须分开。

```text
Armada 真实任务、调度和 Kafka
          |
          v
Android 受控账号实例
  真实消息构造
  真实 Signal 出站加密
  真实 XMPP 编码
  真实 Noise 加密
          |
          v
进程内 net.Pipe 回环
  真实 Noise 解密
  解析 message id
  生成 Server ACK / IQ result
  真实 Noise 加密
          |
          v
实例真实 Noise 解密和 ACK 状态机
```

```text
Web 独立压测进程
  WhatsApp protobuf 编码
  真实 Signal session 加密
          |
          v
回环 peer
  真实 Signal 解密并核对原文
  BinaryNode + Noise AES-GCM 解密
  生成同 message id ACK
  Noise AES-GCM 加密返回
          |
          v
Baileys Noise handler 解密并核对 ACK
```

这条链路足够先测账号实例的消息构造、密钥读取与 Signal ratchet、Noise 加解密、协程、缓存、Kafka 和 ACK 处理开销。它不代表 WhatsApp 公网 RTT、服务端限流、风控或真实送达能力。

## 2. Android 第一阶段实现

Android 仓库增加 `ANDROID_CRYPTO_LOOPBACK_MODE=ack`，并用 `ANDROID_CRYPTO_LOOPBACK_ACCOUNT_SHA256` 精确选择账号：

- 复用 `NoiseNetWork.dialWA`，把正式拨号替换为 `net.Pipe`；
- 内存两端执行真实 Noise XX、AES-GCM、SHA-256 握手并得到成对 cipher state；
- 客户端继续使用生产 `WASegment` 加密和分帧；
- 回环端使用同一 `WASegment` 解密、解析 XMPP，并按原 message id 返回加密 ACK；
- 返回登录 `success` 和通用 IQ `result`，使已加载的受控账号进入发送链路；
- 任何未知的非空模式值都会封死 WhatsApp 拨号并报错，不会回退真实网络；
- `/debug/vars` 暴露连接、明文/线长、ACK、IQ 和错误计数。

当前本地测试已经证明：Noise XX 建链成功；message 经过加密线传输；回环端成功解密；返回的 ACK 再次加密；实例成功解密并得到相同 message id；密文线长大于明文长度。

## 3. Web 第一阶段实现

Web 协议仓增加独立脚本 `scripts/web-crypto-loopback.mjs`，不把 mock 开关塞进生产 socket：

- 使用线上相同的 Baileys 7.0.0-rc13 `proto`、Signal repository、BinaryNode、Curve25519、HKDF、AES-GCM 和 Noise handler；
- 每条消息建立在真实 Signal ratchet 上，回环 peer 解密后逐字节核对 protobuf 原文；
- message node 经过真实 Noise 加密，peer 解密后按同 message id 返回再次加密的 ACK；
- 多 lane 代表独立 Signal/Noise session，同一 ratchet 内保持顺序；
- 不创建 WASocket，不读取账号 creds、`.env`、Redis、MySQL 或 Kafka，不发起 WhatsApp、代理或 CDN 网络请求；
- 输出吞吐、CPU user/system、等效核利用率、RSS/heap 峰值以及两层明密文字节。

第一套 Web 协议机已经低流量实跑：Node 24.16.0、Baileys 7.0.0-rc13；1000 条、8 lanes、512-byte payload 得到 1000 个 ACK、0 error、约 3236 msg/s，回环进程峰值 RSS 152,834,048 bytes，峰值 heap 35,975,632 bytes。执行后 Web `/readyz` 正常，master、4 个 worker 与 traffic dashboard 均在线。

## 4. 密码学真实性口径

第一阶段的每条业务消息必须实际经过：

1. WhatsApp protobuf/XMPP node 构造；
2. 使用测试收件人的可用 Signal session 执行出站加密；
3. 使用当前连接 Noise cipher state 执行帧加密；
4. 回环端解开 Noise 并解析 message id；
5. 回环端用反向 Noise cipher state 加密 Server ACK；
6. 实例解密 ACK 并进入真实 ACK 处理器。

禁止在业务发送函数、Armada executor 或 C3 之前直接伪造成功结果。

第一阶段不声称完成 Signal 对端解密或实例侧入站 Signal 解密。需要验证这两项时再增加小型配对测试 peer，不能把 Noise 回环结果冒充完整双向 Signal 验证。

上句只适用于 Android 当前实现。Web 独立回环已经覆盖 synthetic peer 的 Signal 对端解密，但仍不代表真实 WhatsApp 设备发现、服务端证书握手和真实账号入站消息。

## 5. 零 WhatsApp 外发门禁

开始校准前必须同时满足：

1. 配置门禁：`ANDROID_CRYPTO_LOOPBACK_MODE=ack` 必须同时指定账号 User 的 SHA-256；多个哈希逗号分隔，只有专用隔离节点可显式用 `*`。未知模式、缺少选择器、坏哈希或孤立选择器都会在启动期失败关闭。
2. 网络门禁：测试实例禁止访问 WhatsApp/Meta 域名、IP 和公网代理出口，只放行 Kafka、Redis、DB、业务回调等必要依赖。
3. 运行审计：采集 DNS、连接目标和出口字节；出现任何 WhatsApp/Meta 连接立即停止并判 P0 FAIL。

账号代理不能绕过该门禁。第一阶段不压会触发 WhatsApp CDN 上传的媒体模板；只使用已经带好媒体元数据的路径或纯文本链接模板。

Web 独立回环不创建 WASocket，也不读取生产代理或账号凭据；因此可以在不重启共享 worker 的情况下先做密码学容量校准。正式 L1 全链仍需增加网络命名空间或 egress 审计，不能只相信脚本声明。

## 6. 第一轮真实环境校准

先在 test1 选择 1 个受控 Android 账号和 1 个已有可用 Signal session 的测试收件人：

1. 部署含回环代码的 Android 实例，但暂不接正式任务流量。
2. 设置单个受控账号哈希；在不影响共享节点其他账号的前提下，为该压测账号加网络层 WhatsApp/Meta 出口阻断，再启动回环模式。
3. 确认账号 ONLINE、回环 active connection 为 1、该账号真实 WhatsApp 连接为 0；数量不等于 1 时禁止下发。
4. 下发 1 条无媒体上传的链接消息，核对 Signal 加密、Noise 收发和业务 ACK。
5. 下发 1000 条校准消息，记录 CPU、RSS、GC、P95/P99、Kafka lag 和回环计数。
6. 对账命令、逻辑发送、ACK、任务计数和账务；任何差额都不进入下一档。

校准通过后才依次执行 0.1×、1×、2×短时冲击和 soak。

Web 已完成上机前的 1000 条 crypto smoke。正式 Web 0.1×/1×/2×/soak 仍需先冻结 Web 业务占比、消息大小、并发 session 数与持续时间；当前 1000 条结果只是正确性和低流量性能基线，不是稳定容量。

## 7. 诊断与验收

Android 继续使用现有 `cmd/perf-monitor`、pprof 和 `/debug/vars`。新增核心计数：

- `crypto_loopback_connections_active`
- `crypto_loopback_received_frames`
- `crypto_loopback_received_plaintext_bytes`
- `crypto_loopback_received_wire_bytes`
- `crypto_loopback_message_acks`
- `crypto_loopback_iq_results`
- `crypto_loopback_errors`

第一轮通过条件：

- WhatsApp/Meta egress 连接数和字节数为 0；
- 每次业务提交都有回环收到的 Noise 帧；
- Noise 线长大于对应明文长度；
- Server ACK 成功数与业务提交成功数一致；
- 回环错误为 0；
- 同 commandId 物理提交不超过 1；
- 丢命令、逻辑重复、跨租户、账务差额和计数漂移均为 0。

Web crypto smoke 的独立通过条件为：`ackCount=messages`、`errors=0`、Signal/Noise 密文大于对应明文、解密原文一致、执行后生产 PM2 健康。它不包含 Armada 命令、账务和业务计数，所以不能单独签署全链验收。

## 8. 后续阶段

Android 校准通过后按需增加：

1. ACK 延迟、丢弃、错误和断连场景控制；
2. DELIVERED、READ 和迟到/重复回执；
3. 独立配对 Signal peer，覆盖对端 Signal 解密和实例入站 Signal 解密；
4. Web 全链隔离 worker，将 Armada 命令、真实 Web 账号状态副本和回环密码学 peer 接起来；
5. 媒体隔离 endpoint。

只有当进程内回环的 CPU 偏差无法接受，或必须模拟完整双向 Signal/设备发现时，才拆成独立 Mock 进程。

## 9. 未被证明的事项

- WhatsApp 真实公网时延、服务端排队、限流和风控；
- 真号码注册状态和真实收件设备行为；
- WhatsApp CDN 的真实媒体能力；
- HIGH 蓝标真实性与真实钱包扣费；
- DELIVERED、READ 和入站 Signal 解密。

这些事项需要后续受控用例或获准的 L3/L4 小流量 canary，不能从第一阶段回环容量推断。
