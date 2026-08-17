-- 为 wa_group_profile 的 7 个可独立更新群资料字段增加逐字段版本列。
--
-- 举证（群数据模型设计 2026-08-15 第 925 行要求 field_version_keys 单独举证评审）：
-- 整行水位 metadata_observed_at 无法承载多字段乱序 patch。可复现场景——
--   t1 用户改群名，t2 用户改群描述（t2 > t1）；
--   描述事件先到，落库并把整行水位推到 t2；
--   群名事件后到，occurredAt = t1 < 水位 t2，被整体判为旧事件丢弃；
--   结果群名永久停留在旧值，只能靠人工刷新纠正。
-- 群变更事件直投影（2026-08-16）让该场景由边角变成常态，故本迁移落地逐字段版本。
--
-- 形态沿用 wa_group_participant 的 presence/role 逐字段版本模式：每字段两列（来源 +
-- 事实时间），决胜比较全部在 upsert SQL 内完成，不引入行锁，与批量 upsert 兼容。这与
-- 原设计的 JSON + canonical binary version key 不同：后者要求"锁定单行后"在 Java 比较，
-- 会打掉现有写入路径的无锁性质，且 base64 的字典序不等于原字节序，SQL 内无法直接比较。
--
-- 逐字段 event_id 不落列：同事实时间且同来源的概率极低，此时退化为先到先赢而非确定性
-- total order，换取行宽只增长约 280 字节而非 1785 字节（7 × VARCHAR(255)）。幂等追溯
-- 继续依赖整行级 metadata_observed_at 与事件日志。
--
-- 列一律追加到表末尾而不使用 AFTER：MySQL 8.0.12+ 仅在追加到末尾时可走 INSTANT
-- ADD COLUMN，使用 AFTER 会退化为 INPLACE/COPY 重建整表。14 列合并为一条 ALTER，
-- 只做一次 INSTANT 变更。
--
-- 来源取值与 wa_group_participant 同风格的大写 ascii 枚举，按可信度分级由 upsert SQL
-- 内的 CASE 表达式决定；本迁移不写入数据，存量行全部为 NULL，表示该字段尚无版本事实，
-- 任何带版本的新事件都能覆盖它。

ALTER TABLE wa_group_profile
    ADD COLUMN subject_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        DEFAULT NULL COMMENT 'subject来源',
    ADD COLUMN subject_observed_at BIGINT DEFAULT NULL
        COMMENT 'subject事实时间(epoch毫秒)',
    ADD COLUMN description_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        DEFAULT NULL COMMENT 'description来源',
    ADD COLUMN description_observed_at BIGINT DEFAULT NULL
        COMMENT 'description事实时间(epoch毫秒)',
    ADD COLUMN announce_only_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        DEFAULT NULL COMMENT 'announce_only来源',
    ADD COLUMN announce_only_observed_at BIGINT DEFAULT NULL
        COMMENT 'announce_only事实时间(epoch毫秒)',
    ADD COLUMN admin_only_edit_info_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        DEFAULT NULL COMMENT 'admin_only_edit_info来源',
    ADD COLUMN admin_only_edit_info_observed_at BIGINT DEFAULT NULL
        COMMENT 'admin_only_edit_info事实时间(epoch毫秒)',
    ADD COLUMN member_add_mode_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        DEFAULT NULL COMMENT 'member_add_mode来源',
    ADD COLUMN member_add_mode_observed_at BIGINT DEFAULT NULL
        COMMENT 'member_add_mode事实时间(epoch毫秒)',
    ADD COLUMN join_approval_mode_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        DEFAULT NULL COMMENT 'join_approval_mode来源',
    ADD COLUMN join_approval_mode_observed_at BIGINT DEFAULT NULL
        COMMENT 'join_approval_mode事实时间(epoch毫秒)',
    ADD COLUMN ephemeral_duration_seconds_source VARCHAR(32) CHARACTER SET ascii
        COLLATE ascii_bin DEFAULT NULL COMMENT 'ephemeral_duration_seconds来源',
    ADD COLUMN ephemeral_duration_seconds_observed_at BIGINT DEFAULT NULL
        COMMENT 'ephemeral_duration_seconds事实时间(epoch毫秒)';
