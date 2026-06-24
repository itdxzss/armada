# account 数据模型重设计 —— 待决(WIP)

> 已做:读 wheel 代码逐列定生死(workflow `w0y5u6alc`,9 agent)。3 个决策待拍——**先 park,做完营销模板样板再回来定。**

## 一、死列(删,不迁;约 15)
`source`、`dispatched_by`、`ws_version`、`nickname`、`country`(真国家走 proxy_country)、`worker_id`、`owner_endpoint`、`proxy_id`、`capping_status/remaining/cycle_end`、`risk_start_time`、`successful_interactions`、`account.role`、`account.tag_id`、`account_tag(表)`、`ip_type`(已 DROP)。
依据:恒 NULL 占位 / 写了从不读 / 功能未接。

## 二、★死列但一期 UI 要它(wheel 的"假字段",armada 需真建或占位)
| 需求字段 | 列 | wheel 现状 |
|---|---|---|
| 头像 | avatar_url | 恒 NULL(同步没接) |
| 好友/群 | friends_num/groups_num | 恒 0(无回写真值) |
| 超链寿命 | hyperlink_sent_count | 恒 0(功能未上线) |
| 入库时间 | first_login_time | 恒 NULL(可改用 created_at) |
| IP来源 | proxy_asn | 只写不读(需求要展示) |

## 三、三镜像裁决(代码实证,可安全归一)
`account.role` 恒 null,角色是**任务执行时按分组分配**(TaskTemplate 绑角色→分组,`resolveTagIdForRole` 选号);每号单组(导入 `List.of(groupId)`)。
→ 归一为单 `account_group_id`;删 `tag_id` / `role` / `account_tag`(选号 SQL 的 tag_id 过滤改读 account_group_id)。

## 四、拆分建议(按"写入频率"拆,隔离高频 Kafka 回写)
```
account                身份/归属(冻结低频):ws_phone account_type number_source channel_name
                       ownership lease_until dispatched_at remark priority account_group_id active_phone_key
account_state          生命周期(高频Kafka):account_state login_state last_state_sync_time state_source block_reason invalidated_at
account_risk           风控禁言:risk_status risk_end_time cooldown_until mute_status
account_protocol_link  协议身份:protocol_id protocol_account_id protocol_address
account_proxy_runtime  代理运行态:truth_ip proxy_country proxy_failure_count (ip_group_name?)
```
pull_into_group_count 单列,留主表或 account_counter;不变:account_credential / account_group / account_history / import_batch/detail。

## 五、待拍 3 决策
1. 第二类 5 个"需求要但 wheel 没建"字段:armada 一期**真建**(接协议层回写)vs 先占位二期补?
2. 拆分:5 表方案(主表+4子表)vs 更少表(只拆 state)?
3. 三镜像归一(删 tag_id/role/account_tag)确认?
