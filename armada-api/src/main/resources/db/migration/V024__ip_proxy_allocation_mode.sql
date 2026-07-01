ALTER TABLE ip_proxy
    ADD COLUMN allocation_mode VARCHAR(16) NOT NULL DEFAULT 'smart'
        COMMENT '分配方式:smart=智能分配 mixed=混合分组' AFTER ownership;
