ALTER TABLE protocol_command_outbox
    ADD COLUMN protocol_backend VARCHAR(16) NOT NULL DEFAULT 'WEB'
        COMMENT '协议后端:WEB/ANDROID' AFTER protocol_account_id;
