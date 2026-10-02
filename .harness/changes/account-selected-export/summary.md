# 账号勾选原格式导出与移除

## 用户最终要求

在主仓库实现。业务人员自己批量离线，导出按钮只校验离线；所选六段、全参、JSON 分文件打 ZIP，交付后从控端移除。

## 实现

- 后端 `AccountExportController / AccountExportService / AccountExportMapper / AccountExportArchive`。
- 前端独立 `AccountExportDrawer` 和 `account-export.ts`，复用原列表勾选，提供记录重下载。
- 原始材料严格通过成功导入明细的 account_id 关联；不从运行凭据反推格式、不按整个批次扩大集合。
- ZIP 持久化后状态预占为 EXPORTED。运行凭据查询及上线条件更新拒绝导出预占；真实 ONLINE 回调仍更新登录态，避免把重新在线藏起来。
- 下载接口不删除；客户端完整接收并触发保存后发回执，事务软删账号/运行凭据，释放代理。失败可重试，重复回执幂等。
- 未交付可取消；文件有效期 24h，后台每分钟最多清理 100 项；未交付到期恢复原业务状态，不自动上线。
- 账号/分组只读查询无 FOR UPDATE；生成 ZIP 后用条件更新核对状态/归属/占用与数量。仅同一导出作业交付、取消和清理互斥，作业锁不覆盖文件生成或网络下载。

## API / DB / Redis

- `POST /api/accounts/exports`：`{requestId, ids}`，返回作业元数据。
- `GET /api/accounts/exports`：本人最近 50 次记录，不含敏感内容。
- `GET /api/accounts/exports/{id}/file`：私有 ZIP 附件，no-store。
- `POST /api/accounts/exports/{id}/complete`：`{sha256}`，幂等交付移除。
- `POST /api/accounts/exports/{id}/cancel`：取消未交付作业。
- 接口要求账号 view + edit 权限，并校验租户、账号归属、作业创建人。
- V204 新增 `account_export_job` / `account_export_item`，凭据原文仍以既有导入明细为真相源。导出文件仅作为有 TTL 的交付副本。
- 无 Redis 结构变更。数据模型使用本目录 `generate-model.py` 从 V204 离线生成两表段落，保留其他会话改动。

## 验证

- Java 17 + 现有 Byte Buddy agent 执行 8 个相关测试类，共 **115 项通过，0 失败/错误**：Archive 4、ExportServiceH2 13、AccountService 16、StateEvent 9、StateEventConcurrencyH2 8、OnlineCommand 38、BatchLifecycle 10、AccountController 17。
- 新增 H2 用例真实执行 V204、导出/凭据/状态 Mapper、租户插件、Spring 事务；覆盖选择精确性、重复回执、缺材料、无权访问、非离线、取消、过期、下载后事务失败回滚、无锁快照后的状态变化，以及条件更新与上线竞争。
- 前端相关测试 **29 项通过**，使用 `node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test ...`；直接调用项目已安装的 TypeScript/vue-tsc、ESLint 和 Vite 通过。Vite 输出至 `/tmp/account-export-web-build`，不覆盖本地已有 dist。
- 新旧 Mapper XML `xmllint --noout` 通过，两个仓库 `git diff --check` 通过；API 文档生成测试通过。
- 初次 Maven 默认 Java 23 的 Mockito 自挂载不可用，改用项目要求的 Java 17 和已安装 agent 后通过；pnpm 触发自动依赖检查/联网失败，改用现有 node_modules 的同一工具直接执行，未升级或重装依赖。
- 未连接真实 MySQL、未部署、未对真实账号下线/导出/删除；真实环境文件下载与账号再次登录未验收。

## 部署与回滚

未 commit / push / 部署。用户指定环境后再执行 Flyway 与前后端部署。

回滚优先关闭新建入口，保留现存作业下载、完成和取消能力；先取消未交付作业再回退代码。保留作业表至现有产物过期，禁止直接 DROP 导致导出预占无法恢复。已交付账号不自动恢复或上线。

## 边界

单次最多 500 个账号、4MB 原始材料；表头全选只覆盖当前页。历史缺原文、非导入来源或来源不唯一明确拒绝。保留原始导入内容，未新增协议层实时凭据迁移；真实账号二次登录未验收。H2 验证事务与条件更新，不等同 MySQL InnoDB 现场并发压测。
