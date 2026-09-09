# Agent 04：超链数据包验收结果

## 结论

- Overall：`BLOCKED`（`PASS 1 / FAIL 0 / BLOCKED 33`）。
- RUN_ID：`20260830-HLQA-02`。
- 环境：`test1 / 第一套测试环境`。
- 执行账号：`HLQA-20260830-HLQA-02-A04`（用户 ID `17`，页面昵称 `HLQA A04 Agent`）；不记录密码、Token 或认证信息。
- 用户明确本轮暂不验证租户隔离与 RBAC；相关项保持 `BLOCKED`。
- BrowserSkill：`bsk doctor` 六项全部 `ok`；独占会话 `lirq` 已停止；收口时 `bsk session list` 为 `(no active sessions)`。
- 安全：未触发真实 WhatsApp、钱包、批量发送或 canary；未保存含完整手机号的截图、下载文件或报告内容；未修改业务代码。
- API 阻塞：终端到 test1 的登录请求 20 秒超时，随后固定入口连通性探测 5 秒连接超时、HTTP `000`。没有取得或保存 Token，因此 API、并发、审计和完整导出合同均不能写 PASS。
- 本轮没有发现可由已执行证据确认的 P0/P1 缺陷；多数用例因 API、租户或专用状态夹具缺失而阻塞。

## 保留夹具

- 别名：`HLQA-20260830-HLQA-02-A04-MAIN`
- 数据包 ID：`6`
- 状态：有效，当前 generation 未通过 API 读取；页面显示 4 条、全部未使用、0 失败、主要国家 BR，实际为受控小型多国家/未识别混合包。
- 用途：移交 Agent 01 作为可建任务的小型合法数据包。
- 备注：`Agent04 合法小包，供 Agent01 使用`
- 清理：本轮仅创建这一项且按要求保留；浏览器内存文件随会话关闭，未留下本地 TXT；没有其他 A04 数据包夹具或下载文件需要删除。

## 证据索引

- [DATA-01 首屏列表](./evidence/agent-04/data-01-list.png)
- [DATA-05 名称必填](./evidence/agent-04/data-05-required.png)
- [DATA-12 导入后主包](./evidence/agent-04/data-12-main-imported.png)
- [DATA-26 访问趋势真实空态](./evidence/agent-04/data-26-empty-trend.png)
- [DATA-27～30 点击分析](./evidence/agent-04/data-27-30-click-analysis.png)

## 用例结果

### DATA-01

- Case ID: DATA-01
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 2026-08-30T17:20:00+0800 / 2026-08-30T17:32:20+0800
- Fixture Aliases: A04 账号；A04-MAIN
- Expected: 列表、国家候选、默认 pageSize=20、空态和接口失败重试全部正确。
- Actual: 页面正常打开，国家候选可见，分页显示 20 条/页，表格和操作区完整；未注入列表/国家接口失败，未形成错误重试证据。
- Evidence: `data-01-list.png`；BrowserSkill 首屏 observe。
- Issue Severity: NONE
- Cleanup: 无临时写入。

### DATA-02

- Case ID: DATA-02
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 名称、今天、昨天、自定义时间、国家和 UV 最小/最大筛选全部正确。
- Actual: 名称精确筛选仅返回 A04-MAIN；今天返回当日 2 条、昨天返回前一日 1 条；UV 最小 50 大于最大 10 时提示“UV 占比最小值不能大于最大值”。自定义时间、具体国家和有效 UV 区间未执行。
- Evidence: BrowserSkill observe 输出。
- Issue Severity: NONE
- Cleanup: 筛选已重置。

### DATA-03

- Case ID: DATA-03
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 无
- Expected: 非法国家、UV 越界/倒置和时间倒置返回稳定校验错误。
- Actual: API 通道不可达；仅验证浏览器 UV 倒置提示，不能替代后端合同。
- Evidence: API 连接超时；浏览器提示记录。
- Issue Severity: NONE
- Cleanup: 无写入。

### DATA-04

- Case ID: DATA-04
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN / ID 6
- Expected: 创建、trim、备注、generation=1、version 和初始零指标正确。
- Actual: 浏览器以带首尾空格的名称和备注创建成功，列表回显均已 trim，初始总数/已使用/失败/点击均为 0；generation 和 version 无 API 证据。
- Evidence: `data-12-main-imported.png` 的同一资源；创建后 BrowserSkill observe。
- Issue Severity: NONE
- Cleanup: 资源按要求保留。

### DATA-05

- Case ID: DATA-05
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 名称必填/128、备注 255、同租户名称唯一。
- Actual: 空名称保存被页面阻止；未完整验证 128/255 边界和重复名称后端冲突。
- Evidence: `data-05-required.png`。
- Issue Severity: NONE
- Cleanup: 未创建无效资源。

### DATA-06

- Case ID: DATA-06
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 重命名/备注后 version 递增，旧 version 并发更新冲突。
- Actual: 缺少可用 API 通道和第二 API 会话，未执行。
- Evidence: API 连接阻塞记录。
- Issue Severity: NONE
- Cleanup: 无额外写入。

### DATA-07

- Case ID: DATA-07
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 缺 TENANT_B 可登录测试身份
- Expected: 同名允许且跨租户读写/导入/删除隔离。
- Actual: 用户明确本轮跳过租户验证。
- Evidence: Agent 00 范围调整。
- Issue Severity: NONE
- Cleanup: 无跨租户写入。

### DATA-08

- Case ID: DATA-08
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 接受 UTF-8 TXT，拒绝空、非 UTF-8、非 TXT 和无有效号码。
- Actual: 浏览器选择 68B UTF-8 TXT 后解析出 4 个有效号码并可提交；四类负向文件未完整验证。
- Evidence: BrowserSkill 文件选择摘要（不保存号码预览）。
- Issue Severity: NONE
- Cleanup: 文件仅存在浏览器内存，session stop 后释放。

### DATA-09

- Case ID: DATA-09
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 无
- Expected: 100000 行允许、100001 行整体拒绝。
- Actual: API 通道不可达，未向共享 test1 写入大体量边界数据。
- Evidence: API 阻塞记录。
- Issue Severity: NONE
- Cleanup: 无写入。

### DATA-10

- Case ID: DATA-10
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 无
- Expected: 六个禁止国家/地区号码均阻止上传。
- Actual: 页面明确展示六个禁止前缀说明；未提交逐国负向文件，不能据文案判 PASS。
- Evidence: 导入弹窗 BrowserSkill snapshot。
- Issue Severity: NONE
- Cleanup: 无禁止号码写入。

### DATA-11

- Case ID: DATA-11
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 无
- Expected: 全巴西样本显示 +9/去9 风险确认，取消与确认语义正确。
- Actual: 保留夹具为混合国家包，未使用全巴西样本。
- Evidence: 无完整执行证据。
- Issue Severity: NONE
- Cleanup: 无写入。

### DATA-12

- Case ID: DATA-12
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN / ID 6
- Expected: APPEND 返回 total/accepted/invalid/duplicated 并与最终总数一致。
- Actual: 受控文件 6 行在浏览器端解析为 4 个有效唯一号码，确认上传后列表总数从 0 变为 4、未用 4；未取得 API 导入响应，不能证明四个服务端行数字段。
- Evidence: `data-12-main-imported.png`。
- Issue Severity: NONE
- Cleanup: A04-MAIN 按要求保留。

### DATA-13

- Case ID: DATA-13
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 文件内、包内重复和非法格式均不形成重复活动号码。
- Actual: 文件内一条重复和一条非法在浏览器预解析后未进入最终 4 条；缺服务端号码唯一性与第二批包内重复 API 证据。
- Evidence: 浏览器预解析摘要与导入后总数。
- Issue Severity: NONE
- Cleanup: 资源保留。

### DATA-14

- Case ID: DATA-14
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: OVERWRITE 原子切换新 generation，页面只展示新代。
- Actual: 为保护下游保留夹具，仅执行 APPEND，未执行 OVERWRITE。
- Evidence: 无完整执行证据。
- Issue Severity: NONE
- Cleanup: 未改变 generation。

### DATA-15

- Case ID: DATA-15
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: OVERWRITE 失败保持旧 generation/统计且无半切换。
- Actual: 缺 API 与失败注入能力。
- Evidence: API 阻塞记录。
- Issue Severity: NONE
- Cleanup: 无写入。

### DATA-16

- Case ID: DATA-16
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 编辑、删除、APPEND、OVERWRITE 并发一致。
- Actual: 缺并发 API 通道；未对共享环境制造并发写入。
- Evidence: API 阻塞记录。
- Issue Severity: NONE
- Cleanup: 无并发残留。

### DATA-17

- Case ID: DATA-17
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 导入审计记录操作者、包、模式、generation、行数与结果，且不泄露号码。
- Actual: 无审计查询入口或权限；未读取服务日志/数据库。
- Evidence: 无审计证据。
- Issue Severity: NONE
- Cleanup: 无。

### DATA-18

- Case ID: DATA-18
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 号码抽屉默认 50、分页、号码筛选和重置。
- Actual: 抽屉标题正确、只读统计确认 4 行，存在号码筛选、查询和重置；为避免输出完整号码未保存抽屉截图，且未取得 API pageSize=50 证据。
- Evidence: BrowserSkill 最小 DOM 摘要 `open=true,rowCount=4,hasPhoneFilter=true,hasReset=true`。
- Issue Severity: NONE
- Cleanup: 抽屉已关闭。

### DATA-19

- Case ID: DATA-19
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 只允许最多 20 位数字，前后端均拒绝字母/符号/超长。
- Actual: 输入框 maxlength=20；输入字母后页面提示“手机号筛选只能输入最多 20 位数字”。符号与后端校验未执行。
- Evidence: BrowserSkill 控件属性和消息摘要。
- Issue Severity: NONE
- Cleanup: 抽屉已关闭。

### DATA-20

- Case ID: DATA-20
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 核对全部状态指标和漏斗百分比。
- Actual: 当前包显示 total=4、unused=4、used=0、failed=0、unregistered=0、single=0、double=0、clickUv=0，百分比均为 0；缺非零状态夹具，无法覆盖所有映射。
- Evidence: `data-12-main-imported.png`。
- Issue Severity: NONE
- Cleanup: 资源保留。

### DATA-21

- Case ID: DATA-21
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 缺失败状态包
- Expected: 只重置 RETRYABLE_FAILED，UNREGISTERED 不恢复，零值按钮禁用/提示。
- Actual: A04-MAIN 没有失败状态；未构造后台状态夹具。
- Evidence: 列表显示失败 0。
- Issue Severity: NONE
- Cleanup: 无。

### DATA-22

- Case ID: DATA-22
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 七种号码导出数量和状态事实一致。
- Actual: 为避免保存完整号码且缺非零多状态夹具，本轮未下载七类文件。
- Evidence: 七项导出入口在页面存在；不足以判 PASS。
- Issue Severity: NONE
- Cleanup: 无下载文件。

### DATA-23

- Case ID: DATA-23
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 多选批量导出、未选禁用、超过 100 拒绝、跨租户不泄露。
- Actual: 批量导出入口存在；上限与跨租户未验证，且用户跳过租户范围。
- Evidence: `data-01-list.png`。
- Issue Severity: NONE
- Cleanup: 无下载。

### DATA-24

- Case ID: DATA-24
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 点击 TXT/CSV 内容、字段、文件名和计数头正确。
- Actual: 当前环境明确无点击事实；未下载空文件或伪造点击明细。
- Evidence: 点击分析真实空态。
- Issue Severity: NONE
- Cleanup: 无下载。

### DATA-25

- Case ID: DATA-25
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 当前列表
- Expected: 本页 CSV 有 BOM、中文正常、12 列且与页面一致。
- Actual: 导出 CSV 按钮存在；本轮未下载并解析文件。
- Evidence: `data-01-list.png`。
- Issue Severity: NONE
- Cleanup: 无下载。

### DATA-26

- Case ID: DATA-26
- Result: PASS
- Method: BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 点击名称打开访问趋势；无聚合时明确真实空态，不绘制假曲线。
- Actual: 弹窗显示发送成功/双钩/点击 UV 均为 0，并显示“暂无按时间聚合的访问趋势数据”及事实说明，无假曲线。
- Evidence: `data-26-empty-trend.png`。
- Issue Severity: NONE
- Cleanup: 弹窗已关闭。

### DATA-27

- Case ID: DATA-27
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 当前租户真实空事实
- Expected: “从来不点”和“点击比例”两种模式均由 API 与页面验证。
- Actual: 浏览器可在两种模式间切换，分别展示次数/百分比口径；API 不可达。
- Evidence: `data-27-30-click-analysis.png`；BrowserSkill 模式切换。
- Issue Severity: NONE
- Cleanup: 抽屉已关闭。

### DATA-28

- Case ID: DATA-28
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 当前租户真实空事实
- Expected: 默认 5/10/15/20，可增删整数、至少保留一个、比例不超过 100。
- Actual: 默认四档正确；输入 101 被控件钳制为 100 并可添加，删除一档成功；未继续删除至最后一档验证保底提示。
- Evidence: `data-27-30-click-analysis.png`；BrowserSkill 阈值操作。
- Issue Severity: NONE
- Cleanup: 抽屉关闭后恢复局部状态。

### DATA-29

- Case ID: DATA-29
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 当前租户真实空事实
- Expected: 今天、昨天、近 7 天、自定义范围和 90 天限制。
- Actual: 三个快捷按钮与近 7 天默认范围可见；未完成自定义范围和 API 90 天负向。
- Evidence: `data-27-30-click-analysis.png`。
- Issue Severity: NONE
- Cleanup: 抽屉已关闭。

### DATA-30

- Case ID: DATA-30
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 当前租户真实空事实
- Expected: 全局/国家维度 bucket count/percent、factSourceReady 和空态。
- Actual: 页面明确提示未接入点击事实，四档 count=0、percent=0，并展示真实空结果；未开启国家分组形成完整对账，API 也不可达。
- Evidence: `data-27-30-click-analysis.png`。
- Issue Severity: NONE
- Cleanup: 抽屉已关闭。

### DATA-31

- Case ID: DATA-31
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 无点击事实
- Expected: 按模式、阈值、国家导出数量等于 bucket。
- Actual: 页面显示导出入口，但当前事实源未就绪且 API 不可达，未下载空文件冒充通过。
- Evidence: 点击分析真实空态。
- Issue Severity: NONE
- Cleanup: 无下载。

### DATA-32

- Case ID: DATA-32
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: 当前租户真实空事实
- Expected: 快速切换后旧响应不得覆盖最终条件。
- Actual: 完成模式和阈值顺序切换，最终页面与最后选择一致；没有延迟注入，不能证明旧响应晚返回保护。
- Evidence: BrowserSkill 切换记录。
- Issue Severity: NONE
- Cleanup: 抽屉已关闭。

### DATA-33

- Case ID: DATA-33
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 删除取消/确认正确，删除后列表、详情、任务候选不可见。
- Actual: 用户要求保留一个合法包给 Agent 01，本轮没有额外 A04 数据包可安全删除；未执行删除矩阵。
- Evidence: 夹具清单。
- Issue Severity: NONE
- Cleanup: A04-MAIN 明确保留。

### DATA-34

- Case ID: DATA-34
- Result: BLOCKED
- Method: API
- Started At / Finished At: 同本轮证据窗口
- Fixture Aliases: A04-MAIN
- Expected: 删除后名称复用及保留期清理不影响其他租户/活动包。
- Actual: API 不可达，且用户跳过租户隔离；未执行。
- Evidence: API 与范围阻塞记录。
- Issue Severity: NONE
- Cleanup: 无写入。

## 最终清理与交付

- BrowserSkill 会话 `lirq` 已停止，当前无活动 session。
- 保留：`HLQA-20260830-HLQA-02-A04-MAIN / ID 6 / 有效 / 4 条全部未使用`。
- 空包：创建后已转为上述小包，不另留空包。
- 小包/多国家包：由 A04-MAIN 同时承担。
- 失败状态包：缺后台状态夹具，未创建，相关用例保持 `BLOCKED`。
- 其他 A04 数据包：0 个。
- 本地导入/下载文件：0 个；上传文件只存在 Agent Window 内存并已随 session 释放。
