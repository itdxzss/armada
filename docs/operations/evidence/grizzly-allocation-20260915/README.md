# Grizzly 0.15 USD 有报价但取号失败：test1 任务 1

排查日期：2026-09-15。目标：第一套测试环境 test1，注册任务 1。

## 当前结论

- 10 个任务项均已终止，原因为 `SMS_NO_NUMBERS`，不是调度未启动。
- 失败发生在向 Grizzly 请求号码的阶段。未生成 Grizzly 激活订单、Cobalt 注册或 Armada 账号。
- 最新只读报价仍给美国（虚拟）WhatsApp 的 0.15 USD 档标注 125,310 个库存；V3 将该价格关联到供应商 362。
- 已部署客户端的离线请求捕获没有发现金额换算、重复编码或自动重试。
- 经过用户授权后的 5 次实际对照，已定位到 **`minPrice` 过滤条件的兼容问题**：`minPrice=maxPrice=0.15` 重复返回 NO_NUMBERS，省略 minPrice 则立即分配成功，实际扣款 0.15 USD。不能再把本次故障解释为“0.15 档实际没有号码”。
- 仅缩短金额格式没有效果；把 minPrice 降到 0.1499 返回 `WRONG_MIN_PRICE:0.15`，所以不能用随意减小小数的方式修复。
- 供应商内部如何处理最低价尚不透明，本次未证明其采用严格大于、浮点舍入或其他具体算法。
- 已完成 5 次有界诊断取号，仅 1 次分配成功，随后取消并全额退款，净支出为 0 USD；原 Armada 任务未重新运行，诊断号码未进入 Cobalt，线上业务代码未修改。

## 1. 任务实际数据

| 字段 | 值 |
| --- | --- |
| 任务 / 租户 | 1 / 1 |
| 国家代码 | 12 |
| 单价 | 0.150000000000 |
| 数量 | 10 |
| 目标分组 | 216 |
| 创建时间 | 2026-09-15 11:38:53（Asia/Shanghai） |
| 执行区间 | 2026-09-15 11:38:56 至 11:39:54（Asia/Shanghai） |
| 任务项 1–10 | state=8 / FAILED，failure_code=SMS_NO_NUMBERS |
| 每项关联数据 | actual_cost、currency、activation_id、registration_id、account_id 均为 NULL |

`AccountRegistrationWorker.purchase` 先保存 PURCHASING，再调用 Grizzly。`SMS_NO_NUMBERS` 来自客户端对响应体 `NO_NUMBERS` 的固定匹配，并非把传输失败、余额不足或 Cobalt 错误统一改名。传输及非成功 HTTP 状态另有错误分类。

源码位置：

- `armada-api/src/main/java/com/armada/account/service/impl/AccountRegistrationWorker.java:123`
- `armada-api/src/main/java/com/armada/platform/sms/grizzly/GrizzlySmsClient.java:181`

## 2. 已部署程序的离线请求捕获

使用部署产物的 classes 与依赖，仅把 HTTP transport 替换为本地内存实现，使用占位密钥。没有网络请求。

- 后端实现版本：1695e7aa。
- Jar SHA256：`2afec26092771f8fee8b65396aeedbeb83cb32761b2f3cc52c9ff6729f1c6499`。
- 输入与任务持久化单价一致，使用 BigDecimal `0.150000000000`。
- 下列 URL 是**由同一发布产物离线复现的请求**，不是历史网络抓包。

```text
GET https://api.grizzlysms.com/stubs/handler_api.php
  ?api_key=[redacted]
  &action=getNumberV2
  &service=wa
  &country=12
  &maxPrice=0.150000000000
  &minPrice=0.150000000000
```

- providerIds、exceptProviderIds、phoneException 都没有发送。
- 注入模拟 `NO_NUMBERS` 响应后得到 reason=NO_NUMBERS、outcomeUnknown=false。
- transport 调用次数为 1；实际网络调用次数为 0。
- 这些结果确认客户端如何编码与处理响应，不能证明 Grizzly 的服务端过滤实现正确。

## 3. 供应商只读证据

采样时间：2026-09-15 11:48:42–43（Asia/Shanghai），请求从 test1 发出。

| 查询 | 结果 |
| --- | --- |
| getBalance，version=2 | ACCESS_BALANCE:50.3542:0.0000（可用余额 / 预留余额） |
| getCountries，id=12 | USA (2)，中文“美国（虚拟）” |
| getCountries，id=187 | USA，中文“美国” |
| getPricesV2，wa / 12 | 0.1500 → 125310；0.2800 → 21534；0.4000 → 8247；0.4400 → 20；0.5900 → 159 |
| getPricesV3，wa / 12，provider=362 | price=[0.15]，count=125310 |
| 上述响应头 | HTTP 200，CF-Cache-Status=DYNAMIC，Date 与采样时间一致 |

因此不是控端独自保存了过期的 0.15 报价，也没有证据表明命中了 Cloudflare 边缘缓存。**仍不能排除 Grizzly 或其上游供应商内部缓存、库存同步问题。**

V3 的服务总 count=912480，与返回的供应商明细数量之和并不一致；0.44 档的 V2 与 V3 供应商数也存在差异。这说明不能用 V3 服务总数作为具体档位可分配数量。但这些差异不能单独解释 0.15 档的失败，因为 0.15 在 V2/V3 均返回 125310。

登录网站的开支记录未显示本批 0.15 USD 扣款，页面余额同为 50.3542 USD。查询记录展示已取得号码及供应商，没有找到这 10 次失败分配的详细拒绝理由。网站时间的时区未明确，不与本地时间直接相减。

## 4. 官方接口约定

- [Grizzly 官方当前 API 文档](https://grizzlysms.com/cn/docs)：getNumber 支持 minPrice、maxPrice 和可选 providerIds；getNumberV2 说明接受与 getNumber 相同参数。
- [官方 MCP 客户端源码](https://github.com/GrizzlySMS-Git/grizzly-sms-mcp/blob/main/src/grizzly-sms-client.ts)：使用相同主机和路径，V1/V2 由 action 区分；SDK 暴露 maxPrice、providerIds，但没有透传 minPrice。

官方 SDK 未暴露 minPrice 不等于服务端不支持它；当前文档也没有详细定义 minPrice=maxPrice 的边界和小数位处理。不能据此直接删掉最低价或修改业务价格。

## 5. 实际取号对照：授权范围与原计划

只读排查后，用户回复“你来做吧”，授权最多 5 次取号，总最高支出 0.75 USD，每次最高价固定 0.15 USD，country=12、service=wa；不进入 Cobalt。

1. 当前参数：getNumberV2，minPrice=maxPrice=0.150000000000，不指定供应商。
2. 仅缩短金额表示：getNumberV2，minPrice=maxPrice=0.15。
3. 仅去掉最低价：getNumberV2，maxPrice=0.15。
4. 仅指定供应商：getNumberV2，maxPrice=0.15，providerIds=362。
5. 仅更换接口版本：getNumber，maxPrice=0.15，providerIds=362。

按观察结果缩减后续调用。发生超时、响应无法识别、费用异常等结果不明情况时停止，不自动重试。成功分配的订单需要记录并按供应商允许的取消时间处理。单次对照成功只能缩小范围，不能排除时变库存；判断根因时保留这一限制。

## 6. 实际执行结果

以下请求均从 test1 直接调用 Grizzly，HTTP 均为 200。固定 service=wa、country=12，maxPrice 始终为 0.15 USD，均不指定供应商。密钥只在服务器内存使用；诊断输出不包含手机号、验证码或密钥。

| 次数 | 北京时间 | action | minPrice | maxPrice | 供应商实际响应 |
| --- | --- | --- | --- | --- | --- |
| 1 | 11:53:22 | getNumberV2 | 0.150000000000 | 0.150000000000 | NO_NUMBERS |
| 2 | 11:53:23 | getNumberV2 | 0.15 | 0.15 | NO_NUMBERS |
| 3 | 11:53:24 | getNumberV2 | 不发送 | 0.15 | 分配成功，activationCost=0.15 |
| 4 | 11:54:54 | getNumberV2 | 0.15 | 0.15 | NO_NUMBERS |
| 5 | 11:54:55 | getNumberV2 | 0.1499 | 0.15 | WRONG_MIN_PRICE:0.15 |

第 3 次成功后先停止采购并核对费用；随后将原计划的供应商、V1 对照调整为两次最低价边界复核，仍在总计 5 次授权范围内。第 5 次响应起初未被诊断脚本识别，停止后从服务器私有响应文件确认是明确的最低价校验拒绝；没有重复下单。

因此此次没有验证显式 providerIds 或 getNumber V1，不声称它们有效或无效。第 3 次没有返回 provider ID，不能断言实际分配一定由供应商 362 完成；362 是报价目录给出的关联。

### 币种与费用

- 取号前余额 50.3542，成功取号后余额 50.2042；第 4、5 次之后仍为 50.2042，累计扣款 0.15。
- 成功响应同时返回 activationCost=0.15、currency=643、countryCode=12。
- [官方美元切换公告](https://grizzlysms.com/cn/blog/important-update-all-grizzly-sms-prices-and-balances-are-switching-to-usd)明确自 2025-09-01 起价格、余额和 maxPrice 按 USD 计价。实际美元余额减少 0.15 与该公告一致；响应中的 643 币种标记与之矛盾。
- 本次不根据 643 把金额换算成卢布，也不篡改原始响应。若修复应用展示，应分别保留供应商原始币种字段与已核实的 USD 结算口径。

### 取消与退款

成功响应给出的 activationTime=03:53:24、activationCancel=03:58:24，间隔 5 分钟。取消时间按本地收到响应的 UTC 时间加该相对间隔并留 5 秒余量计算，避免假定供应商时间文本的时区。

北京时间 11:58:51 前完成取消，供应商明确返回 `ACCESS_CANCEL`。紧接着读取余额返回 available=50.3542、reserved=0.0000，恢复至诊断前余额。

本轮 5 次请求：3 次 NO_NUMBERS、1 次 WRONG_MIN_PRICE:0.15、1 次成功分配；成功订单已取消。累计先扣 0.15 USD、后退 0.15 USD，净支出 0 USD；不存在本轮诊断产生的待取消订单。

脱敏执行证据见同目录 `grizzly-authorized-probe-results.jsonl`、`grizzly-authorized-probe-confirmation.jsonl`、`grizzly-authorized-probe-reconciliation.jsonl`、`grizzly-authorized-probe-cancellation.jsonl`。订单 ID 仅保存在 test1 权限受限的诊断状态文件，不放入公开日志。

## 7. 修复边界

根因已缩小到当前“最低价等于最高价”的接入方式与 Grizzly 实际过滤行为不兼容。不能简单删除所有价格档位的 minPrice：高价档只传 maxPrice 可能买到更便宜的档位，改变用户选档语义。

修复时应保证所选档位、供应商过滤及实际成交价一致，价格不匹配时不得进入 Cobalt。最低价档省略 minPrice 已有一次成功分配的真实证据；其他档位及显式供应商筛选仍需另行验证。本文是诊断证据，不代表该修复已实施或部署。
