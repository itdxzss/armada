-- 通讯录同步状态新增 PARTIAL 取值。
-- 协议层判定快照不完整（强制 resync 中途超时），或 armada 收到的分片没收齐时，
-- 只 upsert 不删除残留行，状态记 PARTIAL —— 宁可留几条脏数据，
-- 也不能因为快照不全把号主的通讯录删掉一半。
-- 只改列注释，不改类型与列宽。

ALTER TABLE account_contact_sync
    MODIFY COLUMN sync_status VARCHAR(16) NOT NULL DEFAULT 'NEVER'
    COMMENT '同步状态:NEVER SYNCING SUCCESS FAILED PARTIAL(快照不完整,已入库未清残留)';
