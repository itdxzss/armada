# 变更记录：普通营销任务单账号下群组发送间隔

- 日期 / 分支 / worktree: 2026-07-18 / `1.0.1-snapshot` / 主工作区
- 需求来源: 新增“单账号下群组发送间隔”，避免同一账号瞬间向全部群推送营销命令
- 设计文档: `docs/superpowers/specs/2026-07-18-marketing-account-group-send-interval-design.md`
- 实施计划: `docs/superpowers/plans/2026-07-18-marketing-account-group-send-interval.md`
- 状态: 本地实施完成，未提交、未部署；真库 DbTest 待确认

## 目标（一句话）

普通营销任务由 Armada 按账号以 0.5～3.0 秒固定间隔向 Kafka 推送群消息命令，协议端不改。

## 任务清单

- [x] 核对前端、任务聚合、轮次 worker、outbox 和 Web/Android 路由。
- [x] 前端增加字段、默认值、范围和一位小数校验。
- [x] 后端增加任务字段、API 映射、Flyway 和创建校验。
- [x] 轮次 worker 按账号计算每条命令的 `notBeforeAt`。
- [x] outbox 复用现有 `next_retry_at`，事务提交后按到期时间简单调度。
- [x] 删除独立 pace 表、outbox 新列、水位和复杂 dispatcher 逻辑。
- [x] 运行最终后端测试、打包与前端验证。
- [ ] 用户确认 `localhost:3306 / armada` 后再运行真库 DbTest。

## 关键决定

- 页面名称固定为“单账号下群组发送间隔”，默认 0.5 秒，范围 0.5～3.0 秒，步长 0.1 秒。
- 固定推送间隔，不等待上一条 WhatsApp 发送结果。
- 只修改 `wheel-saas-pure-web` 和 `armada`；不修改 `armada-protocol`。
- 任务配置按整数毫秒保存，Kafka payload 不增加字段。
- 同一轮中每个账号独立从第 0 个群开始排期，不同账号可并行。
- 采用简单本机定时投递，复用应用现有 `taskScheduler`、`protocol_command_outbox.next_retry_at` 和原有 10 秒扫描兜底。
- 旧任务默认 500ms；非普通营销命令立即投递。

## 验证

- 后端最终相关单元测试：90 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `mvn -DskipTests package`：`BUILD SUCCESS`，可执行 JAR 打包完成。
- 前端页面测试：12 tests，12 pass，0 fail。
- `tsc --noEmit`、`vue-tsc --noEmit --skipLibCheck`、受影响文件 ESLint 和 Prettier：全部通过。
- Vite 生产构建：2241 modules transformed，构建成功。
- `MarketingTaskMapper.xml` 通过 `xmllint --noout`。
- 前后端 `git diff --check` 通过。
- 前后端保持在本地 `1.0.1-snapshot`，没有新增 commit。
- 真库 DbTest 未运行，等待用户确认非敏感目标 `localhost:3306 / armada`；未输出数据库凭据。

## 遗留 / 跟进

- 本次不实现“上一条发送完成后再等待”的结果驱动节流。
- 本次不实现多实例共享的全局账号节流状态。
- 本次不扩展建群营销和历史群营销。
