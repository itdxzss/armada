# 变更记录：AI 全流程交付设计入库与独立复核

- 日期 / 分支 / worktree：2026-08-26 / `1.0.3-snapshot` / 主工作区
- 需求来源：用户要求将此前的 AI 全流程交付设计正式提交，并让另一台 AI 重点判断设计是否合理、是否符合最初预想，而不只是审核代码和进度
- 状态：`VERIFIED`

## 目标

把工作区根目录中未受 Git 管理的 AI 交付体系迁入 `armada` 仓库，并提供一个降低现有方案锚定效应的独立复核入口。

## 完成内容

- [x] 将完整设计、schema、样例和图源迁入 `docs/ai-delivery-system/`
- [x] 增加 `original-intent.md`，只描述用户想得到的工作方式、边界和成功标准，不预设当前实现
- [x] 增加 `independent-design-review.md`，要求评审者先从原始目标独立设计，再读取现方案、代码和进度
- [x] 允许评审者保留、简化、删除、替代或重新设计当前方案，不把已有投入当作方向正确的理由
- [x] 将当前六层验收进度记录纳入 Git，并补记本地提交门禁结果
- [x] 验证 JSON 文件、Markdown 相对链接和 Git diff 格式

## 关键决策

- 设计文档归 `armada/docs/ai-delivery-system/`，因为 Runner、部署与验收控制面当前由 `armada-deploy` 承载；其他三仓仍作为独立候选与 adapter 边界存在。
- 复核分为“原始目标独立设计、现方案比较、当前进度核验、建议方案”四阶段，避免另一台 AI 只做清单式合规审计。
- 普通业务与环境事实不为本次交接额外脱敏；凭据、Token、密码、Cookie 和 PEM 私钥仍不进入 Git。
- 当前本地 `staging-accept` 在途代码与设计分成逻辑独立的提交，方便另一台 AI 区分“现有实现事实”和“目标设计”。

## 影响

- 数据库：无。
- API：无。
- Redis/Kafka：无运行态写入。
- 部署：本次不部署。
- 回滚：回退对应文档提交不会改变业务运行；回退验收工具提交只影响尚未正式启动的 soak 与观测能力。

## 验证

- `git diff --check`
- 对 `docs/ai-delivery-system/` 中 JSON 文件执行标准 JSON 解析
- 校验 Markdown 本地相对链接均指向存在文件
- `staging-accept` 的 Go、Python、Node 和 shell 合同测试结果记录在同日六层验收 change 中
