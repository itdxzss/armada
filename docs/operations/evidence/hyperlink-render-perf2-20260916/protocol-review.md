# perf2 按钮标题协议复核

日期：2026-09-16。结论：图片和标题共享 Header 在所核对的 Protobuf schema 中合法；本次未确定手机不显示标题的最终触发条件。不得将字段共存、版本差异、联系人状态直接写为已证实原因。

## 消息结构

任务普通按钮（message_type=3）和卡片按钮（4）在后端都映射 BUTTON_CARD，原生协议都映射 Template=2，再构造顶层 Message.interactiveMessage（字段45）。

| 位置 | 字段号 | 当前值/来源 | 核验 |
|---|---:|---|---|
| InteractiveMessage.header | 1 | Header | 结构独立于Body/Footer |
| Header.title | 1 | title/link.Title | 不属于media oneof |
| Header.imageMessage | 4 | 上传图片元数据 | 属于media oneof |
| Header.hasMediaAttachment | 5 | 有图时true | 不是标题显示开关 |
| InteractiveMessage.body | 2 | 普通按钮content；卡片按钮card_text | Text字段1 |
| InteractiveMessage.footer | 3 | 卡片按钮content | Text字段1 |
| InteractiveMessage.nativeFlowMessage | 6 | cta_url按钮 | 和其他互动类型互斥 |
| NativeFlowMessage.messageVersion | 3 | 原生0；本地Web构包1 | 两条链不同，尚无证据该差异导致缺标题 |

需要区分旧 ButtonsMessage：其header oneof确实互斥text/image，并有headerType字段；当前发送不是该消息类型，不能套用旧结构互斥规则。

发送外层：text stanza，无enc mediatype；biz/interactive(type=native_flow,v=1)/native_flow(v=9,name=mixed)。当前原生实现无viewOnce包装。该结构与独立开源项目的mixed native-flow实例一致，不能仅因v=9就判断版本错误。stanza版本与内层messageVersion不是同一个版本域。

Go编码后才逐设备加密；面向收件设备使用同一个plain字节数组。只有发送账号自己的其他设备使用DeviceSentMessage包装，内部仍是同一payload。未发现按iOS/Android或联系人关系删title的分支。

## 离线复现

工作目录：/private/tmp/perf2-header-protocol-probe

- main.go：导入当前项目真实BuildLinkPeerPayload，生成合成消息，Marshal/Unmarshal校验。
- decode-with-baileys.mjs：使用本地baileys 7.0.0-rc13的WAProto独立解码Go输出。
- 8、96、283个Unicode字符，各有图/无图，共6组。每组标题、正文、Footer、图片存在性全部保留。
- titleHasOneof=false，imageOneof=media，字段号1/4，hasMediaAttachment与图片存在一致。
- 数据仅为合成标记和example.invalid，未发送、上传或调用WhatsApp。

复现命令：

```sh
cd /private/tmp/perf2-header-protocol-probe
GOCACHE=/private/tmp/perf2-header-protocol-go-cache GOPROXY=off GOSUMDB=off go run -mod=mod .
node decode-with-baileys.mjs
```

原始字节与解码结果：title-8-image-true.hex / .bin / .json，其余5组同命名。数据是合成离线样本，不是19:39真实发送包。

## 服务器文件一致性

对已确认perf2原生协议主机进行只读SHA256检查，以下服务器源码与本地逐字节一致：

- internal/service/node/message_payload.go: e45e0b9cd159efc3010d2690f1906260fb0dc4e9105e2687db2107f22ed10356
- internal/service/node/prepared_peer_send.go: 4263e8d3b4b967178c14cc663ffef3c1160003945ea817ed0d302319ba621002
- internal/service/node/nodes/message.go: b01bbfc558a191d2f0a8097eeeb71dee3781671d6c8d07df6739895ad2708238
- internal/service/waproto/WAWebProtobufsE2E.proto: 7de8af34421c61d0c3d8c72bc996704b05178d0c916f480ddca22bc1610f471c

这不是运行二进制与源码一致性的完整证明，也没有抓取历史发送明文。之前数据库发件箱的六条一致性与这次源码/离线证据应分别陈述。

## 外部原始参考

- Baileys protobuf： https://github.com/WhiskeySockets/Baileys/blob/master/WAProto/WAProto.proto （InteractiveMessage.Header，title不在media oneof）
- whatsmeow protobuf： https://github.com/tulir/whatsmeow/blob/main/proto/waE2E/WAWebProtobufsE2E.proto （同样布局）
- Evolution 2.4.0-rc1 release： https://github.com/evolution-foundation/evolution-api/releases/tag/2.4.0-rc1 （顶层interactiveMessage与mixed v9的独立使用实例，只作结构对照，不作本问题修复证明）

schema表达字段可编码性，不表达所有手机版本的UI渲染条件。公开Cloud API的高层header约束不能直接替代此私有native-flow协议的终端行为。

## 尚缺证据

1. 缺标题与正常手机的WhatsApp具体版本、版本类型及同一messageId的接收端解码字段。
2. 如接收端Header.title存在，继续查该客户端的显示条件；如不存在，沿实际发送明文/重试链追字段变化。
3. 不能仅凭显示姓名推定通讯录状态；也不能仅凭图片未下载推定缺标题原因（早先图片已显示仍缺标题）。

本次无生产代码修改、无部署、无真实发消息。未以“搬到正文”替代标题显示问题的解决。
