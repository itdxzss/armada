# 变更记录：超链数据包与超链营销模板一期

- 日期 / 分支 / worktree：2026-08-27 / `1.0.3-snapshot` / 主工作区
- 需求来源：用户要求先复刻“超链数据包”和“超链营销模板”，并为后续超链任务、素材和分析留出稳定接口
- 状态：方案设计完成，尚未进入编码

## 目标（一句话）

在不提前实现超链任务和分析链路的前提下，交付两个可独立验收的前置资源菜单，并冻结号码快照、模板内容快照和图片稳定 ID 三条跨期契约。

## 缺口拆解 / 任务清单

- [x] 核对竞品数据包、模板和任务引用行为
- [x] 核对 Armada 现有群营销模板和图片文件能力
- [x] 明确数据包、模板与任务/素材/分析的依赖边界
- [x] 产出详细设计文档
- [ ] 同步目标分支并重新分配 Flyway 版本
- [ ] 实现后端数据模型、API 和测试
- [ ] 实现前端菜单、页面和测试
- [ ] 完成联调、端到端验收和自动数据模型更新

## 关键设计决策

- 新建 `com.armada.hyperlink` 业务域；超链模板不合并进生产中的群营销模板。
- 数据包号码保存当前池状态；任务执行历史未来保存在独立收件人/投递尝试表。
- 模板保存完整推广链接和按钮目标参数；任务选择模板后复制不可变内容快照。
- 图片一期复用 `marketing_template_file` 的 ID 和 Service，不直接改名为 `resource_asset`。
- 一期只开放单图文、普通按钮和卡片按钮；双图文保留枚举但明确拒绝。
- 不提前创建任务、点击、策略或分析的空表和假接口。

### 第二版评审修正（2026-08-27）

- `data_package` 补六个池状态计数列。原设计列表页 7 个指标只能对 `data_package_phone`
  全量 GROUP BY，包内号码可累积到数十万，翻一页最坏扫千万级索引条目。
- 覆盖导入改硬删旧号码，`data_package_phone` 去掉 `deleted_at` / `is_active`。
  原软删设计的死行无界增长且没有任何清理方。
- 单包总号码数设上限 500000（待产品确认），把覆盖导入的事务规模钉死。
- 删包与覆盖采用不同删除策略：删包是一次性操作，软删包主行 + 后台清理号码；
  覆盖是重复操作，必须硬删。
- 图片列名定为 `link_preview_asset_id` / `body_main_asset_id`，不用 `*_file_id`——
  值在一期仍指向 `marketing_template_file.id`，迁移时只换指向不换列名。
- `content` 按 `message_type` 逐条写清语义，替代原“正文/副标题/底部小字”一列三义的注释。
- 补 `POST /api/data-packages/{id}/recount`（仅管理员）与 `tenant:hyperlink_data:recount`。
- 定义 `GET /api/data-packages/countries` 返回结构。
- 统一 `version` 命名，删除 `templateVersion` 别名。
- 点明 `failedCount` 与 `unregisteredCount` 的重叠是对齐竞品的有意口径，不是缺陷。

详细设计：`docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md`。

## 验证（evidence-before-done）

- 已核对 `origin/1.0.3-snapshot`：远程分支已存在 `V140__group_canonical_first_classification.sql`，因此旧超链文档的 V140 起始编号失效。
- 已核对现有 `marketing_template_file` Controller、Service、Mapper 和群营销运行路径，确认直接表改名会影响滚动发布。
- 本阶段只新增设计与变更记录，没有执行编译、数据库测试或前端构建。

第二版评审复核（2026-08-27）：

- 竞品单次 TXT 上限 `1e5`：`hylbuiaxykfrontendsource/readable/assets/data-CdPwdTG4.js:863`。
- 竞品拦截国家常量 `马来西亚、新加坡、香港、中国、澳门、台湾`：同上 `:2276`；
  巴西为风险提醒（`:1313`）而非拦截，两者不可混为一谈。
- 竞品模板创建下拉只开放单图文/普通按钮/卡片按钮：`templates-BLWMxusB.js:165-171`
  （`:796-808` 的四项是筛选下拉，不是创建入口）。
- 竞品模板确实保存 `promotion_link` 与按钮目标 URL：`templates-BLWMxusB.js:399`，
  与页面“跳转链接在创建任务时配置”的提示冲突，以 payload 为准。
- Armada 侧落位属实：`ApiResponse` / `PageResult` 存在于 `shared/response`；
  `sys_menu.component_path` 格式为 `account/index/index`（`V071` 种子数据）；
  权限键格式为 `tenant:<module>:<action>`；
  `MarketingTemplateFileController` 为类级 `@PreAuthorize("hasAuthority('tenant:marketing_template:view')")`，
  会挡住只有超链权限的用户，必须改 `hasAnyAuthority`。
- `origin/1.0.3-snapshot` 的 `V140__group_canonical_first_classification.sql` 已复核存在。
- 已解决与 `docs/business/hyperlink-marketing-data-model.md` 的冲突：该文已加效力声明，
  一期数据包与模板表以本设计为准。

## 部署

- 尚未编码或部署。

## 遗留 / 跟进

- 编码前必须先同步后端和前端目标分支，避免基于落后工作区分配迁移号或覆盖远程文档。
- 实施过程中按详细设计第 16 节的依赖顺序拆分任务。
- 完成实现后补充真实 Maven、前端构建和端到端验收输出。
