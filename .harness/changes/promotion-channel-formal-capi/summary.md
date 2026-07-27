# 变更记录：推广渠道正式 Facebook CAPI 上报

- 日期 / 分支 / worktree：2026-07-26 / `1.0.1-snapshot-wyfBranch` / 用户当前 IDEA 工作区
- 需求来源：渠道页三个上报事件改为 Meta 18 个官方标准事件，并按意向用户、协议受理、登录成功三个真实业务触发点正式上报
- 状态：代码已实现；原有编译错误和本地 Flyway 重复版本已修复，未部署

## 目标

移除前端手工测试探测交互，将 Facebook 渠道的三个可配置标准事件按真实配对业务状态可靠投递到 Meta CAPI，协议层接口和事件结构保持不变。

## 实现

- 新增 `GET /api/promotion-channels/facebook-standard-events`，返回顺序稳定的 18 个 Meta 官方标准事件；Facebook 渠道三个字段只接受该目录值。
- 前端删除渠道列表“探测”按钮、测试码弹窗和探测 API；新增、编辑表单的三个事件下拉框共用后端目录。
- 落地页创建配对会话时可选携带 `_fbp`、`_fbc`、来源 URL；后端只在直连来源属于可信私网/回环反代时采用 `X-Real-IP`，否则使用真实远端地址。
- 会话创建事务写入三条事件快照：`LEAD` 待发送，`LOGIN_REQUEST`、`LOGIN_SUCCESS` 等待触发；事件名在创建时冻结。
- 协议层接受配对请求后，在会话状态事务内激活请求登录事件；账号落库、代理确认和会话成功写入同一事务后激活登录成功事件。
- 配对失败或过期只取消尚未触发的事件；已触发事件继续按 Outbox 规则投递。
- 调度器跨租户扫描候选，并在每条发送前单独 CAS 领取，使用稳定 `event_id` 有界指数退避重试；成功、永久失败和取消均清理手机号哈希、IP、UA、`fbp/fbc` 与来源 URL。
- 可选归因字段非法时逐项丢弃，不阻断配对主流程；来源 URL 必须与渠道域名一致，且上报前移除查询参数和片段。
- 所有临时匹配字段具有七天硬保留期限；独立清理任务不受正式投递开关影响，超期后终止未完成投递并清空敏感字段。
- Access Token 仍仅在渠道 Service 内解密；正式出站只允许官方 HTTPS Graph API，不包含 `test_event_code`，日志和 Outbox 错误不记录 Token 或 Meta 原始响应。

## 数据模型

- Flyway：`V080__promotion_capi_event_outbox.sql`；账号期望登录状态沿用 1.0.1 的 `V077__account_desired_login_state.sql`。
- 原 `V062__account_desired_login_state.sql` 调整为 V072，保留 canonical 的 `V062__promotion_channel_country_values.sql`，当前工作区迁移版本唯一。
- 新表 `promotion_capi_event_outbox` 独立承载业务阶段快照、重试状态和临时匹配数据，不把投递状态塞入配对会话或渠道配置宽表。
- `(tenant_id, pairing_session_id, event_stage)` 保证同一会话同一阶段唯一；`event_id` 全局唯一并在重试间保持不变。
- `(status, locked_at, id)` 支持失效锁恢复，`(sensitive_expires_at, id)` 支持隐私期限清理。
- 未连接未确认数据库，因此未运行 `.harness/wiki/gen_datamodel.py`；测试库执行 Flyway 后必须基于真实 `information_schema` 重新生成数据模型文档。

## 验证

- 前端定向 Node 测试：24/24 通过。
- 前端 `pnpm typecheck`、本次文件 ESLint、Prettier 校验和 `pnpm build` 通过。
- 后端 `mvn -q -DskipTests package` 通过，完整主代码和测试代码编译通过。
- 后端 15 个相关非真库测试类共 134/134 通过；其中本次修复的账号代理快照和代理候选 CAS 测试共 53/53 通过。
- `IpProxyMapper.xml` XML 解析与参数契约检查通过；Flyway 本地文件版本唯一性和迁移 SQL 契约测试通过。
- Mapper DbTest 和 Flyway 真库验证未执行：当前未确认目标 MySQL，连接真实测试库的尝试因数据库不可达而在执行 SQL 前失败，因此不声称真库验证通过。

## 部署与遗留

- 未 commit、未 push、未部署，也未调用真实 Meta Pixel。
- V071、删除旧 V062 和新增 V072 必须进入同一提交、同一制品，禁止先发布 V072 后补 V071。
- 部署前必须确认目标库为 MySQL 8，并只读检查 `flyway_schema_history` 的 V061-V072：canonical 环境应为 `V061__promotion_template_channel_statistics.sql`、`V062__promotion_channel_country_values.sql`；如果目标库记录的是历史 account 分支的 `V061__group_pull_marketing.sql`、`V062__account_desired_login_state.sql`，必须停止上线并做一次性迁移谱系对账，不能直接 `flyway repair`。
- 目标库谱系确认后再运行 Flyway validate/migrate、Mapper DbTest，并在 Meta Events Manager 核对三个正式业务事件。
- 如生产暂时需要停止投递，可设置 `PROMOTION_CAPI_SCHEDULER_ENABLED=false`；已写入 Outbox 的事件不会被删除，恢复开关后继续投递。
