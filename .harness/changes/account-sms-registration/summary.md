# 接码注册任务

## 目标与边界

在账号域创建可恢复的 WhatsApp 美国接码注册任务。数量表示采购尝试次数，失败不补购。注册成功后复用账号导入、账号分组和 Android 自动上线，只在协议回报在线后计成功。主仓本地修改；不 commit/push/部署，不调用真实收费或注册接口。

## 数据模型

- `account_registration_task` 保存租户、幂等 requestId、固定采购价格/次数、注册类型与目标分组；不重复保存最终账号状态。
- `account_registration_item` 保存每次采购的工作流、外部订单 ID、注册 ID、实际成本、导入/账号关联和数据库租约。OTP 和六段均不入此表。
- 现有 `account`/`account_group`/`account_credential`/导入批次是账号和分组事实源，不创建重复模型。
- 新增 Flyway V193（开始时最新 V192），记录逐项状态值及索引。

## API

`/api/account-registrations` 下 catalog、price-tiers、POST 创建、GET 分页、GET /{id} 明细、POST /{id}/cancel。统一 `tenant:account:edit`；tenant 只来自服务器上下文。

## 一致性与恢复

- 创建只落库；幂等 `(tenant_id, request_id)` 唯一，同键不同参数拒绝。
- 全局 Redis token 锁保护每次 tick；数据库 item leaseToken/leaseUntil 条件更新，防止过期 worker 覆盖。
- 采购前提交 PURCHASING；采购超时/进程崩溃变 UNKNOWN，禁止盲重买。
- 优先推进已有在途条目，默认一条从购买到最终状态，取消只取消未采购项。
- HTTP 不放事务；导入和关联注册明细放同一短事务，复用现有自动上线 scheduler。
- Cobalt ID 稳定，查询恢复而非换 ID 重试；日志仅固定错误码。

## Redis / 配置

专属 `armada:account-registration:tick` 锁，令牌比较释放；TTL 180 秒（大于单步 HTTP 上限）。默认关闭业务开关和 scheduler。Grizzly 与 Cobalt 自身开关及 Cobalt health 是采购前门禁。

## 验证

- [ ] 状态、幂等参数、价格/国家校验和未知采购测试。
- [ ] H2 真实 Mapper/XML/租户/分页/CAS/取消/租约测试。
- [ ] 导入关联事务、在线成功边界与无敏感凭据响应测试。
- [ ] 本地聚焦测试结果登记。
- [ ] 真实购买、Cobalt 注册、Android 登录未执行。

## 回滚

先关闭本功能开关和 scheduler；仅撤销本任务代码。已采购订单保留供核对，不通过回滚自动退款。新表有真实订单后禁止直接删除。
