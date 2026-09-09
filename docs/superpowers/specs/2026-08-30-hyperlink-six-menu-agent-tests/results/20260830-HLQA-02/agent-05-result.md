# Agent 05 营销模板验收结果

- RUN_ID：`20260830-HLQA-02`
- 环境：`test1 / 第一套环境`
- 测试身份：`HLQA-20260830-HLQA-02-A05`，页面昵称已确认 `HLQA A05 Agent`
- 命名空间：`HLQA-20260830-HLQA-02-A05-`
- 总体结论：`BLOCKED`（本轮按协调者要求跳过租户/RBAC，且宿主 API 通道不可用，未将未覆盖项写 PASS）
- 安全：未触发 WhatsApp、钱包、批量发送或 canary；未读取 cookie、localStorage、Token 或密码；未修改业务代码。

## 已验证事实与保留夹具

- 保留模板：`HLQA-20260830-HLQA-02-A05-BUTTON`，ID `9`，类型 `普通按钮`；创建、详情回填与更新请求均为 HTTP 200。此为本轮明确移交 Agent 01/09 的唯一保留夹具。
- 辅助写入记录：`HLQA-20260830-HLQA-02-A05-SINGLE`，ID `10`，单图文创建请求 HTTP 200，已绑定 Agent 07 JPEG 素材 ID `30`（320×180，4.5 KB）。协调者要求立即释放共享浏览器前无法清理，故记录为未清理辅助夹具，不作为下游依赖。
- 素材读取证据：选择器列表和详情/内容请求 `GET /api/resource-assets/30`、`GET /api/resource-assets/30/content` 均为 HTTP 200，页面已显示缩略图和 Object URL 回显。

## 用例结果

### TPL-01

- Result: BLOCKED
- Method: API+BROWSER
- Expected: 默认分页、名称/类型筛选、空态与错误重试均正确。
- Actual: 页面打开；默认 20 条/页，名称筛选可回读 ID 9，类型筛选控件可见；空态与故障重试未执行。
- Evidence: `evidence/agent-05/tpl-01-list.png`；BrowserSkill `GET /api/hyperlink-templates` HTTP 200。
- Issue Severity: NONE
- Cleanup: 无。

### TPL-02 / TPL-03

- Result: BLOCKED
- Method: API+BROWSER / API
- Expected: 权限矩阵与 options 查询/租户隔离。
- Actual: 用户明确跳过租户与 RBAC；宿主 API 连接不可用，未执行 options 参数合同。
- Evidence: 范围调整与 Agent 00 API 阻塞记录。
- Issue Severity: NONE
- Cleanup: 无。

### TPL-04

- Result: PASS
- Method: API+BROWSER
- Expected: 单图文保存名称、标题、正文、链接描述、推广链接和预览素材。
- Actual: ID 10 已创建；表单和实时预览回显正文、标题、链接描述、`example.test` 域名，素材 ID 30 可见且服务端创建请求 HTTP 200。
- Evidence: `evidence/agent-05/tpl-04-single-preview.png`；Network #143-#149。
- Issue Severity: NONE
- Cleanup: ID 10 记录为未清理辅助夹具；不作为下游依赖。

### TPL-05

- Result: PASS
- Method: API+BROWSER
- Expected: 普通按钮含单个 CTA、文字、HTTP(S) URL、可选底部小字和预览。
- Actual: ID 9 已创建，普通按钮预览和表单显示单 CTA `查看详情`、`https://example.test/...` 及底部小字；创建请求 HTTP 200。
- Evidence: `evidence/agent-05/tpl-05-button-preview.png`；Network #116-#117。
- Issue Severity: NONE
- Cleanup: ID 9 为下游唯一保留夹具。

### TPL-06 / TPL-07

- Result: BLOCKED
- Method: API+BROWSER
- Expected: 卡片按钮创建与双图文拒绝。
- Actual: 因共享浏览器时限收口，未执行。
- Evidence: none。
- Issue Severity: NONE
- Cleanup: 无。

### TPL-08 / TPL-09 / TPL-10 / TPL-11

- Result: BLOCKED
- Method: API
- Expected: schema、类型、字段边界、CTA 与 URL 非法负向合同。
- Actual: 宿主 API 到 test1 不可用，未形成可信服务端响应。
- Evidence: Agent 00/04 API 连通性阻塞；本 Agent 只记录页面内同源请求摘要。
- Issue Severity: NONE
- Cleanup: 无。

### TPL-12

- Result: PASS
- Method: API+BROWSER
- Expected: 从素材库搜索并选择合法素材，鉴权内容正确回显。
- Actual: 素材选择器搜索到 ID 30，显示 JPEG、320×180、4.5 KB、标签；选中后单图文表单显示图片、名称与 ID 30；详情/内容请求均 HTTP 200，页面 Blob/Object URL 回显成功。
- Evidence: `evidence/agent-05/tpl-12-asset-picker-selected.png`；Network #143-#148。
- Issue Severity: NONE
- Cleanup: 素材 ID 30 由 Agent 07 交接，保留。

### TPL-13 / TPL-14 / TPL-15

- Result: BLOCKED
- Method: API / BROWSER
- Expected: 非法素材、切换清理和快速切换 Object URL 场景。
- Actual: 未执行负向/时序用例。
- Evidence: none。
- Issue Severity: NONE
- Cleanup: 无。

### TPL-16

- Result: BLOCKED
- Method: API+BROWSER
- Expected: 完整编辑、version 原子递增、旧 version 冲突及页面刷新提示。
- Actual: ID 9 编辑表单完整回填；修改底部小字后 `PUT /api/hyperlink-templates/9` HTTP 200，列表更新时间刷新并提示“超链营销模板已更新”。版本数和旧版本冲突未获取 API 合同，故不可判 PASS。
- Evidence: `evidence/agent-05/tpl-16-edit-preview.png`；Network #151-#153。
- Issue Severity: NONE
- Cleanup: ID 9 保留。

### TPL-17 / TPL-19 / TPL-21

- Result: BLOCKED
- Method: API
- Expected: 名称唯一/并发、复制素材锁定、跨租户不可见。
- Actual: API/跨租户夹具不可用；未执行。
- Evidence: Agent 00 范围调整。
- Issue Severity: NONE
- Cleanup: 无。

### TPL-18

- Result: BLOCKED
- Method: API+BROWSER
- Expected: 复制生成唯一副本，version=1。
- Actual: 列表中复制入口可见；为立即释放共享浏览器未点击。
- Evidence: `evidence/agent-05/tpl-18-list-before-copy.png`。
- Issue Severity: NONE
- Cleanup: 无副本产生。

### TPL-20

- Result: BLOCKED
- Method: API+BROWSER
- Expected: 删除确认、取消和成功后不可读取。
- Actual: 删除入口可见；为保留 Agent 01 夹具且协调者要求立即收口，未执行删除。
- Evidence: `evidence/agent-05/tpl-18-list-before-copy.png`。
- Issue Severity: NONE
- Cleanup: ID 9 为下游保留夹具；ID 10 为已记录的未清理辅助夹具。

### TPL-22

- Result: BLOCKED
- Method: API+BROWSER
- Expected: 任务引用后的内容/素材快照不随模板变更删除而改变。
- Actual: 未创建任务，避免触发业务发送链路。
- Evidence: none。
- Issue Severity: NONE
- Cleanup: 无。

## 证据索引

- `evidence/agent-05/a05-home.png`
- `evidence/agent-05/tpl-01-list.png`
- `evidence/agent-05/tpl-04-single-preview.png`
- `evidence/agent-05/tpl-05-button-preview.png`
- `evidence/agent-05/tpl-12-asset-picker-selected.png`
- `evidence/agent-05/tpl-16-edit-preview.png`
- `evidence/agent-05/tpl-18-list-before-copy.png`

## 缺陷

未发现可由本轮证据确认的产品缺陷。未覆盖项均按 `BLOCKED` 记录，不以页面存在或 HTTP 200 替代服务端边界、权限、并发和跨租户验证。
