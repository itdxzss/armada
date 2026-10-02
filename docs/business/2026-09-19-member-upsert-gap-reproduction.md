# 成员 UPSERT 间隙锁：本地复现与原因隔离

日期：2026-09-19。用户要求复现已取得的 perf2 成员通知死锁并判断具体原因。本轮仅运行本地隔离实验，没有修改业务代码、连接线上进行实验或部署。

## 结论

**两个事务各执行一条成员 UPSERT，就足以复现与线上报告相同的 LID 唯一索引间隙锁环路。无需 FOR UPDATE、身份合并函数、任务主表更新或第三个事务。**

已确认的触发组合：

1. 已有成员行包含 PN 和 phone，但 LID 为 NULL。
2. 两个通知同时携带相同的 LID、phone，PN 为 NULL。
3. INSERT 尝试写新行，却在 phone 唯一键上命中原成员，随后通过 `ON DUPLICATE KEY UPDATE lid_jid=COALESCE(lid_jid,VALUES(lid_jid))` 补原行 LID。
4. 这条语句的插入、唯一键冲突处理和补写 LID 在两个事务间交错，形成双方持 LID gap lock、互等 insert intention 的环路。

完整 UPSERT、只保留 LID 补写的精简 UPSERT 均复现；取消 LID 补写的原因对照没有复现。按既有主键执行同样的身份/状态更新没有复现，而且结果正确。

因此先前“可能需要第三个事务回滚”的解释只是另一种充分触发条件，**不是必要条件**；不能用它解释此次实验必须发生的前置动作。

## 实验边界与环境

- 数据库：本机现有 `mysql:8.4.8` 镜像，实际 `SELECT VERSION()` 返回 8.4.8。
- 容器：`member-gap-repro-20260919`，`--network none`、无宿主数据库端口、无线上目录挂载，512 MiB 内存。
- 表：使用管理员 `SHOW CREATE TABLE wa_group_participant` 返回的实际 DDL，包括 PN、LID、phone 三个唯一索引。
- 原 SQL：从事故时已提取的部署 Mapper XML 渲染单成员 `upsertParticipantFacts`，展开 `<include>` 并加入租户列，保留全部身份/状态更新表达式；只替换成合成租户、群、账号和事件值。
- 两个 mysql 客户端先建立事务，再在同一时刻执行语句。每轮前单独准备数据；准备事务在两个并发写开始前已提交。两边写完立即提交。
- 没有 FOR UPDATE，没有第三个业务事务，没有调用 Java 身份合并函数，没有其他业务表参与。
- 本实验按数据库真实错误号和 InnoDB 报告判断，不用 H2 或 mock 模拟锁。

## 1000 轮对照结果

一轮表示两个并发事务，不是单条 SQL。概率受调度影响，下列计数为本次实际运行结果，不承诺重跑获得同样次数。

| 场景 | 隔离级别 | 轮数 | 死锁轮数 | 与线上相同的 LID gap 环路 |
| --- | --- | ---: | ---: | ---: |
| 原 SQL，已有 PN/phone、缺 LID | RR | 100 | 19 | 19 |
| 原 SQL，成员不存在 | RR | 100 | 0 | 0 |
| 原 SQL，已有 LID/phone | RR | 100 | 0 | 0 |
| 原 SQL，已有 PN/phone、缺 LID | RC | 100 | 18 | 15，另 3 为其他索引锁环路 |
| 原 SQL，成员不存在 | RC | 100 | 0 | 0 |
| 原 SQL，已有 LID/phone | RC | 100 | 0 | 0 |
| 原 SQL，仅取消 LID 补写 | RR | 100 | 0 | 0 |
| 保留全部更新表达式，改按既有主键 UPDATE | RR | 100 | 0 | 0 |
| 保留全部更新表达式，改按既有主键 UPDATE | RC | 100 | 0 | 0 |
| 精简 SQL，仅保留唯一键字段与 LID 补写 | RR | 100 | 12 | 12 |

取消 LID 补写仅用于隔离原因，会丢失身份补全，不能作为修复。RC 仍复现，不能用“改隔离级别即可修好”作结论。

## 最小问题 SQL

前置已提交数据：

```text
id=11, tenant=1, group=20, pn='10001@s.whatsapp.net', lid=NULL, phone='10001'
```

两个线程同时执行：

```sql
INSERT INTO wa_group_participant
    (tenant_id, group_id, pn_jid, lid_jid, phone, created_at, updated_at)
VALUES (1, 20, NULL, '213@lid', '10001', 1, 1)
ON DUPLICATE KEY UPDATE
    lid_jid = COALESCE(lid_jid, VALUES(lid_jid));
```

此精简 SQL 已复现 12/100 轮。它删除了全部进退群优先级、时间判断、管理员状态等复杂表达式，只保留多唯一键下从插入转为身份更新的行为。

InnoDB 在尝试插入时会逐步写索引；遇到后续唯一键冲突后，需要撤销本条语句已完成的部分插入再走更新。约束检查中的锁在记录被删除时可能继承到相邻间隙，RC 也不能一概排除此类约束锁。这解释了为何没有显式锁 SQL 仍出现 gap lock；该机制可对照 MySQL 工程师在 [Bug 116815](https://bugs.mysql.com/bug.php?id=116815) 中对部分回滚和锁继承的说明。引用该说明是解释机制，不是宣称当前版本命中了某个历史已修复 Bug。

## 与线上报告比对

线上 2026-09-18 17:14:01 报告与本地复现均为：

- 两边当前 SQL：`INSERT INTO wa_group_participant ... ON DUPLICATE KEY UPDATE`。
- 冲突索引：`uq_wa_group_participant_lid`。
- 两边持有：`lock_mode X locks gap before rec`。
- 两边等待：`lock_mode X locks gap before rec insert intention waiting`。
- 每份报告中两个事务的等待点均是同一个 LID 右边界记录。

原 SQL 的本地样例还呈现一端 `inserting`、另一端 `updating or deleting`，与线上报告一致。

本实验已经证明具体 SQL 与初始身份状态足以产生线上锁形态，**不再只是泛泛的并发猜测**。但线上旧事务的完整语句历史和写入前行快照没有保留，因此不能声称逐微秒重建了线上全部过程，也不能直接覆盖任务 109 的较早主表汇总死锁。

## 已验证的修复方向

已解析到既有成员主键时，直接按 `tenant_id + id` 更新身份和事实，避免再次尝试插入同一成员后经 phone 冲突转向 LID 更新。

对照 SQL 保留原 UPSERT 的全部 UPDATE 表达式，将 `VALUES(column)` 替换为对应通知值，最后加 `WHERE tenant_id=1 AND id=11`。RR、RC 各 100 轮均提交成功，查询结果均为：

```text
id=11, pn=10001@s.whatsapp.net, lid=213@lid, phone=10001,
presence_status=1, presence_event_id=event-1
```

这验证了当前复现场景中的改法，不是全业务实现已经修复。真正落地还需保留租户边界、PN/LID 冲突校验、晚到事件判断，并处理“初次读取不存在、随后并发插入”的分支。本轮没有改生产 Mapper 或 Java 文件。

## 脚本与证据

仓库内证据目录：`docs/business/evidence/2026-09-19-member-upsert-gap/`。

- `probe.py`：基础客户端与 9 组探索性实验；其中第三事务回滚也能产生同样环路，独立于上述两事务原因验证。
- `stress.py`：原 SQL 两线程、三种初始状态、RR/RC 共 600 轮。
- `controls.py`：去掉 LID 补写、主键更新、精简 SQL 共 400 轮。
- `schema.sql`、`upsert.sql`、`minimal-upsert.sql`、`primary-update-control.sql`：实际实验输入。
- `stress-summary.json`、`control-summary.json`、对应 progress 文件：计数与结果。
- `*-innodb.txt`：选取的三份代表性锁报告。其余报告暂存在 `/private/tmp/member-gap-repro-20260919/`；summary 中 examples 记录的是该原始目录下的文件名。

运行脚本需要本机已有 Docker 和 Python 3；客户端使用容器内 mysql。先启动同名无网络的 MySQL 8.4.8 容器并等初始化完成，再分别运行两个 Python 脚本。脚本只能连接该固定本地容器，不接受远程主机或线上凭据；会重建其 `stress_*`、`probe_*` 和对照前缀的合成数据库。
