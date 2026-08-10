# India Community 30-Group Batch Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在第一套测试环境中，按 10 个建群账号三轮串行创建 `India Community 02` 至 `India Community 31`，每群加入并提升两个正常管理员、设置三项群权限、批量拉入同一份辅助账号快照，并在累计 10 个计划群失败时停止。

**Architecture:** 使用一个只存在于 `/private/tmp` 的 Bash 编排脚本，通过 SSH 在第一套测试后端主机内读取 Armada 数据库状态并调用 Android 原生协议 HTTP 接口。脚本按计划群生成唯一群名，以 JSONL 账本保存脱敏检查点；每轮独立执行和复核，避免单个长连接失效后丢失已完成状态。

**Tech Stack:** Bash 5、OpenSSH、MySQL CLI、curl、jq、Armada MySQL、Android 原生 `/ws/v1/groups/*` 接口。

## Global Constraints

- 目标只允许是第一套测试环境 `ec2-65-2-123-53.ap-south-1.compute.amazonaws.com`。
- 私钥只从本地 `测试pem/dev-1.pem` 读取，绝不复制、提交或输出。
- 不修改代码、数据库行、服务配置或部署版本；数据库操作全部为 `SELECT`。
- `India Community 01` 不计入本批，且不得修改。
- 计划群固定为 `India Community 02` 至 `India Community 31`。
- 10 个建群账号按 Armada 账号 ID 升序固定轮转三轮。
- 两个备用管理员只从分组 `113` 的生命周期正常账号中冻结；两个封禁账号永久排除。
- 辅助账号在启动时按分组 `110`、生命周期正常、登录态在线冻结一次；58 或 59 个都可接受。
- 全程严格串行；第一、第二轮各处理 10 个计划群后等待 20 秒。
- 一个计划群最多累计一次失败；累计失败达到 10 个群时停止。
- 不自动重复任何协议写请求，不自动补拉、修复或删除失败群。
- 跳过“发送消息记录”和“通过链接邀请”。
- 运行日志不得包含完整手机号、凭据、邀请码、邀请链接、数据库密码或 API 密钥。

---

### Task 1: 创建可离线检查的批次编排脚本

**Files:**
- Create: `/private/tmp/armada-india-community-30-run.sh`
- Reference: `docs/superpowers/specs/2026-08-10-india-community-30-group-batch-design.md`

**Interfaces:**
- Consumes: `--mode dry-run|live|verify`、`--from-item 1..30`、`--to-item 1..30`、`--ledger <absolute-path>`。
- Produces: 每个计划群一条 JSONL 结果；退出码 `0` 表示指定范围处理完，`10` 表示累计失败达到 10，`20` 表示启动前置条件不满足，`30` 表示账本或脚本自身错误。

- [ ] **Step 1: 使用 `apply_patch` 创建脚本骨架**

脚本必须以如下结构开头，并且不得把密码或手机号写入日志：

```bash
#!/usr/bin/env bash
set -euo pipefail

TASK_REMOTE_HOST="ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com"
TASK_KEY_PATH="/Users/daishuaishuai/IdeaProjects/测试pem/dev-1.pem"
TASK_ANDROID_BASE_URL="http://172.31.13.65:9100"
TASK_CREATOR_GROUP_ID=135
TASK_ADMIN_GROUP_ID=113
TASK_AUX_GROUP_ID=110
TASK_FIRST_SUBJECT_NO=2
TASK_PLANNED_COUNT=30
TASK_FAILURE_LIMIT=10
TASK_ROUND_SIZE=10
TASK_ROUND_PAUSE_SECONDS=20

```

后续步骤在这些常量之后加入完整实现，函数名固定为 `task_parse_args`、`task_remote_read`、`task_freeze_snapshot`、`task_load_or_create_ledger`、`task_preflight_subjects`、`task_status_ok`、`task_find_group_by_subject`、`task_post_once`、`task_verify_group`、`task_record_item`、`task_failure_count`、`task_run_item`、`task_snapshot_group_states` 和 `task_main`。全部函数定义完成后，文件末尾必须使用下面的 guard，使离线测试可以安全 `source` 脚本而不触发网络或协议写操作：

```bash
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  task_main "$@"
fi
```

- [ ] **Step 2: 实现参数、映射和快照函数**

`task_freeze_snapshot` 必须用以下三条只读 SQL 一次生成建群、管理员和辅助账号数组，不允许每群重新选号。SQL 结果中的手机号只保存在进程内：

```sql
SELECT a.id, a.ws_phone
FROM account a
JOIN account_state s ON s.account_id = a.id
WHERE a.deleted_at IS NULL
  AND a.account_group_id = 135
  AND s.login_state = 1
  AND COALESCE(s.account_state, 2) = 2
ORDER BY a.id;

SELECT a.id, a.ws_phone
FROM account a
JOIN account_state s ON s.account_id = a.id
WHERE a.deleted_at IS NULL
  AND a.account_group_id = 113
  AND s.login_state = 1
  AND COALESCE(s.account_state, 2) = 2
ORDER BY a.id;

SELECT a.id, a.ws_phone
FROM account a
JOIN account_state s ON s.account_id = a.id
WHERE a.deleted_at IS NULL
  AND a.account_group_id = 110
  AND s.login_state = 1
  AND COALESCE(s.account_state, 2) = 2
ORDER BY a.id;
```

第一次 dry-run 时还必须把冻结账号 ID 写入一条 `BATCH_SNAPSHOT` 账本记录。后续轮次从账本读取 `creatorAccountIds`、`adminAccountIds` 和 `auxiliaryAccountIds`，再用只读 SQL 解析当前手机号；不得按在线状态重新选号，也不得把完整手机号持久化：

```json
{
  "recordType": "BATCH_SNAPSHOT",
  "creatorAccountIds": [1116, 1117, 1118, 1119, 1120, 1121, 1122, 1123, 1124, 1125],
  "adminAccountIds": [888, 892],
  "auxiliaryAccountIds": [1, 2, 3],
  "createdAt": 1786330000000
}
```

计划群映射公式必须固定：

```bash
task_item_round()       { echo $((($1 - 1) / 10 + 1)); }
task_item_round_pos()   { echo $((($1 - 1) % 10 + 1)); }
task_item_subject_no()  { echo $(($1 + 1)); }
task_item_subject()     { printf 'India Community %02d' "$(task_item_subject_no "$1")"; }
task_item_creator_idx() { echo $((($1 - 1) % 10)); }
```

- [ ] **Step 3: 实现只发送一次的协议写函数**

`task_post_once <label> <url> <body>` 只能调用一次 curl。返回体只在内存中交给调用者解析，不得原样打印；输出日志只允许包含 `label`、HTTP/协议码和脱敏账号 ID。

需要映射的 Android 原生接口：

```text
POST /ws/v1/groups/create/{creatorPhone}
POST /ws/v1/groups/admin/set/{creatorPhone}
POST /ws/v1/groups/settings/sendmessage/{creatorPhone}
POST /ws/v1/groups/settings/join-mode/{creatorPhone}
POST /ws/v1/groups/settings/approval/{creatorPhone}
POST /ws/v1/groups/members/add/{creatorPhone}
POST /ws/v1/groups/members/{creatorPhone}
GET  /ws/v1/groups/list/{creatorPhone}
GET  /ws/v1/auth/status/{phone}
```

- [ ] **Step 4: 实现结果未知对账和群 ID 归一化**

`task_find_group_by_subject` 必须只接受唯一匹配；Android 群列表返回裸数字 `group_id` 时追加 `@g.us`：

```bash
task_normalize_group_jid() {
  local task_value=$1
  if [[ "$task_value" == *@g.us ]]; then
    printf '%s\n' "$task_value"
  elif [[ "$task_value" =~ ^[0-9]+$ ]]; then
    printf '%s@g.us\n' "$task_value"
  else
    return 1
  fi
}
```

建群请求结果未知时只查询群列表：找到唯一群则恢复执行；零个或多个匹配都记失败，禁止再次建群。

- [ ] **Step 5: 实现单群状态机**

`task_run_item <itemNo>` 必须依次执行且在本群首次失败后停止剩余写操作：

```text
PREFLIGHT_ACCOUNT_STATUS
RECONCILE_OR_CREATE
PROMOTE_ADMIN_1
PROMOTE_ADMIN_2
SET_SEND_MESSAGES_TRUE
SET_ADD_MEMBERS_TRUE
SET_JOIN_APPROVAL_FALSE
BATCH_ADD_FROZEN_AUXILIARIES
READBACK_VERIFY
RECORD_RESULT
```

账号原生状态首次异常时等待 15 秒并只读复查一次；仍异常才记本群失败。

- [ ] **Step 6: 实现严格成功判定**

`task_verify_group` 只有在以下 jq 断言全部成立时返回成功：

```text
matching subject count == 1
group JID ends with @g.us
member count == frozen auxiliary count + 3
auxiliary membership intersection count == frozen auxiliary count
verified admin role count == 2
announce_only == false
member_add_mode == all_member_add
group_join_state == off
```

读取 `announce_only` 时必须保留布尔 `false`，不能使用会把 `false` 当作缺失值的 `//` 链：

```jq
if has("announce_only") then .announce_only
elif has("AnnounceOnly") then .AnnounceOnly
else "UNKNOWN"
end
```

- [ ] **Step 7: 实现脱敏账本与累计失败停止**

每条 JSONL 必须符合如下结构，字段值缺失时写 `null`，不得写完整手机号：

```json
{
  "recordType": "ITEM",
  "itemNo": 1,
  "round": 1,
  "roundPosition": 1,
  "subject": "India Community 02",
  "creatorAccountId": 1116,
  "groupJid": "120363000000000000@g.us",
  "status": "SUCCESS",
  "failedStep": null,
  "protocolCode": "0",
  "requestedAuxiliaries": 59,
  "verifiedAuxiliaries": 59,
  "verifiedAdmins": 2,
  "announceOnly": false,
  "memberAddMode": "all_member_add",
  "groupJoinState": "off",
  "startedAt": 1786330000000,
  "finishedAt": 1786330009000
}
```

`task_failure_count` 只按 `recordType == "ITEM" and status == "FAILED"` 的 JSONL 行计数。Task 2 增加并测试 `task_should_stop`，由它在失败数达到 10 时返回真，主流程据此使用退出码 `10` 停止。

每次协议写成功后还必须立即追加一条 `ITEM_CHECKPOINT`，至少包含 `itemNo`、`completedStep`、`groupJid` 和 `recordedAt`：

```json
{
  "recordType": "ITEM_CHECKPOINT",
  "itemNo": 1,
  "completedStep": "PROMOTE_ADMIN_1",
  "groupJid": "120363000000000000@g.us",
  "recordedAt": 1786330003000
}
```

恢复执行时先查本 item 的最后检查点，只从下一步继续，禁止重放已经完成的写步骤。若群名已唯一存在但账本没有本 item 的任何检查点，只执行完整只读验证：验证通过则直接写 `SUCCESS`；验证不完整则写 `FAILED`、`failedStep=PREEXISTING_WITHOUT_CHECKPOINT`，不得猜测或补写。

- [ ] **Step 8: 运行 Bash 语法检查**

Run:

```bash
bash -n /private/tmp/armada-india-community-30-run.sh
```

Expected: exit `0`，无输出。

### Task 2: 为调度、失败计数和 JSON 解析建立离线测试

**Files:**
- Create: `/private/tmp/armada-india-community-30-test.sh`
- Test: `/private/tmp/armada-india-community-30-run.sh`

**Interfaces:**
- Consumes: 编排脚本中的纯函数与 fixture JSON。
- Produces: `PASS mapping`、`PASS failure-limit`、`PASS jid-normalization`、`PASS false-preservation`、`PASS reconcile-unique`。

- [ ] **Step 1: 使用 `apply_patch` 创建离线测试脚本**

测试必须覆盖以下断言：

```bash
[[ "$(task_item_subject 1)" == "India Community 02" ]]
[[ "$(task_item_subject 10)" == "India Community 11" ]]
[[ "$(task_item_subject 11)" == "India Community 12" ]]
[[ "$(task_item_subject 30)" == "India Community 31" ]]
[[ "$(task_item_creator_idx 1)" == "0" ]]
[[ "$(task_item_creator_idx 10)" == "9" ]]
[[ "$(task_item_creator_idx 11)" == "0" ]]
[[ "$(task_normalize_group_jid 120363412732996120)" == "120363412732996120@g.us" ]]
```

fixture 账本需包含同一群多个错误字段，但只能计一次失败；第 9 个失败后允许继续，第 10 个失败后返回停止。
fixture 还必须包含 `CREATE`、`PROMOTE_ADMIN_1` 和 `SET_SEND_MESSAGES_TRUE` 检查点，断言恢复函数返回 `SET_ADD_MEMBERS_TRUE`，不会返回任何已完成步骤。

- [ ] **Step 2: 先运行测试并确认新的停止判定测试失败**

Run:

```bash
bash /private/tmp/armada-india-community-30-test.sh
```

Expected: 非零退出，错误明确指向尚未定义的 `task_should_stop`；映射、JID 归一化、布尔保真和唯一匹配断言不得失败。

- [ ] **Step 3: 实现最小停止判定函数**

把以下函数加入编排脚本；测试必须继续不依赖网络、数据库、SSH 或真实手机号：

```bash
task_should_stop() {
  local task_ledger=$1
  [[ "$(task_failure_count "$task_ledger")" -ge "$TASK_FAILURE_LIMIT" ]]
}
```

- [ ] **Step 4: 再次运行离线测试**

Run:

```bash
bash /private/tmp/armada-india-community-30-test.sh
```

Expected:

```text
PASS mapping
PASS failure-limit
PASS jid-normalization
PASS false-preservation
PASS reconcile-unique
```

- [ ] **Step 5: 再运行语法检查**

Run:

```bash
bash -n /private/tmp/armada-india-community-30-run.sh
bash -n /private/tmp/armada-india-community-30-test.sh
```

Expected: 两条命令均 exit `0`。

### Task 3: 第一套测试环境只读预检与快照冻结

**Files:**
- Execute: `/private/tmp/armada-india-community-30-run.sh`
- Create at runtime: `/private/tmp/armada-india-community-30-20260810-batch01.jsonl`

**Interfaces:**
- Consumes: 第一套测试环境只读数据库状态、Android 原生账号状态和群列表。
- Produces: 冻结快照摘要、30 个群名冲突摘要、三个分组基线状态、账本绝对路径。

- [ ] **Step 1: 运行 dry-run 预检**

Run:

```bash
/private/tmp/armada-india-community-30-run.sh \
  --mode dry-run \
  --from-item 1 \
  --to-item 30 \
  --ledger /private/tmp/armada-india-community-30-20260810-batch01.jsonl
```

Expected:

```text
PRECHECK target=first-test creators=10 admins=2 auxiliaries=59
PRECHECK running_normal_group_tasks=0
PRECHECK existing_unique_subjects=0
PRECHECK ambiguous_subjects=0
PRECHECK writes_sent=0
```

其中 `auxiliaries` 必须输出运行时实际正整数；示例中的 `59` 也允许实际为 `58`。
计划群名不存在时进入待创建队列；唯一存在时不算冲突，记录在 `existing_unique_subjects` 并进入只读对账；同一建群账号下出现多个同名群时计入 `ambiguous_subjects`，其值必须为 `0` 才能进入 live 模式。

- [ ] **Step 2: 核对快照约束**

必须确认：

```text
creator count == 10
admin count == 2
auxiliary count > 0
all 10 creators native status Code == 0
both admins native status Code == 0
no active normal_group_creation_task
```

任一条件不成立则退出码 `20`，不得进入 live 模式。

- [ ] **Step 3: 保存批次基线状态**

账本写入一条 `BATCH_BASELINE` 记录，只包含三个分组的总数、在线、待重连、封禁、解绑和冻结辅助人数。

### Task 4: 串行执行第一轮 10 个计划群

**Files:**
- Execute: `/private/tmp/armada-india-community-30-run.sh`
- Append: `/private/tmp/armada-india-community-30-20260810-batch01.jsonl`

**Interfaces:**
- Consumes: Task 3 的冻结快照和账本。
- Produces: item `1..10` 的结果、第一轮状态快照、20 秒轮次等待证据。

- [ ] **Step 1: 执行 item 1 至 10**

Run:

```bash
/private/tmp/armada-india-community-30-run.sh \
  --mode live \
  --from-item 1 \
  --to-item 10 \
  --ledger /private/tmp/armada-india-community-30-20260810-batch01.jsonl
```

Expected: 每个 item 输出一行脱敏进度；每个成功写步骤先落 `ITEM_CHECKPOINT` 再进入下一步；脚本严格串行，不打印请求体或手机号。

- [ ] **Step 2: 核对第一轮账本**

Run:

```bash
jq -s '{processed: map(select(.recordType=="ITEM" and .itemNo>=1 and .itemNo<=10))|length,
        success: map(select(.recordType=="ITEM" and .itemNo>=1 and .itemNo<=10 and .status=="SUCCESS"))|length,
        failed: map(select(.recordType=="ITEM" and .itemNo>=1 and .itemNo<=10 and .status=="FAILED"))|length}' \
  /private/tmp/armada-india-community-30-20260810-batch01.jsonl
```

Expected: `processed == 10`，且 `success + failed == 10`。若累计失败为 10，停止并跳到 Task 7。

- [ ] **Step 3: 记录轮次状态并等待 20 秒**

脚本追加 `ROUND_SNAPSHOT` 记录后执行：

```bash
sleep 20
```

Expected: 日志包含 `ROUND 1 pause_completed_seconds=20`。

### Task 5: 串行执行第二轮 10 个计划群

**Files:**
- Execute: `/private/tmp/armada-india-community-30-run.sh`
- Append: `/private/tmp/armada-india-community-30-20260810-batch01.jsonl`

**Interfaces:**
- Consumes: 同一冻结快照、item `1..10` 账本与累计失败数。
- Produces: item `11..20` 的结果、第二轮状态快照、20 秒轮次等待证据。

- [ ] **Step 1: 确认累计失败少于 10**

Run:

```bash
jq -s '[.[] | select(.recordType=="ITEM" and .status=="FAILED")] | length' \
  /private/tmp/armada-india-community-30-20260810-batch01.jsonl
```

Expected: `0..9`。如果为 `10`，不执行第二轮。

- [ ] **Step 2: 执行 item 11 至 20**

Run:

```bash
/private/tmp/armada-india-community-30-run.sh \
  --mode live \
  --from-item 11 \
  --to-item 20 \
  --ledger /private/tmp/armada-india-community-30-20260810-batch01.jsonl
```

Expected: 对已有 item 使用逐步骤账本检查点，从最后完成步骤的下一步恢复且不重复执行；累计失败达到 10 时立即停止。

- [ ] **Step 3: 核对第二轮账本并等待 20 秒**

Expected: item `11..20` 中已处理项没有重复记录；未触发失败上限时处理数为 10。追加第二轮状态快照后等待 20 秒，日志包含 `ROUND 2 pause_completed_seconds=20`。

### Task 6: 串行执行第三轮 10 个计划群

**Files:**
- Execute: `/private/tmp/armada-india-community-30-run.sh`
- Append: `/private/tmp/armada-india-community-30-20260810-batch01.jsonl`

**Interfaces:**
- Consumes: 同一冻结快照、item `1..20` 账本与累计失败数。
- Produces: item `21..30` 的结果和第三轮状态快照。

- [ ] **Step 1: 确认累计失败少于 10**

Expected: 失败数为 `0..9`；达到 `10` 时不执行第三轮。

- [ ] **Step 2: 执行 item 21 至 30**

Run:

```bash
/private/tmp/armada-india-community-30-run.sh \
  --mode live \
  --from-item 21 \
  --to-item 30 \
  --ledger /private/tmp/armada-india-community-30-20260810-batch01.jsonl
```

Expected: item `21..30` 严格串行；第三轮结束后不额外 sleep。

- [ ] **Step 3: 记录第三轮状态快照**

Expected: 账本包含 `round == 3` 的 `ROUND_SNAPSHOT`，但没有 `pause_completed_seconds`。

### Task 7: 全批独立回读与最终汇总

**Files:**
- Read: `/private/tmp/armada-india-community-30-20260810-batch01.jsonl`
- Execute read-only: `/private/tmp/armada-india-community-30-run.sh`

**Interfaces:**
- Consumes: 所有已处理 item 的账本、实时群列表、群成员明细和账号分组状态。
- Produces: 最终成功/失败/未处理汇总、成功群独立回读结果、三个分组状态变化和账本路径。

- [ ] **Step 1: 汇总账本完整性**

Run:

```bash
jq -s '{processed: map(select(.recordType=="ITEM"))|length,
        success: map(select(.recordType=="ITEM" and .status=="SUCCESS"))|length,
        failed: map(select(.recordType=="ITEM" and .status=="FAILED"))|length,
        duplicateItems: ([map(select(.recordType=="ITEM"))|group_by(.itemNo)[]|select(length>1)]|length)}' \
  /private/tmp/armada-india-community-30-20260810-batch01.jsonl
```

Expected: `duplicateItems == 0`；`processed <= 30`；执行期首次累计到 10 个失败后不再出现更大 itemNo 的写操作；未触发失败上限时 `processed == 30`。最终独立审计若发现新的不一致，可以让最终失败数大于 10，但必须单独标明为审计后重分类。

- [ ] **Step 2: 对每个成功群重新只读验证**

使用 `--mode verify` 只读入口重新检查每个 `SUCCESS` 群：唯一名称、群 JID、成员总数、辅助覆盖数、两个管理员角色、`announce_only=false`、`member_add_mode=all_member_add`、`group_join_state=off`。

Expected: 所有最终仍标记为 `SUCCESS` 的群通过独立回读；不一致项改为 `FAILED_AFTER_VERIFY`，每个群仍只计一次失败。

- [ ] **Step 3: 对比账号分组状态**

读取分组 `110`、`113`、`135` 的最终总数、在线、待重连、封禁和解绑，与 `BATCH_BASELINE` 对比。只报告事实，不推断封禁因果。

- [ ] **Step 4: 输出最终交付摘要**

最终回复必须包含：

```text
planned / processed / success / failed / unprocessed
stoppedBecauseFailureLimit: true|false
round 1/2/3 summaries
failed subject + failedStep + stable code
account-group state deltas
absolute ledger path
```

不得声称完成，除非 Task 7 的独立回读命令刚刚执行且输出支持该结论。
