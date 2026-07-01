ALTER TABLE ip_proxy
    ADD COLUMN check_status TINYINT NOT NULL DEFAULT 0
        COMMENT '检测生命周期:0=检测中 1=成功 2=失败' AFTER last_check_error,
    ADD COLUMN whatsapp_check_status TINYINT NOT NULL DEFAULT 0
        COMMENT 'WhatsApp连通性检测生命周期:0=检测中 1=成功 2=失败' AFTER check_status,
    ADD COLUMN whatsapp_http_status INT DEFAULT NULL
        COMMENT 'WhatsApp探测HTTP状态码' AFTER whatsapp_check_status,
    ADD COLUMN whatsapp_check_error VARCHAR(512) DEFAULT NULL
        COMMENT 'WhatsApp连通性检测失败原因' AFTER whatsapp_http_status;

UPDATE ip_proxy
SET check_status = 2,
    whatsapp_check_status = 2
WHERE check_status = 0
  AND whatsapp_check_status = 0;

ALTER TABLE ip_proxy
    ALTER COLUMN check_status SET DEFAULT 2,
    ALTER COLUMN whatsapp_check_status SET DEFAULT 2;
