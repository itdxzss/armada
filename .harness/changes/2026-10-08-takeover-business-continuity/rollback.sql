-- 仅供人工审查后回滚 V215；先回滚不再访问这些结构的应用版本。
-- 两个配置开关关闭即可回退行为，无需执行结构回滚；执行本脚本会删除熔断历史。
DROP TABLE IF EXISTS account_takeover_breaker;
ALTER TABLE account_state DROP COLUMN offline_since;
