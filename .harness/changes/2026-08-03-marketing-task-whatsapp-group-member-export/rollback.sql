-- 仅供已确认回滚窗口内由运维执行；正常发布禁止手工绕过 Flyway。
DROP TABLE IF EXISTS whatsapp_group_member_state;
DROP TABLE IF EXISTS whatsapp_group_member_cache;
DROP TABLE IF EXISTS whatsapp_group_member_join_fact;
DROP TABLE IF EXISTS whatsapp_group_departed_member;
