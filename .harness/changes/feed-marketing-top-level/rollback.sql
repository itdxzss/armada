-- 仅在确认回退目标环境后执行；恢复 V172 的目录层级与排序。
UPDATE sys_menu feed
JOIN sys_menu task_center
  ON task_center.tenant_id = feed.tenant_id
 AND task_center.menu_key = 'TaskCenter'
SET feed.parent_id = task_center.id,
    feed.sort_no = 60
WHERE feed.menu_key = 'TaskFeedMarketing'
  AND feed.menu_type = 'D';
