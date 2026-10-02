# 群链接模式（新）实施记录

- 日期：2026-09-19
- 位置：用户要求直接在 armada / wheel-saas-pure-web 主仓 `1.0.3-snapshot` 实施。
- 状态：实现完成，已于 2026-09-19 部署第二套 perf2；未提交或推送，尚未进行真实业务验收。
- 分析：[需求与依赖](../../../docs/business/2026-09-19-pull-task-direct-link-analysis.md)

## 已确认边界

新增相邻 tab 群链接模式（新）。拉手以普通成员身份踩链接进群拉人；取消管理分组、执行策略、群信息设置、任务内互加和 A/a 料子提权。没有草稿，点击创建一次生成正式任务，后续执行与详情复用已有能力。旧模式维持原行为。

## 实施契约

- 使用现有 creation_mode 的新增值 DIRECT_LINK 表达新模式，保持 task_type=STANDARD、mode=NORMAL_LINK。无需另存一份重复流程身份；所有群来源判断明确适配该值。
- 新阶段 DIRECT_PULLER_JOIN(10) 负责无管理号的拉手分配及入群；入群成功绑定真实群 JID。
- POST /api/pull-tasks/standard/direct-link：multipart request(JSON) + files。包含请求幂等键、参数、链接或群分组、TXT/数据包，无 draftTaskId。
- 建单事务失败整笔回滚；关闭自动启动直接创建正式待启动任务。自动启动发生在建单提交后，启动失败保留正式任务，重试通过同一 requestId 读回。
- 数据迁移放宽管理分组空值，添加正式创建请求的幂等约束；具体版本见迁移脚本。
- 不新增 Redis 结构、队列或调度系统。

## 分工与进度

- [x] 前端 tab、直接创建、列表/详情与本地检查。
- [x] 后端直接创建、数据包、幂等和兼容迁移。
- [x] 无管理号执行、群 JID、补拉手、恢复和换群。
- [x] 读回/补管理 API 的新模式边界与综合验证。
- [x] 聚焦单元/H2/Mapper 测试、旧流程回归、前端检查。
- [x] 最终变更审查与证据记录。

## 验证策略

Java 测试使用 JDK 17 与已有 Byte Buddy agent；在临时源码构建副本运行，避免并发会话共用 target。源码修改仍全部在用户指定主仓。数据库测试使用 test-scope H2 和真实 Mapper，不连接环境数据库。

## 部署与回滚

已部署第二套前后端并成功应用 V208。部署证据见 [发布记录](../../../docs/operations/evidence/perf2-direct-link-20260919.md)。回滚先关闭新任务创建，妥善处理在途 DIRECT_LINK 任务；禁止让旧后端运行不认识的阶段。数据库迁移不得在存在新模式数据时盲目恢复管理字段 NOT NULL。

## 当前验证证据

- 前端：151/151 Node 回归，2/2 本地浏览器场景，TypeScript/vue-tsc、目标 ESLint/Stylelint/Prettier、Vite build 通过。浏览器使用全 API 拦截，验证新表单无草稿请求、旧输入保留、提交锁定、失败重试 requestId 稳定。
- 后端创建/接口/资源及旧创建回归：88 项通过，0 失败/错误。含真实 H2 Mapper/租户插件/事务、重复提交、来源冲突回滚、TXT/数据包 A 标记边界、正式启动和补管理拒绝。
- API 文档生成回归 1 项通过；Mapper XML 解析及 diff-check 通过。
- 广回归发现 6 类既有测试夹具/静态断言问题，在未修改的 HEAD `0bb0a688` 临时副本复现（23 项失败/错误）；另一次长 JVM 执行退出 134，后续按聚焦批次验证。不能称全库测试通过。
- 既有失败类：PullTaskMapperBusinessConditionTest、PullTaskNormalLinkSchemaSelfTest、PullTaskLifecycleMapperInMemoryTest、PullTaskGroupMarketingGroupMapperInMemoryTest、PullTaskMapperInMemoryTest、PullTaskGroupSettingsApplyTimingIntegrationTest。
- 未连接真库、未调用真实 WhatsApp；本地端到端测试中的协议边界为可控替身，不等同上线业务验收。

测试原始结果目录：`/private/tmp/armada-direct-link-test.Ru5KxC/`；HEAD 对照目录：`/private/tmp/armada-direct-link-baseline/`。

## 最终补齐与评审

- 仅新模式正常完成时执行拉手完成分组归档：选择仍占用且已确认入群的拉手，账号去重排序，先通过账号域转组服务迁移再释放租约；转组失败与完成状态同事务回滚。旧模式保持原逻辑。
- 重复创建的自动启动乐观锁冲突，只在读回同一请求已经启动的任务时收敛；仍待启动的真实错误继续报出。
- 原 Spring 默认 1MB/10MB 容量无法承载一次多 TXT 提交，应用配置设为单文件 2MB/总请求 110MB，保留 Spring 环境变量覆盖。部署时还需核验 API 反向代理的上传上限，不能用独立 device-ingest 的配置代替。
- 新阶段等待回执时，即使拉手离线也保留可收敛状态；UNKNOWN/审批允许其他未发拉手继续入群。首次缺群 JID 不算成功，后续回执不能覆盖不同群身份。
- 按 expert-reviewer 复核创建全链路、租户、幂等、事务、完整执行调用方和收口归档。发现并修复自动启动并发返回、离线回执和待审批查询缓存问题。
- 最终聚焦验证共 344 项，336 项通过，8 项为已在 HEAD 复现的旧依赖夹具错误。详细清单见 [verification.md](verification.md)。没有宣称全库测试或真号业务验收通过。
