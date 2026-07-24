-- 跨租户手机号占用校验按 ws_phone 查询；补充前导索引，不改变既有租户级唯一规则。
CREATE INDEX idx_account_ws_phone_active ON account (ws_phone, is_active);
