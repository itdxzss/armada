# IP 代理 WhatsApp 连通性检测设计 — armada

> 范围:修正 IP 管理导入后的代理检测语义。上传接口只做格式校验和入库,后台异步全量检测真实出口 IP 与 WhatsApp 官方连通性。IP 数据统计继续按现有原型口径展示,不新增“检测中”前端卡片。

## 1. 背景

当前 `HttpIpProxyDetector` 通过代理访问 `ip-api.com`,以返回的 `query` 作为出口 IP。这只能证明代理能访问 IP 查询服务,不能证明代理能连 WhatsApp 官方。

竞品上传前检测展示的是另一种语义:

- 代理隧道建立。
- WhatsApp 有明确响应,例如 `WhatsApp 响应 400`。
- 同时展示出口 IP、国家、地区、ISP。

我们的目标不是上传阶段强拦截,而是上传快速返回,后台检测沉淀质量数据。

## 2. 目标

- TXT 上传保持快速:只做格式、字段、去重和入库,然后提交后台检测任务并立即返回导入统计。
- 后台异步全量检测每条新增 IP。
- 检测成功后:
  - 代理可参与分配。
  - `smart` 分组按检测国家写入 `ip_proxy.region`。
  - `mixed` 分组保持 `混合（不限国家）`。
- 检测失败后:
  - 标记不可用。
  - 写入失败原因。
  - 不参与自动分配。
- 后端保留检测生命周期字段,前端暂不展示“检测中”。
- 日志打印每条检测耗时,用于观察代理质量和检测链路性能。

## 3. 非目标

- 不做上传前抽样强拦截。
- 不新增 IP 数据统计页面的顶部卡片或新图表。
- 不把出口 IP 当成连接 WhatsApp 的目标地址。
- 不在日志中打印代理用户名、密码或完整代理 URL。
- 不接入供应商专有 API 作为首期必需能力;可作为后续优化。

## 4. 当前可复用能力

现有导入流程已经包含:

- TXT 行解析: `host:port:username:password`。
- 批内去重、库内去重。
- 新行入库。
- 导入后提交后台检测任务。
- `ipProxyCheckExecutor` 线程池。
- 检测结果字段:
  - `detected_country_code`
  - `outbound_ip`
  - `detected_location`
  - `detected_isp`
  - `detected_latitude`
  - `detected_longitude`
  - `check_fail_count`
  - `last_check_error`

本次主要替换检测语义,并补齐检测状态和耗时观测。

## 5. 数据模型

新增 `ip_proxy.check_status`:

| 值 | 语义 | 前端展示 |
|----|------|----------|
| `0` | detecting,后台检测中 | 暂不展示 |
| `1` | success,最近一次检测通过 | 暂不单独展示 |
| `2` | failed,最近一次检测失败 | 通过现有“不可用”体现 |

`status` 仍表示能否参与分配:

| `status` | 语义 |
|----------|------|
| `1` | 空闲,可分配 |
| `2` | 使用中 |
| `3` | 不可用,不可分配 |

导入新行初始状态:

- `check_status=0`
- `status=3`
- `region`:
  - `smart`: `NULL`
  - `mixed`: `混合（不限国家）`

这样检测完成前不会参与分配,且不会被误计入真实国家覆盖。

新增 WhatsApp 检测字段:

- `whatsapp_check_status`:`0/1/2`,同检测中/成功/失败。
- `whatsapp_http_status`:记录 WhatsApp 探测拿到的 HTTP 状态码,例如 `400`。
- `whatsapp_check_error`:记录 WhatsApp 连通性失败原因。

这些字段用于把出口 IP 查询失败和 WhatsApp 不通分开,避免后续统计、弹窗和日志只能依赖一段错误文本。

## 6. 检测流程

单条后台检测按以下步骤执行:

1. 读取代理行的协议、网关、端口、用户名、密码。
2. 通过同一条代理 session 获取出口 IP 和归属信息。
   - 首期使用轻量 HTTP echo + IP 归属查询。
   - 后续可接 IPRoyal session 当前出口 IP API 作为快路径。
3. 通过同一条代理 session 探测 WhatsApp 官方域名。
   - HTTP 代理:建立 `CONNECT web.whatsapp.com:443` 隧道。
   - SOCKS5 代理:完成 SOCKS5 握手并 connect 到 `web.whatsapp.com:443`。
   - 隧道建立后执行 TLS + 最小 HTTP 探测,拿到 WhatsApp 明确响应。只要响应来自 WhatsApp 官方链路,`400` 这类 4xx 也可以视为连通成功,并记录状态码。
4. 汇总结果并更新 `ip_proxy`。

成功标准:

- 代理认证成功。
- 能拿到真实出口 IP。
- WhatsApp 隧道建立成功。
- WhatsApp 有明确响应。

失败分类:

- 代理连接失败。
- 代理认证失败。
- 获取出口 IP 超时或解析失败。
- WhatsApp CONNECT/SOCKS connect 失败。
- TLS 握手失败。
- WhatsApp 未返回明确响应。
- 国家码无法映射到系统支持国家。

## 7. 分组规则

`smart`:

- 导入时 `region=NULL`。
- 检测成功后,按检测到的 ISO2 国家码解析 `country.name_zh`,写入 `ip_proxy.region`。
- 检测国家不支持 IP 管理时,该 IP 标记不可用,记录失败原因。

`mixed`:

- 导入时 `region=混合（不限国家）`。
- 检测成功后仍保持混合分组。
- 检测出的国家、地区、ISP 只作为检测详情字段保存。

检测失败:

- `status=3`
- `check_status=2`
- 清空本次无效的出口详情字段。
- `check_fail_count + 1`
- 写入 `last_check_error`。

## 8. 并发与超时

后台检测使用线程池并发执行。现有固定 `core=2,max=4` 偏小,本次改为配置化:

- `armada.ip-proxy-check.executor.core-size`
- `armada.ip-proxy-check.executor.max-size`
- `armada.ip-proxy-check.executor.queue-capacity`
- `armada.ip-proxy-check.timeout.connect-ms`
- `armada.ip-proxy-check.timeout.read-ms`
- `armada.ip-proxy-check.timeout.total-ms`

建议测试环境初始值:

- `core-size=4`
- `max-size=12`
- `queue-capacity=5000`
- `connect-ms=5000`
- `read-ms=8000`
- `total-ms=15000`

队列满时:

- 导入仍返回成功统计。
- 该条检测任务提交失败时记录 warn。
- IP 保持 `check_status=0,status=3`,后续可通过手动检测或补偿任务处理。

## 9. 日志与观测

每条检测结束打印结构化日志,不打印敏感信息:

```text
IP代理检测完成 proxyId=1021 protocol=HTTP host=geo.iproyal.com port=12321
result=success checkStatus=success status=idle region=印度 outboundIp=68.187.236.156
totalMs=6420 egressMs=1200 whatsappConnectMs=3100 whatsappProbeMs=1800 geoMs=300
whatsappHttpStatus=400 error=
```

失败示例:

```text
IP代理检测完成 proxyId=1021 protocol=HTTP host=geo.iproyal.com port=12321
result=failed checkStatus=failed status=unavailable
totalMs=10032 egressMs=0 whatsappConnectMs=0 whatsappProbeMs=0 geoMs=0
error=WhatsApp CONNECT 超时
```

日志要求:

- `proxyId`、协议、host、port 可以打印。
- 不打印 username、password、完整代理 URL。
- `totalMs` 必填。
- 分阶段耗时能拿到多少打印多少。
- 手动检测和导入后台检测共用同一日志格式。

## 10. IP 数据统计影响

统计页按现有原型口径保持:

- IP 总数量。
- 覆盖国家数。
- 无 IP 国家数。
- 使用中 IP 数。
- 空闲 IP 数。
- 不可用 IP 数。
- 国家维度统计。
- 资源状态:正常、无 IP、无空闲 IP、可用不足、不可用偏高。

本次不新增“检测中”卡片。检测中 IP 因 `status=3` 会暂时归到不可用,但 `check_status=0` 会保留后端语义,后续如需前端展示可以直接接出。

需要注意:

- `smart` 检测成功后才写真实国家,因此成功前不贡献真实国家覆盖数。
- `mixed` 不进入真实国家覆盖数。
- 检测失败的 `smart` IP 没有国家时,不应污染国家维度统计。

## 11. 手动检测

IP 管理列表中的“检测”按钮继续保留,但检测语义改为同后台检测一致:

- 返回出口 IP、归属地、ISP。
- 返回 WhatsApp 连通性。
- 成功则可用,失败则不可用。
- 前端请求超时需要单独放宽,避免用户手动检测时客户端 10 秒先断开。

## 12. 验收标准

- 导入 100 条 TXT 时,HTTP 接口不等待每条代理检测完成。
- 新增 IP 检测完成前不参与账号分配。
- `smart` IP 检测成功后写入检测国家。
- `mixed` IP 检测成功后仍保留混合分组。
- WhatsApp 不通时标记不可用,并记录可读错误原因。
- IP 数据统计按现有原型显示可用/不可用和国家维度数据。
- 日志中能看到每条检测总耗时和主要阶段耗时。
- 日志不泄露代理用户名和密码。
