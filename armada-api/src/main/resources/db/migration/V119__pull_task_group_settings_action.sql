-- 拉群群设置异步化：pull_task_account_action.action_type 新增 5=放开加人权限、6=关闭进群审核。
--
-- 这两个动作由任务管理员在提权确认后执行，各自一条 Kafka 命令、一行动作、一个结果。
-- 唯一键 uq_pull_task_action_pair 含 action_type，两行的 actor 与 target 均为管理员角色行本身，
-- 靠 action_type 区分才能共存，因此必须是两个取值而非一个取值加判别字段。
--
-- 本脚本只更新列注释：action_type 已是 TINYINT NOT NULL，取值 5、6 无需扩容；
-- 唯一键与索引均不变，也不触碰任何业务数据。

SET @pull_task_group_settings_action_comment_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_account_action'
       AND column_name = 'action_type') = 1,
    'ALTER TABLE pull_task_account_action MODIFY COLUMN action_type TINYINT NOT NULL COMMENT ''动作类型:1=保存联系人 2=邀请入群 3=踩链接入群 4=设置任务管理员 5=放开加人权限 6=关闭进群审核''',
    'SELECT 1'
);
PREPARE pull_task_group_settings_action_comment_stmt
    FROM @pull_task_group_settings_action_comment_sql;
EXECUTE pull_task_group_settings_action_comment_stmt;
DEALLOCATE PREPARE pull_task_group_settings_action_comment_stmt;
