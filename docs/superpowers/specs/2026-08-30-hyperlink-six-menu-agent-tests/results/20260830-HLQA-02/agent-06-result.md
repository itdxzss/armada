# Agent 06 超链策略验收结果

- RUN_ID: `20260830-HLQA-02`
- Environment: `test1`
- Namespace: `HLQA-20260830-HLQA-02-A06-`
- Test identity: 专用 `TENANT_ADMIN` 用户，页面昵称确认是 `HLQA A06 Agent`
- Started / Finished: 2026-08-30 16:34 / 2026-08-30 17:15（Asia/Shanghai）
- Overall: `FAIL`
- Summary: `PASS 9 / FAIL 3 / BLOCKED 14`
- Safety: 未执行真实 WhatsApp、钱包、批量发送或 canary；未读取 cookie、localStorage、认证 header 或 Token；未修改业务代码。
- BrowserSkill: 本轮 `bsk doctor` 全部通过；最终核验会话 `opdv` 已执行 `bsk session stop opdv`，返回 `stopped opdv`。

## 缺陷摘要

### A06-DEFECT-01：整数参数接受小数并静默截断

- Severity: `P2`
- Related: `STR-06`、`STR-07`
- Actual: API 接受 `1.5`，并把 `maxExecutingAccounts`、`maxUseAccounts`、`maxSendPerAccount` 分别持久化为 `1`；产生策略 ID `4`、`5`、`6`。
- Expected: 三个字段只能接受整数，小数必须稳定拒绝，不能静默改写。
- Cleanup: ID `4`、`5`、`6` 已软删除；最终列表仅保留 ID `1`、`2`、`3`。

### A06-DEFECT-02：筛选抽屉缺少“通讯录有名字联系人数”控件

- Severity: `P2`
- Related: `STR-12`
- Actual: API 接受并试算 `contactNamedNumMin/contactNamedNumMax`，但浏览器筛选抽屉没有对应输入控件；抽屉仅展示双向好友、存活天数、注册天数等字段。
- Expected: 页面应能配置联系人数量范围，并明确区分于双向好友数。
- Evidence: [str-11-12-filter-drawer.png](./evidence/agent-06/str-11-12-filter-drawer.png)

## 用例结果

### Case ID: STR-01

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: ID `1`、`2`、`3`
- Expected: 默认 pageSize、筛选、分页、空态和错误重试完整可用。
- Actual: 已验证策略页、空态、筛选控件、分页控件以及 `pageSize=20`；列表 GET 返回成功。未执行分页切换与错误重试，不能写 PASS。
- Evidence: BrowserSkill VOM；页面网络摘要。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-02

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: A06 专用 `TENANT_ADMIN`
- Expected: view/create/edit/delete 四类权限与接口一致受限。
- Actual: 本轮按协调要求跳过租户隔离/RBAC，且没有四类受限账号；仅确认 A06 管理员可见新建、编辑、删除入口。
- Evidence: BrowserSkill VOM。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-03

- Result: `PASS`
- Method: `API+BROWSER`
- Fixture Aliases: `HLQA-20260830-HLQA-02-A06-INSTANT`，ID `1`
- Expected: 即时策略名称、模式、账号范围、AUTO、账号上限和启用状态正确。
- Actual: 创建成功；ID `1`、即时群发、已启用、分组 `2`、最大执行账号 `0/AUTO`（列表显示“均分”）、最大使用与单号上限 `0`（显示“不限”）。
- Evidence: [str-03-instant-create-form.png](./evidence/agent-06/str-03-instant-create-form.png)；POST 和详情读取结果。
- Issue Severity: `NONE`
- Cleanup: 保留给 Agent 01/09。

### Case ID: STR-04

- Result: `PASS`
- Method: `API+BROWSER`
- Fixture Aliases: ID `1`、`2`、`3`
- Expected: 即时、预发布、周期三种策略和周期标签正确。
- Actual: 三种模式均创建并启用；周期 ID `3` 显示“每 30 分钟”，非周期显示“—”。
- Evidence: [str-04-three-modes-list.png](./evidence/agent-06/str-04-three-modes-list.png)；最终 A06 页面显示 3 条启用策略。
- Issue Severity: `NONE`
- Cleanup: 三条均作为下游夹具保留。

### Case ID: STR-05

- Result: `BLOCKED`
- Method: `API`
- Fixture Aliases: ID `1`
- Expected: 租户内名称唯一、删除后复用、跨租户同名。
- Actual: 同租户重复名称稳定返回业务码 `40901`、“策略名称已存在”。删除后复用和跨租户同名未执行；本轮明确跳过跨租户测试。
- Evidence: 脱敏 API 结果。
- Issue Severity: `NONE`
- Cleanup: 无新增重复夹具。

### Case ID: STR-06

- Result: `FAIL`
- Method: `API+BROWSER`
- Fixture Aliases: ID `1`；已清理 ID `4`
- Expected: 最大执行账号仅允许 0～100 的整数，0 原样持久化为 AUTO。
- Actual: `-1`、`101` 均返回 `40001`；0 原样持久化并显示“均分”；但 `1.5` 被接受并持久化为 `1`。
- Evidence: 脱敏 API 边界结果；列表曾显示 ID `4` 值为 `1`。
- Issue Severity: `P2`
- Cleanup: ID `4` 已删除。

### Case ID: STR-07

- Result: `FAIL`
- Method: `API`
- Fixture Aliases: 已清理 ID `5`、`6`
- Expected: 最大使用账号和单号最大发送数只能为非负整数。
- Actual: 负数均返回 `40001`；但两个字段的 `1.5` 均被接受并静默持久化为 `1`。
- Evidence: 脱敏 API 边界结果；列表曾显示 ID `5`、`6` 截断值。
- Issue Severity: `P2`
- Cleanup: ID `5`、`6` 已删除。

### Case ID: STR-08

- Result: `PASS`
- Method: `API`
- Fixture Aliases: 失败请求 `A06-BELOW`
- Expected: 固定并发不得大于非零最大使用账号数。
- Actual: `maxExecutingAccounts=10`、`maxUseAccounts=9` 稳定返回 `40001`，提示最大使用账号不得小于最大执行账号。
- Evidence: 脱敏 API 边界结果。
- Issue Severity: `NONE`
- Cleanup: 请求失败，无夹具。

### Case ID: STR-09

- Result: `PASS`
- Method: `API+BROWSER`
- Fixture Aliases: ID `1`、`3`
- Expected: 周期每轮账号至少 1、间隔至少 30；非周期间隔归零。
- Actual: 周期间隔 `29` 和每轮账号 `0` 均返回 `40001`；周期 ID `3` 持久化 30 分钟；非周期 ID `1` 详情为 0。
- Evidence: 脱敏 API 结果；三模式列表截图。
- Issue Severity: `NONE`
- Cleanup: 失败请求未产生夹具。

### Case ID: STR-10

- Result: `PASS`
- Method: `BROWSER`
- Fixture Aliases: 未提交的新建表单
- Expected: 切换周期模式补全默认值，切回非周期不残留周期必填。
- Actual: 周期模式自动补全每轮账号 `50`、间隔 `60` 分钟；切回即时模式后周期字段隐藏且不再必填。
- Evidence: BrowserSkill VOM；未提交表单已放弃。
- Issue Severity: `NONE`
- Cleanup: 未产生夹具。

### Case ID: STR-11

- Result: `PASS`
- Method: `API+BROWSER`
- Fixture Aliases: A06 筛选试算
- Expected: 国家、大洲、分组、渠道、协议、在线、轮号、账号类型、平台、号码等筛选可配置并试算。
- Actual: 筛选抽屉展示对应控件；API 对各单项 raw filter 均返回成功，当前真实匹配 `0`、协议数 `2`、最大执行账号 `30`。
- Evidence: [str-11-12-filter-drawer.png](./evidence/agent-06/str-11-12-filter-drawer.png)；20 项脱敏试算结果。
- Issue Severity: `NONE`
- Cleanup: 仅读取试算。

### Case ID: STR-12

- Result: `FAIL`
- Method: `API+BROWSER`
- Fixture Aliases: A06 筛选试算
- Expected: 号源、好友数、联系人数量、存活/注册天数、入库时间、允许拉群均可在页面配置。
- Actual: API 对全部字段均接受并返回真实试算；页面缺少 `contactNamedNumMin/contactNamedNumMax` 对应的联系人数量控件。
- Evidence: [str-11-12-filter-drawer.png](./evidence/agent-06/str-11-12-filter-drawer.png)
- Issue Severity: `P2`
- Cleanup: 仅读取试算。

### Case ID: STR-13

- Result: `PASS`
- Method: `API`
- Fixture Aliases: 多个失败请求
- Expected: 国家冲突、非法范围/枚举、未知 schema/字段和损坏 JSON 全部失败关闭。
- Actual: 各请求均稳定返回 `40001`；损坏 JSON 返回“超链策略请求 JSON 非法”。
- Evidence: 脱敏 API 批量边界结果。
- Issue Severity: `NONE`
- Cleanup: 全部失败，无夹具。

### Case ID: STR-14

- Result: `BLOCKED`
- Method: `API`
- Fixture Aliases: 无
- Expected: NULL 画像在配置筛选时不能按 0/false 命中。
- Actual: 缺少可证明 NULL 画像的已知账号夹具；零匹配结果不足以证明 fail-closed。
- Evidence: 无充分证据。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-15

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: account-context
- Expected: 返回真实候选；失败时页面可重试且不猜默认组。
- Actual: 成功路径返回 40 个分组、249 个国家、1 个渠道、2 个协议；默认分组是 ID `187`“公共组”和 ID `189`“超链组”。未注入 account-context 故障，错误重试路径未验证。
- Evidence: 脱敏 context 结果；筛选抽屉 VOM。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-16

- Result: `PASS`
- Method: `API`
- Fixture Aliases: default group IDs `187`、`189`
- Expected: 默认组仅来自稳定 public + hyperlink 业务编码。
- Actual: 返回的两个默认组分别显示“公共组”和“超链组”，与页面提示 public + hyperlink 一致。
- Evidence: 脱敏 account-context 结果。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-17

- Result: `BLOCKED`
- Method: `BROWSER`
- Fixture Aliases: 无
- Expected: 250ms 防抖、取消旧请求、最终结果取最后条件。
- Actual: 本轮未进行可观测的快速连续修改和请求取消核对。
- Evidence: 无充分证据。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-18

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: ID `1`
- Expected: 真实匹配 0 可保存并警告；试算错误不能冒充 0。
- Actual: 0 匹配时页面显示“当前匹配为 0，仍可保存策略”，且 ID `1` 创建成功；未注入真实试算服务错误，错误展示分支未验证。
- Evidence: 创建表单截图；BrowserSkill VOM。
- Issue Severity: `NONE`
- Cleanup: ID `1` 保留。

### Case ID: STR-19

- Result: `PASS`
- Method: `API`
- Fixture Aliases: match-count 失败请求
- Expected: 仅接受 raw filter，包装对象、未知字段、非法值稳定 400。
- Actual: raw filter 成功；包装对象、未知字段、非法国家和损坏 JSON 均返回 `40001`。
- Evidence: 脱敏 API 结果。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-20

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: ID `1`、`2`、`3`
- Expected: 停用后从 options 消失，重启后恢复，列表同步。
- Actual: 最终仅确认三条启用策略均在列表；本轮未执行停用/恢复切换。
- Evidence: 最终 A06 页面 VOM。
- Issue Severity: `NONE`
- Cleanup: 三条保持启用。

### Case ID: STR-21

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: 无
- Expected: version 原子递增、旧 version 冲突、页面刷新真值。
- Actual: 未执行更新和旧版本并发请求。
- Evidence: 无充分证据。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-22

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: 已删除 ID `4`、`5`、`6`
- Expected: 删除后列表/详情/options 均不可见且名称可复用。
- Actual: ID `4`、`5`、`6` 已从最终列表删除；未补做详情/options 不可见和名称复用对账。
- Evidence: 最终页面仅显示 ID `1`、`2`、`3`。
- Issue Severity: `NONE`
- Cleanup: ID `4`、`5`、`6` 已删除。

### Case ID: STR-23

- Result: `BLOCKED`
- Method: `API`
- Fixture Aliases: 无任务快照
- Expected: 列表、详情、options 只返回 TEMPLATE，TASK_SNAPSHOT 不可猜 ID 暴露。
- Actual: 当前三个模板可正常读取；缺少已知任务快照 ID，无法证明猜 ID 不暴露。
- Evidence: 无充分快照证据。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-24

- Result: `BLOCKED`
- Method: `API+BROWSER`
- Fixture Aliases: ID `1`、`2`、`3`
- Expected: 任务保存独占快照，后续模板变更不影响已有任务。
- Actual: 本轮没有可安全消费的任务夹具，未创建任务。
- Evidence: 无任务证据。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-25

- Result: `BLOCKED`
- Method: `API`
- Fixture Aliases: 无任务快照
- Expected: 手工调整后的最终表单值进入快照，sourceStrategyId 仅追溯来源。
- Actual: 缺少任务夹具，未执行。
- Evidence: 无任务证据。
- Issue Severity: `NONE`
- Cleanup: 无。

### Case ID: STR-26

- Result: `BLOCKED`
- Method: `API`
- Fixture Aliases: 无任务快照
- Expected: 报价、列表、详情、运行读取同一快照。
- Actual: 缺少报价/任务运行夹具，且本轮禁止真实发送与 canary，未执行。
- Evidence: 无任务证据。
- Issue Severity: `NONE`
- Cleanup: 无。

## 下游保留夹具

| ID | Alias | Mode | Enabled | Limits | Cycle |
|---:|---|---|---|---|---|
| 1 | `HLQA-20260830-HLQA-02-A06-INSTANT` | instant | true | AUTO / unlimited / unlimited | 0 |
| 2 | `HLQA-20260830-HLQA-02-A06-ROLLING` | rolling | true | 5 / 10 / 100 | 0 |
| 3 | `HLQA-20260830-HLQA-02-A06-CYCLE` | cycle | true | 3 / 5 / 50 | 30 minutes |

Agent 01 至少可使用 ID `1`；ID `2`、`3` 同时保留供三模式联调。最终 A06 页面确认仅有这三条且全部启用。
