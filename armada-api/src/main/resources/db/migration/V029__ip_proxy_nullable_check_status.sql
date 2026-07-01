ALTER TABLE ip_proxy
    MODIFY COLUMN check_status TINYINT NULL DEFAULT NULL COMMENT '检测生命周期:NULL=未检测 0=检测中 1=成功 2=失败',
    MODIFY COLUMN whatsapp_check_status TINYINT NULL DEFAULT NULL COMMENT 'WhatsApp连通性检测生命周期:NULL=未检测 0=检测中 1=成功 2=失败';
