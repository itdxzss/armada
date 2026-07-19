# 变更记录：Armada 多环境统一部署

- 日期 / 分支 / worktree：2026-07-19 / 1.0.1-snapshot / 主工作区
- 需求来源：用户要求为第二套环境补齐覆盖后端、前端、Baileys 和 Android Zhuan 的部署能力
- 设计文档：docs/superpowers/specs/2026-07-19-armada-multi-environment-deploy-design.md
- 状态：设计完成，待用户审阅书面版本

## 目标（一句话）

把第一套与第二套部署收敛为统一入口、非敏感环境档案和组件模块，同时保持第一套命令兼容。

## 缺口拆解 / 任务清单

- [x] 对账现有 deploy-test.sh、测试和历史设计。
- [x] 只读核验第二套 Armada 与 Zhuan 当前运行状态。
- [x] 确认第二套覆盖后端、前端、Baileys 和 Zhuan。
- [x] 确认基础设施大部分已准备，新增项由单独操作处理。
- [x] 确认采用统一入口 + profile + 组件模块。
- [x] 确认日常快速检查与显式 --check 分层。
- [x] 完成部署设计。
- [ ] 用户审阅书面设计。
- [ ] 编写实施计划。
- [ ] TDD 实现 profile、模块化、第二套部署和验证。
- [ ] 打通第二套 Baileys 私网 SSH 路由。
- [ ] 第二套 dry-run、分组件和 full 验收。

## 关键设计决策

- 不复制 deploy-perf.sh，避免两套脚本漂移。
- 不继续向 865 行主脚本堆叠全部环境分支，按 common、armada、protocol、zhuan 拆分。
- profile 只保存主机、路径、启动方式和隔离规则；凭据与真实连接串保留在远端。
- 不指定 --env 时默认 test1，保留现有 scope 和覆盖变量。
- 默认按 scope 做快速检查；--check 才执行 Kafka、RDS、Redis 和跨组件深度检查。
- --full 顺序为 Baileys、Zhuan、backend、frontend；协议失败后不继续更新 Armada。
- 不做跨四组件自动全局回滚，失败后按组件重试。
- 第二套 Baileys 采用 Armada 跳板访问私网协议机；当前 TCP 22 超时，实施时按确认流程补安全组。

## 验证（evidence-before-done）

- 已读取 armada、前端和 Zhuan 项目规则及部署脚本。
- 已运行当前部署脚本 --help 与第一套 --dry-run。
- 2026-07-19 只读远端核验：第二套 Armada backend/nginx running；第二套 Zhuan app/callback healthy；未修改远端状态。
- 尚未运行代码测试；当前阶段仅新增设计与变更记录。

## 部署

- commit / 环境 / 部署后验证结果：未部署；未修改任何远端配置、容器、数据库、Kafka、Redis 或安全组。

## 遗留 / 跟进

- 第二套 Armada 远端 Compose 尚未透传 Android URL，需随实现同步并重建 backend。
- 第二套 Baileys 私网 SSH 需要打通。
- 实施和远程验证前再次确认第二套环境、四仓 commit 和部署 scope。
