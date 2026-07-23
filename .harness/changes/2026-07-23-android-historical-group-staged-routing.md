# 变更记录：Android 历史群全链路分批协议路由

- 日期 / 分支 / worktree: 2026-07-23 / `1.0.1-snapshot` / 当前 Armada worktree
- 需求来源: 用户确认 Android 历史群需要全部打通，但分批实施；设计文档 `docs/superpowers/specs/2026-07-23-android-historical-group-staged-routing-design.md`
- 状态: 设计已确认，待编写实施计划

## 目标（一句话）

在保持 Web 历史群行为不变的前提下，按读取、成员管理、拉手和营销四批完成 Android 历史群协议路由，第一批只实现固定操作账号只读闭环及自动化测试。

## 缺口拆解 / 任务清单

- [x] 核对 baseline、实时群读取、成员操作、拉手和营销的 Web/Android 当前能力。
- [x] 确认历史群集合语义：交集是目前仍在的历史群，导入后新群不进入历史群页面。
- [x] 确认采用能力级 Routing Port，不建设 Android 历史群专用接口。
- [x] 确认分四批实施，第一批只做状态刷新、详情、成员列表和邀请链接。
- [x] 确认当前阶段不考虑发布、远程环境和真实账号验收。
- [x] 写入并自检跨仓设计文档。
- [ ] 用户审阅设计文档。
- [ ] 编写第一批实施计划。
- [ ] 按 TDD 完成 Android Go 第一批读取契约。
- [ ] 按 TDD 完成 Armada 第一批 Routing backend 和业务等价测试。
- [ ] 完成两仓代码、测试、构建、静态检查和差异检查。

## 关键设计决策

- `account_group_baseline` 继续作为历史群范围唯一事实。
- Android 当前已有 baseline 上报、群原子能力、join/contact 路由和 historical correlation，禁止沿用旧文档中的过期限制。
- Armada 历史群业务层不增加 Android 分支；Web/Android 只在协议 backend 不同。
- 第一批对 Android Zhuan 增加轻量群列表参数、通用 metadata summaries 和成员响应增量字段。
- metadata 与邀请链接独立失败；当前群列表整体失败时绝不把 baseline 群误判为退出。
- 无 backend 的 Web 批量查群能力不塞入固定账号混合协议 Routing Port。
- 否决数据库快照代替手动刷新，因为它不具备本次请求实时性且缺少角色、禁言和邀请链接。
- 否决历史群专用 Android 大接口，因为会把 Armada 业务概念下沉到协议服务。

## 验证（evidence-before-done）

- 设计阶段只完成静态代码、测试和历史变更对账，尚未修改生产代码或运行实施验证。
- 实施阶段按设计文档第 11 节记录真实命令与输出。

## 部署

- 不在当前任务范围；不执行部署、SSH、远程环境修改或真实 WhatsApp 操作。

## 遗留 / 跟进

- 第一批完成并验收后，再分别为成员管理、Android 拉手和 Android A 账号营销建立后续实施批次。
