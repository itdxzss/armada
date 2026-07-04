# 营销模板新增图文内容模式 — 规格

- 日期:2026-07-04
- 项目:armada
- 模块:`armada-api` / `wheel-saas-pure-web`
- 范围:营销任务新增抽屉、营销模板新增/编辑抽屉、营销任务与营销模板数据模型

## 1. 当前需求口径

1. 「营销任务 > 新增营销任务」恢复为只选择「营销模板」作为发送内容。
2. 营销模板必填,不再提供任务级「文本内容」输入框。
3. 「营销模板 > 新增营销模板」的「消息类型」下拉新增「图文内容」。
4. 图文内容模式保存为 `marketing_template.link_mode = 3`。
5. 只有 `2=按钮超链` 可以配置消息按钮;`1=普通超链` 和 `3=图文内容` 均不可配置按钮。

## 2. 数据库

`V036__marketing_task_text_content.sql` 已在测试环境执行过,不能删除历史迁移文件。本次用新的前滚迁移落最终结构:

- 新增 `V037__marketing_template_only_and_image_text_mode.sql`;
- 删除 `marketing_task.send_content_type`;
- 删除 `marketing_task.text_content`;
- 恢复 `marketing_task.marketing_template_id` 为 `NOT NULL`;
- 恢复 `marketing_task.marketing_template_name` 为 `NOT NULL`;
- 更新 `marketing_template.link_mode` 注释为 `消息类型:1=普通超链 2=按钮超链 3=图文内容`。

## 3. 后端接口与校验

`POST /api/marketing-tasks`:

- `marketingTemplateId` 必填;
- 未传模板时报错「请选择营销模板」;
- 请求/响应模型不再包含 `sendContentType`、`textContent`。

`POST /api/marketing-templates` / `PUT /api/marketing-templates/{id}`:

- `linkMode` 允许值为 `1`、`2`、`3`;
- `linkMode=2` 时必须有 1 到 3 个按钮;
- `linkMode=1` 或 `linkMode=3` 时按钮必须为空。

## 4. 前端页面

营销任务新增抽屉:

- 保留「营销模板」字段并作为必填;
- 移除「文本内容」输入框与二选一禁用提示;
- 保存时只提交模板 ID 与模板名称。

营销模板新增/编辑抽屉:

- 「消息类型」下拉包含「普通超链」「按钮超链」「图文内容」;
- 选择「图文内容」时不展示按钮编辑器;
- 列表和预览展示「图文内容」标签。
