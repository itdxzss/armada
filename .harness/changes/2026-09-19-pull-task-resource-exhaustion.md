# 拉群换群冲突与资源耗尽结束

## 用户确认

- 选群同时排除已使用的 JID 和链接。
- 绑定时发生唯一键竞争，跳过该候选，尝试后续候选。
- 群资源耗尽直接结束任务，不进入等待群资源，不因以后补群自动继续。
- 本轮仅主仓修改与本地验证，未授权部署、修改线上状态或重新执行任务。

## 现场与原因

第二套任务 `任务_13077084`，后台 task=48。历史执行217已结束，链接仍存在但JID为空；候选13813命中同一个链接，却未被只按JID的筛选排除。绑定时触发 `uq_pull_task_execution_link(tenant_id, task_id, normalized_link)`，旧代码吞掉唯一冲突返回0，下一轮再次取同一个候选。220/221/222因此没有账号角色或协议动作。原始只读证据：`/private/tmp/pull-13077084-diag/`。

## 实现

- `PullTaskExecutionTransactionService` 保留JID排除，并复用活动链接占用查询，新增本任务历史链接查询（含终态、缺JID记录）。
- 先校验并发名额，再领取群，避免并发已满时把待轮到的TXT误判成群耗尽。
- 每个候选每轮最多尝试一次；绑定唯一冲突记录不含链接的日志并继续后续候选。版本/租约CAS失败仍返回调度器，不误判资源耗尽。
- 无候选或本轮候选全部绑定冲突时，调用任务生命周期的群耗尽结束入口，写 `ENDED`、结束时间和“群资源已耗尽，任务结束”。复用人工结束的执行行取消、未发布命令取消、拉手释放、数据包投影流程；已有成功及已提交未知结果遵循原结束语义。
- 旧 `WAIT_GROUP_RESOURCE` 枚举及历史数据读/人工恢复兼容保留，本路径不再产生该状态。
- 构造注入生命周期服务使用延迟代理，避免其调度唤醒依赖回到本执行服务产生循环。

## 验证

- 先红：缺JID历史链接测试无工作项返回；资源耗尽测试实际仍为WAIT_GROUP_RESOURCE，2个回归测试均复现。
- JDK17 + 显式Byte Buddy agent；在 `/private/tmp/armada-pull-resource-fix/armada-api` 独立源快照/target中测试，避免干扰主仓其他会话构建。
- 5个聚焦测试类共79项，失败0、错误0、跳过0：ExecutionTransactionService 17，StandardLifecycleService 7，ExecutionEndToEndIntegration 17，GroupExecutionMapperInMemory 28，ExecutionSlotMapper 10。
- H2 MySQL模式加载真实Mapper、租户插件及Spring事务。覆盖缺JID历史链接排除、其他任务活动链接排除、租户隔离、并发名额、空池结束、筛选后实际竞争写入触发唯一冲突再选后继、全部冲突结束、任务结束清理与旧链路回归。
- 竞争测试通过测试专用MyBatis拦截器控制独立JDBC写入时序，业务SQL和唯一约束均真实执行，无mock Mapper。
- `xmllint --noout` 和 `git diff --check` 通过。H2不等同InnoDB，本轮未做真库写入/真实协议业务验收。

## 影响与交付边界

共享普通拉群的运行时选群/换群入口适用，包含DIRECT_LINK、RESOURCE_POOL和配置来源分组的普通链接重试。无数据库结构、Redis、HTTP接口变更；保留现有唯一约束及历史执行记录。未提交、未推送、未部署。

回滚仅撤销本记录涉及的选群/结束改动，保留其他会话的在途内容；已经结束的任务不因回滚自动恢复。
