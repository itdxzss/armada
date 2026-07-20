-- 推广模板与渠道管理数据模型人工回滚脚本。
-- 高风险提示:执行前必须确认目标环境，并备份模板、渠道及追踪配置。

-- 1. 必须先确认结果为0；否则恢复 account.channel_name VARCHAR(64) 会失败或截断。
SELECT COUNT(*) AS channel_name_too_long
FROM account
WHERE CHAR_LENGTH(channel_name) > 64;

-- 2. 解除 account 对新渠道模型的兼容结构。
ALTER TABLE account DROP INDEX idx_account_promotion_channel;
ALTER TABLE account DROP COLUMN promotion_channel_id;
ALTER TABLE account
    MODIFY COLUMN channel_name VARCHAR(64) DEFAULT NULL
        COMMENT '推广渠道名';

-- 3. 按引用反方向删除本期新表；以下操作会永久删除渠道和追踪配置。
DROP TABLE promotion_channel_tracking_config;
DROP TABLE promotion_channel;
DROP TABLE promotion_domain;
DROP TABLE promotion_landing_template;
