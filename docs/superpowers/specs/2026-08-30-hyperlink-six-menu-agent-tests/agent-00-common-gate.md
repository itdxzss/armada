# Agent 00：公共门禁、环境与权限

## 目标

在其他 Agent 产生测试数据前，确认目标环境、候选版本、六个菜单、权限夹具和租户隔离均可形成可信测试结论。

## 前置与方法

- 硬前置：协调者明确测试环境别名；若无法证明不是生产，立即 `BLOCKED`。
- 方法：API+浏览器。
- 浏览器操作必须使用腾讯开源的 `browser-skill`，通过 `bsk` 驱动已连接的 Chrome Agent Window；复用现有登录态但不得读取 cookie、localStorage 或密码，结束时必须停止本 Agent 的 `bsk` session。
- 不执行真实 WhatsApp 发送、钱包扣费或批量业务数据修改。
- 测试数据统一使用 `HLQA-{RUN_ID}-A00-` 前缀。
- 每条输出 `Case ID / PASS|FAIL|BLOCKED / Method / Expected / Actual / Evidence / Severity / Cleanup`；只报告问题，不修改业务代码。

## 用例

- [ ] COM-01【API】读取运行时版本或制品信息，记录前端、Armada、Web 协议、Android 协议实际 full SHA/digest；版本混用则 `BLOCKED`。
- [ ] COM-02【浏览器】登录测试账号，确认当前域名、租户和环境标识属于指定测试环境。
- [ ] COM-03【API+浏览器】验证菜单顺序为：超链任务、超链数据包、超链营销模板、超链策略、图片素材、超链市场分析。
- [ ] COM-04【浏览器】依次打开六个菜单并直接刷新 URL，验证不白屏、不 404、不跳错模块。
- [ ] COM-05【浏览器】验证浏览器后退、前进、菜单切换和标签页恢复不会串页或残留上一菜单状态。
- [ ] COM-06【API】准备 TENANT_A、TENANT_B 以及 USER_ALL、USER_VIEW、USER_EXPORT、USER_NO_ACCESS 权限夹具；只记录别名，不记录凭据。
- [ ] COM-07【API+浏览器】无页面 view 权限时菜单不可见，直接访问 URL 不能读到数据，直接调用接口返回 403。
- [ ] COM-08【API+浏览器】逐域验证 create、edit、delete、import、export、action、attribution_sensitive 权限相互独立。
- [ ] COM-09【API】TENANT_A 用户使用 TENANT_B 的 taskId、dataPackageId、templateId、strategyId、assetId，统一返回 403/NOT_FOUND 且不泄露资源是否存在。
- [ ] COM-10【API】验证分页从 1 开始，非法 page/pageSize、非法时间范围、未知枚举和未知排序字段返回稳定校验错误。
- [ ] COM-11【浏览器】模拟列表接口失败和候选接口失败，页面必须显示可重试错误，不得把失败渲染成空数据或 0。
- [ ] COM-12【浏览器】快速切换筛选和菜单，使旧请求晚返回，验证旧响应不得覆盖最终条件。
- [ ] COM-13【安全】检查浏览器 Network、响应、错误提示和可访问日志，不得出现 Token、完整号码、IP、UA、shortCode、消息正文或内部文件路径。
- [ ] COM-14【审计】验证登录用户执行创建、编辑、动作、导出和敏感读取后存在对应审计；普通列表读取不产生错误写审计。
- [ ] COM-15【输出】产出统一运行清单：RUN_ID、环境别名、四仓版本、测试租户别名、权限角色别名、测试开始时间和已知阻塞项。

## 交付

- 向协调者返回 `gate=PASS|FAIL|BLOCKED`。
- 只有 `gate=PASS` 时才允许其他 Agent 执行写入型用例。
- 不向其他 Agent 传递密码或 Token，只传环境入口、租户/用户别名和允许操作的资源范围。
