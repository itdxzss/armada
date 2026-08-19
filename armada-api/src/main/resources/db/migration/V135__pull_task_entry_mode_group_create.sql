-- 新群模式：pull_task_group_account.entry_mode 新增取值 4 = 建群时作为初始成员加入。
--
-- 站台有两种进群方式并存（ADR-0012）：
--   3 = 拉手拉入：由拉手在每次拉人调用中与料子同批加入，沿用 ADR-0006 既有语义；
--   4 = 建群时作为初始成员加入：随建群调用一次性进群，新群模式专有。
--
-- 实现时的硬约束：建群回执明确成功的初始站台必须写 membership_status=IN_GROUP。
-- PullTaskStationSelectionService 的 reusableStation 判定要求 membership_status=NOT_JOINED
-- 才允许复用；初始站台若写成 NOT_JOINED，会被后续拉人调用重新选中并重复提交同一个号。
--
-- 本脚本只更新列注释：entry_mode 已是 TINYINT，取值 4 无需扩容；
-- 唯一键与索引均不变，也不触碰任何业务数据。写法参照 V119。

SET @pull_task_entry_mode_comment_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_account'
       AND column_name = 'entry_mode') = 1,
    'ALTER TABLE pull_task_group_account MODIFY COLUMN entry_mode TINYINT DEFAULT NULL COMMENT ''进群方式:1=踩链接 2=管理员邀请 3=拉手拉入 4=建群时作为初始成员加入;站台补充为NULL''',
    'SELECT 1'
);
PREPARE pull_task_entry_mode_comment_stmt FROM @pull_task_entry_mode_comment_sql;
EXECUTE pull_task_entry_mode_comment_stmt;
DEALLOCATE PREPARE pull_task_entry_mode_comment_stmt;
