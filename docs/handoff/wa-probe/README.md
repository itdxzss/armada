# wa-probe：首次联系 / 号质量实验用的本机探针

2026-11-04 从本机 `~/daixz工作空间/armada/_tools/wa-probe/` 复制进仓库，方便换机继续。
只收了脚本，**运行记录（`runs/`）、扫码页、日志都没进仓库**：里面有真实号码和临时配对码，原件只在原来那台电脑上。

所有脚本都直接用 `armada-protocol/protocol-layer` 里装好的 Baileys（7.0.0-rc13 + 我们的补丁），以"关联设备（分身）"身份扫码上线，不连 Armada / WB Pro 环境。

## 先准备

```
cd <armada-protocol>/protocol-layer && npm ci && npm run build   # web-send 要用编译后的 dist/…/card-content.js
export PROTOCOL_LAYER=<armada-protocol>/protocol-layer             # 默认 ~/daixz工作空间/armada/armada-protocol/protocol-layer
export RECV_PHONE=<接收号，纯数字>                                   # 只有 reporting-token-check 用
```

## 脚本

| 脚本 | 用途 |
|---|---|
| `recv-probe.mjs` | **接收探针**：扫码挂接收号分身，原样记录收到的每一帧（举报令牌、tctoken、privacy_token 通知、正在输入）和解密后的消息、链接预览。Ctrl+C 注销分身并写 `report.md` |
| `web-send.mjs` | **Web 协议发送**：发送号挂分身，按线上 `worker-consumer.ts` 私聊路径（`generateWAMessage` → `relayMessage`，不发正在输入）发 "hi" 和苹果链接，记录发出的每一帧 |
| `integrity-probe.mjs` | **账号权重探针**（11-04 下午）：扫码挂分身，只读查一批号码的档位 trust_tier、是否可疑、注册时间，查完自动注销。`node integrity-probe.mjs --targets 名单.tsv --queries IntegrityQuery,StartChatContextIntegrityQuery --gap 4`（名单每行"号码<TAB>标签"；`--dry` 只打印查询）。扫码页 `qr-integrity.html`，结果在 `runs/integrity-<时间>/summary.tsv`。一次别超过 20 个号，否则被限流。结论见 [`../../business/2026-11-04-商业号质量提升-知识库.md`](../../business/2026-11-04-商业号质量提升-知识库.md) |
| `old-creation-probe/probe.mjs` | 11-03 的注册时间 / 商家资料探针（`--biz`、`--light --biz-file`）。它的扫码页路径有中文解码问题，再用前按 `recv-probe.mjs` 第 18 行的写法改 `here` |
| `reporting-token-check/` | Baileys 举报令牌缺陷复现：`rt-test.mjs` 单次、`rt-sim.mjs` 跑 2000 次统计、`verify-rt.mjs` 用实测记录验算（要原来那台电脑的 `runs/`，或设 `WA_PROBE_RUNS`） |

## recv-probe 用法

```
cd docs/handoff/wa-probe
node recv-probe.mjs                     # 自动打开扫码页：接收号手机 → 已关联的设备 → 关联新设备 → 扫码
# 上线后用发送手机给接收号发消息；结束按 Ctrl+C
node recv-probe.mjs report runs/<时间>   # 重新生成报告
```

每次运行一个目录 `runs/<北京时间>/`：`frames.jsonl`（收到的帧）、`tx.jsonl`（探针发出的帧，确认没发消息）、`messages.jsonl`（解密后的消息）、`thumbs/`、`baileys-debug.log`、`report.md`。

## 注意

- 探针**不能关历史同步**，关了主手机 2 秒内就把分身移除（`device_removed`）。
- 扫码期间接收号收到的**所有**消息都会记到本机。
- **接收号必须从没在我们任何协议（zhuan / Web / WB）上登录过**。11-04 的接收号 A 曾是 perf2 的 zhuan 账号，服务器发给新联系人的还是旧公钥，陌生人发来的消息 A 主手机全都解不开。详见 [`../2026-11-04-真手机首次联系报文实测-iPhone.md`](../2026-11-04-真手机首次联系报文实测-iPhone.md)。
