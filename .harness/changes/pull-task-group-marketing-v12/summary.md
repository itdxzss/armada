# 变更记录：拉群营销 V1.2 端到端实施

- 日期 / 分支 / worktree: 2026-08-01 / `feat/group-marketing-v12-candidates` / `/Users/yanwc/IdeaProjects/armada`
- 需求来源: `拉群营销_PRD需求文档_V1.2_修订标红版_结构调整版.docx`、`group-marketing-standalone.html`、用户要求“规划一个列表，一点一点的做，做好”
- 设计: `docs/superpowers/specs/2026-08-01-group-marketing-v1-2-implementation-design.md`
- 状态: 第 01 项已完成，等待用户 review

## 目标（一句话）

把现有统一列表、全局设置和前端原型扩展为可创建、可执行、可暂停恢复、可追溯的拉群营销任务闭环。

## 缺口拆解 / 任务清单

- [x] 01 候选群组去重聚合、可选原因、等待池软占用
- [ ] 02 真实账号分组、营销模板、目标数据清洗和资源预览
- [ ] 03 草稿创建、编辑及任务配置/模板/全局设置快照
- [ ] 04 启动前实时复核、群硬锁、资源规划与目标号码预占
- [ ] 05 单群接管执行器、管理员交接和安全检查点
- [ ] 06 拉手、水军、目标号码分批执行与唯一使用
- [ ] 07 营销账号首次资格、静默、发送结果与群组封控
- [ ] 08 暂停、恢复、永久停止、补充拉手和强制营销
- [ ] 09 任务详情、群组明细、四页签、统计生产者和审计
- [ ] 10 全量测试、跨仓联调、测试环境迁移和 PRD 验收

## 当前实施：01 候选群组与等待池

- [x] H2 失败测试：同 JID 去重并聚合全部可操作管理员
- [x] H2 失败测试：普通成员、封禁/解绑/删除账号不可使群组可选
- [x] H2 失败测试：历史群和自收群来源识别及来源信息
- [x] H2 失败测试：软占用唯一、跨任务冲突、租约过期和移出恢复
- [x] 后端分页查询、加入/移出/读取/释放等待池 API
- [x] 前端候选表、跨页选择、等待池恢复与真实接口接入
- [x] Docker MySQL/Redis + 真实后端 + 真实浏览器端到端测试
- [x] 本分片回归与 change 证据更新

## 第 01 项实现结果

- 候选群以 JID 为业务唯一键分页；历史来源读取 `account_group_baseline`，自收来源读取最早成功晋升的
  `join_task_result`，同群存在多条自收记录时来源任务 ID、名称和时间来自同一条记录。
- SQL 聚合同群全部创建者/管理员关系；管理账号筛选不会误命中普通成员，普通成员群仅在显式开启时展示且不可选。
- 状态策略明确区分正常、等待账号上线、无管理权限、账号失效、群封禁、链接失效、健康未知和其他任务占用，
  并返回逐群不可选原因。
- 新增 `pull_task_group_marketing_group_occupancy`：数据库唯一键保证同租户同 JID 单一有效占用；
  `WAITING` 使用两小时可续租软锁，列表/读取/加入会清理过期锁，避免异常关页永久占群。
- 等待池 token 绑定当前认证用户；跨用户读取/释放失败。前端在当前标签页保存 token，刷新后从服务端恢复；
  正常取消或离开创建路由会释放整个等待池。
- 前端候选页使用真实分页、JID row key、跨页勾选和状态原因；只有服务端成功入池的群才写入创建草稿的目标群列表。
- 端到端测试发现并修复了两个仅靠静态检查未暴露的问题：前端遗漏 `WheelPagination` 导入、
  `selectByGroupJids` 复用 SQL 片段时访问了不存在的 `query` 参数。

## 第 01 项 API

- `GET /api/pull-tasks/group-marketing/candidate-groups`
- `GET /api/pull-tasks/group-marketing/waiting-pool?reservationToken=...`
- `POST /api/pull-tasks/group-marketing/waiting-pool`
- `POST /api/pull-tasks/group-marketing/waiting-pool/remove`
- `DELETE /api/pull-tasks/group-marketing/waiting-pool?reservationToken=...`

## 关键设计决策

- `group_link.origin` 是首次入池来源，不能单独代表 PRD 的历史老群/自收群；历史老群由
  `account_group_baseline` 识别，自收群由成功且已晋升管理员的 `join_task_result` 识别。
- 同群多账号在 SQL 中按 JID 聚合；账号详情单独批量查询，避免字符串拼接承载业务对象。
- 等待池和硬锁使用独立占用表，避免把任务瞬时状态写进跨业务共享的 `group_link`。
- 等待池是持久化租约而不是永久软锁；任务创建分片会把同一行原子转换为 `HARD_LOCK`。
- 待确认默认值不写死；不依赖它们的分片先实施。

## 验证（evidence-before-done）

- 基线后端：`PullTaskUnifiedListMigrationTest` + `PullTaskMapperInMemoryTest`，8 项通过。
- 基线前端：统一列表与创建页结构 Node 测试，32 项通过。
- 后端定向：6 个测试类共 18 项，覆盖候选 Policy、真实 H2 Mapper/Service/事务/并发、迁移结构、
  服务并发冲突、权限契约和 Docker MySQL 8.4 InnoDB 并发唯一占用，
  `Failures: 0, Errors: 0, Skipped: 0`。
- 后端 XML：两个新增 Mapper XML 经 `xmllint --noout` 解析通过。
- API 文档：`python3 .harness/wiki/test_api_docs.py`，1 项通过，识别 35 个 Controller / 176 个端点。
- 前端定向：API、创建页、草稿、交互、候选/等待池共 20 项，全部通过。
- 前端静态门禁：`pnpm typecheck`、本次 13 个代码文件的 ESLint 零警告、`pnpm build` 均通过。
- 端到端：`bash armada-api/src/test/e2e/run-group-marketing-e2e.sh` 通过。脚本从空 MySQL 8.4 库执行
  90 个 Flyway 迁移到 V089，启动 Redis、完整 Spring Boot 和前端开发服务器；Playwright 使用真实验证码登录，
  完成跨页选择两个群、加入等待池、数据库占用计数、刷新恢复、移出一个群、取消释放并确认占用归零。
- 浏览器视觉：桌面端和 375px 小屏全页截图已人工检查；小屏等待应用进入 `mobile + hideSidebar` 后截图，
  页面按既有响应式规则切为单列。测试同时断言无浏览器 console error 和 API 4xx/5xx。
- 全量普通测试曾尝试运行；仓库既有 `HttpFacebookCapiClientTest` 因受限环境禁止绑定本地端口失败，
  且 `GroupLinkRegistryServiceImplTest`（名称未带 DbTest）仍尝试连接未配置 MySQL，因此不作为本分片通过证据。
- `.harness/wiki/gen_datamodel.py` 未运行：它要求真库 `information_schema` 的三个 `/tmp/wheel_*.tsv`，
  当前文件不存在且本次未获授权连接共享数据库；未手工修改自动生成的 `数据模型.md`。

## 部署

- commit / 环境 / 部署后验证结果: 尚未提交、未部署；未连接远程或真实数据库。

## 2026-08-01 环境 1 候选群超时修复

- 分支：`fix/group-marketing-candidate-query-timeout`。
- 根因：候选群统计和分页分别执行候选聚合，原 SQL 对每条当前群关系使用
  `JSON_CONTAINS` 扫描租户全部 baseline JSON；环境 1 单次简化统计约 `6.88s`，两次查询超过前端
  `10s` 超时。
- 修复：MySQL 使用 `JSON_TABLE` 一次展开租户 baseline，按二进制 JID 去重后再关联当前群关系；
  显式传递可信 `TenantContext`，并完整约束所有相关表的租户条件。H2 测试继续执行等价
  `JSON_CONTAINS` 分支，不进入生产运行时。
- 语义对账：环境 1 旧、新历史群集合均为 `5015` 条，双向差集均为 `0`；优化后的简化统计约
  `0.11s`。过滤条件、聚合口径、来源判定和分页排序未修改。
- 数据库：无表、列、索引和 Flyway 变更，迁移最高版本仍为 `V089`。
- 验证：SQL 结构测试 2 项、H2 真 Mapper/租户隔离及相关服务回归共 23 项通过；真实 MySQL 8.4、
  Redis、Spring Boot、前端和 Playwright 端到端用例 1 项通过。首次端到端运行发现
  `ONLY_FULL_GROUP_BY` 兼容问题，补充回归断言并修复后完整重跑通过。
- 部署：待提交并部署环境 1 后补充实际接口耗时和 Flyway 启动日志证据。

## 遗留 / 跟进

- PRD 第 23 节的默认值和邀请链接重置口径需业务/协议确认；详见设计第 7 节。
- 国家/大洲、入群审批、成员邀请权限和群存续天数目前没有可 SQL 下推的同步字段，前端筛选项已显式禁用，
  不做内存分页或伪筛选；后续元数据同步分片补齐后再开放。
- H2 已执行窗口函数、租户插件、分页和占用 SQL；Docker MySQL 8.4 已验证 V089 生成列/唯一索引、
  释放后复用和真实 InnoDB 并发竞争。共享测试环境仍未部署，部署前需按目标环境流程确认。
