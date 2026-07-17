---
name: request-analysis
description: Use when analyzing a new Armada backend feature, reconciling requirements with current code, or producing an impact analysis before implementation.
---

# 需求分析技能

把用户要求与 Armada 当前事实对账，形成可执行的缺口清单与设计。

## 事实源顺序

1. 用户本次明确要求与已确认口径。
2. `docs/business/requirements/一期需求.xlsx`、相关已确认设计文档。
3. `.harness/wiki/`、`docs/business/`、当前代码与测试。
4. `.harness/changes/` 历史记录只作背景，不得覆盖当前事实。

## 步骤

1. 先确认需求涉及后端、同级前端仓库还是协议层仓库，避免在错误项目实现。
2. 逐条对账“需求文字 × 原型/业务文档 × 当前代码与测试”，记录证据路径。
3. 区分真缺口、现有实现口径偏差、仅文档过期和跨仓依赖。
4. 列出 API、数据模型、租户隔离、状态流转、部署与回滚影响。
5. 对会改变需求口径、数据归属或跨仓契约的关键歧义先向用户确认，不擅自决定。
6. 多步骤或跨会话任务使用 `.harness/changes/_TEMPLATE.md` 建 change 记录。

## 产出

- 设计方案：`docs/superpowers/specs/<日期>-<主题>-design.md` 或对应 `docs/business/` 文档。
- 持久化进度：`.harness/changes/<日期>-<主题>.md` 或同主题目录。
- 结论必须注明事实、推断、未确认项和验证方式。
