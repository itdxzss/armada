-- 已执行的 V172 必须保持内容不变；菜单名称调整通过新迁移完成。
UPDATE sys_menu
SET menu_name = '动态发布任务'
WHERE menu_key = 'TaskFeed'
  AND menu_type = 'M'
  AND menu_name = '动态任务';
