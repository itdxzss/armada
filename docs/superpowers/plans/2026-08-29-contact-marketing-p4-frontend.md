# P4 通讯录营销前端 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans。步骤用 `- [ ]` 跟踪。

**Goal:** 把通讯录营销从「只能调接口」做成「界面上能用」：菜单、任务列表、新建/编辑抽屉、
账号数据抽屉、账号筛选弹窗、剧本任务空占位。

**Spec:** `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md` §8
**上游背景:** `docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md`

**Tech Stack:** Vue 3 + TS + Element Plus（`wheel-saas-pure-web`，pure-admin 范式）；
Java 17（`armada`，仅补两处缺口）

---

## 对设计 §8 的三处有意偏离（**先读这里**）

### 偏离 1：`previewImageFileId` 是后端缺口，必须先补

发送引擎 `ContactTaskMessageCommandFactory:55-75` 已经会读 `task.getPreviewImageFileId()`
取 `MarketingTemplateFile` 并按 `IMAGE` 发送，`ContactTaskDetailVO` 也暴露了这个字段，
控制器 javadoc 明写「预览图由调用方先上传后传 `previewImageFileId`」——
**但 `ContactTaskFormDTO` 没有这个组件，service 里 `setPreviewImageFileId` 零调用。**

即：图片链路只差最后一厘米，现在图文消息只能发纯文字。P4 Task 1 补掉。

### 偏离 2：账号筛选弹窗只画 10 个字段，不是竞品的 20 个

`ContactAccountFilterNormalizer` 白名单放行约 20 个键，但真正参与圈号 SQL 的
`AccountFilterCriteria` 只实现了 12 个。存了但不生效的有：

```
continent / online_status / platform / wid_type / error_code / error_desc
created_at_from|to / logged_in_from|to / retention_days_min|max
```

**画出不生效的控件比没有这个控件更糟**——用户以为筛了，实际没筛。因此只渲染生效项。
再去掉 `friend_count_min|max`（交接文档 §5.3 硬约束：双向好友恒为 0，前端不得渲染），
最终渲染 10 个：

```
country_iso2s / exclude_country_iso2s / group_ids / channel_ids
protocol_id / account_type / phone / register_days_min|max / group_invite_allowed
```

> 后端补齐 `AccountFilterCriteria` 后再逐个放开，**每放开一个都要同时改 SQL 和本弹窗**。

### 偏离 3：账号筛选弹窗是新建，不是复用

设计 §8 写「复用现有组件：账号筛选弹窗」。实测 `wheel-saas-pure-web` 里没有这个组件
（`grep -rl "accountFilter\|account_filter" src/` 无命中），超链**任务**页也不存在，
只有 `hyperlink/data` 与 `hyperlink/templates` 两页。本期新建，放在
`src/views/contact/hyperlink/components/` 下，暂不提前抽公共组件（YAGNI，
等超链任务页真要用时再抽）。

---

## Global Constraints

- **接口是 JSON 不是 multipart**：竞品用 multipart，我们的 `create`/`update` 收 `@RequestBody`。
  图片先 `POST /api/marketing-template-files` 拿 id，再把 `previewImageFileId` 放进 JSON。
- **发送间隔是带一位小数的秒**，落成整数会把「最快 0.1s」这档做没。
- **双状态**：`isEnabled`(0/1) + `runStatus`(0未开始/1进行中/2已完成/3已暂停/4已停止)。
  展示优先级：`isEnabled=0` 一律显示「已停用」，否则按 `runStatus`。
- **没有删除**：接口没有，行操作也不许有。
- **不复刻单价 badge**（armada 无计费体系）。**开放新建按钮**（竞品是灰度关闭）。
- **剧本任务逐字复刻空占位**，不编造功能。
- 权限节点逐字对齐 `V159`：`tenant:contact_task:view|create|edit|operate`。
- 组件路径逐字对齐 `V159` 的 `component_path`：`contact/hyperlink/index`、`contact/script/index`。

### 跑测试

```bash
cd /home/yanwenchao/ideaProject/wheel-saas-pure-web
npx vitest run src/api/contact-task.test.ts        # 组件/接口层
node --test src/router/contact-route.test.ts       # 路由契约（Node 内置 runner）
```

> 两套 runner 并存是仓库现状：`src/router/*.test.ts` 用 `node:test`，其余用 vitest。
> 新增测试按所在目录跟随既有 runner，不要统一。

---

## 视觉规格（**照竞品复刻**，出处 `hyperlink-BYpivFya.css`）

我们是 Element Plus，竞品是 NaiveUI —— **复刻的是配色、几何与层次，不是组件**。
竞品那套 `--me-*` 变量系统可以原样搬过来，它本身与组件库无关。

### 配色

| 用途 | 值 |
|---|---|
| WhatsApp 绿渐变（标题图标、序号徽章、状态图标） | `linear-gradient(135deg, #25d366, #128c7e)` |
| 主强调色 | `#18a058` |
| 错误态 | `#d03050` |
| 卡片底/边/分隔 | `--me-card-bg` `--me-card-border` `--me-card-divider` |
| 三级文字 | `--me-text-1: #000000d9` / `--me-text-2: #0000008c` / `--me-text-3: #00000059` |
| 填充 | `--me-fill-soft: #0000000a` / `--me-fill-embedded: #00000004` |
| 虚线 | `--me-dashed: #d9d9d9` |

暗色模式整套覆盖（`#ffffff0a` / `#ffffff1f` / `#ffffffeb` / `#fff9` / `#ffffff61` …）。

### 关键块

- **`.form-section`** 卡片：1px 边 + 12px 圆角 + `overflow:hidden`；
  hover 边框转 `#18a05873`、加 `0 2px 12px -6px #18a05840` 阴影
- **`.section-header`**：底色 `linear-gradient(#18a05814, #18a05800)`；
  左侧 26×26 渐变绿序号徽章（8px 圆角、13px/700）；右侧标题 14px/600 + 描述 12px
- **`.account-range`**：1.5px 绿虚线 + `#18a0580d` 底 + 12px 圆角；
  右侧胶囊计数（999px 圆角，数字 14px/700）；**命中 0 时整块转红**
  （底 `#d030500f`、边 `#d030508c`、计数 `#d0305029`）
- **`.interval-stats`**：`grid-template-columns: 1fr auto 1fr`，两张 `#18a0580f` 卡 + 中间 `~`；
  输入数字 **22px/700 绿色**，单位 13px/600
- **`.status-toggle`**：两张大卡二选一；选中绿态 `#18a0581a` + `#18a058` 边 + 绿阴影，
  选中灰态用 `--me-text-2` 边；左侧 40×40 渐变图标，右侧 22px 单选圆点
- **`.upload-area`**：2px 虚线、`min-height:168px`、图标 40px，hover 转绿
- **`.file-chip`**：绿实心卡；demo 态 `repeating-linear-gradient(135deg,…)` 斜条纹 + 虚线边
- **`.preview-pane`**：**360px 定宽 + `position:sticky; top:0`**，绿到透明渐变底
- **`.drawer-title-icon`**：36×36 渐变绿 + `0 4px 10px -4px #25d36699` 阴影

### 响应式

| 断点 | 行为 |
|---|---|
| `≤1100px` | 预览栏转 `static` 且宽度 100% |
| `≤720px` | `.status-toggle` 与 `.interval-stats` 塌成单列，`~` 分隔符隐藏 |

### 只读态

`.form-pane.is-readonly` 整块 `cursor:not-allowed`，且 `* { cursor: not-allowed !important }`。

---

## Task 0: 共享视觉 token

**Files:**
- Create: `src/views/contact/hyperlink/styles/tokens.css`

把上表的 `--me-*` 变量与暗色覆盖落成一个文件，各组件 `@import` 后在 scoped 样式里用。
**不要每个组件各写一份**，否则暗色模式迟早对不齐。

- [ ] 落盘并提交

---

## Task 1: 后端补 `previewImageFileId`（armada）

**Files:**
- Modify: `armada-api/src/main/java/com/armada/contact/task/model/dto/ContactTaskFormDTO.java`
- Modify: `armada-api/src/main/java/com/armada/contact/task/service/impl/ContactTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/controller/MarketingTemplateFileController.java`
- Test: `armada-api/src/test/java/com/armada/contact/task/ContactTaskServiceImplTest.java`

- [ ] Step 1: 在 `ContactTaskServiceImplTest` 加失败用例：`create` 带 `previewImageFileId` 时落库、
      `update` 传 null 时清空
- [ ] Step 2: 跑测试确认失败
- [ ] Step 3: `ContactTaskFormDTO` 追加 `Long previewImageFileId`（放在 `content` 之后，
      与 VO 字段顺序对齐），补 javadoc
- [ ] Step 4: service 的 create/update 里 `setPreviewImageFileId(form.previewImageFileId())`
- [ ] Step 5: `MarketingTemplateFileController` 两个 `@PreAuthorize` 各加
      `tenant:contact_task:create`/`edit`（上传）与 `tenant:contact_task:view`（读取）
- [ ] Step 6: 跑 `ContactTask*Test` 确认通过
- [ ] Step 7: 提交 `feat(contact): accept preview image on contact task form`

---

## Task 2: 前端 API 层

**Files:**
- Create: `src/api/contact-task.ts`
- Test: `src/api/contact-task.test.ts`

照 `src/api/hyperlink-template.ts` 逐字对齐：`armadaRequest` + 导出类型 + 每接口一个函数。
图片上传直接复用 `uploadHyperlinkTemplateImage` 的形状（`FormData` + 删 `Content-Type`）。

- [ ] Step 1: 写失败测试（6 个接口的 method/url/参数形状 + 上传删 header）
- [ ] Step 2: 跑测试确认失败
- [ ] Step 3: 实现
- [ ] Step 4: 跑测试确认通过
- [ ] Step 5: 提交

---

## Task 3: domain 纯逻辑（先做，最好测）

**Files:**
- Create: `src/views/contact/hyperlink/domain/interval-preset.ts` + `.test.ts`
- Create: `src/views/contact/hyperlink/domain/task-form.ts` + `.test.ts`
- Create: `src/views/contact/hyperlink/domain/task-status.ts` + `.test.ts`

`interval-preset.ts`：四档预设与「当前区间落在哪一档」的反查。
```
最快 0.1~0.1 / 平台推荐 0.5~1 / 稳健 1~3 / 防风控 3~5
```
滑杆 0.1~30，min 输入下限 0.1，max 输入上限 60，min 一位小数取整，max 裁剪为 >= min。

`task-form.ts`：默认值、校验规则、提交体组装。要点：
- `messageType=1` 时 `title`/`description`/`promotionLink` 恒为 `""`
- `startMode=now` 时 `taskDelayMinutes` 恒为 0
- `scheduled` + 延迟 0 **仅在启用时**拒绝，存草稿允许
- `accountFilterJson` 是**字符串**不是对象；条件为空提交 `"{}"`
- 条件非空时强制注入 `account_status:'normal'` 与 `is_exported:false`
- **不注入** `stranger_muted`（这是与超链任务的真实差异，不是笔误）

`task-status.ts`：`isEnabled` + `runStatus` 的展示口径与行操作分支。
```
isEnabled=0            → 已停用
runStatus 0 未开始 → 启动 + 编辑
runStatus 1 进行中 → 暂停 + 停止 + 查看
runStatus 3 已暂停 → 恢复 + 停止 + 查看
runStatus 2|4      → 仅查看
账号数据按钮任何状态都有
```

- [ ] 每个文件走一遍 RED → GREEN → 提交

---

## Task 4: 账号筛选弹窗

**Files:**
- Create: `src/views/contact/hyperlink/components/ContactAccountFilterDialog.vue` + `.test.ts`
- Create: `src/views/contact/hyperlink/domain/account-filter.ts` + `.test.ts`

只渲染偏离 2 列出的 10 个字段。`account-filter.ts` 负责表单值 ↔ 提交 JSON 的双向转换，
空值不进 JSON（否则后端归一化会把空串当条件）。

- [ ] RED → GREEN → 提交

---

## Task 5: 任务抽屉与预览

**Files:**
- Create: `components/ContactTaskDrawer.vue` + `.test.ts`
- Create: `components/ContactTaskPreview.vue` + `.test.ts`

四段式与竞品一致：`1 基础信息`（消息类型 + 任务名 + 账号范围）、`2 消息内容`、
`3 发送策略`、`4 发布`。左预览右表单，`readonly` 时整块 `inert`。
**编辑态消息类型不可改**（后端 `update` 就是这么校验的）。

- [ ] RED → GREEN → 提交

---

## Task 6: 列表页与账号数据抽屉

**Files:**
- Create: `components/ContactTaskSearchCard.vue` + `.test.ts`
- Create: `components/ContactTaskAccountDrawer.vue` + `.test.ts`
- Create: `composables/useContactTaskPage.ts` + `.test.ts`
- Create: `index.vue`

列表 7 列照设计 §2.10。账号数据抽屉三个数值列走服务端排序（`sortBy` + `sortOrder`）。

- [ ] RED → GREEN → 提交

---

## Task 7: 剧本任务空占位

**Files:**
- Create: `src/views/contact/script/index.vue`

竞品实现就 20 行：满高居中 + `Result status="info" title=$t('common.lookForward')`。
逐字复刻，不编造功能。

- [ ] 提交

---

## Task 8: 路由与菜单

**Files:**
- Modify: `mock/asyncRoutes.ts`
- Create: `src/router/contact-route.test.ts`

对齐 `hyperlink-route.test.ts` 的两个用例：组件路径能映射到真实模块、
开发预览菜单与后端 RBAC 对齐（四个权限节点逐字断言）。

- [ ] RED → GREEN → 提交

---

## 收尾

- [ ] 前端全量：`npx vitest run` + `node --test src/router/*.test.ts`
- [ ] armada 全量对基线 `Failures 7 / Errors 461`
- [ ] 回填交接文档 §0（前端不再是缺口）、§4（删掉 P4 未完成）、§7（新坑）
