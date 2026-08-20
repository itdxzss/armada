# 拉群任务「新群模式」交接件

> 交接时间：2026-08-19
> 分支：`feat/pull-task-new-group-mode`
> 工作目录：`armada/.worktrees/pulltask-new-group-mode`（worktree，**不要在主 checkout 上干活**，那里常挂着别人的 agent）
> 提交：9 个，**未推送**，已 merge 主线 `1.0.3-snapshot`（含群信息设置那一刀）
>
> 接手前请先读：`docs/business/pull-task-new-group-mode-development-design.md`（实施规格）与 `docs/adr/0010`~`0013`（四条架构决策）。本文只讲进度与坑，不重复规格内容。

## 1. 已完成

### 第 1 刀：迁移与枚举（提交 `a193ed3e`、`e51d4558`、`6911dd49`）

| 迁移 | 内容 | 状态 |
|---|---|---|
| V133 | 执行行 `normalized_link`/`invite_code`/`source_link_line_no` 三列改可空；新增 `create_step`/`create_operation_id`/`create_attempt_count`/`group_subject`；订正 `stage` 注释为九阶段 | 本机 MySQL 实跑通过 |
| V134 | `pull_task` 新增 `creation_mode` | 已写，未实跑 |
| V135 | `entry_mode` 注释追加取值 4 | 已写，仅改注释 |
| V136 | `pull_task_standard_setting` 新增 `creator_group_id`/`creator_group_name`/`initial_station_count` | 本机 MySQL 实跑通过 |

枚举纯追加：`PullTaskAccountEntryMode.GROUP_CREATE_INITIAL(4)`、`PullTaskExecutionStage.GROUP_CREATE(9)`。既有取值一个未动。

### 第 2 刀（部分）：创建入参校验（提交 `0cf5bf1c`）

- `PullTaskStandardCreateDTO` 末尾追加 `creationMode` / `creatorGroupId` / `initialStationCount`，均可空。
- 新增 `PullTaskCreationMode` 枚举（`PASTED_LINK` / `NEW_GROUP`）。
- 新增 `PullTaskNewGroupModeValidator`（静态方法，非 Spring Bean），已接进 `PullTaskStandardCreateTransactionService.validate()`。
- 校验规则：新群模式必选建群人分组；初始站台数非负；初始站台 > 0 必须选站台分组；群链接模式整体跳过。

## 2. 未完成——下一步从这里开始

### 2.1 第 2 刀剩余两件（**优先**）

**(a) 配置落库。** 目前三个新字段只做了校验，**没有写进数据库**。需要改 `PullTaskStandardSettingWriter`，把 `creatorGroupId` / `initialStationCount` 写进 `pull_task_standard_setting` 的对应列，并同步写 `creator_group_name` 快照（照既有 `managerGroupName` 等的写法）。`creationMode` 要写进 `pull_task.creation_mode`。

**(b) 新群模式不该要求粘贴群链接。** `PullTaskStandardCreateTransactionService.submit()` 里有一道：

```java
List<PullTaskGroupExecution> rows = executionMapper.selectByTaskId(task.getId());
if (rows.isEmpty()) {
    throw new BusinessException(ErrorCode.VALIDATION, "至少需要一条群链接与 TXT 的匹配");
}
```

新群模式没有粘贴的链接，执行行要按「计划创建几个群」生成，链接三列留空（V133 已经让它们可空）。这一步要想清楚：**新群模式的执行行由什么驱动生成**——规格里定的是仍绑定料子 TXT（ADR-0005 的 1:1 配对，配对左侧从「粘贴的链接」变成「计划创建的群」），所以大概率是按上传的 TXT 文件数生成执行行。**这条没有实现过，接手时请先确认草稿态是怎么收料子的**，别照本文猜。

### 2.2 第 3~4 刀：建群阶段

规格 6.2 有完整的七步序列。**注意第 4、6 步（群资料、拉人前群设置）不要自己实现下发**，直接调主线的 `PullTaskGroupProfileDispatcher.dispatchIfDue(执行行, 时机, now)`，详见规格 3.1.2。

### 2.3 第 5 刀

原计划的「收口阶段 AFTER_PULL 分支」主线已做。本片改为：定 `is_group_setting_enabled` 在新群模式下的默认值 + 端到端串联。

### 2.4 第 6~7 刀

前端 Tab 与表单；test1 真环境 Playwright 闭环（前置条件见 §4）。

## 3. 必须知道的坑

1. **`"NORMAL_LINK"` 硬编码在 26 个主代码文件里**，是执行链路开关不是群来源。新群模式的 `mode` 必须仍是 `NORMAL_LINK`，否则调度器不认领执行行——任务建完一步不动，**且不报错**，只是永远停在待启动。模式区分走 `creation_mode`。

2. **不要碰 `group_source` 列**。那是 V088 给拉群营销定义的历史群/自收群来源，名字相近语义无关。

3. **迁移版本号必须在落盘当刻复查**，昨天查过不算。本次两天撞号两次（V130 被 delaySend 占、V132 被群设置总开关占）。核对命令在规格 4.4。

4. **H2 测试表结构是手工维护的**（`PullTaskNormalLinkSchema`）。加列只写迁移不改它，Mapper 测试会拿过期结构照样通过——假绿。

5. **`PullTaskStandardCreateDTO` 是按位置构造的 record**，加字段一律追加末尾，改序会连带炸掉 8 处派生 helper。

6. **初始站台的 `membership_status` 必须写 `IN_GROUP`**。写成 `NOT_JOINED` 会被后续拉人调用重新选中、重复提交同一个号（判定逻辑见规格 3.2）。

7. **建群人落既有 `role_type=4`**，不新增角色。建群 + 给次管理员提权后即退居二线，不参与邀请拉手。

## 4. 测试基线（重要）

**这个分支本来就带着 6 个红，不是新引入的。** 判断自己有没有弄坏东西，请跟这 6 个比对：

```
PullTaskMapperBusinessConditionTest.businessStatusConditionsMustComeFromJavaParameters
PullTaskClosingTransactionServiceTest.closesLastExecutionAndCompletesParentTaskWithCas
PullTaskStationSupplementServiceTest.rejectsOverfillAndManualAccountsOutsideCurrentCandidates
PullTaskStationSupplementServiceTest.automaticSupplementFreezesTheRequestedNumberOfCandidates
PullTaskStationSupplementServiceTest.manualSupplementLocksOnlyTheStationAndPreservesManualPause
PullTaskNormalLinkCollationDbTest.inviteCodesDifferingOnlyByCaseAreDistinctLinks（要连真库，本地起不来上下文）
```

跑法：

```bash
cd armada-api && mvn -Dtest='PullTask*Test,*PullTask*Test' -DfailIfNoTests=false test
```

当前：**754 个，3 failures + 3 errors**，与基线一致。

**改列/生成列/索引类迁移，建议在本机 MySQL 实跑一遍再合并**——H2 上 Flyway 根本不执行，文本测试只能断言脚本写了什么，不能断言 MySQL 认不认；迁移失败会 crash-loop 全站 502。做法见规格 4.1 的验证记录。本机库凭证在 `armada-api/.env`（gitignored，worktree 里没有，从主 checkout 读），指向 `localhost:3306`。

> 我验证时建过一个临时库 `armada_ddl_probe_v131` 和 `armada_ddl_probe_v133`，**尚未清理**，可直接 drop（属于删除操作，请先取得用户同意）。

## 5. 真环境验证的前置条件（尚未满足）

0818 实测 test1：

- 站台分组（108 拉群测试-站台分组）20 个账号 **0 在线**；
- 拉手分组（117 测试拉手分组）6 个 **0 在线**，145 那个 87 个仅 1 在线；
- 环境有人在用（当时 `#167` 正在执行）。

不解决则执行行会停在「等待拉手」，验不到拉人段。用户已表示这两件由他处理。

料子号码是硬消耗：拉进群的号不能再用，按每轮 5~10 人计，一个 40 人文件够跑四到八轮。

## 6. 两条已知缺口（**不要为新群模式单独补**）

1. **群设置结果回不来**：协议事件 source 白名单没加 `pull_task_group_profile`，协议侧也没有「哪一项失败」字段。发出去只知道发了，不知道成没成。建群阶段第 4、6 步只能发完即过。
2. **失败不自动重发是 0819 明确取舍**，不是缺口。理由：协议侧对这类失败恒回 UNKNOWN，无上限会无限重发；运营在明细里看得见，手动重来。

这两条对两个模式一视同仁，单独补会让两个模式分叉。
