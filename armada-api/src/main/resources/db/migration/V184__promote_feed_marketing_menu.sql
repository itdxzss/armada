-- 动态营销提升为一级目录，排在任务中心（30）与素材管理（40）之间。
-- 保留菜单 ID、页面路由及子菜单，已有角色授权继续引用原菜单。
UPDATE sys_menu
SET parent_id = 0,
    sort_no = 35
WHERE menu_key = 'TaskFeedMarketing'
  AND menu_type = 'D';
