# 变更记录：WhatsApp 群封禁状态持久化

- 日期 / 分支 / worktree: 2026-08-07 / 1.0.2-snapshot / armada 主 worktree
- 需求来源: 用户确认 Web 与 Android 协议收到 `suspended/terminated` 后都应更新 Armada 数据库
- 状态: 进行中

## 目标（一句话）

把 Web/Android 明确收到的群暂停或终止信号统一持久化为群封禁，并阻止可见群快照误清封禁。

## 缺口拆解 / 任务清单

- [x] 核对第一套测试环境协议日志和数据库现状
- [x] 确认复用 `group.health_reported` 契约
- [ ] Web 发布 `suspended/terminated` 健康事件
- [ ] Android 发布 WGP2/HistorySync 群健康事件
- [ ] 后端支持按租户和群 JID 定位健康行
- [ ] 账号群同步保留既有封禁事实
- [ ] 三端定向测试通过

## 关键设计决策

- 复用 `group.health_reported`，不新增表、topic 或并行事件契约。
- 只依据协议明确的 `suspended/terminated`，不把普通 403 推断为封禁。
- 封禁是粘性事实；群仍可见只更新人数和观测时间，明确健康事件才可恢复。
- 实时事件允许缺少 `groupLinkId`，后端在租户上下文内按 `groupJid` 定位。

## 验证（evidence-before-done）

待实现后补充三端测试命令与真实输出。

## 部署

- commit / 环境 / 部署后验证结果: 未部署；目标为第一套测试环境，部署前另行确认。

## 遗留 / 跟进

- 无。
