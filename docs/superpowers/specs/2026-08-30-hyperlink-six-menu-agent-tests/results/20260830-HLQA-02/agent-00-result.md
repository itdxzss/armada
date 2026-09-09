# Agent 00 公共门禁验收结果

## 结论

- `gate=BLOCKED`
- RUN_ID：`20260830-HLQA-02`
- 环境别名：`test1 / 第一套测试环境`
- 白名单入口：`http://armada.65.2.123.53.nip.io/`
- 证据窗口：2026-08-30T15:54:05+0800 ～ 2026-08-30T16:01:19+0800
- BrowserSkill：`bsk doctor` 六项全部 `ok`；隔离会话 `miew` 已停止；收口时 `bsk session list` 为 `(no active sessions)`。
- 环境结论：登录态有效，首页标题为“首页 | 第一套环境”，域名与 test1 一致；页面未提供可确认的测试租户别名，因此租户门禁未闭合。
- 六菜单结论：菜单名称和顺序符合要求；完成前五个菜单的直接路由刷新渲染抽查，第六个“超链市场分析”未在本次收口前完成刷新验证。
- 安全与写入：未读取 cookie、localStorage、认证 header、密码或 Token；未创建测试夹具，未提交表单，未触发 WhatsApp、钱包、导出、批量发送或 canary；未修改业务代码。

## 已知阻塞

1. 无法从 test1 运行时读取并确认前端、Armada、Web 协议、Android 协议四个实际 full SHA/digest。
2. 页面没有可确认的 `TENANT_A`、`TENANT_B` 测试租户别名。
3. 没有可安全切换的 `USER_ALL`、`USER_VIEW`、`USER_EXPORT`、`USER_NO_ACCESS` 权限账号别名。
4. 没有不接触认证秘密的独立 API 调用方式，无法验证 API 权限、跨租户、参数边界、审计与数据一致性。
5. 未获故障注入夹具或代理能力，无法安全模拟列表/候选接口失败及旧请求晚返回。
6. 六菜单直接 hash 路由命令在 SPA 内等待导航事件时超时；刷新命令与页面观察成功。按协调者指令在第五个页面后立即停止，不再扩展检查。

## 用例结果

### COM-01

- Case ID: COM-01
- Result: BLOCKED
- Method: API
- Started At / Finished At: 2026-08-30T15:54:05+0800 / 2026-08-30T16:01:19+0800
- Fixture Aliases: 无
- Expected: 读取前端、Armada、Web 协议、Android 协议运行时实际 full SHA/digest，并确认不存在版本混用。
- Actual: 仅能确认本地 Armada worktree 为分支 `1.0.3-snapshot`、SHA `97e271a50830dbb83412beb637d2873e4371b06a`；该值不能代替 test1 运行时版本。其余三个运行时 full SHA/digest 也不可得。
- Evidence: 本地只读 `git rev-parse HEAD` 输出；未把本地版本冒充环境版本。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-02

- Case ID: COM-02
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 2026-08-30T15:54:05+0800 / 2026-08-30T15:54:05+0800
- Fixture Aliases: 当前已登录测试账号（仅别名，不记录凭据）；租户别名不可得
- Expected: 当前域名、租户和环境标识均属于指定 test1。
- Actual: 域名为白名单入口，标题为“首页 | 第一套环境”，环境标识匹配；页面未显示可确认的测试租户别名。
- Evidence: [com-02-home.png](./evidence/com-02-home.png)；BrowserSkill `observe` 返回 `RootWebArea "首页 | 第一套环境"`。
- Issue Severity: NONE
- Cleanup: 未产生写入；会话已停止。

### COM-03

- Case ID: COM-03
- Result: PASS
- Method: BROWSER
- Started At / Finished At: 2026-08-30T15:54:05+0800 / 2026-08-30T15:55:15+0800
- Fixture Aliases: 当前已登录测试账号
- Expected: 菜单依次为超链任务、超链数据包、超链营销模板、超链策略、图片素材、超链市场分析。
- Actual: 展开的“超链营销”菜单按上述顺序展示六项；菜单 HTML 对应路由依次为 `#/hyperlink/tasks`、`#/hyperlink/data`、`#/hyperlink/templates`、`#/hyperlink/strategy`、`#/hyperlink/library`、`#/hyperlink/analysis`。
- Evidence: [com-02-home.png](./evidence/com-02-home.png)；在先执行 `observe/snapshot` 仍未暴露静态子项后，使用 `get-html --ref` 读取“超链营销”最小节点子树。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-04

- Case ID: COM-04
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 2026-08-30T15:55:15+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 当前已登录测试账号
- Expected: 六个菜单均可直接打开并刷新，且不白屏、不 404、不跳错模块。
- Actual: 前五个路由刷新后均成功观察到对应标题和非空可访问性树：`超链任务 | 第一套环境`（109 refs）、`超链数据包 | 第一套环境`（136 refs）、`超链营销模板 | 第一套环境`（98 refs）、`超链策略 | 第一套环境`（91 refs）、`图片素材 | 第一套环境`（129 refs）。第六个“超链市场分析”未在协调者要求立即收口前完成。
- Evidence: BrowserSkill 每页执行 `navigate`、`reload --wait-until domcontentloaded`、`observe --json`；刷新均成功。直接 hash `navigate` 等待 SPA 导航事件时返回 timeout，未据此误判页面失败。
- Issue Severity: NONE
- Cleanup: 无写入；会话已停止。

### COM-05

- Case ID: COM-05
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 无
- Expected: 后退、前进、菜单切换和标签页恢复不串页、不残留旧状态。
- Actual: 按协调者要求在路由等待超时后立即收口，未继续执行导航历史与标签页恢复检查。
- Evidence: 无可形成 PASS/FAIL 的完整执行证据。
- Issue Severity: NONE
- Cleanup: 会话已停止。

### COM-06

- Case ID: COM-06
- Result: BLOCKED
- Method: API
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 缺少 `TENANT_A`、`TENANT_B`、`USER_ALL`、`USER_VIEW`、`USER_EXPORT`、`USER_NO_ACCESS`
- Expected: 准备两个测试租户与四类权限用户，只传递别名。
- Actual: 未提供安全夹具或测试管理 API；不创建、不猜测权限账号。
- Evidence: 前置资料与当前页面均无上述夹具别名。
- Issue Severity: NONE
- Cleanup: 未创建夹具。

### COM-07

- Case ID: COM-07
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 缺少 `USER_NO_ACCESS`
- Expected: 无 view 权限时菜单不可见、直达 URL 不返回数据、接口返回 403。
- Actual: 无可切换的无权限账号，且禁止提取当前登录态认证信息，无法安全验证。
- Evidence: COM-06 前置未满足。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-08

- Case ID: COM-08
- Result: BLOCKED
- Method: API+BROWSER
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 缺少独立权限角色
- Expected: 分别验证 create、edit、delete、import、export、action、attribution_sensitive 权限独立。
- Actual: 无权限矩阵夹具，未执行任何写入、导出或敏感动作。
- Evidence: COM-06 前置未满足。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-09

- Case ID: COM-09
- Result: BLOCKED
- Method: API
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 缺少 `TENANT_A`、`TENANT_B` 及跨租户资源 ID
- Expected: 跨租户访问 task/dataPackage/template/strategy/asset 均返回 403 或 NOT_FOUND，且不泄露存在性。
- Actual: 双租户与资源夹具不可得，未伪造 ID、未读取认证信息。
- Evidence: COM-06 前置未满足。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-10

- Case ID: COM-10
- Result: BLOCKED
- Method: API
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 无
- Expected: 验证分页、时间范围、枚举与排序字段非法输入返回稳定校验错误。
- Actual: 无独立 API 认证调用方式；禁止从浏览器提取 Token/header，因此未执行。
- Evidence: 无安全 API 前置。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-11

- Case ID: COM-11
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 无故障注入夹具
- Expected: 列表或候选接口失败时显示可重试错误，不渲染为空或 0。
- Actual: 未提供测试代理、服务端开关或故障注入数据；不修改页面脚本或拦截真实请求。
- Evidence: 无安全故障注入前置。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-12

- Case ID: COM-12
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 无延迟注入夹具
- Expected: 快速切换筛选/菜单时，晚返回旧响应不得覆盖最终条件。
- Actual: 未提供可控网络延迟能力，且按要求未扩展页面交互。
- Evidence: 无安全延迟注入前置。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-13

- Case ID: COM-13
- Result: BLOCKED
- Method: BROWSER
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 无
- Expected: Network、响应、错误提示和可访问日志均不泄露敏感字段。
- Actual: 本次没有读取认证 header、请求/响应 body 或日志；因此也未形成覆盖完整敏感字段集合的安全扫描结论。
- Evidence: BrowserSkill 只读取可见结构与最小菜单 HTML；未执行 Network/body/日志扩展检查。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-14

- Case ID: COM-14
- Result: BLOCKED
- Method: API
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:00:00+0800
- Fixture Aliases: 无审计读取权限或动作夹具
- Expected: 创建、编辑、动作、导出、敏感读取产生正确审计，普通列表读取无错误写审计。
- Actual: 未提供审计查询能力，且本次禁止产生业务动作。
- Evidence: 无审计/API 前置。
- Issue Severity: NONE
- Cleanup: 无写入。

### COM-15

- Case ID: COM-15
- Result: BLOCKED
- Method: OUTPUT
- Started At / Finished At: 2026-08-30T16:00:00+0800 / 2026-08-30T16:01:19+0800
- Fixture Aliases: 环境 `test1`；租户及权限角色别名不可得
- Expected: 输出 RUN_ID、环境、四仓版本、测试租户、权限角色、开始时间与已知阻塞。
- Actual: 已输出 RUN_ID、test1 环境、证据时间和阻塞项；四仓运行时版本、测试租户与四权限角色缺失，运行清单不完整。
- Evidence: 本报告“结论”和“已知阻塞”章节。
- Issue Severity: NONE
- Cleanup: 未创建任何资源；BrowserSkill 会话已停止。

## 后续解阻条件

只有补齐下列信息并重跑 Agent 00，才能把门禁提升为 `PASS`：

1. test1 四个运行时制品的 full SHA/digest 查询入口；
2. `TENANT_A`、`TENANT_B` 测试租户别名及允许操作范围；
3. 四类权限用户别名与安全切换方式（不向 Agent 传递密码或 Token）；
4. 测试专用 API 调用渠道或可审计的非秘密认证代理；
5. 故障/延迟注入方式及审计读取权限；
6. 补跑“超链市场分析”刷新、浏览器历史/页签恢复与完整安全检查。

## 租户夹具补充核对

- 用户随后明确授权在 test1 创建两个测试租户。
- 创建前先对 test1 `tenant` 注册表进行只读核对，确认环境已经存在并启用两个预置租户：`TENANT_A=demo / 演示租户A / id=1`、`TENANT_B=demo2 / 演示租户B / id=2`。
- 为避免重复和污染，没有再插入新的租户行。
- 当前已提供测试账号属于 `TENANT_A` 且状态启用；`TENANT_A` 当前共有 15 个系统用户，`TENANT_B` 当前没有系统用户。
- 当前系统管理页面只有用户、角色和菜单管理，没有租户管理入口；后端也没有公开的租户创建接口。登录服务通过 `AUTH_DEFAULT_TENANT_ID` 固定查找默认租户，当前入口不能直接登录 `TENANT_B`。
- 因此 `COM-06` 仍为 `BLOCKED`：租户行已经具备，但 `TENANT_B` 的可登录用户及四类权限账号尚未具备。未读取密码哈希、Token、用户列表或业务数据，未执行任何数据库写入。

### TENANT_B 管理员补建

- 用户明确授权为 `TENANT_B` 配置独立测试登录能力后，先校验源测试账号、`TENANT_B` 的 `TENANT_ADMIN` 角色和目标用户名均符合前置条件。
- 已在单个数据库事务中创建 `TENANT_B` 测试管理员：`HLQA-20260830-HLQA-02-B-ADMIN`，数据库 ID `16`，状态启用，并绑定一条 `TENANT_ADMIN` 角色关系。
- 密码哈希仅在数据库内部从用户已授权的 test1 测试账号复制；未查询、输出或保存密码哈希，也未在报告中记录明文密码。
- 当前登录服务仍固定使用默认租户 `1`。计划中的临时切换会强制重建共享 `armada-backend`，造成一次短暂 test1 API 中断；该操作因需要在披露风险后取得明确批准而尚未执行。
- 已复核现有 `armada-backend` 与 `armada-nginx` 均保持原启动时间和 `running` 状态，公开 API 健康，未出现租户配置变化。

### 租户范围调整与清理

- 用户随后决定本轮暂不验证租户隔离，继续执行当前租户内的六菜单功能测试。
- 已精确删除本轮创建的 `TENANT_B` 测试管理员及其角色关系，复核剩余数量为 `0`；该测试账号不可恢复，如需重测必须重新创建。
- 已删除尚未使用的 tenant2 临时 Compose 覆盖文件；共享 `armada-backend` 从未执行租户切换或重启。
- `COM-02` 的租户别名确认、`COM-06` 的双租户/权限用户准备、`COM-07`～`COM-09` 的权限及跨租户验证维持 `BLOCKED`，但不再阻止用户明确授权的“单租户六菜单功能测试”。最终报告必须把这部分列为范围排除，不能记为 `PASS`。
