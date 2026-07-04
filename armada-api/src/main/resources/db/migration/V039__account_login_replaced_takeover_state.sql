ALTER TABLE account_state
    MODIFY COLUMN account_state TINYINT NULL COMMENT '账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中';
