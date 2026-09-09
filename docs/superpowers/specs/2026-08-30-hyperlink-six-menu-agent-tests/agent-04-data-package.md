# Agent 04：超链数据包

## 目标

验证 `/hyperlink/data` 的数据包 CRUD、TXT 导入、generation、号码明细、失败重置、号码/点击导出和点击分析。

## 前置与依赖

- 硬前置：Agent 00 公共门禁通过。
- 可与 Agent 06、07 并行执行。
- 方法：导入、generation、边界、租户和并发以 API 为主；主流程、弹框、筛选、下载和错误提示必须浏览器复核。
- 浏览器操作必须使用腾讯开源的 `browser-skill`，通过 `bsk` 驱动已连接的 Chrome Agent Window，并保存导入、导出和点击分析证据；结束时必须停止本 Agent 的 `bsk` session。
- 所有数据包和文件使用 `HLQA-{RUN_ID}-A04-` 前缀；导入文件只含受控测试号码。
- 每条输出 `Case ID / PASS|FAIL|BLOCKED / Method / Expected / Actual / Evidence / Severity / Cleanup`；只报告问题，不修改业务代码。

## 列表与 CRUD

- [ ] DATA-01【API+浏览器】打开数据包菜单，验证列表、国家候选、默认 pageSize=20、空态和错误重试。
- [ ] DATA-02【API+浏览器】分别验证名称、今天/昨天/自定义创建时间、国家和 UV 最小/最大比例筛选。
- [ ] DATA-03【API】非法国家、UV 不在 0～100、最小值大于最大值、结束早于开始必须返回稳定校验错误。
- [ ] DATA-04【API+浏览器】创建数据包，验证名称 trim、备注、默认 generation=1、version 和初始零指标。
- [ ] DATA-05【API+浏览器】名称必填且不超过 128 字、备注不超过 255 字、同租户有效名称唯一。
- [ ] DATA-06【API+浏览器】重命名和修改备注后 version 递增；两个会话使用旧 version 更新必须冲突。
- [ ] DATA-07【API】TENANT_A/TENANT_B 可以有相同名称，但不能互相读取、修改、导入或删除。

## TXT 导入与 generation

- [ ] DATA-08【API+浏览器】只接受 UTF-8 `.txt`；拒绝空文件、非 UTF-8、非 TXT 和未解析到有效号码的文件。
- [ ] DATA-09【API】100000 条非空行边界允许，100001 条必须拒绝且不写入部分数据。
- [ ] DATA-10【API+浏览器】检测马来西亚 60、新加坡 65、中国 86、香港 852、澳门 853、台湾 886 号码并阻止上传。
- [ ] DATA-11【浏览器】抽样全部为巴西 55 号码时显示 `+9/去9` 风险确认；取消不上传，确认才继续。
- [ ] DATA-12【API+浏览器】APPEND 导入核对 totalRows、acceptedRows、invalidRows、duplicatedRows 和导入后总数。
- [ ] DATA-13【API】APPEND 时文件内重复、包内重复和格式非法号码不得形成重复活动号码。
- [ ] DATA-14【API+浏览器】OVERWRITE 导入创建新 generation，并原子切换当前 generation；页面和明细只展示新代。
- [ ] DATA-15【API】OVERWRITE 失败时 currentGeneration 和原代统计保持不变，不得出现半切换。
- [ ] DATA-16【API】编辑、删除、APPEND、OVERWRITE 并发时不得产生跨代统计、重复活动行或软删除后继续导入。
- [ ] DATA-17【API】导入审计记录操作人、包 ID、模式、generation、行数和结果；报告不得包含完整号码。

## 号码、指标与导出

- [ ] DATA-18【API+浏览器】打开号码抽屉，验证默认 pageSize=50、分页、号码筛选和重置。
- [ ] DATA-19【API+浏览器】手机号筛选只允许最多 20 位数字；字母、符号和超长输入被前后端同时拒绝。
- [ ] DATA-20【API+浏览器】核对 total、unused、used、sent、delivered、failed、unregistered、clickUv 及漏斗百分比。
- [ ] DATA-21【API+浏览器】只重置 RETRYABLE_FAILED；UNREGISTERED 不得恢复，零可重置数时按钮禁用或提示无数据。
- [ ] DATA-22【API+浏览器】逐项导出全部、未使用、成功、单钩、双钩、失败、404 七种号码，数量和状态事实一致。
- [ ] DATA-23【API+浏览器】多选数据包批量导出；无选择禁用，单次超过 100 个拒绝，跨租户 ID 不泄露数据。
- [ ] DATA-24【API+浏览器】批量点击记录 TXT 只含收件人手机号，CSV 含规定业务字段，文件名和计数响应头正确。
- [ ] DATA-25【浏览器】导出本页 CSV 只包含当前页，带 UTF-8 BOM，中文正常，12 列和页面指标一致。
- [ ] DATA-26【浏览器】点击数据包名称打开访问趋势占位；无时间聚合时必须明确显示真实空态，不绘制假曲线。

## 点击分析

- [ ] DATA-27【API+浏览器】分别验证“从来不点”和“点击比例”两种分析模式。
- [ ] DATA-28【浏览器】默认阈值 5/10/15/20，允许添加/删除整数阈值；至少保留一个，比例不得超过 100。
- [ ] DATA-29【API+浏览器】验证今天、昨天、近 7 天、自定义范围和最多 90 天限制。
- [ ] DATA-30【API+浏览器】验证全局和收信国家维度的 bucket count/percent、factSourceReady 和空态。
- [ ] DATA-31【API+浏览器】按模式、阈值和国家导出号码，导出数量必须等于所选 bucket。
- [ ] DATA-32【浏览器】快速切换模式、日期、国家和阈值，旧响应不得覆盖最终选择。

## 删除与交付

- [ ] DATA-33【API+浏览器】删除确认和取消行为正确；成功后列表、详情和新任务候选均不可见。
- [ ] DATA-34【API】删除后名称允许按服务端合同复用；号码保留期清理不得影响其他租户或当前活动包。
- 为 Agent 01/09 输出：空包、小包、含多国家包、含失败状态包的别名和 ID。
- 保留已明确移交的夹具，其余数据包软删除；记录导入文件和本地下载文件清理结果。
