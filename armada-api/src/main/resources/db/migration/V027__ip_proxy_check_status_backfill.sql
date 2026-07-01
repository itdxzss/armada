UPDATE ip_proxy
SET check_status = 2,
    whatsapp_check_status = 2
WHERE check_status = 0
  AND whatsapp_check_status = 0;

ALTER TABLE ip_proxy
    ALTER COLUMN check_status SET DEFAULT 2,
    ALTER COLUMN whatsapp_check_status SET DEFAULT 2;
