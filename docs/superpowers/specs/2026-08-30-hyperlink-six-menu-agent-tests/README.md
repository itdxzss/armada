# 超链营销六菜单多 Agent 验收包

> 日期：2026-08-30
>
> 适用范围：Armada 测试环境中的“超链营销”六个菜单
>
> 禁止范围：生产环境、非白名单真实 WhatsApp 账号、未经确认的真实钱包或批量真实发送

## 1. 文档与 Agent 分配

每个 Agent 只领取一份执行文档；协调者负责提供同一个 `RUN_ID`、测试环境别名和允许使用的测试租户。

| Agent | 文档 | 负责范围 |
|---|---|---|
| Agent 00 | [公共门禁](./agent-00-common-gate.md) | 环境、版本、菜单、RBAC、租户隔离和证据规范 |
| Agent 01 | [超链任务列表与编辑器](./agent-01-task-list-editor.md) | 任务列表、筛选、导出、新建、编辑、查看、复制 |
| Agent 02 | [超链任务生命周期](./agent-02-task-lifecycle.md) | 报价、准备、启动、暂停、继续、停止、幂等和受控 canary |
| Agent 03 | [超链任务详情与统计](./agent-03-task-detail-stats.md) | 收信人、账号统计、归因、访问趋势、封号原因 |
| Agent 04 | [超链数据包](./agent-04-data-package.md) | 数据包 CRUD、导入、号码、导出和点击分析 |
| Agent 05 | [超链营销模板](./agent-05-template.md) | 三类模板、素材绑定、复制、编辑和删除 |
| Agent 06 | [超链策略](./agent-06-strategy.md) | 策略 CRUD、账号筛选、试算、启停和任务快照 |
| Agent 07 | [图片素材](./agent-07-asset-library.md) | 素材上传、筛选、标签、编辑、删除和引用保护 |
| Agent 08 | [超链市场分析](./agent-08-market-analysis.md) | 日/小时统计、筛选、KPI、国家对和趋势 |
| Agent 09 | [端到端与权限收口](./agent-09-e2e-rbac.md) | 六菜单串联、全权限矩阵、跨租户和最终清理 |

## 2. 前后关系

```text
Agent 00 公共门禁
  ├─ Agent 04 数据包 ──────────────┐
  ├─ Agent 06 策略 ────────────────┤
  └─ Agent 07 图片素材 ── Agent 05 模板 ─┤
                                      ├─ Agent 01 任务列表与编辑器
                                      │       └─ Agent 02 任务生命周期
                                      │               ├─ Agent 03 任务详情与统计
                                      │               └─ Agent 08 市场分析
                                      └────────────────────── Agent 09 最终端到端
```

- `Agent 00` 是硬前置：环境、版本或权限夹具不可信时，其他 Agent 不得开始产生状态的测试。
- `Agent 04`、`Agent 06`、`Agent 07` 在公共门禁通过后可以并行。
- `Agent 05` 完整测试依赖一个合法素材；可读取 `Agent 07` 产出的素材别名，也可以用自己的命名空间创建。
- `Agent 01` 完整测试需要数据包、模板、策略、素材；允许自行创建专属夹具，避免等待其他 Agent。
- `Agent 02` 需要可启动任务；优先消费 `Agent 01` 的任务别名，也可以自行创建。
- `Agent 03` 和 `Agent 08` 需要真实运行事实或受控聚合夹具；没有数据时只能判定 `BLOCKED`，不能用前端零值冒充通过。
- `Agent 09` 必须最后执行，它不替代任何单菜单测试。

## 3. API 还是浏览器

本套测试必须同时使用两种方式，不能二选一：

- `API`：验证参数边界、业务错误码、租户隔离、权限、乐观锁、并发、幂等、状态机和数据一致性。
- `浏览器`：验证菜单、路由、按钮权限、表单校验、弹框/抽屉、筛选分页、预览、下载、错误提示和真实用户路径。
- `API+浏览器`：先用 API 准备小而可控的夹具，再通过浏览器完成主流程，最后用 API 读取并对账。
- `CANARY`：真实 WhatsApp、真实钱包或公网短链动作，只有在协调者再次明确授权并提供白名单资源后执行；否则记录 `BLOCKED`。

所有浏览器操作统一使用腾讯开源的 `browser-skill`，通过 `bsk` 驱动已连接的 Chrome Agent Window，不得自行改用应用内 Browser、临时 Playwright 服务或其他浏览器控制面。执行前必须确认 `bsk doctor` 全部通过；每个浏览器任务必须执行 `bsk session start`，所有命令携带同一个 `--session`，并在成功、失败或阻塞时执行 `bsk session stop <id>`。允许复用 Chrome 现有登录态，但禁止读取 cookie、localStorage、密码或浏览器配置文件。浏览器用例至少保存关键页面截图和对应 Network 请求摘要。

建议总体比例是 API 约 60%～70%，浏览器约 30%～40%。所有面向用户的核心路径至少必须完整点击一次，不能只根据接口 200 判定页面通过。

## 4. 统一执行约定

1. 每个 Agent 使用独立命名空间：`HLQA-{RUN_ID}-{AGENT_CODE}-`。
2. 只使用测试租户和测试账号；不得在请求、日志或报告中回显 Token、密码、完整手机号、IP、UA、shortCode、消息正文或图片内部路径。
3. 每条用例只能记录 `PASS`、`FAIL` 或 `BLOCKED`：未执行、缺夹具、缺权限、缺 canary 授权均为 `BLOCKED`，不能写 `SKIPPED` 或“应该通过”。
4. `PASS` 必须包含真实请求/响应或浏览器证据；代码存在、单元测试存在和页面能打开都不足以证明业务通过。
5. 所有写操作在测试结束后清理；不能清理的资源必须记录别名、ID、当前状态和原因。
6. Agent 不得修改业务代码来适配失败用例；发现问题只提交缺陷报告和证据。

## 5. 每条用例输出格式

```text
Case ID:
Result: PASS | FAIL | BLOCKED
Method: API | BROWSER | API+BROWSER | CANARY
Started At / Finished At:
Fixture Aliases:
Expected:
Actual:
Evidence:
Issue Severity: P0 | P1 | P2 | P3 | NONE
Cleanup:
```

严重度建议：跨租户、越权、重复扣费、非白名单发送、重复物理发送和开放重定向为 P0；核心 CRUD/状态机不可用为 P1；统计、导出或重要交互错误为 P2；纯文案或轻微样式为 P3。

## 6. 总完成条件

- Agent 00～09 所有 required 用例都有结果，且没有未解释的 `BLOCKED`。
- 所有 P0/P1 缺陷关闭并复测；P2/P3 有明确接受或修复结论。
- API、浏览器和最终端到端对同一业务事实的结果一致。
- 所有测试任务已经停止，测试数据已清理，未留下继续发送或轮询的任务。
