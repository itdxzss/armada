# Agent 09：六菜单端到端、RBAC 与最终收口

## 目标

在单菜单测试完成后，用同一个 RUN_ID 验证六个菜单之间的数据流、快照、权限、租户隔离、统计收敛和最终清理。

## 前置与依赖

- 硬前置：Agent 00～08 已提交结果；存在未解释的环境/版本 `BLOCKED` 时不得开始最终验收。
- 依赖：读取其他 Agent 输出的 fixture 别名，不接收密码、Token 或完整手机号。
- 方法：所有端到端主旅程必须浏览器操作，关键阶段同时用 API 读取并对账。
- 浏览器操作必须使用腾讯开源的 `browser-skill`，通过 `bsk` 驱动已连接的 Chrome Agent Window，并保存各菜单串联过程的截图与 Network 摘要；结束时必须停止本 Agent 的 `bsk` session。
- `CANARY` 仍需单独授权；无授权时相关旅程为 `BLOCKED`，不得用 mock 或静态页面替代。
- 每条输出 `Case ID / PASS|FAIL|BLOCKED / Method / Expected / Actual / Evidence / Severity / Cleanup`；只报告问题，不修改业务代码。

## 六菜单主旅程

- [ ] E2E-01【API+浏览器】图片素材上传合法 JPEG，并在素材库验证标签、缩略图和引用数初始值。
- [ ] E2E-02【API+浏览器】使用该素材创建单图文或按钮模板，回到素材库验证引用数增加且删除被阻止。
- [ ] E2E-03【API+浏览器】创建数据包并导入受控号码，核对 accepted/invalid/duplicated 和当前 generation。
- [ ] E2E-04【API+浏览器】创建启用策略并验证它出现在任务“引用策略”候选中。
- [ ] E2E-05【API+浏览器】新建任务并引用模板、策略、数据包和素材，核对模板/策略只覆盖允许字段。
- [ ] E2E-06【API+浏览器】先保存停用草稿，再报价并启动，观察 PROCESSING→READY→RUNNING 或明确失败终态。
- [ ] E2E-07【API+浏览器】运行任务依次暂停、继续和停止，列表按钮、详情状态、recipient、claim 和计费状态同步。
- [ ] E2E-08【API+浏览器】任务产生事实后核对数据包指标、任务摘要、账号统计和市场分析的发送/单钩/双钩/点击结果。
- [ ] E2E-09【API+浏览器】修改或删除源模板和源策略，已有任务消息内容和策略快照保持不变。
- [ ] E2E-10【API+浏览器】任务仍引用素材时删除必须失败；任务和引用清理后素材才允许删除。

## 短链与真实闭环

- [ ] E2E-11【CANARY】Web 白名单账号发送一条短链任务消息，真实收信端只出现一个 CTA 和一条消息。
- [ ] E2E-12【CANARY】Android 白名单账号发送一条卡片按钮消息，图片、卡片正文、CTA、ACK 和任务关联正确。
- [ ] E2E-13【CANARY】同一 shortCode 点击两次，第一次 UV+1/PV+1，第二次只 PV+1，均 302 到冻结 HTTP(S) 目标。
- [ ] E2E-14【API+浏览器】点击投影收敛后，任务归因、趋势、数据包点击分析和市场分析点击 UV 口径一致。
- [ ] E2E-15【API】无效码、大小写变体、脏目标、非 HTTP(S) 目标和失效内容不能开放重定向或产生统计。

## 权限与租户闭环

- [ ] E2E-16【API+浏览器】USER_ALL 可执行本套已授权动作；USER_VIEW 只能查看；USER_NO_ACCESS 看不到菜单且 API 403。
- [ ] E2E-17【API+浏览器】USER_EXPORT 可使用普通导出，但无 attribution_sensitive 时不能读取或导出 IP/UA。
- [ ] E2E-18【API】直接调用被页面隐藏的 create/edit/delete/import/export/action 接口，后端权限必须独立生效。
- [ ] E2E-19【API】TENANT_A/TENANT_B 交叉使用 taskId、jobId、sourceTaskId、dataPackageId、templateId、strategyId、assetId 均不可读写。
- [ ] E2E-20【API】另一用户猜导出 jobId 时，状态和下载同时校验当前租户与创建人，不暴露文件路径。
- [ ] E2E-21【安全】审计覆盖创建、编辑、动作、导出、敏感读取和计费变化；日志不含敏感值。

## 可靠性和最终清理

- [ ] E2E-22【浏览器】六个菜单连续操作过程中无未处理 4xx/5xx、控制台异常、重复 toast、假成功和旧请求覆盖。
- [ ] E2E-23【API】重复提交、旧 version、并发动作和重复 command 不产生重复任务、重复扣费或重复物理发送。
- [ ] E2E-24【API】所有统计在允许投影窗口后收敛；不一致时记录原始事实、投影水位和受影响菜单，不强行判 PASS。
- [ ] E2E-25【API+浏览器】停止全部仍运行或暂停的测试任务，等待在途命令收口。
- [ ] E2E-26【API】按依赖逆序清理：任务 → 模板/策略/数据包 → 无引用素材；记录每项清理结果。
- [ ] E2E-27【浏览器】清理后六个菜单不再显示本 RUN_ID 的临时数据，且没有后台继续发送或轮询。
- [ ] E2E-28【报告】汇总 Agent 00～09 的 PASS/FAIL/BLOCKED、P0～P3 缺陷、未清理资源和最终发布建议。

## 最终判定

- `ACCEPTED`：全部 required 用例完成，无 P0/P1，所有阻塞已解除或经业务 owner 明确裁剪范围。
- `REJECTED`：存在 P0/P1 或 API/页面/事实之间的核心不一致。
- `BLOCKED`：环境、版本、真实账号、钱包、统计夹具或证据不足，无法形成可信判断。
