# 通讯录营销执行进度与回执统计

- 日期：2026-09-10。
- 用户授权：在主仓实施设计，不 commit、不 push。未授权部署。
- 主仓分支：前后端均为 `1.0.3-snapshot`；起始 Armada `a690ed13`、前端 `eaf2a50`。
- 状态：本地代码与验证完成；真实环境性能和 Android/Web 设备验收待授权发布后执行。

## 实现

- 列表改为已处理 / 计划，UNKNOWN 属于处理终态且不自动重发；准备中不显示最终百分比，零分母显示 `—`。
- S / D / R 为累计发送确认、送达、已读，互斥筛选为仅单勾、已送达未读、已读；列表、账号、明细、CSV 复用同一统计定义。
- 新增 `GET /api/contact-tasks/{id}/stats` 与任务级 `/{id}/recipients`（taskAccountId / sendStatus / receiptStatus / errorCode）。保留旧账号级 HTTP 明细入口以支持前后端分批发布，内部统一委托新查询。
- 列表按当前页任务 ID 批量聚合，账号指标全任务排序后分页，明细过滤和 count 均下推 SQL；沿用租户插件和 `tenant:contact_task:view` 权限，在 REPEATABLE_READ 只读事务中取得一致快照。
- 单个结果抽屉包含概览、账号数据、联系人明细，支持指标与原因钻取；可见时每 10 秒串行刷新，关闭、浏览器后台、缓存页面停用后停止；任务完成后仍可刷新迟到回执。
- “封号数”改为“执行异常账号数”，有收件人账号数使用当次聚合。统计缺失与矛盾显式提示，不伪造零或正常百分比。
- 回执新增已携带 accountId 的归属校验。现有 command/JID/messageId/tenant 校验保留；协议事件没有通讯录 task/recipient ID，不扩展协议契约。
- 删除旧账号排序查询与旧联系人抽屉，保留发送/准备 H2 测试仍使用的实体明细 Mapper。

## 验证证据

- 聚焦改动先红后绿：错误发信账号 ACK 的 H2 用例先失败，再通过归属校验修复；统计与前端指标用例跟随实现验证。
- 后端相关回归 206 项，0 失败/错误/跳过，含 10 项新增 StatsH2：真实 Mapper、租户插件、事务、SQL 聚合、跨页/账号筛选、排序白名单、权限和接口参数、任务结束后重复迟到 READ、全 UNKNOWN 和矛盾状态。
- 清理旧账号排序 SQL 后 StatsH2 10 项再次通过；旧静态排序测试已转向新 StatsMapper 并复核。
- 接口文档生成测试 1 项通过（59 controllers / 312 endpoints）。
- 前端相关 Node 130 项通过；完整 tsc / vue-tsc、定向 ESLint / Stylelint / Prettier、Vite 生产构建通过。
- Playwright 2 项通过：实际列表进度与 CSV、累计回执入口，以及结果抽屉筛选/第二页/账号排序、迟到 READ、定时刷新/关闭/后台停刷新、准备中与全 UNKNOWN、390px 窄屏。API 全部拦截为合成数据，外部网络被拦截，无真实发送。
- 检查过本地桌面和窄屏截图：`/private/tmp/contact-receipts-product.png`、`/private/tmp/contact-receipts-product-mobile.png`。

复现命令（在各仓对应根目录，使用可用的 Maven/Node）：

```sh
# armada-api
mvn -q '-Dtest=ContactTask*Test,ContactCloudAudienceResolutionTest,CloudStatusAudienceCollectorTest' test
# armada
python3 .harness/wiki/test_api_docs.py
# wheel-saas-pure-web；Node 24 自带 TS strip，沿用项目 alias loader，避免 tsx 先改写 API alias
node --import ./src/api/__tests__/node-test-alias.mjs --test 'src/views/contact/hyperlink/**/*.test.ts' src/views/contact/hyperlink/ContactHyperlinkIndex.test.ts src/api/contact-task.test.ts
# 启动本地 Vite 8856 后
ARMADA_E2E_BASE_URL=http://127.0.0.1:8856 ARMADA_E2E_BROWSER_CHANNEL=chrome node node_modules/@playwright/test/cli.js test e2e/contact-receipts.spec.ts --reporter=line
```

本机 Maven 测试需要沙箱外 JVM attach 才能运行已有 Mockito 用例；均使用本地 H2，不读真实数据库。

## 发布与限制

无数据库迁移、Redis key 或协议契约修改。后续发布先后端再前端；回滚对应前后端制品即可，回执事实保留。旧前端需要的 HTTP 路径和旧字段语义仍保留；新前端遇到缺少 stats 的旧后端会明确显示统计不可用。

未执行真库 EXPLAIN/规模性能验证、test1 部署、真实 Android/Web 发送确认/送达/已读验收。当前测试证明本地实现，不能代表截图中任务 5 的真实执行结果。

设计与依据见[设计方案](../../docs/superpowers/specs/2026-09-10-contact-marketing-receipt-metrics-design.md)。

## 发布授权与发布前复核

2026-09-10 用户追加授权 commit、push、部署。目标为主仓 itdxzss/armada 与 itdxzss/wheel-saas-pure-web 的 1.0.3-snapshot，第一套测试环境 test1，范围后端与前端，协议层不变。已核对远端无领先提交、Flyway 无重复版本、Mapper XML、已有测试证据和部署 dry-run。专家复核发现列表有收件人账号数仍用旧字段，已切换为 stats.accounts.readyAccountNum 并增加页面断言。部署脚本回归通过。下方记录发布实际结果。
