-- 历史实时 remove 未保存操作人证据，无法可靠区分主动退群与管理员移除。
-- 新版消费端只允许携带 WGP2_ACTOR_DIFFERENT 证据的事件写入 REMOVED。
UPDATE whatsapp_group_departed_member
SET exit_type = 'UNKNOWN'
WHERE source_type = 'WGP2_NOTIFICATION'
  AND exit_type = 'REMOVED';
