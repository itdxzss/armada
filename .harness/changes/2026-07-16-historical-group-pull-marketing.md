# 变更记录：历史群单群拉人及营销

- 日期 / 分支 / worktree: 2026-07-16 / `1.0.1-snapshot` / 当前 Armada worktree
- 需求来源: 用户本次逐项确认；`docs/superpowers/specs/2026-07-16-historical-group-pull-marketing-design.md`
- 实施计划: `docs/superpowers/plans/2026-07-16-historical-group-pull-marketing.md`
- 状态: 本地代码验证与测试环境部署完成；待目标环境菜单配置、真库 DbTest 和真实群验收

## 目标（一句话）

在一个“历史群管理”菜单内，基于账号 baseline 展示全部历史群，并完成详情懒加载、管理员操作、拉手踩链接单群拉人和全部 A 账号一次性营销发送的 MVP 闭环。

## 缺口拆解 / 任务清单

- [x] 核对 Armada、前端和协议层现有 baseline、群详情、进群、成员管理和发送能力。
- [x] 与用户确认一期范围、操作门禁、失败语义和非目标。
- [x] 完成跨仓设计文档。
- [x] 编写分仓、分步骤实施计划。
- [x] 协议层增加 baseline 交集的有界 metadata 摘要接口，并为历史营销发送跳过预检。
- [x] Armada 增加 baseline 摘要、历史群聚合和单群执行持久化。
- [x] Armada 接通固定操作账号的群详情、成员管理、拉人和一次性营销发送。
- [x] 前端增加唯一历史群菜单及页面内全部交互。
- [x] 完成三仓单测、契约测试、构建和前端静态验收。
- [x] 将当前三个仓库的本地未提交文件部署到已确认的测试环境。
- [ ] 完成真库 DbTest、目标环境菜单配置和真实群业务验收。

## 关键设计决策

- baseline JID 列表是历史群范围唯一事实，当前同步不能覆盖或扩张它。
- baseline 首次上报本来就有群名；Armada 只新增 `baseline_group_subjects` 保存 JID→subject，不在首次上线时逐群读取 metadata。
- 当前群列表保持轻量；Armada 先与 baseline 求交集，再调用有界批量 metadata 摘要，协议响应不返回成员数组。
- 前端只新增一个菜单；群详情、单群拉人、执行记录和营销发送都在同一页面上下文内。
- 拉手只能踩系统获取的邀请链接进群；不支持操作账号邀请，也不支持手工输入链接。
- 获取不到邀请链接时，成员管理、拉人和营销全部禁用。
- 采用独立的单群执行聚合，不补齐旧前端通用拉人任务壳，不复用建群营销任务。
- A 标记表示营销账号；去重冲突时营销身份优先；拉群排序营销账号优先。
- 本次全部 A 账号各完整发送模板一次；不做发送前协议检查，不重试，逐账号记录结果。
- 操作账号为管理员时支持提升、降级和踢人；群主和本人受保护；批量操作部分失败继续。
- 一期不做任何跨任务账号占用控制。

## 验证（evidence-before-done）

- Armada：21 个聚焦测试类合计 130 tests，0 failures / 0 errors；`test-compile`、3 个 mapper XML、`git diff --check` 通过。
- Armada 接口文档：20 controllers / 108 endpoints，生成器契约测试通过，已重建 `armada_endpoints.json` 与 `接口协议.md`。
- armada-protocol：7 suites / 100 tests 通过；TypeScript build、`git diff --check` 通过。
- wheel-saas-pure-web：5 个入口共 38 tests 通过；`tsc`、`vue-tsc`、Vite build、ESLint、Prettier 与 `git diff --check` 通过。
- 真库 DbTest 已编写；本机 MySQL 建连持续停在 Hikari `Starting...`，此前同环境返回 `08S01 Communications link failure`，因此本地真库断言不能标记通过，数据模型 wiki 未伪造刷新。
- 测试环境启动日志确认 Flyway 从 v055 顺序应用 V056、V057，最终为 v057；两个 migration 用时约 0.115 秒。
- 三仓均确认 cached diff 为空，没有 `git add` 或 commit；目标环境唯一菜单仍待配置。

## 部署

- 来源: 按用户要求直接部署三个仓库当前本地未提交文件，不使用远端分支或 commit，也不部署未改动的 Zhuan 工程。
- 环境: Armada 默认测试环境；2026-07-16 执行。
- 命令顺序: `deploy-test.sh --protocol -y` → `deploy-test.sh --be -y` → `deploy-test.sh --fe -y`。
- 协议层: 远端 TypeScript 构建通过；master + 4 workers 均为 `online`、Node.js 24.16.0；`/healthz` 返回 200；`metadata-summaries` 已存在于 worker 和 master 编译产物。
- 后端: 镜像与容器启动成功；首次脚本探测早于 Spring Boot 启动完成而返回 502，约 10 秒后复查真实入口返回 200；历史群列表和营销路由返回预期租户业务响应；近期无启动致命日志。
- 前端: 生产构建通过并部署；首页与 `platform-config.json` 返回 200，环境标识正确；远端产物包含 12 个 HistoricalGroup 相关静态文件。
- 部署后状态: backend、nginx 和 5 个协议 PM2 目标进程均持续运行；未执行真实群拉人、成员变更或营销发送。
- 旧 Web 账号兼容热修: 账号 `302` 的 `protocol_id=NULL` 原本被历史群服务误判为“协议身份不完整”；现统一沿用 `ProtocolBackend.fromProtocolId(null) -> WEB` 的既有兼容语义，且 Web 拉手 SQL 接受空协议类型、仍排除显式 Android 账号。无需重新导入、回填数据库或修改账号数据。
- 热修验证: 回归测试先出现 2 个预期失败再转绿；6 个聚焦测试类合计 50 tests，0 failures / 0 errors；`test-compile`、`AccountMapper.xml` 校验和 `git diff --check` 通过。测试库只读核验显示 207/207 个在线正常旧 Web 账号被新条件覆盖，显式 Android 错配为 0；真库 DbTest 仅完成编译，未在本机标记执行通过。
- 热修部署: 后端当前本地文件已重新打包并部署；部署脚本仍因探测早于 Spring 启动而短暂得到 502，条件式复查确认容器存活且 `Started Application in 9.711 seconds`。本地 JAR、远端部署包和运行容器内 JAR 的 SHA-256 均为 `b4a8c63b1ca830ef5818b001e9a8172e20a061a9f076e48faaacb15ff211db2f`。
- 群列表 HTTP 链路热修: Web backend 配置现按 `PROTOCOL_WEB_*` → `ARMADA_PROTOCOL_*` → `PROTOCOL_*` 顺序继承，避免部署变量存在时仍回落到 `localhost:3000`；协议 master 精确放行并转发 `GET /v1/accounts/{accountId}/groups`，不扩大其它账号路由。
- 群列表热修验证: 后端配置/协议路由测试均完成 RED→GREEN；Armada 28 个聚焦测试通过，协议层 51 suites / 437 tests 与 TypeScript build 通过。协议层和后端均已重新部署，master 日志确认 GET 被转发到 owner worker。
- 测试网络配置: 协议机安全组仅向后端内网来源放行 TCP 8080，测试后端 `ARMADA_PROTOCOL_BASE_URL` 已切换为 `http://172.31.3.208:8080`。后端宿主机和容器访问 `/healthz` 均返回 200；容器请求账号 `302` 群列表已收到协议业务响应，不再连接超时。账号当前仍为 `NEED_REAUTH`，因此 owner worker 返回 `ACCOUNT_NOT_FOUND`，需真实重新认证上线后再做群列表业务验收。
- 操作账号筛选热修: 历史群页面选择账号分组后，账号列表请求固定携带 `accountState=2`（正常）与 `loginState=1`（在线）；只收窄该页面的操作账号下拉框，不改变账号分组总数、其他页面或拉手选择逻辑。新增断言完成 RED→GREEN；历史群 4 个测试入口共 32 tests、`tsc`、`vue-tsc`、Vite build、ESLint、Prettier 和差异检查均通过。
- 操作账号筛选部署: 仅将当前本地前端文件重新部署到第一套测试环境，nginx 重建并持续运行，首页返回 200、环境标识正确；远端实际 `index-C3BrIPgn.js` 已核对包含 `{accountGroupId:n,accountState:ae,loginState:ne,page:1,pageSize:500}`，其中 `ae=2`、`ne=1`。
- 群列表加载超时调整: 仅为前端 `POST /api/historical-groups/refresh` 设置 60 秒 Axios 超时，全局 10 秒超时、其它接口、后端协议读取超时、协议并发和不重试语义均保持不变。API 测试完成 RED→GREEN；历史群 4 个测试入口共 32 tests、`tsc`、`vue-tsc`、Vite build、ESLint、Prettier 和差异检查均通过。仅前端重新部署到第一套测试环境，首页返回 200；远端 `HistoricalGroupPullPanel-CbOCpl8L.js` 已核对刷新调用包含 `{timeout:6e4}`。
- 操作账号筛选临时口径（已撤销）: 曾临时移除正常/在线过滤并展示分组全部账号，同时补充分页聚合和 loading 复位。数据库核验后确认该宽松口径不符合最终业务要求。
- 操作账号筛选最终回退: 历史群账号分页请求已恢复固定携带 `accountState=2`（正常）与 `loginState=1`（在线），分页聚合和 loading 复位继续保留。第一套测试库只读核验确认账号 `302` 为 `account_state=5`（解绑）、`login_state=2`（离线），被严格筛选排除属于预期行为；测试库不存在 `login_state=1` 但账号状态非正常的记录。
- 生产菜单数据源确认后只配置以下一个菜单，不另建拉人或营销子菜单：
  `path=/group/history`、`name=HistoricalGroupManagement`、
  `component=group/history/index`、`module_key=historical_group`、
  `perm_key=tenant:historical_group:view`、`title=历史群管理`。

## 遗留 / 跟进

- 生产菜单数据源不在三仓内；目标环境确认后按计划中的唯一菜单元组配置。
- 三仓均存在群详情相关在途修改，实施时只做增量集成，不覆盖用户未提交内容。
- 老 baseline 只有 JID，账号退出群后无法反向恢复名称、成员、权限或邀请链接。
- 拉手一期只选择 Web 账号；Android 当前只有 join 能力，没有联系人保存和成员 ADD 路由，纯 Android 拉手分组会明确返回 `PULLER_BACKEND_UNSUPPORTED`。
- Android worker 当前不识别历史群消息 correlation；Android 营销号一期逐账号记录 `MARKETING_BACKEND_UNSUPPORTED`，Web 营销号正常发送。完整 Android 支持需另行授权修改第四个 worker 仓库。
- 后续优化候选：跨任务账号占用、多群批量、自动恢复/重试；均不属于一期。
