# 拉群数据包实施与本地验收

日期：2026-09-15。用户确认四张表、避免过度设计；点击/访问趋势明确延期。

更新：本次前后端已于 01:43 部署到第一套 test1，V191/V192、运行制品、公网入口和只读深度检查通过；登录后的业务验收待完成。详细证据见 `.harness/changes/group-pull-data-package/test1-deployment.md`。下文“本地验证”仅描述部署前测试。

## 已实现范围

| 入口 | 当前行为 |
|---|---|
| 任务中心 → 拉群数据包 | 动态菜单、独立权限、服务端名称/国家/大洲/消费业务/日期筛选和分页 |
| 列表和详情 | 包名称备注、国家、大洲、消费业务、组合号码统计、详情号码及导入记录 |
| 创建/编辑/删除 | 版本校验；删除保留执行证据，活动任务或未确认分配受到保护 |
| 导入 | TXT预检、样例、追加/覆盖、A/a标记、重复合并、原始顺序/物理行、历史隐私筛选、成功/失败审计 |
| 导出 | 单包/多包按全部、未用、成功、失败、隐私拒绝导出TXT/CSV；列表CSV明确当前页 |
| 重置失败 | 只回收可重试失败；隐私拒绝、未注册和UNKNOWN保留，不把不确定结果当未使用 |
| 正式新建标准拉群 | 选择数据包→执行计划→正式提交领取，原TXT路径可继续使用 |
| 任务结果 | 成功、失败、未知、结束、未提交释放、删除和换群重试回写包状态 |
| 点击/访问趋势 | 显示待接入；不新增无写入方的PV/UV字段或伪造图表 |

每个选中数据包的未用号码是一份料子执行单元；多个包按选中顺序产生多个单元。群被替换时保留同一逻辑分配，新群从头执行，旧群迟到结果不覆盖新群状态。
单次最多50包，每包取用上限100000；超限明确拒绝，不能静默截断。包内可追加至500000，单次导入最多100000有效去重号码，文件10MB。

## 代码与模型

资源仅新增 `group_data_package`、`group_data_package_phone`、`group_data_package_import`、`group_data_package_stat` 四表，现有拉群成员与尝试记录仍为执行事实。V192在执行行增加包ID/包代次，在成员增加来源号码ID/分配版本；旧TXT来源为空。

- 后端资源：`armada-api/src/main/java/com/armada/resource/`；接口 `/api/group-data-packages`。
- 正式取用：`PullTaskStandardDraftServiceImpl`、`PullTaskDataPackageSourceService`、`PullTaskStandardCreateTransactionService`。
- 回写：`GroupDataPackageTaskProjectionServiceImpl`及既有结果/生命周期入口；逐号码回调仅同步对应成员，避免每个回调扫描整包。
- 前端：同级 `wheel-saas-pure-web/src/views/resource/group-data-package/` 和标准拉群抽屉。
- 迁移：`V191__group_data_package.sql`、`V192__pull_task_group_data_package_source.sql`。审计副本与保护回滚见 `.harness/changes/group-pull-data-package/`。

必要字段用途：`generation`保护覆盖前草稿；`allocation_version`阻止旧分配写入新分配；`member_seq/source_line_no/admin_required`保留执行顺序与A标记；`last_used_at`只在真实领取写入并驱动消费业务显示；`version`用于名称备注并发编辑；导入表追溯文件与计数；stat直接供列表查询，不在GET中扫描任务历史。

## 本地验证

- 资源真实H2/MyBatis：12项通过，覆盖追加/覆盖回滚与审计、租户隔离、UNKNOWN保护、并发领取、同批旧新分配、读接口无任务写入、10万号码导入和领取的SQL批量规模。
- 资源接口/迁移合同：5项通过，覆盖权限、表数、字段、菜单和隔离级别约束。
- 真实跨域H2：`GroupDataPackageTaskResourceH2Test` 3项通过。同一数据源/真实Mapper/事务连接任务来源和资源状态，覆盖领取→回写→结束→回收→再领取，以及第二单元冲突回滚第一单元的资源和绑定、单执行失败且兄弟仍运行时立即释放未提交号码。
- 任务相关回归：草稿计划/读取/创建、材料来源复制、生命周期、逐成员结果、未知结果、群失败/换群、控制器和迁移均有本地测试。最终合并25个测试类、249项全部通过，0失败/错误/跳过（数据包投影16项、跨域3项、菜单管理11项包括在内）。明确排除下述已在原始基线复现的两项旧E2E，其余9项E2E通过。
- 前端拉群/数据包相关测试166项、菜单/路由测试11项通过；typecheck、ESLint、Stylelint、build通过。Stylelint使用已安装pnpm配置目录解析，不改规则或依赖。
- 本地浏览器7项通过：服务端名称筛选、保存失败保留输入、实际TXT下载内容、只读权限、导入预检和确认、编辑版本/重置/删除、号码及导入记录。
- 浏览器使用测试专属合成API夹具；真实SQL与事务由后端H2验证。没有调用实际WhatsApp、真实租户接口或远程数据库。

发现并修复：导出组件多根节点导致权限指令失效；操作列过宽遮挡统计；前后端大洲和物理行口径不一致；同批旧分配先出现导致新分配被跳过；大包逐条SQL；超限快照截断；回写锁SQL被租户插件重排；单执行回写扩锁其他任务；同执行旧UNKNOWN尝试在结束时被误释放；菜单维护白名单遗漏。

## 一致性收口

领取和结算按包ID升序持锁；持包锁后号码用FOR UPDATE当前读。单条回调只同步当前成员，单执行结束只同步当前执行，任务整体结束只同步当前任务，避免按数据包扩锁其他任务。资源GET使用只读快照；重置和删除在READ_COMMITTED事务中取得包锁后再次检查活动引用。实际MySQL隔离级别的部署验证尚未执行。

已有UNKNOWN且执行状态不是NOT_STARTED的尝试保留为不确定历史：即便成员回到待重试后再结束，也不会被释放或重置；明确成功结果可收敛未知，确认未开始的尝试仍允许安全释放。

## 已知限制与发布边界

1. 两个既有E2E用例 `revokedInviteWithKnownGroupJidCurrentlyNeverRefreshesInvite[WEB/ANDROID]` 仍失败。已将未改动HEAD `267bb14cfc610353231f6ce8a1cf6318cbb275e7` 通过git archive放入隔离目录复跑，同样实际 `[2,2,2,2,2,2]` 而预期 `[3,2,3,2,3,2]`。这是基线已有差异，本次没有放松断言或顺带修改该业务。
2. 部署前生成器曾因缺 `/tmp/wheel_tables.tsv` 退出；本次部署后已从 test1 导出真实 information_schema，并重新生成自动 wiki。
3. 未提交、未推送；已部署 test1，真实 MySQL 的 V191/V192 迁移成功。登录后的实际导入/任务取用操作和 WhatsApp 执行结果仍需分别验收。
4. 回滚脚本仅在四表和任务来源均无数据时允许删结构；已有数据时保留schema并走兼容回退，脚本本轮没有执行。

## 证据位置

- `/tmp/group-data-browser-final.log`；截图 `/tmp/group-data-package-page.png`（合成测试数据）。
- `/tmp/group-data-package-frontend-tests.tap`、`/tmp/group-data-package-frontend-build.log`。
- `/tmp/task-dataset-final-combined.log`及 `armada-api/target/surefire-reports/`。
- 基线复现 `/tmp/group-data-baseline.log`；隔离目录 `/tmp/armada-group-package-baseline-267bb14/armada-api`。
