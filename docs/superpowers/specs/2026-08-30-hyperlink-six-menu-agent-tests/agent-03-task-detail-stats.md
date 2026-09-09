# Agent 03：超链任务详情与五类统计

## 目标

验证任务详情抽屉、摘要、收信人流水、发信账号统计、深度归因、访问趋势和封号原因的页面与接口一致性。

## 前置与依赖

- 硬前置：Agent 00 公共门禁通过。
- 数据前置：优先消费 Agent 02 输出的 RUNNING/COMPLETED/STOPPED/含点击任务；没有真实运行事实时相关用例必须 `BLOCKED`。
- 方法：API 负责统计和边界对账；浏览器负责抽屉、Tab、筛选、分页、图表、导出和权限展示。
- 浏览器操作必须使用腾讯开源的 `browser-skill`，通过 `bsk` 驱动已连接的 Chrome Agent Window，并保存各 Tab 截图和 Network 摘要；结束时必须停止本 Agent 的 `bsk` session。
- 使用 `HLQA-{RUN_ID}-A03-` 作为自建统计夹具前缀。
- 每条输出 `Case ID / PASS|FAIL|BLOCKED / Method / Expected / Actual / Evidence / Severity / Cleanup`；只报告问题，不修改业务代码。

## 详情与摘要

- [ ] DETAIL-01【浏览器】从任务列表打开详情，验证 1300px 右侧抽屉、任务标题、遮罩/按钮关闭和默认收信人 Tab。
- [ ] DETAIL-02【浏览器】验证五个 Tab 顺序：收信人流水统计、发信账号维度统计、深度归因、访问趋势、封号原因分布。
- [ ] DETAIL-03【API+浏览器】核对摘要中的人数、发送、单钩、双钩、失败、未注册、点击、使用号数和执行时长。
- [ ] DETAIL-04【API+浏览器】验证零分母、运行中时长、metricsUpdatedAt 和分钟级投影提示，不出现 NaN/Infinity。
- [ ] DETAIL-05【浏览器】切换任务时清空上一任务的摘要、筛选、分页、导出轮询和图表状态。

## 收信人流水

- [ ] DETAIL-06【API+浏览器】分别和组合测试手机号、收信国家、发信国家、完整失败原因筛选。
- [ ] DETAIL-07【API+浏览器】验证 PENDING、PROCESSING、SUCCESS、DELIVERED、READ、FAILED、UNREGISTERED 等状态和时间优先级。
- [ ] DETAIL-08【浏览器】UNREGISTERED 显示为失败子集并给出号码未注册原因；失败原因只在失败状态展示且支持全文 tooltip。
- [ ] DETAIL-09【API】同 recipient 经周期、outbox 重放、重复 ACK 和恢复后仍只有一行，账号和发送方快照稳定。
- [ ] DETAIL-10【API+浏览器】ACK 后流水可先变化；投影完成后摘要与 recipient 重建结果一致。
- [ ] DETAIL-11【API+浏览器】收信人导出与当前四类筛选、snapshotAt、状态和页面行一致；空结果只含表头。
- [ ] DETAIL-12【权限】查看和导出权限独立；猜测另一用户、租户或过期 jobId 不得下载。

## 发信账号统计

- [ ] DETAIL-13【浏览器】首次进入账号 Tab 才懒加载；切出返回保留条件，关闭或换任务恢复默认。
- [ ] DETAIL-14【API】不传时间读取累计 account_stat；传时间按 recipient 聚合，两条 SQL 路径结果在收敛任务上保持一致。
- [ ] DETAIL-15【API+浏览器】验证时间范围成对且左闭右开、国家、成功数 min/max 和组合筛选。
- [ ] DETAIL-16【API+浏览器】验证 success/delivered/failed 三指标远程升降序、清除排序和稳定分页。
- [ ] DETAIL-17【API+浏览器】核对 SUCCESS/DELIVERED/READ 的包含式计数以及 FAILED/UNREGISTERED/TASK_STOPPED 归类。
- [ ] DETAIL-18【API+浏览器】账号删除或资料变化后仍展示 usage 冻结的手机号、国家、类型和创建时间快照。
- [ ] DETAIL-19【API+浏览器】账号统计导出列、筛选、排序、未分配桶和 snapshotAt 与页面一致。

## 深度归因、趋势与封号原因

- [ ] DETAIL-20【API+浏览器】深度归因只返回 clickCount>0 的 recipient，验证手机号筛选、分页和默认点击数排序。
- [ ] DETAIL-21【权限】无 attribution-sensitive 权限时 IP/UA 字段存在但值为空；有权限读取和导出必须产生审计。
- [ ] DETAIL-22【API】归因总数按点击 UV 行计数而非 PV；首访环境不被重复点击覆盖，末访和 PV 正常推进。
- [ ] DETAIL-23【API+浏览器】访问趋势默认 24h/30m，验证范围、粒度、左闭右开、补零桶、new/cumulative UV、PV 和点击率。
- [ ] DETAIL-24【API+浏览器】验证峰值、Top3、洞察和无 UV/0 success/并列峰值/少于三个非零桶的空值规则。
- [ ] DETAIL-25【API+浏览器】趋势 CSV 每一桶与接口 series 和页面图表一致，列名明确 PV 的首访桶近似口径。
- [ ] DETAIL-26【API+浏览器】封号原因覆盖已知英文映射、大小写变体、未知原因、空原因和无封号空态。
- [ ] DETAIL-27【API】普通网络失败、代理失败、离线和未注册 recipient 不得进入封号原因统计；同账号重复封号只计一次。
- [ ] DETAIL-28【浏览器】快速切换范围、粒度、Tab 和任务，旧响应不得覆盖最后一次选择。

## 交付与清理

- 给协调者提供每个详情 Tab 的至少一张脱敏截图和对应接口事实摘要。
- 不删除 Agent 02 尚需用于市场分析的任务；通过别名约定把清理责任移交给 Agent 09。
- 统计不收敛时记录投影水位和最后更新时间，不能用刷新页面掩盖差异。
