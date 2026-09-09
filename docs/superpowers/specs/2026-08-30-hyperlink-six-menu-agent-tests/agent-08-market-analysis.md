# Agent 08：超链市场分析

## 目标

验证 `/hyperlink/analysis` 的日/小时窗口、国家候选、全部筛选、八类 KPI、国家对明细、趋势聚合和数据口径。

## 前置与依赖

- 硬前置：Agent 00 公共门禁通过。
- 数据前置：优先消费 Agent 02 产生的发送、ACK、点击和封号投影事实；没有真实非零数据时，公式和去重用例必须 `BLOCKED`。
- 方法：统计口径和边界以 API 对账为主；筛选、快捷范围、KPI、表格、展开行、趋势图和错误重试使用浏览器。
- 浏览器操作必须使用腾讯开源的 `browser-skill`，通过 `bsk` 驱动已连接的 Chrome Agent Window，并保存 KPI、国家对表和趋势图证据；结束时必须停止本 Agent 的 `bsk` session。
- 如需自建受控聚合夹具，统一使用 `HLQA-{RUN_ID}-A08-`，不得修改生产统计事实。
- 每条输出 `Case ID / PASS|FAIL|BLOCKED / Method / Expected / Actual / Evidence / Severity / Cleanup`；只报告问题，不修改业务代码。

## 页面、时间与筛选

- [ ] ANALYSIS-01【API+浏览器】打开市场分析，验证默认按日、近 7 天、国家候选和主查询，无白屏和控制台异常。
- [ ] ANALYSIS-02【API+浏览器】验证日维度近 7、30、90 天快捷范围以及最多 90 个自然日。
- [ ] ANALYSIS-03【API+浏览器】验证小时维度近 24 小时、3 天、7 天快捷范围以及最多 168 个小时桶。
- [ ] ANALYSIS-04【API+浏览器】空范围、格式错误、开始晚于结束、日超过 90 天或小时超过 7 天必须拒绝。
- [ ] ANALYSIS-05【API+浏览器】分别验证即时/预发布/周期任务类型筛选。
- [ ] ANALYSIS-06【API+浏览器】分别验证发信国家和被营销国家筛选，ISO2 统一大写。
- [ ] ANALYSIS-07【API+浏览器】分别验证个人/商业账号、Android/iPhone 和短链启用/关闭筛选。
- [ ] ANALYSIS-08【API+浏览器】组合全部筛选，页面请求参数、overview、items 和显示范围一致。
- [ ] ANALYSIS-09【API+浏览器】国家候选跟随当前日/小时窗口并排除 `ZZ`；旧选择不再合法时自动清空。
- [ ] ANALYSIS-10【浏览器】重置恢复当前粒度默认范围并清空其他筛选，不残留旧国家或任务类型。

## KPI 与国家对表

- [ ] ANALYSIS-11【API+浏览器】核对发送量、单钩/单钩率、双钩/双钩率、访问率、点击 UV、使用号数、号均、封号/封号率。
- [ ] ANALYSIS-12【API】公式核对：单钩率=success/send，双钩率=delivered/success，访问率=UV/success，号均=success/used，封号率=banned/used。
- [ ] ANALYSIS-13【API+浏览器】分母为 0 时返回合理 0 并正确格式化，不出现 NaN、Infinity 或假百分比。
- [ ] ANALYSIS-14【API+浏览器】国家对表按“发信国 → 被营销国”展示 summary；展开后时间明细与 series 一致。
- [ ] ANALYSIS-15【API】顶部 overview 的使用号和封号必须全局去重，不能简单累加同一账号参与的多个国家对。
- [ ] ANALYSIS-16【API】国家对 series 保留时间桶内去重，overview 与国家对 summary 的口径差异必须符合设计。
- [ ] ANALYSIS-17【API+浏览器】未知国家显示为明确未知值，不生成错误国旗或把 `ZZ` 放入筛选候选。

## 趋势和业务语义

- [ ] ANALYSIS-18【API+浏览器】切换国家对表和趋势图，趋势按 statTime 稳定排序，所有国家对按桶正确汇总。
- [ ] ANALYSIS-19【API】设备筛选读取发送时 `device_os` 快照，不得用 `protocol_backend` 冒充 Android/iPhone。
- [ ] ANALYSIS-20【API】只统计 usageStatus=BANNED；普通 OFFLINE、PROXY_FAILED、需重登、登录替换和未注册号码不计封号。
- [ ] ANALYSIS-21【API】点击 UV 来自深度追踪事实；短链关闭任务不得产生伪点击，访问率分母使用单钩。
- [ ] ANALYSIS-22【API】日表和小时表读取正确；时间边界使用 Asia/Shanghai，左闭右开且没有跨日重复或遗漏。
- [ ] ANALYSIS-23【API】日表保留 90 天、小时表保留策略正常；清理任务不得删除保留窗口内数据或其他租户数据。
- [ ] ANALYSIS-24【API+浏览器】无数据时 overview 合理归零、表格显示空态、趋势图不画假数据。
- [ ] ANALYSIS-25【浏览器】国家候选失败和主查询失败分别提示并可独立重试，不能相互清空真实结果。
- [ ] ANALYSIS-26【浏览器】快速切换粒度、时间、国家和其他筛选，旧响应不得覆盖最后一次选择。
- [ ] ANALYSIS-27【权限】只有 view 权限才可读取页面和 API；所有统计严格限制当前租户。

## 交付与清理

- 输出至少一组非零 KPI 的手工公式对账和一组零分母结果。
- 输出国家对表与趋势图各一张脱敏截图，并记录同一查询 API 的关键数字。
- 市场分析是只读菜单，不得为了造数直接修改聚合表；没有受控数据准备能力时记录 `BLOCKED`。
