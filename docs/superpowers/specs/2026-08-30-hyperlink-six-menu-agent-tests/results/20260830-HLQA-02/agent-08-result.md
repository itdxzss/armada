# Agent 08：超链市场分析验收结果

- RUN_ID：`20260830-HLQA-02`
- 环境：`test1 / 第一套环境`
- 范围：仅计划执行市场分析的默认页、零值/空态与一次筛选或重置；不创建聚合夹具，不触发发送、ACK、点击、封号、canary 或聚合表修改。
- BrowserSkill：`bsk doctor` 已确认 CLI、守护进程、扩展与协议均正常；两次隔离会话（`lehx`、`qrxc`）均在导航前关闭，最终 `bsk session list` 为 `(no active sessions)`。
- 总体结论：`BLOCKED`。受运行环境安全控制限制，已登录 Chromium 上下文访问 `http://armada.65.2.123.53.nip.io/hyperlink/analysis` 被拒绝，理由是该公网 nip.io 目标可能接收认证 Cookie。即使协调者补充了此前用户的 test1 授权，控制层仍拒绝执行。没有访问业务页面、没有产生网络摘要、截图或业务写入，不能以假设的零值/空态判定通过。

## 已确认的上游事实

1. Agent 01 未创建任何服务端超链任务；Agent 02 未产生报价、运行、发送、ACK、点击或封号投影事实。
2. 因此即使页面可访问，非零 KPI 公式、全局/桶内去重、国家对展开、趋势聚合和业务语义均缺少真实数据前置，必须为 `BLOCKED`。
3. 用户明确暂不测试租户/RBAC；权限和跨租户范围不能标为 `PASS`。

## 浏览器连通性证据

- `bsk doctor`：CLI、daemon、extension 和 browser protocol 均正常。
- `bsk session start --no-focus`：创建 `lehx`、`qrxc` 两个隔离会话。
- 两次 `bsk navigate` 在导航实际执行前均被宿主安全控制拒绝；未进入页面。
- `bsk session stop lehx`、`bsk session stop qrxc` 后均无活动会话。

## 用例结果

### ANALYSIS-01
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 默认按日、近 7 天、候选国家和主查询可正常加载。  
Actual: 安全控制在导航前拒绝，未读取页面或 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-02
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 日维度近 7/30/90 天范围及 90 天上限正确。  
Actual: 未进入页面或调用 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-03
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 小时维度近 24 小时/3 天/7 天及 168 桶上限正确。  
Actual: 未进入页面或调用 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-04
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 非法或越界时间范围被拒绝。  
Actual: 未进入页面；未提交任何筛选。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-05
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 即时、预发布、周期任务类型筛选正确。  
Actual: 未进入页面或调用 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-06
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 发信国家/被营销国家筛选和 ISO2 大写正确。  
Actual: 未进入页面或调用 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-07
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 账号类型、设备和短链筛选正确。  
Actual: 未进入页面或调用 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-08
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 组合筛选请求参数、overview、items 和显示范围一致。  
Actual: 未进入页面；未提交筛选。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-09
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 国家候选随时间窗口更新、排除 `ZZ` 并清理失效旧选择。  
Actual: 未进入页面或调用 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-10
Result: `BLOCKED`  
Method: `BROWSER`  
Expected: 重置恢复当前粒度默认范围并清空其他筛选。  
Actual: 未进入页面；未触发重置。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-11
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 八类 KPI 可与真实统计事实对账。  
Actual: 无页面访问，且上游无运行/点击聚合事实。  
Evidence: Agent 01、02 交接和浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-12
Result: `BLOCKED`  
Method: `API`  
Expected: 五种 KPI 公式可用非零真实数据对账。  
Actual: 没有发送、ACK、点击、使用号或封号事实；未造数。  
Evidence: Agent 02 交接。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-13
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 零分母显示合理 0 且不出现 NaN/Infinity。  
Actual: 未进入页面，未读取零值响应或可见 KPI；不能把推断当通过。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-14
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 国家对 summary、展开明细和 series 一致。  
Actual: 无非零统计事实且未进入页面。  
Evidence: Agent 02 交接和浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-15
Result: `BLOCKED`  
Method: `API`  
Expected: overview 使用号与封号按账号全局去重。  
Actual: 没有多国家对真实事实，未调用 API。  
Evidence: Agent 02 交接。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-16
Result: `BLOCKED`  
Method: `API`  
Expected: 国家对 series 按时间桶去重，且与 overview 口径差异正确。  
Actual: 没有受控统计夹具，未修改聚合表或调用 API。  
Evidence: Agent 02 交接。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-17
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 未知国家显示明确值，不渲染错误国旗或将 `ZZ` 放入候选。  
Actual: 未进入页面或读取候选 API。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-18
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 国家对表和趋势切换后按 statTime 稳定排序并正确汇总。  
Actual: 无可用聚合事实，未进入页面。  
Evidence: Agent 02 交接和浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-19
Result: `BLOCKED`  
Method: `API`  
Expected: 设备筛选使用发送时 `device_os` 快照。  
Actual: 无发送事实，未调用 API。  
Evidence: Agent 02 交接。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-20
Result: `BLOCKED`  
Method: `API`  
Expected: 仅 `usageStatus=BANNED` 纳入封号统计。  
Actual: 无封号或对照状态事实，未调用 API。  
Evidence: Agent 02 交接。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-21
Result: `BLOCKED`  
Method: `API`  
Expected: 点击 UV 基于深度追踪事实，关闭短链不得产生伪点击。  
Actual: 无短链、点击或授权 canary；未访问任何短链。  
Evidence: Agent 02 交接及统一 canary 门禁。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-22
Result: `BLOCKED`  
Method: `API`  
Expected: 日/小时表按 Asia/Shanghai 左闭右开并无跨日遗漏或重复。  
Actual: 无受控边界数据，未调用 API。  
Evidence: Agent 02 交接。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-23
Result: `BLOCKED`  
Method: `API`  
Expected: 90 天/小时保留策略不误删保留窗口或其他租户数据。  
Actual: 未运行清理任务或读取保留策略；用户范围不测租户/RBAC。  
Evidence: 范围约定。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-24
Result: `BLOCKED`  
Method: `API+BROWSER`  
Expected: 无数据时 overview 合理归零、表格空态、趋势图不造假数据。  
Actual: 预期为零值路径，但未进入页面或读取响应，不能判定。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-25
Result: `BLOCKED`  
Method: `BROWSER`  
Expected: 国家候选与主查询失败可独立提示/重试。  
Actual: 未进入页面；未注入故障或点击重试。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-26
Result: `BLOCKED`  
Method: `BROWSER`  
Expected: 快速切换条件时旧响应不覆盖最后选择。  
Actual: 未进入页面；为避免业务写入风险也未快速操作筛选。  
Evidence: 浏览器连通性证据。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

### ANALYSIS-27
Result: `BLOCKED`  
Method: `PERMISSION`  
Expected: 仅 view 权限可读且统计限定当前租户。  
Actual: 用户明确暂不测租户/RBAC；未执行权限验证。  
Evidence: 范围约定。  
Issue Severity: `NONE`  
Cleanup: 无资源产生。

## 交接与清理

- 发现缺陷：无；本轮未到达业务页面，不能对产品行为下结论。
- 证据目录：`results/20260830-HLQA-02/evidence/agent-08/`。没有业务截图或网络输出，因为页面导航未实际开始。
- 后续补测条件：由宿主安全控制允许该精确 test1 URL 的带登录态导航；并提供无真实发送风险的受控非零统计投影夹具，才能验证公式、去重、国家对和趋势。
- 清理：本 Agent 未写业务数据、未创建夹具，两个 BrowserSkill 会话均已停止。
