# 养群管理测试环境用例与执行方案

日期：2026-09-11。共 **87 条用例**。状态：**已执行 test1 首轮回归，存在失败及未覆盖项**。见 [首轮实测报告](2026-09-11-group-maintenance-test-report.md) 和 [87 条执行状态](../testing/evidence/group-maintenance/GM-20260911-080650/results.json)。

需求依据：[养群管理三菜单实施契约](2026-09-10-group-maintenance-requirements.md)。本文按当前工作区的 Controller、DTO、执行服务和前端调用核对接口；工作区含在途修改，不能据此认定测试环境已运行同一版本。历史 [test1 验收记录](../../.harness/changes/script-marketing-refinement/acceptance-20260910.md) 仅作为回归线索，不能填成本轮通过结果。

## 1. 怎么测更快

**用真实业务 API 批量执行主流程和规则回归，页面只验交互，专用 WhatsApp 群验证实际发送与接收，并逐级扩大真实发送量。** 用户已明确允许大量真实发送；执行仍需确定具体测试环境、账号池和目标群。不需要每个用例都从页面重新建素材、建剧本、选群。

| 方式 | 主要验证内容 | 为什么保留这一层 |
|---|---|---|
| 接口回归 | 素材/剧本/草稿、参数、权限、完整资格报告、启动拦截、任务操作、分页、快照 | 数据可复用，断言可自动运行，减少重复点击和等待页面加载 |
| API 驱动真实发送 | 逐群绑定、顺序、间隔、暂停恢复、独立进群后重查、成功与失败统计 | 走真实后端、MySQL、outbox 和协议服务，检验联调 |
| 少量页面验收 | 三菜单、按钮权限、跨搜索保留勾选、报告分页、保存后跳转、选用副本、迟到响应 | API 无法证明这些前端行为正确 |
| 批量真实发送 | 10/50/100 群的完整剧本、命令唯一性、结果收敛、查询性能和实际接收 | 用户已允许大量真实发送；群数、账号数和消息数分别登记，见 LOAD 用例 |
| 隔离故障测试 | 未投递挂起、入队失败、超时、重复/迟到回执、服务重启与竞态 | 普通业务 API 无法稳定控制这些时机，需测试环境专用故障控制或协议替身 |

接口能省下操作时间，**不能省掉产品配置的间隔、真实回执等待和群资料同步时间**。短回归用 3 秒间隔；暂停/故障用例用 20–30 秒留出操作窗口；86400 秒只验配置边界，不实际等待一天。不要把间隔全部改成 0 后声称验证过节奏。

建议执行顺序：准备环境与数据 → 无发送接口回归 → 文本双群冒烟 → 媒体和可控异常 → 页面验收 → 隔离故障与兼容回归。普通接口回归不逐例查库；关键零提交、绑定不变、超时和重复发送用例再补数据库/outbox/协议证据。

### 1.1 本轮范围与边界

- 规划目标沿用设计中的 **test1**。实际开跑时记录确认的环境名、入口、租户、执行用户、后端/前端/协议版本和迁移状态；编制阶段没有连接远程；后续首轮实测事实见独立报告，本文表格仍为测试预期。
- 只使用专用测试账号、测试群和可接收测试消息的成员设备；消息带唯一 `runId`。同一账号/群不同时运行营销回归、进群补齐和其他有冲突的任务。
- 准备数据、进群和修改群发言设置走现有业务接口。数据库仅做只读核对，不直接更新群成员、任务状态、绑定或发送结果制造通过。
- 故障控制必须限制到本轮任务或独立实例；无法隔离的停消费者、断网络、重启服务另安排独占测试窗口。没有条件的用例记 `BLOCKED`，不能借用本地单测通过代替。
- 不在本套件验每日重复调度、自动进群/补人、自动换号、引用回复、可配置跳过策略、语言/分类扩展。设计明确未纳入这些行为。
- 历史 test1 记录主要覆盖双群文本和部分异常；图片、按钮、Android/混合协议以及成员接收需要独立补验。历史 ID 只可只读复查，不能当本轮可用数据直接复用。

### 1.2 第一轮先跑哪些

以下顺序可用同一套账号和两个群，任务分别创建，避免把故障状态带进正常用例。

| 次序 | 动作 | 用例 | 关键结果 |
|---|---|---|---|
| 1 | 创建文本素材、定义，再保存缺人的双群草稿 | MAT-01、DEF-01、CFG-01 | 三层内容完整，草稿不发送 |
| 2 | 一个群合格、另一个群缺 1 人，检查并尝试启动 | QUAL-03、QUAL-11 | 完整报告；所有群均零提交、未固定绑定 |
| 3 | 独立进群任务给缺人群补齐；重新检查 | QUAL-12 | 群事实满足后通过；仍是草稿 |
| 4 | 显式启动新的双群四步文本任务 | EXEC-01、EXEC-02 | 2 群各 4 条，按群固定角色；首条不等配置等待 |
| 5 | 从记录计算后项间隔并对照原绑定 | EXEC-03、QUAL-13 | 按上一条提交时间计时，同角色账号不变 |
| 6 | 另建长间隔任务，暂停、等待、恢复 | CTRL-01、CTRL-02 | 暂停不新增后项；恢复丢弃剩余等待、保留原命令 |
| 7 | 另建双群任务，运行后暂停其中一个群再恢复 | CTRL-05 | 另一群继续；恢复群继续原进度 |
| 8 | 修改素材和定义，再读已保存任务 | MAT-07、DEF-04 | 已有任务内容不被追改 |
| 9 | 对正常任务收集协议结果和成员设备接收证据 | EXEC-01、EXEC-08 | 分别给出协议发送与指定成员接收结论 |
| 10 | 在页面走一次完整三菜单流程及缺口跳转 | UI-01～UI-05 | 页面关键交互通过 |

这是快速冒烟，不等于完整验收。剩余 P0 用例，尤其权限、截止、原命令和重复发送控制，仍须跑完。

## 2. 测试数据与环境登记

每轮使用形如 `GM-20260911-01` 的唯一 `runId`，素材、剧本、任务和消息文本均带此前缀。以下均为逻辑别名，实际资源 ID 由本轮业务接口返回后登记，不硬编码历史任务 ID。

| 别名 | 准备要求 | 用途 |
|---|---|---|
| U1 | 租户 T1 的测试用户，具备 view/create/edit/operate | 主执行用户 |
| U2 / U3 | U2 与 U1 同租户不同用户；U3 属于另一测试租户 T2 | 本人边界和租户隔离 |
| U-view / U-create / U-edit / U-operate | 仅 view；view+create；view+edit；view+operate | 分别验证按钮与后端授权；资源删除另配资源库删除权限 |
| A | 可在两个正常群发言的管理员账号 | ADMIN 角色手动绑定；业务角色名不能代替真实群内资格 |
| POOL-2 | 分组内至少 3 个账号，其中至少 2 个在正常目标群内且在线可发言 | 2 个推手角色的正常池；管理员不能占推手候选名额 |
| P-equal / P-small | 分组总数分别为 2、1；需要 2 个推手角色 | 严格大于人数门槛的边界；池构造与其他用例隔离 |
| P5 / P6 | 分组总数为 5、6；剧本配置 5 个不同推手角色 | 需求中 5 人/6 账号门槛，优先只做检查，不做大批发送 |
| G-A / G-B | 两个专用群；A 和至少两个合格推手真实在群 | 正常双群、独立推进、暂停恢复 |
| G-short | 至少一个分组账号已在群，但只有 1 个合格推手 | 可被群候选检索，仍缺 1 人；补齐后可作为 G-B |
| G-off / G-deny / G-unknown | 分别有离线或受限成员、无发言资格成员、关系或发言事实未确认的成员 | 检查原因、运行前/运行后资格变化；可分时复用故障群 |
| G-many | 同租户可选择的至少 21 个真实群条目 | 群搜索与缺口报告翻页，保存/检查即可，无需启动 |
| M-text / M-image / M-image-text / M-button | 带 runId 的文本、JPEG 纯图片、图文、按钮素材 | 验消息形态、复用与接收 |
| D4 | 四步：ADMIN(admin) → PROMOTER(p1) → PROMOTER(p2) → ADMIN(admin) | 2 个不同推手；管理员重复发言不增加角色人数 |
| Receiver-A / Receiver-B | 分别留在 G-A/G-B 的指定接收设备，尽量独立于发送账号 | 验收消息显示、顺序和按钮行为；只证明这些成员的接收 |
| LEGACY | 环境中真实存在的 `account_group_id=NULL` 旧任务/旧草稿 | 旧任务兼容；不存在则记 BLOCKED，不造库数据 |

Web、Android、混合协议分别登记账号的实际 `protocol_id` 和所用任务 ID。只跑了 Web，报告就只写 Web 通过。Android 多按钮或非链接按钮用来验证能力拦截；支持的单链接按钮另跑真实正向接收。

准备结束时记录：账号可用状态、群成员/发言事实及查询时间、推手分组实际总数、角色数、接收设备、素材 ID。资格检查已能发现成员缺口时，优先复用现有测试群，再通过独立进群流程补齐，避免反复建群。

### 2.1 需要多少个 WhatsApp 账号

**推荐先准备 8 个号：1 个管理员 + 6 个推手 + 1 个独立接收号。** 管理员与推手分组分开准备，可完整测试设计中的 5 推手角色规则。6 个推手都进入正常测试群，既满足分组总数大于 5，也可验证原绑定失效时即使还有候选也不能自动换人。独立接收号不参与发送。

| 覆盖目标 | 管理员 | 推手池 | 独立接收 | 建议合计 |
|---|---:|---:|---:|---:|
| 只跑 2 推手角色的快速冒烟 | 1 | 3 | 1 | 5 |
| 5 推手角色、正常/缺口/暂停/恢复/多群批量真实发送 | 1 | 6 | 1 | **8** |
| Web 与 Android 各一套完整 5 推手角色回归，再加混合协议 | 2（各协议 1） | 12（各协议 6） | 1（共用） | **15** |

这里是建议准备数，按管理员与推手池分开配置计算。群数量不会直接乘到账号数量：同一管理员、6 个推手和接收号可加入 10/50/100 个测试群，分别复核真实在群和发言资格。需要两套同时执行且不相互影响的故障测试池时，再为第二套准备独立发送账号。

8 个号方案可以大批发送，但若账号全为 Web，只能给 Web 结论；若采用混合池，也不能替代 Web、Android 各自 5 推手角色的完整覆盖。后台 U1/U2/U3 等是登录用户，不计入上述 WhatsApp 账号数。Receiver-A/Receiver-B 可是同一个独立接收号在不同群里的观察记录。

## 3. 已核对的接口与断言口径

### 3.1 接口清单

所有路径相对于测试环境站点根地址，已包含 `/api`。使用正式租户登录会话的 Bearer 鉴权，不把 token 写进文档、导出的用例集或 Git。

| 对象 | 方法与路径 | 请求/取值要点 |
|---|---|---|
| 素材列表 | `GET /api/script-materials` | `keyword,linkMode,page,pageSize`；按 ID 取素材可使用 `id` 查询 |
| 新建/编辑素材 | `POST /api/script-materials`；`PUT /api/script-materials/{id}` | 完整 `MarketingTemplateDTO` |
| 复制素材 | `POST /api/script-materials/{id}/clone` | 返回新的素材 ID |
| 图片列表/上传/读取 | `GET /api/resource-assets`；`POST /api/resource-assets`；`GET /api/resource-assets/{id}/content` | 上传为 multipart，文件字段 `file`，当前入口校验 JPEG；业务消息引用返回的图片 ID |
| 删除无引用图片 | `DELETE /api/resource-assets/{id}` | 使用专用图片和有资源库删除权限的用户验证；被引用时应拦截 |
| 剧本列表/详情 | `GET /api/script-definitions`；`GET /api/script-definitions/{id}` | 列表 `keyword,status,page,pageSize`；`status=1/0` 分别筛启用/停用 |
| 新建/编辑剧本 | `POST /api/script-definitions`；`PUT /api/script-definitions/{id}` | `{name,enabled,steps}`，定义内全部 `accountId=null` |
| 剧本启停/复制/删除 | 启停用完整 `PUT`；复制读取后 `POST`；软删 `DELETE /api/script-definitions/{id}` | 没有独立 enable/disable/clone 路由；启停不要漏传 name、steps |
| 分组候选 | `GET /api/script-marketing-tasks/options/account-groups` | 返回数组，ID 用于 `accountGroupId` |
| 管理员候选 | `GET /api/script-marketing-tasks/options/accounts` | `keyword,page,pageSize`；候选可见不等于具备目标群发言资格 |
| 目标群候选 | `GET /api/script-marketing-tasks/options/groups` | 必传 `accountGroupId`，支持 `keyword,page,pageSize`；使用条目 ID 作为 `groupLinkId` |
| 任务列表 | `GET /api/script-marketing-tasks` | `keyword,status,page,pageSize` |
| 保存/编辑草稿 | `POST /api/script-marketing-tasks`；`PUT /api/script-marketing-tasks/{id}` | `taskName,intervalSeconds,startAt,endAt,groupLinkIds,steps,accountGroupId` |
| 表单检查 | `POST /api/script-marketing-tasks/check` | 与保存草稿相同的完整请求体；检查不保存、不启动 |
| 已保存任务检查 | `GET /api/script-marketing-tasks/{id}/check` | 草稿验候选，已启动任务验原绑定 |
| 任务详情 | `GET /api/script-marketing-tasks/{id}` | `data.task`、`data.steps`、`data.groups` |
| 发送记录 | `GET /api/script-marketing-tasks/{id}/records` | `page,pageSize`；遍历全部页，不能只看第一页 |
| 整任务操作 | `POST /api/script-marketing-tasks/{id}/start`、`/pause`、`/resume`、`/close` | 无请求体；动作返回成功后还需查询实际状态 |
| 单群操作 | `POST /api/script-marketing-tasks/{id}/groups/{groupId}/pause`、`/resume` | `groupId` 为任务详情 `groups[].id`，不是 `groupLinkId`，也不是群 JID |

素材没有本次新增的删除接口，也没有 `GET /api/script-materials/{id}`。任务保存不接受 `definitionId` 或 `materialId` 来联动读取：选用时复制完整 `steps/message`。定义删除或停用后，已经保存的任务副本仍独立存在。

### 3.2 响应、时间与状态

| 字段 | 判定方式 |
|---|---|
| 通用响应 | `{code,message,data}`；`code=0` 才表示业务调用成功，不能只断言 HTTP 200 |
| 参数错误 | 已核对通用业务码 `40001`；未知资源、权限错误同时核对不泄露数据及无副作用，不把所有错误硬写成同一码 |
| 资格检查 | 检查成功可返回 `code=0,data.ready=false`，说明检查完成但条件不满足 |
| 启动资格拦截 | `code=40901`，`data` 为完整资格报告；可能仍是 HTTP 200，不能只依赖 HTTP 层失败判断 |
| 资格报告 | `ready,accountCount,requiredPromoters,poolReason,checkedAt,groups[]`；群行含 `groupLinkId,groupJid,groupName,ready,required,available,shortage,offline,noPermission,unconfirmed,reasons` |
| 最终是否可启动 | 同时检查总报告 `ready`、`poolReason` 和各群 `ready/reasons`。当前人数列反映基础在群可发言人数；`shortage=0` 仍可能因管理员、角色能力或原绑定无效而不通过 |
| 分页 | `data.list,page,pageSize,total,totalPages`；页码从 1 开始，默认 10 条，上限 1000；报告的 `groups` 一次返回全部目标，页面再每页显示 20 条 |
| 时间 | `startAt,endAt,checkedAt,nextAt,submittedAt,finishedAt` 为 Unix 毫秒；等待参数为秒，计算时乘 1000 |
| 任务状态 | `0 DRAFT` 草稿；`1 RUNNING` 运行/等待开始；`2 PAUSED` 暂停；`3 FINISHED` 处理完成且结果已收敛；`4 CLOSED` 主动关闭/截止 |
| 记录状态 | `1 SENDING` 等结果；`2 SUCCESS` 协议成功；`3 FAILED` 明确失败；`4 UNKNOWN` 未知；`5 HELD` 明确未投递且已挂起 |
| 群进度 | `nextStep` 从 0 起，是下一未处理步骤索引；四步处理完为 4。处理完不等于四步全部成功 |
| 角色绑定 | `groups[].bindingsJson` 是 JSON 字符串，解码后比较 roleKey→accountId，不按键的输出顺序比较 |
| 计数 | `successCount/failedCount/unknownCount/inFlightCount` 从记录聚合；`inFlightCount` 当前只算 SENDING，HELD 需自行从明细统计 |

完整记录对账：`records.total = SUCCESS + FAILED + UNKNOWN + SENDING + HELD`。入队前失败也可能生成失败记录，因此 `records.total` 和记录中的 `submittedAt` **不能单独证明实际向协议提交了多少次**；需结合 outbox 和协议命令。首次整单拦截应更严格：记录、outbox、协议提交均为零。

## 4. 用例表

优先级：P0 为发布前必须明确结论的核心规则，P1 为完整回归。方式：**API** 为真实业务接口，**LIVE** 为 API 驱动真实协议，**FAULT** 为隔离故障控制，**UI** 为真实页面。API 类型默认不发送；行内明确要求启动时须使用专用测试群。

每行执行时记录 `NOT_RUN / PASS / FAIL / BLOCKED / PARTIAL`、实际结果和证据路径。表内是预期，**编制时初始状态均为 NOT_RUN；实测状态以 results.json 为准，PARTIAL 表示仅完成部分变体**。同一行的多个边界或协议变体应分别留结果，不能只测其中一个就整行 PASS。

### 4.1 剧本素材库

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| MAT-01 / P0 / API | U1 创建带 runId 的文字素材，`linkMode=1`、非空 content；按名称查回 | ID 存在，内容完整，列表数量正确；仅建素材不创建任务/发送 |
| MAT-02 / P0 / API+LIVE | 上传专用 JPEG，创建 `linkMode=3,imageFileId=图片ID,content=""` 的纯图片；选入合格任务发送 | 空文字允许；图片可读，实际发出的图片正确，无强加的正文 |
| MAT-03 / P0 / API+LIVE | 同一图片创建图文素材，填写 content/bodyText；选入任务发送 | 图与文均保存/显示正确，定义和任务复用图片 ID |
| MAT-04 / P0 / API+LIVE | 建 1～3 个按钮的 `linkMode=2` 素材；对可支持协议分别使用 LINK_JUMP、COPY_CONTENT、QUICK_REPLY | 合法内容保存；协议能力另按 QUAL-09；指定接收设备验证按钮展示及对应行为，不能只看 messageId |
| MAT-05 / P1 / API | 分别提交空文字且无图、未知 linkMode、非按钮消息带按钮、按钮数 0/4、非法链接；对任务内嵌消息也做同类变体 | 非零业务错误、无部分保存；边界未拦截按设计缺陷记录，不因为实现接受而修改预期 |
| MAT-06 / P1 / API | 查询 keyword/linkMode，翻页；复制素材后编辑副本 | 查询条件和 total 正确；副本 ID 不同，编辑不改变原素材；纯图片与图文同属 linkMode=3 |
| MAT-07 / P0 / API | 素材 M 选入定义 D，D 选入任务 T；把 M 文本改成 v2，再读 D/T | D/T 保留 v1；此后新选用 M 才得到 v2，图片引用仍按保存快照保留 |
| MAT-08 / P0 / API | 专用图片仅被定义引用时尝试资源库删除；再建任务引用该图并软删定义，再次删除图片 | 两次均因有效引用被拦截；图片仍可读取，任务不被终止。使用有删除权限的用户，避免把权限拒绝误当引用保护 |

### 4.2 养群剧本定义

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| DEF-01 / P0 / API | 创建 D4，全部 accountId=null，ADMIN/PROMOTER 角色齐全，逐项等待有效 | 保存完整顺序和消息；不绑定实际账号、不创建执行群/消息 |
| DEF-02 / P1 / API | 查详情，编辑名称/内容/顺序/等待；完整 PUT 停用，再启用；按 keyword/status 翻页 | 数据和筛选正确；启停不丢步骤；状态分别为 enabled=false/true |
| DEF-03 / P1 / API | GET 定义，改名后 POST 新定义；修改副本 | 新 ID，内容初始相同；原定义不受影响 |
| DEF-04 / P0 / API+LIVE | D-v1 复制为 T 并保存；D 改为 v2/停用/软删，再读并启动 T | T 仍按 v1 执行，定义变化不追改或终止 T；三种变体分别留证据 |
| DEF-05 / P1 / API+UI | 停用 D，检查启用列表与选用器；已展开候选后由另一会话停用，再点击旧候选 | 启用列表不返回 D；选用前详情复核拒绝停用项；不要求禁止用户直接编排相同内容 |
| DEF-06 / P0 / API | 定义任一步填具体 accountId，包括 ADMIN；再次提交 | 拒绝，提示在任务创建时选择管理员；定义不保存账号绑定 |
| DEF-07 / P0 / API | 同一 roleKey 重复多次；再构造同名混用 ADMIN/PROMOTER、缺 ADMIN 或缺 PROMOTER | 合法重复角色通过；非法身份组合或缺角色被拒绝，不能把同一角色多条消息计为多人 |
| DEF-08 / P1 / API | 名称空/100/101 字；steps 为 1/2/100/101；roleKey 为空/前后空格/32/33 字；message=null | 合法边界成功，非法边界拒绝；100 步仅保存，无需真实发送 |

### 4.3 任务草稿与群选择

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| CFG-01 / P0 / API | 用 G-A+G-short 保存 D4 草稿，资源未齐；重复查询详情/记录 | 保存成功 status=0，群完整、steps 完整、bindingsJson 为空、records.total=0；无发送 |
| CFG-02 / P0 / API | 直接编排和复制定义两种方式各建草稿；后者补 ADMIN accountId，推手保持 null | 均保存完整内容副本；新任务 accountGroupId 必填，不能依赖 definitionId 自动取配置 |
| CFG-03 / P0 / API | 同一 roleKey 的 ADMIN 配不同账号；PROMOTER 手填账号；ADMIN 不填；新任务不填分组 | 分别拒绝。普通草稿不要求在线/已齐员，但必填、消息合法性和可访问资源仍要校验 |
| CFG-04 / P1 / API | 分别传空群、1/100/101 个群、同 ID 重复、不同 ID 但同 JID、无效群 JID | 1～100 个有效不同 JID 可保存；空/超量/重复/无效拒绝，失败不残留半个草稿 |
| CFG-05 / P0 / API | 使用分组 A/B 查询群；名称搜索、pageSize=1 翻页；同群多个账号在群 | 只返回所选分组账号真实在群候选，结果按群去重、total 正确；仅有邀请链接不等于可候选 |
| CFG-06 / P1 / API | 逐项等待测 0/0、3/3、2/5、86400/86400；再测负数、max<min、>86400、缺值 | 有效区间可保存；非法拒绝。`intervalSeconds` 单独测 1/86400 接受、0/86401 拒绝；默认间隔与逐项允许 0 的规则不同 |
| CFG-07 / P1 / API | startAt 不传/未来；endAt 不传/晚于当前且晚于开始/等于开始/早于当前 | 合法组合保存；非法截止拒绝；时间单位毫秒，未来开始任务启动后先等待 |
| CFG-08 / P0 / API | 编辑草稿内容、顺序、目标群；启动后再 PUT 修改；运行中和暂停中各一次 | 草稿编辑生效；启动后的配置不可改，失败后 steps、群和绑定不变 |

### 4.4 资格、角色与启动拦截

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| QUAL-01 / P0 / API | 2 角色配分组总数 1/2/3；5 角色配总数 5/6；群内候选另保证足够 | 总数必须严格大于不同推手角色数；等于也失败。总数足够不能覆盖群内缺人或能力不足 |
| QUAL-02 / P0 / API | p1 同角色发 3 条，再加 p2 发 1 条，检查 | requiredPromoters=2，不是 4；人数按不同账号计算，不使用群总成员数 |
| QUAL-03 / P0 / API | 2 角色，G-A 合格，G-short 仅 1 人，表单检查与已保存检查各一次 | 所有选中群都出报告；G-short required=2、available=1、shortage=1；G-A 保留，不被悄悄移除 |
| QUAL-04 / P0 / API | 大分组但目标群只有 1 个成员；另准备无成员/资料未确认变体 | 不能用分组总数替代真实群内可用数；缺人/未确认报告能定位群名和 JID |
| QUAL-05 / P0 / API | 某推手已在群但离线或受限，检查；恢复后等真实账号状态更新再检查 | 不计作合格候选，显示原因；恢复后按最新事实重算，checkedAt 更新 |
| QUAL-06 / P0 / API | 推手真实在群但不能发言，例如仅管理员可发言且推手不是实际管理员 | 不计作可用；noPermission/reasons 解释缺口；不能因为业务角色称管理员就放行 |
| QUAL-07 / P0 / API | 已在群/发言事实未知；另一个进群任务标记完成但群事实仍未确认 | 未知不当成功，unconfirmed/reasons 可见；只有真实群事实确认后重查才可通过 |
| QUAL-08 / P0 / API | 管理员 A 同时位于推手分组；再把 A 设置为不在群/离线/无发言资格的变体 | A 不占推手候选；管理员无资格也拦截。推手 shortage=0 时仍应看 ready=false 和原因 |
| QUAL-09 / P0 / API | 同一推手角色含文本和按钮；池中有 Android 和 Web，构造只够人数但不够支持该角色全部消息的候选 | 不支持的 Android 候选不能凑数；Android 仅一个 LINK_JUMP 按钮为支持配置；多按钮/复制/快捷回复不能随机分给 Android |
| QUAL-10 / P0 / API | 两个角色都只有同一个账号支持全部消息，另有不匹配候选；再补入能形成完整分配的账号 | 无法无重复分配时失败；存在完整匹配时通过，不能仅按候选总数判定 |
| QUAL-11 / P0 / API | 对 QUAL-03 草稿 POST start，随后查任务、群、全部记录及 outbox/协议 | 40901+完整报告；仍草稿，所有群 bindingsJson 为空；全部群记录=0、outbox=0、协议提交=0；营销不自动创建进群任务 |
| QUAL-12 / P0 / LIVE | G-short 用独立进群任务补人，等群事实确认；重新检查但暂不 start | report.ready=true；任务仍草稿，记录仍为 0。显式 start 后才执行；进群业务结果单独登记 |
| QUAL-13 / P0 / LIVE | 合格双群 start；采集 bindingsJson，连续检查并读每条 record.accountId | 每群按本群候选分配，同群不同角色不占同一账号；同角色所有发言使用同账号。不同群可复用账号，不要求不同任务每次抽到不同人 |
| QUAL-14 / P0 / LIVE | check 通过后、start 前使一个选中群资格失效；再 start | 后端重新检查全部目标并拒绝；无绑定落库、无消息提交，不信任旧报告 |
| QUAL-15 / P0 / LIVE | startAt 设未来 60 秒并 start；开始前让某群原绑定账号失效，即使池中还有可替代账号也不替换 | 首次实际提交前复核全部原绑定；全任务暂停、有 pauseReason、零提交；恢复原账号后检查并 resume 才继续 |

### 4.5 顺序、间隔与真实消息

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| EXEC-01 / P0 / LIVE | G-A/G-B 执行 D4，后项固定 3 秒，不注入故障；拉全 records，对照协议结果 | 2 群各 4 个步骤，8 条唯一记录/原命令；正常终态 task=3、success=8、failed=unknown=inFlight=0、HELD=0；每条有匹配协议成功事件 |
| EXEC-02 / P0 / LIVE | 首项故意配置 30 秒等待，任务分别立即开始/未来开始 | 首项到达开始时间即进调度，不额外等 30 秒；未来开始前零提交。记录首项提交延迟，不把“立即”断言成严格 0 毫秒 |
| EXEC-03 / P0 / LIVE | 后三项固定 3/5/3 秒；读每群 submittedAt、nextAt | 后项按上一条实际提交时间加本项等待计时；没有人为停顿时不得提前；调度误差按第 6 节统计 |
| EXEC-04 / P0 / LIVE+FAULT | 下一步设 3 秒，上一条结果延迟超过 3 秒；实际延迟不足时用隔离控制制造 | 下一条提交早于上一条结果回写；延迟结果只改原记录，不再次推进 nextStep 或新增 commandId；未获得延迟窗口则本项 BLOCKED |
| EXEC-05 / P0 / LIVE | 后项等待 10～20 秒；进入等待后每秒读详情，不做暂停；再做一次等待中的服务重启变体 | `nextAt-上一项submittedAt` 在 10000～20000 毫秒内；当前项未推进时 nextAt 不变，不能每轮调度/重启重新抽等待；重启变体用独占窗口 |
| EXEC-06 / P1 / API+LIVE | 每项显式设 3 秒，任务 intervalSeconds 改成 30；再测逐项 0 秒 | 显式逐项等待不被默认值覆盖；0 秒是不额外等待，仍按调度顺序各提交一次，不要求所有时间戳相同 |
| EXEC-07 / P0 / LIVE | 双群运行，单独暂停/延迟 G-B，保持 G-A 正常 | 每群执行完整剧本并独立推进，G-B 不拖住 G-A；不要求跨群全局严格交替顺序 |
| EXEC-08 / P0 / LIVE | 指定接收设备逐条核对 runId、群、角色、步骤标识、文本/图片/按钮；记录接收时间 | API、协议命令和指定成员可见消息可关联；角色和内容正确、无重复。只证明所观察成员收到，不声称所有成员已读 |
| EXEC-09 / P1 / LIVE | 将文本、纯图片、图文、单链接按钮分别放到 Web/Android 角色发送；混合池补一轮 | 每种实际参与的协议均有正向发送与接收证据；未参与或能力不支持的组合单独记录，不从文本成功推断媒体成功 |

### 4.6 暂停、继续、关闭与资格变化

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| CTRL-01 / P0 / LIVE | D4 后项设 30 秒；首条已提交后暂停，等超过原计划时间；定时查记录 | task=2，后续步骤不新增；已在途结果可回写原记录，暂停不代表已经投递的消息能撤回 |
| CTRL-02 / P0 / LIVE | 在 CTRL-01 暂停中快照绑定、原 recordId/commandId/submittedAt，恢复后再次比较 | 原值不变；下一未提交项立即进入调度，不等剩余 30 秒；恢复前原绑定全部复核通过 |
| CTRL-03 / P0 / FAULT | 暂缓本轮命令投递，形成一条或多条明确未投递记录；暂停再恢复 | 记录进入 HELD，原 outbox 标记挂起；恢复同一 outbox/commandId，不创建替代命令；submittedAt 不变，result_deadline_at 按恢复重设 |
| CTRL-04 / P0 / LIVE | 暂停后让某个原绑定账号失效，保留另一个可用候选；resume，恢复原账号后再 resume | 首次恢复失败并返回原绑定问题，任务仍暂停、绑定不变；原账号恢复后继续，不能自动换成备用号 |
| CTRL-05 / P0 / LIVE | 整任务运行时暂停 G-B；G-A 继续；恢复 G-B | G-B paused=true，原绑定与进度保留；G-A 独立完成；G-B 恢复原进度并丢弃剩余等待 |
| CTRL-06 / P0 / LIVE | 整任务暂停后调用单群 resume；另测已过 endAt 的单群 resume | 均不能绕过整任务状态或截止；无新增后项。任务内已单独暂停的群，不因恢复整任务而被悄悄恢复 |
| CTRL-07 / P0 / LIVE | 首项已发送后，让 G-B 原绑定账号离线/退群/无发言资格，或群不可用；G-A 保持正常 | 后续检查暂停受影响群并保留原因/绑定，其他群继续；已在途项据实收结果。每种可控制的原因分别执行并恢复群事实 |
| CTRL-08 / P0 / LIVE+FAULT | 分别在等待期、HELD、已在途时 close；再尝试 start/resume | task=4，后续步骤停止；明确未投递命令取消，已在途结果可补记；不会重开已关闭任务，也不自动重发未知命令 |
| CTRL-09 / P0 / LIVE | 设置近截止 endAt，在运行和全任务暂停两种状态分别等过截止 | 到期关闭，后续不提交；已在途结果可收敛；需要查询 task/group/records，而非只观察按钮禁用 |
| CTRL-10 / P0 / API+LIVE | 重复 start/pause/resume/close；并发两次 start；另对已完成任务 start/resume | 状态合法的重复动作不产生额外绑定或发送；不合法转换不重启任务。两次 start 可均响应成功，重点验命令唯一与绑定只固定一次 |

### 4.7 入队失败、结果超时与回执

这些用例的触发点应先由本轮专用实例/故障控制准备好，再运行表中步骤。业务接口没有“伪造回执”或“修改 nextAt”的入口，不向共享消息通道直接注入未经隔离的事件。普通真实发送没遇到该时机，就不能把相应用例算作已覆盖。

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| RESULT-01 / P0 / FAULT | 全任务第一条提交前让消息准备/入队失败，再解除故障 | 全任务暂停并给出原因；发送意图/outbox 事务回滚，零提交；检查、显式 resume 后继续 |
| RESULT-02 / P0 / FAULT | 任务已有成功提交后，让某后续项局部准备或入队失败；下一项条件正常 | 原项据实 FAILED，保留原因，继续后项；未入队失败以失败时刻作为后项计时依据，无失败原项自动重试 |
| RESULT-03 / P0 / LIVE+FAULT | 为已提交项产生明确终态失败结果，并保持后续角色资格正常 | 失败仅更新对应记录，后项按既定提交节奏继续。若回执晚于下一项提交，不能回退或重新计时；不把持续资格失效导致暂停误判成单项失败规则 |
| RESULT-04 / P0 / FAULT | 一条原命令已建但明确未投递，持续到结果超时；随后恢复投递器 | 原命令取消，记录 FAILED，原因明确为等待投递超时；恢复后不再发这条原命令 |
| RESULT-05 / P0 / FAULT | 原命令可能已投递但结果不明，持续到超时，再恢复通道 | UNKNOWN，保持原 commandId，无自动重发/替换账号；不能把未知计入成功或确定未发送 |
| RESULT-06 / P0 / FAULT | 最后一项已处理但结果仍待回；分别给结果/让超时收敛 | nextStep 已等于步数时 task 仍保持执行状态；全部原命令结果/超时收敛后才 FINISHED；统计由各自记录状态计算 |
| RESULT-07 / P0 / FAULT | 针对本轮同一 commandId 发送重复终态事件、迟到事件及错误 groupJid 事件；另测 UNKNOWN 后迟到成功 | 重复/不匹配结果不产生新记录或推进；UNKNOWN 的原记录可补为成功；已关闭任务保持 CLOSED，success 后重复失败不覆盖已定终态 |
| RESULT-08 / P0 / FAULT | 控制两个调度请求竞争同一任务锁；入队后等待时重启服务；保留原记录/绑定快照 | 每个 `(taskId,groupId,stepIndex)` 仅一条记录、一个原命令；重启读取持久化绑定/nextAt，无重复发送；数据库锁测试结果与真实协议去重证据分别记录 |

### 4.8 权限与数据范围

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| AUTH-01 / P0 / API+UI | U-view 查询三菜单数据，再直接调用 create/update/start/delete；无 view 用户直接请求详情/check/options | 只读权限不能写/操作；无 view 不能绕过页面读业务数据；服务器拒绝且无副作用 |
| AUTH-02 / P0 / API | 分别用 U-create/U-edit/U-operate 调用创建、编辑、任务操作、定义删除；去掉对应权限再重复 | 三菜单操作分别要求 view+对应 create/edit/operate；定义软删要求 edit；资源库删除遵循其独立权限，不能只靠隐藏按钮 |
| AUTH-03 / P0 / API | U1 创建任务/定义，U2 查询列表并直接访问其 detail/check/records/update/action/delete 路径 | 同租户他人任务/定义不可读取或操作，不能通过猜 ID 越过本人范围；公共素材按租户公共范围仍可见 |
| AUTH-04 / P0 / API | U3 使用 U1 的任务/定义/素材/图片/群/分组/账号 ID 查询或保存 | 跨租户不能读、绑定或操作，响应不泄露内容；无外租户引用和残留任务 |
| AUTH-05 / P0 / API | U1 的任务路径携带另一个任务的执行 groupId，调用单群 pause/resume；包含同用户其他任务、他人任务、其他租户变体 | 均拒绝，不改变任一任务；不能把 groupLinkId 误作执行 groupId |
| AUTH-06 / P0 / API | 草稿嵌入不可访问的图片/群/分组；管理员填外租户或不存在账号；分别尝试 check/start | 资源访问资格由后端复核；不能产生越权绑定/协议提交。管理员候选资格可在检查/启动时报告，但不能因草稿已存就放行启动 |

### 4.9 页面和兼容性

| ID / 级别 / 方式 | 前置与步骤 | 预期与证据 |
|---|---|---|
| UI-01 / P0 / UI | 正式租户登录后查看素材库→剧本→任务三个菜单，进入原任务路由 | 菜单授权和按钮可用；原任务菜单 ID、路由、按钮权限延续，不能依赖本地 mock 菜单 |
| UI-02 / P0 / UI | 选素材进定义，保存；选定义进任务，配置管理员/推手池，检查并保存 | 副本内容完整；推手无手动账号绑定；顺序、角色和逐项等待可见 |
| UI-03 / P0 / UI | 搜 G-A 勾选，再搜 G-B 勾选，翻页并清空搜索，保存 | 已勾选群保留，保存后的目标与明确勾选一致，无重复 JID或静默丢群 |
| UI-04 / P0 / UI | 缺口报告分别从表单、列表检查和启动失败打开；用至少 21 群翻页、切“只看不达标群” | 报告持续可看，有群名/JID/所需/可用/缺口/原因；人数已够但能力不符时不能误导为整群已达标；全部问题均能查看 |
| UI-05 / P0 / UI | 未保存编辑页点“保存草稿并前往进群任务”；做保存失败变体；补齐后返回重查 | 成功才跳 `/task/join`，配置可恢复；失败保留表单且不跳转；重查成功不自动启动 |
| UI-06 / P1 / UI | 默认间隔改值后新增步骤，再改默认；首项设大等待；快选两个剧本、快切两个任务详情/记录 | 默认只影响新项，已有显式等待不被改；预计等待为每群后项等待总和；较早迟到响应不覆盖最后一次选择 |
| UI-07 / P0 / UI | 查看运行/暂停/完成/关闭及包含失败、未知、HELD 的任务 | 处理进度、成功/失败/未知分别展示；查看到的数据与对应 API 一致，不能将步骤处理完显示为全部成功 |
| LEGACY-01 / P0 / API+LIVE | 读取真实 `account_group_id=NULL` 旧任务，按其合法状态继续执行 | 使用旧固定账号和按结果后计时语义；不强行随机绑定、不被新执行器混用 |
| LEGACY-02 / P1 / API+UI | 打开旧草稿并保存；先不填分组，再按新规则补分组、roleKey、逐项等待并去掉推手固定账号 | 显式要求补齐配置；成功保存后清楚转换为新配置；旧任务 check/单群操作不支持时给出说明 |
| LEGACY-03 / P0 / 环境核验 | 开跑前核对后端/前端版本、V186/V187 成功状态与旧迁移校验 | 当前制品确实包含本文功能；未改写已应用 V180，无新旧执行器混跑；只记录本轮实查结果 |
| LEGACY-04 / P1 / API+LIVE | 独占窗口内令任务处于等待/暂停/在途后重启后端，再查绑定、原命令和进度 | 三种状态均恢复持久化事实；不重新抽号、不重复提交；已暂停任务不因重启自动运行 |

### 4.10 批量真实发送

使用 8 个号方案即可：1 管理员、6 推手加入全部目标群，1 接收号用于收件验证。批量剧本配置 **1 个 ADMIN + 5 个不同 PROMOTER roleKey**，角色可重复发言，消息携带 `runId/stepIndex`；20/100 个发送项不需要 20/100 个账号。群由目标 JID 区分。

| ID / 级别 / 方式 | 规模与步骤 | 预期与证据 |
|---|---|---|
| LOAD-01 / P0 / LIVE | 10 群 × 20 项 = **200 条**，固定 3 秒间隔；正常条件连续执行一轮 | 每群 20 个唯一步骤，共 200 个原命令；正常目标成功 200，失败/未知逐条归因；无漏项、重复和跨群错投 |
| LOAD-02 / P1 / LIVE | 50 群 × 20 项 = **1000 条**；复用同一池，完成上一轮后运行 | 同样做全量命令/结果对账；采集开始延迟、间隔偏差、结果耗时、积压和查询耗时，不能仅截图总成功数 |
| LOAD-03 / P1 / LIVE | 100 群 × 100 项 = **10000 条**，达到当前单任务群数/步骤上限 | 全量原命令唯一、结果可收敛、记录分页无丢失；确认各群确实完成 100 项。实际设备接收可采样，但采样群/步骤须列明 |
| LOAD-04 / P0 / LIVE | 10 群 × 20 项，运行后单独暂停 1 群，确认其余 9 群继续，再恢复原群 | 受影响群保持绑定/原进度，其余群完整执行；恢复后最终仍只有 200 个步骤意图，无重发；结合 CTRL/RESULT 区分在途结果 |

分阶段记录数据，不预设接口能达到某个 QPS。3 秒间隔时每群 20 项理论配置等待为 `19×3=57秒`，100 项为 `99×3=297秒`；这是等待下限口径，不含调度、协议排队、发送和结果等待。当前调度器每轮有界扫描，实际总时长用本轮证据衡量，不能用 `消息总数÷3秒` 推算吞吐。

每轮必须拉取所有记录页，全量计算预期步骤集合与实际步骤集合差异；单条正常成功同时具备记录状态和对应协议成功结果。出现失败/未知时按事实报告该轮结果，不通过继续造任务把失败总量冲淡。批量回归结束后给出 **账号数、群数、预期条数、实际意图数、协议提交数、成功/失败/未知/待收敛数、重复数、抽样接收结果**。

## 5. 接口执行样例

可以把第 3 节导入现有接口工具，按变量串联；批量回归用脚本调用同一组业务 API 即可。首次搭建需要整理环境和断言，之后重复回归直接复用。以下样例是**文本双群四步**的最短路径，与批量 5 推手角色场景分开；没有在本次编制中向远程执行这些请求。

### 5.1 生成合法请求体

先用 options 查询本轮资源，将实际数值登记为环境变量 `GM_ACCOUNT_GROUP_ID`、`GM_ADMIN_ID`、`GM_GROUP_A_ID`、`GM_GROUP_B_ID`。在终端设置已确认的 `GM_BASE_URL`（站点根，不含 `/api`）和临时会话 `GM_TOKEN`，后者不得打印、保存进用例或提交。接口工具使用自己的环境/密钥变量功能即可。

下面代码只在临时目录生成三个 JSON 文件，没有网络操作。`task.json` 可直接用于表单检查和保存草稿；定义的账号为空，任务的管理员账号来自本轮配置，推手仍为空。

```bash
export GM_RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gm-api.XXXXXX")"
python3 - <<'PY'
import copy
import datetime
import json
import os
import pathlib
import uuid

def resource(name):
    value = int(os.environ[name])
    if value <= 0:
        raise ValueError(f"{name} 必须是本轮真实资源 ID")
    return value

pool = resource("GM_ACCOUNT_GROUP_ID")
admin = resource("GM_ADMIN_ID")
targets = [resource("GM_GROUP_A_ID"), resource("GM_GROUP_B_ID")]
if len(set(targets)) != 2:
    raise ValueError("两个目标群条目 ID 不能相同；JID 去重另由接口校验")
run = "GM-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
out = pathlib.Path(os.environ["GM_RUN_DIR"])
material = {
    "templateName": run + "-文字素材", "linkMode": 1, "textType": None,
    "imageFileId": None, "content": run + "-素材-v1", "bodyText": "",
    "buttons": [], "promotionLink": "", "remark": "养群测试", "mentionAll": False
}
steps = []
for index, (role, key) in enumerate([
    ("ADMIN", "admin"), ("PROMOTER", "p1"),
    ("PROMOTER", "p2"), ("ADMIN", "admin")
]):
    message = copy.deepcopy(material)
    message["content"] = f"{run} / step={index} / role={key}"
    steps.append({"role": role, "roleKey": key, "accountId": None,
                  "message": message, "waitMinSeconds": 30 if index == 0 else 3,
                  "waitMaxSeconds": 30 if index == 0 else 3})
definition = {"name": run + "-D4", "enabled": True, "steps": copy.deepcopy(steps)}
for step in steps:
    if step["role"] == "ADMIN":
        step["accountId"] = admin
task = {"taskName": run + "-双群四步", "intervalSeconds": 3,
        "startAt": None, "endAt": None, "groupLinkIds": targets,
        "steps": steps, "accountGroupId": pool}
for name, body in [("material", material), ("definition", definition), ("task", task)]:
    (out / f"{name}.json").write_text(json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"runId={run}\n请求体目录={out}")
PY
```

这三个文件用于对应对象的合法请求样例。真正验证“选用快照”时，应先读取已创建素材/定义的响应再复制其内容，随后修改源对象并对比旧副本，不能仅凭本地生成了相似 JSON 判定快照功能通过。

### 5.2 检查、保存、启动分步执行

在同一终端定义以下请求函数；token 经标准输入传给 curl，不放在 URL。不要开启 shell 调试输出。请求超时后，先按本轮 taskName 查询和核对状态，避免不明结果下再次 POST 创建重复任务。

```bash
gm_api() {
  : "${GM_BASE_URL:?请配置已确认的测试环境站点根地址}"
  : "${GM_TOKEN:?请配置临时测试登录会话}"
  printf 'header = "Authorization: Bearer %s"\n' "$GM_TOKEN" |
    curl --config - --silent --show-error --max-time 30 \
      --request "$1" --header 'Content-Type: application/json' \
      "${GM_BASE_URL%/}$2" "${@:3}"
}

# 查询选项；当前函数适用于 Bash 或 Zsh。
gm_api GET /api/script-marketing-tasks/options/account-groups

# 对完整请求体检查；code=0 仍须继续看 data.ready。
gm_api POST /api/script-marketing-tasks/check \
  --data-binary "@$GM_RUN_DIR/task.json" \
  --output "$GM_RUN_DIR/check.json"

# 保存草稿不要求资格已齐，不会发送。
gm_api POST /api/script-marketing-tasks \
  --data-binary "@$GM_RUN_DIR/task.json" \
  --output "$GM_RUN_DIR/create.json"

export GM_TASK_ID="$(python3 - <<'PY'
import json, os, pathlib
body = json.loads((pathlib.Path(os.environ["GM_RUN_DIR"]) / "create.json").read_text())
assert body["code"] == 0, body.get("message")
assert body["data"]["task"]["status"] == 0, "创建结果必须为草稿"
print(int(body["data"]["task"]["id"]))
PY
)"
```

**下一段 start 会真实发送。** 核对环境、GM_TASK_ID、两个群和发送内容后执行；若本次用例要求缺口拦截，则预期 40901 并记录完整报告，不能当普通脚本错误丢掉响应。正常用例启动前应重新读取 check 的 `ready`，启动本身还会再验一次。

```bash
: "${GM_TASK_ID:?先从草稿响应取得任务 ID}"
gm_api GET "/api/script-marketing-tasks/$GM_TASK_ID/check" \
  --output "$GM_RUN_DIR/check-saved.json"
gm_api POST "/api/script-marketing-tasks/$GM_TASK_ID/start" \
  --output "$GM_RUN_DIR/start.json"
gm_api GET "/api/script-marketing-tasks/$GM_TASK_ID" \
  --output "$GM_RUN_DIR/detail.json"
gm_api GET "/api/script-marketing-tasks/$GM_TASK_ID/records?page=1&pageSize=1000" \
  --output "$GM_RUN_DIR/records-page-1.json"
```

动作与查询不要靠固定 sleep 就认定通过：小任务每 1～2 秒轮询 detail/records 直到目标状态或观察窗口结束；大任务按 2～5 秒查摘要、按阶段抓全量记录，减少取证自身造成的负载。超过一页则遍历到 totalPages；任务静止/收敛后再做最终全量对账。start/pause/resume/close 的响应体都需检查 `code`。

纯图片消息使用 `linkMode=3,imageFileId=本轮图片ID,content="",buttons=[]`；图文仍是 3；按钮使用 2，按钮元素结构为 `{"type":"LINK_JUMP","text":"查看测试页","param":"https://example.com"}`。这些是消息片段，嵌入完整 message 后发送；不存在“纯图片类型 4”。

## 6. 如何判定结果和留证据

### 6.1 四层结论分开写

| 层次 | 最少证据 | 可以得出的结论 |
|---|---|---|
| 接口与业务状态 | 脱敏请求/响应、检查报告、完整 task/groups/records | 参数、资格、快照和任务状态正确 |
| 持久化与命令 | 关键用例的只读数据库结果；recordId/commandId、bindings_json、next_at、result_deadline_at、对应 outbox | 固定绑定、原命令、事务/超时/暂停事实；不得导出含凭据的整个 payload |
| 协议结果 | 用本轮 commandId/messageId/JID 关联的协议提交及 `message.send_result_reported` 等实际结果事件 | 协议实际处理、成功/失败及重复提交情况；读取事件不修改共享消费者进度 |
| 成员接收 | 指定成员设备的可见消息、时间、群、runId/step 标识；有实际 ACK 时保留其层级 | 指定成员收到/读到的范围；无 ACK 不自动判定未送达，也不扩大成全体已读 |

基本证据目录建议为 `docs/testing/evidence/group-maintenance/<runId>/`（执行时创建），包含环境登记、脱敏请求响应、关键时间线、全量记录摘要、协议关联、接收截图和结果表。认证信息不进入证据目录；图片与消息只用专用测试内容。

普通素材/草稿参数用例有接口证据即可。涉及“零提交、只一次、保留原命令、超时”的用例必须补命令证据；涉及真实接收的用例必须有指定成员证据。缺哪层就说明哪层未证实，不要求对每个纯参数测试都截图查库。

### 6.2 时间与次数断言

- 无暂停/失败的第 i 个后项：`计划等待 = nextAt(i) - submittedAt(i-1)`，应落在本项 min/max 区间且只抽一次；`实际间隔 = submittedAt(i) - submittedAt(i-1)`，不得早于已保存 nextAt。
- 正常环境短回归建议先以 5 秒作为调度额外延迟的排查阈值，记录实际最大值；这不是设计给出的性能 SLA。超出时核对积压与调度日志，再给性能结论。批量场景单独记录分位数，不把 5 秒阈值机械套到全部负载。
- 首项不得早于 startAt，且须在启动状态提交后进入调度。保存 start 请求起止时刻与首项 submittedAt，用请求时间窗评估启动延迟；首项可能在 HTTP 响应到达前已入队，不能断言 submittedAt 必须晚于响应到达时间。
- 恢复等待中的新任务：下一未提交项从恢复时进入调度；此前已提交记录的 `submittedAt` 不变。`result_deadline_at` 需数据库证据，当前 records VO 没有该字段。
- 当前本地代码结果等待窗口为 **120000 毫秒**、调度器固定延迟 **1000 毫秒**；开跑先核对实际版本。超时用例观察至少一个实际结果窗口再加调度余量，禁止改写库中时间缩短测试。
- 并发/重复请求断言业务唯一，不要求每个 HTTP 请求都报错：正常每个 `(taskId,groupId,stepIndex)` 一个 recordId、一个 commandId。协议传输重试与重复物理发送需分别核对，不能仅凭单个 outbox 行证明成员没收到重复消息。
- 所有步骤处理完仍有 SENDING/HELD，不能判 FINISHED；全部收敛后可 FINISHED 且包含 FAILED/UNKNOWN。终态字段不是“全成功”的同义词。

### 6.3 执行记录格式

每条用例及其协议/边界变体至少填写：

| 字段 | 内容要求 |
|---|---|
| 标识 | caseId、runId、执行时间窗（北京时间，同时保留原始毫秒时间） |
| 环境 | 环境名、实际版本、租户/后台用户 ID、Web/Android/混合 |
| 资源 | 实际 accountGroupId、管理员/推手 ID、群 JID、素材/定义/任务/执行群 ID |
| 步骤 | 请求顺序、变体、故障触发位置与时刻、恢复动作 |
| 预期与实际 | 状态、绑定、记录/命令数量、成功/失败/未知/在途/HELD、间隔与结果耗时 |
| 接收 | 指定成员、群与步骤范围、采样数量/总量、实际收到/未证实 |
| 证据 | 脱敏 API、关键 DB/outbox、协议事件、必要截图的路径 |
| 结论 | PASS / FAIL / BLOCKED / NOT_RUN；问题原因、影响和下一步；不能省略未执行项 |

批量用例给出完整发送结果统计和接收采样范围；例：只有抽样接收证据时，应写“10000 条协议结果已对账，指定接收号抽样检查的 10 群/100 条可见”，不能写“全部群所有成员 10000 条均已收到”。这只是报告写法示例，不是本轮实测结果。

### 6.4 结束与通过条件

1. 快速冒烟通过后，继续执行本范围全部 P0；任一核心规则 FAIL 均应修复后复测，BLOCKED 单列并说明无法覆盖的原因。
2. 正常真实发送用例的预期步骤、记录、原命令和协议结果完整对账；正常路径目标为全成功，故障用例按设计失败/未知收敛。所有路径均不得多发或错投。
3. 页面用例在真实测试环境执行；本地拦截 API 的浏览器夹具只能算前端隔离测试，不计接口联调。
4. 接收结论按观察范围填写；Web/Android/混合、素材类型和负载梯度各给结论，不能相互替代。
5. 恢复本轮修改的群发言设置、专用账号状态、投递/事件故障控制；关闭本轮遗留活动任务并核对原命令，不删除历史任务/记录来清空失败证据。素材没有本次专用删除接口，保留 runId 便于定位。

## 7. 需求覆盖与编制依据

| 需求章节 | 对应用例 |
|---|---|
| §1 三菜单、四类消息、内容快照、图片复用 | MAT-01～08、DEF-01～06、CFG-02、UI-01～02 |
| §2 角色去重、严格容量、每群随机、协议能力、绑定持久化 | DEF-07～08、CFG-03、QUAL-01～15、CTRL-02～04、LEGACY-04 |
| §3 群选择、完整缺口、整单零提交、独立进群、二次复核 | CFG-01/04/05、QUAL-03～08/11/12/14/15、UI-03～05 |
| §4 首项、逐项间隔、提交驱动、迟到结果、独立推进、暂停恢复 | CFG-06～07、EXEC-01～07、CTRL-01～06、RESULT-04～08、UI-06 |
| §5 整任务/单群异常、局部失败、未知不重发、关闭与收敛 | CTRL-07～10、RESULT-01～08、UI-07 |
| §6 数据模型、权限、存量兼容 | MAT-08、AUTH-01～06、LEGACY-01～04 |
| §7 真实测试环境与送达边界 | EXEC-08～09、LEGACY-03、第 6 节证据与通过条件 |
| 本次追加：账号预算与大量真实发送 | §2.1、LOAD-01～04 |

以下为编制时的“依据 → 结论 → 验证路径”，只证明用例有来源，不代表测试环境执行通过：

| 依据（Evidence） | 编制结论（Finding） | 验证路径（Path） |
|---|---|---|
| [设计契约](2026-09-10-group-maintenance-requirements.md) §2/3 | 每群资格与整体启动门槛必须同时满足 | options → check → 草稿 → start 拦截 → 独立进群 → check → 显式 start |
| [任务 Controller](../../armada-api/src/main/java/com/armada/marketing/controller/ScriptMarketingTaskController.java)、[DTO](../../armada-api/src/main/java/com/armada/marketing/model/dto/ScriptMarketingSaveDTO.java)、[资格 VO](../../armada-api/src/main/java/com/armada/marketing/model/vo/ScriptQualificationVO.java) | 接口已支持无页面回归，资格错误保留报告；必须验业务码 | 第 3/5 节请求 → QUAL/CFG/AUTH 断言 |
| [素材 Controller](../../armada-api/src/main/java/com/armada/marketing/controller/ScriptMaterialController.java)、[定义 Controller](../../armada-api/src/main/java/com/armada/marketing/controller/ScriptDefinitionController.java) | 不能编造素材删除/详情、定义 clone/enable 路由 | 素材列表/保存/clone；定义 GET→POST 复制、完整 PUT 启停 |
| [资格服务](../../armada-api/src/main/java/com/armada/marketing/script/service/ScriptQualificationService.java)、[消息能力](../../armada-api/src/main/java/com/armada/marketing/script/service/ScriptMarketingContentService.java) | 人数为零缺口并不保证能力匹配；新任务推手不手绑 | QUAL-08～10/13～15 → ready/reasons → 固定绑定验证 |
| [分组执行器](../../armada-api/src/main/java/com/armada/marketing/script/service/ScriptMarketingPacedExecutionService.java)、[结果执行服务](../../armada-api/src/main/java/com/armada/marketing/script/service/ScriptMarketingExecutionService.java) | 提交进度与结果分离，暂停恢复保留原命令 | EXEC/CTRL/RESULT → 记录时间线 → outbox/协议结果 |
| [任务统计 Mapper](../../armada-api/src/main/resources/mapper/marketing/ScriptMarketingTaskMapper.xml)、[记录 VO](../../armada-api/src/main/java/com/armada/marketing/model/vo/ScriptMarketingSendRecordVO.java) | HELD 不在 inFlightCount 中，result_deadline_at 不在 records API 中 | 全量明细分组计数；关键期限只读查库 |
| [历史 test1 验收](../../.harness/changes/script-marketing-refinement/acceptance-20260910.md)、[本地测试记录](../../.harness/changes/script-marketing-refinement/test-summary.md) | 双群文本和本地夹具不能替代媒体、全部协议、成员接收与规模覆盖 | 新 runId → EXEC-08/09、LOAD → 本轮证据与独立结论 |

编制检查：需求覆盖、路由/字段/状态核对、Markdown 相对链接及样例本地语法校验；未执行测试环境回归，未把历史通过结果计入本轮。
