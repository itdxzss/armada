ALTER TABLE account_state
    MODIFY COLUMN login_state TINYINT DEFAULT NULL
    COMMENT '1在线 2离线 3待上线;NULL=未上报/未发起上线';
