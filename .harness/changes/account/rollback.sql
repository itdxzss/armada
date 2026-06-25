-- 回滚 V005：按子→父顺序 DROP，避免外键约束报错
DROP TABLE IF EXISTS account_import_detail;
DROP TABLE IF EXISTS account_import_batch;
DROP TABLE IF EXISTS account_credential;
DROP TABLE IF EXISTS account_state;
DROP TABLE IF EXISTS account_group;
DROP TABLE IF EXISTS account;
