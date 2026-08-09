# 变更记录：拉群任务快速只读诊断

- 日期 / 分支 / worktree: 2026-08-09 / `1.0.2-snapshot` / 主工作区
- 需求来源: 用户要求落地“固定输入约定 + 一键只读诊断 + 自动分流 + 两段式结论”的第一阶段。
- 状态: 已完成

## 目标（一句话）

为测试环境页面 `#任务号` 提供一个不改数据、不重试、不重启的诊断 CLI，优先输出简短层级判断和可追踪证据。

## 缺口拆解 / 任务清单

- [x] 锁定 `--env` / `--task-id` / `--execution-id` / 测试时间 / 现象的参数契约。
- [x] 复用 `armada-deploy/envs/*.conf` 和现有普通群链接异常摘要 SQL。
- [x] 按 `pull_task.task_type/mode` 自动分流，对普通链接拉群输出任务、执行行和异常候选摘要。
- [x] 阻止非法 ID、未明确环境、密码回显和敏感业务字段输出。
- [x] 补充脚本测试、运维手册和测试环境只读冒烟证据。

## 关键设计决策

- 诊断入口放在 `armada-deploy/tools/`，不增加业务 API 或数据库结构。
- 环境必须由 `--env test1|perf2` 显式指定；脚本不提供生产档案。
- 不复制异常状态机；运行时从 `docs/operations/pull-task-normal-link-diagnosis.sql` 提取参数块和结果 9。
- MySQL 密码只在测试机远端进程环境中使用，不作为命令参数或 CLI 输出。
- 第一阶段对 `STANDARD/NORMAL_LINK` 给出深度异常摘要；`GROUP_MARKETING` 先完成识别和任务级聚合分流，不误用普通拉群状态机。

## 验证（evidence-before-done）

- `bash -n armada-deploy/tools/pull-task-diagnose.sh`: 通过，退出码 0。
- `bash -n armada-deploy/tools/pull-task-diagnose.test.sh`: 通过，退出码 0。
- `bash armada-deploy/tools/pull-task-diagnose.test.sh`: `PASS pull-task-diagnose tests`，覆盖 `#ID` 解析、非法 ID 拦截、普通/营销分流、摘要输出、只读 SQL、敏感字段和 `.env` 非执行式解析。
- test1 连通性负向冒烟：`#123` 不存在时明确返回“找不到”，未执行修改。
- test1 真实任务只读冒烟：对最新 `STANDARD/NORMAL_LINK` 任务退出码 0，输出运行时、执行行摘要、异常类别和 `executionId/commandId`，未输出号码、链接、JID、payload 或密码。

## 部署

- 仅本地运维工具和文档，未部署或重启业务服务。

## 遗留 / 跟进

- 第二阶段再增加快照对比和新故障模式的持续沉淀。
